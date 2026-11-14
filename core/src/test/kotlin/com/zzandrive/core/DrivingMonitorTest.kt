package com.zzandrive.core

import com.zzandrive.core.Fixtures.cents
import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.bd
import com.zzandrive.core.settlement.Ledger
import com.zzandrive.core.settlement.OpeningBalance
import com.zzandrive.core.settlement.PenaltyConfirmed
import com.zzandrive.core.verification.DrivingEvent
import com.zzandrive.core.verification.DrivingMonitor
import com.zzandrive.core.verification.InefficiencyRules
import com.zzandrive.core.verification.TripPenalty
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

/** 운전 습관 차감 (기획서 3-5와 기획 계산식, spec D-35..D-38): 급가속 건당, 공회전 180초 초과, 과속. */
class DrivingMonitorTest {
    private val t0 = Instant.parse("2026-11-13T03:00:00Z").toEpochMilli()

    private fun park(sec: Double, engine: Boolean? = true) =
        DriveSample(t0 + (sec * 1000).toLong(), parked = true, gear = "P", origin = DataOrigin.MOCK, speedKmh = 0.0, engineOn = engine)

    private fun drive(sec: Double, kmh: Double, limit: Int? = null, engine: Boolean? = true) =
        DriveSample(t0 + (sec * 1000).toLong(), parked = false, gear = "D", origin = DataOrigin.MOCK, speedKmh = kmh, engineOn = engine, speedLimitKmh = limit)

    private fun run(type: EnergyType, samples: List<DriveSample>): Pair<List<DrivingEvent>, TripPenalty?> {
        val monitor = DrivingMonitor(type)
        val events = samples.flatMap(monitor::onSample)
        return events to events.filterIsInstance<DrivingEvent.TripEnded>().lastOrNull()?.penalty
    }

    /** One trip with every kind: 8 minutes of idling, a harsh start, a harsh acceleration, a hard brake, 6.25 km of speeding. */
    private fun habitTrip(): List<DriveSample> = buildList {
        for (s in 0..420 step 60) add(park(s.toDouble()))
        add(drive(480.0, 0.0, 50))
        add(drive(481.0, 14.0, 50)) // 급출발: 0 → 14 in a second
        add(drive(482.0, 26.0, 50)) // still the same acceleration
        add(drive(483.0, 30.0, 50))
        listOf(32.0, 34.0, 36.0, 38.0, 40.0, 40.0).forEachIndexed { i, v -> add(drive(490.0 + i * 10, v, 50)) }
        add(drive(550.0, 40.0, 50))
        add(drive(551.0, 50.0, 50)) // 급가속: +10 at 40 km/h (threshold 8)
        add(drive(552.0, 52.0, 50))
        add(drive(560.0, 52.0, 50))
        add(drive(561.0, 38.0, 50)) // 급감속: recorded only
        listOf(70.0, 90.0, 105.0, 110.0, 110.0).forEachIndexed { i, v -> add(drive(570.0 + i * 10, v, 100)) } // 110 = 100 × 1.1: not speeding
        for (s in 620..790 step 10) add(drive(s.toDouble(), 125.0, 100)) // 180 s at 125: 6.25 km, 15 over
        listOf(100.0, 60.0, 30.0, 10.0).forEachIndexed { i, v -> add(drive(800.0 + i * 10, v, 100)) }
        add(park(840.0))
        add(park(843.0, engine = false))
    }

