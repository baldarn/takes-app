package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import app.takes.core.Affine
import app.takes.core.ColorSettings
import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.EditorTool
import app.takes.core.FilmstripPlan
import app.takes.core.GradeMotion
import app.takes.core.LUTInput
import app.takes.core.PictureFraming
import app.takes.core.Size
import app.takes.core.SourceMedia
import app.takes.core.SubtitleCue
import app.takes.core.SubtitleSettings
import app.takes.core.TimelineClip
import app.takes.core.TitleMotionRanges
import app.takes.core.ToolRegions
import app.takes.core.TrackSubtitles
import app.takes.core.TrackTimeline
import app.takes.core.TransitionLayout
import app.takes.core.VideoTimelineItem
import app.takes.core.grade
import app.takes.core.gradeTime
import app.takes.core.matrixValues
import app.takes.core.newID
import app.takes.core.resolvingEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The timeline's thumbnails as one moment of the timeline's pictures: what each one draws ([plan]), the generation
 * that names the pictures as they are ([generation]), each picture by id, and where its tools' regions begin and end
 * ([edges]), at which its tiles are cut (YouRec/EditorTimeline.swift:1290-1304).
 */
@Immutable
internal class FilmstripView(
    val plan: FilmstripPlan,
    val generation: String,
    val items: Map<String, VideoTimelineItem>,
    val edges: Map<String, List<Double>>,
    val frames: FilmstripFrames,
)

/**
 * What a thumbnail needs besides the edit: the recordings' files, upright sizes, media and log profiles, the movie's
 * frame and its frame rate.
 */
internal data class FilmstripSetting(
    val files: Map<String, File>,
    val sizes: Map<String, Size>,
    val media: Map<String, SourceMedia>,
    val logs: Map<String, LUTInput>,
    val canvas: Pair<Int, Int>,
    val rate: Double,
)

/**
 * The timeline's thumbnails of the edited picture, as the original's since 57e5d91 (YouRec/Filmstrip.swift,
 * YouRec/EditorTimeline.swift:1270-1400, 1447-1590). The original asks its image generator for the frame of the
 * editor's composition at each tile's time, at most 256 pixels on a side, and keeps it by what it draws
 * ([FilmstripPlan]) in a cache of 192 pictures and 24 MB. Here a thumbnail is drawn by the preview's own pieces, one
 * picture at a time on a thread of its own: the source frame at the tile's source second, fitted to the movie's frame
 * at 256 pixels on its longer side, through the preview's night fix, grade ([ColorLuts.Preview], with a log clip's
 * local highlight recovery worked out on this frame) and video effects ([EffectPreview]) on Android 13 and later,
 * framed by the clip's crop and flip in force at that moment ([PictureFraming.frame]), with the clip's pictures and
 * titles ([TitlePainter]) and the captions ([SubtitleLayout]) over it. Only the tiles near the screen ask for one, and
 * a tile whose key is kept is not drawn again.
 */
internal class FilmstripFrames(context: Context, private val folder: File) {
    private val context = context.applicationContext
    private var last: TrackTimeline? = null
    private var view: FilmstripView? = null

    @Volatile var setting: FilmstripSetting? = null
        set(value) {
            // The movie's frame, or a log recording's profile found after its pictures were drawn, changes every picture.
            if (field?.canvas != value?.canvas || field?.logs != value?.logs) cache.evictAll()
            field = value
        }

    /** The thumbnails of [edit] drawn as [timeline], reusing what the previous moment worked out. */
    fun view(edit: EditState, timeline: TrackTimeline): FilmstripView {
        val previous = view
        val generation = if (previous == null || FilmstripPlan.regenerates(last, timeline)) newID() else previous.generation
        val plan = FilmstripPlan(timeline, previous?.plan)
        val edges = ToolRegions.of(edit).filter { it.tool != EditorTool.Placement }.groupBy { it.clipID }
            .mapValues { (_, regions) -> regions.flatMap { listOf(it.start, it.end) }.toSortedSet().toList() }
        last = timeline
        captions = timeline.subtitles?.let { settings -> TrackSubtitles.cues(timeline).takeIf { it.isNotEmpty() }?.let { settings to it } }
        return FilmstripView(plan, generation, timeline.video.associateBy { it.id }, edges, this).also { view = it }
    }

