package app.takes

import android.content.Context
import android.media.Image
import app.takes.core.FaceDetection
import app.takes.core.EffectSubjectAnalysis
import app.takes.core.EffectSubjectCandidate
import app.takes.core.EffectSubjectTrack
import app.takes.core.EffectSubjectTracker
import app.takes.core.PersonMaskChunks
import app.takes.core.PersonMaskPlacement
import app.takes.core.Point
import app.takes.core.Rect
import app.takes.core.SourceMedia
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectSettings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The subject a Dolly Zoom, or a Crash Zoom following a face, moves toward, followed across its clip from the point
 * the person set, as the original's editor analyses it (YouRec/VideoEffects/VideoEffectSubjectTracking.swift:108-150):
 * the footage looked at 15 times a second over the span the effect covers, the faces found in each frame or, where
 * there is none, the person, and the subject chosen and followed by [EffectSubjectTracker]. The track is kept in the
 * project's `Cache/subjects/`, named by the recording, the span and the point, and in memory, for the preview and
 * the export. A take's own effects follow the subject the recorder's preview followed, saved with it.
 *
 * Android has neither Vision's face rectangles nor its human rectangles: the faces are MediaPipe's full-range face
 * detector's ([FaceFinder]), as the recorder's face follow finds them (CameraFrames.kt), and the person is the box
 * around the person mask the effects already read (A/PersonMasks.kt), both where LiteRT runs.
 */
object SubjectTracks {
    private val tracks = ConcurrentHashMap<String, EffectSubjectTrack>()
    private val working = ConcurrentHashMap.newKeySet<String>()
    private val background = Executors.newSingleThreadExecutor { Thread(it, "Subject tracks").apply { isDaemon = true } }
    private val claims = ConcurrentHashMap<String, Any>()

