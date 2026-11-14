package com.zzandrive.core.verification

import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.toWon
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergyType
import java.math.BigDecimal
import java.math.MathContext

/** What one trip's driving cost in avoidable fuel or electricity (기획서 3-5, spec D-35..D-38). Amounts are exact until [fullWon]. */
data class TripPenalty(
    val tripId: String,
    val startMs: Long,
    val endMs: Long,
    /** 급출발·급가속, counted once per acceleration. */
    val accelEvents: Int,
    val accelCost: BigDecimal,
    /** Length of the idling stretches that went past the 180-second grace, and the part that is charged. */
    val idleSeconds: Long,
    val idleEvalSeconds: Long,
    val idleCost: BigDecimal,
    val speedingKm: BigDecimal,
    val speedingCost: BigDecimal,
    /** 급감속: recorded, never charged (R-31). */
    val decelEvents: Int,
) {
    val total: BigDecimal get() = accelCost.add(idleCost).add(speedingCost)

    /** Rounded once, at the end of the trip (spec D-12). */
    val fullWon: Long get() = total.toWon()
}

sealed interface DrivingEvent {
    data class TripStarted(val tripId: String, val byEngine: Boolean) : DrivingEvent

    /** A new 급출발 or 급가속; [count] is its number in this trip. */
    data class HarshAccel(val kind: InefficiencyRules.Kind, val v0: Double, val a: Double, val count: Int, val cost: BigDecimal) : DrivingEvent

    data class Decel(val a: Double) : DrivingEvent

    /** Idling went past 180 seconds (said once per stretch). */
    data object IdleOver : DrivingEvent

    data class SpeedingStarted(val speedKmh: Double, val limitKmh: Int) : DrivingEvent

    data class TripEnded(val penalty: TripPenalty) : DrivingEvent
}

/**
 * Turns speed, gear and engine readings into one trip's deductions (spec D-35..D-38). Pure: it only looks at the
 * samples' own times, so a replayed trip gives the same amounts.
 *
 * A trip is open while the car is out of P or the engine is running, and ends at the first sample in P with the engine
 * off or unknown. Without an engine signal (electric cars here) that is simply drive → park.
 */
