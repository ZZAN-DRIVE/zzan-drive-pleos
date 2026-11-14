package com.zzandrive.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Soft-UI depth (Stitch "Hyundai Automotive Soft UI"): raised `-8 -8 16 / 8 8 16`, wells `6 6 12`. [offset] in dp,
 * [blur] is the Paint.setShadowLayer radius in dp (σ = 0.577·r + 0.5, so a CSS blur of 16 px is a radius of about 13).
 */
enum class Depth(val offset: Float, val blur: Float) {
    LARGE(8f, 13f),
    MEDIUM(6f, 10f),
    SMALL(4f, 7f),
}

object Neu {
    private fun sigma(radiusPx: Float) = 0.57735f * radiusPx + 0.5f

    /**
     * Room a raised shape needs inside its own view so neither shadow is cut: offset + 2σ (what is cut beyond 2σ is
     * under 3 % of the shadow). Shapes are drawn inset by this, so views never need clipChildren=false.
     */
    fun pad(depth: Depth, density: Float): Float = depth.offset * density + 2f * sigma(depth.blur * density)
}

/**
 * One neumorphic surface. RAISED: two shadows (light key top-left, ambient dark bottom-right) under a shape of [fill].
 * INSET: a well whose top-left inner walls are shaded and bottom-right lit. FLAT: no depth (a clear [NONE] fill for the
 * text-only buttons). [pressable] raised and flat shapes sink into a well with a rim in the accent colour while touched
 * (Stitch: "inverts into an inset well"), so the state never relies on shadow alone.
 * Hardware accelerated: setShadowLayer on filled shapes is supported from API 28.
 */
class NeuDrawable(
    private val palette: Palette,
    private val density: Float,
    var shape: Shape = Shape.ROUND_RECT,
    var cornerDp: Float = 32f,
    var depth: Depth = Depth.LARGE,
    var style: Style = Style.RAISED,
    var fill: Int = palette.page,
    var pressable: Boolean = false,
) : Drawable() {
    enum class Shape { ROUND_RECT, CIRCLE }
    enum class Style { RAISED, INSET, FLAT }

    private var pressed = false
    private var enabled = true
    private val rect = RectF()
    private val hole = Path()
    private val frame = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Raised (and pressable) shapes are drawn inset by the room their shadows need; wells fill their bounds. */
    val pad: Float get() = if (style == Style.RAISED || pressable) Neu.pad(depth, density) else 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    private fun corner(): Float = if (shape == Shape.CIRCLE) minOf(rect.width(), rect.height()) / 2f else cornerDp * density

    private fun rebuild() {
        val p = pad
        rect.set(bounds.left + p, bounds.top + p, bounds.right - p, bounds.bottom - p)
        hole.reset()
        frame.reset()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val c = corner()
        hole.addRoundRect(rect, c, c, Path.Direction.CW)
        frame.fillType = Path.FillType.EVEN_ODD
        val e = Neu.pad(depth, density) * 2f
        frame.addRect(rect.left - e, rect.top - e, rect.right + e, rect.bottom + e, Path.Direction.CW)
        frame.addRoundRect(rect, c, c, Path.Direction.CW)
    }

    private fun current(): Style = when {
        !enabled -> Style.FLAT
        pressable && pressed && style != Style.INSET -> Style.INSET // raised shapes and flat text buttons sink
        else -> style
    }

    override fun draw(canvas: Canvas) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val c = corner()
        val off = depth.offset * density
        val r = depth.blur * density
        when (current()) {
            Style.RAISED -> {
                darkPaint.color = fill
                darkPaint.setShadowLayer(r, off, off, palette.shadowDark)
                lightPaint.color = fill
                lightPaint.setShadowLayer(r, -off, -off, palette.shadowLight)
                canvas.drawRoundRect(rect, c, c, darkPaint)
                canvas.drawRoundRect(rect, c, c, lightPaint)
            }
            Style.INSET -> {
                fillPaint.color = fill
                canvas.drawRoundRect(rect, c, c, fillPaint)
                val o = off * 0.75f
                val ri = r * 0.75f
                // The palette's well colours are tuned for page-coloured wells. On a dark fill by day (a pressed
                // Hyundai Blue button) they would light both walls, so that well is shaded with black and white.
                val pageWell = palette.night || fill == palette.page || fill == NONE // a clear well shows the page
                darkPaint.color = palette.page
                darkPaint.setShadowLayer(ri, o, o, if (pageWell) palette.insetDark else Color.argb(115, 0, 0, 0))
                lightPaint.color = palette.page
                lightPaint.setShadowLayer(ri, -o, -o, if (pageWell) palette.insetLight else Color.argb(38, 255, 255, 255))
                val save = canvas.save()
                canvas.clipPath(hole)
                canvas.drawPath(frame, darkPaint)
                canvas.drawPath(frame, lightPaint)
                canvas.restoreToCount(save)
                if (pressable && pressed) {
                    rimPaint.color = palette.accent
                    rimPaint.strokeWidth = 1.5f * density
                    canvas.drawRoundRect(rect, c, c, rimPaint)
                }
            }
            Style.FLAT -> {
                fillPaint.color = if (enabled || fill == NONE) fill else palette.surfaceHigh
                canvas.drawRoundRect(rect, c, c, fillPaint)
            }
        }
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        val e = state.contains(android.R.attr.state_enabled)
        if (p == pressed && e == enabled) return false
        pressed = p
        enabled = e
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** No fill: for the transparent text-only buttons. */
        const val NONE = Color.TRANSPARENT
    }
}
