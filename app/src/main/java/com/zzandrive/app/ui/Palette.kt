package com.zzandrive.app.ui

import android.content.res.Configuration

/**
 * Colours by role: Hyundai Motor Company's CI colours (Hyundai Blue #002C5F, Active Blue #00AAD2, Sky Blue #AACAE6,
 * Hyundai Gold #A36B4F, Active Red #E63312) on glass. The backdrop is one base colour with four large soft blobs of
 * the CI colours; panels, pills and secondary buttons are translucent glass over it (a bright edge at the top left,
 * a soft shadow below).
 *
 * The blobs drift, so every pairing is checked against the strongest blob it can meet: text and icons 4.5:1 or more,
 * chart marks 3:1 or more (chart marks sit on glass only). Light Active Blue is too weak for marks on a light
 * surface, so by day the accent is the deeper Hyundai web blue #007FA8; at night Active Blue is the brand colour.
 */
data class Palette(
    /** Backdrop base under the blobs; also the window background. */
    val page: Int,
    /** The backdrop's blobs (ARGB): behind the dashboard, bottom right, behind the character, top left. */
    val blobs: List<Int>,
    /** Frosted glass: panels, pills, secondary buttons. */
    val glass: Int,
    /** Glass while it is touched. */
    val glassPressed: Int,
    /** Tinted glass: the recommended row, the result line. */
    val glassTint: Int,
    /** The glass edge: bright at the top left, fading towards the bottom right. */
    val edgeLight: Int,
    val edgeDark: Int,
    /** The shadow under glass. */
    val shadow: Int,
    /** Popup panels: glass dense enough for long text. */
    val popup: Int,
    /** The unfilled part of a ring. */
    val track: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val textDisabled: Int,
    val divider: Int,
    /** A chosen chip. */
    val filled: Int,
    val onFilled: Int,
    /** Primary action, the 추천 badge: Hyundai Blue by day, Active Blue at night. */
    val brand: Int,
    val onBrand: Int,
    /** Where a ring's gradient ends and the pressed rim (Hyundai web blue #007FA8 by day, Sky Blue at night). */
    val accent: Int,
    /** Loss and deduction amounts (text). */
    val negative: Int,
    /**
     * Chart marks. Deductions are [chartRed], savings [chartBlue]; the trip donut adds [chartAlt]. The three stay
     * apart for colour-blind readers (OKLab ΔE 11 or more under protan and deutan simulation, 16 or more unsimulated).
     * [chartGold] is the alert level only.
     */
    val chartRed: Int,
    val chartGold: Int,
    val chartBlue: Int,
    val chartAlt: Int,
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

        /** Hyundai's web blue (hyundai.com): Active Blue deep enough for marks on a light surface. */
        const val WEB_BLUE = 0x007FA8

        /** The bolt's yellow (the mascot's own colour): the level marker and the coins, with ink text (14:1). */
        val LEVEL: Int = rgb(0xF6E27F)
        val LEVEL_RIM: Int = rgb(0xE0C552)
        val ON_LEVEL: Int = rgb(0x131417)

        val DAY = Palette(
            page = rgb(0xF1F3F6),
            blobs = listOf(alpha(SKY_BLUE, 90), alpha(ACTIVE_BLUE, 30), alpha(0xEAD8C6, 90), alpha(HYUNDAI_BLUE, 10)),
            glass = alpha(0xFFFFFF, 48), glassPressed = alpha(0xFFFFFF, 80), glassTint = alpha(HYUNDAI_BLUE, 8),
            edgeLight = alpha(0xFFFFFF, 90), edgeDark = alpha(0xFFFFFF, 25), shadow = alpha(HYUNDAI_BLUE, 14),
            popup = alpha(0xFFFFFF, 90), track = alpha(HYUNDAI_BLUE, 9),
            text = rgb(HYUNDAI_BLUE), text2 = rgb(0x33475C), text3 = rgb(0x445263), textDisabled = alpha(HYUNDAI_BLUE, 38),
            divider = alpha(HYUNDAI_BLUE, 12),
            filled = rgb(HYUNDAI_BLUE), onFilled = rgb(0xFFFFFF),
            brand = rgb(HYUNDAI_BLUE), onBrand = rgb(0xFFFFFF),
            accent = rgb(WEB_BLUE),
            negative = rgb(0xA61616),
            chartRed = rgb(ACTIVE_RED), chartGold = rgb(HYUNDAI_GOLD), chartBlue = rgb(HYUNDAI_BLUE), chartAlt = rgb(WEB_BLUE),
            scrim = alpha(0x0A1220, 28), night = false,
        )

        val NIGHT = Palette(
            page = rgb(0x0A1220),
            blobs = listOf(alpha(0x0B4EA2, 55), alpha(ACTIVE_BLUE, 24), alpha(HYUNDAI_GOLD, 28), alpha(SKY_BLUE, 10)),
            // The night tint is a deep blue: a light one would lift the surface and cost the muted text its contrast.
            glass = alpha(0xFFFFFF, 8), glassPressed = alpha(0xFFFFFF, 18), glassTint = alpha(0x0B4EA2, 30),
            edgeLight = alpha(0xFFFFFF, 26), edgeDark = alpha(0xFFFFFF, 5), shadow = alpha(0x000000, 45),
            popup = alpha(0x1A2433, 92), track = alpha(0xFFFFFF, 11),
            text = rgb(0xFFFFFF), text2 = rgb(0xD3DAE4), text3 = rgb(0xB4BECB), textDisabled = alpha(0xFFFFFF, 34),
            divider = alpha(0xFFFFFF, 10),
            filled = rgb(ACTIVE_BLUE), onFilled = rgb(0x0B1A2E),
            brand = rgb(ACTIVE_BLUE), onBrand = rgb(0x0B1A2E),
            accent = rgb(SKY_BLUE),
            negative = rgb(0xFFA094),
            chartRed = rgb(0xF2765F), chartGold = rgb(0xC89277), chartBlue = rgb(ACTIVE_BLUE), chartAlt = rgb(SKY_BLUE),
            scrim = alpha(0x000000, 50), night = true,
        )

        fun of(config: Configuration): Palette =
            if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
    }
}
