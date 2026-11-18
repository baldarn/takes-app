package app.takes

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import app.takes.core.ProjectRestore
import app.takes.core.Size
import app.takes.core.yourec.YouRecNote
import java.io.File

/** What the app looks at in the media of a ZIP made by YouRec, the iPhone original, and how it tells what changed (docs/IOS-INTEROP.md, 3.5). */
object YouRecMedia {
    val probe = ProjectRestore.MediaProbe(size = ::uprightSize, playable = ::playable)

    /**
     * A video's size as it is shown, turned by its rotation, as the original reads a take's frame
     * (YouRec/MediaRenderer.swift:513-516); the core rounds it to even pixels as there.
     */
    private fun uprightSize(file: File): Size? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            fun number(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
            val width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return null
            val height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return null
            val turned = (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0) % 180 != 0
            if (turned) Size(height.toDouble(), width.toDouble()) else Size(width.toDouble(), height.toDouble())
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Whether this device has a decoder for every picture and sound track of [file]. ProRes, which an iPhone
     * may import, has none. Dolby Vision is read as the HEVC beneath it, so it is not asked about.
     */
    private fun playable(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            (0 until extractor.trackCount).all { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/") || mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) return@all true
                codecs.findDecoderForFormat(format) != null
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** One line saying what [note] means, in the person's language. */
    fun describe(context: Context, note: YouRecNote): String {
        val files = note.files.joinToString(", ")
        return when (note.kind) {
            YouRecNote.Kind.CanvasFixed -> context.getString(R.string.yourec_canvas_fixed, files)
            YouRecNote.Kind.CanvasDropped -> context.getString(R.string.yourec_canvas_dropped, files)
            YouRecNote.Kind.RecordingRunEnded -> context.getString(R.string.yourec_recording_run_ended)
            YouRecNote.Kind.MissingMedia -> context.getString(R.string.yourec_missing_media, files)
            YouRecNote.Kind.DamagedMedia -> context.getString(R.string.yourec_damaged_media, files)
            YouRecNote.Kind.AudioNotConverted -> context.getString(R.string.yourec_audio_not_converted, files)
            YouRecNote.Kind.UnplayableVideo -> context.getString(R.string.yourec_unplayable, files)
            YouRecNote.Kind.NeuralStagesUnprocessed -> context.getString(R.string.yourec_neural_unprocessed)
            YouRecNote.Kind.TitlesShortened -> context.getString(R.string.yourec_titles_shortened)
            YouRecNote.Kind.LegacyEffectsDropped -> context.getString(R.string.yourec_legacy_effects)
        }
    }
}
