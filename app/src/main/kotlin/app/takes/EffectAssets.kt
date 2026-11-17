package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import app.takes.core.CaptureEffectsFile
import app.takes.core.CapturedEffectAnalysis
import app.takes.core.EffectColorMath
import app.takes.core.EffectFrameInputs
import app.takes.core.EffectImage
import app.takes.core.EffectPalette
import app.takes.core.EffectPaletteAnalysis
import app.takes.core.EffectPaletteTrack
import app.takes.core.EffectValue
import app.takes.core.PersonMaskChunks
import app.takes.core.Size as GeometrySize
import app.takes.core.SourceMedia
import app.takes.core.EffectVector
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTime
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What the effects of a clip read besides its frames: a Quantization's
 * palette taken from the scene, the picture a Chroma Key puts behind the
 * subject, and the person in the frame. Asked for on the thread that draws.
 */
interface EffectAssets {
    /**
     * The person mask of the frame at source second [sourceTime], or with [smoothed] the motion-gated EMA of it that
     * Auto Background reads (the original's `autoMask` stream); null when there is none yet, or none can be made.
     */
    fun person(sourceTime: Double, smoothed: Boolean = false): PersonFrame? = null

    /** The palette analysed from the footage for [effect] at source second [sourceTime], before its own choices; null when there is none. */
    fun analysed(effect: VideoEffectSettings, sourceTime: Double): EffectPalette? = null

    /** The picture of a Chroma Key's Image background, upright and decoded; null without one. */
    fun picture(effect: VideoEffectSettings): Bitmap? = null

    /** The movie of a Chroma Key's or an Auto Background's Video background; null without one. */
    fun movie(effect: VideoEffectSettings): File? = null

    /** Whether a movie's frames are read when they are drawn (an export) rather than in the background (the recorder). */
    val waitsForMovies: Boolean get() = true

    /**
     * A Chroma Key's clean plate, upright and graded as the clip; null without one, when its shape is not the
     * recording's, or while it is read. VideoEffectAssets.swift:492-512.
     */
    fun plate(effect: VideoEffectSettings): CleanPlate? = null

    /**
     * Where the subject [effect] follows is at source second [sourceTime], on the recording stood upright; null when
     * it is not followed there, or not analysed yet. VideoEffectAssets.swift:476-486.
     */
    fun subject(effect: VideoEffectSettings, sourceTime: Double): EffectSubject? = null

    /**
     * The quality [effect] is drawn at at source second [sourceTime]: lighter where the recorder's preview drew it
     * lighter while the take was recorded, as the analysis saved with it says (VideoEffectAssets.swift:474).
     */
    fun quality(effect: VideoEffectSettings, sourceTime: Double): app.takes.core.VideoEffectQuality = app.takes.core.VideoEffectQuality.Full

    companion object {
        val NONE: EffectAssets = object : EffectAssets {}

        /** The palettes of the frame at [sourceTime] for each Quantization of [effects], as its choices make them ([EffectPaletteAnalysis.displayPalette]). */
        fun inputs(assets: EffectAssets, effects: List<VideoEffectSettings>?, sourceTime: Double, placing: ((GeometrySize) -> app.takes.core.Affine)? = null): Map<String, EffectFrameInputs> {
            val active = effects.orEmpty().filter { it.enabled && sourceTime >= it.start && sourceTime < it.end }
            val palettes = active.filter { it.kind == VideoEffectID.Quantization }.mapNotNull { effect ->
                EffectPaletteAnalysis.displayPalette(effect, sourceTime, assets.analysed(effect, sourceTime))?.let { effect.id to EffectFrameInputs(palette = it) }
            }
            // The subject followed, laid on the frame the effect draws on: [placing] takes the upright recording of a size
            // in pixels to that frame, 0…1. VideoEffectAssets.swift:481-484.
            val targets = if (placing == null) emptyList() else active.filter { app.takes.core.EffectSubjectAnalysis.follows(it, sourceTime) }.mapNotNull { effect ->
                val subject = assets.subject(effect, sourceTime) ?: return@mapNotNull null
                val point = app.takes.core.Point(subject.center.x * subject.upright.width, subject.center.y * subject.upright.height).applying(placing(subject.upright))
                effect.id to EffectFrameInputs(target = app.takes.core.Point(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0)))
            }
            return (palettes + targets).toMap()
        }
    }
}