    @Test
    fun `one trip - two accelerations, eight minutes idling and 6,25 km of speeding come to 377 won`() {
        val (events, p) = run(EnergyType.GASOLINE, habitTrip())
        p!!
        assertEquals("trip-$t0", p.tripId)
        assertEquals(2, p.accelEvents)
        assertEquals(bd("272.01"), p.accelCost.cents()) // 2 × 0.0733 L × 1,855.43
        assertEquals(480L, p.idleSeconds)
        assertEquals(300L, p.idleEvalSeconds)
        assertEquals(bd("93.65"), p.idleCost.cents())
        assertEquals(0, bd("6.25").compareTo(p.speedingKm.setScale(4, java.math.RoundingMode.HALF_UP)))
        assertEquals(bd("11.25"), p.speedingCost.cents()) // 6.25 km × 0.000064675 × 15 × 1,855.43
        assertEquals(1, p.decelEvents)
        assertEquals(bd("376.90"), p.total.cents())
        assertEquals(377L, p.fullWon)
        // In order: the trip opens on the running engine, idling passes 180 s, then the driving events.
        assertEquals(DrivingEvent.TripStarted("trip-$t0", byEngine = true), events.first())
        assertEquals(1, events.count { it is DrivingEvent.IdleOver })
        val accels = events.filterIsInstance<DrivingEvent.HarshAccel>()
        assertEquals(listOf(InefficiencyRules.Kind.RAPID_START, InefficiencyRules.Kind.RAPID_ACCEL), accels.map { it.kind })
        assertEquals(listOf(14.0, 10.0), accels.map { it.a })
        assertEquals(listOf(bd("136.00"), bd("136.00")), accels.map { it.cost.cents() })
        assertEquals(listOf(-14.0), events.filterIsInstance<DrivingEvent.Decel>().map { it.a })
        assertEquals(listOf(DrivingEvent.SpeedingStarted(125.0, 100)), events.filterIsInstance<DrivingEvent.SpeedingStarted>())
    }

    @Test
    fun `one acceleration is one event however long it lasts, and a new one after three seconds counts again`() {
        val oneLong = listOf(drive(0.0, 0.0), drive(1.0, 12.0), drive(2.0, 24.0), drive(3.0, 34.0), park(10.0, engine = null))
        assertEquals(1, run(EnergyType.ELECTRIC, oneLong).second!!.accelEvents)
        val two = listOf(
            drive(0.0, 0.0), drive(1.0, 12.0), drive(2.0, 13.0), drive(3.0, 13.0), drive(4.0, 13.0),
            drive(5.0, 25.0), // 4 s after the first: its own event
            park(10.0, engine = null),
        )
        val p = run(EnergyType.ELECTRIC, two).second!!
        assertEquals(2, p.accelEvents)
        assertEquals(bd("47.74"), p.accelCost.cents()) // 2 × 0.06851 kWh × 348.4
        assertEquals(48L, p.fullWon)
    }

    @Test
    fun `only intervals of about a second are judged - a gap is skipped and a fast feed waits`() {
        // 1.3 s between readings: not evaluated, however large the jump.
        val gap = listOf(drive(0.0, 0.0), drive(1.3, 30.0), park(5.0, engine = null))
        assertEquals(0, run(EnergyType.GASOLINE, gap).second!!.accelEvents)
        // Ten readings a second: judged once the first is 0.8 s old (0 → 11.2 km/h in 0.8 s = 14 km/h/s).
        val fast = (0..10).map { drive(it / 10.0, it * 1.4) } + park(5.0, engine = null)
        val p = run(EnergyType.GASOLINE, fast).second!!
        assertEquals(1, p.accelEvents)
    }

    @Test
    fun `braking is recorded and never charged`() {
        val brakes = listOf(drive(0.0, 60.0), drive(1.0, 45.0), drive(10.0, 45.0), drive(11.0, 30.0), park(20.0, engine = null))
        val p = run(EnergyType.GASOLINE, brakes).second!!
        assertEquals(2, p.decelEvents)
        assertEquals(0L, p.fullWon)
        assertEquals(0, BigDecimal.ZERO.compareTo(p.total))
    }

