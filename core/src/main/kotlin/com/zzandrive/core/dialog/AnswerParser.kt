package com.zzandrive.core.dialog

/**
 * Closed-set understanding of the driver's short spoken answers (기획서 p.9·p.19: "응", "아니", "왜 거기야?",
 * "가까운 데로 가", 양·금액). Recognized text in, one meaning out; anything else is [Heard.Unknown], which never acts.
 *
 * Consent is the one answer that makes the app act (navigation), so it is read strictly:
 * - "응" counts only when the whole answer is made of yes words ("응", "응 들를게", "거기로 가", "좋아요"). A question
 *   or a longer sentence that merely starts with or contains one ("어디야?", "그래서 얼마야?", "다른 데 가줘") is not.
 * - A refusal wins ("응 아니야", "그냥 갈게", "나중에 갈게").
 * - "가까운 데로 가" must be an order to go there: the answer ends with the order, and nothing but a plain lead-in
 *   ("아니", "그냥", "거기 말고") comes before it. A question, a condition or the connective "가까운데" is not.
 * - "그만"/"취소" stop only as a command ("그만 좀 물어봐"), not inside a sentence ("그만큼 아끼면 가야지").
 * - Amounts and places: the last one said wins ("20리터 아니 30리터", "본가 말고 여기"); one turned down or put off
 *   ("가득은 너무 많아", "다음에 가득 넣을게", "본가는 싫어") is not the answer.
 */
sealed interface Heard {
    data object Yes : Heard
    data object No : Heard
    data object Why : Heard
    data object Nearest : Heard
    data object Stop : Heard

    /** An answer to "얼마나 넣을까?" as a [FillChoices] id ("full", "q20", "m30000", "skip"). */
    data class Fill(val choiceId: String) : Heard

    /** An answer to "어디 근처로 찾을까?": "current" or a saved place id. */
    data class Place(val id: String) : Heard

    data object Unknown : Heard
}

/** What the last question was, so the same words mean the right thing. */
enum class Expect { CONSENT, REFERENCE, FILL }

object AnswerParser {
    private const val END = "[\\s.!~]*$"

    /** A stop command: "그만", "그만해", "취소 취소", "조용히 좀 해", "아 그만", "그만 좀 물어봐". */
    private val STOP = Regex(
        "^((아|어|에이|아휴|알았어|됐어|됐고)[\\s,.!~]*)?(이제\\s*)?(그만|취소|조용히)(\\s*(그만|취소))*\\s*(좀)?\\s*" +
            "(해|하자|해\\s*줘|해요|해\\s*주세요|해라|물어봐|물어보지\\s*마|말해)?\\s*(좀)?\\s*(요)?$END",
    )

    private val WHY = Regex("^왜|왜\\s*거기|이유")

    private val NO = Regex(
        "아니|아냐|아뇨|아닙|아녜|싫|됐어|됐습|됐다|안\\s*(가|갈|들|돼)|다음에|나중에|이따|필요\\s*없|패스|^(노|no)$END|" +
            "그냥\\s*(갈게|가자|갈래|지나|가$END)|지나\\s*갈|(가|들르)지\\s*(말자|마)|" +
            "^((아|음|아이|어|전|저는|난|나는|아직|지금은|거긴|거기|아니)\\s*)*(괜찮(아|아요|습니다|다)?|별로(야|예요|요|다)?)(\\s*괜찮(아|아요))*$END",
    )

    /** "가까운 데" as a place: 데 apart, or 데 with 로/에 ("가까운데로"). "가까운데 가자" (it is close, so) is not. */
    private val NEAREST_PHRASE = Regex("가까운(\\s+데|데(?=\\s*(로|으로|에)(\\s|$))|\\s*(곳|거|주유소|충전소))")
    private val NEAREST_ORDER = Regex(
        "^\\s*(로|으로|에)?\\s*(그냥\\s*)?(가|가자|가요|가자니까|가라니까|가\\s*줘|가\\s*줘요|가\\s*주세요|갈래|가라|가\\s*봐|" +
            "들르자|들러|들러\\s*줘|들러\\s*주세요|들를래|안내\\s*해\\s*(줘|줘요|주세요)?|안내해|안내)$END",
    )

