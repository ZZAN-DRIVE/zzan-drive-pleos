package com.zzandrive.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zzandrive.app.runtime.Listener
import java.util.Locale

/**
 * Pleos SpeechToText (ON_DEVICE: no network, no app registration) for one short answer at a time. The window is
 * kept by the app because the SDK has no timeout setting; every path ends with stop(), so the microphone is never
 * left open (a session survived its app by about an hour on the emulator).
 */
class PleosSttListener(context: Context) : Listener {
    private val main = Handler(Looper.getMainLooper())
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false
    private var pending: ((String?) -> Unit)? = null

    /** The latest partial hypothesis (isFinal=false). Never taken as the answer: a cut-off "응…" must not approve. */
    private var lastText: String? = null
    private var graced = false

    /**
     * End of the window. Still talking (a partial is on the way): wait once more for the final result. Otherwise, or after
     * that grace, end with nothing heard; a partial result is dropped.
     */
    private val window = Runnable {
        if (lastText != null && !graced) {
            graced = true
            Log.i(TAG, "window: still talking, wait for the final result")
            main.postDelayed(windowEnd, GRACE_MS)
        } else {
            windowEnd.run()
        }
    }
    private val windowEnd = Runnable {
        Log.i(TAG, "window closed${lastText?.let { " (partial dropped: $it)" } ?: ""}")
        // Something was said but never finalized: "…" is not understood (asked once more), unlike silence (null).
        finish(if (lastText != null) Listener.UNCLEAR else null)
    }

    override val available: Boolean get() = ready

    private val results = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            Log.i(TAG, "update final=$isFinal $text")
            main.post {
                lastText = text
                if (isFinal) finish(text)
            }
        }
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onStartedRecognition() { Log.i(TAG, "listening") }
        // End of speech (EPD). The final result comes after it as onUpdated(isFinal = true), sometimes before any partial
        // (audio sent in one piece), so this does not end the session; the window or its grace does.
        override fun onEndedRecognition() { Log.i(TAG, "end of speech") }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "STT error $errorType")
            main.post { finish(null) }
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        runCatching {
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        stt.addListener(results)
                        stt.setLocale(Locale.KOREA)
                    }.onFailure { Log.w(TAG, "STT setup failed: ${it.message}") }
                    ready = true
                    Log.i(TAG, "STT ready (ON_DEVICE)")
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "STT init failed: $errorType (answers stay on the screen)")
                }
            })
        }.onFailure { Log.w(TAG, "STT unavailable: ${it.message}") }
    }

    override fun listen(windowMs: Long, onHeard: (String?) -> Unit) {
        main.post {
            if (!ready) return@post onHeard(null)
            finish(null) // one session at a time
            pending = onHeard
            lastText = null
            graced = false
            runCatching { stt.request() }
                .onSuccess { Log.i(TAG, "request window=$windowMs") }
                .onFailure { Log.w(TAG, "request failed: ${it.message}"); finish(null); return@post }
            main.postDelayed(window, windowMs)
        }
    }

    override fun recognize(pcm: ByteArray, onHeard: (String?) -> Unit) {
        main.post {
            if (!ready) return@post onHeard(null)
            finish(null)
            pending = onHeard
            lastText = null
            graced = false
            runCatching {
                stt.sendAudio(pcm)
                stt.completeAudioSend()
            }.onSuccess { Log.i(TAG, "sent ${pcm.size} bytes of PCM") }
                .onFailure { Log.w(TAG, "sendAudio failed: ${it.message}"); finish(null); return@post }
            main.postDelayed(window, RECORDED_WINDOW_MS)
        }
    }

    private fun finish(text: String?) {
        val callback = pending ?: return
        pending = null
        main.removeCallbacks(window)
        main.removeCallbacks(windowEnd)
        runCatching { stt.stop() }
        Log.i(TAG, "heard ${text ?: "(nothing)"}")
        callback(text)
    }

    override fun stop() {
        main.post { finish(null) }
    }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { stt.stop() }
        runCatching { stt.removeListener(results); stt.release() }
    }

    private companion object {
        const val TAG = "ZzanStt"
        const val GRACE_MS = 1_500L

        /** Recorded audio (debug): the first recognition after the app starts can take a while to finalize. */
        const val RECORDED_WINDOW_MS = 20_000L
    }
}
