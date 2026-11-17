package app.takes.core

import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The recorder's two camera moves, Crash Zoom and Dolly Zoom, as the original makes them while it records
 * (YouRec/CaptureController.swift:212-233, 2005-2106; YouRec/VideoEffects/LiveVideoEffects.swift:281-391;
 * YouRec/VideoEffects/VideoEffectMath.swift:252-292; YouRec/VideoEffects/VideoEffectSubjectTracking.swift).
 *
 * A Crash Zoom is drawn over the preview and saved beside the take as a recipe: the movie is recorded clean, and
 * the move arrives in the editor as an effect of the take's clip. A Dolly Zoom moves the lens's zoom against the
 * size of a subject as the person walks; nothing is saved but the zoom itself.
 */

/** What a live Crash Zoom is doing, with the original's words for the button. LiveVideoEffects.swift:6. */
enum class LiveCrashState(val label: String) { Ready("Ready"), Pushing("Pushing"), Holding("Holding"), Returning("Returning") }

/** The button of a live Dolly Zoom, with the original's words. CaptureZoomHistory.swift:180-183. */
enum class LiveDollyState(val label: String) {
    Ready("Start dolly"), Preparing("Preparing"), Following("Stop dolly"), Stopping("Settling"), Holding("Start again");

    val moving: Boolean get() = this == Preparing || this == Following || this == Stopping
}

/**
 * A face or a person in the picture: a box in fractions of the frame, from the top. The original's
 * `EffectSubjectSample` (VideoEffectSubjectTracking.swift:7-20).
 */
data class SubjectSample(val time: Double, val box: Rect, val confidence: Double, val face: Boolean) {
    val center: Point get() = Point(box.midX, box.midY)
    /** Touching an edge of the frame, so that its size is not all there. */
    val clipped: Boolean get() = box.minX < 0.015 || box.minY < 0.015 || box.maxX > 0.985 || box.maxY > 0.985
    val valid: Boolean
        get() = time.isFinite() && time in 0.0..VideoEffectSettings.MAXIMUM_TIME && confidence.isFinite() && confidence in 0.0..1.0 &&
            listOf(box.minX, box.minY, box.width, box.height).all(Double::isFinite) &&
            box.width > 0 && box.height > 0 && box.minX >= 0 && box.minY >= 0 && box.maxX <= 1.0001 && box.maxY <= 1.0001
}

/**
 * Chooses the subject among the faces, or failing them the people, found in a picture, and keeps it from one
 * look to the next, as the original's `EffectSubjectTracker` chooses (VideoEffectSubjectTracking.swift:45-97):
 * the one that overlaps the subject of the last half second most, of the same kind; else the one under the tapped
 * [point], then the nearest to it; else the largest. Its centre is eased over 0.12 s. Boxes are in fractions of
 * the frame, from the top.
 *
 * This port has no tracker between two looks, as the original's Vision object tracking: each look is a detection.
 * A detected box counts with a confidence of 1.
 */
data class SubjectTracker(val point: Point? = null, val previous: SubjectSample? = null) {
    /** The next state and the subject in a picture of [time] where [faces] and [people] were found. */
    fun update(faces: List<Rect>, people: List<Rect>, time: Double): Pair<SubjectTracker, SubjectSample?> {
        val candidates = if (faces.isNotEmpty()) faces.map { it to true } else people.map { it to false }
        val last = previous?.takeIf { time - it.time < 0.5 }
        val chosen = when {
            last != null -> candidates.filter { it.second == last.face && overlap(it.first, last.box) > 0.1 }.maxByOrNull { overlap(it.first, last.box) }
            point != null -> candidates.minWithOrNull { a, b ->
                val aa = contains(a.first, point)
                val bb = contains(b.first, point)
                if (aa != bb) (if (aa) -1 else 1)
                else hypot(a.first.midX - point.x, a.first.midY - point.y).compareTo(hypot(b.first.midX - point.x, b.first.midY - point.y))
            }
            else -> candidates.maxByOrNull { it.first.width * it.first.height }
        } ?: return this to null
        val box = intersectUnit(chosen.first) ?: return this to null
        var result = SubjectSample(time, box, 1.0, chosen.second)
        if (!result.valid) return this to null
        if (last != null && time > last.time && last.face == result.face) {
            val alpha = 1 - exp(-(time - last.time) / 0.12)
            val x = last.center.x + (result.center.x - last.center.x) * alpha
            val y = last.center.y + (result.center.y - last.center.y) * alpha
            result = result.copy(box = intersectUnit(Rect(x - box.width / 2, y - box.height / 2, box.width, box.height)) ?: box)
        }
        return copy(previous = result) to result
    }

