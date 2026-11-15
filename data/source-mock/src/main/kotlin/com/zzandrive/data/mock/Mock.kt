package com.zzandrive.data.mock

import com.zzandrive.data.api.ActiveRoute
import com.zzandrive.data.api.Cancellable
import com.zzandrive.data.api.ChargeHintSample
import com.zzandrive.data.api.Clock
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergySample
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.LocationSample
import com.zzandrive.data.api.PlaceSource
import com.zzandrive.data.api.RouteChoices
import com.zzandrive.data.api.RouteLeg
import com.zzandrive.data.api.RouteOption
import com.zzandrive.data.api.RouteSource
import com.zzandrive.data.api.SavedPlace
import com.zzandrive.data.api.Scheduler
import com.zzandrive.data.api.StationOffer
import com.zzandrive.data.api.StationSource
import com.zzandrive.data.api.VehicleSample
import com.zzandrive.data.api.VehicleSource
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Stations for the demo, with the plan's example prices (기획서 3-1 시연 가정, not survey averages).
 * Coordinates are fictional points near the Pleos emulator's navigation position (성남 수정구).
 */
object FixtureStations {
    val HOME = GeoPoint(37.41272, 127.09468)

    val EV_A = StationOffer(
        id = "ev-a", name = "회사 앞 충전소", energyType = EnergyType.ELECTRIC,
        unitPrice = BigDecimal("350"), roundTripDetourKm = BigDecimal("2"),
        location = GeoPoint(37.41350, 127.09600), totalMinutes = BigDecimal("47"), origin = DataOrigin.PLAN_EXAMPLE,
    )
    val EV_B = StationOffer(
        id = "ev-b", name = "집 가는 길 B 충전소", energyType = EnergyType.ELECTRIC,
        unitPrice = BigDecimal("290"), roundTripDetourKm = BigDecimal("4"),
        location = GeoPoint(37.40200, 127.10800), totalMinutes = BigDecimal("50"), origin = DataOrigin.PLAN_EXAMPLE,
    )
    val FUEL_A = StationOffer(
        id = "fuel-a", name = "가까운 A 주유소", energyType = EnergyType.GASOLINE,
        unitPrice = BigDecimal("1700"), roundTripDetourKm = BigDecimal("2"),
        location = GeoPoint(37.41450, 127.09200), totalMinutes = BigDecimal("6"), origin = DataOrigin.PLAN_EXAMPLE,
    )
    val FUEL_B = StationOffer(
        id = "fuel-b", name = "경로상 B 주유소", energyType = EnergyType.GASOLINE,
        unitPrice = BigDecimal("1650"), roundTripDetourKm = BigDecimal("12"),
        location = GeoPoint(37.39300, 127.11500), totalMinutes = BigDecimal("14"), origin = DataOrigin.PLAN_EXAMPLE,
    )

    val ALL = listOf(EV_A, EV_B, FUEL_A, FUEL_B)

    // ---- The plan's search: road distances from the reference place, 5 km rule (p.5 ④⑤) ----

    /** Cheapest of all but 6.2 km of road away (3.9 km in a straight line): the 5 km rule leaves it out. */
    val EV_FAR = StationOffer(
        id = "ev-far", name = "고개 너머 C 충전소", energyType = EnergyType.ELECTRIC,
        unitPrice = BigDecimal("250"), roundTripDetourKm = BigDecimal("12.4"), roadKm = BigDecimal("6.2"),
        location = GeoPoint(37.44782, 127.09468), totalMinutes = BigDecimal("55"), origin = DataOrigin.PLAN_EXAMPLE,
    )

    /** A second fuel station within 5 km, so the fuel search compares two places. */
    val FUEL_C = StationOffer(
        id = "fuel-c", name = "시장 앞 C 주유소", energyType = EnergyType.GASOLINE,
        unitPrice = BigDecimal("1680"), roundTripDetourKm = BigDecimal("4"), roadKm = BigDecimal("2"),
        location = GeoPoint(37.42500, 127.09900), totalMinutes = BigDecimal("8"), origin = DataOrigin.PLAN_EXAMPLE,
    )

    /** Around the current location: the plan's four plus the two above (never in [ALL], which the fixed-amount flow uses). */
    val NEAR_HOME = ALL + listOf(EV_FAR, FUEL_C)

