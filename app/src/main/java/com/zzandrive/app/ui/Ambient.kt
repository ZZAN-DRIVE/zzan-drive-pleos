package com.zzandrive.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * What lies behind the glass, and the screen's ambient motion: the backdrop (one base colour and four large soft
 * blobs of the CI colours that drift very slowly) and the pieces that float around the character (coins in the
 * mascot's yellow, bolts for an electric car or drops for a fuel car, glass bubbles).
 *
 * Nothing here carries information, so nothing here is read. It moves only while [running] (parked, visible, no
 * popup on top, system animations on); otherwise it is a still picture. [pulse] (a saving was credited) swells the
 * pieces once; [burst] (a level was reached) throws coins and sparkles out from the character.
 */
class AmbientView(context: Context, private val palette: Palette) : View(context) {
    var running: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            lastFrame = 0L
            invalidate()
        }

    /** Bolts (electric) or drops (fuel) among the pieces. */
    var electric: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val hero = RectF()
    private var time = 0f
    private var lastFrame = 0L
    private var pulseAt = -10f
    private var burstAt = -10f
    private val blobPaints = palette.blobs.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val blobRadius = FloatArray(BLOBS.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Pleos.typeface(700)
    }
    private val mark = Path()
    private val sparkle = Path()
    private val boltColor = (0xFF shl 24) or Palette.ACTIVE_BLUE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Where the character stands, in this view's coordinates: the pieces arrange themselves around it. */
    fun setHero(left: Float, top: Float, right: Float, bottom: Float) {
        if (hero.left == left && hero.top == top && hero.right == right && hero.bottom == bottom) return
        hero.set(left, top, right, bottom)
        invalidate()
    }

    fun pulse() {
        if (running) pulseAt = time
    }

    fun burst() {
        if (running) burstAt = time
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val unit = min(w, h).toFloat()
        if (unit <= 0f) return // nothing to draw yet (a gradient needs a radius)
        BLOBS.forEachIndexed { i, b ->
            val r = b.r * unit
            blobRadius[i] = r
            val c = palette.blobs[i]
            // A smoothstep falloff: no bright point in the middle and no visible rim at the edge.
            blobPaints[i].shader = RadialGradient(
                0f, 0f, r,
                IntArray(FALLOFF.size) { GlassDrawable.scaleAlpha(c, FALLOFF[it]) },
                FloatArray(FALLOFF.size) { it / (FALLOFF.size - 1f) }, Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (running) {
            val now = SystemClock.uptimeMillis()
            if (lastFrame != 0L) time += min(now - lastFrame, 64L) / 1000f
            lastFrame = now
        }
        canvas.drawColor(palette.page)
        val unit = min(width, height).toFloat()
        BLOBS.forEachIndexed { i, b ->
            if (blobRadius[i] <= 0f) return@forEachIndexed
            // The warm blob stays behind the character wherever the layout puts it.
            val bx = if (b.behindHero && !hero.isEmpty) hero.centerX() else b.x * width
            val by = if (b.behindHero && !hero.isEmpty) hero.centerY() + hero.height() * 0.06f else b.y * height
            val x = bx + b.drift * unit * sin(TWO_PI * time / b.period + b.phase)
            val y = by + b.drift * unit * cos(TWO_PI * time / (b.period * 1.31f) + b.phase * 1.7f)
            val save = canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, blobRadius[i], blobPaints[i])
            canvas.restoreToCount(save)
        }
        if (!hero.isEmpty) {
            drawBurst(canvas)
            drawPieces(canvas)
        }
        // Every display frame while it runs: measured on the Pleos emulator, a steady full-rate loop drops fewer
        // frames (under 2 %) than drawing every other one beside the character's own animation (over 40 %).
        if (running) postInvalidateOnAnimation()
    }

    private fun drawPieces(canvas: Canvas) {
        val half = min(hero.width(), hero.height()) / 2f
        val k = (half * 2f / (600f * d)).coerceIn(0.45f, 1.1f)
        val swell = 1f + 0.3f * bump((time - pulseAt) / 0.6f)
        PIECES.forEach { p ->
            val a = Math.toRadians(p.angle.toDouble())
            val phase = TWO_PI * time / p.period + p.phase
            val x = hero.centerX() + (cos(a) * p.reach * half).toFloat() + 4f * d * k * sin(phase * 0.5f)
            val y = hero.centerY() + (sin(a) * p.reach * half * 0.92f).toFloat() + p.bob * d * k * sin(phase)
            val size = p.size * d * k * swell
            drawPiece(canvas, p.kind, x, y, size, 9f * sin(phase * 0.7f + 1f), 1f, shadow = true)
        }
    }

    /** Level-up: pieces fly out from the character, slow down, fall a little and fade. */
    private fun drawBurst(canvas: Canvas) {
        val t = time - burstAt
        if (t < 0f || t > BURST_S) return
        val q = t / BURST_S
        val fade = 1f - smooth((q - 0.55f) / 0.45f)
        val travel = 1f - (1f - q) * (1f - q) * (1f - q) // ease out
        val half = min(hero.width(), hero.height()) / 2f
        for (i in 0 until BURST_COUNT) {
            val a = Math.toRadians((i * 137.5).rem(360.0))
            val reach = half * (0.55f + 0.75f * noise(i * 1.7f))
            val x = hero.centerX() + (cos(a) * reach).toFloat() * travel
            val y = hero.centerY() + (sin(a) * reach).toFloat() * travel + 160f * d * q * q
            val size = (18f + 22f * noise(i * 3.1f)) * d
            val kind = when (i % 3) {
                0 -> Kind.COIN
                1 -> Kind.SPARKLE
                else -> Kind.MARK
            }
            drawPiece(canvas, kind, x, y, size, 220f * q * (if (i % 2 == 0) 1f else -1f), fade, shadow = false)
        }
    }

    private fun drawPiece(canvas: Canvas, kind: Kind, x: Float, y: Float, size: Float, turn: Float, alpha: Float, shadow: Boolean) {
        if (alpha <= 0.01f || size <= 1f) return
        val r = size / 2f
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(turn)
        paint.style = Paint.Style.FILL
        when (kind) {
            Kind.COIN -> {
                paint.color = Palette.LEVEL
                paint.alpha = a
                if (shadow) paint.setShadowLayer(r * 0.5f, 0f, r * 0.22f, palette.shadow)
                canvas.drawCircle(0f, 0f, r, paint)
                paint.clearShadowLayer()
                edge.color = Palette.LEVEL_RIM
                edge.alpha = a
                edge.strokeWidth = r * 0.14f
                canvas.drawCircle(0f, 0f, r * 0.76f, edge)
                glyph.color = Palette.ON_LEVEL
                glyph.alpha = (a * 0.62f).toInt()
                glyph.textSize = r * 1.02f
                canvas.drawText("₩", 0f, r * 0.36f, glyph)
            }
            Kind.MARK -> {
                mark.reset()
                if (electric) IconDrawable.bolt(mark) else IconDrawable.drop(mark)
                canvas.scale(size / 24f, size / 24f)
                canvas.translate(-12f, -12f)
                paint.color = boltColor
                paint.alpha = a
                canvas.drawPath(mark, paint)
            }
            Kind.BUBBLE -> {
                // A soap bubble: thinner than the panels' glass, the same bright edge, one small highlight.
                paint.color = GlassDrawable.scaleAlpha(palette.glass, 0.6f)
                canvas.drawCircle(0f, 0f, r, paint)
                edge.color = palette.edgeLight
                edge.strokeWidth = 1.5f * d
                canvas.drawCircle(0f, 0f, r - 0.75f * d, edge)
                paint.color = palette.edgeLight
                canvas.drawCircle(-r * 0.36f, -r * 0.36f, r * 0.16f, paint)
            }
            Kind.SPARKLE -> {
                sparkle.reset()
                val w = r * 0.26f
                sparkle.moveTo(0f, -r)
                sparkle.quadTo(w, -w, r, 0f)
                sparkle.quadTo(w, w, 0f, r)
                sparkle.quadTo(-w, w, -r, 0f)
                sparkle.quadTo(-w, -w, 0f, -r)
                sparkle.close()
                paint.color = if (palette.night) Color.WHITE else palette.accent
                paint.alpha = a
                canvas.drawPath(sparkle, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    /** 0 → 1 → 0 over 0..1, flat outside. */
    private fun bump(x: Float): Float = if (x <= 0f || x >= 1f) 0f else sin(PI.toFloat() * x)

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** A fixed pseudo-random 0..1 for an index, so every burst looks composed and none needs a random source. */
    private fun noise(i: Float): Float {
        val v = sin(i * 12.9898f) * 43758.547f
        return v - floor(v)
    }

    private enum class Kind { COIN, MARK, BUBBLE, SPARKLE }

    /** [x], [y] in parts of the view; [r] and [drift] in parts of its shorter side; [period] in seconds. */
    private class Blob(val x: Float, val y: Float, val r: Float, val drift: Float, val period: Float, val phase: Float, val behindHero: Boolean = false)

    /** [angle] in degrees around the character (0 = right, −90 = up), [reach] in parts of its half size, [size] and [bob] in dp. */
    private class Piece(val kind: Kind, val angle: Float, val reach: Float, val size: Float, val bob: Float, val period: Float, val phase: Float)

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()
        const val BURST_S = 1.8f
        const val BURST_COUNT = 30

        /** A blob's opacity from its centre to its edge, in equal steps. */
        val FALLOFF = floatArrayOf(1f, 0.896f, 0.648f, 0.352f, 0.104f, 0f)

        /** In the order of [Palette.blobs]: behind the dashboard, bottom right, behind the character, top left. */
        val BLOBS = listOf(
            Blob(0.80f, 0.16f, 0.62f, 0.045f, 37f, 0.0f),
            Blob(0.96f, 0.96f, 0.50f, 0.040f, 29f, 2.1f),
            Blob(0.21f, 0.56f, 0.50f, 0.025f, 43f, 4.0f, behindHero = true),
            Blob(0.04f, 0.02f, 0.42f, 0.035f, 33f, 1.2f),
        )

        /** Far pieces first (bubbles), so the coins and marks pass in front of them. */
        val PIECES = listOf(
            Piece(Kind.BUBBLE, -118f, 0.80f, 30f, 7f, 6.1f, 1.3f),
            Piece(Kind.BUBBLE, 12f, 0.97f, 24f, 6f, 5.4f, 2.9f),
            Piece(Kind.BUBBLE, 176f, 0.84f, 40f, 9f, 6.9f, 2.4f),
            Piece(Kind.COIN, -150f, 0.92f, 56f, 10f, 5.2f, 0.0f),
            Piece(Kind.MARK, -62f, 0.98f, 46f, 9f, 4.6f, 2.1f),
            Piece(Kind.COIN, -28f, 0.88f, 40f, 8f, 6.6f, 0.7f),
            Piece(Kind.COIN, 38f, 0.90f, 34f, 7f, 4.9f, 1.9f),
            Piece(Kind.MARK, 152f, 0.93f, 34f, 8f, 5.8f, 0.4f),
            Piece(Kind.COIN, -172f, 0.99f, 28f, 6f, 4.4f, 3.3f),
        )
    }
}
