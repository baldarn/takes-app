package app.takes

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import app.takes.core.SourceMedia
import app.takes.core.StabilizationAnalysis
import app.takes.core.StabilizationLevel
import app.takes.core.StabilizationMeasure
import app.takes.core.StabilizationPath
import app.takes.core.StabilizationPlan
import app.takes.core.TimelineClip
import app.takes.core.stabilizationEnd
import app.takes.core.stabilizationStart
import app.takes.stabilization.MotionEstimator
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * The stabilisation of a clip, as the original's `VideoStabilizer` (YouRec/VideoStabilizer.swift:6-206): the camera
 * path of the stretch of its recording the clip refers to, measured once and kept in the project's
 * `Cache/Stabilization/`, and the plan of corrections a level makes of it.
 *
 * The frames are decoded in order with MediaCodec and given to the original's motion estimator, compiled under the
 * NDK ([MotionEstimator]), at its analysis size; the path is chained in core ([StabilizationMeasure]).
 *
 * The cached path is named by the recording's name, size and modification time where the original hashes the
 * file's contents (SourceMediaCache.swift:126-150): a recording in a project is never written again, and reading a
 * whole 4K take to hash it would cost about as long as measuring it.
 */
object Stabilizer {
    /** Cached paths at most this many bytes together, the oldest let go first (:73-81). */
    private const val CACHE_BYTES = 64_000_000L

    private val claims = ConcurrentHashMap<String, Any>()

    private class Lru<V>(private val count: Int, private val samples: (V) -> Int) {
        private val map = LinkedHashMap<String, V>(16, 0.75f, true)

        @Synchronized fun get(key: String): V? = map[key]

        // At most [count] entries and 360 000 samples together, but always the newest (:20-25, :112-115).
        @Synchronized fun put(key: String, value: V) {
            map[key] = value
            while (map.size > 1 && (map.size > count || map.values.sumOf(samples) > 360_000)) map.remove(map.keys.first())
        }
    }

    private val analyses = Lru<StabilizationAnalysis>(3) { it.samples.size }
    private val plans = Lru<StabilizationPlan>(16) { it.samples.size }

    /** The folder of the cached paths of the project in [folder] (:36). */
    fun directory(folder: File): File = File(File(folder, "Cache"), "Stabilization")

    /** What names a recording's file to the cache. */
    private fun fingerprint(file: File): String = "${file.name}|${file.length()}|${file.lastModified()}"

    /** The cached path of [file] from source second [start] to [end]. */
    fun cacheFile(folder: File, file: File, start: Double, end: Double): File =
        File(directory(folder), StabilizationAnalysis.key(fingerprint(file), start, end) + ".json")

    /**
     * The camera path of [source] (in the project folder [folder]) from source second [start] to [end]: from memory,
     * from the cache, or measured and kept. With [generate] false nothing is measured and null is given when it is not
     * kept. Blocks: call it off the main thread. [progress] goes from 0 to 1; [cancel] is asked at every frame, and
     * when it says so the work stops with a [CancellationException].
     */
    fun analysis(
        source: SourceMedia, folder: File, start: Double, end: Double, generate: Boolean = true,
        progress: (Double) -> Unit = {}, cancel: () -> Boolean = { false },
    ): StabilizationAnalysis? {
        val file = File(folder, source.filename)
        val cached = cacheFile(folder, file, start, end)
        // Two measurements of the same stretch (the preview's and the export's) make it once.
        synchronized(claims.computeIfAbsent(cached.path) { Any() }) {
            analyses.get(cached.path)?.let { progress(1.0); return it }
            if (cached.isFile) {
                val read = StabilizationAnalysis.decode(cached.readBytes())
                if (read != null && read.valid && kotlin.math.abs(read.duration - (end - start)) < 0.00001) {
                    analyses.put(cached.path, read)
                    progress(1.0)
                    return read
                }
            }
            if (!generate) return null
            val measured = measure(file, start, end - start, progress, cancel)
            check(measured.valid) { "no measurable frames in ${source.filename}" }
            val directory = directory(folder)
            directory.mkdirs()
            val writing = File(directory, ".${cached.name}")
            writing.writeBytes(measured.encoded())
            if (!writing.renameTo(cached)) { writing.delete(); throw IllegalStateException("cannot keep ${cached.name}") }
            trim(directory, cached)
            analyses.put(cached.path, measured)
            progress(1.0)
            return measured
        }
    }

    /** Lets the oldest cached paths go past [CACHE_BYTES], never [kept] (:71-81). */
    private fun trim(directory: File, kept: File) {
        val files = directory.listFiles { it: File -> it.isFile && it.extension == "json" && !it.name.startsWith(".") }.orEmpty().sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (old in files) {
            if (bytes <= CACHE_BYTES) break
            if (old == kept) continue
            val size = old.length()
            if (old.delete()) bytes -= size
        }
    }

