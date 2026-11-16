package app.takes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.abs

/** The edit as a track timeline. Pure: the same state gives an equal timeline every time, on any device. */
fun EditState.toTimeline(): TrackTimeline = TrackConversion.timeline(this)

/**
 * From the sequence of clips with two sound tracks that a project saves today
 * ([EditState]) to the original's track timeline.
 *
 * The original's rule is `TrackTimeline(clips:effects:)`
 * (YouRec/TrackTimeline.swift:510-534): lay the clips out as a sequence, give
 * each picture the start the layout found, give each clip a sound with a
 * derived id and a link, turn each transition into a relation with the
 * layout's factor. What is added here is what its old format never had:
 * clips that are not cut on a grid, a clip's own level, fades and noise
 * reduction, sound items, unlinked sound, bookmarks and words on clips, and
 * openings and closes that stayed on a clip that is no longer first or last.
 *
 * The layout is [TimelineEditing.layout], the one the editor, the preview and
 * the export use, so no picture moves by more than half a tick.
 */
object TrackConversion {
    /** The model the app's noise reduction runs, which a clip's `denoise` stands for. */
    private val noiseReduction = AudioEffectKind.VoiceIsolation

    // How the fades of the sequence form (on `ClipAudio`) and the fades of the timeline stand for each other.
    // These two functions are the only place that knows; a change to how a clip's sound stores its fades changes them alone.

    /**
     * The timeline's fades for those of a clip or a sound item. Both forms
     * keep the original's fade, its whole length and how much of it a cut
     * has left behind the edge ([ClipAudio.fades]), so nothing is worked out:
     * a fade longer than its item, or cut by a split, is the same fade there.
     */
    internal fun fades(sound: ClipAudio): AudioEdgeFades = sound.fades

    /** [sound] with a timeline's fades, as the sequence form saves them: the way back from [fades], without loss. */
    internal fun withFades(sound: ClipAudio, fadeIn: AudioEdgeFade?, fadeOut: AudioEdgeFade?): ClipAudio =
        sound.withFades(AudioEdgeFades(fadeIn, fadeOut))

