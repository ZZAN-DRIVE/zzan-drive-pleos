package com.zzandrive.core.domain

import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.StationOffer
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Rules the plan (기획서) leaves as "계산식 : 추후 정의". These are the app's own definitions
 * (spec D-22..D-30), kept in one place so they are easy to change.
 */

/** How a recommendation compares places. */
enum class CompareMode {
    /** Price included: the driver said how much (p.5 "응답값을 기준으로 계산"). */
    COST_INCLUDED,

    /** No amount given: getting there only, price left out (p.5 "경로만 비교"). */
    ROUTE_ONLY,

    /** Nothing within 5 km of road: the nearest one only (p.5 ⑤). */
    NEAREST_ONLY,

    /** Asked while a route is active: the cheapest on the route, no comparison (p.5 ⑥). */
    ON_ROUTE,
}

/** What a confirmed refill is compared with when it is credited (spec D-29). */
enum class CreditRule {
    /** Everyday case: against the nearest candidate (기준 = 가장 가까운 곳, spec D-03). */
    VS_NEAREST,

    /** A stop is unavoidable (②-1·③): against the costliest reachable choice (p.8 −1,000/−3,000/−5,000 → 4,000원). */
    VS_WORST,

    /** No price comparison was made, so nothing can be credited. */
    NONE,
}

/** The driver's answer to "얼마나 넣을까?" (p.5, p.9). */
sealed interface FillAnswer {
    /** Litres or kWh. */
    data class Quantity(val amount: BigDecimal) : FillAnswer

    /** Up to the capacity. */
    data object Full : FillAnswer

    /** An amount of money in won (p.6 "금액으로 답한 경우"). */
    data class Money(val won: BigDecimal) : FillAnswer

    /** No answer, "건너뛰기", or not understood: compare the trip only (p.5). */
    data object Skip : FillAnswer
}

/** spec D-23: turning the answer into the quantity every candidate is priced with. */
object FillPolicy {
    /**
     * Quantity: as said, capped by the room left. Full: the room left. Money: won ÷ the nearest candidate's
     * price, the same quantity for every candidate (spec D-23). Skip: null.
     * Settlement always uses the quantity the car measured, never this one.
     */
    fun quantity(answer: FillAnswer, room: BigDecimal?, nearestPrice: BigDecimal?): BigDecimal? {
        val raw = when (answer) {
            is FillAnswer.Quantity -> answer.amount
            FillAnswer.Full -> room ?: return null
            is FillAnswer.Money -> {
                if (nearestPrice == null || nearestPrice.signum() <= 0) return null
                answer.won.divide(nearestPrice, 4, RoundingMode.HALF_UP)
            }
            FillAnswer.Skip -> return null
        }
        val capped = if (room != null && room.signum() > 0 && raw > room) room else raw
        return capped.takeIf { it.signum() > 0 }
    }
}

/** spec D-26: candidates within 5 km of road from the reference place (p.5 ④⑤⑥). */
object CandidatePolicy {
    val RADIUS_KM: BigDecimal = BigDecimal("5.0")

    /** (inside, outside), boundary included. */
    fun split(offers: List<StationOffer>, radiusKm: BigDecimal = RADIUS_KM): Pair<List<StationOffer>, List<StationOffer>> =
        offers.partition { it.oneWayKm <= radiusKm }

    fun nearest(offers: List<StationOffer>): StationOffer? =
        offers.minWithOrNull(compareBy<StationOffer>({ it.oneWayKm }, { it.unitPrice }, { it.id }))
}

/** p.4 3-1-1 with a destination: ② reachable, ②-1 reachable but hits the threshold on the way, ③ unreachable. */
enum class RouteBranch { REACHABLE, HITS_THRESHOLD, UNREACHABLE }

/** spec D-30. Amounts are litres or kWh, distances km, efficiency km per unit. */
object Reachability {
    /** Plan a stop only where 90 % of the remaining range reaches (never send the car to a place it barely reaches). */
    val MARGIN: BigDecimal = BigDecimal("0.9")

    fun classify(energyNow: BigDecimal, thresholdAmount: BigDecimal, routeKm: BigDecimal, efficiency: BigDecimal): RouteBranch {
        val left = energyNow.subtract(routeKm.divide(efficiency, MC), MC)
        return when {
            left > thresholdAmount -> RouteBranch.REACHABLE
            left.signum() >= 0 -> RouteBranch.HITS_THRESHOLD
            else -> RouteBranch.UNREACHABLE
        }
    }

    /** How far ahead a stop may be: before the threshold (②-1) or within the range (③). */
    fun limitKm(branch: RouteBranch, energyNow: BigDecimal, thresholdAmount: BigDecimal, efficiency: BigDecimal): BigDecimal? = when (branch) {
        RouteBranch.REACHABLE -> null
        RouteBranch.HITS_THRESHOLD -> energyNow.subtract(thresholdAmount, MC).multiply(efficiency, MC).max(BigDecimal.ZERO)
        RouteBranch.UNREACHABLE -> energyNow.multiply(efficiency, MC).multiply(MARGIN, MC)
    }

    /** Stations the car can get to under [limitKm]; offers without [StationOffer.aheadKm] are not on the route and left out. */
    fun reachable(offers: List<StationOffer>, limitKm: BigDecimal?): List<StationOffer> =
        if (limitKm == null) offers else offers.filter { o -> o.aheadKm?.let { it <= limitKm } ?: false }
}

/**
 * spec D-22: the plan's default is "the level where the low-fuel warning light comes on" (p.4), but apps cannot
 * read the warning lights (Pleos `PRIVATE_CAR_SAFETY`, signature|privileged). So the threshold is a setting.
 */
object TriggerPolicy {
    const val DEFAULT_EV = 25
    const val DEFAULT_FUEL = 20
    const val MIN = 10
    const val MAX = 40
    const val STEP = 5

    /** After one recommendation, wait until the level is this far above the threshold before asking again. */
    const val REARM_MARGIN = 5

    fun default(type: EnergyType): Int = if (type.isElectric) DEFAULT_EV else DEFAULT_FUEL

    fun clamp(percent: Int): Int = (percent.coerceIn(MIN, MAX) / STEP) * STEP

    fun fires(levelPercent: Double, threshold: Int): Boolean = levelPercent <= threshold

    fun rearms(levelPercent: Double, threshold: Int): Boolean = levelPercent > threshold + REARM_MARGIN
}
