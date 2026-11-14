package com.zzandrive.data.api

import java.math.BigDecimal

/** Drives the mode switch (R-10): fuel modes refuel in litres, electric charges in kWh. */
enum class EnergyType(val unit: String) {
    GASOLINE("L"),
    DIESEL("L"),
    ELECTRIC("kWh");

    val isElectric: Boolean get() = this == ELECTRIC
}

/**
 * Where a value came from. Every number shown to the driver carries one of these
 * so mock values are never presented as vehicle data (spec 0.2-2).
 */
enum class DataOrigin(val label: String) {
    PLEOS_SDK("차량 실측"),
    AAOS("차량 실측"),
    MOCK("시연 데이터"),
    SURVEY("조사값(2026-11-07)"),
    /** Prices, distances and routes made up for the demo. */
    PLAN_EXAMPLE("예시 데이터"),
    USER("사용자 설정"),
}

data class GeoPoint(val lat: Double, val lon: Double)

/** A raw reading from the vehicle. [atMs] is the reading's own time, so late or batched delivery keeps order. */
sealed interface VehicleSample {
    val atMs: Long
    val origin: DataOrigin
}

/**
 * Stored energy. [amount] is kWh for electric (from battery Wh) or litres for fuel; null when not readable.
 * [socPercent] and [capacity] (same unit as [amount]) allow the SOC fallback when [amount] is missing.
 */
data class EnergySample(
    override val atMs: Long,
    val amount: Double?,
    val socPercent: Double?,
    val capacity: Double?,
    override val origin: DataOrigin,
) : VehicleSample

/** Charging hints. null means "this vehicle/source cannot tell" (e.g. fuel cars, unreadable property). */
data class ChargeHintSample(
    override val atMs: Long,
    val portOpen: Boolean?,
    val switchOn: Boolean?,
    override val origin: DataOrigin,
) : VehicleSample {
    val known: Boolean get() = portOpen != null || switchOn != null
    val active: Boolean get() = portOpen == true || switchOn == true
}

/**
 * Parked = gear P (spec D-15). The driving readings are optional: a source that cannot tell leaves them null, and then
 * the driving habits (spec D-35..D-37) are simply not evaluated.
 */
data class DriveSample(
    override val atMs: Long,
    val parked: Boolean,
    val gear: String?,
    override val origin: DataOrigin,
    /** Speed in km/h. */
    val speedKmh: Double? = null,
    /** The engine is running (fuel cars). */
    val engineOn: Boolean? = null,
    /** The legal speed limit of the road in km/h. */
    val speedLimitKmh: Int? = null,
) : VehicleSample

data class LocationSample(
    override val atMs: Long,
    val point: GeoPoint,
    override val origin: DataOrigin,
) : VehicleSample

fun interface Cancellable {
    fun cancel()
}

/** A source of vehicle readings (Pleos SDK, AAOS or a mock timeline). */
interface VehicleSource {
    val energyType: EnergyType

    /** Delivers readings to [listener] until cancelled. */
    fun subscribe(listener: (VehicleSample) -> Unit): Cancellable

    /** Asks for a fresh reading of everything; results arrive through the listener. */
    fun refresh()
}

/**
 * A whole route in numbers, from here to the destination: for a station, the route that stops there
 * (기획 계산식 "주유소를 포함한 경로"). [minutes] is driving time only; refuelling time is left out.
 */
data class RouteLeg(val km: BigDecimal, val minutes: BigDecimal, val tollWon: BigDecimal = BigDecimal.ZERO)

/** A refuel/charge place with its price. Money is BigDecimal so no float rounding leaks into settlement. */
data class StationOffer(
    val id: String,
    val name: String,
    val energyType: EnergyType,
    /** 원/L or 원/kWh. */
    val unitPrice: BigDecimal,
    /** Extra round-trip distance compared with not stopping (km). */
    val roundTripDetourKm: BigDecimal,
    val extraToll: BigDecimal = BigDecimal.ZERO,
    val location: GeoPoint? = null,
    /** Detour + wait + refuel/charge time in minutes, when known (recorded only; not part of the cost now). */
    val totalMinutes: BigDecimal? = null,
    val origin: DataOrigin,
    /**
     * One-way **road** distance from the reference place in km (기획서 p.5: "직선거리가 아니라 실제 차량 도로
     * 주행거리"). null means half of [roundTripDetourKm].
     */
    val roadKm: BigDecimal? = null,
    /** Distance ahead along the current route in km, for the reachability branches ②-1·③; null off-route. */
    val aheadKm: BigDecimal? = null,
    /** On the current route (기획서 p.5 ⑥ "경로상"). */
    val onRoute: Boolean = false,
    /** The whole route through this station to the destination, when a route source knows it (spec D-32). */
    val route: RouteLeg? = null,
) {
    /** One-way road distance used for the 5 km rule and for "the nearest". */
    val oneWayKm: BigDecimal get() = roadKm ?: roundTripDetourKm.divide(BigDecimal(2))
}

interface StationSource {
    fun offers(energyType: EnergyType, near: GeoPoint?): List<StationOffer>

    /**
     * Candidates around a reference place for the plan's search (p.5 ④⑤⑥), with road distances measured
     * from that place. May include stations beyond 5 km; the 5 km rule is applied by the core.
     */
    fun candidates(energyType: EnergyType, near: GeoPoint?): List<StationOffer> = offers(energyType, near)
}

/** A place the driver saved in navigation, offered as "다른 장소" for the search (p.5 ④). */
data class SavedPlace(val id: String, val name: String, val point: GeoPoint, val origin: DataOrigin)

fun interface PlaceSource {
    fun saved(): List<SavedPlace>
}

/**
 * The route navigation is guiding (기획서 p.4 3-1-1 ①②③): where to, how far is left, and stations along it with
 * [StationOffer.aheadKm] (distance from here) and [StationOffer.roundTripDetourKm] (extra distance to stop there).
 */
data class ActiveRoute(
    val destinationName: String,
    val remainingKm: BigDecimal,
    val stations: List<StationOffer>,
    val origin: DataOrigin,
    /** The fastest way on without a stop (기획 계산식 "주유하지 않은 경로"); null when the source cannot tell. */
    val noStop: RouteLeg? = null,
    /** 기준 단가 for this route's comparison; null means the survey average. */
    val referencePrice: BigDecimal? = null,
)

fun interface RouteSource {
    fun active(energyType: EnergyType): ActiveRoute?

    /** The routes to the destination before departure (기획서 3-2), or null when no route API answers. */
    fun choices(energyType: EnergyType): RouteChoices? = null
}

/**
 * One route a route API returns for the destination (기획서 3-2): length, time and the route's total toll.
 * The toll counts only on expressway routes ("시내 주행 비교에서는 제외"), so [highway] says which kind it is.
 */
data class RouteOption(
    val id: String,
    val name: String,
    val distanceKm: BigDecimal,
    val minutes: Int,
    val tollWon: BigDecimal,
    val highway: Boolean,
)

data class RouteChoices(val destinationName: String, val options: List<RouteOption>, val origin: DataOrigin)

interface Clock {
    fun nowMs(): Long
}

/** Runs actions later; Android uses a Handler, tests use a manual clock. */
interface Scheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}