    /** What may come before the order: a refusal of the recommended place or a plain filler, nothing else. */
    private val NEAREST_LEAD = Regex(
        "^((아니|아냐|아뇨|싫어|싫고|거기\\s*말고|거긴\\s*말고|그거\\s*말고|말고|그냥|제일|응|어|네|예|좋아|그래|그럼|아|음)[\\s,.!~]*)*$",
    )

    /** The whole answer, spaces and punctuation removed, is yes words only (each may end in 요). A trailing "?" is a question. */
    private val YES_WORDS = Regex(
        "^(지금)?((응|웅|어|그래|그럼|좋아|좋지|좋다|좋습니다|네|예|오케이|ok|ㅇㅇ|콜|고고|당연하지|가자|가보자|가봐|가야지|가|" +
            "들를게|들르자|들러보자|들러야지|들러|들러줘|들러주세요|갈게|가줘|가주세요|그러자|그렇게해주세요|그렇게해줘|그렇게해|그렇게하자|" +
            "부탁해|알았어|알겠어|알겠습니다|안내해줘|안내해주세요|거기로|거기)(요)?)+$",
    )
    private val ONLY_THERE = Regex("^(거기로?)+$")

    private val FULL = Regex("가득|만\\s*땅|풀로|완충|만\\s*충|풀\\s*충전|꽉(?!\\s*막)")
    private val SKIP_STRONG = Regex("건너뛰|몰라|아무거나|상관없|얼마든")
    private val SKIP_WEAK = Regex("그냥|가까운")
    private val FILL_REFUSAL = Regex(
        "다음에|나중에|됐어|됐습|싫|안\\s*(가|갈|넣|충전|해도)|패스|괜찮아|괜찮습|필요\\s*없|충분(해|하|합)|넣지\\s*마|(넣|충전하)지\\s*말",
    )
    private val FILL_NO = Regex("아니|아냐|아뇨")

    /** An amount turned down right after it is said: "가득은 너무 많아", "5만 원은 됐어", "완충까지는 필요 없어". */
    private val TURNED_DOWN = Regex(
        "^\\s*(은|는|까지는|까진|이|가|이나|도)?\\s*(좀\\s*)?(" +
            "너무\\s*많|많(아|다|네|은데)|말고|아니|못\\s|안\\s(?!\\s*(넘|되게))|안$|필요\\s*(는\\s*)?없|무리|빼고|됐|싫|넣지|채우지|하지\\s*마|" +
            "안\\s*해도|(넣|채우|충전하)\\s*진\\s*(마|말)|(넣|채|충전|할|칠)\\S*\\s*(필요\\s*(는\\s*)?없|싫|생각\\s*없)|" +
            "(다음|나중|내일|이따|담에|주말))",
    )
    private val PUT_OFF = Regex("다음\\s*(에|엔|번)|담에|나중(에|엔)?|내일|이따|주말|다음\\s*주")

    /** "나중에 말고 지금": a put-off taken back is not a put-off. */
    private val PUT_OFF_TAKEN_BACK = Regex("(다음\\s*(에|엔|번)?|담에|나중(에|엔)?|내일|이따)\\s*(말고|아니고)")

    /** Where a clause starts again ("다음에는 많이 넣고 오늘은 2만 원만"): a put-off before it does not reach past it. */
    private val CLAUSE = Regex("지금|오늘|고\\s|는데|니까|,")

    /** After the last of these, what follows is the answer ("가득 아니고 3만 원", "본가 말고 여기"). */
    private val CORRECTION = Regex("말고|아니고|아니\\s*다|아니야|아니|됐고")

    private val HERE = Regex(
        "여기|현재\\s*위치|지금\\s*위치|지금\\s*(있는\\s*)?(곳|데|여기)|^지금(요)?$END|^현재(요)?$END|이\\s*근처|내\\s*(위치|근처)|" +
            "^(내가\\s*)?있는\\s*(곳|데)|지금\\s*있는",
    )
    private val NOT_HERE = Regex("가는|목적지|회사|집|도착|갈\\s*곳")
    private val PLACE_RULED_OUT = Regex(
        "^(\\s*(쪽|근처|주변|방향))?\\s*(으로|로|에서|서)?\\s*(은|는|이|가|도)?\\s*(싫|빼고|제외|아닌|아니|안\\s*돼|너무\\s*멀|멀어|별로)",
    )
    private val REFERENCE_STOP = Regex("됐어|다음에|나중에|그만")

