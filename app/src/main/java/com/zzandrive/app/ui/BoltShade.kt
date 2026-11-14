package com.zzandrive.app.ui

import android.annotation.TargetApi
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.widget.ImageView
import com.zzandrive.core.motion.BoltGauge
import com.zzandrive.core.motion.Clip
import kotlin.math.roundToInt

/**
 * Empties the top of the bolt above the character's head (spec D-40), for one layer of the stage. The team's v23 clips
 * ship with the bolt full (`_bolt100`); above the fill line its yellow turns into the grey of an empty bolt, the way the
 * designer drew the other fills. Inside the bolt's box ([BoltGauge]) only the bolt is yellow, so nothing else changes.
 *
 * It is a RenderEffect on the layer, so the GPU applies it to every frame of the animation (Android 13+). Without that,
 * or if the platform refuses the shader, the bolt stays full.
 */
internal class BoltShade {
    private var shader: Any? = null // RuntimeShader, kept as Any so this class also loads before Android 13

    /**
     * Shows [fill] (0..1) of the bolt on [iv], which shows [clip]. Clips without a full bolt drawn in them, and a full
     * gauge, take the effect off. Returns the bolt's box on [iv] in px, or null when nothing is drawn.
     */
    fun apply(iv: ImageView, clip: Clip?, fill: Float, tag: String): Rect? {
        val d = iv.drawable
        val level = clip?.level
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || clip?.bolt != FULL || level == null ||
            d == null || d.intrinsicWidth <= 0 || fill >= 1f
        ) {
            clear(iv)
            return null
        }
        return try {
            shade(iv, d.intrinsicWidth, level, fill)
        } catch (e: RuntimeException) { // the shader did not compile or a uniform was refused
            failed = true
            Log.w(tag, "BOLT off", e)
            clear(iv)
            null
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun shade(iv: ImageView, drawnWidth: Int, level: Int, fill: Float): Rect {
        val s = shader as? RuntimeShader ?: RuntimeShader(AGSL).also { shader = it }
        // Where the clip's 720 px canvas is on the layer: CENTER_INSIDE places the decoded frame with this matrix.
        val m = FloatArray(9).also { iv.imageMatrix.getValues(it) }
        val x = m[Matrix.MTRANS_X]
        val y = m[Matrix.MTRANS_Y]
        val scale = m[Matrix.MSCALE_X] * drawnWidth / BoltGauge.CANVAS
        s.setFloatUniform("origin", x, y)
        s.setFloatUniform("scale", scale)
        s.setFloatUniform("box", BoltGauge.LEFT, BoltGauge.TOP, BoltGauge.RIGHT, BoltGauge.BOTTOM)
        s.setFloatUniform("fillLine", BoltGauge.lineRow(level, fill))
        // An effect keeps the values it was made with: a new one for every change.
        iv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "src"))
        fun px(canvas: Float, origin: Float) = (origin + canvas * scale).roundToInt()
        return Rect(px(BoltGauge.LEFT, x), px(BoltGauge.TOP, y), px(BoltGauge.RIGHT, x), px(BoltGauge.BOTTOM, y))
    }

    companion object {
        private const val FULL = 100

        /** Set once the platform refused the effect: the bolt then stays full until the app restarts. */
        @Volatile
        private var failed = false

        fun clear(iv: ImageView) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) iv.setRenderEffect(null)
        }

        /**
         * Inside the box (canvas px), above the fill line, a pixel's yellow (min(R, G) − B, from 20/255 to 60/255) turns
         * into grey = 0.986 × max(R, G) − 6.7/255. Both fitted to the designer's files: pixels of the full bolt against
         * the same pixels of the empty one (RMSE 2.7/255). Colours come premultiplied; alpha is kept.
         */
        private const val AGSL = """
            uniform shader src;
            uniform float2 origin;
            uniform float scale;
            uniform float4 box;
            uniform float fillLine;

            half4 main(float2 p) {
                float4 c = float4(src.eval(p));
                float2 q = (p - origin) / scale;
                if (c.a <= 0.0 || q.x < box.x || q.x >= box.z || q.y < box.y || q.y >= box.w) {
                    return half4(c);
                }
                float3 rgb = c.rgb / c.a;
                float yellow = smoothstep(0.078, 0.235, min(rgb.r, rgb.g) - rgb.b);
                float above = 1.0 - smoothstep(fillLine - 0.5, fillLine + 0.5, q.y);
                float grey = 0.986 * max(rgb.r, rgb.g) - 0.026;
                return half4(float4(mix(rgb, float3(grey), yellow * above) * c.a, c.a));
            }
        """
    }
}