    private fun name(media: SourceMedia, clip: TimelineClip, effect: VideoEffectSettings): String {
        // The face finder is part of the key: a track made with another one is made again.
        val key = EffectSubjectAnalysis.key("${media.filename}:${media.duration}", effect, clip) + "|faces:" + FaceFinder.MODEL
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun file(folder: File, name: String) = File(File(File(folder, "Cache"), "subjects"), "subject-$name.json")

    /**
     * The track of [effect] on [clip] from its recording [file]: kept, read from the project's cache, or made now,
     * which takes seconds: never on the main thread, and before an export rather than while it draws.
     */
    fun track(context: Context, file: File, media: SourceMedia, clip: TimelineClip, effect: VideoEffectSettings): EffectSubjectTrack {
        captured(file, effect)?.let { return it }
        val name = name(media, clip, effect)
        tracks[name]?.let { return it }
        synchronized(claims.computeIfAbsent(name) { Any() }) {
            tracks[name]?.let { return it }
            val folder = file.parentFile ?: File(".")
            val kept = file(folder, name)
            val read = runCatching { kept.readText() }.getOrNull()?.let(EffectSubjectTrack::decoded)
            val made = read ?: analyse(context, file, media, clip, effect).also { track ->
                runCatching {
                    kept.parentFile?.mkdirs()
                    val partial = File(kept.path + ".part")
                    partial.writeText(track.encoded())
                    partial.renameTo(kept)
                }
            }
            if (tracks.size > 32) tracks.clear()
            tracks[name] = made
            return made
        }
    }

    /**
     * Makes the tracks of every effect of [project]'s clips that follows a subject, as the original's renderer
     * prepares them before an export (VideoEffectSubjectTracking.swift:112): made on the frames' thread, an analysis
     * would take longer than the Transformer waits for a frame. A recording that cannot be analysed is left to the
     * targets set by hand.
     */
    fun prepare(context: Context, project: app.takes.core.Project, files: Map<String, File>) {
        val media = project.sources.associateBy { it.id }
        for (clip in project.edit.clips) {
            val file = files[clip.sourceID] ?: continue
            val source = media[clip.sourceID] ?: continue
            for (effect in clip.videoEffects.orEmpty().filter { it.enabled && EffectSubjectAnalysis.tracked(it) }) {
                try {
                    track(context.applicationContext, file, source, clip, effect)
                } catch (problem: Exception) {
                    android.util.Log.w("SubjectTracks", "subject of ${source.filename}", problem)
                }
            }
        }
    }

    /** The track when it is kept or on disk; else null, with the analysis begun in the background and [ready] called when it is done. */
    fun cached(context: Context, file: File, media: SourceMedia, clip: TimelineClip, effect: VideoEffectSettings, ready: () -> Unit): EffectSubjectTrack? {
        captured(file, effect)?.let { return it }
        val name = name(media, clip, effect)
        tracks[name]?.let { return it }
        if (working.add(name)) background.execute {
            try {
                track(context, file, media, clip, effect)
            } catch (problem: Exception) {
                android.util.Log.w("SubjectTracks", "subject of ${media.filename}", problem)
            } finally {
                working.remove(name)
            }
            ready()
        }
        return null
    }

    /**
     * The subject the recorder's preview followed for [effect] while its take was recorded, saved with the take: the
     * original draws an effect of the take with it and analyses only those added in the editor (MediaRenderer.swift:407,
     * VideoEffectAssets.swift:476). Null when the take has none, as one recorded before the subject was saved.
     */
    private fun captured(file: File, effect: VideoEffectSettings): EffectSubjectTrack? =
        if (effect.analysisFile == null) null else CapturedAnalyses.of(file, effect)?.analysis?.subjects

    private fun analyse(context: Context, file: File, media: SourceMedia, clip: TimelineClip, effect: VideoEffectSettings): EffectSubjectTrack {
        val span = EffectSubjectAnalysis.span(effect, clip)
        val folder = file.parentFile ?: File(".")
        val rotation = PersonMasks.rotation(file)
        val started = android.os.SystemClock.elapsedRealtime()
        // The person masks of the span, for the person where no face is found.
        val masks = if (PersonMasks.available()) runCatching {
            PersonMasks.analyse(context, media, folder, from = span.start, to = span.endInclusive)
            PersonMasks.reader(context, media, folder)
        }.onFailure { android.util.Log.w("SubjectTracks", "person masks of ${media.filename}", it) }.getOrNull() else null
        val masked = android.os.SystemClock.elapsedRealtime()
        val tracker = EffectSubjectTracker(EffectSubjectAnalysis.seed(effect, clip))
        val samples = mutableListOf<app.takes.core.EffectSubjectSample>()
        val finder = if (FaceFinder.available()) runCatching { FaceFinder(context) }
            .onFailure { android.util.Log.w("SubjectTracks", "face finder", it) }.getOrNull() else null
        // The frames in order, a fifteenth of a second apart from the span's start, as the original reads them (:140-146).
        var next = span.start
        try {
            decode(file, span.start, span.endInclusive, wants = { at -> at + 0.00001 >= next }) { at, image ->
                next = at + EffectSubjectTrack.STEP
                // The faces on the frame stood upright, with the model's score for Vision's confidence (:67).
                val faces = {
                    finder?.faces(image.cropRect.width(), image.cropRect.height(), rotation, pixels(image))
                        ?.map { EffectSubjectCandidate(it.box, it.score) }.orEmpty()
                }
                val people = { masks?.let { reader -> person(reader, at, rotation) }?.let(::listOf).orEmpty() }
                tracker.update(at, faces, people)?.let { samples += it }
            }
        } finally {
            finder?.close()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("SubjectTracks", "${media.filename} ${span.start}…${span.endInclusive} s: masks ${masked - started} ms, faces ${now - masked} ms, ${samples.size} samples")
        return EffectSubjectTrack(samples)
    }

    /**
     * Decodes the frames of [file] from [from] to [to] seconds in order and gives each, with its time, as the decoder
     * gives it, where [wants] asks for the time. The frame is the decoder's: [frame] must not keep it.
     */
    private fun decode(file: File, from: Double, to: Double, wants: (Double) -> Boolean, frame: (Double, Image) -> Unit) {
        val extractor = android.media.MediaExtractor().apply { setDataSource(file.path) }
        var codec: android.media.MediaCodec? = null
        try {
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            format.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.seekTo((from * 1e6).toLong(), android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = android.media.MediaCodec.BufferInfo()
            val lastSample = ((to + 1.0) * 1e6).toLong()
            var fed = false
            while (true) {
                while (!fed) {
                    val slot = decoder.dequeueInputBuffer(0)
                    if (slot < 0) break
                    val size = extractor.readSampleData(decoder.getInputBuffer(slot)!!, 0)
                    val time = extractor.sampleTime
                    if (size < 0 || time > lastSample) {
                        decoder.queueInputBuffer(slot, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        decoder.queueInputBuffer(slot, 0, size, time, 0)
                        extractor.advance()
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 5_000)
                if (out < 0) continue
                val time = info.presentationTimeUs / 1e6
                val end = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || time >= to
                if (info.size > 0 && !end && time + 0.000001 >= from && wants(time)) {
                    decoder.getOutputImage(out)?.use { image -> frame(time, image) }
                }
                decoder.releaseOutputBuffer(out, false)
                if (end) break
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /**
     * The pixels of a decoded frame, within its crop, read from its Y′CbCr planes as BT.709 limited range: close
     * enough to the colours for finding faces. Ten-bit frames come as 16-bit samples, their high byte last: that
     * byte is read.
     */
    private fun pixels(image: Image): FaceDetection.Pixels {
        val crop = image.cropRect
        val (y, u, v) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val luma = y.buffer
        val cb = u.buffer
        val cr = v.buffer
        val high = if (y.pixelStride == 2) 1 else 0
        val left = crop.left
        val top = crop.top
        val yRow = y.rowStride
        val yStep = y.pixelStride
        val uRow = u.rowStride
        val uStep = u.pixelStride
        val vRow = v.rowStride
        val vStep = v.pixelStride
        return FaceDetection.Pixels { px, py ->
            val x = left + px
            val row = top + py
            val l = ((luma.get(row * yRow + x * yStep + high).toInt() and 0xFF) - 16) * 1.164f
            val pb = (cb.get(row / 2 * uRow + x / 2 * uStep + high).toInt() and 0xFF) - 128
            val pr = (cr.get(row / 2 * vRow + x / 2 * vStep + high).toInt() and 0xFF) - 128
            val r = (l + 1.793f * pr).toInt().coerceIn(0, 255)
            val g = (l - 0.213f * pb - 0.533f * pr).toInt().coerceIn(0, 255)
            val b = (l + 2.112f * pb).toInt().coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }
    }

    /** The box around the person of the mask at [time], on the recording stood upright. */
    private fun person(reader: PersonMaskChunks.Reader, time: Double, rotation: Int): EffectSubjectCandidate? {
        val mask = synchronized(reader) { reader.mask(time, false) } ?: return null
        val stored = EffectSubjectTracker.maskBox(mask.rows, mask.width, mask.height) ?: return null
        val toUpright = PersonMaskPlacement.uprightToStored(rotation).inverted()
        val corners = listOf(Point(stored.box.minX, stored.box.minY), Point(stored.box.maxX, stored.box.maxY)).map { it.applying(toUpright) }
        val x0 = min(corners[0].x, corners[1].x)
        val y0 = min(corners[0].y, corners[1].y)
        return EffectSubjectCandidate(Rect(x0, y0, max(corners[0].x, corners[1].x) - x0, max(corners[0].y, corners[1].y) - y0), stored.confidence)
    }
}
