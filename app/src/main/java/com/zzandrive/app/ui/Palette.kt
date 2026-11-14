package com.zzandrive.app.ui

import android.content.res.Configuration

/**
 * Colours by role: Hyundai Motor Company's CI colours (Hyundai Blue #002C5F, Active Blue #00AAD2, Sky Blue #AACAE6,
 * Hyundai Gold #A36B4F, Active Red #E63312, the sand neutrals) on the warm-sand soft-UI base that Google Stitch derived
 * from them ("Hyundai Automotive Soft UI": base #EAE8E3, light key top-left, warm ambient shadow bottom-right).
 *
 * Surfaces have the colour of the page and get their depth from two shadows only (neumorphism). Everything that carries
 * information keeps the Pleos guide's contrast: text and icons 4.5:1 or more against the page in both themes, graphics
 * 3:1 or more. Light Active Blue is 2.2:1 on sand, so by day the accent (ring ends, the pressed rim) is the deeper
 * Hyundai web blue #007FA8 (3.7:1, Stitch's tertiary); at night Active Blue is the brand colour.
 */
data class Palette(
    /** Window background and the colour of every raised or sunken surface. */
    val page: Int,
    /** Kept for surfaces that are drawn flat (same as [page]). */
    val card: Int,
    val popup: Int,
    val surfaceHigh: Int,
    val surfaceAccent: Int,
    /** Progress bar track. */
    val track: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val textDisabled: Int,
    val divider: Int,
    val tint: Int,
    val tintPressed: Int,
    /** A chosen chip. */
    val filled: Int,
    val filledPressed: Int,
    val onFilled: Int,
    /** Primary action, progress, the 추천 badge: Hyundai Blue by day, Active Blue at night. */
    val brand: Int,
    val brandPressed: Int,
    val onBrand: Int,
    /** Where a ring's gradient ends and the pressed rim (Hyundai web blue #007FA8 by day, Sky Blue at night). */
    val accent: Int,
    /** Loss and deduction amounts (text). */
    val negative: Int,
    /** Chart segments: Active Red, Hyundai Gold, Hyundai Blue / Active Blue. */
    val chartRed: Int,
    val chartGold: Int,
    val chartBlue: Int,
    /** Soft-UI shadows: raised (light key, ambient dark) and sunken wells. */
    val shadowLight: Int,
    val shadowDark: Int,
    val insetLight: Int,
    val insetDark: Int,
    val scrim: Int,
    val night: Boolean,
) {
    companion object {
        private fun rgb(v: Int): Int = (0xFF shl 24) or v

        private fun alpha(v: Int, percent: Int): Int = (((percent * 255 + 50) / 100) shl 24) or v

        /** Hyundai CI colours (hyundai.com/kr/ko/info/ci/ci-color). */
        const val HYUNDAI_BLUE = 0x002C5F
        const val ACTIVE_BLUE = 0x00AAD2
        const val SKY_BLUE = 0xAACAE6
        const val HYUNDAI_GOLD = 0xA36B4F
        const val ACTIVE_RED = 0xE63312

        /** Hyundai's web blue (hyundai.com; Stitch's tertiary): Active Blue deep enough for graphics on sand. */
        const val WEB_BLUE = 0x007FA8

        /** The bolt's yellow (the mascot's own colour): the level marker on the character side, with ink text (14:1). */
        val LEVEL: Int = rgb(0xF6E27F)
        val ON_LEVEL: Int = rgb(0x131417)

        val DAY = Palette(
            page = rgb(0xEAE8E3), card = rgb(0xEAE8E3), popup = rgb(0xFBF9F4), surfaceHigh = rgb(0xE4E2DD), surfaceAccent = rgb(0xDCDAD5),
            track = rgb(0xD6D3CD),
            text = rgb(HYUNDAI_BLUE), text2 = rgb(0x36454F), text3 = rgb(0x55606B), textDisabled = alpha(HYUNDAI_BLUE, 34),
            divider = alpha(HYUNDAI_BLUE, 12), tint = alpha(HYUNDAI_BLUE, 5), tintPressed = alpha(HYUNDAI_BLUE, 10),
            filled = rgb(HYUNDAI_BLUE), filledPressed = rgb(0x00224A), onFilled = rgb(0xFFFFFF),
            brand = rgb(HYUNDAI_BLUE), brandPressed = rgb(0x00224A), onBrand = rgb(0xFFFFFF),
            accent = rgb(WEB_BLUE),
            negative = rgb(0xBA1A1A),
            chartRed = rgb(ACTIVE_RED), chartGold = rgb(HYUNDAI_GOLD), chartBlue = rgb(HYUNDAI_BLUE),
            shadowLight = alpha(0xFFFFFF, 85), shadowDark = alpha(0xB9B4AC, 60),
            insetLight = alpha(0xFFFFFF, 75), insetDark = alpha(0xB4AFA6, 55),
            scrim = alpha(0x131417, 30), night = false,
        )

        val NIGHT = Palette(
            page = rgb(0x1B2230), card = rgb(0x1B2230), popup = rgb(0x232B3A), surfaceHigh = rgb(0x222A38), surfaceAccent = rgb(0x2C3545),
            track = rgb(0x2C3545),
            text = rgb(0xFFFFFF), text2 = rgb(0xC9D1DC), text3 = rgb(0x9AA6B5), textDisabled = alpha(0xFFFFFF, 34),
            divider = alpha(0xFFFFFF, 10), tint = alpha(0xFFFFFF, 6), tintPressed = alpha(0xFFFFFF, 12),
            filled = rgb(ACTIVE_BLUE), filledPressed = rgb(0x0095B8), onFilled = rgb(0x0B1A2E),
            brand = rgb(ACTIVE_BLUE), brandPressed = rgb(0x0095B8), onBrand = rgb(0x0B1A2E),
            accent = rgb(SKY_BLUE),
            negative = rgb(0xFF8A7A),
            chartRed = rgb(0xF2765F), chartGold = rgb(0xC89277), chartBlue = rgb(ACTIVE_BLUE),
            // Stitch's night soft UI: a faint top-left rim light (5–8 %) and a deep bottom-right shadow (up to 85 %).
            shadowLight = alpha(0xFFFFFF, 8), shadowDark = alpha(0x000000, 80),
            insetLight = alpha(0xFFFFFF, 7), insetDark = alpha(0x000000, 70),
            scrim = alpha(0x000000, 55), night = true,
        )

        fun of(config: Configuration): Palette =
            if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
    }
}
