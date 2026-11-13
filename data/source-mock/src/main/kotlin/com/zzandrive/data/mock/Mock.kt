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
 * 시나리오 A (기획서 p.16~17): Seoul → 강릉 on a family trip, 220 km left (example data). With 6 L in a 60 L tank at
 * 10 km/L the car reaches 60 km, so a stop is unavoidable (③). The plan's two stations are along the way.
 */
class FixtureRouteSource(private val remainingKm: BigDecimal = BigDecimal("220")) : RouteSource {
    /**
     * Three made-up routes to 강릉 for the departure comparison (예시 데이터, not a route API). At the default 5,000원
     * an hour the slower 국도 wins; at 10,000원 the 고속도로 does, so the wage setting visibly matters.
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
            stations = listOf(
                FixtureStations.FUEL_A.copy(aheadKm = BigDecimal("1"), onRoute = false),
                FixtureStations.FUEL_B.copy(aheadKm = BigDecimal("30"), onRoute = true),
            ),
            origin = DataOrigin.PLAN_EXAMPLE,
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

    fun fuelLow(tankL: Double = 60.0, percent: Double = 10.0): List<TimedSample> = listOf(
        TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) },
        TimedSample(100) { LocationSample(it, FixtureStations.HOME, O) },
        TimedSample(300) { EnergySample(it, tankL * percent / 100, percent, tankL, O) },
    )

    /**
     * Arrive at B, fill [addL] litres, drive off (fuel cars give no charge hint, so leaving ends the
     * refill), then park at home: the level-up celebration waits for that stop (spec R-46).
     */
    fun fuelRefillAtB(startL: Double = 6.0, addL: Double = 50.0, tankL: Double = 60.0, stepMs: Long = 1_500): List<TimedSample> = buildList {
        add(TimedSample(0) { DriveSample(it, parked = true, gear = "P", origin = O) })
        add(TimedSample(100) { LocationSample(it, FixtureStations.FUEL_B.location!!, O) })
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