class DrivingMonitor(
    private val type: EnergyType,
    private val unitPrice: BigDecimal = PlanReference.survey(type).unitPrice,
) {
    private val mc = MathContext.DECIMAL64

    private var tripId: String? = null
    private var startMs = 0L
    private var previous: DriveSample? = null

    /** The sample accelerations are measured from (only intervals of 0.8–1.2 s are evaluated). */
    private var anchor: DriveSample? = null
    private var lastAccelMs: Long? = null
    private var lastDecelMs: Long? = null
    private var accelEvents = 0
    private var accelCost = BigDecimal.ZERO
    private var decelEvents = 0

    private var idleSinceMs: Long? = null
    private var idleOverSaid = false
    private var idleSeconds = 0L
    private var idleEvalSeconds = 0L
    private var idleCost = BigDecimal.ZERO

    private var speeding = false
    private var speedingKm = BigDecimal.ZERO
    private var speedingCost = BigDecimal.ZERO

    val inTrip: Boolean get() = tripId != null

    fun reset() {
        tripId = null
        previous = null
        clearTrip()
    }

    private fun clearTrip() {
        anchor = null
        lastAccelMs = null
        lastDecelMs = null
        accelEvents = 0
        accelCost = BigDecimal.ZERO
        decelEvents = 0
        idleSinceMs = null
        idleOverSaid = false
        idleSeconds = 0
        idleEvalSeconds = 0
        idleCost = BigDecimal.ZERO
        speeding = false
        speedingKm = BigDecimal.ZERO
        speedingCost = BigDecimal.ZERO
    }

    fun onSample(s: DriveSample): List<DrivingEvent> {
        val events = mutableListOf<DrivingEvent>()
        val prev = previous
        if (prev != null && s.atMs <= prev.atMs) return events // out of order or repeated: not a new observation
        val active = !s.parked || s.engineOn == true

        if (tripId == null) {
            if (!active) {
                previous = s
                return events
            }
            clearTrip()
            tripId = "trip-${s.atMs}"
            startMs = s.atMs
            events += DrivingEvent.TripStarted(tripId!!, byEngine = s.parked)
        } else if (prev != null) {
            speedingBetween(prev, s, events)
        }

        idle(s, events)
        if (active) accelerate(s, events) else anchor = null

        if (!active) {
            events += DrivingEvent.TripEnded(
                TripPenalty(tripId!!, startMs, s.atMs, accelEvents, accelCost, idleSeconds, idleEvalSeconds, idleCost, speedingKm, speedingCost, decelEvents),
            )
            tripId = null
        }
        previous = s
        return events
    }

    /** 급출발·급가속 (eTAS 택시 기준, [InefficiencyRules.classify]); one acceleration is one event however long it lasts. */
    private fun accelerate(s: DriveSample, events: MutableList<DrivingEvent>) {
        val v1 = s.speedKmh
        val from = anchor
        if (v1 == null) {
            anchor = null
            return
        }
        val v0 = from?.speedKmh
        if (from == null || v0 == null) {
            anchor = s
            return
        }
        val dt = (s.atMs - from.atMs) / 1000.0
        if (dt < 0.8) return // too close (a 10 Hz feed): wait for the anchor to be about a second old
        anchor = s
        if (!InefficiencyRules.validInterval(dt)) return // a gap: start measuring again from here
        val a = InefficiencyRules.acceleration(v0, v1, dt)
        when (val kind = InefficiencyRules.classify(v0, a)) {
            InefficiencyRules.Kind.RAPID_START, InefficiencyRules.Kind.RAPID_ACCEL -> {
                val last = lastAccelMs
                lastAccelMs = s.atMs
                if (last != null && s.atMs - last <= MERGE_MS) return // the same acceleration, still going
                accelEvents += 1
                val cost = InefficiencyRules.accelEventCost(type, unitPrice)
                accelCost = accelCost.add(cost, mc)
                events += DrivingEvent.HarshAccel(kind, v0, a, accelEvents, cost)
            }
            InefficiencyRules.Kind.DECEL -> {
                val last = lastDecelMs
                lastDecelMs = s.atMs
                if (last != null && s.atMs - last <= MERGE_MS) return
                decelEvents += 1
                events += DrivingEvent.Decel(a)
            }
            else -> Unit
        }
    }

    /** 공회전: P, standing, engine running. Each stretch has its own 180 seconds; the rest is charged when it ends. */
    private fun idle(s: DriveSample, events: MutableList<DrivingEvent>) {
        val idling = s.parked && (s.speedKmh ?: 0.0) == 0.0 && s.engineOn == true && InefficiencyRules.idleUsePerHour(type) != null
        val since = idleSinceMs
        if (idling) {
            if (since == null) {
                idleSinceMs = s.atMs
                idleOverSaid = false
            } else if (!idleOverSaid && (s.atMs - since) / 1000 > InefficiencyRules.IDLE_GRACE_SECONDS) {
                idleOverSaid = true
                events += DrivingEvent.IdleOver
            }
        } else if (since != null) {
            idleSinceMs = null
            val seconds = (s.atMs - since) / 1000
            val evaluated = InefficiencyRules.idleEvaluationSeconds(seconds)
            if (evaluated > 0) {
                idleSeconds += seconds
                idleEvalSeconds += evaluated
                idleCost = idleCost.add(InefficiencyRules.idleCost(evaluated, type, unitPrice), mc)
            }
        }
    }

    /** 과속: the stretch between two samples at the earlier one's speed and limit, when they are at most 10 seconds apart. */
    private fun speedingBetween(a: DriveSample, b: DriveSample, events: MutableList<DrivingEvent>) {
        val v = a.speedKmh
        val limit = a.speedLimitKmh
        val dtMs = b.atMs - a.atMs
        val over = v != null && limit != null && !a.parked && BigDecimal.valueOf(v) > InefficiencyRules.evaluationSpeed(limit)
        if (!over || v == null || limit == null) {
            speeding = false
            return
        }
        if (!speeding) {
            speeding = true
            events += DrivingEvent.SpeedingStarted(v, limit)
        }
        if (dtMs > MAX_GAP_MS) return // too long between readings to know the speed held
        val km = BigDecimal.valueOf(v).multiply(BigDecimal(dtMs), mc).divide(BigDecimal(3_600_000), mc)
        speedingKm = speedingKm.add(km, mc)
        speedingCost = speedingCost.add(InefficiencyRules.speedingCost(km, BigDecimal.valueOf(v), limit, type, unitPrice), mc)
    }

    private companion object {
        /** Observations of one acceleration within 3 s are one event (기획서 3-5). */
        const val MERGE_MS = 3_000L
        const val MAX_GAP_MS = 10_000L
    }
}
