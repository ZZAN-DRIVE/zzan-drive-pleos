package com.zzandrive.core.domain

import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.StationOffer
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal val MC: MathContext = MathContext.DECIMAL64
private val SIXTY = BigDecimal(60)

fun bd(value: String): BigDecimal = BigDecimal(value)
fun bd(value: Int): BigDecimal = BigDecimal(value)
fun bd(value: Double): BigDecimal = BigDecimal.valueOf(value)

/** spec 3.2 / D-12: amounts are exact until confirmation, then rounded to whole won once (half up). */
fun BigDecimal.toWon(): Long = setScale(0, RoundingMode.HALF_UP).longValueExact()

/** km per L (fuel) or km per kWh (electric), with where the number came from (D-14). */
data class Efficiency(val kmPerUnit: BigDecimal, val origin: DataOrigin)

/** One station's real cost for a given quantity (spec F02/F03). */
data class CostBreakdown(
    val offer: StationOffer,
    val quantity: BigDecimal,
    val efficiency: BigDecimal,
    val energyCost: BigDecimal,
    val detourCost: BigDecimal,
    val toll: BigDecimal,
) {
    val total: BigDecimal get() = energyCost.add(detourCost, MC).add(toll, MC)
}

data class RouteSegment(val distanceKm: BigDecimal, val efficiency: BigDecimal, val unitPrice: BigDecimal)

object CostCalculator {
    /** distance ÷ efficiency × unit price (기획서 3-1 "10 km 예상 금액", spec TV-05). */
    fun energyCost(distanceKm: BigDecimal, efficiency: BigDecimal, unitPrice: BigDecimal): BigDecimal =
        distanceKm.divide(efficiency, MC).multiply(unitPrice, MC)

    /**
     * 실질 비용 = 양 × 단가 + 추가 이동거리 ÷ 연비·전비 × 우회 평가 단가 + 추가 통행료.
     * The detour is valued at **this station's own price** (D-02): only then do the plan's
     * examples (860원, 2,308원) come out, and settlement equals the recommendation difference.
     */
    fun realCost(quantity: BigDecimal, offer: StationOffer, efficiency: BigDecimal): CostBreakdown =
        CostBreakdown(
            offer = offer,
            quantity = quantity,
            efficiency = efficiency,
            energyCost = quantity.multiply(offer.unitPrice, MC),
            detourCost = energyCost(offer.roundTripDetourKm, efficiency, offer.unitPrice),
            toll = offer.extraToll,
        )

    /**
     * Getting there only, with the refuel/charge price left out (기획서 p.5: 양·금액을 답하지 않으면 "주유·충전 비용은
     * 계산에서 제외하고 경로만 비교"). The trip is valued at [referencePrice] (the survey average, p.5 표) so that
     * no station's own price sneaks back into the comparison (spec D-25).
     */
    fun travelCost(offer: StationOffer, efficiency: BigDecimal, referencePrice: BigDecimal): CostBreakdown =
        CostBreakdown(
            offer = offer,
            quantity = BigDecimal.ZERO,
            efficiency = efficiency,
            energyCost = BigDecimal.ZERO,
            detourCost = energyCost(offer.roundTripDetourKm, efficiency, referencePrice),
            toll = offer.extraToll,
        )

    /** 예상 운행비 = 최종 통행료 + Σ 구간별 (거리 ÷ 연비·전비 × 단가) (spec F04). */
    fun routeOperatingCost(finalToll: BigDecimal, segments: List<RouteSegment>): BigDecimal =
        segments.fold(finalToll) { acc, s -> acc.add(energyCost(s.distanceKm, s.efficiency, s.unitPrice), MC) }

    /** 추천 비교값 = 운행비 + 총소요시간(분) ÷ 60 × 시간가치. Time value never enters real savings. */
    fun comparisonValue(cost: BigDecimal, totalMinutes: BigDecimal, timeValuePerHour: BigDecimal): BigDecimal =
        cost.add(totalMinutes.divide(SIXTY, MC).multiply(timeValuePerHour, MC), MC)
}

/** Reference numbers from the plan (조사 2026-11-07) and its demo assumptions. */
object PlanReference {
    data class Survey(val energyType: EnergyType, val vehicle: String, val efficiency: BigDecimal, val unitPrice: BigDecimal)

    /** 전국 주유소 단순평균(부가세 포함) / 충전 100~200kW 미만 기준. Shown with [DataOrigin.SURVEY]. */
    val SURVEY_2026_11_07: List<Survey> = listOf(
        Survey(EnergyType.GASOLINE, "아반떼 1.6 IVT·15인치", bd("15.3"), bd("1855.43")),
        Survey(EnergyType.DIESEL, "2025 투싼 2.0 2WD·8AT·17인치", bd("14.3"), bd("1841.76")),
        Survey(EnergyType.ELECTRIC, "2024 아이오닉 6 LR 2WD·18인치", bd("6.0"), bd("348.4")),
    )

    fun survey(type: EnergyType): Survey = SURVEY_2026_11_07.first { it.energyType == type }

    /** 기획서 3-1 예시의 시연 가정 (조사 평균값 아님). */
    val DEMO_FUEL_EFFICIENCY = Efficiency(bd("10"), DataOrigin.PLAN_EXAMPLE)
    val DEMO_EV_EFFICIENCY = Efficiency(bd("5"), DataOrigin.PLAN_EXAMPLE)

    /** Default time value (D-08). */
    val TIME_VALUE_PER_HOUR: BigDecimal = bd("5000")
}