    companion object {
        /** The area two boxes share against the area they cover. VideoEffectSubjectTracking.swift:57-61. */
        fun overlap(a: Rect, b: Rect): Double {
            val w = min(a.maxX, b.maxX) - max(a.minX, b.minX)
            val h = min(a.maxY, b.maxY) - max(a.minY, b.minY)
            if (w <= 0 || h <= 0) return 0.0
            val shared = w * h
            return shared / max(0.000001, a.width * a.height + b.width * b.height - shared)
        }

        private fun contains(r: Rect, p: Point) = p.x >= r.minX && p.x < r.maxX && p.y >= r.minY && p.y < r.maxY

        /** The part of [r] inside the frame; null when none is. */
        fun intersectUnit(r: Rect): Rect? {
            val minX = max(0.0, r.minX)
            val minY = max(0.0, r.minY)
            val maxX = min(1.0, r.maxX)
            val maxY = min(1.0, r.maxY)
            return if (maxX > minX && maxY > minY) Rect(minX, minY, maxX - minX, maxY - minY) else null
        }
    }
}

/**
 * The Crash Zooms of a preview or a segment being recorded: each trigger adds one, at the time of the effect clock,
 * from the enabled Crash Zoom of the live effects. LiveVideoEffects.swift:339-391. The clock is the segment's
 * seconds while one is recorded, otherwise seconds since the preview started.
 */
data class LiveCrashZooms(val moves: List<VideoEffectSettings> = emptyList()) {
    private fun current(time: Double): Pair<Int, VideoEffectSettings>? =
        moves.withIndex().lastOrNull { (_, it) -> it.kind == VideoEffectID.CrashZoom && it.enabled && it.start <= time && it.end > time }?.let { it.index to it.value }

    /** The move under way at [time], if any. */
    fun active(time: Double): VideoEffectSettings? = current(time)?.second

    /** LiveVideoEffects.swift:387-392. */
    fun state(time: Double): LiveCrashState {
        val effect = active(time) ?: return LiveCrashState.Ready
        val push = effect.number("speed")
        val origin = effect.start - (effect.phaseOffset ?: 0.0)
        val release = releaseOf(effect, origin, push)
        return when {
            time < origin + push + 0.08 -> LiveCrashState.Pushing
            time >= release -> LiveCrashState.Returning
            else -> LiveCrashState.Holding
        }
    }

    /**
     * The control pressed at [time]: a move holding returns now, else a new one starts from [template] toward
     * [target], the subject tapped. Null when nothing changes: a move pushing, or returning, or no template.
     * LiveVideoEffects.swift:354-372.
     */
    fun triggered(time: Double, template: VideoEffectSettings?, target: Point?, id: String = newID()): LiveCrashZooms? {
        current(time)?.let { (index, effect) ->
            val push = effect.number("speed")
            val origin = effect.start - (effect.phaseOffset ?: 0.0)
            if (time < origin + push + 0.08 || time >= releaseOf(effect, origin, push)) return null
            return copy(moves = moves.toMutableList().also { it[index] = effect.copy(releaseTime = time, end = time + push) })
        }
        if (template == null || !template.enabled || template.kind != VideoEffectID.CrashZoom) return null
        var effect = template.copy(
            id = id, start = time, releaseTime = null, phaseOffset = 0.0,
            end = if (template.toggle("return")) time + 2 * template.number("speed") + 0.08 + template.number("hold") else VideoEffectSettings.MAXIMUM_TIME,
        )
        if (target != null) effect = effect.with("targetX", EffectValue.Number(target.x)).with("targetY", EffectValue.Number(target.y))
        return copy(moves = moves + effect)
    }

    /**
     * The move under way at [time], carried onto a clock that starts again at zero: when a segment begins or ends.
     * LiveVideoEffects.swift:339-345.
     */
    fun carried(time: Double): LiveCrashZooms = LiveCrashZooms(
        moves.filter { it.kind == VideoEffectID.CrashZoom && it.enabled && it.start <= time && it.end > time }.map { effect ->
            effect.copy(
                phaseOffset = (effect.phaseOffset ?: 0.0) + time - effect.start, start = 0.0,
                end = if (effect.end == VideoEffectSettings.MAXIMUM_TIME) VideoEffectSettings.MAXIMUM_TIME else max(1.0 / 30, effect.end - time),
                releaseTime = effect.releaseTime?.let { it - time },
            )
        },
    )