    private const val NUM_K = "영일이삼사오육칠팔구십백"
    private val QUANTITY = Regex(
        "(?<![^\\s])([0-9]+(?:\\s*(?:\\.|점|쩜)\\s*[0-9]+)?|[$NUM_K][$NUM_K\\s]*?(?:\\s*(?:점|쩜)\\s*(?:[영일이삼사오육칠팔구]+|[0-9]+))?)\\s*" +
            "(리터|ℓ|l(?![a-z])|킬로와트시|키로와트시|킬로와트|키로와트|kwh|킬로|키로)",
        RegexOption.IGNORE_CASE,
    )
    private val LITRE_UNITS = setOf("리터", "ℓ", "l")
    private val BARE_KM = setOf("킬로", "키로")

    /** After a bare 킬로 an EV answer is kWh only in these words; anything else is a distance ("200킬로 탈 만큼"). */
    private val KWH_TAIL = Regex("^\\s*(요|만|만요|정도|쯤|충전|넣|채|까지|이요|이면)")
    private val DISTANCE = Regex("갈|가면|가는|가게|가야|주행|달리|달릴|탈\\s|타고|남았|남아|거리|이상|더\\s|운전|운행|이동|뛰|뛸|버티|미터")
    private val DISTANCE_BEFORE = Regex("까지|거리|왕복|주행|남은|하루에")

    /** Bare 킬로 above this is not a charge (no battery holds it): a distance. */
    private const val MAX_BARE_KWH = 120

    /** A sum of money starting at a word boundary: digits, commas and numerals (만 included) before 원. */
    private val MONEY = Regex("(?<![^\\s])([0-9,일이삼사오육칠팔구십백천만][0-9,일이삼사오육칠팔구십백천만\\s]*)원")

    /**
     * Money said without 원, in the strict shape of a sum: a 만 group with an optional rest ("3만", "만 오천", "이만 오천"),
     * or a 천 group ("오천", "5천"). What follows must be a particle or the end, so "삼만이요" is 30,000, not 30,002.
     */
    private val MONEY_BARE = Regex(
        "(?<![^\\s])(" +
            "(?:[0-9]+|[일이삼사오육칠팔구십백천]+)?\\s*만(?:\\s*(?:(?:[0-9]+|[일이삼사오육칠팔구])?\\s*천(?:\\s*(?:[0-9]+|[일이삼사오육칠팔구])?\\s*백)?|" +
            "[0-9]{1,4}(?![0-9]|\\s*[천백십])))?" +
            "|(?:[0-9]+|[일이삼사오육칠팔구])\\s*천(?:\\s*[일이삼사오육칠팔구]?백)?" +
            ")(?=\\s|$|[.!~?]|정도|쯤|어치|만큼|이요|이에요|이면|으로|이|요|만)",
    )
    private val ENOUGH_NOW = Regex("^\\s*(됐|하자|할게|끝|그만|가자|가\\s*보자|마치)")
    private val NOT_A_SUM_AFTER = Regex("^\\s*(쪽|가는|휴게소|으로|근처|에서|방향|땅|충)")
    private val UNIT_PRICE_BEFORE = Regex("당\\s*$")
    private val LONE_MAN_OK = Regex("^\\s*(이요|이에요|이면|정도|쯤|어치|요)")

    /** Sums outside this range are taken as a mishearing (one refuel or charge, spec D-23). */
    private const val MIN_WON = 1_000L
    private const val MAX_WON = 500_000L

    /**
     * [electric]: the car's energy type when known; an answer in the other unit (litres for an EV, kWh for a fuel car)
     * is not taken, so "20리터" never becomes 20 kWh.
     */
    fun parse(text: String?, expect: Expect, places: List<Pair<String, String>> = emptyList(), electric: Boolean? = null): Heard {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return Heard.Unknown
        if (STOP.matches(t)) return Heard.Stop
        return when (expect) {
            Expect.CONSENT -> consent(t)
            Expect.REFERENCE -> reference(t, places)
            Expect.FILL -> fill(t, electric)
        }
    }