    // ---- 강릉 가는 길 (시나리오 A): the planner's five-station example as whole routes (기획 계산식, spec D-32) ----

    /** [km], [minutes], [toll]: the whole route from the rest stop through this station to 강릉. [ahead]: km to the station. */
    private fun onTheWay(letter: String, price: String, km: String, minutes: String, toll: String, ahead: String, at: GeoPoint) = StationOffer(
        id = "route-${letter.lowercase()}", name = "강릉길 $letter 주유소", energyType = EnergyType.GASOLINE,
        unitPrice = BigDecimal(price), roundTripDetourKm = BigDecimal(km).subtract(BigDecimal("100")), location = at,
        origin = DataOrigin.PLAN_EXAMPLE, roadKm = BigDecimal(ahead), aheadKm = BigDecimal(ahead), onRoute = true,
        route = RouteLeg(BigDecimal(km), BigDecimal(minutes), BigDecimal(toll)),
    )

    val ROUTE_A = onTheWay("A", "1700", "100", "80", "3000", "12", GeoPoint(37.44500, 127.30000))
    val ROUTE_B = onTheWay("B", "1660", "110", "100", "0", "18", GeoPoint(37.47000, 127.38000))
    val ROUTE_C = onTheWay("C", "1640", "108", "90", "1000", "25", GeoPoint(37.50000, 127.47000))
    val ROUTE_D = onTheWay("D", "1600", "115", "110", "0", "40", GeoPoint(37.55000, 127.65000))
    val ROUTE_E = onTheWay("E", "1670", "120", "96", "0", "48", GeoPoint(37.58000, 127.74000))

    val ON_THE_WAY = listOf(ROUTE_A, ROUTE_B, ROUTE_C, ROUTE_D, ROUTE_E)

    /** A saved place with nothing within 5 km of road (p.5 ⑤ "가장 가까운 … 1곳만"). */
    val PARENTS = GeoPoint(37.49100, 127.48800)

    val NEAR_PARENTS = listOf(
        StationOffer(
            id = "ev-p1", name = "읍내 충전소", energyType = EnergyType.ELECTRIC, unitPrice = BigDecimal("330"),
            roundTripDetourKm = BigDecimal("12"), roadKm = BigDecimal("6.0"), location = GeoPoint(37.50300, 127.54000),
            origin = DataOrigin.PLAN_EXAMPLE,
        ),
        StationOffer(
            id = "ev-p2", name = "면사무소 충전소", energyType = EnergyType.ELECTRIC, unitPrice = BigDecimal("310"),
            roundTripDetourKm = BigDecimal("15"), roadKm = BigDecimal("7.5"), location = GeoPoint(37.45200, 127.53000),
            origin = DataOrigin.PLAN_EXAMPLE,
        ),
        StationOffer(
            id = "fuel-p1", name = "읍내 주유소", energyType = EnergyType.GASOLINE, unitPrice = BigDecimal("1690"),
            roundTripDetourKm = BigDecimal("13"), roadKm = BigDecimal("6.5"), location = GeoPoint(37.50600, 127.54500),
            origin = DataOrigin.PLAN_EXAMPLE,
        ),
        StationOffer(
            id = "fuel-p2", name = "국도변 주유소", energyType = EnergyType.GASOLINE, unitPrice = BigDecimal("1640"),
            roundTripDetourKm = BigDecimal("16"), roadKm = BigDecimal("8.0"), location = GeoPoint(37.44800, 127.53800),
            origin = DataOrigin.PLAN_EXAMPLE,
        ),
    )
}

class FixtureStationSource(private val stations: List<StationOffer> = FixtureStations.ALL) : StationSource {
    override fun offers(energyType: EnergyType, near: GeoPoint?): List<StationOffer> = stations.filter { it.energyType == energyType }

    /** Around the saved place when the search is centred there, otherwise around the current location. */
    override fun candidates(energyType: EnergyType, near: GeoPoint?): List<StationOffer> {
        val p = FixtureStations.PARENTS
        val atParents = near != null && abs(near.lat - p.lat) < 0.05 && abs(near.lon - p.lon) < 0.05
        return (if (atParents) FixtureStations.NEAR_PARENTS else FixtureStations.NEAR_HOME).filter { it.energyType == energyType }
    }
}

