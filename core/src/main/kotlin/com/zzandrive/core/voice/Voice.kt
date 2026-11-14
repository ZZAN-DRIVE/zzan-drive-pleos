package com.zzandrive.core.voice

import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.SkipReason
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

/** spec D-12 / TV-19: exact amounts on screen, rounded amounts in speech. */
object WonFormat {
    /** Screen: whole won with grouping, e.g. "2,308원". */
    fun screen(won: Long): String = String.format(Locale.ROOT, "%,d원", won)

    /** Screen with decimals for reference values, e.g. "1,212.70원". */
    fun screen(amount: BigDecimal, scale: Int): String =
        String.format(Locale.ROOT, "%,.${scale}f원", amount.setScale(scale, java.math.RoundingMode.HALF_UP))

    /**
     * Speech: 1,000원 and up rounds to 100원 and says "약" when it changed it;
     * below 1,000원 rounds to 10원 (so 860원 stays exact). Negative amounts say "마이너스".
     */
    fun spoken(won: Long): String {
        val magnitude = abs(won)
        val step = if (magnitude >= 1_000) 100L else 10L
        val rounded = ((magnitude + step / 2) / step) * step
        val approx = if (rounded != magnitude) "약 " else ""
        val sign = if (won < 0) "마이너스 " else ""
        return approx + sign + String.format(Locale.ROOT, "%,d원", rounded)
    }

    fun spoken(amount: BigDecimal): String = spoken(amount.toWon())
}

/** Korean particles that depend on the last syllable (받침): 가/이, 를/을, 로/으로. */
object Josa {
    private fun batchim(word: String): Int? {
        val c = word.trimEnd().lastOrNull() ?: return null
        return if (c in '가'..'힣') (c - '가') % 28 else null
    }

    /** 이/가: "B 충전소가", "판교점이". Non-Korean endings read as no 받침 ("B가"). */
    fun ga(word: String): String = word + if ((batchim(word) ?: 0) != 0) "이" else "가"

    /** 을/를. */
    fun reul(word: String): String = word + if ((batchim(word) ?: 0) != 0) "을" else "를"

    /** 이면/면: "가득이면", "3만 원이면", "40kWh면" (a Latin unit reads as 킬로와트시·리터, no 받침). */
    fun myeon(word: String): String = word + if ((batchim(word) ?: 0) != 0) "이면" else "면"

    /** 은/는. */
    fun neun(word: String): String = word + if ((batchim(word) ?: 0) != 0) "은" else "는"

    /** 과/와. */
    fun wa(word: String): String = word + if ((batchim(word) ?: 0) != 0) "과" else "와"

    /** 으로/로 (ㄹ 받침은 "로"). */
    fun ro(word: String): String {
        val b = batchim(word) ?: 0
        return word + if (b != 0 && b != 8) "으로" else "로"
    }
}

/** The two characters keep the same level and money (R-45); only voice wording differs. */
enum class Persona(val displayName: String) {
    ZZANDORI("짠돌이"),
    ZZANSUNI("짠순이"),
}

/**
 * Template lines filled from structured results (spec F05): the numbers always come from the
 * calculator, never from free text. Short lines while driving (R-30).
 */
class VoiceLines(private val persona: Persona) {
    private fun ask(): String = if (persona == Persona.ZZANDORI) "들를까?" else "들를래?"
    private fun end(): String = if (persona == Persona.ZZANDORI) "!" else "~"

    fun recommendCharge(baselineName: String, bestName: String, savingWon: Long): String =
        "${baselineName}보다 ${Josa.ga(bestName)} ${WonFormat.spoken(savingWon)} 이득이야. ${ask()}"

    fun recommendRefuel(baselineName: String, bestName: String, savingWon: Long): String =
        "${baselineName}보다 ${Josa.ga(bestName)} ${WonFormat.spoken(savingWon)} 이득이야. 거기로 갈까?"

