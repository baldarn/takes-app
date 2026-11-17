package app.takes.core

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What the recorder's live effects worked out while a take was recorded, saved beside it so that the editor
 * draws the take with what the preview showed: the original's `CapturedEffectAnalysis`
 * (YouRec/VideoEffects/VideoEffectCaptureAnalysis.swift:25-36), in its JSON, with its data file beside it.
 *
 * It holds, in seconds of the segment: the palettes each Quantization showed, by the recipe's effect; the person
 * masks the preview drew with ([masks]) and Auto Background's smoothed ones ([autoBackgroundMasks]), each a frame's
 * 8-bit rows compressed on its own in the data file (:107-125); the quality each effect was drawn at
 * ([qualities], :96-100); and the subject a camera move followed ([subjects], :88-95). The temporal mattes the
 * original also keeps (:126-134) are not written: see [Recorder].
 *
 * Departures: a mask is compressed with Deflate, as [PersonMaskChunks] keeps its own, where the original uses LZFSE
 * (:114); a mask is in the coordinates of the picture the preview drew, the recording stood upright, and a subject's
 * box 0…1 from its top left, as the port's editor keeps them (C/EffectSubjectTracking.kt); and [maskGaps] marks the
 * stretches whose masks were found on a picture the preview's Crash Zoom had pushed in, which are not the
 * recording's, so that the editor finds those masks itself.
 */
