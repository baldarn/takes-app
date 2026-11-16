package app.takes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The short chime a finished export plays, as the original's `ExportCompletionSound`
 * (YouRec/EditorController.swift:870-895): 0.3 s made here, 880 Hz with a softer 1320 Hz, a 5 ms rise and a
 * fall to silence, at a low level. As in the original (YouRec/EditorController.swift:814-817), it sounds when
 * the screen showing a newly made movie appears, once for that movie ([finished]); that screen gives the
 * "success" tap.
 */
object ExportChime {
    private const val RATE = 48_000
    private const val COUNT = 14_400

    private val samples: ShortArray by lazy {
        ShortArray(COUNT) { index ->
            val time = index.toDouble() / RATE
            val envelope = min(1.0, index / 240.0) * (1 - index.toDouble() / COUNT).pow(2)
            val sample = (sin(time * 2 * PI * 880) + sin(time * 2 * PI * 1320) * 0.4) * envelope * 0.12
            (sample * 32767).toInt().toShort()
        }
    }

    private var track: AudioTrack? = null

    /** The movie the chime last sounded for: an export's result is a new [java.io.File] each time it makes one. */
    private var chimed: java.io.File? = null

    /**
     * Plays the chime when [movie], the export's result now on screen, has not had it yet, as the original's
     * `exportActionsAppeared` with its `pendingExportSound`; true when it did, for the screen to give the tap.
     */
    fun finished(movie: java.io.File): Boolean {
        if (chimed === movie) return false
        chimed = movie
        play()
        return true
    }

    private fun play() {
        try {
            track?.release()
            val next = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(COUNT * 2)
                .build()
            next.write(samples, 0, COUNT)
            next.play()
            track = next
        } catch (_: Exception) {
            // No sound is no harm: the movie is made.
        }
    }
}
