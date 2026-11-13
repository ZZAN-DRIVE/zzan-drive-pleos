package com.zzandrive.app.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.zzandrive.app.runtime.Speaker
import com.zzandrive.core.voice.Persona
import java.util.Locale

/**
 * Pleos TextToSpeech (ON_DEVICE, no app registration) with a first-in-first-out queue.
 *
 * Each line is played only with audio focus (Pleos audio focus guide: request before playing, stop on loss):
 * the same attributes go to the TTS SDK and to the focus request (media usage, speech content, transient and
 * may-duck), so a call, Gleo or an emergency message wins and the line is dropped rather than talked over.
 * Each character speaks with its own Pleos voice ([setSpeakerType]): 짠돌이 sanghyeok, 짠순이 haeun.
 *
 * The call order is the one verified on the 2.1.0 emulator:
 * initialize → onSuccess { addEventListener; setLocale(Locale.KOREA) } → main.post { speak(text, isFinal = true) }.
 * Speaking synchronously inside onSuccess, using Locale.KOREAN ("ko") or isFinal=false produced no audio.
 */
class PleosTtsSpeaker(context: Context) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private class Line(val text: String, val isCurrent: () -> Boolean, val onDone: ((Boolean) -> Unit)?)

    private val queue = ArrayDeque<Line>()
    private var current: ((Boolean) -> Unit)? = null
    private var currentLine: Line? = null
    private var ready = false
    private var speaking = false
    private val timeout = Runnable { Log.w(TAG, "TTS timeout; stop"); abort() }

    /** We asked the SDK to stop the current line; its onStop (or this fallback) ends it, not the next line. */
    private var stopping = false
    private var resumeAt = 0L
    private val stopFallback = Runnable { if (speaking) { Log.w(TAG, "no stop event; next line"); finishCurrent(played = false) } }

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Log.i(TAG, "focus lost ($change): stop")
                abort()
            }
        }, main)
        .build()
    private var holdsFocus = false
    private var wantedSpeaker: String? = null
    private var appliedSpeaker: String? = null

    private val events = object : EventListener {
        override fun onStart() { Log.i(TAG, "start") } // start/done prove the line was played (e.g. while driving)
        override fun onReady() = Unit
        override fun onDone() {
            Log.i(TAG, "done")
            main.post { if (speaking) finishCurrent(played = !stopping) }
        }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS error $errorType")
            main.post { if (speaking) finishCurrent(played = false) }
        }
        override fun onStop() {
            Log.i(TAG, "stopped")
            main.post { if (speaking) finishCurrent(played = false) }
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        tts.addEventListener(events)
                        tts.setLocale(Locale.KOREA)
                        tts.setAudioAttributes(attributes)
                    }.onFailure { Log.w(TAG, "TTS setup failed: ${it.message}") }
                    main.post {
                        ready = true
                        next()
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS init failed: $errorType (lines stay on screen)")
                }
            })
        }.onFailure { Log.w(TAG, "TTS unavailable: ${it.message}") }
    }

    override fun speak(text: String) = speakWhile(text, { true }, null)

    override fun speakThen(text: String, onDone: (played: Boolean) -> Unit) = speakWhile(text, { true }, onDone)

    override fun speakWhile(text: String, isCurrent: () -> Boolean, onDone: ((played: Boolean) -> Unit)?) {
        main.post {
            queue.addLast(Line(text, isCurrent, onDone))
            next()
        }
    }

    override fun refresh() {
        main.post {
            val stale = queue.filter { !it.isCurrent() }
            queue.removeAll(stale.toSet())
            stale.forEach { it.onDone?.invoke(false) }
            if (speaking && currentLine?.isCurrent?.invoke() == false) {
                Log.i(TAG, "stale line: stop")
                abort()
            }
        }
    }

    override fun voice(persona: Persona) {
        main.post { wantedSpeaker = if (persona == Persona.ZZANDORI) SPEAKER_ZZANDORI else SPEAKER_ZZANSUNI }
    }

    private fun next() {
        if (!ready || speaking) return
        val wait = resumeAt - SystemClock.uptimeMillis()
        if (wait > 0) { main.postDelayed({ next() }, wait); return }
        var item = queue.removeFirstOrNull() ?: return
        while (!item.isCurrent()) { // went stale while waiting (answered by a tap, demo switched off)
            item.onDone?.let { cb -> main.post { cb(false) } }
            item = queue.removeFirstOrNull() ?: return
        }
        val line = item.text
        val onDone = item.onDone
        val granted = runCatching { audio.requestAudioFocus(focus) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // A call, Gleo or an emergency message has the car's audio: drop the line, never talk over it.
            Log.i(TAG, "focus denied ($granted): skipped \"$line\"")
            onDone?.let { cb -> main.post { cb(false) } }
            main.post { next() }
            return
        }
        current = onDone
        currentLine = item
        holdsFocus = true
        wantedSpeaker?.takeIf { it != appliedSpeaker }?.let { s ->
            runCatching { tts.setSpeakerType(s) }
                .onSuccess { appliedSpeaker = s }
                .onFailure { Log.w(TAG, "setSpeakerType($s) failed: ${it.message}") }
        }
        Log.i(TAG, "focus granted speaker=${appliedSpeaker ?: "default"}")
        speaking = true
        runCatching { tts.speak(line, true) }
            .onFailure { Log.w(TAG, "speak failed: ${it.message}"); finishCurrent(played = false) }
        main.postDelayed(timeout, 15_000)
    }

    /** Stops the line being spoken and waits for the SDK's own stop event before the next line starts. */
    private fun abort() {
        if (!speaking || stopping) return
        stopping = true
        main.removeCallbacks(timeout)
        runCatching { tts.stop() }
        main.postDelayed(stopFallback, 1_000)
    }

    /** [played]: the line ended normally (done); false after an error, a timeout or a lost focus (then nobody listens). */
    private fun finishCurrent(played: Boolean = true) {
        main.removeCallbacks(timeout)
        main.removeCallbacks(stopFallback)
        stopping = false
        speaking = false
        currentLine = null
        if (holdsFocus) {
            runCatching { audio.abandonAudioFocusRequest(focus) }
            holdsFocus = false
        }
        current?.let { cb -> current = null; cb(played) } // focus is free again before anyone listens
        // A short gap: late events of the line that just ended arrive while nothing is speaking and are ignored.
        resumeAt = SystemClock.uptimeMillis() + GAP_MS
        main.postDelayed({ next() }, GAP_MS)
    }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        if (holdsFocus) runCatching { audio.abandonAudioFocusRequest(focus) }
        runCatching { tts.removeEventListener(events); tts.release() }
    }

    private companion object {
        const val TAG = "ZzanTts"
        const val GAP_MS = 250L

        /** Pleos Korean voices (setSpeakerType): seoyoon, eunwoo, haeun, sanghyeok. Gleo uses seoyoon here, so neither character does. */
        const val SPEAKER_ZZANDORI = "sanghyeok"
        const val SPEAKER_ZZANSUNI = "haeun"
    }
}
