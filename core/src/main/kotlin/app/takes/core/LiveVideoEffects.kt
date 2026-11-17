package app.takes.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The recorder's live video effects: which are offered, which are shown at a moment of the preview, and the recipe
 * saved beside a take. The movie is recorded clean; the effects go into the editor as the take's clip effects, as the
 * original saves them (YouRec/VideoEffects/LiveVideoEffects.swift:168-200, 281-345).
 */
object LiveVideoEffects {
    /** The effects offered while recording: the library's without the editor-only ones (Night fix, Freeze Frame, Step Printing). Catalog:60. */
    val offered: List<VideoEffectID> = VideoEffectLibrarySection.entries.flatMap { section -> section.effects(live = true).map { it.id } }

    /** The camera moves, made by the camera rather than drawn as a look. LiveVideoEffects.swift:184. */
    fun isMove(kind: VideoEffectID): Boolean = kind == VideoEffectID.DollyZoom || kind == VideoEffectID.CrashZoom

    /** The effects drawn as a look over the preview: all but the moves. LiveVideoEffects.swift:188. */
    fun looks(effects: List<VideoEffectSettings>): List<VideoEffectSettings> = effects.filter { !isMove(it.kind) }

    /** Something is drawn over the preview: an enabled look. LiveVideoEffects.swift:232-235. */
    fun drawsLook(effects: List<VideoEffectSettings>): Boolean = looks(effects).any { it.enabled }

    /**
     * The recipe of a take: the looks as they were set, each with its diagnostic view back on the result, over the
     * whole recording, then the Crash Zooms pressed in it. LiveVideoEffects.swift:291, 329-335.
     */
    fun recipe(effects: List<VideoEffectSettings>, crashes: List<VideoEffectSettings>): List<VideoEffectSettings> =
        looks(effects).map { it.recordingResult.copy(start = 0.0, end = VideoEffectSettings.MAXIMUM_TIME) } + crashes

    /** The project files an effect of [effects] names: a picked picture, copied beside the take. LiveVideoEffects.swift:286-290. */
    fun mediaFiles(effects: List<VideoEffectSettings>): List<String> = looks(effects).flatMap { it.mediaFiles }.distinct()

    /**
     * The effects shown at [time] of the preview: a Negative Strobe only while it is tried ([audition]) and for its
     * burst, from [Audition.start]; while recording, all of them. Null [Audition] in the result: the try is over.
     * LiveVideoEffects.swift:154-167.
     */
    fun displayed(effects: List<VideoEffectSettings>, time: Double, recording: Boolean, audition: Audition?): Pair<List<VideoEffectSettings>, Audition?> {
        if (recording || effects.none { it.kind == VideoEffectID.NegativeStrobe }) return effects to audition
        var current = audition
        val shown = effects.mapNotNull { effect ->
            if (effect.kind != VideoEffectID.NegativeStrobe) return@mapNotNull effect
            val trying = current?.takeIf { effect.enabled && it.id == effect.id } ?: return@mapNotNull null
            val start = trying.start ?: time
            current = trying.copy(start = start)
            val duration = if (effect.toggle("timedBurst")) effect.number("burstLength") else 2.0
            if (!(time >= start && time < start + duration)) {
                current = null
                return@mapNotNull null
            }
            effect.copy(start = start, end = start + duration)
        }
        return shown to current
    }

    /** A Negative Strobe being tried, from its first frame shown. LiveVideoEffects.swift:136-146. */
    data class Audition(val id: String, val start: Double? = null)

    /**
     * The effects drawn before [id], which a colour is picked from and a background placed over: without Quantization
     * and Step Printing, each with its view on the result, an Undercranking at its own speed.
     * YouRec/VideoEffects/VideoEffectAssets.swift:74-83.
     */
    fun prefix(effects: List<VideoEffectSettings>, id: String): List<VideoEffectSettings> =
        VideoEffectSettings.ordered(effects).takeWhile { it.id != id }
            .filter { it.kind != VideoEffectID.Quantization && it.kind != VideoEffectID.StepPrinting }
            .map { effect ->
                val value = effect.recordingResult
                if (value.kind != VideoEffectID.Undercranking) value
                else value.copy(parameters = value.parameters.map { if (it.id == "speed") it.copy(value = EffectValue.Number(1.0), keyframes = emptyList()) else it })
            }

