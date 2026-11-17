package app.takes

import app.takes.core.AudioGainMix
import app.takes.core.AudioGainSelection
import app.takes.core.AudioHeadroom
import app.takes.core.AudioLevels
import app.takes.core.EditInterval
import app.takes.core.EditState
import app.takes.core.TrackMixPlan
import app.takes.core.TrackSequenceView
import app.takes.core.TrackTimeline
import app.takes.core.mixPlan
import app.takes.core.toTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * How far the sound a level is being chosen for can go before the movie's
 * mix clips, measured as the original measures it (AudioGain.swift:190-246):
 * the mix of the chosen sound alone at 0 dB and the mix of everything else,
 * sample by sample at 48 kHz over the chosen stretch of the movie, each made
 * by the audio engine from the mix plan with the levels of [TrackMixPlan.gainMix],
 * as the export mixes it: overlapping sounds, speed with its pitch kept,
 * chains and censors included.
 */
object Headroom {
    private val working = Dispatchers.IO.limitedParallelism(1)

    /**
     * The ids the timeline's sounds have for the clips and sound items [chosen] (by id) in [state]: a clip's
     * own sound, and a sound item as the conversion names it ([TrackSequenceView.soundID]).
     */
    private fun owners(state: EditState, chosen: Set<String>): Set<String> {
        if (!state.isSequence) return chosen
        val clips = state.clips.mapTo(HashSet()) { it.id }
        val items = state.audio.orEmpty().mapTo(HashSet()) { it.id }
        return chosen.flatMapTo(LinkedHashSet()) { id ->
            listOfNotNull(TrackTimeline.counterpart(id).takeIf { id in clips }, TrackSequenceView.soundID(state, id).takeIf { id in items })
                .ifEmpty { listOf(id) }
        }
    }

    /**
     * The headroom of the clips and sounds [chosen] (by id) in [state] over
     * [range] seconds of the movie. An empty measurement ([AudioHeadroom.silent])
     * when the chosen sound is silent or muted there; null when a recording
     * cannot be decoded. [files] gives each source's file; the effects' background movies, which the mix plays
     * too, are in [folder].
     */
    suspend fun measure(state: EditState, files: Map<String, File>, folder: File, chosen: Set<String>, range: ClosedFloatingPointRange<Double>): AudioHeadroom? = withContext(working) {
        val voiced = HashMap<String, Boolean>()
        val timeline = state.toTimeline()
        val (plan, sounds) = BackgroundSounds.mix(timeline.mixPlan { source -> voiced.getOrPut(source) { files[source]?.let(::hasSound) == true } }, timeline, files, folder)
        val rate = AudioLevels.MEASURE_RATE
        val frames = AudioHeadroom.frames(range, plan.duration.seconds, rate) ?: return@withContext AudioHeadroom()
        val selection = AudioGainSelection(owners(state, chosen), EditInterval.of(range.start, range.endInclusive))
        val failures = ArrayList<String>()
        val processed = Denoiser.input(sounds)
        audioEngine(plan.gainMix(selection, AudioGainMix.Mode.Selected), sounds, processed, rate, failures = failures).use { selected ->
            audioEngine(plan.gainMix(selection, AudioGainMix.Mode.Remainder), sounds, processed, rate, failures = failures).use { remainder ->
                var result = AudioHeadroom()
                val block = 4096
                val own = FloatArray(block * 2)
                val rest = FloatArray(block * 2)
                selected.seek(frames.first)
                remainder.seek(frames.first)
                var frame = frames.first
                while (frame <= frames.last) {
                    coroutineContext.ensureActive()
                    val count = minOf(block.toLong(), frames.last - frame + 1).toInt()
                    selected.render(own, count)
                    remainder.render(rest, count)
                    result = result.including(own, rest, count * 2)
                    frame += count
                }
                if (failures.isNotEmpty()) null else result
            }
        }
    }
}
