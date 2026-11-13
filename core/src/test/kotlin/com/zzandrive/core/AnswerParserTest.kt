package com.zzandrive.core

import com.zzandrive.core.dialog.AnswerParser
import com.zzandrive.core.dialog.Expect
import com.zzandrive.core.dialog.Heard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Short spoken answers (기획서 p.9·p.19). Unknown never acts. */
class AnswerParserTest {
    private fun consent(t: String) = AnswerParser.parse(t, Expect.CONSENT)
    private fun fill(t: String) = AnswerParser.parse(t, Expect.FILL)

    @Test
    fun `consent - yes, no, why, nearest, stop, with negation first`() {
        assertEquals(Heard.Yes, consent("응"))
        assertEquals(Heard.Yes, consent("그래 들를게"))
        assertEquals(Heard.Yes, consent("네"))
        assertEquals(Heard.No, consent("아니"))
        assertEquals(Heard.No, consent("응 아니야"))
        assertEquals(Heard.No, consent("다음에"))
        assertEquals(Heard.Why, consent("왜 거기야?"))
        assertEquals(Heard.Nearest, consent("그냥 가까운 데로 가"))
        assertEquals(Heard.Stop, consent("그만"))
        assertEquals(Heard.Unknown, consent("오늘 날씨 어때"))
        assertEquals(Heard.Unknown, consent("음")) // a hesitation (also a common mishearing of 응) never approves
        assertEquals(Heard.Unknown, consent(""))
        assertEquals(Heard.Unknown, AnswerParser.parse(null, Expect.CONSENT))
    }

    @Test
    fun `fill - full, litres, kWh, money in digits and Korean numerals, skip`() {
        assertEquals(Heard.Fill("full"), fill("가득"))
        assertEquals(Heard.Fill("full"), fill("가득 넣어줘"))
        assertEquals(Heard.Fill("q20"), fill("20리터"))
        assertEquals(Heard.Fill("q20"), fill("이십 리터"))
        assertEquals(Heard.Fill("q40"), fill("40 킬로와트시"))
        assertEquals(Heard.Fill("m30000"), fill("3만 원어치"))
        assertEquals(Heard.Fill("m30000"), fill("삼만 원"))
        assertEquals(Heard.Fill("m5000"), fill("오천 원"))
        assertEquals(Heard.Fill("skip"), fill("몰라 그냥 가"))
        assertEquals(Heard.Unknown, fill("음"))
    }

    @Test
    fun `consent is strict - questions, put-offs and other places are never a yes`() {
        listOf("어디야?", "어딘데", "그래서 얼마야", "다른 데 가줘", "예약돼?", "어떻게 가", "좋아 보이는데 얼마야").forEach {
            assertEquals(Heard.Unknown, consent(it), it)
        }
        listOf("그냥 갈게", "그냥 지나갈게", "나중에 갈게", "이따 갈게", "응 아니야", "싫어").forEach {
            assertEquals(Heard.No, consent(it), it)
        }
        listOf("응", "응 들를게", "좋아요", "네 네", "그래 가자", "어", "오케이").forEach {
            assertEquals(Heard.Yes, consent(it), it)
        }
    }

    @Test
    fun `nearest only as an order to go there, never a question or a refusal of it`() {
        assertEquals(Heard.Nearest, consent("그냥 가까운 데로 가"))
        assertEquals(Heard.Nearest, consent("가까운 충전소로 가줘"))
        assertEquals(Heard.Unknown, consent("가까운 데 말고"))
        assertEquals(Heard.Unknown, consent("가까운 데는 어디야"))
        assertEquals(Heard.Unknown, consent("가까운 데로 안 가"))
        assertEquals(Heard.Why, consent("왜 가까운 데 말고 거기야"))
    }

    @Test
    fun `money with 만 groups, commas and bare 만, units checked against the car`() {
        assertEquals(Heard.Fill("m10000"), fill("만 원"))
        assertEquals(Heard.Fill("m15000"), fill("만 오천 원"))
        assertEquals(Heard.Fill("m15000"), fill("1만 5천 원어치"))
        assertEquals(Heard.Fill("m35000"), fill("삼만 오천 원어치"))
        assertEquals(Heard.Fill("m25000"), fill("2만 5000원"))
        assertEquals(Heard.Fill("m30000"), fill("30,000원"))
        assertEquals(Heard.Fill("m30000"), fill("가득 말고 3만 원어치"))
        assertEquals(Heard.Unknown, fill("오 원")) // below one refuel: a mishearing
        assertEquals(Heard.Fill("q40"), AnswerParser.parse("40킬로", Expect.FILL, electric = true))
        assertEquals(Heard.Unknown, AnswerParser.parse("20리터", Expect.FILL, electric = true)) // never 20 kWh
        assertEquals(Heard.Unknown, AnswerParser.parse("40킬로와트시", Expect.FILL, electric = false))
        assertEquals(Heard.Stop, fill("다음에")) // not now: the question closes, nothing is recommended
        assertEquals(Heard.Stop, fill("됐어"))
        assertEquals(Heard.Fill("skip"), fill("그냥 가까운 데"))
        assertEquals(35_000L, AnswerParser.amount("3만5천"))
        assertEquals(10_000L, AnswerParser.amount("만"))
    }

    @Test
    fun `reference - here, a saved place by name`() {
        val places = listOf("parents" to "본가")
        assertEquals(Heard.Place("current"), AnswerParser.parse("여기서", Expect.REFERENCE, places))
        assertEquals(Heard.Place("current"), AnswerParser.parse("지금 위치", Expect.REFERENCE, places))
        assertEquals(Heard.Place("parents"), AnswerParser.parse("본가 근처로", Expect.REFERENCE, places))
        assertEquals(Heard.Unknown, AnswerParser.parse("음", Expect.REFERENCE, places))
    }

    @Test
    fun `Korean numerals`() {
        assertEquals(20L, AnswerParser.number("이십"))
        assertEquals(3500L, AnswerParser.number("삼천오백"))
        assertEquals(15L, AnswerParser.number("십오"))
        assertEquals(null, AnswerParser.number("스물"))
    }
}