    /** "가까운 X" once, even when the station's own name already starts with 가까운. */
    private fun near(name: String): String = if (name.startsWith("가까운")) name else "가까운 $name"

    /** The nearest is also the cheapest: still a question, since the driver is asked to say "응". */
    fun nearestIsCheapest(name: String): String = "${Josa.ga(near(name))} 제일 싸. ${q("들를")}"

    /**
     * 기획서 3-2: when routes reach the alert level on the way, the plain comparison does not hold. [best] null: every
     * route needs a stop (stations go into the comparison later); otherwise [best] is the one that does not.
     */
    fun routeStops(electric: Boolean, best: String?, stopNames: List<String> = emptyList(), advice: String? = null): String {
        val stop = if (electric) "충전해야" else "기름을 넣어야"
        if (best == null) return "어느 길로 가도 도착 전에 알림 기준 아래로 내려가. 도중에 $stop 해."
        val others = when (stopNames.size) { // only the routes that do need a stop are named
            0 -> "다른 길은"
            1 -> Josa.neun(stopNames[0])
            2 -> "${Josa.wa(stopNames[0])} ${Josa.neun(stopNames[1])}"
            else -> Josa.neun(stopNames.joinToString(", "))
        }
        return (advice ?: "${Josa.ro(best)} 가면 도중에 안 들르고 도착해.") + " $others 도중에 $stop 해."
    }

    /** A recommendation the driver did not hear while driving, asked once more at the stop. */
    fun askAgainAtStop(name: String): String = "아까 못 들었지? $name ${q("들를")}"

    /** A spoken "다음에"/"됐어" to a question: said so the driver knows it was heard. */
    fun askClosed(): String = "알았어, 이번엔 안 물어볼게."

    /** While driving: the best place and the amount only, no second station name to hold in mind. */
    fun recommendShort(bestName: String, savingWon: Long): String =
        "${Josa.ga(bestName)} ${WonFormat.spoken(savingWon)} 이득이야. ${ask()}"

    /** Before the amount question when the level crossed the threshold, so the driver knows why the app speaks. */
    fun lowLevel(electric: Boolean, percent: Int): String =
        if (electric) "배터리가 $percent% 남았어." else "기름이 $percent% 남았어."

    fun navigationStarted(name: String): String = "좋아, ${Josa.ro(name)} 안내할게${end()}"

    fun declined(): String = if (persona == Persona.ZZANDORI) "알겠어, 이번엔 그냥 갈게." else "알았어, 다음에 아끼자~"

    fun savingConfirmed(savingWon: Long): String = "${WonFormat.spoken(savingWon)} 아꼈어${end()}"

    fun levelUp(food: String, nextFood: String?, remainingWon: Long?): String =
        if (nextFood == null || remainingWon == null) "$food 레벨 달성! 최고 레벨이야${end()}"
        else "$food 레벨 달성! ${nextFood}까지 ${WonFormat.spoken(remainingWon)} 남았어${end()}"

    fun remaining(nextFood: String, remainingWon: Long): String =
        "${nextFood}까지 ${WonFormat.spoken(remainingWon)} 남았어${end()}"

    /** One line, character voice; null when nothing should be said (e.g. a refill with no recommendation). */
    fun notCredited(reason: SkipReason): String? = reason.spoken.ifBlank { null }

    // ---- 기획서 flow: one question at a time, numbers only from the calculator ----

    private fun q(stem: String): String = if (persona == Persona.ZZANDORI) "${stem}까?" else "${stem}래?"

    /** p.5 ④ "현재 위치 기준인지 다른 장소 기준인지". */
    fun askReference(): String = "어디 근처로 ${q("찾을")}"

    /** p.5 "주유·충전량(L/kWh) 또는 금액을 먼저 묻고". */
    fun askFill(electric: Boolean): String = "얼마나 ${q(if (electric) "충전할" else "넣을")}"

    /** p.5 "답하지 않으면 … 경로만 비교": no price, so no saving is promised. */
    fun routeOnly(name: String, km: String): String = "제일 ${Josa.ga(near(name))} ${km}킬로야. ${q("들를")}"

