package com.zzandrive.core.verification

import com.zzandrive.data.api.ChargeHintSample
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergySample
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.LocationSample
import com.zzandrive.data.api.VehicleSample
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thresholds for refuel/charge detection (spec D-05):
 * a session starts on an active charge hint or a rise of [startIncrease] while parked, ends when the
 * hint turns off (after [graceMs] for late samples), when the car leaves park, or after [quietMs]
 * without a rise when no hint is readable; it counts if it added at least [minQuantity].
 */
data class RefillConfig(
    val startIncrease: Double,
    val minQuantity: Double,
    val quietMs: Long,
    val graceMs: Long,
) {
    companion object {
        /** kWh. */
        val EV = RefillConfig(startIncrease = 0.5, minQuantity = 1.0, quietMs = 120_000, graceMs = 2_000)

        /** Litres. */
        val FUEL = RefillConfig(startIncrease = 0.5, minQuantity = 1.0, quietMs = 120_000, graceMs = 2_000)
    }
}

/** How the quantity was measured: stored energy/fuel directly, or SOC × capacity (less precise). */
enum class QuantityBasis { ENERGY, SOC }

data class RefillDetected(
    /** Deterministic from the session start, so replaying the same samples gives the same id (R-08). */
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val startLevel: Double,
    val endLevel: Double,
    val quantity: Double,
    val basis: QuantityBasis,
    val location: GeoPoint?,
)

/**
 * IDLE → PARKED(baseline) → REFILLING(start, max) → finalize.
 * The quantity is (max level seen) − (level at start), so duplicated, batched or late callbacks
 * cannot be counted twice. Timing uses each sample's own timestamp, never the wall clock.
 */
class RefillDetector(
    private val config: RefillConfig,
    private val capacityFallback: Double? = null,
    private val idPrefix: String = "refill",
) {
    private sealed interface State
    private data object Idle : State
    private data class Parked(val baseline: Double) : State
    private data class Refilling(
        val start: Double,
        val startMs: Long,
        val max: Double,
        val lastRiseMs: Long,
        val closingDeadlineMs: Long?,
    ) : State

    private var state: State = Idle
    private var parked: Boolean? = null
    private var hint: ChargeHintSample? = null
    private var level: Double? = null
    private var basis: QuantityBasis = QuantityBasis.ENERGY
    private var capacity: Double? = capacityFallback
    private var location: GeoPoint? = null

    /** Feed one sample; returns a detection when a session closes. */
    fun onSample(sample: VehicleSample): RefillDetected? {
        val t = sample.atMs
        val closed = due(t) // a sample past a deadline closes the previous session first
        if (closed != null) {
            consume(sample)
            return closed
        }
        return consume(sample)
    }

    /** Closes a session whose grace or quiet period has passed; call periodically with the current time. */
    fun tick(nowMs: Long): RefillDetected? = due(nowMs)

    /** True while a charge/refuel session is open (used to defer UI and to keep polling). */
    val isRefilling: Boolean get() = state is Refilling

    private fun consume(sample: VehicleSample): RefillDetected? {
        val t = sample.atMs
        when (sample) {
            is LocationSample -> location = sample.point
            is DriveSample -> {
                parked = sample.parked
                if (!sample.parked) {
                    val result = finalize(t)
                    state = Idle
                    return result
                }
                enterParkedIfIdle()
            }
            is ChargeHintSample -> {
                hint = sample
                when (val s = state) {
                    is Parked -> if (sample.active) startRefilling(s.baseline, t)
                    is Refilling ->
                        state = if (sample.known && !sample.active) {
                            s.copy(closingDeadlineMs = s.closingDeadlineMs ?: (t + config.graceMs))
                        } else {
                            s.copy(closingDeadlineMs = null)
                        }
                    Idle -> Unit
                }
            }
            is EnergySample -> {
                sample.capacity?.let { capacity = it }
                val lv = levelOf(sample) ?: return null
                level = lv
                when (val s = state) {
                    Idle -> enterParkedIfIdle()
                    is Parked -> {
                        val baseline = minOf(s.baseline, lv) // parked consumption lowers the baseline
                        state = Parked(baseline)
                        val hintKnown = hint?.known == true
                        val hintActive = hint?.active == true
                        if (hintActive || (!hintKnown && lv - baseline >= config.startIncrease)) startRefilling(baseline, t)
                    }
                    is Refilling -> if (lv > s.max) state = s.copy(max = lv, lastRiseMs = t)
                }
            }
        }
        return null
    }

    private fun levelOf(sample: EnergySample): Double? {
        sample.amount?.let { basis = QuantityBasis.ENERGY; return it }
        val soc = sample.socPercent ?: return null
        val cap = sample.capacity ?: capacity ?: return null
        basis = QuantityBasis.SOC
        return soc / 100.0 * cap
    }

    private fun enterParkedIfIdle() {
        if (state == Idle && parked == true) level?.let { state = Parked(it) }
    }

    private fun startRefilling(baseline: Double, t: Long) {
        val current = level ?: baseline
        state = Refilling(start = baseline, startMs = t, max = maxOf(baseline, current), lastRiseMs = t, closingDeadlineMs = null)
    }

    private fun due(nowMs: Long): RefillDetected? {
        val s = state as? Refilling ?: return null
        val deadline = s.closingDeadlineMs
        val hintKnown = hint?.known == true
        val closeNow = (deadline != null && nowMs >= deadline) || (!hintKnown && nowMs - s.lastRiseMs >= config.quietMs)
        return if (closeNow) finalize(deadline ?: nowMs) else null
    }

    private fun finalize(endMs: Long): RefillDetected? {
        val s = state as? Refilling ?: return null
        state = if (parked == true) Parked(level ?: s.max) else Idle
        val quantity = s.max - s.start
        if (quantity < config.minQuantity) return null
        return RefillDetected(
            id = "$idPrefix-${s.startMs}",
            startMs = s.startMs,
            endMs = endMs,
            startLevel = s.start,
            endLevel = s.max,
            quantity = quantity,
            basis = basis,
            location = location,
        )
    }
}

/** Matches where a refuel/charge happened to a known station (default within 300 m). */
object StationMatcher {
    const val DEFAULT_RADIUS_M = 300.0
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** The id of the nearest station within [radiusM], or null. */
    fun match(at: GeoPoint?, stations: Map<String, GeoPoint?>, radiusM: Double = DEFAULT_RADIUS_M): String? {
        if (at == null) return null
        return stations.entries
            .mapNotNull { (id, p) -> p?.let { id to distanceM(at, it) } }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }
            ?.first
    }
}
