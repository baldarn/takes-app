@file:OptIn(UnstableApi::class)

package app.takes.sound

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import app.takes.clippedItem
import app.takes.core.RenderPlan
import app.takes.steadySpeed
import java.io.File
import kotlin.math.roundToLong

/**
 * The sound of a sequence's export as it was made before the audio engine: each clip's sound on sequences of
 * its own ([RenderPlan.voices]), the two sound tracks ([RenderPlan.soundtracks]), the cleaned sound of a clip
 * in place of its recording, and every run's level applied by [SoundProcessor]; the movie ends with its
 * picture. Kept word for word from `ExportMovie.sound()` as the other side of the sound gate.
 */
fun legacySound(plan: RenderPlan, files: Map<String, File>, voiced: Map<String, Boolean>, clean: Map<Int, File>): Composition {
    fun sequence(entries: List<RenderPlan.Entry>): EditedMediaItemSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).apply {
        for (entry in entries) when (entry) {
            is RenderPlan.Entry.Gap -> addGap(entry.micros)
            is RenderPlan.Entry.Sound -> addItem(
                EditedMediaItem.Builder(clippedItem(entry, files.getValue(entry.item.sourceID))).setRemoveVideo(true)
                    .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(plan.soundEnvelope(entry.item), entry.start)), emptyList()))
                    .build(),
            )
            is RenderPlan.Entry.Clip -> plan.pictures[entry.index].let { heard ->
                addItem(
                    EditedMediaItem.Builder(clippedItem(heard, clean[heard.index] ?: files.getValue(heard.sourceID))).setRemoveVideo(true)
                        .setEffects(Effects(listOf<AudioProcessor>(SoundProcessor(plan.clipEnvelope(heard.index), heard.start)), emptyList()))
                        .apply { steadySpeed(heard.rate)?.let(::setSpeed) }
                        .build(),
                )
            }
        }
    }.build()
    val heard = plan.soundtracks + plan.voices { voiced[it] == true }
    val silence = listOfNotNull((plan.duration * 1e6).roundToLong().takeIf { it > 0 }?.let { RenderPlan.Entry.Gap(0.0, it) })
    return Composition.Builder(heard.map { sequence(it.entries) }.ifEmpty { listOf(sequence(silence)) }).build()
}