/**
 * 시나리오 A (기획서 p.16~17): Seoul → 강릉 on a family trip, 100 km left at the rest stop (example data). With 6 L in a
 * 60 L tank at 15.3 km/L the car's range is 83 km, so a stop is unavoidable. The five stations ahead are the planner's
 * example (기획 계산식): each has the whole route through it to 강릉.
 */
class FixtureRouteSource(private val remainingKm: BigDecimal = BigDecimal("100")) : RouteSource {
    /**
     * Three made-up routes to 강릉 for the departure comparison (예시 데이터, not a route API). At the minimum wage
     * (10,320원 an hour, the default) the 70 minutes the 고속도로 saves outweigh the 7,575원 more it costs to run.
     */
    override fun choices(energyType: EnergyType): RouteChoices = RouteChoices(
        destinationName = "강릉",
        options = listOf(
            RouteOption("highway", "고속도로", BigDecimal("230"), 160, BigDecimal("10000"), highway = true),
            RouteOption("national", "국도", BigDecimal("250"), 230, BigDecimal.ZERO, highway = false),
            RouteOption("mixed", "혼합 경로", BigDecimal("240"), 195, BigDecimal("5000"), highway = true),
        ),
        origin = DataOrigin.PLAN_EXAMPLE,
    )

    override fun active(energyType: EnergyType): ActiveRoute? =
        if (energyType.isElectric) null else ActiveRoute(
            destinationName = "강릉",
            remainingKm = remainingKm,
            stations = FixtureStations.ON_THE_WAY,
            origin = DataOrigin.PLAN_EXAMPLE,
            // The fastest way on without a stop is the route station A sits on.
            noStop = RouteLeg(BigDecimal("100"), BigDecimal("80"), BigDecimal("3000")),
        )
}

/** The saved places navigation would offer; demo data until the Pleos bookmark API is verified on the emulator. */
class FixturePlaceSource : PlaceSource {
    override fun saved(): List<SavedPlace> = listOf(SavedPlace("parents", "본가", FixtureStations.PARENTS, DataOrigin.PLAN_EXAMPLE))
}

/** One step of a scenario: [offsetMs] after the phase starts, emit the sample built for that time. */
data class TimedSample(val offsetMs: Long, val build: (atMs: Long) -> VehicleSample)

/**
 * The plan's two scenarios as vehicle-data timelines. Each scenario has two phases so the demo can
 * wait for the driver's "응" between the low-energy moment and the refill.
 */
object Scenarios {
    private val O = DataOrigin.MOCK

