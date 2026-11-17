package app.takes

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import app.takes.core.SourceMedia
import app.takes.core.StabilizationAnalysis
import app.takes.core.StabilizationCheckpoint
import app.takes.core.StabilizationLevel
import app.takes.core.StabilizationMeasure
import app.takes.core.StabilizationPath
import app.takes.core.StabilizationPlan
import app.takes.core.TimelineClip
import app.takes.core.stabilizationEnd
import app.takes.core.stabilizationStart
import app.takes.stabilization.MotionEstimator
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
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
 * The cached path is named by a hash of the recording's contents, as the original names it
 * (VideoStabilizer.swift:31-35, SourceMediaCache.swift:126-159), the hash kept by the file's path, size and date.
 * A measurement of the whole path, which an edit's stabilisation needs, keeps a checkpoint beside it, and one
 * that was stopped goes on from there (:52-70, :119-206); the tool's preview of a window keeps none, as in the
 * original, where only a saved edit's measurement does.
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

    private val fingerprints = ConcurrentHashMap<String, String>()

    /**
     * What names a recording's file to the cache: the SHA-256 of its contents, read a megabyte at a time, kept in
     * memory and in `Cache/Fingerprints/` of its folder by its path, size and date (SourceMediaCache.swift:126-159).
     */
    private fun fingerprint(file: File): String {
        val identity = "${file.path}|${file.length()}|${file.lastModified() / 1000.0}"
        fingerprints[identity]?.let { return it }
        val memo = File(File(File(file.parentFile, "Cache"), "Fingerprints"), sha256(identity.toByteArray()))
        runCatching { memo.readText() }.getOrNull()?.takeIf { value -> value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' } }?.let {
            fingerprints[identity] = it
            return it
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val made = digest.digest().joinToString("") { "%02x".format(it) }
        fingerprints[identity] = made
        // Keeping it is a convenience: a full disk only costs reading the file again next time.
        runCatching {
            memo.parentFile?.mkdirs()
            val writing = File(memo.parentFile, ".${memo.name}")
            writing.writeText(made)
            if (!writing.renameTo(memo)) writing.delete()
        }
        return made
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The cached path of [file] from source second [start] to [end]. */
    fun cacheFile(folder: File, file: File, start: Double, end: Double): File =
        File(directory(folder), StabilizationAnalysis.key(fingerprint(file), start, end) + ".json")

    /** The paths being measured with a checkpoint now, by their cached file. */
    private val building: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The checkpoint beside the cached path [cached] (:54). */
    private fun checkpointFile(cached: File): File = File(cached.parentFile, cached.nameWithoutExtension + ".checkpoint")

    /**
     * The camera path of [source] (in the project folder [folder]) from source second [start] to [end]: from memory,
     * from the cache, or measured and kept. With [generate] false nothing is measured and null is given when it is not
     * kept. Blocks: call it off the main thread. [progress] goes from 0 to 1; [cancel] is asked at every frame, and
     * when it says so the work stops with a [CancellationException]. With [resumable] a checkpoint is kept as it goes
     * and one left by a measurement that was stopped is gone on from; the checkpoints of other paths not being
     * measured are let go (:52-61).
     */
    fun analysis(
        source: SourceMedia, folder: File, start: Double, end: Double, generate: Boolean = true,
        progress: (Double) -> Unit = {}, cancel: () -> Boolean = { false }, resumable: Boolean = false,
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
            val directory = directory(folder)
            directory.mkdirs()
            val checkpoint = checkpointFile(cached).takeIf { resumable }
            if (checkpoint != null) {
                building += cached.path
                directory.listFiles { it: File -> it.extension == "checkpoint" }.orEmpty()
                    .filter { other -> File(directory, other.nameWithoutExtension + ".json").path !in building }
                    .forEach { it.delete() }
            }
            val measured = try {
                measure(file, start, end - start, progress, cancel, checkpoint)
            } finally {
                building -= cached.path
            }
            check(measured.valid) { "no measurable frames in ${source.filename}" }
            val writing = File(directory, ".${cached.name}")
            writing.writeBytes(measured.encoded())
            if (!writing.renameTo(cached)) { writing.delete(); throw IllegalStateException("cannot keep ${cached.name}") }
            checkpoint?.delete()
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
        val measured = analysis(source, folder, start, end, generate, progress, cancel, resumable = true) ?: return null
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

    /** A checkpoint that did not fit the recording once its frames were read: measured again from the start. */
    private class Unresumable : Exception()

    /**
     * Measures the camera path of [file] over [duration] seconds from source second [start] (:119-206): every frame
     * the decoder gives, reduced to the analysis size, compared with the one before. With [checkpoint], one kept
     * there is gone on from, and one is kept five seconds on and then every 5 to 30 seconds (:134-149, :191-197).
     */
    private fun measure(file: File, start: Double, duration: Double, progress: (Double) -> Unit, cancel: () -> Boolean, checkpoint: File?): StabilizationAnalysis {
        val saved = checkpoint?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }?.let(StabilizationCheckpoint::decode)
        return try {
            measure(file, start, duration, progress, cancel, checkpoint, saved)
        } catch (_: Unresumable) {
            checkpoint?.delete()
            measure(file, start, duration, progress, cancel, checkpoint, null)
        }
    }

    private fun measure(
        file: File, start: Double, duration: Double, progress: (Double) -> Unit, cancel: () -> Boolean, checkpoint: File?, saved: StabilizationCheckpoint?,
    ): StabilizationAnalysis {
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
                // Going on from a checkpoint: a little before its last frame, whose time is skipped through (:144-146).
                val from = start + (saved?.let { StabilizationCheckpoint.resumeFrom(it.timestamp) } ?: 0.0)
                extractor.seekTo((from * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val info = MediaCodec.BufferInfo()
                // Frames later in time can come earlier in the file: read a little past the end.
                val lastSample = ((start + duration + 1.0) * 1e6).toLong()
                var fed = false
                // The frame shown when the stretch begins: kept as the one the first frame inside it is compared with.
                var opening: Double? = null
                var nextCheckpoint = StabilizationCheckpoint.first(saved?.timestamp ?: Double.NEGATIVE_INFINITY)
                progress(maxOf(0.0, saved?.timestamp ?: 0.0) / duration)
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
                        val meter = measure ?: run {
                            val fresh = StabilizationMeasure(crop.width(), crop.height(), duration)
                            if (saved == null) fresh
                            else if (!saved.resumes(crop.width(), crop.height(), duration, fresh.analysisWidth, fresh.analysisHeight)) throw Unresumable()
                            else StabilizationMeasure.resuming(saved)
                        }.also { measure = it }
                        val motion = estimator ?: MotionEstimator(meter.analysisWidth, meter.analysisHeight).also { made ->
                            estimator = made
                            // The frame the checkpoint kept is the one the next is compared with.
                            saved?.let { kept ->
                                val plane = ByteBuffer.allocateDirect(kept.previousFrame.size).put(kept.previousFrame).also { it.flip() }
                                made.push(plane, meter.analysisWidth, 1, 0, 0, 0, meter.analysisWidth, meter.analysisHeight, fullRange = true, estimate = false)
                            }
                        }
                        fun push(estimate: Boolean) = motion.push(y.buffer, y.rowStride, y.pixelStride, high, crop.left, crop.top, crop.width(), crop.height(), full, estimate)
                        if (presentation <= start) {
                            // Before the stretch or at its first second: the latest such frame opens it (:144-146, 153).
                            // Going on from a checkpoint it is long measured.
                            if (saved == null) {
                                push(estimate = false)
                                opening = presentation
                            }
                        } else {
                            opening?.let { meter.add(StabilizationMeasure.time(it, start), null); opening = null }
                            val time = StabilizationMeasure.time(presentation, start)
                            if (meter.accepts(time)) {
                                val found = push(estimate = meter.count > 0)
                                meter.add(time, found?.let { StabilizationMeasure.Motion(it.x, it.y, it.angle, it.logScale, it.valid) })
                                if (checkpoint != null && time >= nextCheckpoint) {
                                    meter.checkpoint(motion.lastFrame())?.let { kept ->
                                        val writing = File(checkpoint.parentFile, ".${checkpoint.name}")
                                        writing.writeBytes(kept.encoded())
                                        if (!writing.renameTo(checkpoint)) writing.delete()
                                    }
                                    nextCheckpoint = StabilizationCheckpoint.next(time)
                                }
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
