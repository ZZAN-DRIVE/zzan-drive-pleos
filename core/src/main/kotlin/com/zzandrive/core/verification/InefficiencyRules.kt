package com.zzandrive.core.verification

import com.zzandrive.data.api.EnergyType
import java.math.BigDecimal
import java.math.MathContext

/**
 * 기획서 3-5 산식 (pure functions; wiring them to PERF_VEHICLE_SPEED is the next step).
 * Speeds in km/h, accelerations in km/h per second, times in seconds unless the name ends in Ms or Ns.
 */
object InefficiencyRules {
    private val MC = MathContext.DECIMAL64
    private val SECONDS_PER_HOUR = BigDecimal(3600)

    /** 1 US gallon in litres (기획서). */
    val US_GALLON_L: BigDecimal = BigDecimal("3.785411784")

    /** Extra use per km under aggressive / non-eco driving: (aggressive − normal) ÷ 100. */
    fun extraUsePerKm(type: EnergyType): BigDecimal = when (type) {
        EnergyType.GASOLINE -> BigDecimal("18.13").subtract(BigDecimal("10.80")).divide(BigDecimal(100), MC) // 0.0733 L/km
        EnergyType.DIESEL -> BigDecimal("11.63").subtract(BigDecimal("7.93")).divide(BigDecimal(100), MC) // 0.037 L/km
        EnergyType.ELECTRIC -> BigDecimal("22.537").subtract(BigDecimal("15.686")).divide(BigDecimal(100), MC) // 0.06851 kWh/km
    }

    /** Idle consumption in L per hour; electric cars are not evaluated for idling (null). */
    fun idleUsePerHour(type: EnergyType): BigDecimal? = when (type) {
        EnergyType.GASOLINE -> BigDecimal("0.16").multiply(US_GALLON_L, MC) // 0.605666 L/h
        EnergyType.DIESEL -> BigDecimal("0.17").multiply(US_GALLON_L, MC) // 0.643520 L/h
        EnergyType.ELECTRIC -> null
    }

    fun kmh(speedMps: Double): Double = 3.6 * speedMps

    /** dt in seconds from two sample timestamps in nanoseconds (t_ms = t_ns ÷ 1,000,000). */
    fun dtSeconds(previousNs: Long, currentNs: Long): Double = (currentNs / 1_000_000.0 - previousNs / 1_000_000.0) / 1000.0

    /** Only intervals of 0.8–1.2 s are evaluated. */
    fun validInterval(dt: Double): Boolean = dt in 0.8..1.2

    fun acceleration(v0: Double, v1: Double, dt: Double): Double = (v1 - v0) / dt

    /** Threshold b for rapid acceleration by starting speed v0. */
    fun accelThreshold(v0: Double): Double = when {
        v0 <= 10.0 -> 12.0
        v0 <= 20.0 -> 10.0
        else -> 8.0
    }

    enum class Kind { RAPID_START, RAPID_ACCEL, DECEL, UNCLASSIFIED, NONE }

    /**
     * 급출발 = 0 ≤ v0 ≤ 5 and a ≥ 10; 급가속 = v0 ≥ 6 and a ≥ b; 5 < v0 < 6 is unclassified.
     * Deceleration is only recorded, never charged (R-31). The plan gives no deceleration threshold;
     * [decelThreshold] (default 10 km/h/s, mirror of 급출발) is an assumption.
     */
    fun classify(v0: Double, a: Double, decelThreshold: Double = 10.0): Kind = when {
        a <= -decelThreshold -> Kind.DECEL
        v0 > 5.0 && v0 < 6.0 -> Kind.UNCLASSIFIED
        v0 in 0.0..5.0 && a >= 10.0 -> Kind.RAPID_START
        v0 >= 6.0 && a >= accelThreshold(v0) -> Kind.RAPID_ACCEL
        else -> Kind.NONE
    }

    /** True for kinds that may lead to a deduction (subject to exclusions: signals, congestion, needed acceleration). */
    fun chargeable(kind: Kind): Boolean = kind == Kind.RAPID_START || kind == Kind.RAPID_ACCEL

    /** T_eval = max(0, T_confirmed − 180 s). */
    fun idleEvaluationSeconds(confirmedIdleSeconds: Long): Long = maxOf(0L, confirmedIdleSeconds - 180L)

    /** C_drive = d × k × p. */
    fun driveExtraCost(distanceKm: BigDecimal, type: EnergyType, unitPrice: BigDecimal): BigDecimal =
        distanceKm.multiply(extraUsePerKm(type), MC).multiply(unitPrice, MC)

    /** C_idle = T_eval ÷ 3,600 × r × p (0 for electric). */
    fun idleCost(evaluationSeconds: Long, type: EnergyType, unitPrice: BigDecimal): BigDecimal {
        val r = idleUsePerHour(type) ?: return BigDecimal.ZERO
        return BigDecimal(evaluationSeconds).divide(SECONDS_PER_HOUR, MC).multiply(r, MC).multiply(unitPrice, MC)
    }

    /**
     * N₆₀: independent acceleration/deceleration events in the last 60 s; observations in the same
     * direction within 3 s count as one event. Repeat candidate when N₆₀ ≥ 3.
     */
    class RepeatCounter(private val windowMs: Long = 60_000, private val mergeMs: Long = 3_000) {
        private data class Event(val atMs: Long, val positive: Boolean)
        private val events = ArrayDeque<Event>()

        /** Records an observation and returns the current N₆₀. */
        fun observe(atMs: Long, positive: Boolean): Int {
            val last = events.lastOrNull()
            if (last == null || last.positive != positive || atMs - last.atMs > mergeMs) {
                events.addLast(Event(atMs, positive))
            } else {
                events[events.lastIndex] = Event(atMs, positive) // same event continues
            }
            while (events.isNotEmpty() && atMs - events.first().atMs > windowMs) events.removeFirst()
            return events.size
        }

        fun isRepeatCandidate(n60: Int): Boolean = n60 >= 3
    }
}
