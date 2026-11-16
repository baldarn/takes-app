@file:OptIn(UnstableApi::class)

package app.takes

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.takes.core.LayerPlan
import app.takes.core.PicturePlayback
import java.io.File
import kotlin.math.abs

/**
 * Plays an edit drawn by picture layers ([LayerPlan]) on the preview's
 * [lanes], one player a lane, as the export draws it from one sequence a
 * lane: each player holds the pictures of its lane in the order they play,
 * with their sound, and is standing on the next one where its lane plays
 * nothing. Nothing about the edit is decided here.
 *
 * The clock is the lane whose picture began last, as for a sequence the deck
 * that began last; where no picture plays the time runs on by itself. A lane
 * stops by itself at the end of a run of pictures, on its last frame, which
 * a still or a transition may go on showing. A still whose picture plays on
 * under it for its sound shows the picture moving here, not the frame it
 * holds: that the export draws.
 */
internal class LanePlayback(private val lanes: List<ExoPlayer>, val plan: LayerPlan, private val speed: () -> Double) {
    /** The pictures each lane holds, by index, in the order they play. */
    private val items = List(lanes.size) { lane -> plan.pictures.filter { it.lane == lane }.sortedWith(compareBy<LayerPlan.Picture> { it.start }.thenBy { it.index }).map { it.index } }

    /** What the players hold: two plans that give the same need no new playlists. */
    val media: Any = items to plan.pictures.map { listOf(it.id, it.sourceID, it.sourceStartUs, it.sourceEndUs, it.rate) }

    private val pictures get() = plan.pictures
    private var time = 0.0

    /** Gives each player its lane's pictures, unless it holds them already ([previous] is what they hold). */
    fun prepare(files: Map<String, File>, previous: Any?) {
        if (previous == media) return
        lanes.forEachIndexed { lane, player ->
            if (items[lane].isEmpty()) { if (player.mediaItemCount > 0) player.clearMediaItems(); return@forEachIndexed }
            player.setMediaItems(items[lane].map { clippedItem(pictures[it], files.getValue(pictures[it].sourceID)) })
            player.prepare()
        }
    }

    /** The picture the player of [lane] is on now. */
    private fun current(lane: Int): Int? = items[lane].getOrNull(lanes[lane].currentMediaItemIndex)

    /** The frame [lane] holds at [time] though its picture may be over: that of a still. */
    private fun held(lane: Int, time: Double): LayerPlan.Freeze? = plan.freezes.firstOrNull { it.lane == lane && time >= it.from && time < it.until }

    /** The next picture of [lane] after [time], where its player waits. */
    private fun coming(lane: Int, time: Double): Int? = items[lane].firstOrNull { pictures[it].start > time }

    /** Whether the run of pictures on [lane] ends with [picture]: the next on the lane does not follow straight on. */
    private fun endsRun(lane: Int, picture: Int): Boolean = plan.endings.any { it.deck == lane && abs(it.at - pictures[picture].end) < 1e-6 }

    /** Plays the lane at its picture's speed where the player is in it: a retimed picture changes speed as it goes. */
    private fun pace(player: ExoPlayer, picture: Int?) {
        val at = player.currentPosition.coerceAtLeast(0) / 1000.0
        val rate = ((picture?.let { PicturePlayback.rate(pictures[it].spans, at) } ?: 1.0) * speed()).toFloat()
        if (player.playbackParameters.speed != rate) player.playbackParameters = PlaybackParameters(rate)
    }

    private fun stopAtRunEnd(lane: Int) {
        val player = lanes[lane]
        val last = current(lane)?.let { endsRun(lane, it) } == true
        if (player.pauseAtEndOfMediaItems != last) player.pauseAtEndOfMediaItems = last
    }

    private fun position(picture: LayerPlan.Picture, offset: Double): Long = (picture.position(offset.coerceIn(0.0, picture.duration)) * 1000).toLong()

    /** Puts every lane where [time] needs it, and returns the time, inside the movie. */
    fun seek(time: Double, starting: Boolean = false): Double {
        this.time = time.coerceIn(0.0, plan.duration)
        val at = this.time
        for (lane in lanes.indices) {
            val player = lanes[lane]
            if (items[lane].isEmpty()) continue
            val on = plan.onLane(lane, at)
            val frozen = held(lane, at)
            when {
                frozen != null && (on == null || (on.index == frozen.picture && !starting)) ->
                    player.seekTo(items[lane].indexOf(frozen.picture), position(pictures[frozen.picture], frozen.offset))
                on != null -> player.seekTo(items[lane].indexOf(on.index), position(on, at - on.start))
                else -> coming(lane, at)?.let { player.seekTo(items[lane].indexOf(it), 0) }
            }
            pace(player, current(lane))
            stopAtRunEnd(lane)
        }
        return this.time
    }

    /** Starts the lanes that play at the time last sought to. */
    fun start() {
        for (lane in lanes.indices) if (items[lane].isNotEmpty() && plan.onLane(lane, time) != null) lanes[lane].play()
    }

    /** Advances the lanes, [passed] seconds after the last call, and returns the movie's time. */
    fun tick(passed: Double): Double {
        val leading = lanes.indices.mapNotNull { lane -> plan.onLane(lane, time)?.let { lane to it } }.maxByOrNull { it.second.start }
        time = if (leading == null) time + passed * speed() else {
            val (lane, picture) = leading
            val player = lanes[lane]
            val on = current(lane)
            when {
                on == null -> time + passed * speed()
                // A player that has gone on to the next picture of its run: the time is that picture's.
                on != picture.index -> if (pictures[on].start >= picture.start) pictures[on].start + pictures[on].offset(player.currentPosition.coerceAtLeast(0) / 1000.0) else time + passed * speed()
                player.playbackState == Player.STATE_ENDED -> picture.end
                !player.playWhenReady && player.playbackState == Player.STATE_READY && player.currentPosition >= player.duration - 30 -> picture.end
                else -> (picture.start + picture.offset(player.currentPosition.coerceAtLeast(0) / 1000.0)).coerceAtMost(picture.end)
            }
        }.coerceAtMost(plan.duration)
        for (lane in lanes.indices) {
            if (items[lane].isEmpty()) continue
            val player = lanes[lane]
            val on = plan.onLane(lane, time)
            val now = current(lane)
            if (on != null) {
                // A picture whose moment has come starts from where its lane was standing.
                if (now != on.index) player.seekTo(items[lane].indexOf(on.index), position(on, time - on.start))
                val atEnd = now == on.index && endsRun(lane, on.index) && player.currentPosition >= player.duration - 30
                if (!player.playWhenReady && !atEnd) player.play()
                pace(player, current(lane))
            } else if (held(lane, time) == null) {
                // A lane that has finished waits on its next picture, once its last frame is no longer shown.
                val next = coming(lane, time)
                if (next != null && now != next) {
                    player.pause()
                    player.seekTo(items[lane].indexOf(next), 0)
                } else if (next == null && player.playWhenReady) player.pause()
            }
            stopAtRunEnd(lane)
        }
        return time
    }
}
