package com.zzandrive.probe

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

/**
 * Throwaway probe: do the Pleos SDKs respond on the emulator?
 * Pick a suite with an intent extra: `--es suite vehicle|navi|all` (default: all).
 * Results go to the screen and to logcat (tag "ZzanProbe").
 */
class ProbeActivity : Activity() {

    private lateinit var out: TextView
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(p0: Int?) = log("PUSH  EvBattery.SOC -> $p0 %")
        override fun onFailed(e: Exception) = log("PUSH  EvBattery.SOC FAILED: $e")
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(p0: Int?) = log("PUSH  Fuel.percent -> $p0 %")
        override fun onFailed(e: Exception) = log("PUSH  Fuel.percent FAILED: $e")
    }

    /** Logs every NaviHelper event we care about, so we see what the emulator actually delivers. */
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = log("NAVI  onCurrentLocationInfo lat=${info.latitude} lon=${info.longitude} addr=${info.address}")
        override fun onRouteStateInfo(info: RouteStateInfo) = log("NAVI  onRouteStateInfo routing=${info.isRouting} mode=${info.naviMode} option=${info.routeOption} terms=${info.termsAgreement}")
        override fun onRouteStarted(info: RouteStartInfo) = log("NAVI  onRouteStarted name=${info.name} distance=${info.distance} duration=${info.duration} tollFare=${info.tollFare}")
        override fun onRoutingCompleted() = log("NAVI  onRoutingCompleted")
        override fun onDestinationInfo(info: DestinationInfo) = log("NAVI  onDestinationInfo name=${info.name} remainDistance=${info.remainDistance} remainTime=${info.remainTime} tollFare=${info.tollFare}")
        override fun onRouteOptionChanged(option: RouteOption) = log("NAVI  onRouteOptionChanged $option")
        override fun onRouteCancelled() = log("NAVI  onRouteCancelled")
        override fun onNavigationStatus(status: NavigationStatus) = log("NAVI  onNavigationStatus $status")
        override fun onError(code: NaviErrorCode) = log("NAVI  onError $code")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })

        val suite = intent?.getStringExtra("suite") ?: "all"
        log("== ZZAN SDK probe start (suite=$suite) ==")
        // Vehicle and NaviHelper bind to services; give them a moment after initialize().
        if (suite == "vehicle" || suite == "all") startVehicle()
        if (suite == "navi" || suite == "all") startNavi(delayMs = if (suite == "all") 6000 else 0)
    }

    // ---------------------------------------------------------------- Vehicle

    private fun startVehicle() {
        val v = Vehicle(applicationContext)
        vehicle = v
        try {
            v.initialize()
            log("Vehicle.initialize() returned (service connection not guaranteed yet)")
        } catch (e: Exception) {
            log("Vehicle.initialize() THREW: $e")
            return
        }
        main.postDelayed({ runVehicleProbes(v) }, 2000)
    }

    private fun runVehicleProbes(v: Vehicle) {
        val ev = v.getEvBattery()
        val odo = v.getOdometer()
        val fuel = v.getFuel()
        val gear = v.getGear()
        val brake = v.getBrake()
        val drivingMode = v.getDrivingMode()
        val carInfo = v.getCarInfo()

        // Already verified: energy / odometer
        probe<Int?>("EvBattery.SOC (%)") { ok, ng -> ev.getEvBatteryStateOfCharge(ok, ng) }
        probe<Float?>("EvBattery.level") { ok, ng -> ev.getEvBatteryLevel(ok, ng) }
        probe<Float?>("EvBattery.capacity") { ok, ng -> ev.getEvBatteryCapacity(ok, ng) }
        probe<Boolean?>("EvBattery.chargeSwitch") { ok, ng -> ev.getEvChargeSwitchState(ok, ng) }
        probe<Boolean?>("EvBattery.chargePortOpen") { ok, ng -> ev.isEvChargePortOpen(ok, ng) }
        probe<Float?>("Odometer.distanceDriven") { ok, ng -> odo.getCurrentDistanceDriven(ok, ng) }
        probe<Float?>("Odometer.rangeRemaining") { ok, ng -> odo.getRangeRemainingDistance(ok, ng) }
        probe<Int?>("Fuel.percent") { ok, ng -> fuel.getFuelPercent(ok, ng) }

        // New: driving state (needed for "is the car parked/moving" and brake handling)
        probe<Any?>("Gear.selection") { ok, ng -> gear.getCurrentGearSelectionState(ok, ng) }
        probe<Boolean?>("Brake.pedalPressed") { ok, ng -> brake.getCurrentBrakePedalStatus(ok, ng) }
        probe<Any?>("DrivingMode.current") { ok, ng -> drivingMode.getCurrentDrivingModeState(ok, ng) }
        // New: vehicle info (VIN content is not logged, only its length)
        probe<String?>("CarInfo.VIN length") { ok, ng -> carInfo.getVINInfo({ ok(it?.length?.toString()) }, ng) }
        probe<Map<String, Any?>?>("CarInfo.capabilityInfo") { ok, ng ->
            carInfo.getCapabilityInfo({ m -> ok(m?.mapValues { it.value }) }, ng)
        }

        guarded("register SOC listener") { ev.registerEvBatteryStateOfCharge(socListener) }
        guarded("register Fuel listener") { fuel.registerFuelPercent(fuelListener) }
        log("-- vehicle listeners registered --")
    }

    // ---------------------------------------------------------------- NaviHelper

    private fun startNavi(delayMs: Long) {
        main.postDelayed({
            val n = NaviHelper(applicationContext)
            navi = n
            try {
                n.initialize(
                    { log("NAVI  initialize() onSuccess") ; runNaviScenario(n) },
                    { t -> log("NAVI  initialize() onFailure: $t") }
                )
            } catch (e: Exception) {
                log("NAVI  initialize() THREW: $e")
            }
        }, delayMs)
    }

    /** Sequential scenario: location -> state -> request route -> destination info -> option FREE -> destination info -> cancel. */
    private fun runNaviScenario(n: NaviHelper) {
        n.addListener(naviListener)
        // (name, wait after the step in ms, action). Route search is asynchronous, so wait
        // for onRouteStarted before touching the active route (docs: option change needs an active route).
        val steps = listOf<Triple<String, Long, () -> Unit>>(
            Triple("getCurrentLocationInfo", 2500) { n.getCurrentLocationInfo() },
            Triple("getRouteStateInfo", 2500) { n.getRouteStateInfo() },
            Triple("requestRoute(42dot sample POI, RECOMMENDED)", 9000) {
                // Sample destination taken from the official docs (Routes page).
                n.requestRoute(
                    RouteInfo(
                        latitude = 37.48544722,
                        longitude = 127.03636666,
                        displayLongitude = null,
                        displayLatitude = null,
                        poiName = "42dot",
                        poiId = "36khp37hh8gqcm5nqhe5",
                        poiSubId = "0",
                        address = "서울시 강남구 남부순환로 2621"
                    )
                )
            },
            Triple("getDestinationInfo (RECOMMENDED)", 3000) { n.getDestinationInfo() },
            Triple("changeRouteOption(FREE)", 6000) { n.changeRouteOption(RouteOption.FREE) },
            Triple("getRouteStateInfo (after FREE)", 2500) { n.getRouteStateInfo() },
            Triple("getDestinationInfo (after FREE)", 3000) { n.getDestinationInfo() },
            Triple("changeRouteOption(SHORT_TIME)", 6000) { n.changeRouteOption(RouteOption.SHORT_TIME) },
            Triple("getDestinationInfo (after SHORT_TIME)", 3000) { n.getDestinationInfo() },
            Triple("cancelRoute", 3000) { n.cancelRoute() },
        )
        var at = 0L
        steps.forEachIndexed { i, (name, wait, action) ->
            main.postDelayed({
                log("NAVI  >> step ${i + 1}: $name")
                guarded("navi step $name") { action() }
            }, at)
            at += wait
        }
        main.postDelayed({ log("-- navi scenario finished --") }, at)
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> probe(name: String, call: ((T) -> Unit, (Exception) -> Unit) -> Unit) {
        guarded(name) {
            call({ value -> log("OK    $name = $value") }, { e -> log("FAIL  $name : $e") })
        }
    }

    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("THROW $name : $e")
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        main.post { out.append(msg + "\n") }
    }

    override fun onDestroy() {
        vehicle?.let { v ->
            try {
                v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
                v.getFuel().unregisterFuelPercent(fuelListener)
                v.release()
            } catch (e: Exception) {
                Log.w(TAG, "vehicle release failed: $e")
            }
        }
        navi?.let { n ->
            try {
                n.removeListener(naviListener)
                n.release()
            } catch (e: Exception) {
                Log.w(TAG, "navi release failed: $e")
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ZzanProbe"
    }
}
