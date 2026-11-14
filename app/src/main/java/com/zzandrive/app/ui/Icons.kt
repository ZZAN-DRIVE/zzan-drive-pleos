package com.zzandrive.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sin

/** The app's own line icons, drawn on a 24 × 24 grid: a word less to read wherever a picture says it. */
enum class Icon { MIC, MIC_OFF, BELL, BARS, CHECK, INFO, PIN_OFF, CHEVRON, UP, DOWN, PARK, DRIVE, BOLT, DROP }

/**
 * One [Icon] in one colour, [sizePx] square. Strokes are 2/24 of the size with round ends; BOLT, DROP, UP and DOWN
 * are filled; PARK and DRIVE are the gear's letter in a ring.
 */
class IconDrawable(private val kind: Icon, tint: Int, private val sizePx: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Pleos.typeface(700)
        color = tint
    }
    private val path = Path()
    private val box = RectF()

    var color: Int = tint
        set(v) {
            field = v
            stroke.color = v
            solid.color = v
            letter.color = v
            invalidateSelf()
        }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        if (s <= 0f) return
        val save = canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        stroke.strokeWidth = 2f
        path.reset()
        when (kind) {
            Icon.MIC, Icon.MIC_OFF -> {
                box.set(9f, 3f, 15f, 14f)
                path.addRoundRect(box, 3f, 3f, Path.Direction.CW)
                box.set(6f, 5f, 18f, 17f)
                path.addArc(box, 0f, 180f)
                path.moveTo(12f, 17f)
                path.lineTo(12f, 21f)
                path.moveTo(8.5f, 21f)
                path.lineTo(15.5f, 21f)
                if (kind == Icon.MIC_OFF) {
                    path.moveTo(4f, 4f)
                    path.lineTo(20f, 20f)
                }
                canvas.drawPath(path, stroke)
            }
            Icon.BELL -> {
                path.moveTo(6f, 17f)
                path.lineTo(6f, 11f)
                box.set(6f, 5f, 18f, 17f)
                path.arcTo(box, 180f, 180f)
                path.lineTo(18f, 17f)
                path.moveTo(4.5f, 17f)
                path.lineTo(19.5f, 17f)
                box.set(10f, 17.5f, 14f, 21.5f)
                path.addArc(box, 20f, 140f)
                path.moveTo(12f, 3f)
                path.lineTo(12f, 5f)
                canvas.drawPath(path, stroke)
            }
            Icon.BARS -> {
                path.moveTo(6f, 19f)
                path.lineTo(6f, 13f)
                path.moveTo(12f, 19f)
                path.lineTo(12f, 6f)
                path.moveTo(18f, 19f)
                path.lineTo(18f, 10f)
                stroke.strokeWidth = 3f
                canvas.drawPath(path, stroke)
            }
            Icon.CHECK -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                path.moveTo(8f, 12.5f)
                path.lineTo(11f, 15.5f)
                path.lineTo(16.5f, 9f)
                canvas.drawPath(path, stroke)
            }
            Icon.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                path.moveTo(12f, 11f)
                path.lineTo(12f, 16.5f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(12f, 7.6f, 1.3f, solid)
            }
            Icon.PIN_OFF -> {
                path.moveTo(12f, 21f)
                path.cubicTo(12f, 21f, 5.5f, 15f, 5.5f, 10f)
                box.set(5.5f, 3.5f, 18.5f, 16.5f)
                path.arcTo(box, 180f, 180f)
                path.cubicTo(18.5f, 15f, 12f, 21f, 12f, 21f)
                path.moveTo(4f, 4f)
                path.lineTo(20f, 20f)
                canvas.drawPath(path, stroke)
            }
            Icon.CHEVRON -> {
                path.moveTo(9f, 6f)
                path.lineTo(15f, 12f)
                path.lineTo(9f, 18f)
                canvas.drawPath(path, stroke)
            }
            Icon.UP -> {
                path.moveTo(12f, 6f)
                path.lineTo(19f, 17f)
                path.lineTo(5f, 17f)
                path.close()
                canvas.drawPath(path, solid)
            }
            Icon.DOWN -> {
                path.moveTo(12f, 18f)
                path.lineTo(19f, 7f)
                path.lineTo(5f, 7f)
                path.close()
                canvas.drawPath(path, solid)
            }
            Icon.PARK, Icon.DRIVE -> {
                canvas.drawCircle(12f, 12f, 10f, stroke)
                letter.textSize = 13f
                canvas.drawText(if (kind == Icon.PARK) "P" else "D", 12f, 16.6f, letter)
            }
            Icon.BOLT -> {
                bolt(path)
                canvas.drawPath(path, solid)
            }
            Icon.DROP -> {
                drop(path)
                canvas.drawPath(path, solid)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** A lightning bolt on the 24 grid (electric cars). */
        fun bolt(path: Path) {
            path.moveTo(13.5f, 2f)
            path.lineTo(5f, 13.5f)
            path.lineTo(11f, 13.5f)
            path.lineTo(10f, 22f)
            path.lineTo(19f, 10f)
            path.lineTo(13f, 10f)
            path.close()
        }

        /** A drop on the 24 grid (fuel cars). */
        fun drop(path: Path) {
            path.moveTo(12f, 3f)
            path.cubicTo(12f, 3f, 5.5f, 10.5f, 5.5f, 15f)
            path.arcTo(RectF(5.5f, 8.5f, 18.5f, 21.5f), 180f, -180f)
            path.cubicTo(18.5f, 10.5f, 12f, 3f, 12f, 3f)
            path.close()
        }
    }
}

/**
 * Four bars that move while the character's line is fresh (the picture of speaking) and then rest: [kick] starts
 * them for a while. A line that is shown without motion (driving, animations off) gets the resting bars.
 */
class VoiceBarsDrawable(tint: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }
    private var until = 0L

    fun kick(ms: Long) {
        until = SystemClock.uptimeMillis() + ms
        invalidateSelf()
    }

    /** Stops the bars where motion is no longer allowed. */
    fun rest() {
        if (until == 0L) return
        until = 0L
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width() / (REST.size * 2f - 1f)
        val now = SystemClock.uptimeMillis()
        val live = now < until
        REST.forEachIndexed { i, rest ->
            val share = if (live) 0.25f + 0.75f * abs(sin(now / 140.0 + i * 1.3)).toFloat() else rest
            val h = b.height() * share
            val left = b.left + i * 2f * w
            canvas.drawRoundRect(left, b.exactCenterY() - h / 2f, left + w, b.exactCenterY() + h / 2f, w / 2f, w / 2f, paint)
        }
        if (live) invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val REST = floatArrayOf(0.4f, 0.75f, 0.55f, 0.3f)
    }
}
