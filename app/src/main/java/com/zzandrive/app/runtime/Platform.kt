package com.zzandrive.app.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zzandrive.core.domain.RoutePlanner
import com.zzandrive.core.domain.TriggerPolicy
import com.zzandrive.core.settlement.LedgerCodec
import com.zzandrive.core.settlement.LedgerEvent
import com.zzandrive.core.voice.Persona
import com.zzandrive.data.api.Cancellable
import com.zzandrive.data.api.Clock
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.Scheduler
import java.io.File
import java.io.FileOutputStream

/** Main-thread scheduler and wall clock. */
class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler, Clock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val r = Runnable { action() }
        handler.postDelayed(r, delayMs)
        return Cancellable { handler.removeCallbacks(r) }
    }
}

/** Speaks a line (TTS). Lines queue up and play in order. */
interface Speaker {
    fun speak(text: String)

    /** Speaks, then calls [onDone] with whether the line was actually played (false: no audio focus or no engine). */
    fun speakThen(text: String, onDone: (played: Boolean) -> Unit) {
        speak(text)
        onDone(false)
    }

    /**
     * Speaks [text] only while [isCurrent] holds: a line that went stale before it was played (a question already
     * answered by a tap, a demo switched off) is dropped, and [refresh] stops one that is playing.
     * [onDone] as in [speakThen]; a dropped line reports false.
     */
    fun speakWhile(text: String, isCurrent: () -> Boolean, onDone: ((played: Boolean) -> Unit)? = null) {
        if (onDone == null) speak(text) else speakThen(text, onDone)
    }

    /** Re-checks the lines given to [speakWhile]: drops the stale ones waiting and stops a stale one playing. */
    fun refresh() {}

    /** The character whose voice the next lines use (기획서 p.9 "두 캐릭터는 목소리와 말투가 다르고"). */
    fun voice(persona: Persona) {}

    fun release() {}
}

/** Listens once for a short spoken answer (Pleos SpeechToText). */
interface Listener {
    /** Initialized and allowed to listen now. */
    val available: Boolean

    /**
     * Opens the microphone for at most [windowMs]; [onHeard] gets the final text, null for silence or an error, or
     * [UNCLEAR] when speech was heard but never finalized (the question is asked once more, not taken as silence).
     */
    fun listen(windowMs: Long, onHeard: (String?) -> Unit)

    /** Recognizes recorded 16 kHz 16-bit mono PCM instead of the microphone (ON_DEVICE only; emulator tests). */
    fun recognize(pcm: ByteArray, onHeard: (String?) -> Unit) = onHeard(null)

    fun stop() {}
    fun release() {}

    companion object {
        /** Speech without a final result: not an answer, not silence. */
        const val UNCLEAR = "…"

        val NONE = object : Listener {
            override val available = false
            override fun listen(windowMs: Long, onHeard: (String?) -> Unit) = onHeard(null)
        }
    }
}

/** Hands a destination to navigation (live: Pleos NaviHelper; demo: logs only). */
fun interface Navigator {
    fun navigateTo(id: String, name: String, point: GeoPoint): Boolean
}

/** Where ledger events live. */
interface LedgerStore {
    fun load(): List<String>
    fun append(event: LedgerEvent)
    fun clear()
}

/**
 * Append-only JSON Lines file in app-private storage (D-16: never leaves the device).
 * Each append is flushed to disk; a cut-off last line is skipped on load by [LedgerCodec].
 */
class FileLedgerStore(context: Context, name: String = "ledger.jsonl") : LedgerStore {
    private val file = File(context.filesDir, name)

    override fun load(): List<String> = if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()

    override fun append(event: LedgerEvent) {
        FileOutputStream(file, true).use { out ->
            out.write((LedgerCodec.encode(event) + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    override fun clear() {
        if (file.exists() && !file.delete()) Log.w("ZzanApp", "ledger file could not be deleted")
    }
}

class MemoryLedgerStore : LedgerStore {
    private val lines = mutableListOf<String>()
    override fun load(): List<String> = lines.toList()
    override fun append(event: LedgerEvent) { lines += LedgerCodec.encode(event) }
    override fun clear() = lines.clear()
}

/** Small settings: chosen character and the last level whose celebration was shown and spoken. */
class Prefs(context: Context, name: String = "zzan") {
    private val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var persona: Persona?
        get() = sp.getString("persona", null)?.let { runCatching { Persona.valueOf(it) }.getOrNull() }
        set(value) { sp.edit().putString("persona", value?.name).apply() }

    var lastCelebratedLevel: Int
        get() = sp.getInt("lastCelebratedLevel", 0)
        set(value) { sp.edit().putInt("lastCelebratedLevel", value).apply() }

    /** The last level whose level-up line was spoken (the line plays once; the screen waits for a tap). */
    var lastAnnouncedLevel: Int
        get() = sp.getInt("lastAnnouncedLevel", 0)
        set(value) { sp.edit().putInt("lastAnnouncedLevel", value).apply() }

    /** Answer the character's questions by voice (setting; on by default where speech recognition works). */
    var voiceAnswers: Boolean
        get() = sp.getBoolean("voiceAnswers", true)
        set(value) { sp.edit().putBoolean("voiceAnswers", value).apply() }

    /** The last answer to "얼마나 넣을까?", shown as the suggestion next time (never applied without a tap). */
    var lastFillChoice: String?
        get() = sp.getString("lastFillChoice", null)
        set(value) { sp.edit().putString("lastFillChoice", value).apply() }

    /** spec D-22: when to recommend, in % of the tank or battery (setting; apps cannot read the warning lights). */
    fun threshold(type: EnergyType): Int = TriggerPolicy.clamp(sp.getInt("threshold_${if (type.isElectric) "EV" else "FUEL"}", TriggerPolicy.default(type)))

    fun setThreshold(type: EnergyType, percent: Int) {
        sp.edit().putInt("threshold_${if (type.isElectric) "EV" else "FUEL"}", TriggerPolicy.clamp(percent)).apply()
    }

    /** spec D-31: what an hour on the road is worth when routes are compared (setting; 5,000원 by default). */
    var wage: Int
        get() = sp.getInt("wage", RoutePlanner.DEFAULT_WAGE_PER_HOUR.toInt()).takeIf { it in RoutePlanner.WAGE_CHOICES }
            ?: RoutePlanner.DEFAULT_WAGE_PER_HOUR.toInt()
        set(value) { sp.edit().putInt("wage", value).apply() }

    /** Demo runtime only: every demo starts from the default settings, so it plays the same way each time. */
    fun resetSettings() = sp.edit()
        .remove("threshold_EV").remove("threshold_FUEL").remove("wage").remove("voiceAnswers").remove("lastFillChoice")
        .apply()

    /** "처음부터" resets the record (character, celebrations), not the driver's settings. */
    fun clear() = sp.edit().remove("persona").remove("lastCelebratedLevel").remove("lastAnnouncedLevel").apply()
}