    fun timeline(state: EditState): TrackTimeline {
        // An edit that is not a sequence is its timeline, with what a tool wrote into its view.
        state.layered?.let { return TrackEditState.merged(state, it) }
        val clips = state.clips
        val layout = TimelineEditing.layout(clips)
        val count = clips.size
        val places = layout.placements

        // Boundaries first. The original rounds each start and each length to
        // ticks by itself; on cuts that are not on a grid that leaves one in
        // four a tick apart, and a cut a tick apart is not a cut to the model.
        // So a start is the layout's, rounded, and a length is the distance to
        // the boundary the picture must meet.
        val starts = LongArray(count) { EditTime.of(places[it].start).ticks }
        val lengths = LongArray(count) { index ->
            val natural = EditTime.of(places[index].duration).ticks
            if (index == count - 1) return@LongArray natural
            val join = layout.joins[index + 1]
            val distance = starts[index + 1] - starts[index]
            when {
                // Across a transition: where the next picture begins, back by where it begins in the transition, on by where this one ends in it.
                join.transition != null -> distance - EditTime.of(join.timing.rightStart).ticks + EditTime.of(join.timing.leftEnd).ticks
                // A plain cut: the two meet on one tick.
                abs(places[index + 1].start - places[index].end) < TransitionLayout.TICK / 2 -> distance
                // Time of its own after it (a close that reaches past the clip, an opening that begins ahead of the next).
                else -> minOf(natural, distance)
            }
        }

        val video = clips.mapIndexed { index, clip ->
            VideoTimelineItem(
                occurrenceID = clip.id,
                start = EditTime(starts[index]),
                content = clip.copy(
                    transition = null, openingTransition = null, transitionFit = null, openingTransitionFit = null,
                    audio = null, bookmarks = null, subtitleWords = null,
                ),
                layer = index,
                mapping = SourcePlaybackMap.of(clip, EditTime(lengths[index])),
            )
        }

        // A muted clip with a sound item under its id is a clip whose sound was unlinked (AudioUnlinking).
        val items = state.audio.orEmpty()
        val clipIDs = clips.map { it.id }.toSet()
        val unlinked = clips.mapNotNull { clip ->
            if (clip.audio?.muted != true) return@mapNotNull null
            items.firstOrNull { it.id == clip.id && it.sourceID == clip.sourceID }?.let { clip.id to it }
        }.toMap()

        val audio = mutableListOf<AudioTimelineItem>()
        val links = mutableListOf<TimelineLink>()
        clips.forEachIndexed { index, clip ->
            val id = TrackTimeline.counterpart(clip.id)
            val detached = unlinked[clip.id]
            if (detached != null) {
                // The sound keeps its own place, cut, level and fades; what the muted clip remembered for relinking is not carried.
                audio += free(detached, id, clip.id).copy(subtitleWords = clip.subtitleWords)
                return@forEachIndexed
            }
            val sound = clip.audio ?: ClipAudio()
            audio += AudioTimelineItem(
                id = id, occurrenceID = clip.id, sourceID = clip.sourceID, start = video[index].start, mapping = video[index].mapping,
                followsVideo = clip.id, volumeDB = sound.volumeDB, muted = sound.muted,
                fadeIn = fades(sound).fadeIn, fadeOut = fades(sound).fadeOut,
                // The shape the original gives a chain for the whole project: one region over the whole item, under the item's id.
                enhancements = if (sound.denoise > 0) {
                    listOf(AudioEnhancementRegion(id, EditInterval(EditTime.ZERO, video[index].mapping.duration), listOf(AudioEffect.make(noiseReduction, id).copy(amount = sound.denoise))))
                } else emptyList(),
                subtitleWords = clip.subtitleWords,
            )
            links += TimelineLink(clip.id, setOf(clip.id, id))
        }
        for (item in items) {
            if (unlinked[item.id] === item) continue
            // Ids are unique across pictures and sounds in the model; today only among clips and among items.
            val id = if (item.id in clipIDs) TrackTimeline.alias(item.id) else item.id
            audio += free(item, id, id)
        }

        val transitions = layout.all.mapNotNull { join ->
            val settings = join.transition ?: return@mapNotNull null
            val timing = join.timing
            // Where the prune puts it: by the end of the picture it leads out of, or by the start of the one it opens.
            val origin = join.outgoing?.takeIf { join.incoming == null }?.let { starts[it] + lengths[it] - EditTime.of(timing.leftEnd).ticks }
                ?: (starts[join.incoming!!] - EditTime.of(timing.rightStart).ticks)
            TimelineTransition(join.outgoing?.let { clips[it].id }, join.incoming?.let { clips[it].id }, settings, EditTime(origin), join.factor)
        }

        val bookmarks = clips.flatMap { clip ->
            clip.bookmarks.orEmpty().map { TimelineBookmark(it.id, it.label ?: "", BookmarkAnchor.Source(clip.id, clip.sourceID, it.time)) }
        }

        val result = TrackTimeline(video, audio, links, bookmarks, emptyList(), transitions, transitions.map { it.generated })
        val subtitles = state.subtitles ?: return result
        // The saved fingerprint was made by today's formula. Where it still matched, the model's is written; otherwise
        // none, so regrouping stays refused exactly where it was.
        val matched = subtitles.timingFingerprint == SubtitleEditing.fingerprint(clips)
        return result.copy(subtitles = subtitles.copy(timingFingerprint = if (matched) timingFingerprint(result) else ""))
    }

    /** A sound item as a sound of the timeline that follows no picture: its place and its end rounded as boundaries. */
    private fun free(item: AudioItem, id: String, occurrence: String): AudioTimelineItem {
        val start = EditTime.of(item.start)
        val sound = item.audio ?: ClipAudio()
        return AudioTimelineItem(
            id = id, occurrenceID = occurrence, sourceID = item.sourceID, start = start,
            mapping = SourcePlaybackMap.single(EditTime.of(item.end) - start, item.sourceStart, item.sourceEnd),
            volumeDB = sound.volumeDB, muted = sound.muted,
            fadeIn = fades(sound).fadeIn, fadeOut = fades(sound).fadeOut,
        )
    }

    @Serializable
    private class Timing(val id: String, val mapping: SourcePlaybackMap, val muted: Boolean, val source: String, val start: EditTime)

    /**
     * The structure captions were made for: which sound and which picture
     * plays where, with what timing, and whether it is heard. Color, framing,
     * level and caption edits do not change it. The original's
     * `subtitleTimingFingerprint` (YouRec/Subtitles.swift:183-189): the same
     * fields in the same order, sounds first. The bytes hashed are this
     * port's own JSON, not Swift's.
     */
    fun timingFingerprint(timeline: TrackTimeline): String {
        val sound = timeline.audio.map { Timing(it.id, it.mapping, it.muted, it.sourceID, it.start) }
        val picture = timeline.video.map { Timing(it.id, it.mapping, false, it.content.sourceID, it.start) }
        return sha256(documentJson.encodeToString(ListSerializer(Timing.serializer()), sound + picture).toByteArray())
    }
}
