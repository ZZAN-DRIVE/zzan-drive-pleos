package com.zzandrive.data.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zzandrive.data.api.Cancellable
import com.zzandrive.data.api.ChargeHintSample
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergySample
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.LocationSample
import com.zzandrive.data.api.VehicleSample
import com.zzandrive.data.api.VehicleSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live vehicle readings from the Pleos SDK, all labelled [DataOrigin.PLEOS_SDK].
 *
 * - SOC arrives through the documented listener; stored energy (Wh), capacity, charge port, charge switch
 *   and gear are polled every [pollMs] (these getters need only normal permissions; some are not in the
 *   public reference, so every call is wrapped).
 * - Location comes from FusedLocation once the runtime permission is granted ([startLocation]).
 * - Everything is delivered on the main thread with the time it was read.
 *
 * Verified on the Pleos 2.1.0 emulator: console battery_level 19.35
 * reads as 19,350 Wh; port/switch writes read back; SOC and energy are independent console fields.
 */
class PleosVehicleSource(
    context: Context,
    private val pollMs: Long = 2_000,
) : VehicleSource {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(VehicleSample) -> Unit>()
    private val vehicle = Vehicle(app)
    private var location: FusedLocation? = null
    private var locationListener: FusedLocationListener? = null

    @Volatile private var running = false
    @Volatile private var socPercent: Double? = null
    @Volatile private var capacityKwh: Double? = null
    @Volatile private var portOpen: Boolean? = null
    @Volatile private var switchOn: Boolean? = null

    /** Decided at start (R-10): electric when the EV battery answers, fuel when the fuel gauge does. */
    @Volatile override var energyType: EnergyType = EnergyType.ELECTRIC
        private set

    /** Human-readable status for logs and the diagnostics line, e.g. "EV (SOC 응답, 연료 미지원)". */
    @Volatile var modeReason: String = "확인 중"
        private set

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            value?.let { socPercent = it.toDouble() }
            readEnergy() // pair the SOC event with a fresh Wh reading
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "SOC listener failed: ${e.message}")
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            readAll()
            main.postDelayed(this, pollMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        runCatching { vehicle.initialize() }.onFailure { Log.w(TAG, "Vehicle.initialize failed: ${it.message}") }
        // The SDK binds to its service asynchronously; give it a moment before the first calls.
        main.postDelayed({
            detectEnergyType()
            runCatching { vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener) }
                .onFailure { Log.w(TAG, "register SOC failed: ${it.message}") }
            poll.run()
        }, 1_500)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
        stopLocation()
        runCatching { vehicle.release() }
    }

    override fun subscribe(listener: (VehicleSample) -> Unit): Cancellable {
        listeners += listener
        return Cancellable { listeners -= listener }
    }

    override fun refresh() {
        main.post { readAll() }
    }

    /** Call after FUSED_LOCATION is granted. Safe to call more than once. */
    fun startLocation() {
        if (locationListener != null) return
        val fused = FusedLocation(app)
        val supported = runCatching { fused.isSupported() }.getOrDefault(false)
        if (!supported) {
            Log.w(TAG, "FusedLocation not supported")
            return
        }
        runCatching { fused.initialize() }.onFailure { Log.w(TAG, "FusedLocation.initialize failed: ${it.message}"); return }
        val l = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                emit(LocationSample(now(), GeoPoint(location.latitude, location.longitude), DataOrigin.PLEOS_SDK))
            }

            override fun onError(exception: Exception) {
                Log.w(TAG, "location error: ${exception.message}")
            }
        }
        runCatching {
            fused.getLastKnownLocation(l)
            fused.registerFusedLocationCallback(l)
        }.onSuccess {
            location = fused
            locationListener = l
        }.onFailure { Log.w(TAG, "location subscribe failed: ${it.message}") }
    }

    private fun stopLocation() {
        val f = location ?: return
        locationListener?.let { l -> runCatching { f.unregisterFusedLocationCallback(l) } }
        runCatching { f.release() }
        location = null
        locationListener = null
    }

    private fun detectEnergyType() {
        runCatching {
            vehicle.getEvBattery().getEvBatteryStateOfCharge({ soc ->
                if (soc != null) {
                    energyType = EnergyType.ELECTRIC
                    modeReason = "EV 배터리 응답(SOC $soc%)"
                    Log.i(TAG, "mode=EV ($modeReason)")
                }
            }, { e ->
                runCatching {
                    vehicle.getFuel().getFuelPercent({ pct ->
                        if (pct != null) {
                            energyType = EnergyType.GASOLINE
                            modeReason = "연료 게이지 응답(${pct}%)"
                            Log.i(TAG, "mode=FUEL ($modeReason)")
                        }
                    }, { Log.w(TAG, "neither EV nor fuel answered: ${e.message} / ${it.message}") })
                }
            })
        }
    }

    private fun readAll() {
        readEnergy()
        readHints()
        readGear()
    }

    // These getters are deprecated in Vehicle 2.3.2 ("use the Google EV_BATTERY_LEVEL / INFO_EV_BATTERY_CAPACITY /
    // EV_CHARGE_PORT_OPEN property"). They are used on purpose now: they need only normal permissions and were verified
    // on the emulator. The AAOS CarPropertyManager path (needs the dangerous CAR_ENERGY permission) comes next.
    @Suppress("DEPRECATION")
    private fun readEnergy() {
        val ev = runCatching { vehicle.getEvBattery() }.getOrNull() ?: return
        runCatching { ev.getEvBatteryCapacity({ wh -> wh?.let { capacityKwh = it / 1000.0 } }, { }) }
        runCatching {
            ev.getEvBatteryStateOfCharge({ soc -> soc?.let { socPercent = it.toDouble() } }, { })
        }
        runCatching {
            ev.getEvBatteryLevel({ wh ->
                emit(EnergySample(now(), wh?.let { it / 1000.0 }, socPercent, capacityKwh, DataOrigin.PLEOS_SDK))
            }, { e ->
                // Level not readable: still report SOC so the SOC × capacity path can work.
                emit(EnergySample(now(), null, socPercent, capacityKwh, DataOrigin.PLEOS_SDK))
                Log.d(TAG, "level unreadable: ${e.message}")
            })
        }.onFailure {
            emit(EnergySample(now(), null, socPercent, capacityKwh, DataOrigin.PLEOS_SDK))
        }
    }

    @Suppress("DEPRECATION")
    private fun readHints() {
        val ev = runCatching { vehicle.getEvBattery() }.getOrNull() ?: return
        runCatching {
            ev.isEvChargePortOpen({ open ->
                portOpen = open
                emit(ChargeHintSample(now(), portOpen, switchOn, DataOrigin.PLEOS_SDK))
            }, { })
        }
        runCatching {
            ev.getEvChargeSwitchState({ on ->
                switchOn = on
                emit(ChargeHintSample(now(), portOpen, switchOn, DataOrigin.PLEOS_SDK))
            }, { })
        }
    }

    @Suppress("DEPRECATION") // Gear selection getter is deprecated in 2.3.2 but still answers; AAOS GEAR_SELECTION next.
    private fun readGear() {
        runCatching {
            vehicle.getGear().getCurrentGearSelectionState({ g ->
                val name = g?.toString() ?: return@getCurrentGearSelectionState
                emit(DriveSample(now(), parked = name.contains("PARK"), gear = name, origin = DataOrigin.PLEOS_SDK))
            }, { })
        }
    }

    private fun emit(sample: VehicleSample) {
        if (Looper.myLooper() == Looper.getMainLooper()) deliver(sample) else main.post { deliver(sample) }
    }

    private fun deliver(sample: VehicleSample) = listeners.forEach { it(sample) }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "ZzanPleos"
    }
}
