package com.zzandrive.app.ui

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Screen colors taken from 짠돌이 himself (sampled from the character art): aqua body, deep-teal
 * shade, navy eyes, yellow bolt, pink cheeks. Day and night follow the system (Pleos design guide:
 * 야간/주간 모드 지원). Every text color was checked against [background], [spot] and [surface] with
 * the WCAG formula: lowest text pair 5.29:1, progress bar 4.55:1 (Pleos: 4.5:1 or more).
 */
data class Palette(
    val background: Int,
    /** Center of the soft aqua light behind the character. */
    val spot: Int,
    /** Cards (recommendation, level-up). */
    val surface: Int,
    /** Navy, like the character's eyes. */
    val text: Int,
    val muted: Int,
    /** Saved money (positive 손익). */
    val accent: Int,
    /** Negative 손익, shown as it is (R-35). */
    val negative: Int,
    /** Confirmed results. */
    val good: Int,
    /** Progress bar fill and track. */
    val fill: Int,
    val track: Int,
    /** Outline of the quiet (secondary) buttons; their text uses [text]. */
    val outline: Int,
    /** Full-screen overlays (character choice, location, notices). */
    val scrim: Int,
) {
    companion object {
        /** Deep teal (the character's shade): main buttons, white text 6.3:1. */
        val TEAL = Color.rgb(10, 104, 130)
        /** Navy (the character's eyes): white text 15.9:1; also the text on [BOLT]. */
        val NAVY = Color.rgb(14, 36, 51)
        /** The bolt's yellow: level badge and demo buttons, navy text 12.2:1. */
        val BOLT = Color.rgb(246, 226, 127)
        /** 시연 데이터 marker (orange), white text 5.3:1. Kept apart from the character colors on purpose. */
        val DEMO = Color.rgb(168, 84, 0)
        /** 짠순이 (the cheeks' pink, deepened for white text 6.0:1). */
        val PINK = Color.rgb(170, 60, 96)

        val DAY = Palette(
            background = Color.rgb(243, 250, 252),
            spot = Color.rgb(214, 240, 246),
            surface = Color.WHITE,
            text = NAVY,
            muted = Color.rgb(70, 98, 114),
            accent = Color.rgb(10, 104, 130),
            negative = Color.rgb(176, 44, 84),
            good = Color.rgb(10, 104, 130),
            fill = Color.rgb(10, 104, 130),
            track = Color.rgb(205, 233, 240),
            outline = Color.rgb(140, 201, 216),
            scrim = Color.argb(246, 243, 250, 252),
        )

        val NIGHT = Palette(
            background = Color.rgb(11, 30, 42),
            spot = Color.rgb(18, 60, 82),
            surface = Color.rgb(19, 48, 63),
            text = Color.rgb(234, 248, 251),
            muted = Color.rgb(158, 196, 208),
            accent = Color.rgb(243, 222, 109),
            negative = Color.rgb(245, 154, 176),
            good = Color.rgb(127, 214, 230),
            fill = Color.rgb(87, 193, 213),
            track = Color.rgb(30, 74, 94),
            outline = Color.rgb(63, 124, 144),
            scrim = Color.argb(242, 7, 22, 32),
        )

        fun of(config: Configuration): Palette =
            if ((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
    }
}

/** A soft round light behind the character, sized to the stage. */
class SpotlightDrawable(private val center: Int, private val edge: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        val r = (minOf(bounds.width(), bounds.height()) * 0.56f).coerceAtLeast(1f)
        paint.shader = RadialGradient(bounds.exactCenterX(), bounds.exactCenterY() + r * 0.12f, r, center, edge, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