    @Volatile private var captions: Pair<SubtitleSettings, List<SubtitleCue>>? = null

    // The original's cache of 24 MB (YouRec/EditorTimeline.swift:1278-1283), which at 256 pixels holds fewer than its 192 pictures.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    // The picture last drawn for the same footage, look and caption, whatever the rest of the transcript, while the
    // captions are fitted again (YouRec/EditorTimeline.swift:1578-1580).
    private val held = LruCache<String, Bitmap>(64)

    /** What a tile of picture [lane] keyed by [sample] is kept by: a picture not its own lane's alone is drawn as that lane's. */
    private fun key(sample: FilmstripPlan.Sample, lane: String): String = if (sample.sourceTime == null) sample.key + "#" + lane else sample.key

    /** The picture of [sample] already drawn, else one that can stand in for it until it is. */
    fun shown(sample: FilmstripPlan.Sample, lane: String): Bitmap? =
        cache.get(key(sample, lane)) ?: sample.captionPlaceholder?.let { held.get(it) }

    /** Whether [sample] has its own picture drawn. */
    fun drawn(sample: FilmstripPlan.Sample, lane: String): Boolean = cache.get(key(sample, lane)) != null

    /** Draws the thumbnail of [sample] on picture [item] at timeline time [time]; null when it cannot be read. */
    suspend fun picture(sample: FilmstripPlan.Sample, item: VideoTimelineItem, time: EditTime): Bitmap? {
        val key = key(sample, item.id)
        cache.get(key)?.let { return it }
        val setting = setting ?: return null
        val clip = item.content
        val file = setting.files[clip.sourceID] ?: return null
        val offset = (time - item.start).seconds.coerceIn(0.0, clip.duration)
        val sourceTime = sample.sourceTime ?: clip.sourceTime(offset)
        val frame = FilmstripDecoder.frame(file, sourceTime, LONG_SIDE) ?: return null
        val drawn = withContext(drawing) {
            runCatching { draw(frame, clip, offset, sourceTime, time.seconds, setting) }.getOrNull()
        } ?: return null
        cache.put(key, drawn)
        sample.captionPlaceholder?.let { held.put(it, drawn) }
        return drawn
    }

    private val painter by lazy { TitlePainter(context.assets, OverlayPictures(folder, OverlayPictures.PREVIEW_PIXELS)) }
    private var layout: Triple<Pair<SubtitleSettings, List<SubtitleCue>>, Pair<Int, Int>, SubtitleLayout>? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** The picture: [frame] of [clip] [offset] seconds into it, timeline second [at], at most [LONG_SIDE] pixels on a side. */
    private fun draw(frame: Bitmap, clip: TimelineClip, offset: Double, sourceTime: Double, at: Double, setting: FilmstripSetting): Bitmap? {
        val (movieWidth, movieHeight) = setting.canvas
        val scale = LONG_SIDE.toDouble() / maxOf(movieWidth, movieHeight)
        val width = maxOf(1, (movieWidth * scale).roundToInt())
        val height = maxOf(1, (movieHeight * scale).roundToInt())
        val canvas = Size(width.toDouble(), height.toDouble())
        val source = setting.sizes[clip.sourceID] ?: Size(frame.width.toDouble(), frame.height.toDouble())
        // The frame laid on the movie's frame as the deck shows it, whole and fitted, then framed as the clip is there.
        val fit = Affine.scale(source.width / frame.width, source.height / frame.height).concatenating(PictureFraming.fit(source, canvas))
        val framing = PictureFraming.frame(clip, offset, source, canvas)
        val framed = PictureFraming.fit(source, canvas).inverted().concatenating(framing.transform)
        val titles = if (clip.texts.isNullOrEmpty() && clip.images.isNullOrEmpty()) emptyList()
            else listOf(clip).let { alone -> TitleMotionRanges(alone).shown(alone, offset) { _: TransitionLayout.Join -> setting.rate } }
        val captioned = captions?.let { shown ->
            val kept = layout?.takeIf { it.first == shown && it.second == setting.canvas }
                ?: Triple(shown, setting.canvas, SubtitleLayout(shown.second, shown.first, movieWidth, movieHeight, context.assets)).also { layout = it }
            kept.third to shown.first
        }
        fun finish(target: Canvas, picture: (Canvas) -> Unit) {
            target.drawColor(Color.BLACK)
            target.withMatrix(Matrix().apply { setValues(framed.matrixValues()) }) { picture(this) }
            painter.draw(target, width.toFloat(), height.toFloat(), titles)
            captioned?.let { (drawing, settings) -> drawing.draw(target, width.toFloat(), height.toFloat(), at, settings) }
        }
        val fitted = Matrix().apply { setValues(fit.matrixValues()) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val effect = effect(frame, fitted, clip, offset, sourceTime, width, height, setting)
            if (effect != null) return hardware(width, height) { target ->
                val picture = RenderNode("filmstrip picture").apply {
                    setPosition(0, 0, width, height)
                    setRenderEffect(effect)
                }
                picture.beginRecording().also { it.drawBitmap(frame, fitted, paint) }
                picture.endRecording()
                finish(target) { it.drawRenderNode(picture) }
            }
        }
        // Without effects, or before Android 13, where the preview shows no color either, drawn on the processor.
        val bitmap = createBitmap(width, height)
        finish(Canvas(bitmap)) { it.drawBitmap(frame, fitted, paint) }
        return bitmap
    }

