@file:OptIn(UnstableApi::class)

package app.takes.pixels

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import app.takes.ClipColor
import app.takes.HdrSignalEffect
import app.takes.core.HdrToneMap
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * HLG footage given to the effects that work on SDR (YouRec/VideoEffects/MetalVideoEffects.swift:206-221, 605-613):
 * the export's pass draws core's tone map ([HdrToneMap.hlgToSdr]) on a ramp and on colours, as the original's test
 * draws an HLG ramp (Tests/VideoEffectIntegrationTests.swift:7-40); its highlights are not held at SDR white; and a
 * clip whose only effect is a Kaleidoscope keeps its HDR (MetalVideoEffects.swift:596-606).
 */
class HdrToneMapPixelCases(private val bench: Bench) {
    fun all(): List<Case> = listOf(
        Case("HDR tone map: the pass draws core's curve on an HLG ramp and on colours") { ramp() },
        Case("HDR tone map: only Kaleidoscopes keep a clip's HDR before its effects") { geometry() },
    )

    private fun Checks.ramp() {
        val width = 256
        val input = Picture(width, 64) { x, y ->
            when (y * 4 / 64) {
                0 -> rgb(x, x, x)
                1 -> rgb(x, (x * 0.8).toInt(), (x * 0.5).toInt())
                2 -> rgb((x * 0.4).toInt(), x, (x * 0.7).toInt())
                else -> { val n = (x + 256 * y) * 2654435761L; rgb((n shr 8).toInt() and 0xff, (n shr 16).toInt() and 0xff, (n shr 24).toInt() and 0xff) }
            }
        }
        val stage = GlEffectStage(bench, HdrSignalEffect(HdrSignalEffect.Conversion.HlgToSdr))
        val drawn = try { stage.frame(input) } finally { stage.release() }
        var worst = 0
        for (y in 0 until input.height) for (x in 0 until width) {
            val color = input.at(x, y)
            val want = HdrToneMap.hlgToSdr(DoubleArray(3) { c -> ((color shr (16 - 8 * c)) and 0xff) / 255.0 })
            for (c in 0..2) worst = maxOf(worst, abs(drawn.channel(x, y, c) - (want[c] * 255).roundToInt()))
        }
        val top = (200 until width).map { drawn.channel(it, 0, 1) }
        note("largest difference from core $worst levels; the grey ramp's top from 200: ${top.toSet().size} levels, ${top.first()}…${top.last()}")
        check(worst <= 2) { "The pass is $worst levels from core's tone map" }
        check(top.toSet().size >= 8) { "The highlights collapse to ${top.toSet()}" }
    }

    private fun Checks.geometry() {
        fun added(vararg kinds: VideoEffectID): List<Effect> {
            val effects = mutableListOf<Effect>()
            val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 2.0, videoEffects = kinds.map { VideoEffectSettings(it) })
            ClipColor(effects, hdr = true, transfer = C.COLOR_TRANSFER_HLG).beforeEffects(clip)
            return effects
        }
        note("Kaleidoscope: ${added(VideoEffectID.Kaleidoscope).size} passes; with Noir: ${added(VideoEffectID.Kaleidoscope, VideoEffectID.Noir).size}")
        check(added(VideoEffectID.Kaleidoscope).isEmpty()) { "A Kaleidoscope alone takes the picture to SDR" }
        check(added(VideoEffectID.Kaleidoscope, VideoEffectID.Noir).single() is HdrSignalEffect) { "A colour effect keeps HDR" }
        check(added(VideoEffectID.Noir).single() is HdrSignalEffect) { "A colour effect alone keeps HDR" }
    }
}