    /** p.5 ⑤: nothing within 5 km of road. */
    fun nearestOnly(name: String, km: String): String = "5킬로 안엔 없어. 제일 가까운 ${Josa.ga(name)} ${km}킬로야. ${q("갈")}"

    /** p.5 ⑥ with a route: the cheapest on the route, no comparison. */
    fun onRoute(name: String, unitPriceWon: Long, unit: String): String =
        "가는 길에 제일 싼 ${name}, ${unit}당 ${WonFormat.screen(unitPriceWon)}이야. ${q("들를")}"

    /** p.4 ③: the destination is out of range, a stop is needed. */
    fun mustStop(electric: Boolean, bestName: String, savingWon: Long, fillEcho: String? = null): String =
        "이대로는 목적지까지 못 가. ${fillEcho?.let { "$it " } ?: ""}${Josa.ga(bestName)} ${WonFormat.spoken(savingWon)} 덜 들어. ${q("들를")}"

    /**
     * 기획 계산식 1 (spec D-34): out of range, the route with the lowest whole-trip spend. "시간까지 치면" tells the
     * driver the amount counts the driving time too.
     */
    fun mustStopRoute(bestName: String, savingWon: Long, fillEcho: String? = null): String =
        "이대로는 목적지까지 못 가. ${fillEcho?.let { "$it " } ?: ""}시간까지 치면 ${Josa.ga(bestName)} ${WonFormat.spoken(savingWon)} 덜 들어. ${q("들를")}"

    /** Out of range with a single place within reach: nothing to compare. */
    fun onlyStop(name: String): String = "이대로는 목적지까지 못 가. 갈 수 있는 데가 ${name}뿐이야. ${q("들를")}"

    /** 기획 계산식 2: the destination is within range, but stopping at [bestName] costs less than going on. */
    fun stopPays(bestName: String, savingWon: Long): String =
        "${bestName}에 들렀다 가면 그냥 가는 것보다 ${WonFormat.spoken(savingWon)} 덜 들어. ${q("들를")}"

    /** 기획 계산식 2: within range and not stopping is the cheapest. A statement: there is nothing to answer. */
    fun noStopNeeded(electric: Boolean, nextName: String? = null, moreWon: Long? = null): String =
        (if (electric) "지금은 충전 안 해도 돼." else "지금은 안 넣어도 돼.") +
            (if (nextName != null && moreWon != null) " ${nextName}에 들르면 ${WonFormat.spoken(moreWon)} 더 들어." else "")

    /** "왜 거기야?" for a whole-route comparison. */
    fun explainRoute(electric: Boolean, bestName: String, savingWon: Long): String =
        "${if (electric) "충전비" else "기름값"}, 통행료, 시간을 다 합치면 ${Josa.ga(bestName)} ${WonFormat.spoken(savingWon)} 덜 들어${end()}"

    /** While driving without voice answers: no question the driver cannot answer. */
    fun drivingNotice(electric: Boolean): String =
        (if (electric) "배터리가 얼마 안 남았어. 차 세우면 싼 충전소 보여줄게" else "기름이 얼마 안 남았어. 차 세우면 싼 주유소 보여줄게") + end()

    /** "왜 거기야?": the reason in one line, without totals that would round differently. */
    fun explain(bestName: String, unit: String, cheaperByWon: Long, savingWon: Long): String =
        "${Josa.ga(bestName)} ${unit}당 ${WonFormat.screen(cheaperByWon)} 싸서, 더 가도 ${WonFormat.spoken(savingWon)} 덜 들어${end()}"

    fun nearestChosen(name: String): String = "${Josa.ga(near(name))} 제일 싸서 골랐어${end()}"

    /** spec D-28, said after stopping. A deduction is said exactly, as on the screen. */
    fun opportunityCost(name: String, won: Long): String = "${Josa.reul(name)} 그냥 지나와서 ${WonFormat.screen(won)} 뺐어."