    /** How the picture is drawn at [time]: the magnification, the reach of its radial blur and its target. Metal:87-94, 361-366. */
    fun look(time: Double, tracked: Point? = null): CrashLook? {
        val effect = active(time) ?: return null
        val offset = effect.phaseOffset ?: 0.0
        val local = max(0.0, time - effect.start + offset)
        val release = effect.releaseTime?.let { it - effect.start + offset }
        val state = VideoEffectFormulasB.crashZoom(local, effect.number("zoom", time), effect.number("speed", time), effect.number("hold", time), effect.toggle("return", time), release)
        val target = tracked?.takeIf { effect.toggle("trackFace", time) } ?: Point(effect.number("targetX", time), effect.number("targetY", time))
        return CrashLook(state.zoom, VideoEffectFormulasB.crashZoomBlur(state.velocity, effect.number("motionBlur", time)), target)
    }

    private companion object {
        fun releaseOf(effect: VideoEffectSettings, origin: Double, push: Double): Double =
            effect.releaseTime ?: if (effect.toggle("return")) origin + push + 0.08 + effect.number("hold") else Double.POSITIVE_INFINITY
    }
}

/** A Crash Zoom on one picture: [zoom] about [target], in fractions of the frame from the top, with a radial blur reaching [blur]. */
data class CrashLook(val zoom: Double, val blur: Double, val target: Point)

/** The Crash Zooms of a take, saved beside its movie as the original's `<movie>.effects.json` (LiveVideoEffects.swift:291, 375-382). */
object CaptureEffectsFile {
    private val format = ListSerializer(VideoEffectSettingsSerializer)

    fun beside(movie: File): File = File(movie.parentFile, movie.nameWithoutExtension + ".effects.json")

    fun write(effects: List<VideoEffectSettings>, movie: File) =
        ProjectDisk.writeAtomically(documentJson.encodeToString(format, effects).toByteArray(), beside(movie))

    /**
     * The effects saved for [movie] within its [duration], as the clip of the take is given them, or null when there
     * are none or the file cannot be read; the file is kept either way. ProjectStore.swift:360-376.
     */
    fun read(movie: File, duration: Double): List<VideoEffectSettings>? = try {
        val file = beside(movie)
        if (!file.isFile) null
        else documentJson.decodeFromString(SupportedVideoEffects, file.readText()).takeIf { list -> list.all { it.valid } }
            ?.let { VideoEffectSettings.capturedRecipe(it, duration) }?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun delete(movie: File) {
        beside(movie).delete()
    }
}

/**
 * Keeps a subject the same size by zooming against its measured height: the original's `DollyZoomFollower`
 * (VideoEffectMath.swift:252-292). It uses the zoom of the lens at the picture measured, not the latest command,
 * and an absolute size, so that a stale measurement is not corrected again and again.
 */
class DollyZoomFollower(val referenceHeight: Double, zoom: Double, val range: ClosedFloatingPointRange<Double>, time: Double) {
    var zoom: Double = zoom; private set
    var atLimit: Boolean = false; private set
    private var rawLogHeight: Double? = ln(max(0.00001, referenceHeight / zoom))
    private var measurementTime = time
    private var frameTime = time
    private var velocity = 0.0
    private var stoppingAt: Double? = null

    fun observe(height: Double, appliedZoom: Double, time: Double) {
        if (stoppingAt != null || !height.isFinite() || height <= 0.0001 || !appliedZoom.isFinite() || appliedZoom <= 0 || time <= measurementTime) return
        val value = ln(height / appliedZoom)
        val alpha = 1 - exp(-(time - measurementTime) / 0.15)
        rawLogHeight = rawLogHeight?.let { it + (value - it) * alpha } ?: value
        measurementTime = time
    }

    val isStopping: Boolean get() = stoppingAt != null
    fun hasStopped(time: Double): Boolean = stoppingAt?.let { time - it >= 0.3 } ?: false
    fun stop(time: Double) { if (stoppingAt == null) stoppingAt = time }

