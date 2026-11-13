package com.zzandrive.core

import com.zzandrive.core.dialog.AnswerParser
import com.zzandrive.core.dialog.Expect
import com.zzandrive.core.dialog.Heard
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every answer in the adversarial corpus (core/src/test/resources/dialog/answers.tsv and answers2.tsv)
 * parses to one of its allowed meanings. Above all: nothing that is not consent may come out as Yes or Nearest.
 */
class AnswerCorpusTest {
    private fun show(h: Heard): String = when (h) {
        is Heard.Fill -> "Fill(${h.choiceId})"
        is Heard.Place -> "Place(${h.id})"
        else -> h.toString()
    }

    @Test
    fun `every answer in the corpus means what it should`() = check("/dialog/answers.tsv")

    @Test
    fun `answers that are easy to misread mean what they should`() = check("/dialog/answers2.tsv")

    private fun check(resource: String) {
        val lines = javaClass.getResourceAsStream(resource)!!.bufferedReader(Charsets.UTF_8).readLines()
        val places = listOf("parents" to "본가")
        val wrong = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
            val (expect, electric, text, allowed) = line.split("\t")
            val el = when (electric) { "true" -> true; "false" -> false; else -> null }
            val got = show(AnswerParser.parse(text, Expect.valueOf(expect), places, el))
            if (got in allowed.split("|")) null else "$expect '$text' -> $got (allowed $allowed)"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} answers misread:\n" + wrong.joinToString("\n"))
    }
}
