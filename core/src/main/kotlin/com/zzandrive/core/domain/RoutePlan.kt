package com.zzandrive.core.domain

import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.RouteChoices
import com.zzandrive.data.api.RouteOption
import java.math.BigDecimal

/**
 * One route's numbers before departure (기획서 3-2 표, spec D-31). The station price and the trip to a station are left
 * out: this comparison is for a destination the car can reach ("목적지까지 갈 수 있는 경우").
 */
data class RouteResult(
    val option: RouteOption,
    /** L or kWh the route needs: distance ÷ efficiency. */
    val energy: BigDecimal,
    /** Toll counted only on expressway routes (기획서 3-2 "시내 주행 비교에서는 제외"). */
    val toll: BigDecimal,
    /** 예상 운행비 = 통행료 + 거리 ÷ 연비·전비 × 평균 단가. */
    val operatingCost: BigDecimal,
    /** 시간 환산 = 총소요시간(분) ÷ 60 × 시급. Weighed only; never saved as savings (D-31). */
    val timeValue: BigDecimal,
    /** With the level known: whether the car arrives above the alert level (spec D-30); null when unknown. */
    val branch: RouteBranch? = null,
) {
    /** 기획서 3-2: a route that reaches the alert level on the way needs a station in the comparison (not yet offered). */
    val needsStop: Boolean get() = branch != null && branch != RouteBranch.REACHABLE

    /** 추천 비교값 = 운행비 + 시간 환산. */
    val value: BigDecimal get() = operatingCost.add(timeValue, MC)
}

/** The routes side by side, the best by comparison value, and the one to weigh it against out loud. */
data class RouteComparison(
    val destinationName: String,
    /** In the route source's order. */
    val results: List<RouteResult>,
    val efficiency: Efficiency,
    /** Average price the fuel or electricity is valued at (the survey average, as for distance-only comparisons). */
    val unitPrice: BigDecimal,
    val wagePerHour: BigDecimal,
    val origin: DataOrigin,
) {
    init {
        require(results.isNotEmpty()) { "no routes" }
    }

    /** Lowest comparison value among the routes the car reaches without a stop (all routes if none); ties: shorter time. */
    val best: RouteResult
        get() = (results.filter { !it.needsStop }.ifEmpty { results })
            .minWith(compareBy<RouteResult> { it.value }.thenBy { it.option.minutes })

    private val candidates: List<RouteResult> get() = results.filter { !it.needsStop }.ifEmpty { results }

    val fastest: RouteResult get() = candidates.minWith(compareBy<RouteResult> { it.option.minutes }.thenBy { it.operatingCost })

    val cheapest: RouteResult get() = candidates.minWith(compareBy<RouteResult> { it.operatingCost }.thenBy { it.option.minutes })

    /** What [best] gives up or gains against: the cheapest to run if that is another route, else the fastest; null if [best] is both. */
    val rival: RouteResult?
        get() = when {
            cheapest.option.id != best.option.id -> cheapest
            fastest.option.id != best.option.id -> fastest
            else -> null
        }

    /** Minutes [best] saves against [rival] (negative: slower). */
    val minutesFaster: Int get() = rival?.let { it.option.minutes - best.option.minutes } ?: 0

    /** Won [best] costs more to run than [rival] (negative: cheaper), whole won. */
    val costMoreWon: Long get() = rival?.let { best.operatingCost.subtract(it.operatingCost, MC).toWon() } ?: 0
}

object RoutePlanner {
    /** Default hourly wage for the time value (D-08, D-31). */
    val DEFAULT_WAGE_PER_HOUR: BigDecimal = PlanReference.TIME_VALUE_PER_HOUR

    /** Wages the settings offer (won per hour): the minimum wage first, then higher values of one's own time. */
    val WAGE_CHOICES: List<Int> = listOf(PlanReference.MINIMUM_WAGE_PER_HOUR.toInt(), 15_000, 20_000)

    /** [energyNow] and [thresholdAmount] (L or kWh), when known, mark the routes that reach the alert level on the way. */
    fun compare(
        choices: RouteChoices,
        efficiency: Efficiency,
        unitPrice: BigDecimal,
        wagePerHour: BigDecimal,
        energyNow: BigDecimal? = null,
        thresholdAmount: BigDecimal? = null,
    ): RouteComparison? {
        if (choices.options.isEmpty()) return null
        val results = choices.options.map { o ->
            val toll = if (o.highway) o.tollWon else BigDecimal.ZERO
            RouteResult(
                branch = if (energyNow != null && thresholdAmount != null) {
                    Reachability.classify(energyNow, thresholdAmount, o.distanceKm, efficiency.kmPerUnit)
                } else {
                    null
                },
                option = o,
                energy = o.distanceKm.divide(efficiency.kmPerUnit, MC),
                toll = toll,
                operatingCost = CostCalculator.routeOperatingCost(toll, listOf(RouteSegment(o.distanceKm, efficiency.kmPerUnit, unitPrice))),
                timeValue = CostCalculator.comparisonValue(BigDecimal.ZERO, BigDecimal(o.minutes), wagePerHour),
            )
        }
        return RouteComparison(choices.destinationName, results, efficiency, unitPrice, wagePerHour, choices.origin)
    }
}
