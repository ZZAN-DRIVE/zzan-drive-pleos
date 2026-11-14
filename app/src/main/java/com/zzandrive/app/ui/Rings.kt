package com.zzandrive.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A circular gauge in the soft-UI style (Stitch "Circular Telemetry Gauges"): a raised disc, a sunken groove as the
 * track, and the value as an arc from 12 o'clock clockwise. Either one value ([fraction], drawn with a gradient from
 * the brand colour to the accent and round ends, or in one [arcColor]) or a donut of [segments]. An optional [threshold] tick
 * crosses the groove in [thresholdColor] (Hyundai Gold: the alert level has one colour on every ring, and it is never
 * the colour of the number, so a tick at 3 o'clock does not read as a minus sign).
 * The number in the middle always says the value too, so the arc never has to carry the information alone.
 * The arc is drawn without shadows, so changing it costs nothing extra.
 */
class RingView(context: Context, private val palette: Palette) : View(context) {
    /** 0..1 of the single value. */
    var fraction: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** (share 0..1, colour) for a donut; drawn instead of [fraction] when not empty. */
    var segments: List<Pair<Float, Int>> = emptyList()
        set(v) {
            field = v
            invalidate()
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

    /** Largest size of the middle number in sp; it shrinks (not below [minValueSp]) to fit inside the groove. */
    var valueSp: Float = 44f
    var minValueSp: Float = 28f
    var caption: String = ""
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val depth = Depth.MEDIUM
    private val disc = RectF()
    private val arcBox = RectF()
    private var cx = 0f
    private var cy = 0f
    private var rOut = 0f
    private var rIn = 0f
    private val groove = Path()
    private val grooveFrame = Path()
    private val discDark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discLight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wellBase = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wellDark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wellLight = Paint(Paint.ANTI_ALIAS_FLAG)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = Neu.pad(depth, d)
        val r = min(w, h) / 2f - pad
        cx = w / 2f
        cy = h / 2f
        disc.set(cx - r, cy - r, cx + r, cy + r)
        rOut = r * 0.84f
        rIn = r * 0.62f
        val mid = (rOut + rIn) / 2f
        arcBox.set(cx - mid, cy - mid, cx + mid, cy + mid)
        val thick = rOut - rIn
        arc.strokeWidth = thick * 0.62f
        seg.strokeWidth = thick * 0.62f
        tick.strokeWidth = max(3f * d, thick * 0.12f)
        groove.reset()
        groove.fillType = Path.FillType.EVEN_ODD
        groove.addCircle(cx, cy, rOut, Path.Direction.CW)
        groove.addCircle(cx, cy, rIn, Path.Direction.CW)
        grooveFrame.reset()
        grooveFrame.fillType = Path.FillType.EVEN_ODD
        val e = rOut + pad * 2f
        grooveFrame.addRect(cx - e, cy - e, cx + e, cy + e, Path.Direction.CW)
        grooveFrame.addCircle(cx, cy, rOut, Path.Direction.CW)
        grooveFrame.addCircle(cx, cy, rIn, Path.Direction.CW)
        shaderFor = -1f
    }

    /** The gradient runs over the value's own sweep, so every arc ends in the accent colour. */
    private var shaderFor = -1f

    private fun updateShader() {
        if (shaderFor == fraction) return
        shaderFor = fraction
        val end = fraction.coerceIn(0.02f, 1f)
        // The round start cap reaches asin(halfStroke / mid) before 12 o'clock: the sweep starts at its tip, so the
        // cap has the start colour (otherwise it would take the colour at the end of the sweep).
        val capDeg = Math.toDegrees(asin((arc.strokeWidth / (rOut + rIn)).toDouble().coerceIn(0.0, 1.0))).toFloat()
        val c = capDeg / 360f
        arc.shader = SweepGradient(
            cx, cy,
            intArrayOf(palette.brand, palette.brand, palette.accent, palette.accent),
            floatArrayOf(0f, c + end * 0.15f, min(1f, c + end), 1f),
        ).apply { setLocalMatrix(Matrix().apply { setRotate(-90f - capDeg, cx, cy) }) }
    }

    override fun onDraw(canvas: Canvas) {
        if (disc.width() <= 0f) return
        val off = depth.offset * d
        val r = depth.blur * d
        // Raised disc.
        discDark.color = palette.page
        discDark.setShadowLayer(r, off, off, palette.shadowDark)
        discLight.color = palette.page
        discLight.setShadowLayer(r, -off, -off, palette.shadowLight)
        val rad = disc.width() / 2f
        canvas.drawCircle(cx, cy, rad, discDark)
        canvas.drawCircle(cx, cy, rad, discLight)
        // Sunken groove (the track).
        wellBase.color = palette.page
        canvas.drawPath(groove, wellBase)
        val o = off * 0.6f
        val ri = r * 0.6f
        wellDark.color = palette.page
        wellDark.setShadowLayer(ri, o, o, palette.insetDark)
        wellLight.color = palette.page
        wellLight.setShadowLayer(ri, -o, -o, palette.insetLight)
        val save = canvas.save()
        canvas.clipPath(groove)
        canvas.drawPath(grooveFrame, wellDark)
        canvas.drawPath(grooveFrame, wellLight)
        canvas.restoreToCount(save)
        // Value.
        if (segments.isNotEmpty()) {
            var start = -90f
            val gap = if (segments.count { it.first > 0f } > 1) 3f else 0f
            segments.forEach { (share, color) ->
                if (share <= 0f) return@forEach
                val sweep = 360f * share
                seg.color = color
                canvas.drawArc(arcBox, start + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), false, seg)
                start += sweep
            }
        } else if (fraction > 0f) {
            val solid = arcColor
            if (solid != null) {
                arc.shader = null
                arc.color = solid
            } else {
                updateShader()
            }
            canvas.drawArc(arcBox, -90f, (360f * fraction).coerceAtLeast(1f), false, arc)
        }
        threshold?.let { t ->
            val a = Math.toRadians((-90.0 + 360.0 * t.coerceIn(0f, 1f)))
            // From the inner edge of the arc's stroke to just outside the groove.
            val r0 = rIn + (rOut - rIn) * 0.19f
            val r1 = rOut + 8f * d
            tick.color = thresholdColor
            canvas.drawLine(
                cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat(),
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), tick,
            )
        }
        // Middle text: the value, shrunk to fit inside the groove, and an optional caption under it.
        val room = (rIn - 10f * d) * 2f
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
        val valueY = if (hasCaption) cy + size * 0.16f else cy + size * 0.36f
        canvas.drawText(value, cx, valueY, valuePaint)
        if (hasCaption) canvas.drawText(caption, cx, valueY + 34f * sd, captionPaint)
    }
}
