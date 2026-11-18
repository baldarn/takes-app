package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import app.takes.core.AudioModel
import app.takes.core.ProjectRestore
import app.takes.core.Size
import app.takes.core.yourec.YouRecExport
import app.takes.core.yourec.YouRecNote
import app.takes.restoration.RestorationModels
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** What the app looks at in the media of a ZIP made by YouRec, the iPhone original, or made for it, and how it tells what changes (docs/IOS-INTEROP.md, 3.5, 3.6). */
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

    /** What "Zip for iPhone" asks of a file: the MIME types of its tracks, to block what the iPhone cannot play. */
    val exportProbe = YouRecExport.Probe(mimes = ::mimes)

    private fun mimes(file: File): List<String>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** A picture of an effect re-encoded as PNG, which the iPhone requires of a WebP (docs/IOS-INTEROP.md, 5.3). */
    fun png(file: File): ByteArray {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("A picture of a video effect cannot be read: ${file.name}")
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** One line saying what [issue] of "Zip for iPhone" means, in the person's language. */
    fun describe(context: Context, issue: YouRecExport.Issue): String {
        val files = issue.files.joinToString(", ")
        return when (issue.kind) {
            YouRecExport.Issue.Kind.UnsupportedMedia -> context.getString(R.string.iphone_unsupported_media, files)
            YouRecExport.Issue.Kind.MissingRecording -> context.getString(R.string.iphone_missing_recording, files)
            YouRecExport.Issue.Kind.ColorMotion -> context.getString(R.string.iphone_color_motion)
            YouRecExport.Issue.Kind.FrozenFrame -> context.getString(R.string.iphone_frozen_frame)
            YouRecExport.Issue.Kind.InvalidTimeline -> context.getString(R.string.iphone_invalid_timeline)
            YouRecExport.Issue.Kind.FileNames -> context.getString(R.string.iphone_file_names, files)
            YouRecExport.Issue.Kind.TooLarge -> context.getString(R.string.iphone_too_large)
            YouRecExport.Issue.Kind.AnalysisRecomputed -> context.getString(R.string.iphone_analysis)
            YouRecExport.Issue.Kind.RecordingRunDropped -> context.getString(R.string.iphone_recording_run)
            YouRecExport.Issue.Kind.CaptionRegrouping -> context.getString(R.string.iphone_caption_regrouping)
        }
    }

    /** One line saying what [note] means, in the person's language; empty when it no longer applies. */
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
            YouRecNote.Kind.NeuralStagesUnprocessed -> {
                // Only the models this phone has not downloaded yet; with all of them there is nothing to say.
                val missing = listOf(RestorationModels.Lava to AudioModel.Lava, RestorationModels.VoiceFixer to AudioModel.VoiceFixer)
                    .filter { (model, identity) -> identity.id in note.files && !RestorationModelStore.installed(model) }
                if (missing.isEmpty()) "" else context.getString(R.string.yourec_neural_unprocessed, missing.joinToString(", ") { it.first.name })
            }
            YouRecNote.Kind.TitlesShortened -> context.getString(R.string.yourec_titles_shortened)
            YouRecNote.Kind.LegacyEffectsDropped -> context.getString(R.string.yourec_legacy_effects)
        }
    }
}
