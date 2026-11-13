package com.zzandrive.app.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import com.zzandrive.core.motion.CharacterClips
import com.zzandrive.core.motion.Clip
import com.zzandrive.core.voice.Persona
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Plays the character's clips in one place (spec R-46, D-18). Every clip shares one camera, so the
 * stage swaps files in place: a looping resting clip for the current level, and one-off clips
 * (level-up, eating, happy, sad) that hand back to it when they end.
 *
 * - Frames are decoded at the size they are shown ([ImageDecoder.setTargetSize], never above the
 *   720 px source) and drawn 1:1, so nothing is stretched or blurred.
 * - A new clip fades in over the previous one, which stays opaque underneath: clips whose first frame
 *   does not match the last one (the food appearing after a level-up) change smoothly, matching
 *   clips change invisibly.
 * - Decoding and drawing stop while the stage cannot be seen ([pause], hidden, [motionAllowed] off).
 *   Coming back decodes the current clip again, so no callback from a stopped drawable is trusted.
 * - Without any clip for the character it falls back to [CharacterArtView].
 */
class CharacterStageView(context: Context) : FrameLayout(context) {
    /** Log tag of the runtime shown on screen (ZzanApp live, ZzanDemo demo); scripts read `CLIP` lines. */
    var logTag: String = "ZzanApp"