    @Test
    fun `idling - every stretch has its own 180 seconds, electric cars and standing in D are not charged`() {
        fun idleFor(seconds: Double, type: EnergyType = EnergyType.GASOLINE) =
            run(type, listOf(park(0.0), drive(seconds, 0.0), park(seconds + 5, engine = false))).second!!
        assertEquals(0L, idleFor(170.0).idleEvalSeconds)
        assertEquals(1L, idleFor(181.0).idleEvalSeconds)
        assertEquals(bd("0.31"), idleFor(181.0).idleCost.cents())
        assertEquals(0L, idleFor(181.0).fullWon) // under a won: nothing to deduct
        assertEquals(bd("93.65"), idleFor(480.0).idleCost.cents())
        // Two stretches of 170 s are two graces, not 340 s.
        val twice = listOf(park(0.0), drive(170.0, 0.0), drive(171.0, 3.0), drive(180.0, 0.0), park(181.0), drive(351.0, 0.0), park(360.0, engine = false))
        assertEquals(0L, run(EnergyType.GASOLINE, twice).second!!.idleEvalSeconds)
        // No engine to idle in an electric car; and waiting in D (a signal) is not idling.
        assertEquals(0L, idleFor(480.0, EnergyType.ELECTRIC).idleEvalSeconds)
        val inD = listOf(drive(0.0, 0.0), drive(480.0, 0.0), park(481.0, engine = false))
        assertEquals(0L, run(EnergyType.GASOLINE, inD).second!!.idleEvalSeconds)
    }

    @Test
    fun `speeding - over the limit by more than a tenth, by distance and by how far over`() {
        // The planner's check: 10 km/h over the evaluation speed for 25 km is 30 won.
        assertEquals(bd("30.00"), InefficiencyRules.speedingCost(bd(25), bd(120), 100, EnergyType.GASOLINE, bd("1855.43")).cents())
        assertEquals(bd("9.00"), InefficiencyRules.speedingCost(bd(5), bd(70), 50, EnergyType.GASOLINE, bd("1855.43")).cents())
        assertEquals(bd("29.78"), InefficiencyRules.speedingCost(bd(25), bd(120), 100, EnergyType.DIESEL, bd("1841.76")).cents())
        assertEquals(0, BigDecimal.ZERO.compareTo(InefficiencyRules.speedingCost(bd(25), bd(110), 100, EnergyType.GASOLINE, bd("1855.43")))) // exactly × 1.1
        assertEquals(0, BigDecimal.ZERO.compareTo(InefficiencyRules.speedingCost(bd(25), bd(120), 100, EnergyType.ELECTRIC, bd("348.4"))))
        assertEquals(0, bd(55).compareTo(InefficiencyRules.evaluationSpeed(50)))

        // 750 s at 120 km/h under a 100 limit: 25 km → 30 won.
        val steady = (0..74).map { drive(it * 10.0, 120.0, 100) } + drive(750.0, 100.0, 100) + park(760.0, engine = false)
        val p = run(EnergyType.GASOLINE, steady).second!!
        assertEquals(0, bd(25).compareTo(p.speedingKm.setScale(4, java.math.RoundingMode.HALF_UP)))
        assertEquals(bd("30.00"), p.speedingCost.cents())
        // Electric: the distance is kept, nothing is charged.
        val ev = run(EnergyType.ELECTRIC, steady.map { it.copy(engineOn = null) }).second!!
        assertEquals(0, bd(25).compareTo(ev.speedingKm.setScale(4, java.math.RoundingMode.HALF_UP)))
        assertEquals(0, BigDecimal.ZERO.compareTo(ev.speedingCost))
        // Readings more than 10 s apart: the speed in between is unknown, so nothing is added.
        val sparse = listOf(drive(0.0, 120.0, 100), drive(11.0, 120.0, 100), drive(22.0, 120.0, 100), park(34.0, engine = false))
        assertEquals(0, BigDecimal.ZERO.compareTo(run(EnergyType.GASOLINE, sparse).second!!.speedingKm))
        // No limit known: not evaluated.
        val unknown = listOf(drive(0.0, 150.0), drive(10.0, 150.0), park(20.0, engine = false))
        assertEquals(0, BigDecimal.ZERO.compareTo(run(EnergyType.GASOLINE, unknown).second!!.speedingKm))
    }