    /**
     * The corrections of [clip] at its level, from the path of its whole reference range (:86-117); null for a clip
     * not stabilised. Blocks, as [analysis].
     */
    fun plan(
        source: SourceMedia, clip: TimelineClip, folder: File, generate: Boolean = true,
        progress: (Double) -> Unit = {}, cancel: () -> Boolean = { false },
    ): StabilizationPlan? {
        val settings = clip.stabilization ?: return null
        if (settings.level == StabilizationLevel.Off) return null
        val start = clip.stabilizationStart
        val end = clip.stabilizationEnd
        val key = cacheFile(folder, File(folder, source.filename), start, end).path + "|" + settings.level.id
        plans.get(key)?.let { progress(1.0); return it }
        val measured = analysis(source, folder, start, end, generate, progress, cancel) ?: return null
        val made = StabilizationPath.make(measured, settings.level) { if (cancel()) throw CancellationException() }
        plans.put(key, made)
        return made
    }

    /**
     * The corrections of [clip] at [level] measured over its source seconds [from]…[to] alone, a window of it, timed
     * from the clip's reference start as the whole plan is: what the tool previews before the whole path is measured
     * (:98-110). Its crop is the window's, which the whole clip's may exceed.
     */
    fun samplePlan(
        source: SourceMedia, clip: TimelineClip, level: StabilizationLevel, from: Double, to: Double, folder: File,
        progress: (Double) -> Unit = {}, cancel: () -> Boolean = { false },
    ): StabilizationPlan? {
        val measured = analysis(source, folder, from, to, true, progress, cancel) ?: return null
        val made = StabilizationPath.make(measured, level) { if (cancel()) throw CancellationException() }
        return made.shifted(from - clip.stabilizationStart)
    }

    /**
     * Measures the camera path of [file] over [duration] seconds from source second [start] (:119-206): every frame
     * the decoder gives, reduced to the analysis size, compared with the one before.
     */
    private fun measure(file: File, start: Double, duration: Double, progress: (Double) -> Unit, cancel: () -> Boolean): StabilizationAnalysis {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("no video in ${file.name}")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            var measure: StabilizationMeasure? = null
            var estimator: MotionEstimator? = null
            try {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                codec.configure(format, null, null, 0)
                codec.start()
                extractor.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val info = MediaCodec.BufferInfo()
                // Frames later in time can come earlier in the file: read a little past the end.
                val lastSample = ((start + duration + 1.0) * 1e6).toLong()
                var fed = false
                // The frame shown when the stretch begins: kept as the one the first frame inside it is compared with.
                var opening: Double? = null
                while (true) {
                    if (cancel()) throw CancellationException()
                    while (!fed) {
                        val slot = codec.dequeueInputBuffer(0)
                        if (slot < 0) break
                        val buffer = codec.getInputBuffer(slot)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > lastSample) {
                            codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fed = true
                        } else {
                            codec.queueInputBuffer(slot, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 5_000)
                    if (out < 0) continue
                    val presentation = info.presentationTimeUs / 1e6
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || presentation >= start + duration
                    if (info.size > 0 && !end) codec.getOutputImage(out)?.use { image ->
                        val crop = image.cropRect
                        val y = image.planes[0]
                        // 10-bit frames come as 16-bit samples (P010), their high byte last: that byte is read.
                        val high = if (y.pixelStride == 2) 1 else 0
                        val meter = measure ?: StabilizationMeasure(crop.width(), crop.height(), duration).also { measure = it }
                        val motion = estimator ?: MotionEstimator(meter.analysisWidth, meter.analysisHeight).also { estimator = it }
                        fun push(estimate: Boolean) = motion.push(y.buffer, y.rowStride, y.pixelStride, high, crop.left, crop.top, crop.width(), crop.height(), full, estimate)
                        if (presentation <= start) {
                            // Before the stretch or at its first second: the latest such frame opens it (:144-146, 153).
                            push(estimate = false)
                            opening = presentation
                        } else {
                            opening?.let { meter.add(StabilizationMeasure.time(it, start), null); opening = null }
                            val time = StabilizationMeasure.time(presentation, start)
                            if (meter.accepts(time)) {
                                val found = push(estimate = meter.count > 0)
                                meter.add(time, found?.let { StabilizationMeasure.Motion(it.x, it.y, it.angle, it.logScale, it.valid) })
                                progress((time / duration).coerceIn(0.0, 1.0))
                            }
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (end) break
                }
                // A stretch of a single frame.
                opening?.let { measure?.add(StabilizationMeasure.time(it, start), null) }
            } finally {
                estimator?.close()
                runCatching { codec.stop() }
                codec.release()
            }
            return measure?.analysis() ?: throw IllegalStateException("no frames in ${file.name} from $start s")
        } finally {
            extractor.release()
        }
    }
}