    /** False holds the current frame: the car is moving (demo data), an overlay covers the stage, or a still picture is wanted. */
    var motionAllowed: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updateRunning()
            }
        }

    /** True when the chosen character has no art yet and the other character's clips are shown. */
    val borrowed: Boolean get() = clips.borrowed

    private val layers = Array(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } }
    private var front = 0
    private val placeholder = CharacterArtView(context)

    private var persona: Persona? = null
    private var clips: CharacterClips = CharacterClips.EMPTY
    private var level = 0
    private val queue = ArrayDeque<Clip>()
    private var current: Clip? = null // on the front layer, or being decoded for it
    private var currentOnce = false
    private var decodedSide = 0
    private var token = 0
    private var lifecyclePaused = false
    private var shown = false
    private var running = false

    init {
        layers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        placeholder.visibility = GONE
    }

    /** Shows [persona] at [level]: moves to that level's resting loop unless a one-off clip is playing. */
    fun setCharacter(persona: Persona, level: Int) {
        use(persona, level)
        if (!busy) showRest()
    }

    /**
     * Level-up scene for [level] (celebration, then eating the new food), then rests at that level.
     * Call it before [setCharacter] for the same state, so the new food is not shown before the celebration.
     */
    fun playLevelUp(persona: Persona, level: Int) {
        use(persona, level)
        play(clips.levelUp(level))
    }

    private fun use(persona: Persona, level: Int) {
        if (persona != this.persona) {
            this.persona = persona
            clips = clipsFor(context, persona)
            queue.clear()
            current = null
            currentOnce = false
            val none = clips.isEmpty
            placeholder.visibility = if (none) VISIBLE else GONE
            layers.forEach { it.visibility = if (none) GONE else VISIBLE }
            placeholder.persona = persona
        }
        this.level = level
        placeholder.level = level
    }

    /** A tap: eat the current food (or hop). Ignored while another one-off clip plays. */
    fun playTap() {
        if (!busy) play(listOfNotNull(clips.tap(level)))
    }

    /** After a settlement: happy when credited, sad when not. Ignored while another one-off clip plays. */
    fun playReaction(happy: Boolean) {
        if (!busy) play(listOfNotNull(clips.reaction(happy)))
    }

    /** Activity stopped: stop decoding and drawing. */
    fun pause() {
        lifecyclePaused = true
        updateRunning()
    }

    /** Activity started again: replay what should be on screen. */
    fun resume() {
        lifecyclePaused = false
        updateRunning()
    }

    private val busy: Boolean get() = currentOnce || queue.isNotEmpty()

    private fun play(list: List<Clip>) {
        if (list.isEmpty()) return
        queue.clear()
        queue.addAll(list)
        next()
    }

    private fun next() {
        val clip = queue.removeFirstOrNull()
        if (clip != null) show(clip, once = true) else showRest()
    }

    private fun showRest() {
        val rest = clips.base(level) ?: return
        if (rest == current && !currentOnce) return // already looping it
        show(rest, once = false)
    }

    private fun show(clip: Clip, once: Boolean) {
        current = clip
        currentOnce = once
        val t = ++token
        val side = targetSide()
        if (side == 0) return // not laid out yet: onSizeChanged shows it
        val app = context.applicationContext
        val tag = logTag
        DECODER.execute {
            val d = decode(app, clip.path, side, tag)
            MAIN.post { if (t == token && d != null) attach(clip, once, d, side) }
        }
    }

    private fun attach(clip: Clip, once: Boolean, d: Drawable, side: Int) {
        decodedSide = side
        layers.forEach { it.animate().cancel() }
        val outgoing = layers[front]
        outgoing.alpha = 1f
        val incoming = layers[1 - front]
        release(incoming)
        incoming.setImageDrawable(d)
        incoming.alpha = 0f
        incoming.bringToFront()
        front = 1 - front
        if (d is AnimatedImageDrawable) {
            d.repeatCount = if (once) 0 else AnimatedImageDrawable.REPEAT_INFINITE
            if (once) {
                val t = token
                d.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        MAIN.post { if (t == token && running) next() }
                    }
                })
            }
            if (running) {
                d.start()
                Log.i(logTag, "CLIP ${clip.name} ${d.intrinsicWidth}px")
            }
        }
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction { release(outgoing) }.start()
    }

    private fun updateRunning() {
        val should = !lifecyclePaused && motionAllowed && shown
        if (should == running) return
        running = should
        if (should) {
            val c = current
            if (c != null) show(c, currentOnce) else showRest()
        } else {
            token++ // drop decodes in flight
            layers.forEach { iv -> (iv.drawable as? AnimatedImageDrawable)?.let { it.clearAnimationCallbacks(); it.stop() } }
            if (current != null) Log.i(logTag, "CLIP stop")
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        updateRunning()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = targetSide()
        val c = current ?: return
        if (side == 0) return
        // A one-off clip keeps its size (CENTER_INSIDE only shrinks); the next clip is decoded at the new size.
        if (decodedSide == 0 || (!currentOnce && abs(side - decodedSide) > RESIZE_SLACK)) show(c, currentOnce)
    }

    override fun onDetachedFromWindow() {
        token++
        layers.forEach(::release)
        decodedSide = 0
        running = false
        super.onDetachedFromWindow()
    }

    private fun targetSide(): Int = minOf(width, height, MAX_SIDE).let { if (it < MIN_SIDE) 0 else it }

    private fun release(iv: ImageView) {
        (iv.drawable as? AnimatedImageDrawable)?.let {
            it.clearAnimationCallbacks()
            it.stop()
        }
        iv.setImageDrawable(null)
    }

    companion object {
        private const val FADE_MS = 250L
        private const val MAX_SIDE = 720 // source size: never scale up
        private const val MIN_SIDE = 16
        private const val RESIZE_SLACK = 8

        private val MAIN = Handler(Looper.getMainLooper())
        private val DECODER: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "zzan-clip").apply { isDaemon = true } }
        private val cache = HashMap<Persona, CharacterClips>()

        /** Clips in assets/character/<persona>/; a character without any borrows 짠돌이's (shown as "그림 준비 중"). */
        fun clipsFor(context: Context, persona: Persona): CharacterClips = cache.getOrPut(persona) {
            fun scan(p: Persona): CharacterClips {
                val dir = "character/${p.name.lowercase()}"
                return CharacterClips.parse(dir, context.assets.list(dir)?.toList().orEmpty())
            }
            val own = scan(persona)
            if (persona == Persona.ZZANDORI) own else CharacterClips.resolve(own, scan(Persona.ZZANDORI))
        }

        private fun decode(context: Context, path: String, side: Int, tag: String): Drawable? = try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, path)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w > side) decoder.setTargetSize(side, (h.toLong() * side / w).toInt().coerceAtLeast(1))
            }
        } catch (e: Exception) {
            Log.w(tag, "CLIP decode failed $path", e)
            null
        }
    }
}