/** Where a followed subject's centre is, 0…1 on the recording stood [upright], of that size in pixels. */
class EffectSubject(val center: app.takes.core.Point, val upright: GeometrySize)

/**
 * A person mask as it was stored ([PersonMaskChunks.Mask], in the decoded frame's coordinates), with what lays it
 * on the movie's frame: the clockwise [rotation] that stands the recording upright and its [upright] size.
 */
class PersonFrame(val mask: PersonMaskChunks.Mask, val rotation: Int, val upright: GeometrySize)

/**
 * The assets of [clip] from its recording [source] and the project [folder]
 * where picked pictures are kept. With [wait] a palette or a person mask not
 * yet analysed is analysed there and then, as the export needs; without, in
 * the background, and [ready] called once it is, as the preview needs. The
 * person masks need the recording's [media] and a [context].
 */
class SourceAssets(
    private val source: File?,
    private val folder: File?,
    private val clip: TimelineClip,
    private val wait: Boolean = true,
    private val context: Context? = null,
    private val media: SourceMedia? = null,
    private val ready: () -> Unit = {},
) : EffectAssets {
    private val people: PersonFrames? by lazy {
        val file = source ?: return@lazy null
        val recording = media ?: return@lazy null
        val app = context?.applicationContext ?: return@lazy null
        if (!PersonMasks.available()) null else PersonFrames(app, recording, file, CapturedAnalyses.raw(file, clip))
    }

    /** The analysis saved with the take that the frame at [sourceTime] reads (VideoEffectAssets.swift:375-379). */
    private fun saved(sourceTime: Double): CapturedAnalyses.Opened? {
        val file = source ?: return null
        val effect = CapturedEffectAnalysis.source(clip.videoEffects, sourceTime) ?: return null
        return CapturedAnalyses.of(file, effect)
    }

    /**
     * The masks the recorder's preview drew with, saved with the take, while there are some there, the smoothed ones
     * where they were kept; else those the editor finds (VideoEffectAssets.swift:402-433).
     */
    override fun person(sourceTime: Double, smoothed: Boolean): PersonFrame? {
        val size = upright
        val saved = saved(sourceTime)
        if (saved != null && size != null && (if (smoothed) saved.analysis.hasMotionAwareAutoBackground else saved.analysis.masks.isNotEmpty())) {
            // Upright, as the preview found them: laid on the frame without a turn.
            saved.mask(sourceTime, smoothed)?.let { return PersonFrame(it, 0, size) }
        }
        return people?.frame(sourceTime, smoothed, wait, ready)
    }

    override fun quality(effect: VideoEffectSettings, sourceTime: Double): app.takes.core.VideoEffectQuality =
        saved(sourceTime)?.analysis?.quality(effect.analysisID, sourceTime) ?: app.takes.core.VideoEffectQuality.Full

    override fun analysed(effect: VideoEffectSettings, sourceTime: Double): EffectPalette? {
        val file = source ?: return null
        // What the recorder's preview showed, while nothing it was drawn from has changed. MediaRenderer.swift:413-419.
        captured(file, effect)?.let { return it.palette(sourceTime) }
        val track = if (wait) ScenePalettes.track(file, clip, effect) else ScenePalettes.cached(file, clip, effect, ready)
        return track?.palette(sourceTime)
    }

    private fun captured(file: File, effect: VideoEffectSettings): EffectPaletteTrack? {
        val name = effect.analysisFile ?: return null
        val plain = clip.color == null && clip.crop == null && clip.mirror == null && clip.stabilization == null
        // The recipe beside the take, within the length its clip was given it for.
        val length = clip.videoEffects.orEmpty().filter { it.analysisFile == name }.maxOfOrNull { it.end } ?: return null
        val recipe = CaptureEffectsFile.read(file, length)
        if (!CapturedEffectAnalysis.palettesHold(effect, clip.videoEffects.orEmpty(), recipe, plain)) return null
        return CapturedAnalyses.of(file, effect)?.analysis?.palettes?.get(effect.analysisID)?.track?.takeIf { it.frames.isNotEmpty() }
    }

    private val upright by lazy { source?.let(::displaySize)?.let { GeometrySize(it.first.toDouble(), it.second.toDouble()) } }

    override fun subject(effect: VideoEffectSettings, sourceTime: Double): EffectSubject? {
        val file = source ?: return null
        val recording = media ?: return null
        val app = context?.applicationContext ?: return null
        val size = upright ?: return null
        val track = if (wait) SubjectTracks.track(app, file, recording, clip, effect) else SubjectTracks.cached(app, file, recording, clip, effect, ready)
        val sample = track?.sample(sourceTime) ?: return null
        return EffectSubject(sample.center, size)
    }

    override fun picture(effect: VideoEffectSettings): Bitmap? {
        val name = (effect.value("media", 0.0) as? EffectValue.Media)?.file ?: return null
        val file = File(folder ?: return null, name)
        return EffectPictures.get(file)
    }

    override fun movie(effect: VideoEffectSettings): File? = EffectPictures.movie(folder, effect)

    override val waitsForMovies: Boolean get() = wait

    override fun plate(effect: VideoEffectSettings): CleanPlate? {
        val name = (effect.value("cleanPlate", 0.0) as? EffectValue.Media)?.file ?: return null
        val size = upright ?: return null
        return CleanPlates.plate(File(folder ?: return null, name), size, clip.color, folder, wait, ready)
    }
}