    private fun consent(said: String): Heard {
        val t = PUT_OFF_TAKEN_BACK.replace(said, "").trim()
        NEAREST_PHRASE.findAll(t).lastOrNull()?.let { m ->
            val order = NEAREST_ORDER.matches(t.substring(m.range.last + 1)) && NEAREST_LEAD.matches(t.substring(0, m.range.first))
            return when {
                order -> Heard.Nearest // "아니, 가까운 데로 가": the refusal is of the recommended place
                WHY.containsMatchIn(t) -> Heard.Why
                else -> Heard.Unknown // "왜 …", "막히면 가까운 데로 가", "가까운 데 말고": ask again rather than guess
            }
        }
        return when {
            WHY.containsMatchIn(t) -> Heard.Why
            NO.containsMatchIn(t) -> Heard.No
            yesOnly(t) -> Heard.Yes
            else -> Heard.Unknown
        }
    }

    private fun yesOnly(t: String): Boolean {
        val end = t.trimEnd()
        if (end.endsWith("?") || end.endsWith("…") || end.endsWith("..")) return false // "응?" pardon, "어…" hesitation
        val s = t.replace(Regex("[\\s.,!~…]+"), "")
        return YES_WORDS.matches(s) && !ONLY_THERE.matches(s)
    }

    private fun afterCorrection(t: String): String = CORRECTION.findAll(t).lastOrNull()?.let { t.substring(it.range.last + 1) } ?: t

    private fun reference(t: String, places: List<Pair<String, String>>): Heard {
        val corrected = CORRECTION.containsMatchIn(t)
        val s = afterCorrection(t) // "본가 말고 여기": the place after 말고 is the answer
        val named = mutableListOf<Pair<Int, Heard>>()
        places.forEach { (id, name) ->
            Regex(Regex.escape(name.lowercase())).findAll(s).forEach { m ->
                if (!PLACE_RULED_OUT.containsMatchIn(s.substring(m.range.last + 1))) named += m.range.first to Heard.Place(id)
            }
        }
        if (!NOT_HERE.containsMatchIn(s)) {
            HERE.findAll(s).forEach { m ->
                if (!PLACE_RULED_OUT.containsMatchIn(s.substring(m.range.last + 1))) named += m.range.first to Heard.Place("current")
            }
        }
        // A place ruled out later in the answer is taken back even where it was named before ("여기서 찾아 아 여기는 싫어").
        val ruledOutLater = mutableListOf<Pair<Int, Heard>>()
        places.forEach { (id, name) ->
            Regex(Regex.escape(name.lowercase())).findAll(s).forEach { m ->
                if (PLACE_RULED_OUT.containsMatchIn(s.substring(m.range.last + 1))) ruledOutLater += m.range.first to Heard.Place(id)
            }
        }
        HERE.findAll(s).forEach { m ->
            if (PLACE_RULED_OUT.containsMatchIn(s.substring(m.range.last + 1))) ruledOutLater += m.range.first to Heard.Place("current")
        }
        named.filter { (at, h) -> ruledOutLater.none { (rAt, rh) -> rAt > at && rh == h } }
            .maxByOrNull { it.first }?.let { return it.second }
        if (REFERENCE_STOP.containsMatchIn(t)) return Heard.Stop
        if (!corrected && yesOnly(t)) return Heard.Place("current")
        return Heard.Unknown
    }

    private class Mention(val start: Int, val end: Int, val heard: Heard)

