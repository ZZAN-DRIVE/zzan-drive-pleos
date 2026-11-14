package com.zzandrive.app.ui

import android.content.res.Configuration

/**
 * Colours by role. Background, cards, text, dividers and neutral buttons are the Pleos Connect design tokens
 * (UI Design Guide, design assets 2.1.5); the only colours of the app's own are the mascot's teal for the primary
 * action and the bolt's yellow for the level. Every text and control pair here keeps the guide's 4.5:1 contrast
 * (that is why the night primary is the mascot's lighter aqua and the day loss colour is a darker red than the token).
 */
data class Palette(
    /** Window background (background/primary). */
    val page: Int,
    /** Cards, tiles and the speech bubble (background/secondary), always with a 1 px [divider] outline. */
    val card: Int,
    val popup: Int,
    /** Result line (surface/high). */
    val surfaceHigh: Int,
    /** Neutral badges (surface/accent). */
    val surfaceAccent: Int,
    val track: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val textDisabled: Int,
    val divider: Int,
    /** Secondary buttons and the highlighted row (button/basic). */
    val tint: Int,
    val tintPressed: Int,
    /** Selected chips and inverse pills (button/filled). */
    val filled: Int,
    val filledPressed: Int,
    val onFilled: Int,
    /** Primary buttons, progress, the 추천 badge. */
    val brand: Int,
    val brandPressed: Int,
    val onBrand: Int,
    /** Loss and deduction amounts (text only). */
    val negative: Int,
    val scrim: Int,
    val night: Boolean,
) {
    companion object {
        private const val INK = 0x131417
        private const val WHITE = 0xFFFFFF

        private fun rgb(v: Int): Int = (0xFF shl 24) or v

        /** [percent] of full opacity, as the tokens give text and tint levels. */
        private fun alpha(v: Int, percent: Int): Int = (((percent * 255 + 50) / 100) shl 24) or v

        /** The bolt's yellow: the level marker in both themes, with ink text (14:1). */
        val LEVEL: Int = rgb(0xF6E27F)
        val ON_LEVEL: Int = rgb(INK)

        val DAY = Palette(
            page = rgb(0xF7F8FA), card = rgb(WHITE), popup = rgb(WHITE), surfaceHigh = rgb(0xEDEEF2), surfaceAccent = rgb(0xE0E1E6),
            track = rgb(0xE0E1E6),
            text = rgb(INK), text2 = alpha(INK, 84), text3 = alpha(INK, 64), textDisabled = alpha(INK, 32),
            divider = alpha(INK, 10), tint = alpha(INK, 5), tintPressed = alpha(INK, 10),
            filled = rgb(0x44464E), filledPressed = rgb(0x313236), onFilled = rgb(WHITE),
            brand = rgb(0x0A6882), brandPressed = rgb(0x08566C), onBrand = rgb(WHITE),
            negative = rgb(0xC5251D), scrim = alpha(INK, 20), night = false,
        )

        val NIGHT = Palette(
            page = rgb(0x1D1E21), card = rgb(INK), popup = rgb(0x313236), surfaceHigh = rgb(0x313236), surfaceAccent = rgb(0x44464E),
            track = rgb(0x313236),
            text = rgb(WHITE), text2 = alpha(WHITE, 84), text3 = alpha(WHITE, 64), textDisabled = alpha(WHITE, 32),
            divider = alpha(WHITE, 10), tint = alpha(WHITE, 5), tintPressed = alpha(WHITE, 10),
            filled = rgb(0xE0E1E6), filledPressed = rgb(0xC7C9CE), onFilled = rgb(INK),
            brand = rgb(0x57C1D5), brandPressed = rgb(0x4AA9BB), onBrand = rgb(INK),
            negative = rgb(0xFF4339), scrim = alpha(INK, 50), night = true,
        )

        fun of(config: Configuration): Palette =
            if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
    }
}
