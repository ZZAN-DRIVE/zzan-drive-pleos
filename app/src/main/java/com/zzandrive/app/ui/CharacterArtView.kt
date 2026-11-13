package com.zzandrive.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.zzandrive.core.voice.Persona

/**
 * The character for a level. Uses the team's art from assets/character/<persona>/level_<n>.(webp|png)
 * when it exists (transparent background, spec D-18); otherwise draws a simple original placeholder:
 * a round character holding the level's food (emoji glyph from the system font).
 */
class CharacterArtView(context: Context) : View(context) {
    var persona: Persona = Persona.ZZANDORI
        set(value) { field = value; reload() }
    var level: Int = 0
        set(value) { field = value; reload() }

    private var art: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** True when the team's art is shown instead of the placeholder. */
    val usingTeamArt: Boolean get() = art != null

    private fun reload() {
        val dir = "character/${persona.name.lowercase()}"
        art = listOf("webp", "png").firstNotNullOfOrNull { ext ->
            runCatching { context.assets.open("$dir/level_$level.$ext").use(BitmapFactory::decodeStream) }.getOrNull()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        art?.let {
            val scale = minOf(w / it.width, h / it.height)
            val dw = it.width * scale
            val dh = it.height * scale
            canvas.drawBitmap(it, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
            return
        }
        val r = minOf(w, h) * 0.30f
        val cx = w * 0.42f
        val cy = h * 0.55f
        // shadow
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawOval(RectF(cx - r, cy + r * 0.85f, cx + r, cy + r * 1.15f), paint)
        // body
        paint.color = if (persona == Persona.ZZANDORI) Color.rgb(245, 197, 66) else Color.rgb(242, 141, 178)
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = Color.rgb(60, 40, 10)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.06f
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        // eyes and mouth
        canvas.drawCircle(cx - r * 0.33f, cy - r * 0.15f, r * 0.08f, paint)
        canvas.drawCircle(cx + r * 0.33f, cy - r * 0.15f, r * 0.08f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(cx - r * 0.35f, cy - r * 0.05f, cx + r * 0.35f, cy + r * 0.45f), 20f, 140f, false, paint)
        paint.style = Paint.Style.FILL
        if (persona == Persona.ZZANSUNI) { // bow
            paint.color = Color.rgb(200, 40, 90)
            canvas.drawCircle(cx + r * 0.55f, cy - r * 0.85f, r * 0.16f, paint)
            canvas.drawCircle(cx + r * 0.85f, cy - r * 0.95f, r * 0.16f, paint)
        }
        // food
        text.textSize = r * 0.95f
        canvas.drawText(FOOD_GLYPHS.getOrElse(level) { "🦀" }, cx + r * 1.25f, cy + r * 0.35f, text)
        // placeholder note
        text.textSize = r * 0.13f
        text.color = Color.argb(150, 255, 255, 255)
        canvas.drawText("그림 준비 중", w / 2, h - r * 0.12f, text)
        text.color = Color.BLACK
    }

    companion object {
        /** 빈 지갑, 삼각김밥, 아메리카노, 짜장면, 파스타, 치킨, 족발, 스테이크, 한우, 랍스터, 킹크랩. */
        val FOOD_GLYPHS = listOf("👛", "🍙", "☕", "🍜", "🍝", "🍗", "🍖", "🥩", "🍱", "🦞", "🦀")
    }
}