    private fun fill(raw: String, electric: Boolean?): Heard {
        val text = PUT_OFF_TAKEN_BACK.replace(raw) { " ".repeat(it.value.length) } // same positions, put-off gone
        val said = mutableListOf<Mention>()
        FULL.findAll(text).forEach { m -> said += Mention(m.range.first, m.range.last + 1, Heard.Fill("full")) }
        val withWon = MONEY.findAll(text).toList()
        withWon.forEach { m ->
            if (UNIT_PRICE_BEFORE.containsMatchIn(text.substring(0, m.range.first))) return@forEach // "리터당 2천 원" is a price
            amount(m.groupValues[1])?.takeIf { it in MIN_WON..MAX_WON }
                ?.let { said += Mention(m.range.first, m.range.last + 1, Heard.Fill("m$it")) }
        }
        MONEY_BARE.findAll(text).forEach { m ->
            val sum = m.groupValues[1].trim()
            val tail = text.substring(m.range.last + 1)
            val inside = withWon.any { m.range.first in it.range } // "만 오천 원" is one sum, not 만 and 오천
            val enoughNow = sum == "이만" && ENOUGH_NOW.containsMatchIn(tail) // "이만 됐어", "이만 가자"
            val placeOrFull = (!sum.contains('만') || sum == "만") && NOT_A_SUM_AFTER.containsMatchIn(tail) // "이천 가는 길", "만 땅"
            // A lone 만 is 10,000 only before 이요/이면/정도/어치 or as the whole answer, never "40리터 만 넣어" or "만 땅".
            val loneMan = sum == "만" && !(LONE_MAN_OK.containsMatchIn(tail) || (m.range.first == 0 && tail.isBlank()))
            val price = UNIT_PRICE_BEFORE.containsMatchIn(text.substring(0, m.range.first))
            if (!inside && !enoughNow && !placeOrFull && !loneMan && !price) {
                amount(sum)?.takeIf { it in MIN_WON..MAX_WON }
                    ?.let { said += Mention(m.range.first, m.range.last + 1, Heard.Fill("m$it")) }
            }
        }
        QUANTITY.findAll(text).forEach { m ->
            val unit = m.groupValues[2].lowercase()
            val litres = unit in LITRE_UNITS
            val tail = text.substring(m.range.last + 1)
            val n = quantity(m.groupValues[1])
            val distance = unit in BARE_KM && (
                !(tail.isBlank() || (KWH_TAIL.containsMatchIn(tail) && !DISTANCE.containsMatchIn(tail))) ||
                    DISTANCE_BEFORE.containsMatchIn(text.substring(0, m.range.first)) ||
                    (n?.toBigDecimalOrNull()?.let { it > MAX_BARE_KWH.toBigDecimal() } ?: false)
                )
            val wrongUnit = (electric == true && litres) || (electric == false && !litres) || (electric != false && distance)
            said += Mention(m.range.first, m.range.last + 1, if (wrongUnit || n == null) Heard.Unknown else Heard.Fill("q$n"))
        }
        // An amount turned down or put off right after it, or put off earlier in its own clause, is not the answer; neither is
        // the same amount said before and then taken back ("가득 넣어 줘 아니다 가득은 됐어").
        val refused = said.filter { m ->
            val before = text.substring(0, m.start)
            val clause = CLAUSE.findAll(before).lastOrNull()?.let { before.substring(it.range.last + 1) } ?: before
            TURNED_DOWN.containsMatchIn(text.substring(m.end)) || PUT_OFF.containsMatchIn(clause)
        }
        val kept = said.filter { m -> m !in refused && refused.none { r -> r.start > m.start && r.heard == m.heard } }
        kept.maxByOrNull { it.start }?.let { return it.heard }
        // No amount: "몰라" skips the price; "다음에", "됐어", "괜찮아" close the question (not now).
        if (SKIP_STRONG.containsMatchIn(text)) return Heard.Fill("skip")
        if (FILL_REFUSAL.containsMatchIn(text)) return Heard.Stop
        if (said.isNotEmpty()) return Heard.Unknown // an amount was said and turned down, nothing else: ask again
        if (SKIP_WEAK.containsMatchIn(text)) return Heard.Fill("skip")
        if (FILL_NO.containsMatchIn(text)) return Heard.Stop
        return Heard.Unknown
    }

