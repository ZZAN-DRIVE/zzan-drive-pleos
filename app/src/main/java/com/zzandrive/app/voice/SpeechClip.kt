package com.zzandrive.app.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A recorded answer for [com.zzandrive.app.runtime.Listener.recognize] (debug command `hearpcm`): 16 kHz, mono, 16-bit
 * little-endian PCM, the format Pleos SpeechToText takes through sendAudio. Reads NAME.wav (the data chunk) or a raw
 * NAME.pcm and adds silence before and after, so the recognizer hears the start and the end of the word.
 */
object SpeechClip {
    private const val RATE = 16_000
    private const val LEAD_MS = 400
    private const val TAIL_MS = 800

    fun read(dir: File, name: String): ByteArray {
        require(name.matches(Regex("[a-z0-9_-]+"))) { "bad name: $name" }
        val wav = File(dir, "$name.wav")
        val pcm = if (wav.exists()) wavData(wav.readBytes()) else File(dir, "$name.pcm").readBytes()
        return silence(LEAD_MS) + pcm + silence(TAIL_MS)
    }

    private fun silence(ms: Int) = ByteArray(RATE * 2 * ms / 1000)

    /** The samples of a RIFF/WAVE file, after checking that they are 16 kHz mono 16-bit PCM. */
    internal fun wavData(b: ByteArray): ByteArray {
        require(b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAV file" }
        val le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var formatOk = false
        while (at + 8 <= b.size) {
            val id = String(b, at, 4, Charsets.US_ASCII)
            val len = le.getInt(at + 4)
            require(len >= 0) { "bad chunk $id" }
            when (id) {
                "fmt " -> formatOk = le.getShort(at + 8).toInt() == 1 && le.getShort(at + 10).toInt() == 1 &&
                    le.getInt(at + 12) == RATE && le.getShort(at + 22).toInt() == 16
                "data" -> {
                    require(formatOk) { "need 16 kHz mono 16-bit PCM" }
                    return b.copyOfRange(at + 8, minOf(b.size, at + 8 + len))
                }
            }
            at += 8 + len + (len and 1)
        }
        error("no data chunk")
    }
}
