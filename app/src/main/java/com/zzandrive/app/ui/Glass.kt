package com.zzandrive.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * How far a surface floats over the backdrop: the shadow's drop ([offset], dp) and its softness ([blur], the
 * Paint.setShadowLayer radius in dp; σ = 0.577·r + 0.5).
 */
enum class Depth(val offset: Float, val blur: Float) {
    LARGE(8f, 14f),
    MEDIUM(6f, 10f),
    SMALL(4f, 7f),
}

object Glass {
    private fun sigma(radiusPx: Float) = 0.57735f * radiusPx + 0.5f

    /**
     * Room a floating shape needs inside its own view so its shadow is not cut: offset + 2σ (what is cut beyond 2σ is
     * under 3 % of the shadow). Shapes are drawn inset by this on every side, so they stay centred in their views and
     * views never need clipChildren=false.
     */
    fun pad(depth: Depth, density: Float): Float = depth.offset * density + 2f * sigma(depth.blur * density)
}

/**
 * One glass surface (glassmorphism). GLASS: a translucent fill, a gloss fading down from the top, a 1.5 dp edge that
 * is bright at the top left and fades to the bottom right, and with a [depth] a soft shadow below (drawn outside the
 * shape only, so it never darkens the glass). SOLID: the same build on an opaque colour (the primary action, a chosen
 * chip). CLEAR: nothing until it is touched (text-only buttons).
 *
 * The backdrop behind is already soft (large blurred blobs), so the frosted look needs no blur of its own.
 * [pressable] surfaces change their fill while touched (glass brightens, a solid colour darkens), lose their shadow
 * and get a rim in the accent colour, so the pressed state never relies on one cue alone. Hardware accelerated: setShadowLayer on filled shapes is supported from API 28.
 */
class GlassDrawable(
    private val palette: Palette,
    private val density: Float,
    var cornerDp: Float = 32f,
    var depth: Depth? = Depth.LARGE,
    var style: Style = Style.GLASS,
    var fill: Int = palette.glass,
    var pressable: Boolean = false,
) : Drawable() {
    enum class Style { GLASS, SOLID, CLEAR }

    private var pressed = false
    private var enabled = true
    private val rect = RectF()
    private val edgeRect = RectF()
    private val shape = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Shapes with a [depth] are drawn inset by the room their shadow needs; the others fill their bounds. */
    val pad: Float get() = depth?.let { Glass.pad(it, density) } ?: 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val p = pad
        rect.set(bounds.left + p, bounds.top + p, bounds.right - p, bounds.bottom - p)
        shape.reset()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val c = corner()
        shape.addRoundRect(rect, c, c, Path.Direction.CW)
        val half = EDGE_DP * density / 2f
        edgeRect.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
        edgePaint.strokeWidth = EDGE_DP * density
        // The light comes from the top left: the gradient runs across the line through the top-right and bottom-left
        // corners, so the whole top and left edges are bright and the bottom and right ones dim, on wide shapes too
        // (a gradient along the diagonal would only light the left end of a wide panel).
        val w = rect.width()
        val h = rect.height()
        val k = w * h / (w * w + h * h)
        edgePaint.shader = LinearGradient(
            rect.centerX() - h * k, rect.centerY() - w * k, rect.centerX() + h * k, rect.centerY() + w * k,
            palette.edgeLight, palette.edgeDark, Shader.TileMode.CLAMP,
        )
        val glossEnd = rect.top + minOf(rect.height() * 0.6f, 140f * density)
        glossPaint.shader = LinearGradient(0f, rect.top, 0f, glossEnd, scaleAlpha(palette.edgeLight, 0.2f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        rimPaint.strokeWidth = EDGE_DP * density
    }

    private fun corner(): Float = minOf(cornerDp * density, minOf(rect.width(), rect.height()) / 2f)

    override fun draw(canvas: Canvas) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val c = corner()
        val down = pressable && pressed && enabled
        if (style == Style.CLEAR && !down) return
        val d = depth
        if (d != null && enabled && !down && style != Style.CLEAR) {
            // The shadow alone: the shape is cut out of the clip, so nothing dark shows through the glass.
            shadowPaint.color = palette.page
            shadowPaint.setShadowLayer(d.blur * density, 0f, d.offset * density, if (style == Style.SOLID) solidShadow() else palette.shadow)
            val save = canvas.save()
            canvas.clipOutPath(shape)
            canvas.drawRoundRect(rect, c, c, shadowPaint)
            canvas.restoreToCount(save)
        }
        fillPaint.color = when {
            !enabled -> scaleAlpha(palette.glass, 0.5f)
            style == Style.SOLID -> if (down) shade(fill) else fill
            down -> palette.glassPressed
            else -> fill
        }
        canvas.drawRoundRect(rect, c, c, fillPaint)
        if (enabled && !down) canvas.drawRoundRect(rect, c, c, glossPaint)
        val ec = (c - EDGE_DP * density / 2f).coerceAtLeast(0f)
        if (down) {
            rimPaint.color = palette.accent
            canvas.drawRoundRect(edgeRect, ec, ec, rimPaint)
        } else {
            edgePaint.alpha = if (enabled) 255 else 110
            canvas.drawRoundRect(edgeRect, ec, ec, edgePaint)
        }
    }

    /** A coloured button throws a shadow of its own colour by day; at night the page is too dark to tint. */
    private fun solidShadow(): Int = if (palette.night) palette.shadow else scaleAlpha(fill, 0.3f)

    /** The pressed step of a solid colour: 16 % towards black. */
    private fun shade(color: Int): Int =
        Color.argb(Color.alpha(color), (Color.red(color) * 0.84f).toInt(), (Color.green(color) * 0.84f).toInt(), (Color.blue(color) * 0.84f).toInt())

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
        private const val EDGE_DP = 1.5f

        fun scaleAlpha(color: Int, factor: Float): Int =
            Color.argb((Color.alpha(color) * factor).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
