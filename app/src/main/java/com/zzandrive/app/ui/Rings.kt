package com.zzandrive.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A ring chart on glass: a thin track and the value as an arc from 12 o'clock clockwise. Either one value
 * ([fraction], drawn with a gradient from the brand colour to the accent and round ends, or in one [arcColor]) or a
 * donut of [segments] with gaps of the surface between them. An optional [threshold] tick crosses the track in
 * [thresholdColor] (Hyundai Gold: the alert level has one colour, and it is never the colour of the number, so a tick
 * at 3 o'clock does not read as a minus sign).
 *
 * The number in the middle always says the value too, in the text colour, so the arc never has to carry the
 * information alone. A new value sweeps in from the old one (from nothing the first time) while [motion] is allowed.
 */
class RingView(context: Context, private val palette: Palette) : View(context) {
    /** 0..1 of the single value. */
    var fraction: Float = 0f
        set(v) {
            val to = v.coerceIn(0f, 1f)
            if (field == to && started) return
            field = to
            sweepTo(to)
        }

    /** (share 0..1, colour) for a donut; drawn instead of [fraction] when not empty. */
    var segments: List<Pair<Float, Int>> = emptyList()
        set(v) {
            if (field == v) return
            field = v
            reveal()
        }

    /** 0..1 position of a threshold tick, or null. */
    var threshold: Float? = null
        set(v) {
            field = v
            invalidate()
        }
    var thresholdColor: Int = palette.chartGold
        set(v) {
            field = v
            invalidate()
        }

    /** One colour for the single-value arc instead of the gradient (null = the gradient). */
    var arcColor: Int? = null
        set(v) {
            field = v
            shaderFor = -1f
            invalidate()
        }

    var value: String = ""
        set(v) {
            field = v
            invalidate()
        }
    var valueColor: Int = palette.text
        set(v) {
            field = v
            invalidate()
        }

    /** Largest size of the middle number in sp; it shrinks (not below [minValueSp]) to fit inside the ring. */
    var valueSp: Float = 44f
    var minValueSp: Float = 28f

    /** A small line under the number, inside the ring. */
    var caption: String = ""
        set(v) {
            field = v
            invalidate()
        }

    /**
     * False: values change at once (the car is moving, a popup covers the screen, system animations are off), and a
     * sweep that is on its way goes straight to its end.
     */
    var motion: Boolean = true
        set(v) {
            field = v
            if (!v) animator?.takeIf { it.isRunning }?.end()
        }

    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val arcBox = RectF()
    private var cx = 0f
    private var cy = 0f
    private var rMid = 0f
    private var thick = 0f
    private var started = false
    private var visibleNow = false
    private var shown = 0f
    private var revealed = 1f
    private var animator: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = if (palette.night) GlassDrawable.scaleAlpha(palette.page, 0.9f) else 0xE6FFFFFF.toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Pleos.typeface(700)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Pleos.typeface(400)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun canMove(): Boolean = motion && visibleNow && width > 0 && ValueAnimator.areAnimatorsEnabled()

    /** Coming into view (the first time, or when the dashboard returns): the value sweeps in from nothing. */
    private fun intro() {
        if (!canMove()) return
        if (segments.isNotEmpty()) {
            reveal()
        } else if (fraction > 0f) {
            shown = 0f
            sweepTo(fraction)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == visibleNow) return
        visibleNow = isVisible
        if (isVisible) {
            intro()
        } else {
            animator?.cancel()
            shown = fraction
            revealed = 1f
        }
    }

    private fun sweepTo(to: Float) {
        started = true
        animator?.cancel()
        if (!canMove()) {
            shown = to
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, to).apply {
            duration = SWEEP_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun reveal() {
        animator?.cancel()
        if (segments.isEmpty() || !canMove()) {
            revealed = 1f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                revealed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        shown = fraction
        revealed = 1f
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = min(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f
        thick = (size * 0.086f).coerceIn(12f * d, 22f * d)
        rMid = size / 2f - TICK_ROOM_DP * d - thick / 2f
        arcBox.set(cx - rMid, cy - rMid, cx + rMid, cy + rMid)
        track.strokeWidth = thick
        arc.strokeWidth = thick
        seg.strokeWidth = thick
        tick.strokeWidth = max(3f * d, thick * 0.16f)
        shaderFor = -1f
        if (oldw == 0 && oldh == 0) intro()
    }

    /** The gradient runs over the value's own sweep, so every arc ends in the accent colour. */
    private var shaderFor = -1f

    private fun updateShader(f: Float) {
        if (shaderFor == f) return
        shaderFor = f
        val end = f.coerceIn(0.02f, 1f)
        // The round start cap reaches asin(halfStroke / radius) before 12 o'clock: the sweep starts at its tip, so the
        // cap has the start colour (otherwise it would take the colour at the end of the sweep).
        val capDeg = Math.toDegrees(asin((thick / (2f * rMid)).toDouble().coerceIn(0.0, 1.0))).toFloat()
        val c = capDeg / 360f
        arc.shader = SweepGradient(
            cx, cy,
            intArrayOf(palette.brand, palette.brand, palette.accent, palette.accent),
            floatArrayOf(0f, c + end * 0.15f, min(1f, c + end), 1f),
        ).apply { setLocalMatrix(Matrix().apply { setRotate(-90f - capDeg, cx, cy) }) }
    }

    override fun onDraw(canvas: Canvas) {
        if (rMid <= 0f) return
        track.color = palette.track
        canvas.drawCircle(cx, cy, rMid, track)
        if (segments.isNotEmpty()) {
            // Gaps of the surface (about 2 dp along the ring, never under 3°) separate the parts; no outlines.
            val parts = segments.filter { it.first > 0f }
            val gap = if (parts.size > 1) max(3f, Math.toDegrees((2f * d / rMid).toDouble()).toFloat()) else 0f
            // A part that is there is seen: it gets at least a few degrees of colour, taken from the largest part.
            val least = gap + MIN_PART_DEG
            val sweeps = FloatArray(parts.size) { max(360f * parts[it].first, least) }
            if (parts.size > 1) {
                val over = sweeps.sum() - 360f
                val largest = sweeps.indices.maxByOrNull { sweeps[it] } ?: 0
                if (over > 0f) sweeps[largest] = max(least, sweeps[largest] - over)
            }
            val limit = -90f + 360f * revealed
            var start = -90f
            parts.forEachIndexed { i, (_, color) ->
                val sweep = sweeps[i]
                val from = start + gap / 2f
                val to = min(start + sweep - gap / 2f, limit)
                if (to - from >= 0.5f) {
                    seg.color = color
                    canvas.drawArc(arcBox, from, to - from, false, seg)
                }
                start += sweep
            }
        } else if (shown > 0f) {
            val solid = arcColor
            if (solid != null) {
                arc.shader = null
                arc.color = solid
            } else {
                updateShader(shown)
            }
            canvas.drawArc(arcBox, -90f, (360f * shown).coerceAtLeast(1f), false, arc)
        }
        threshold?.let { t ->
            val a = Math.toRadians((-90.0 + 360.0 * t.coerceIn(0f, 1f)))
            val r0 = rMid - thick / 2f - 2f * d
            val r1 = rMid + thick / 2f + 5f * d
            // A gap of the surface across the band first: the tick stays legible where the arc passes under it (at
            // night the gold tick and the blue arc are almost equally light).
            val h0 = rMid - thick / 2f
            val h1 = rMid + thick / 2f
            halo.strokeWidth = tick.strokeWidth + 6f * d
            canvas.drawLine(
                cx + (h0 * cos(a)).toFloat(), cy + (h0 * sin(a)).toFloat(),
                cx + (h1 * cos(a)).toFloat(), cy + (h1 * sin(a)).toFloat(), halo,
            )
            tick.color = thresholdColor
            canvas.drawLine(
                cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat(),
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), tick,
            )
        }
        // Middle text: the value, shrunk to fit inside the ring, and an optional small line under it.
        val room = (rMid - thick / 2f - 12f * d) * 2f
        var size = valueSp * sd
        valuePaint.textSize = size
        while (valuePaint.measureText(value) > room && size > minValueSp * sd) {
            size -= sd
            valuePaint.textSize = size
        }
        valuePaint.color = valueColor
        captionPaint.textSize = 24f * sd
        captionPaint.color = palette.text3
        val hasCaption = caption.isNotEmpty()
        val valueY = if (hasCaption) cy + size * 0.14f else cy + size * 0.36f
        canvas.drawText(value, cx, valueY, valuePaint)
        if (hasCaption) canvas.drawText(caption, cx, valueY + 34f * sd, captionPaint)
    }

    private companion object {
        const val SWEEP_MS = 800L

        /** Room outside the track for the threshold tick. */
        const val TICK_ROOM_DP = 8f

        /** The least colour a donut part shows between its gaps, in degrees. */
        const val MIN_PART_DEG = 2.5f
    }
}