    // ---- 운전 습관 (기획서 3-5, spec D-35..D-38): one short line while driving, the amount only after the stop ----

    fun harshAccelNudge(): String = if (persona == Persona.ZZANDORI) "어이쿠, 살살 밟자!" else "어머, 살살 밟자~"

    fun speedingNudge(): String = "조금만 천천히 가자${end()}"

    fun idleNudge(): String = "시동 켠 채로 3분 넘었어. 기름이 새고 있어${end()}"

    /**
     * After the trip, parked: why and how much, exactly as on the screen. [floored]: the level's accrual ran out before
     * the whole amount was taken (it never goes below 0 and the level never drops), so the line says that instead.
     */
    fun tripPenalty(food: String?, won: Long, reasons: List<String>, floored: Boolean): String {
        val why = reasons.joinToString(", ")
        return if (floored) "앗, $why 때문에 이번 레벨 적립액이 다 줄었어…"
        else "앗, $why 때문에 ${food?.let { "내 $it " } ?: ""}${WonFormat.screen(won)} 날아갔어…"
    }

    /** Never asks a moving driver to work the navigation. */
    fun navigationFailed(driving: Boolean = false): String =
        if (driving) "내비 연결이 안 돼. 차 세우고 안내를 켜 줘." else "내비 연결이 안 돼. 직접 안내를 켜 줘."

    /**
     * 기획서 3-2, spec D-31: before departure, the best route against its rival in minutes and won to run
     * (spoken amounts rounded like every other line). The time value is only weighed, never saved.
     */
    fun routeAdvice(best: String, rival: String?, minutesFaster: Int, costMoreWon: Long, wageWon: Long): String {
        val b = Josa.ga(best)
        val wage = "시급 ${WonFormat.screen(wageWon)}으로 치면 $b 나아${end()}"
        return when {
            rival == null -> "$b 제일 빠르고 운행비도 제일 적어${end()}"
            minutesFaster > 0 && costMoreWon > 0 -> "$b ${rival}보다 ${minutesFaster}분 빠르고 ${WonFormat.spoken(costMoreWon)} 더 들어. $wage"
            minutesFaster < 0 && costMoreWon < 0 -> "$b ${rival}보다 ${-minutesFaster}분 느리지만 ${WonFormat.spoken(-costMoreWon)} 덜 들어. $wage"
            minutesFaster > 0 && costMoreWon < 0 -> "$b ${rival}보다 ${minutesFaster}분 빠르고 ${WonFormat.spoken(-costMoreWon)} 덜 들어${end()}"
            minutesFaster > 0 -> "$b ${rival}보다 ${minutesFaster}분 빠르고 운행비는 같아${end()}"
            minutesFaster == 0 && costMoreWon < 0 -> "$b ${rival}보다 ${WonFormat.spoken(-costMoreWon)} 덜 들어${end()}"
            else -> "$b 제일 나아${end()}"
        }
    }

    /** After "왜 거기야?": the consent question again, short. */
    fun askAgain(): String = q("들를")

    /** One re-ask when the answer was not understood (Google conversation design: once, then stop). */
    /** The question again with the ways to answer, so a driver who missed it knows what is asked. */
    fun reprompt(expect: com.zzandrive.core.dialog.Expect, electric: Boolean = false, place: String? = null): String = when (expect) {
        com.zzandrive.core.dialog.Expect.CONSENT -> (place?.let { "$it ${q("들를")} " } ?: "") + "응, 아니로 말해줘${end()}"
        com.zzandrive.core.dialog.Expect.FILL ->
            "${askFill(electric)} 가득, ${if (electric) "몇 킬로와트" else "몇 리터"}, 얼마어치로 말해줘${end()}"
        com.zzandrive.core.dialog.Expect.REFERENCE -> "${askReference()} 여기, 아니면 저장한 곳 이름을 말해줘${end()}"
    }
}