    /** A quantity as said: digits ("25.5"), Korean numerals ("삼십 오"), with a spoken decimal ("이십오 점 오"). */
    private fun quantity(raw: String): String? {
        val r = raw.trim()
        r.toBigDecimalOrNull()?.let { return it.takeIf { v -> v.signum() > 0 }?.stripTrailingZeros()?.toPlainString() }
        r.replace(Regex("\\s*(점|쩜)\\s*"), ".").replace(" ", "").toBigDecimalOrNull()
            ?.let { return it.takeIf { v -> v.signum() > 0 }?.stripTrailingZeros()?.toPlainString() } // "25 점 5"
        val parts = r.split(Regex("점|쩜"))
        val whole = number(parts[0]) ?: return null
        val decimals = parts.getOrNull(1)?.trim()?.map { c -> c.digitToIntOrNull()?.toLong() ?: DIGITS[c] ?: return null }
            ?.joinToString("") ?: ""
        return if (decimals.isEmpty()) whole.toString() else "$whole.$decimals".toBigDecimal().stripTrailingZeros().toPlainString()
    }

    /** Sino-Korean numerals or digits up to 9,999 ("이십" = 20, "삼천오백" = 3500, "삼십 오" = 35, "이십 삼십" = 30). */
    fun number(s: String): Long? = read(s)?.takeIf { it > 0 && it < 10_000 }

    /**
     * A sum in won with 만 groups, in digits, Korean numerals or both: "만" = 10,000, "만 오천" = 15,000,
     * "3만 5천" = 35,000, "2만 5000" = 25,000, "15,000" = 15,000. Words that do not continue the sum start it again,
     * so the last one said wins: "2 3만" = 30,000, "이만 삼만" = 30,000. Null when it is not a number or absurdly large.
     */
    fun amount(s: String): Long? = read(s)

    /** Reads words one by one; a word whose largest unit is not below the previous word's smallest starts a new number. */
    private fun read(s: String): Long? {
        val joined = s.replace(Regex("(?<=\\d),(?=\\d{3}(?!\\d))"), "") // the thousands comma only
            .replace(Regex("(?<=[0-9일이삼사오육칠팔구])\\s+(?=[만천백십])"), "") // "이 만" is 이만, "오 천" is 오천
        val words = joined.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var total = 0L
        var lowest = Int.MAX_VALUE
        for (w in words) {
            val (value, high, low) = word(w) ?: return null
            if (high >= lowest) total = 0L // "2 3만", "이만 삼만", "이십 삼십": a new number, the last one wins
            total += value
            lowest = low
            if (total > 100L * MAX_WON) return null
        }
        return total.takeIf { it > 0 }
    }

    /** One word: (value, largest unit rank, smallest unit rank); ranks 만 4, 천 3, 백 2, 십 1, plain digits 0. */
    private fun word(w: String): Triple<Long, Int, Int>? {
        var total = 0L
        var section = 0L
        var current = 0L
        var high = -1
        var low = Int.MAX_VALUE
        var i = 0
        while (i < w.length) {
            val c = w[i]
            when {
                c.isDigit() -> {
                    val start = i
                    while (i < w.length && w[i].isDigit()) i++
                    current = w.substring(start, i).toLongOrNull()?.takeIf { it <= 10L * MAX_WON } ?: return null
                    continue
                }
                c in DIGITS -> current = DIGITS.getValue(c)
                c in UNITS -> {
                    val rank = RANK.getValue(c)
                    section += (if (current == 0L) 1L else current) * UNITS.getValue(c)
                    current = 0L
                    high = maxOf(high, rank)
                    low = minOf(low, rank)
                }
                c == '만' -> {
                    val n = section + current
                    total += (if (n == 0L) 1L else n) * 10_000L
                    section = 0L
                    current = 0L
                    high = 4
                    low = 4
                }
                else -> return null
            }
            i++
        }
        if (current > 0) low = 0
        if (high < 0) high = 0
        if (low == Int.MAX_VALUE) low = 0
        return Triple(total + section + current, high, low)
    }

    private val DIGITS = mapOf('영' to 0L, '일' to 1L, '이' to 2L, '삼' to 3L, '사' to 4L, '오' to 5L, '육' to 6L, '칠' to 7L, '팔' to 8L, '구' to 9L)
    private val UNITS = mapOf('십' to 10L, '백' to 100L, '천' to 1_000L)
    private val RANK = mapOf('십' to 1, '백' to 2, '천' to 3)
}
