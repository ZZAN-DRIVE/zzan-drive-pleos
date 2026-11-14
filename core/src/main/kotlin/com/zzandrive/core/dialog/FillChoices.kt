package com.zzandrive.core.dialog

import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.data.api.EnergyType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The answers to "얼마나 넣을까?" as chips and as ids for debug commands (기획서 p.5: "주유·충전량(L/kWh) 또는 금액").
 * ids: "full", "q40" (L or kWh), "m10000" (won), "skip".
 */
object FillChoices {
    private val QUANTITY = Regex("""q(\d+(?:\.\d+)?)""")
    private val MONEY = Regex("""m(\d+)""")

    fun parse(id: String): FillAnswer? = when {
        id == "full" -> FillAnswer.Full
        id == "skip" -> FillAnswer.Skip
        QUANTITY.matches(id) -> FillAnswer.Quantity(BigDecimal(QUANTITY.matchEntire(id)!!.groupValues[1]))
        MONEY.matches(id) -> FillAnswer.Money(BigDecimal(MONEY.matchEntire(id)!!.groupValues[1]))
        else -> null
    }

    /** Short label for the card and the ledger: "가득", "40kWh", "1만 원", "건너뜀". */
    fun label(answer: FillAnswer, unit: String): String = when (answer) {
        FillAnswer.Full -> "가득"
        FillAnswer.Skip -> "건너뜀"
        is FillAnswer.Quantity -> answer.amount.stripTrailingZeros().toPlainString() + unit
        is FillAnswer.Money -> won(answer.won)
    }

    /** (id, label) chips, most common first; "가득" shows the litres or kWh it means when the level is known. */
    fun options(type: EnergyType, room: BigDecimal?): List<Pair<String, String>> {
        val full = room?.let { "가득 · 약 ${it.setScale(0, RoundingMode.HALF_UP).toPlainString()}${type.unit}" } ?: "가득"
        return if (type.isElectric) {
            listOf("full" to full, "q40" to "40kWh", "m10000" to "1만 원", "m20000" to "2만 원", "skip" to "건너뛰기 · 거리만")
        } else {
            listOf("full" to full, "q30" to "30L", "q50" to "50L", "m30000" to "3만 원", "m50000" to "5만 원", "skip" to "건너뛰기 · 거리만")
        }
    }

    private fun won(amount: BigDecimal): String {
        val w = amount.setScale(0, RoundingMode.HALF_UP).toLong()
        return if (w >= 10_000 && w % 10_000 == 0L) "${w / 10_000}만 원" else String.format(Locale.ROOT, "%,d원", w)
    }
}
