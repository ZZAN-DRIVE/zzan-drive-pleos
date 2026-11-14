package com.zzandrive.app.ui

import android.graphics.Typeface

/**
 * The Pleos Connect type ramp (size / line height in sp, Asta Sans weight). On the car the system sans-serif is
 * Asta Sans: 400 is Regular, 600 the guide's "Strong" (Bold), 700 "Extra Strong" (ExtraBold).
 * Nothing on the screen is smaller than 24, and amounts are never smaller than 28.
 */
enum class Type(val size: Int, val line: Int, val weight: Int) {
    HEADLINE1(60, 80, 700),
    HEADLINE3(48, 64, 700),
    TITLE1(40, 52, 600),
    TITLE2(36, 44, 600),
    TITLE3(32, 40, 600),
    BODY1_STRONG(30, 38, 600),
    BODY2(28, 36, 400),
    BODY2_STRONG(28, 36, 600),
    BODY3(26, 34, 400),
    BODY3_STRONG(26, 34, 600),
    LABEL1(24, 28, 400),
    LABEL1_STRONG(24, 28, 600),
}

/** Pleos button sizes: height, minimum width, horizontal padding, corner radius (dp) and the label style. */
enum class BtnSize(val height: Int, val minWidth: Int, val padH: Int, val radius: Int, val type: Type) {
    L(100, 140, 40, 32, Type.BODY1_STRONG),
    M(80, 120, 32, 24, Type.BODY2_STRONG),
    S(64, 100, 24, 16, Type.LABEL1_STRONG),
}

object Pleos {
    /** Space between things that can be tapped. */
    const val GAP = 24
    const val RADIUS_POPUP = 48
    const val RADIUS_CARD = 32
    const val RADIUS_ROW = 24
    const val RADIUS_SMALL = 16
    const val RADIUS_PILL = 999

    /** Popup widths (System Popup, large and medium). */
    const val POPUP_LARGE = 1200
    const val POPUP_MEDIUM = 808

    private val faces = HashMap<Int, Typeface>()

    fun typeface(weight: Int): Typeface = faces.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }

    /**
     * Keeps every word whole when a line wraps ("들를래?" never splits as "들를/래?"): Korean text may break between any
     * two syllables, so a WORD JOINER goes between the characters of each word and lines break at spaces only.
     */
    fun keepAll(text: String): String {
        val out = StringBuilder(text.length * 2)
        var inWord = false
        for (ch in text) {
            val wordChar = !ch.isWhitespace()
            if (wordChar && inWord) out.append('⁠')
            out.append(ch)
            inWord = wordChar
        }
        return out.toString()
    }
}