/**
 * The person masks of one recording, read by source time from the chunks [PersonMasks] makes in the
 * recording's project folder. A chunk not made yet is made then and there ([wait]), or in the background
 * with [ready] called once it is there.
 */
internal class PersonFrames(private val context: Context, private val media: SourceMedia, private val file: File, private val captured: PersonMasks.Captured? = null) {
    private val reader = PersonMasks.reader(context, media, file.parentFile ?: File("."), captured?.name)
    private val rotation = PersonMasks.rotation(file)
    private val upright = displaySize(file)?.let { GeometrySize(it.first.toDouble(), it.second.toDouble()) }

    fun frame(sourceTime: Double, smoothed: Boolean, wait: Boolean, ready: () -> Unit): PersonFrame? {
        val size = upright ?: return null
        val mask = synchronized(reader) { reader.mask(sourceTime, smoothed) } ?: run {
            val chunk = PersonMaskChunks.chunk(sourceTime)
            if (wait) {
                analyse(chunk)
                synchronized(reader) { reader.mask(sourceTime, smoothed) }
            } else {
                if (asked.add("${reader.directory.path}#$chunk")) {
                    background.execute {
                        analyse(chunk)
                        ready()
                    }
                }
                null
            }
        } ?: return null
        return PersonFrame(mask, rotation, size)
    }

    private fun analyse(chunk: Int) {
        val span = PersonMaskChunks.span(chunk, media.duration)
        try {
            PersonMasks.analyse(context, media, file.parentFile ?: return, from = span.start, to = span.start, captured = captured)
        } catch (problem: Exception) {
            if (problem is java.util.concurrent.CancellationException) throw problem
            android.util.Log.w("Takes", "person masks of ${media.filename} at ${span.start} s", problem)
        }
    }

    private companion object {
        /** One analysis at a time for the preview, as the original's single segmentation queue. */
        val background = Executors.newSingleThreadExecutor { Thread(it, "Person masks").apply { isDaemon = true } }
        val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/** Pictures picked for backgrounds, decoded once and kept while they are used, at most 2048 pixels on their longer side. */
internal object EffectPictures {
    private val kept = ConcurrentHashMap<String, Bitmap>()

    /** The movie picked for [effect]'s Video background in [folder]; null without one. */
    fun movie(folder: File?, effect: VideoEffectSettings): File? {
        val name = (effect.value("media", 0.0) as? EffectValue.Media)?.file ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in app.takes.core.EffectParameterKind.VIDEO_EXTENSIONS) return null
        return File(folder ?: return null, name).takeIf { it.isFile }
    }

    fun get(file: File): Bitmap? {
        if (!file.isFile) return null
        val key = "${file.path}:${file.lastModified()}"
        kept[key]?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        // Upright, as the phone shows it.
        val turn = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val bitmap = if (turn == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(turn) }, true)
        if (kept.size > 4) kept.clear()
        kept[key] = bitmap
        return bitmap
    }
}