@Serializable
data class CapturedEffectAnalysis(
    val version: Int = 1,
    val palettes: Map<String, Track> = emptyMap(),
    val masks: List<MaskFrame> = emptyList(),
    val autoBackgroundMasks: List<MaskFrame>? = null,
    val qualities: Map<String, QualityTrack>? = null,
    val subjects: EffectSubjectTrack? = null,
    val maskFilter: String? = null,
    val autoBackgroundMotionAware: Boolean? = null,
    val maskGaps: List<Gap>? = null,
) {
    @Serializable
    data class Frame(val time: Double, val palette: EffectPalette)

    @Serializable
    data class Track(val frames: List<Frame>) {
        val track: EffectPaletteTrack get() = EffectPaletteTrack(frames.map { EffectPaletteFrame(it.time, it.palette) })
    }

    /** A stored person mask: where its compressed rows lie in the data file (VideoEffectCaptureAnalysis.swift:6-12). */
    @Serializable
    data class MaskFrame(val time: Double, val offset: Long, val bytes: Int, val width: Int, val height: Int)

    /** The quality an effect was drawn at from [time] (:13-16). */
    @Serializable
    data class QualityFrame(val time: Double, val quality: VideoEffectQuality)

    /** An effect's qualities, kept when they change (:17-24). */
    @Serializable
    data class QualityTrack(val frames: List<QualityFrame> = emptyList()) {
        /** The quality at [time]: the last one set at or before it, the first before them all, full without any (:19-23). */
        fun quality(time: Double): VideoEffectQuality {
            var a = 0
            var b = frames.size
            while (a < b) {
                val m = (a + b) / 2
                if (frames[m].time <= time) a = m + 1 else b = m
            }
            return if (frames.isEmpty()) VideoEffectQuality.Full else frames[maxOf(0, a - 1)].quality
        }
    }

    /** Seconds from [start] to [end] whose masks are not the recording's. */
    @Serializable
    data class Gap(val start: Double, val end: Double)

    /** Whether Auto Background's smoothed masks are kept, made with the motion gate (:164). */
    val hasMotionAwareAutoBackground: Boolean
        get() = (autoBackgroundMotionAware == true || maskFilter == "auto-background-motion-v2") && !autoBackgroundMasks.isNullOrEmpty()

    /** Whether [time] falls where the masks kept are not the recording's. */
    fun inGap(time: Double): Boolean = maskGaps.orEmpty().any { time >= it.start && time < it.end }

    /** Whether a stretch from [from] to [to] has a gap in it. */
    fun gapIn(from: Double, to: Double): Boolean = maskGaps.orEmpty().any { it.start < to && it.end > from }

    /** The quality [id] was drawn at, at [time]: full where nothing was kept (VideoEffectAssets.swift:474). */
    fun quality(id: String, time: Double): VideoEffectQuality = qualities?.get(id)?.quality(time) ?: VideoEffectQuality.Full

    /**
     * Whether a reader may trust it, as the original's checks (VideoEffectCaptureAnalysis.swift:175-208), its masks
     * within a data file of [dataLength] bytes.
     */
    fun valid(dataLength: Long): Boolean {
        fun increasing(times: List<Double>) = times.all { it.isFinite() && it >= 0 } && times.zipWithNext().all { (a, b) -> a < b }
        fun masksValid(frames: List<MaskFrame>) = frames.size <= MAXIMUM_FRAMES && increasing(frames.map { it.time }) && frames.all {
            it.width in 1..MAXIMUM_SIDE && it.height in 1..MAXIMUM_SIDE && it.bytes in 1 until MAXIMUM_BYTES && it.bytes <= dataLength &&
                it.offset >= 0 && it.offset <= dataLength - it.bytes
        }
        return version == 1 && masks.size <= MAXIMUM_FRAMES && palettes.size <= 100 &&
            (maskFilter == null || maskFilter in FILTERS) &&
            masksValid(masks) && masksValid(autoBackgroundMasks.orEmpty()) &&
            palettes.values.all { track -> track.frames.size <= MAXIMUM_FRAMES && track.frames.all { it.palette.valid } && increasing(track.frames.map { it.time }) } &&
            subjects?.valid != false &&
            (qualities?.size ?: 0) <= 100 && qualities.orEmpty().values.all { it.frames.size <= MAXIMUM_FRAMES && increasing(it.frames.map { f -> f.time }) } &&
            maskGaps.orEmpty().all { it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end >= it.start }
    }

    /** The valid analysis's palettes check alone, for one read without its data file. */
    val valid: Boolean get() = valid(Long.MAX_VALUE / 2)

    /**
     * Collects the analysis of one segment as its frames are drawn
     * (VideoEffectCaptureAnalysis.swift:54-160): a palette, a quality is kept only when it differs from the effect's
     * last one, and only at a time after it; a mask only when it is another one than the last, at the time of the
     * first frame drawn with it (:119-125); the subject at every frame while it is followed, and where it is lost
     * after it, once (:88-95). The masks are compressed and written on [executor], in order, off the frames' thread;
     * [save] waits for them. Safe to call from the frames' thread while another thread saves it.
     *
     * The temporal mattes the original keeps, the frames each matte's history took (:126-134), are not: they are
     * source frame times, which the preview's clock, the time each frame is drawn, is not.
     */
    class Recorder(
        val metadataName: String = "effect-${newID()}.json",
        val dataName: String = "effect-${newID()}.bin",
        private val executor: Executor = Executor { it.run() },
        private val checkpointInterval: Int = 60,
    ) {
        /**
         * Seconds the clock of [append] runs ahead of the movie's frames: the time on it of the movie's first frame.
         * The original's frames are timed by the movie's writer (LiveVideoEffects.swift:641-646); here the clock
         * starts when the recorder says the recording has begun, before the first frame it keeps, and [snapshot]
         * brings every time back by this much. It can be set at any time; the latest holds.
         */
        @Volatile var shift: Double = 0.0

        private val palettes = LinkedHashMap<String, MutableList<Frame>>()
        private val qualities = LinkedHashMap<String, MutableList<QualityFrame>>()
        private var subjects: MutableList<EffectSubjectSample>? = null
        private var gaps = mutableListOf<Gap>()
        private var gapStart: Double? = null
        private var lastPerson: PersonMaskChunks.Mask? = null
        private var lastAuto: PersonMaskChunks.Mask? = null
        private var frames = 0
        private var folder: File? = null

        // Written on the executor alone.
        private val masks = mutableListOf<MaskFrame>()
        private var autoMasks: MutableList<MaskFrame>? = null
        private var data: RandomAccessFile? = null
        private val deflater = Deflater(Deflater.BEST_SPEED)
        private val buffer = ByteArray(1 shl 16)

        /** Opens the data file in [folder], beside the take, empty, so that a take ended at once has a readable analysis (:66-76). */
        @Synchronized
        fun begin(folder: File) {
            if (this.folder != null) return
            this.folder = folder
            executor.execute {
                runCatching { data = RandomAccessFile(File(folder, dataName), "rw").apply { setLength(0) } }
            }
        }

        /** [append] with the palettes alone. */
        fun append(time: Double, shown: Map<String, EffectPalette>) = append(time, shown, null, null)

        /**
         * Adds what the frame drawn at [time] showed: the palettes [shown]; the raw person mask [person] and Auto
         * Background's smoothed one [auto], or null when the frame drew none, [pushed] when they were found on a
         * picture the preview's Crash Zoom pushed in; the [qualities] the effects were drawn at; the [subject] a camera
         * move followed, or null when none was found, with [tracking] whether one was looked for.
         */
        @Synchronized
        fun append(
            time: Double, shown: Map<String, EffectPalette>, person: PersonMaskChunks.Mask?, auto: PersonMaskChunks.Mask?,
            qualities: Map<String, VideoEffectQuality> = emptyMap(), subject: EffectSubjectSample? = null, tracking: Boolean = false, pushed: Boolean = false,
        ) {
            if (!time.isFinite() || time < 0 || time > VideoEffectSettings.MAXIMUM_TIME) return
            if (tracking || subject != null) {
                val samples = subjects ?: mutableListOf<EffectSubjectSample>().also { subjects = it }
                val last = samples.lastOrNull()
                val found = subject?.copy(time = time, lost = null)?.takeIf { it.valid }
                if (found != null) {
                    if (last == null || time > last.time) samples += found
                } else if (last != null && last.lost != true && time > last.time) {
                    samples += last.copy(time = time, lost = true)
                }
            }
            for ((id, quality) in qualities) {
                val track = this.qualities.getOrPut(id) { mutableListOf() }
                val last = track.lastOrNull()
                if (last == null || last.quality != quality && time > last.time) track += QualityFrame(time, quality)
            }
            for ((id, palette) in shown) {
                val track = palettes.getOrPut(id) { mutableListOf() }
                val last = track.lastOrNull()
                if (last != null && (last.palette == palette || time <= last.time)) continue
                track += Frame(time, palette)
            }
            if (person != null && person !== lastPerson) {
                lastPerson = person
                if (pushed) {
                    if (gapStart == null) gapStart = time
                } else {
                    gapStart?.let { start -> if (time > start) gaps += Gap(start, time) }
                    gapStart = null
                    store(time, person, auto = false)
                }
            }
            if (auto != null && auto !== lastAuto && !pushed) {
                lastAuto = auto
                store(time, auto, auto = true)
            }
            frames++
            if (checkpointInterval > 0 && frames % checkpointInterval == 0) folder?.let { checkpoint(it) }
        }

        private fun store(time: Double, mask: PersonMaskChunks.Mask, auto: Boolean) {
            if (folder == null) return
            executor.execute {
                val file = data ?: return@execute
                try {
                    val list = synchronized(masks) { if (auto) autoMasks ?: mutableListOf<MaskFrame>().also { autoMasks = it } else masks }
                    if (time <= (list.lastOrNull()?.time ?: -1.0)) return@execute
                    val packed = pack(mask.rows)
                    val offset = file.length()
                    file.seek(offset)
                    file.write(packed)
                    synchronized(masks) { list += MaskFrame(time, offset, packed.size, mask.width, mask.height) }
                } catch (_: Exception) {
                    // A mask not kept is found again by the editor.
                }
            }
        }

        private fun pack(rows: ByteArray): ByteArray {
            deflater.reset()
            deflater.setInput(rows)
            deflater.finish()
            val out = ByteArrayOutputStream(rows.size / 8)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        }

        /**
         * What was collected so far, the masks those written by now, its times brought to the movie's by [shift]: what
         * falls before the movie's first frame is dropped but for the last of each, which stands from its start.
         */
        @Synchronized
        fun snapshot(): CapturedEffectAnalysis {
            val by = shift
            val open = gapStart?.let { listOf(Gap(it, VideoEffectSettings.MAXIMUM_TIME + by)) }.orEmpty()
            return CapturedEffectAnalysis(
                palettes = palettes.mapValues { (_, frames) -> Track(shifted(frames, by, { it.time }) { f, t -> f.copy(time = t) }) },
                masks = synchronized(masks) { shifted(masks.toList(), by, { it.time }) { f, t -> f.copy(time = t) } },
                autoBackgroundMasks = synchronized(masks) { autoMasks?.toList()?.let { list -> shifted(list, by, { it.time }) { f, t -> f.copy(time = t) } } },
                qualities = this.qualities.takeIf { it.isNotEmpty() }?.mapValues { (_, frames) -> QualityTrack(shifted(frames, by, { it.time }) { f, t -> f.copy(time = t) }) },
                subjects = subjects?.let { samples -> EffectSubjectTrack(shifted(samples, by, { it.time }) { f, t -> f.copy(time = t) }, stepwise = true) },
                maskFilter = FILTER,
                autoBackgroundMotionAware = true,
                maskGaps = (gaps + open).mapNotNull { gap -> Gap(maxOf(0.0, gap.start - by), gap.end - by).takeIf { it.end > it.start } }.takeIf { it.isNotEmpty() },
            )
        }

        // The JSON written after the masks before it, on the executor, as the original checkpoints every sixty frames (:135-137).
        private fun checkpoint(folder: File) {
            executor.execute {
                runCatching {
                    data?.fd?.sync()
                    ProjectDisk.writeAtomically(documentJson.encodeToString(serializer(), snapshot()).toByteArray(), File(folder, metadataName))
                }
            }
        }

        /**
         * Writes what was collected beside a take in [folder], after the masks asked for before, as the original's
         * checkpoint (VideoEffectCaptureAnalysis.swift:154-160); with [last] the data file is closed. Waits for the
         * writing: not for the frames' thread.
         */
        fun save(folder: File, last: Boolean = false) {
            begin(folder)
            val done = CountDownLatch(1)
            var failure: Exception? = null
            executor.execute {
                try {
                    data?.fd?.sync()
                    ProjectDisk.writeAtomically(documentJson.encodeToString(serializer(), snapshot()).toByteArray(), File(folder, metadataName))
                    if (last) {
                        data?.close()
                        data = null
                        deflater.end()
                    }
                } catch (problem: Exception) {
                    failure = problem
                } finally {
                    done.countDown()
                }
            }
            done.await()
            failure?.let { throw it }
        }

        /** Removes its files from [folder], when no recipe names it (VideoEffectCaptureAnalysis.swift:139-142). */
        fun discard(folder: File) {
            val done = CountDownLatch(1)
            executor.execute {
                runCatching { data?.close() }
                data = null
                deflater.end()
                for (name in listOf(metadataName, dataName)) File(folder, name).delete()
                done.countDown()
            }
            done.await()
        }

        /** The effects of a recipe pointing at this analysis, each by its own id (LiveVideoEffects.swift:329-333, 376-380). */
        fun label(recipe: List<VideoEffectSettings>): List<VideoEffectSettings> =
            recipe.map { it.copy(analysisFile = metadataName, analysisDataFile = dataName, capturedEffectID = it.id) }
    }

    /**
     * The masks of an analysis read back by time, from its data file (VideoEffectCaptureAnalysis.swift:246-282): the
     * one shown at a time is the last at or before it, the first before them all. Not for two threads at once.
     */
    class Masks(val analysis: CapturedEffectAnalysis, data: File) : Closeable {
        private val input = RandomAccessFile(data, "r")
        private val inflater = Inflater()
        private val last = arrayOfNulls<PersonMaskChunks.Mask>(2)

        /** The raw mask shown at [time], or with [auto] Auto Background's smoothed one; null without any, or in a gap. */
        fun mask(time: Double, auto: Boolean = false): PersonMaskChunks.Mask? {
            if (analysis.inGap(time)) return null
            val frames = (if (auto) analysis.autoBackgroundMasks else analysis.masks).orEmpty()
            if (frames.isEmpty()) return null
            var low = 0
            var high = frames.size
            while (low < high) {
                val middle = (low + high) / 2
                if (frames[middle].time <= time) low = middle + 1 else high = middle
            }
            val frame = frames[maxOf(0, low - 1)]
            val kind = if (auto) 1 else 0
            last[kind]?.let { if (it.time == frame.time) return it }
            val packed = ByteArray(frame.bytes)
            input.seek(frame.offset)
            input.readFully(packed)
            val rows = ByteArray(frame.width * frame.height)
            inflater.reset()
            inflater.setInput(packed)
            val n = try { inflater.inflate(rows) } catch (problem: Exception) { throw ProjectException(ProjectException.Reason.InvalidDocument, problem) }
            if (n != rows.size || !inflater.finished()) throw ProjectException(ProjectException.Reason.InvalidDocument)
            return PersonMaskChunks.Mask(frame.time, frame.width, frame.height, rows).also { last[kind] = it }
        }

        override fun close() {
            inflater.end()
            input.close()
        }
    }

    companion object {
        /**
         * [items], in order of time, each [at] [by] seconds earlier: those that come to fall before 0 are dropped,
         * but for the last of them, which is put at 0.
         */
        internal fun <T> shifted(items: List<T>, by: Double, at: (T) -> Double, moved: (T, Double) -> T): List<T> {
            if (by == 0.0) return items.toList()
            val kept = items.indexOfLast { at(it) - by <= 0.0 }
            return items.drop(maxOf(0, kept)).map { moved(it, maxOf(0.0, at(it) - by)) }
        }

        /** How the smoothed masks are made: the motion-gated EMA of Auto Background, as the live pass writes it (LiveVideoEffects.swift:307). */
        const val FILTER = "auto-background-ema-v1"
        private val FILTERS = setOf("motion-gated-ema-v1", "auto-background-ema-v1", "auto-background-motion-v2")
        private const val MAXIMUM_FRAMES = 2_000_000
        private const val MAXIMUM_BYTES = 2_000_000
        private const val MAXIMUM_SIDE = 1024

        /**
         * The analysis in [file], or null when there is none or it cannot be trusted (:175-210): its masks are checked
         * against the data file [data] when it is given.
         */
        fun read(file: File, data: File? = null): CapturedEffectAnalysis? = try {
            if (!file.isFile || file.length() > 64_000_000) null
            else documentJson.decodeFromString(serializer(), file.readText()).takeIf { it.valid(if (data == null) Long.MAX_VALUE / 2 else if (data.isFile) data.length() else 0L) }
        } catch (_: Exception) {
            null
        }

        /** Whether a take's live effects call for an analysis: any effect on but a Dolly Zoom (LiveVideoEffects.swift:307). */
        fun wanted(effects: List<VideoEffectSettings>): Boolean = effects.any { it.enabled && it.kind != VideoEffectID.DollyZoom }

        /**
         * The effect of [effects] whose analysis the frame at source second [time] reads: the first on there that has
         * one (VideoEffectAssets.swift:375-379).
         */
        fun source(effects: List<VideoEffectSettings>?, time: Double): VideoEffectSettings? =
            effects.orEmpty().firstOrNull { it.enabled && it.analysisFile != null && time >= it.start && time < it.end }

        /**
         * Whether the masks of [analysis] serve a clip's effects from [from] to [to] seconds without analysing its
         * footage: it has raw masks, Auto Background's smoothed ones too when [auto] is wanted, and none of them were
         * found on a pushed-in picture there (MediaRenderer.swift:376-378).
         */
        fun masksServe(analysis: CapturedEffectAnalysis?, auto: Boolean, from: Double, to: Double): Boolean = unserved(analysis, auto, from, to) == null

        /**
         * The seconds from [from] to [to] whose masks the editor finds itself, as [masksServe] says: all of them when the
         * saved masks do not serve, the stretch over the gaps when only those are missing, null when none.
         */
        fun unserved(analysis: CapturedEffectAnalysis?, auto: Boolean, from: Double, to: Double): ClosedFloatingPointRange<Double>? {
            if (analysis == null || analysis.masks.isEmpty() || auto && !analysis.hasMotionAwareAutoBackground) return from..to
            val gaps = analysis.maskGaps.orEmpty().filter { it.start < to && it.end > from }
            if (gaps.isEmpty()) return null
            return maxOf(from, gaps.minOf { it.start })..minOf(to, gaps.maxOf { it.end })
        }

        /**
         * Whether the palettes captured for [effect] still draw it as the preview did
         * (YouRec/MediaRenderer.swift:413-419): it has an analysis, its colours and its palette lock are as recorded,
         * the effects before it give the same picture as the recorded ones before theirs, and the clip's own colour,
         * crop, mirror and stabilisation are untouched ([plain]). [recipe] is what was saved beside the take.
         */
        fun palettesHold(effect: VideoEffectSettings, clipEffects: List<VideoEffectSettings>, recipe: List<VideoEffectSettings>?, plain: Boolean): Boolean {
            if (effect.analysisFile == null || !plain) return false
            val original = recipe?.firstOrNull { it.analysisID == effect.analysisID } ?: return false
            val inputs = setOf("colors", "lockPalette")
            val samePalette = original.parameters.filter { it.id in inputs } == effect.parameters.filter { it.id in inputs }
            val sameInput = LiveVideoEffects.prefix(recipe, original.id).map { it.captureIdentity } ==
                LiveVideoEffects.prefix(clipEffects, effect.id).map { it.captureIdentity }
            return samePalette && sameInput
        }
    }
}