    private val grades = HashMap<Pair<ColorSettings, LUTInput?>, ColorLuts.Preview>()
    private val effects by lazy { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) EffectPreview() else null }
    private val assets = object : LinkedHashMap<TimelineClip, SourceAssets>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TimelineClip, SourceAssets>): Boolean = size > 8
    }

    /**
     * The night fix, grade and video effects the preview's deck draws over the clip at that moment, for a deck
     * [width] by [height] pixels (EditorScreen.kt `effects`); null for none.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun effect(frame: Bitmap, fitted: Matrix, clip: TimelineClip, offset: Double, sourceTime: Double, width: Int, height: Int, setting: FilmstripSetting): RenderEffect? {
        val log = setting.logs[clip.sourceID]
        val graded = clip.grade(clip.gradeTime(offset))
        val grade = if (graded == null && log == null) null else {
            if (grades.size > 16) grades.clear()
            val preview = grades.getOrPut((graded ?: ColorSettings()) to log) { ColorLuts.Preview(graded ?: ColorSettings(), folder, log) }
            // A log clip's local highlight recovery reads the frame itself.
            preview.recovery?.let { recovery ->
                val deck = createBitmap(width, height)
                Canvas(deck).drawBitmap(frame, fitted, paint)
                recovery.read(deck, RectF(0f, 0f, width.toFloat(), height.toFloat()), wait = true)
            }
            if (!preview.moves) preview.effect() else preview.effect(GradeMotion.fraction(clip, offset) ?: 0.0)
        }
        val shown = if (clip.effectWindows == null) clip.videoEffects else clip.resolvingEffects(clip.gradeTime(offset)).videoEffects
        var restored: RenderEffect? = null
        var shaded: RenderEffect? = null
        val preview = effects
        if (!shown.isNullOrEmpty() && preview != null) {
            val sources = assets.getOrPut(clip) { SourceAssets(setting.files[clip.sourceID], folder, clip, wait = false, context = context, media = setting.media[clip.sourceID]) }
            restored = preview.nightFix(shown, sourceTime)
            val placing = { upright: Size -> PictureFraming.fit(upright, Size(width.toDouble(), height.toDouble())).concatenating(Affine.scale(1.0 / width, 1.0 / height)) }
            val passes = EffectShader.passes(shown, sourceTime, setting.rate, EffectAssets.inputs(sources, shown, sourceTime, placing), clip.frozenFrame?.let { it.offset + offset })
            if (passes.isNotEmpty()) shaded = preview.effect(
                passes, width.toFloat(), height.toFloat(),
                // A Chroma Key's or an Auto Background's picture behind the subject; a movie there is not drawn.
                picture = { id -> shown.firstOrNull { it.id == id }?.takeIf { it.choice("background", sourceTime) != "Video" }?.let(sources::picture) },
                person = { smoothed -> sources.person(sourceTime, smoothed) },
                plate = { id -> shown.firstOrNull { it.id == id }?.let(sources::plate) },
            )
        }
        // Night fix on the raw picture, then its color, then the other effects.
        val colored = if (restored != null && grade != null) RenderEffect.createChainEffect(grade, restored) else grade ?: restored
        return if (shaded != null && colored != null) RenderEffect.createChainEffect(shaded, colored) else shaded ?: colored
    }

    private var renderer: HardwareRenderer? = null
    private var reader: ImageReader? = null

    /** Draws [content] on the GPU, so that the preview's effects apply, and reads the picture back. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun hardware(width: Int, height: Int, content: (Canvas) -> Unit): Bitmap? {
        val target = reader?.takeIf { it.width == width && it.height == height } ?: run {
            reader?.close()
            renderer?.destroy()
            val made = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            renderer = HardwareRenderer().apply { setSurface(made.surface) }
            reader = made
            made
        }
        val root = RenderNode("filmstrip").apply { setPosition(0, 0, width, height) }
        content(root.beginRecording())
        root.endRecording()
        val gpu = renderer ?: return null
        gpu.setContentRoot(root)
        gpu.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = target.acquireLatestImage() ?: return null
        return try {
            val buffer = image.hardwareBuffer ?: return null
            buffer.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))?.copy(Bitmap.Config.ARGB_8888, false) }
        } finally {
            image.close()
            root.discardDisplayList()
        }
    }

    /** Lets go of the drawing surface; the pictures drawn go with the object. */
    fun close() {
        // On the thread that draws, after whatever it is drawing.
        drawing.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) renderer?.destroy()
            reader?.close()
            renderer = null
            reader = null
        })
    }

    private companion object {
        /** The longer side of a thumbnail, as the original's image generator's largest size (YouRec/EditorTimeline.swift:1335). */
        const val LONG_SIDE = 256

        /** One picture drawn at a time, for every timeline: the drawing surface and the preview's shaders are not shared. */
        val drawing = Executors.newSingleThreadExecutor { Thread(it, "Filmstrip").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
}

/**
 * The picture of the tile of [item] at timeline time [time] (YouRec/EditorTimeline.swift:1568-1590): the edited
 * picture once drawn; meanwhile the one drawn for the same caption fitted against another transcript, or for a frame
 * of the footage as recorded the footage's own thumbnail at [sourceTime] of [file], else nothing; the footage's
 * thumbnail too when the edited picture cannot be drawn.
 */
@Composable
internal fun editedThumbnail(film: FilmstripView, item: VideoTimelineItem, time: EditTime, file: File, sourceTime: Double): State<Bitmap?> {
    val sample = remember(film, item.id, time) { film.plan.sample(item.id, time, film.generation) }
    val frames = film.frames
    return produceState(frames.shown(sample, item.id), sample.key, item.id) {
        value = frames.shown(sample, item.id) ?: if (sample.plain) Thumbnails.frame(file, sourceTime) else null
        if (frames.drawn(sample, item.id)) return@produceState
        value = frames.picture(sample, item, time) ?: value ?: Thumbnails.frame(file, sourceTime)
    }
}

/** The source frames of the thumbnails, read exactly at their second, two at a time, with the readers kept open. */
@OptIn(ExperimentalCoroutinesApi::class)
private object FilmstripDecoder {
    private val decoding = Dispatchers.IO.limitedParallelism(2)
    private val open = ArrayList<Pair<File, MediaMetadataRetriever>>()

    private fun take(file: File): MediaMetadataRetriever? {
        synchronized(open) {
            val index = open.indexOfFirst { it.first == file }
            if (index >= 0) return open.removeAt(index).second
        }
        return runCatching { MediaMetadataRetriever().apply { setDataSource(file.path) } }.getOrNull()
    }

    private fun give(file: File, retriever: MediaMetadataRetriever) {
        val dropped = synchronized(open) {
            open += file to retriever
            if (open.size > 4) open.removeAt(0).second else null
        }
        dropped?.let { runCatching { it.release() } }
    }

    suspend fun frame(file: File, time: Double, longest: Int): Bitmap? = withContext(decoding) {
        val retriever = take(file) ?: return@withContext null
        try {
            val micros = (time * 1e6).toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, longest, longest)
            else retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        } finally {
            give(file, retriever)
        }
    }
}