    fun value(time: Double): Double {
        val dt = max(0.0, min(0.1, time - frameTime))
        frameTime = time
        if (dt <= 0) return zoom
        if (time - measurementTime > 0.5 && stoppingAt == null) stoppingAt = time
        stoppingAt?.let { began ->
            val remaining = max(0.0, 0.3 - (time - began))
            velocity *= if (remaining > 0) remaining / (remaining + dt) else 0.0
            zoom = (zoom * exp(velocity * dt)).coerceIn(range)
            return zoom
        }
        val target = referenceHeight / exp(rawLogHeight ?: ln(referenceHeight / zoom))
        val bounded = target.coerceIn(range)
        atLimit = target <= range.start * 1.02 || target >= range.endInclusive / 1.02
        val limit = 0.6 * ln(2.0)
        val desired = ((ln(bounded) - ln(zoom)) / 0.15).coerceIn(-limit, limit)
        velocity += (desired - velocity) * (1 - exp(-dt / 0.08))
        zoom = (zoom * exp(velocity * dt)).coerceIn(range)
        return zoom
    }
}

/** A subject measured on the whole picture of the lens, with the zoom of that lens at the picture. CaptureZoomHistory.swift:34-42. */
data class DollyMeasurement(val subject: SubjectSample, val time: Double, val zoom: Double, val lens: String)

/** What the person is told about a Dolly Zoom, in the original's words (CaptureController.swift:220, 2005-2106). */
enum class DollyHint(val words: String) {
    TapFace("Tap a face, then start"),
    MoreRange("Choose 1080p or another lens for more zoom range"),
    TapVisible("Tap a visible face or person"),
    HoldStill("Hold still while the camera frames the subject"),
    KeepInFrame("Keep the subject fully in frame, then try again"),
    ZoomOut("Zoom out before starting this move"),
    LeaveRoom("Change Direction or zoom to leave room for the move"),
    WalkToward("Walk slowly toward the subject"),
    WalkAway("Walk slowly away from the subject"),
    Holding("Holding this framing"),
    FollowingBody("Following body — a visible face is steadier"),
    NearLimit("Near the zoom limit"),
    Digital("Digital zoom — image may look softer"),
    // Why a move ended, said in its place.
    MoveHeld("Move held"),
    MoveComplete("Move complete"),
    SubjectLost("Subject lost — tap to choose again"),
    MoveStopped("Move stopped"),
    ZoomStopped("Zoom stopped"),
    MovePaused("Move paused"),
    RecordingStopped("Recording stopped"),
}

/** The arithmetic of starting and running a Dolly Zoom. CaptureController.swift:2005-2087. */
object DollyZoomMove {
    /** The zoom the preparation moves to takes this long, and the move is armed this long after it starts. */
    const val PREPARE_SECONDS = 0.8
    const val ARM_DELAY = 1.15
    /** A measurement older than this cannot start a move. */
    const val FRESH = 0.5

    /** The zooms the move may use: the lens's widest to half again its clean zoom; null where that is less than 1.3 times. */
    fun range(minimum: Double, maximum: Double, clean: Double): ClosedFloatingPointRange<Double>? {
        val upper = min(maximum, clean * 1.5)
        return if (upper / minimum >= 1.3) minimum..upper else null
    }

    /** Stretch first zooms in to leave room to zoom out: the subject 0.7 of the frame high, at most 2×. */
    fun prepared(stretch: Boolean, current: Double, upper: Double, subjectHeight: Double): Double =
        if (stretch) min(upper, max(current, min(2.0, 0.7 / subjectHeight * current))) else current

    fun needsPreparation(desired: Double, current: Double): Boolean = desired > current * 1.05

    /** How far the zoom can go the way the move goes, as a ratio; a move needs 1.12. */
    fun headroom(stretch: Boolean, zoom: Double, range: ClosedFloatingPointRange<Double>): Double =
        if (stretch) zoom / range.start else range.endInclusive / zoom

    fun hasRoom(stretch: Boolean, zoom: Double, range: ClosedFloatingPointRange<Double>): Boolean = headroom(stretch, zoom, range) >= 1.12

    /** The room left, from 1 to 0, as the bar under the button shows it. */
    fun remaining(stretch: Boolean, zoom: Double, range: ClosedFloatingPointRange<Double>): Double {
        val span = max(0.001, ln(range.endInclusive / range.start))
        return (if (stretch) ln(zoom / range.start) / span else ln(range.endInclusive / zoom) / span).coerceIn(0.0, 1.0)
    }

