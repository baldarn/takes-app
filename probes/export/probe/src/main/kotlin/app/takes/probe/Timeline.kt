@file:OptIn(UnstableApi::class)

package app.takes.probe

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import java.io.File

/** A stretch of a recording on one of the two decks. Times are microseconds; [source] is where it starts in its file. */
class Piece(val deck: Int, val file: File, val source: Long, val start: Long, val length: Long) {
    val end: Long get() = start + length
}

/** A movie small enough to read: pieces on two decks, as the app lays out chains that overlap, and music under all of it. */
class Plan(val pieces: List<Piece>, val total: Long, val music: File?) {
    fun playing(deck: Int, time: Long): Boolean = pieces.any { it.deck == deck && time >= it.start && time < it.end }
}

/** What a composition carries. */
enum class Tracks { Both, Video, Audio }

/** How a composition gets its frame times. */
sealed interface Timing {
    /** As the app's exporter does today: the first deck is Media3's primary sequence, and the movie takes its frame times. */
    data object Decks : Timing

    /**
     * A sequence of its own before the decks, holding one small still for the whole stretch at the movie's
     * frame rate. Media3 composes one frame for every frame of its first sequence, so the movie gets
     * exactly the frames of the grid, whatever the decks hold.
     */
    class Clock(val still: File, val framesPerSecond: Int, val frames: Int) : Timing
}

/**
 * What stands where a deck has no clip. Media3's own gap gives frames of 16 by 16 pixels, while a
 * clip is fitted to the movie's size; the change of size makes the deck's processor throw away the
 * textures the compositor is still waiting to draw (docs/EXPORT-TWO-SEQUENCES.md). A filler is a
 * black [still] shown for the stretch and fitted like a clip, so the size never changes.
 */
class Filler(val still: File, val framesPerSecond: Int, val later: Long = 0)

object Compositions {
    /** A still picture shown for [length] microseconds, a frame every 1 / [framesPerSecond] s. */
    fun still(file: File, length: Long, framesPerSecond: Int, effects: List<Effect>): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(maxOf(1, length / 1000)).build())
            .setDurationUs(length)
            .setFrameRate(framesPerSecond)
            .setEffects(Effects(emptyList(), effects))
            .build()

    /**
     * The composition of [plan] between [from] and [to]: every piece cut to the stretch, gaps where a deck
     * is empty, and the time-dependent effects told where the stretch begins.
     */
    fun of(plan: Plan, from: Long, to: Long, canvas: Size, timing: Timing, tracks: Tracks, overlayRate: Int?, filler: Filler? = null): Composition {
        val deckTypes = when (tracks) {
            Tracks.Both -> setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
            Tracks.Video -> setOf(C.TRACK_TYPE_VIDEO)
            Tracks.Audio -> setOf(C.TRACK_TYPE_AUDIO)
        }
        // One for every item: a Presentation keeps the matrix it worked out for the size of its last input, so
        // one shared by a clip and a still, or by two decks, draws one with the matrix of the other.
        fun fit() = listOfNotNull<Effect>(
            Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT),
            filler?.later?.takeIf { it > 0 }?.let(::LaterEffect),
        )
        val decks = (0..1).map { deck ->
            val sequence = EditedMediaItemSequence.Builder(deckTypes)
            var filled = from
            fun empty(length: Long) {
                if (filler == null || tracks == Tracks.Audio) sequence.addGap(length) else sequence.addItem(still(filler.still, length, filler.framesPerSecond, fit()))
            }
            for (piece in plan.pieces.filter { it.deck == deck && it.end > from && it.start < to }.sortedBy { it.start }) {
                val begins = maxOf(piece.start, from)
                val ends = minOf(piece.end, to)
                if (begins > filled) empty(begins - filled)
                val item = MediaItem.Builder().setUri(Uri.fromFile(piece.file)).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(piece.source + begins - piece.start)
                        .setEndPositionUs(piece.source + ends - piece.start)
                        .build(),
                ).build()
                sequence.addItem(
                    EditedMediaItem.Builder(item)
                        .setRemoveAudio(tracks == Tracks.Video)
                        .setRemoveVideo(tracks == Tracks.Audio)
                        .setEffects(Effects(emptyList(), if (tracks == Tracks.Audio) emptyList() else fit()))
                        .build(),
                )
                filled = ends
            }
            if (to > filled) empty(to - filled)
            sequence.build()
        }
        val clock = (timing as? Timing.Clock)?.takeIf { tracks != Tracks.Audio }?.let { clock ->
            val length = Math.round(clock.frames * 1e6 / clock.framesPerSecond)
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(still(clock.still, length, clock.framesPerSecond, emptyList())).build()
        }
        val music = plan.music?.takeIf { tracks != Tracks.Video }?.let { file ->
            val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionUs(from).setEndPositionUs(to).build(),
            ).build()
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(EditedMediaItem.Builder(item).setRemoveVideo(true).build()).build()
        }
        val builder = Composition.Builder(listOfNotNull(clock) + decks + listOfNotNull(music))
        if (tracks != Tracks.Audio) {
            val drawn = listOfNotNull<Effect>(
                SplitEffect(plan, from, canvas),
                overlayRate?.let { OverlayEffect(listOf(CounterOverlay(it, from, canvas))) },
            )
            builder.setVideoCompositorSettings(StackCompositor(canvas, clock != null)).setEffects(Effects(emptyList(), drawn))
        }
        return builder.build()
    }
}