    @Test
    fun `a trip opens on leaving P or a running engine and ends in P with the engine off or unknown`() {
        // No engine signal (electric): drive → park.
        val monitor = DrivingMonitor(EnergyType.ELECTRIC)
        assertTrue(monitor.onSample(park(0.0, engine = null)).isEmpty())
        assertFalse(monitor.inTrip)
        assertEquals(listOf(DrivingEvent.TripStarted("trip-${t0 + 5_000}", byEngine = false)), monitor.onSample(drive(5.0, 0.0, engine = null)))
        assertTrue(monitor.inTrip)
        assertTrue(monitor.onSample(drive(5.0, 10.0, engine = null)).isEmpty()) // the same instant again: ignored
        val end = monitor.onSample(park(60.0, engine = null)).single() as DrivingEvent.TripEnded
        assertEquals(t0 + 60_000, end.penalty.endMs)
        assertFalse(monitor.inTrip)
        // With an engine signal: a stop in P with the engine running keeps the trip open.
        val fuel = DrivingMonitor(EnergyType.GASOLINE)
        fuel.onSample(drive(0.0, 0.0))
        assertTrue(fuel.onSample(park(10.0)).none { it is DrivingEvent.TripEnded })
        assertTrue(fuel.inTrip)
        assertEquals(1, fuel.onSample(park(20.0, engine = false)).count { it is DrivingEvent.TripEnded })
        // The next trip starts clean.
        fuel.onSample(drive(100.0, 0.0))
        fuel.onSample(drive(101.0, 14.0))
        val second = fuel.onSample(park(110.0, engine = false)).filterIsInstance<DrivingEvent.TripEnded>().single().penalty
        assertEquals("trip-${t0 + 100_000}", second.tripId)
        assertEquals(1, second.accelEvents)
    }

    @Test
    fun `the deduction is taken once a trip, stops at the level's accrual and never lowers the cumulative below the level`() {
        val progress = CharacterProgress()
        val ledger = Ledger(progress)
        ledger.append(OpeningBalance("seed", t0, level = 1, accrualWon = 300))
        assertEquals(1_800L, progress.totalSaved(ledger.state))
        val first = ledger.append(PenaltyConfirmed("trip-1", t0 + 1, fullWon = 377, accelEvents = 2, accelRaw = "272.01", idleSeconds = 480, idleEvalSeconds = 300, idleRaw = "93.65", speedingKm = "6.25", speedingRaw = "11.25", decelEvents = 1))
        assertTrue(first.accepted)
        assertEquals(300L, first.penaltyAppliedWon) // only what the level had collected
        assertEquals(CharacterState(1, 0), ledger.state)
        assertEquals(1_500L, progress.totalSaved(ledger.state)) // the amount level 1 was reached at: no lower
        assertEquals(-377L, ledger.monthlyNet(YearMonth.of(2026, 11))) // the month shows all of it
        assertFalse(ledger.append(PenaltyConfirmed("trip-1", t0 + 2, fullWon = 377)).accepted) // the same trip again
        assertEquals(CharacterState(1, 0), ledger.state)
    }

    @Test
    fun `voice lines - short while driving, the amount after the stop`() {
        val dori = VoiceLines(Persona.ZZANDORI)
        val suni = VoiceLines(Persona.ZZANSUNI)
        assertEquals("어이쿠, 살살 밟자!", dori.harshAccelNudge())
        assertEquals("어머, 살살 밟자~", suni.harshAccelNudge())
        assertEquals("조금만 천천히 가자!", dori.speedingNudge())
        assertEquals("시동 켠 채로 3분 넘었어. 기름이 새고 있어~", suni.idleNudge())
        assertEquals("앗, 급가속 때문에 내 삼각김밥 136원 날아갔어…", dori.tripPenalty("삼각김밥", 136, listOf("급가속"), floored = false))
        assertEquals("앗, 급가속, 공회전, 과속 때문에 이번 레벨 적립액이 다 줄었어…", dori.tripPenalty("삼각김밥", 377, listOf("급가속", "공회전", "과속"), floored = true))
        assertEquals("앗, 과속 때문에 30원 날아갔어…", suni.tripPenalty(null, 30, listOf("과속"), floored = false))
    }
}
