@file:OptIn(UnstableApi::class)

package app.takes.probe

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import app.takes.DecksCompositor
import app.takes.TransitionEffect
import app.takes.core.FrameCeiling
import app.takes.core.Rect
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The composition the app's exporter makes of a list of clips, rebuilt here line for line from
 * `Exporter.start` so the probe can run it without the app: the same clipping, the same gaps, the
 * same two sequences, and the app's own `TransitionEffect` and `DecksCompositor`, compiled into the
 * probe from the app's source file. Titles, colour, crop, speed and the shaping of the sound are
 * left out; none of them decides which frames a movie holds.
 *
 * With [clock] the two changes the probe proposes are made: a sequence before the decks that holds
 * a still for the whole movie at the movie's frame rate, and a black still fitted to the movie's
 * size wherever a deck has no clip, in place of Media3's gap, each item fitted by an effect of its own.
 */
object AppMirror {
    /** The app's `FrameCeilingEffect`, copied: it lives in `Exporter.kt`, which cannot be compiled apart from the app. */
    private class CeilingEffect(private val framesPerSecond: Int) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
            private val ceiling = FrameCeiling(framesPerSecond.toDouble())

            override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
                if (ceiling.keeps(presentationTimeUs)) super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs) else {
                    inputListener.onInputFrameProcessed(inputTexture)
                    inputListener.onReadyToAcceptInputFrame()
                }
            }

            override fun signalEndOfCurrentInputStream() { super.signalEndOfCurrentInputStream(); ceiling.reset() }
            override fun flush() { super.flush(); ceiling.reset() }
        }
    }

    fun composition(clips: List<TimelineClip>, files: List<File>, footage: List<Double>, canvas: Size, ceiling: Int, clock: Timing.Clock?, later: Long = 0): Composition {
        val frames = { join: TransitionLayout.Join -> clock?.framesPerSecond?.toDouble() ?: TransitionScene.frameRate(clips, join, footage, ceiling) }
        // The app fits every clip with one Presentation. It keeps the matrix it worked out for the size of its
        // last input, so beside a still of another size each item needs its own.
        val shared = Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT)
        fun fit() = if (clock == null) shared else Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT)
        val items = clips.mapIndexed { index, clip ->
            val picture = buildList<Effect> {
                // The clock decides which frame of the footage each frame of the movie shows; nothing is thinned before it.
                if (clock == null && ceiling <= 30) add(CeilingEffect(ceiling))
                add(fit())
                if (clock != null && later > 0) add(LaterEffect(later))
            }
            val item = MediaItem.Builder().setUri(Uri.fromFile(files[index])).setMediaId(clip.id).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(floor(clip.start * 1e6).toLong())
                    .setEndPositionUs(floor(clip.end * 1e6).toLong())
                    .build(),
            ).build()
            EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), picture)).build()
        }
        val total = TimelineEditing.duration(clips)
        val chains = TimelineEditing.chains(clips)
        val whole = Rect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
        fun drawn(decks: Int) = TransitionEffect(clips, canvas, decks, frames) { whole }
        val timer = clock?.let {
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                .addItem(Compositions.still(it.still, Math.round(it.frames * 1e6 / it.framesPerSecond), it.framesPerSecond, emptyList())).build()
        }
        if (chains.size == 1 && timer == null) {
            val chain = chains[0]
            val joined = TimelineEditing.transitions(clips).isNotEmpty()
            val sequence = if (chain.start < 0.0005 && total - chain.end < 0.0005) EditedMediaItemSequence.withAudioAndVideoFrom(items) else {
                val padded = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                ((chain.start * 1e6).roundToLong()).takeIf { it > 0 }?.let(padded::addGap)
                items.forEach(padded::addItem)
                (((total - chain.end) * 1e6).roundToLong()).takeIf { it > 0 }?.let(padded::addGap)
                padded.build()
            }
            return Composition.Builder(listOf(sequence)).setEffects(if (joined) Effects(emptyList(), listOf<Effect>(drawn(1))) else Effects.EMPTY).build()
        }
        val sequences = List(2) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)) }
        val filled = DoubleArray(2)
        fun gap(deck: Int, until: Double) {
            val micros = ((until - filled[deck]) * 1e6).roundToLong()
            if (micros <= 0) return
            if (clock == null) sequences[deck].addGap(micros) else sequences[deck].addItem(Compositions.still(clock.still, micros, clock.framesPerSecond, listOfNotNull(fit(), LaterEffect(later).takeIf { later > 0 })))
        }
        chains.forEachIndexed { index, chain ->
            gap(index % 2, chain.start)
            for (clip in chain.first..chain.last) sequences[index % 2].addItem(items[clip])
            filled[index % 2] = chain.end
        }
        for (deck in 0..1) gap(deck, total)
        return Composition.Builder(listOfNotNull(timer) + sequences.map { it.build() })
            .setVideoCompositorSettings(if (timer == null) DecksCompositor(canvas) else StackCompositor(canvas, clock = true))
            .setEffects(Effects(emptyList(), listOf<Effect>(drawn(2))))
            .build()
    }
}