/**
 * The palettes Quantization takes from the scene, as the original's editor
 * analyses them (`VideoEffectAssets.swift:183-209`): the clip's frames every
 * 0.2 s and at each key of its colours or lock, each sampled on a 64 × 36 grid
 * of the frame at 256 pixels on its longer side, the palette of each analysed
 * from the one before ([EffectPaletteAnalysis.track]). Worked out on the CPU
 * once for a source, an effect and the settings the analysis reads, and kept
 * for the preview and the export.
 *
 * The original samples the frame as the effects before Quantization leave
 * it; here it is the recording's own frame, before its colour, crop and those
 * effects.
 */
/**
 * The analyses saved with takes by the recorder ([CapturedEffectAnalysis]), each read once and its masks read from
 * its data file as they are asked for, as the original's renderer keeps a reader for each (MediaRenderer.swift:252-260).
 */
object CapturedAnalyses {
    /** One analysis and its data file; its masks are read one at a time. */
    class Opened(val name: String, val analysis: CapturedEffectAnalysis, private val data: File) {
        private var masks: CapturedEffectAnalysis.Masks? = null
        private var failed = false

        /** The raw mask shown at [time], or with [auto] Auto Background's smoothed one; null without one, or where it cannot be read. */
        @Synchronized
        fun mask(time: Double, auto: Boolean = false): PersonMaskChunks.Mask? {
            if (failed) return null
            return try {
                (masks ?: CapturedEffectAnalysis.Masks(analysis, data).also { masks = it }).mask(time, auto)
            } catch (problem: Exception) {
                android.util.Log.w("CapturedAnalyses", "masks of $name", problem)
                failed = true
                null
            }
        }

        @Synchronized
        fun close() {
            masks?.close()
            masks = null
        }
    }

    private val read = ConcurrentHashMap<String, java.util.Optional<Opened>>()

    /** The analysis [effect] names, beside its take [movie]; null when it has none or it cannot be trusted. */
    fun of(movie: File, effect: VideoEffectSettings): Opened? {
        val name = effect.analysisFile ?: return null
        val folder = movie.parentFile ?: return null
        val file = File(folder, name)
        val data = File(folder, effect.analysisDataFile ?: return null)
        val key = file.path + "|" + file.lastModified() + "|" + data.length()
        return read.getOrPut(key) {
            if (read.size > 16) {
                read.values.forEach { it.orElse(null)?.close() }
                read.clear()
            }
            java.util.Optional.ofNullable(CapturedEffectAnalysis.read(file, data)?.let { Opened(name, it, data) })
        }.orElse(null)
    }

    /**
     * The raw masks saved with the take of [clip], which the editor's analysis reads in place of its model's when Auto
     * Background's smoothed masks were not kept with them (MediaRenderer.swift:378-381); null otherwise.
     */
    fun raw(movie: File, clip: TimelineClip): PersonMasks.Captured? {
        val effect = clip.videoEffects.orEmpty().firstOrNull { it.enabled && it.analysisFile != null } ?: return null
        val opened = of(movie, effect)?.takeIf { it.analysis.masks.isNotEmpty() && !it.analysis.hasMotionAwareAutoBackground } ?: return null
        return PersonMasks.Captured(opened.name) { time -> opened.mask(time) }
    }

    /**
     * The seconds of [span] whose masks the effects of [clip] need analysed, the saved ones not serving there
     * ([CapturedEffectAnalysis.unserved]); null when they serve it all.
     */
    fun unserved(movie: File, clip: TimelineClip, span: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double>? {
        val effect = clip.videoEffects.orEmpty().firstOrNull { it.enabled && it.analysisFile != null } ?: return span
        val auto = clip.videoEffects.orEmpty().any { it.enabled && it.kind == VideoEffectID.AutoBackground }
        return CapturedEffectAnalysis.unserved(of(movie, effect)?.analysis, auto, span.start, span.endInclusive)
    }
}

object ScenePalettes {
    private val tracks = ConcurrentHashMap<String, EffectPaletteTrack>()
    private val working = ConcurrentHashMap.newKeySet<String>()
    private val background = Executors.newSingleThreadExecutor { Thread(it, "Scene palettes").apply { isDaemon = true } }