    /** What the person is told while the move runs. */
    fun hint(stopping: Boolean, face: Boolean?, remaining: Double, zoom: Double, clean: Double, stretch: Boolean): DollyHint = when {
        stopping -> DollyHint.Holding
        face == false -> DollyHint.FollowingBody
        remaining < 0.08 -> DollyHint.NearLimit
        zoom > clean * 1.02 -> DollyHint.Digital
        stretch -> DollyHint.WalkToward
        else -> DollyHint.WalkAway
    }

    /**
     * Whether [next] may be taken as the subject's size: whole, sure, neither tiny nor filling the frame, from the
     * lens in use; while a move runs, of the same kind as before and not jumping in size. CaptureController.swift:2056-2073.
     */
    fun accepts(next: DollyMeasurement, lens: String, previous: DollyMeasurement?, following: Boolean): Boolean {
        val subject = next.subject
        if (!subject.valid || subject.clipped || subject.confidence < 0.6 || subject.box.height !in 0.04..0.8 || next.lens != lens) return false
        if (previous != null && following) {
            if (previous.subject.face != subject.face) return false
            val dt = max(1.0 / 30, next.time - previous.time)
            val change = abs(ln((subject.box.height / next.zoom) / (previous.subject.box.height / previous.zoom)))
            if (change >= min(0.5, 0.25 * dt * 15)) return false
        }
        return true
    }
}

/**
 * Points between the recorded frame and the camera's picture as it arrives. The recorded frame is the camera's
 * picture turned clockwise by [upright] degrees, and then mirrored when the take is saved mirrored. Both in fractions
 * from the top-left corner.
 */
object RecorderFramePoints {
    fun toCamera(point: Point, upright: Int, mirrored: Boolean): Point {
        val x = if (mirrored) 1 - point.x else point.x
        val y = point.y
        return when ((upright % 360 + 360) % 360) {
            90 -> Point(y, 1 - x)
            180 -> Point(1 - x, 1 - y)
            270 -> Point(1 - y, x)
            else -> Point(x, y)
        }
    }

    fun fromCamera(point: Point, upright: Int, mirrored: Boolean): Point {
        val turned = when ((upright % 360 + 360) % 360) {
            90 -> Point(1 - point.y, point.x)
            180 -> Point(1 - point.x, 1 - point.y)
            270 -> Point(point.y, 1 - point.x)
            else -> point
        }
        return if (mirrored) Point(1 - turned.x, turned.y) else turned
    }

    /** A box of the camera's picture in the recorded frame. */
    fun fromCamera(box: Rect, upright: Int, mirrored: Boolean): Rect {
        val a = fromCamera(Point(box.minX, box.minY), upright, mirrored)
        val b = fromCamera(Point(box.maxX, box.maxY), upright, mirrored)
        return Rect(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
    }

    /**
     * Where a touch at ([x], [y]) on a view of [width] by [height] falls in a frame of [aspect] (width over height)
     * shown centred in it, filling it or fitting in it; null outside the frame. [unmirror] undoes a preview shown in a mirror.
     */
    fun fromView(x: Double, y: Double, width: Double, height: Double, aspect: Double, fill: Boolean, unmirror: Boolean): Point? {
        if (width <= 0 || height <= 0 || aspect <= 0) return null
        val scale = if (fill) max(width / aspect, height) else min(width / aspect, height)
        val shownWidth = scale * aspect
        val shownHeight = scale
        val px = (x - (width - shownWidth) / 2) / shownWidth
        val py = (y - (height - shownHeight) / 2) / shownHeight
        if (px < 0 || px > 1 || py < 0 || py > 1) return null
        return Point(if (unmirror) 1 - px else px, py)
    }
}

/** The live effects the recorder keeps between launches, as the original's `liveVideoEffects` (CaptureController.swift:204-208, 1382-1389). */
object LiveEffectsStore {
    fun encode(effects: List<VideoEffectSettings>): String = documentJson.encodeToString(SupportedVideoEffects, effects)

    /** What was saved; nothing when it cannot be read or holds an effect that is not valid. */
    fun decode(text: String?): List<VideoEffectSettings> = try {
        if (text == null) emptyList() else documentJson.decodeFromString(SupportedVideoEffects, text).takeIf { list -> list.all { it.valid } } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