    /** 시나리오 B (전기차): parked near the office at 25 % of 77.4 kWh. */
    fun evLowBattery(capacityKwh: Double = 77.4, socPercent: Double = 25.0): List<TimedSample> = listOf(
        TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) },
        TimedSample(100) { LocationSample(it, FixtureStations.HOME, O) },
        TimedSample(200) { ChargeHintSample(it, portOpen = false, switchOn = false, origin = O) },
        TimedSample(300) { EnergySample(it, capacityKwh * socPercent / 100, socPercent, capacityKwh, O) },
    )

    /** Scenario B as the plan tells it (p.17): parked at the office at [socPercent], above the threshold; the driver asks for a station. */
    fun evParked(capacityKwh: Double = 77.4, socPercent: Double = 45.0): List<TimedSample> = listOf(
        TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) },
        TimedSample(100) { LocationSample(it, FixtureStations.HOME, O) },
        TimedSample(200) { ChargeHintSample(it, portOpen = false, switchOn = false, origin = O) },
        TimedSample(300) { EnergySample(it, capacityKwh * socPercent / 100, socPercent, capacityKwh, O) },
    )

    /** Arrive at B, plug in, add [addKwh] in [steps] steps, unplug. Default: 19.35 → 59.35 kWh. */
    fun evChargeAtB(
        startKwh: Double = 19.35,
        addKwh: Double = 40.0,
        capacityKwh: Double = 77.4,
        steps: Int = 8,
        stepMs: Long = 1_500,
    ): List<TimedSample> = buildList {
        add(TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) })
        add(TimedSample(100) { LocationSample(it, FixtureStations.EV_B.location!!, O) })
        add(TimedSample(300) { ChargeHintSample(it, portOpen = true, switchOn = true, origin = O) })
        for (i in 1..steps) {
            val kwh = startKwh + addKwh * i / steps
            add(TimedSample(300 + i * stepMs) { EnergySample(it, kwh, kwh / capacityKwh * 100, capacityKwh, O) })
        }
        val end = 300 + (steps + 1) * stepMs
        add(TimedSample(end) { ChargeHintSample(it, portOpen = false, switchOn = false, origin = O) })
        add(TimedSample(end + 3_000) { EnergySample(it, startKwh + addKwh, (startKwh + addKwh) / capacityKwh * 100, capacityKwh, O) })
    }

    /** 시나리오 A (내연기관): fuel at 10 % of a 60 L tank, parked (room for the plan's 50 L). */
    /** Before departure (scenario A step 1): parked at home with enough fuel for 강릉 (40 of 60 L, over the threshold). */
    fun fuelDeparture(tankL: Double = 60.0, litres: Double = 40.0): List<TimedSample> = listOf(
        TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) },
        TimedSample(100) { LocationSample(it, FixtureStations.HOME, O) },
        TimedSample(300) { EnergySample(it, litres, litres / tankL * 100, tankL, O) },
    )

    /**
     * 시연 C (운전 습관, spec D-35..D-38): one fuel-car trip with every kind of waste, about 14 minutes of driving
     * played in half a minute. The samples carry their own times (seconds into the trip), so the amounts do not depend
     * on how fast the timeline is played: 8 minutes idling → a harsh start → a harsh acceleration → a hard brake
     * (recorded only) → 6.25 km at 125 km/h under a 100 km/h limit → parked, engine off.
     */
    fun habitTrip(tankL: Double = 60.0, litres: Double = 40.0): List<TimedSample> {
        var start: Long? = null
        var wall = 0L
        val out = mutableListOf<TimedSample>()
        fun at(sec: Int, afterMs: Long, parked: Boolean, kmh: Double, engine: Boolean = true, limit: Int? = null) {
            wall += afterMs
            out += TimedSample(wall) { now ->
                val t0 = start ?: now.also { start = it }
                DriveSample(t0 + sec * 1000L, parked, if (parked) "P" else "D", O, speedKmh = kmh, engineOn = engine, speedLimitKmh = limit)
            }
        }
        at(0, 0, parked = true, kmh = 0.0)
        out += TimedSample(50) { LocationSample(it, FixtureStations.HOME, O) }
        out += TimedSample(100) { EnergySample(it, litres, litres / tankL * 100, tankL, O) }
        for (sec in 60..420 step 60) at(sec, 500, parked = true, kmh = 0.0) // idling in P: past 180 s at the 240 s sample
        at(480, 3_500, parked = false, kmh = 0.0, limit = 50)
        at(481, 400, parked = false, kmh = 14.0, limit = 50) // 급출발
        at(482, 400, parked = false, kmh = 26.0, limit = 50) // the same acceleration
        at(483, 400, parked = false, kmh = 30.0, limit = 50)
        listOf(32.0, 34.0, 36.0, 38.0, 40.0, 40.0).forEachIndexed { i, v -> at(490 + i * 10, 400, parked = false, kmh = v, limit = 50) }
        at(550, 400, parked = false, kmh = 40.0, limit = 50)
        at(551, 400, parked = false, kmh = 50.0, limit = 50) // 급가속
        at(552, 400, parked = false, kmh = 52.0, limit = 50)
        at(560, 400, parked = false, kmh = 52.0, limit = 50)
        at(561, 400, parked = false, kmh = 38.0, limit = 50) // 급감속: recorded only
        listOf(70.0, 90.0, 105.0, 110.0, 110.0).forEachIndexed { i, v -> at(570 + i * 10, 400, parked = false, kmh = v, limit = 100) }
        for (sec in 620..790 step 10) at(sec, 300, parked = false, kmh = 125.0, limit = 100) // 180 s over 100 × 1.1
        listOf(100.0, 60.0, 30.0, 10.0).forEachIndexed { i, v -> at(800 + i * 10, 400, parked = false, kmh = v, limit = 100) }
        at(840, 800, parked = true, kmh = 0.0)
        at(843, 600, parked = true, kmh = 0.0, engine = false)
        return out
    }

    fun fuelLow(tankL: Double = 60.0, percent: Double = 10.0): List<TimedSample> = listOf(
        TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) },
        TimedSample(100) { LocationSample(it, FixtureStations.HOME, O) },
        TimedSample(300) { EnergySample(it, tankL * percent / 100, percent, tankL, O) },
    )

    /**
     * Arrive at a station ([at], B by default), fill [addL] litres, drive off (fuel cars give no charge hint, so
     * leaving ends the refill), then park at home: the level-up celebration waits for that stop (spec R-46).
     */
    fun fuelRefillAtB(
        startL: Double = 6.0,
        addL: Double = 50.0,
        tankL: Double = 60.0,
        stepMs: Long = 1_500,
        at: GeoPoint = FixtureStations.FUEL_B.location!!,
    ): List<TimedSample> = buildList {
        add(TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) })
        add(TimedSample(100) { LocationSample(it, at, O) })
        for (i in 1..5) {
            val l = startL + addL * i / 5
            add(TimedSample(300 + i * stepMs) { EnergySample(it, l, l / tankL * 100, tankL, O) })
        }
        val leave = 300 + 7 * stepMs
        add(TimedSample(leave) { DriveSample(it, parked = false, gear = "D", origin = O) })
        add(TimedSample(leave + 5_000) { LocationSample(it, FixtureStations.HOME, O) })
        add(TimedSample(leave + 5_100) { DriveSample(it, parked = true, gear = "P", origin = O) })
    }
}

