package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
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
 * the export.
 *
 * Android has neither Vision's face rectangles nor its human rectangles: the faces are the platform's
 * [FaceDetector]'s, as the recorder's face follow finds them (CameraFrames.kt), and the person is the box around the
 * person mask the effects already read (A/PersonMasks.kt), where LiteRT runs.
 */
object SubjectTracks {
    private val tracks = ConcurrentHashMap<String, EffectSubjectTrack>()
    private val working = ConcurrentHashMap.newKeySet<String>()
    private val background = Executors.newSingleThreadExecutor { Thread(it, "Subject tracks").apply { isDaemon = true } }
    private val claims = ConcurrentHashMap<String, Any>()

    /** The longer side of the frames looked at, as the original's (:63). */
    private const val SIDE = 512
    private const val MAX_FACES = 5

    private fun name(media: SourceMedia, clip: TimelineClip, effect: VideoEffectSettings): String {
        val key = EffectSubjectAnalysis.key("${media.filename}:${media.duration}", effect, clip)
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun file(folder: File, name: String) = File(File(File(folder, "Cache"), "subjects"), "subject-$name.json")

    /**
     * The track of [effect] on [clip] from its recording [file]: kept, read from the project's cache, or made now,
     * which takes seconds: never on the main thread, and before an export rather than while it draws.
     */
    fun track(context: Context, file: File, media: SourceMedia, clip: TimelineClip, effect: VideoEffectSettings): EffectSubjectTrack {
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
        var detector: FaceDetector? = null
        var detectorSize = 0 to 0
        // The frames in order, a fifteenth of a second apart from the span's start, as the original reads them (:140-146).
        var next = span.start
        decodeLuma(file, span.start, span.endInclusive, rotation, wants = { at -> at + 0.00001 >= next }) { at, input ->
            next = at + EffectSubjectTrack.STEP
            val faces = {
                if (detector == null || detectorSize != input.width to input.height) {
                    detector = FaceDetector(input.width, input.height, MAX_FACES)
                    detectorSize = input.width to input.height
                }
                val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
                val count = runCatching { detector!!.findFaces(input, found) }.getOrDefault(0)
                val middle = PointF()
                val w = input.width.toDouble()
                val h = input.height.toDouble()
                (0 until count).mapNotNull { index ->
                    val face = found[index]?.takeIf { it.confidence() >= FaceDetector.Face.CONFIDENCE_THRESHOLD } ?: return@mapNotNull null
                    face.getMidPoint(middle)
                    val eyes = face.eyesDistance().toDouble()
                    // From the eyes to a box around the face, as the recorder's face follow makes it. The platform's
                    // own threshold stands for Vision's 0.5.
                    EffectSubjectCandidate(Rect((middle.x - eyes * 1.1) / w, (middle.y - eyes * 0.9) / h, eyes * 2.2 / w, eyes * 2.6 / h), 1.0)
                }
            }
            val people = { masks?.let { reader -> person(reader, at, rotation) }?.let(::listOf).orEmpty() }
            tracker.update(at, faces, people)?.let { samples += it }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("SubjectTracks", "${media.filename} ${span.start}…${span.endInclusive} s: masks ${masked - started} ms, faces ${now - masked} ms, ${samples.size} samples")
        return EffectSubjectTrack(samples)
    }

    /**
     * Decodes the frames of [file] from [from] to [to] seconds in order and gives each, with its time, as a grey
     * picture stood upright ([rotation] clockwise), at most [SIDE] pixels on its longer side and of an even width,
     * as the face finder takes it, where [wants] asks for the time. One picture is reused: [frame] must not keep it.
     */
    private fun decodeLuma(file: File, from: Double, to: Double, rotation: Int, wants: (Double) -> Boolean, frame: (Double, Bitmap) -> Unit) {
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
            var picture: Bitmap? = null
            var pixels = IntArray(0)
            var places = IntArray(0)
            var geometry = ""
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
                    decoder.getOutputImage(out)?.use { image ->
                        val crop = image.cropRect
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val sw = crop.width()
                        val sh = crop.height()
                        val turned = rotation % 180 != 0
                        val uw = if (turned) sh else sw
                        val uh = if (turned) sw else sh
                        val scale = min(1.0, SIDE.toDouble() / max(uw, uh))
                        val w = max(2, (uw * scale).toInt()) and 1.inv()
                        val h = max(1, (uh * scale).toInt())
                        if (picture?.width != w || picture?.height != h) {
                            picture?.recycle()
                            picture = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                            pixels = IntArray(w * h)
                        }
                        // Where each upright pixel is read on the stored frame, worked out once for a shape.
                        val shape = "$crop:${plane.rowStride}:${plane.pixelStride}:$w:$h"
                        if (shape != geometry) {
                            geometry = shape
                            places = IntArray(w * h)
                            for (y in 0 until h) for (x in 0 until w) {
                                val ux = (x + 0.5) / w
                                val uy = (y + 0.5) / h
                                val fx: Double
                                val fy: Double
                                when ((rotation % 360 + 360) % 360) {
                                    90 -> { fx = uy; fy = 1 - ux }
                                    180 -> { fx = 1 - ux; fy = 1 - uy }
                                    270 -> { fx = 1 - uy; fy = ux }
                                    else -> { fx = ux; fy = uy }
                                }
                                val sx = crop.left + min(sw - 1, (fx * sw).toInt())
                                val sy = crop.top + min(sh - 1, (fy * sh).toInt())
                                places[y * w + x] = sy * plane.rowStride + sx * plane.pixelStride
                            }
                        }
                        for (i in places.indices) {
                            val v = buffer.get(places[i]).toInt() and 0xff
                            pixels[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
                        }
                        picture!!.setPixels(pixels, 0, w, 0, 0, w, h)
                        frame(time, picture!!)
                    }
                }
                decoder.releaseOutputBuffer(out, false)
                if (end) break
            }
            picture?.recycle()
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
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