    private fun key(file: File, clip: TimelineClip, effect: VideoEffectSettings): String {
        val read = effect.parameters.filter { it.id == "colors" || it.id == "lockPalette" }
        return listOf(file.path, file.lastModified(), clip.start, clip.end, effect.id, effect.start, effect.end, read).joinToString("|")
    }

    /** The track of [effect] on [clip], analysed now when it is not kept: seconds of work, never on the main thread. */
    fun track(file: File, clip: TimelineClip, effect: VideoEffectSettings): EffectPaletteTrack {
        val key = key(file, clip, effect)
        tracks[key]?.let { return it }
        val made = analyse(file, clip, effect)
        if (tracks.size > 32) tracks.clear()
        tracks[key] = made
        return made
    }

    /** The track when kept; else null, with the analysis started in the background and [ready] called when it is done. */
    fun cached(file: File, clip: TimelineClip, effect: VideoEffectSettings, ready: () -> Unit): EffectPaletteTrack? {
        val key = key(file, clip, effect)
        tracks[key]?.let { return it }
        if (working.add(key)) background.execute {
            try {
                track(file, clip, effect)
            } catch (_: Exception) {
            } finally {
                working.remove(key)
            }
            ready()
        }
        return null
    }

    /**
     * A palette of [count] colours from the one frame at source second
     * [time]: what the original's "Save palette from this frame" and
     * "Re-sample this frame" keep (`VideoEffectsEditor.swift:883-907`).
     */
    fun sample(file: File, time: Double, count: Int): EffectPalette? = Frames(file).use { frames ->
        frames.samples(time)?.let { EffectPaletteAnalysis.analyze(it, count, seed = VideoEffectTime.frameIndex(time, 30.0).toULong()) }
    }

    private fun analyse(file: File, clip: TimelineClip, effect: VideoEffectSettings): EffectPaletteTrack = Frames(file).use { frames ->
        val start = maxOf(effect.start, clip.start)
        val end = minOf(effect.end, clip.end)
        val keys = effect.parameters.filter { it.id == "colors" || it.id == "lockPalette" }.flatMap { p -> p.keyframes.map { it.time } }
        val rate = frames.rate
        val frameTimes = generateSequence(kotlin.math.floor(start * rate) / rate) { it + 1 / rate }.takeWhile { it < end }.toList()
        val times = EffectPaletteAnalysis.analysisTimes(start, end, keys, frameTimes)
        val empty = listOf(EffectVector.ZERO)
        EffectPaletteAnalysis.track(effect, times) { time -> frames.samples(time) ?: empty }
    }

    /** The frames of a recording, read one at a time. */
    private class Frames(file: File) : AutoCloseable {
        private val retriever = MediaMetadataRetriever().apply { setDataSource(file.path) }

        val rate: Double = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.takeIf { it in 1.0..240.0 }
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()?.let { frames ->
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { frames / (it / 1000) }
            }?.takeIf { it in 1.0..240.0 } ?: 30.0

        /** The analysis's samples of the frame shown at [time], linear. */
        fun samples(time: Double): List<EffectVector>? {
            val us = (time * 1e6).toLong()
            val frame = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 256, 256)
            else retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)) ?: return null
            val scale = 256.0 / maxOf(frame.width, frame.height)
            val small = if (scale < 1) Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * scale).toInt()), maxOf(1, (frame.height * scale).toInt()), true) else frame
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val image = EffectImage.of(small.width, small.height) { x, y ->
                val p = pixels[y * small.width + x]
                EffectVector(EffectColorMath.linear((p shr 16 and 0xff) / 255.0), EffectColorMath.linear((p shr 8 and 0xff) / 255.0), EffectColorMath.linear((p and 0xff) / 255.0))
            }
            if (small !== frame) small.recycle()
            frame.recycle()
            return EffectPaletteAnalysis.samples(image)
        }

        override fun close() = retriever.release()
    }
}