    /**
     * The colour picked at a point of the preview: the median of each channel over the [width] × [height] patch
     * around it (9 × 9 inside the picture), [rgba] four bytes a pixel. VideoEffectAssets.swift:121-132.
     */
    fun medianColor(rgba: ByteArray, width: Int, height: Int): EffectRGB {
        val count = width * height
        if (count <= 0) return EffectRGB(0.0, 0.0, 0.0)
        fun channel(c: Int): Double = (0 until count).map { rgba[it * 4 + c].toInt() and 255 }.sorted()[count / 2] / 255.0
        return EffectRGB(channel(0), channel(1), channel(2))
    }

    /** The 9 × 9 patch around the point ([x], [y]) of a picture of [width] × [height], inside it: left, top, right and bottom, exclusive. */
    fun patch(x: Double, y: Double, width: Int, height: Int): IntArray {
        val px = kotlin.math.floor(min(1.0, max(0.0, x)) * (width - 1)).toInt()
        val py = kotlin.math.floor(min(1.0, max(0.0, y)) * (height - 1)).toInt()
        return intArrayOf(max(0, px - 4), max(0, py - 4), min(width, px + 5), min(height, py + 5))
    }

    /** What the panel says of the preview's quality. LiveVideoEffects.swift:566-572. */
    enum class QualityNote { Warm, WarmLite, Lite, Reduced }

    /**
     * The note for the preview: warm when the phone is ([serious] or more), lighter processing once any effect is
     * drawn lite, a reduced size; none at full quality.
     */
    fun note(serious: Boolean, budget: VideoEffectLiveBudget, lite: Boolean): QualityNote? = when {
        serious -> if (budget.quality == VideoEffectQuality.Lite) QualityNote.WarmLite else QualityNote.Warm
        lite -> QualityNote.Lite
        budget.maximumLongSide != null -> QualityNote.Reduced
        else -> null
    }

    /**
     * The phone's heat, from Android's thermal status (`PowerManager.THERMAL_STATUS_*`, 0 none to 6 shutdown), as the
     * original's serious and critical thermal states: severe is serious, critical and beyond critical.
     */
    fun serious(status: Int): Boolean = status >= 3
    fun critical(status: Int): Boolean = status >= 4
}

/**
 * How large and how carefully the live effects are drawn: smaller before lighter, by the frame times measured and
 * the phone's heat. YouRec/VideoEffects/VideoEffectMath.swift:295-352.
 *
 * [gpuSeconds] is the GPU time a frame may take; null for the original's 10 ms, 12 ms from a source larger than
 * 1080p, which an iPhone's GPU measures at its speed. An Android phone's GPU is timed at the clock its governor runs
 * it at, which stays low while the work is light (221 of 949 MHz on a Galaxy A54), so the recorder allows it most of
 * the frame instead (LiveEffectRendering.kt).
 */
class VideoEffectLiveBudget(private val gpuSeconds: Double? = null) {
    var quality = VideoEffectQuality.Full; private set
    var maximumLongSide: Double? = null; private set
    private val samples = ArrayDeque<Boolean>()
    private val startupGPU = mutableListOf<Double>()
    private var warmupFrames = 0
    private var observationSize: Double? = null
    private var observationQuality: VideoEffectQuality? = null

    /** The same look at serious heat, smaller; at critical, the last step, lite. Math:303-309. */
    fun thermal(serious: Boolean, critical: Boolean) {
        if (!serious && !critical) return
        maximumLongSide = min(maximumLongSide ?: Double.POSITIVE_INFINITY, if (critical) 960.0 else 1280.0)
        if (critical) quality = VideoEffectQuality.Lite
    }