/**
 * A [VehicleSource] that replays scenario phases. Samples get the scheduler's current time,
 * so detection runs exactly as with live data. [refresh] re-sends the latest sample of each kind.
 */
class ReplayVehicleSource(
    override val energyType: EnergyType,
    private val scheduler: Scheduler,
    private val clock: Clock,
) : VehicleSource {
    private val listeners = mutableListOf<(VehicleSample) -> Unit>()
    private val latest = LinkedHashMap<Class<*>, VehicleSample>()
    private val pending = mutableListOf<Cancellable>()

    override fun subscribe(listener: (VehicleSample) -> Unit): Cancellable {
        listeners += listener
        latest.values.forEach(listener)
        return Cancellable { listeners -= listener }
    }

    override fun refresh() {
        val now = clock.nowMs()
        latest.values.toList().forEach { emit(restamp(it, now)) }
    }

    /** Plays one phase; [onDone] runs after its last sample. */
    fun play(phase: List<TimedSample>, onDone: () -> Unit = {}) {
        phase.forEach { step ->
            pending += scheduler.schedule(step.offsetMs) { emit(step.build(clock.nowMs())) }
        }
        val last = phase.maxOfOrNull { it.offsetMs } ?: 0
        pending += scheduler.schedule(last + 1) { onDone() }
    }

    fun stop() {
        pending.forEach { it.cancel() }
        pending.clear()
    }

    private fun emit(sample: VehicleSample) {
        latest[sample.javaClass] = sample
        listeners.toList().forEach { it(sample) }
    }

    private fun restamp(s: VehicleSample, t: Long): VehicleSample = when (s) {
        is EnergySample -> s.copy(atMs = t)
        is ChargeHintSample -> s.copy(atMs = t)
        is DriveSample -> s.copy(atMs = t)
        is LocationSample -> s.copy(atMs = t)
    }
}

/** Deterministic scheduler for tests: time only moves when [advanceTo] is called. */
class ManualScheduler : Scheduler, Clock {
    private data class Task(val atMs: Long, val seq: Long, val action: () -> Unit, var cancelled: Boolean = false)

    private var now = 0L
    private var seq = 0L
    private val tasks = mutableListOf<Task>()

    override fun nowMs(): Long = now

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val task = Task(now + delayMs, seq++, action)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    fun advanceTo(targetMs: Long) {
        while (true) {
            val next = tasks.filter { !it.cancelled && it.atMs <= targetMs }.minWithOrNull(compareBy({ it.atMs }, { it.seq })) ?: break
            tasks -= next
            now = next.atMs
            next.action()
        }
        now = maxOf(now, targetMs)
    }

    fun advanceBy(deltaMs: Long) = advanceTo(now + deltaMs)
}