    /** One frame drawn: [gpu] seconds of drawing, [wall] of the whole frame, at a picture of [renderLongSide] from one of [sourceLongSide]. Math:310-351. */
    fun observe(gpu: Double, wall: Double, sourceLongSide: Double, renderLongSide: Double) {
        if (!gpu.isFinite() || !wall.isFinite() || gpu < 0 || wall < 0) return
        maximumLongSide?.let { if (renderLongSide > it + 1) return }
        if (observationSize?.let { abs(it - renderLongSide) > 1 } != false || observationQuality != quality) {
            samples.clear()
            startupGPU.clear()
            warmupFrames = 0
            observationSize = renderLongSide
            observationQuality = quality
        }
        if (warmupFrames < 10) {
            warmupFrames++
            return
        }
        val budget = gpuSeconds ?: if (sourceLongSide > 1920) 0.012 else 0.010
        if (startupGPU.size < 15) {
            startupGPU += gpu
            if (startupGPU.size == 15 && startupGPU.count { it > 2 * budget } >= 12) {
                val median = startupGPU.sorted()[7]
                val target = renderLongSide * sqrt(max(0.02, (budget * 0.85 - 0.002) / (median - 0.002)))
                val rungs = RUNGS.filter { it < renderLongSide - 1 }
                val next = rungs.firstOrNull { it <= target } ?: rungs.lastOrNull()
                if (next != null) {
                    maximumLongSide = min(maximumLongSide ?: Double.POSITIVE_INFINITY, next)
                    samples.clear()
                    return
                }
            }
        }
        samples.addLast(gpu > budget || wall > 0.026)
        if (samples.size > 90) samples.removeFirst()
        if (samples.size != 90 || samples.count { it } < 6) return
        samples.clear()
        for (limit in RUNGS) {
            if (renderLongSide > limit + 1) {
                maximumLongSide = min(maximumLongSide ?: Double.POSITIVE_INFINITY, limit)
                return
            }
        }
        quality = VideoEffectQuality.Lite
    }

    private companion object {
        val RUNGS = listOf(1920.0, 1280.0, 960.0, 864.0, 768.0, 640.0)
    }
}

/** A drag or a pinch on the preview that places a picked background. Math:354-358. */
sealed interface VideoEffectPlacementGesture {
    data class Pan(val delta: Point) : VideoEffectPlacementGesture
    data class Pinch(val factor: Double, val from: Point, val to: Point) : VideoEffectPlacementGesture
    data object Ended : VideoEffectPlacementGesture
}

/**
 * Where a picked background lies on the frame, as its effect's backgroundX, backgroundY and backgroundZoom: the finger
 * moves the picture, a pinch scales it about the fingers, from Fit or Fill up to four times. Math:359-386.
 */
data class VideoEffectBackgroundPlacement(
    val x: Double = 0.5, val y: Double = 0.5, val zoom: Double = 1.0,
    val canvas: Size, val media: Size, val fill: Boolean,
) {
    private fun size(zoom: Double): Size {
        val sx = canvas.width / max(1.0, media.width)
        val sy = canvas.height / max(1.0, media.height)
        val scale = (if (fill) max(sx, sy) else min(sx, sy)) * zoom
        return Size(media.width * scale / max(1.0, canvas.width), media.height * scale / max(1.0, canvas.height))
    }

    /** The picture exactly covers the frame: it cannot be moved until it is enlarged. */
    val movementLocked: Boolean get() = size(zoom).let { abs(1 - it.width) < 0.0001 && abs(1 - it.height) < 0.0001 }

    fun applying(gesture: VideoEffectPlacementGesture): VideoEffectBackgroundPlacement {
        val old = size(zoom)
        fun align(value: Double, span: Double, previous: Double): Double = if (abs(span) < 0.0001) previous else min(1.0, max(0.0, value / span))
        return when (gesture) {
            is VideoEffectPlacementGesture.Pan -> copy(
                x = align((1 - old.width) * x + gesture.delta.x, 1 - old.width, x),
                y = align((1 - old.height) * y + gesture.delta.y, 1 - old.height, y),
            )
            is VideoEffectPlacementGesture.Pinch -> {
                if (!gesture.factor.isFinite() || gesture.factor <= 0) return this
                val originX = (1 - old.width) * x
                val originY = (1 - old.height) * y
                val nextZoom = min(4.0, max(1.0, zoom * gesture.factor))
                val next = size(nextZoom)
                copy(
                    zoom = nextZoom,
                    x = align(gesture.to.x - (gesture.from.x - originX) * next.width / old.width, 1 - next.width, x),
                    y = align(gesture.to.y - (gesture.from.y - originY) * next.height / old.height, 1 - next.height, y),
                )
            }
            VideoEffectPlacementGesture.Ended -> this
        }
    }
}
