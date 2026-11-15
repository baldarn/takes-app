package app.takes.core

import kotlin.math.abs

/** Refuses a timeline a project must not hold. See [TrackInvariants.violation]. */
fun TrackTimeline.validate(sources: List<SourceMedia>) = TrackInvariants.validate(this, sources)

/** The timeline with its relations brought back in line with its items. See [TrackInvariants.pruned]. */
fun TrackTimeline.pruned(): TrackTimeline = TrackInvariants.pruned(this)

/**
 * What a track timeline must satisfy, and the repair every edit ends with.
 *
 * Two different things. [violation] is what a saved timeline is refused for:
 * it cannot be played or edited as it stands. [pruned] is not a refusal: gaps,
 * overlaps, layer numbers, a placement whose pictures no longer overlap and a
 * transition whose pictures no longer meet are all allowed while an edit is
 * being made, and the prune drops or refits the relations that no longer hold.
 * A timeline at rest is one the prune leaves as it is.
 */
object TrackInvariants {
    /** The first rule a timeline breaks, in the order the original checks them. */
    enum class Violation {
        /** Two items, picture or sound, share an id. */
        DuplicateItem,
        /** The timeline is longer than a week. */
        Duration,
        /** An item starts before zero. */
        Start,
        /** A map has no runs, a run takes no time, or it reads outside its source (or its source is not in the project). */
        Mapping,
        /** A sound's level is not a number from −60 to 12 dB. */
        Level,
        Fade,
        /** A link has under two members, names an item that is not there, or shares one with another link. */
        Link,
        Subtitles,
        /** Over 200 000 words on a sound, two words with one id, an empty or overlong text, or times outside the source. */
        Words,
        /** Two links, two bookmarks, two placements or two transitions share an id. */
        DuplicateRelation,
        /** A placement names under two pictures or one that is not there, lacks a window for one, or has a window out of range. */
        Placement,
        /** A picture has two transitions in, or two out. */
        TransitionSlot,
        /** A transition names no picture or one that is not there, or has settings or a factor out of range. */
        Transition,
        /** A label over 500 characters, or an anchor outside its source or outside a week. */
        Bookmark,
        /** A sound follows a picture it is not linked to, or one with another start or length. */
        Follower,
        Censor,
        /** Chains out of order, overlapping, outside the item, empty, or holding an invalid stage or two with one id. */
        Enhancement,
    }

    const val MAXIMUM_WORDS = 200_000
    const val MAXIMUM_LABEL = 500

    /** A source may be read this far past its length: the slack of its measured duration. */
    private const val SOURCE_SLACK = 0.001

    /**
     * Null for a timeline a project may hold; otherwise the first rule it
     * breaks. The original's `validate(sources:)`
     * (YouRec/TrackTimeline.swift:1175-1248), rule for rule and in its order.
     */
    fun violation(timeline: TrackTimeline, sources: List<SourceMedia>): Violation? {
        val video = timeline.video
        val audio = timeline.audio
        val ids = video.map { it.id } + audio.map { it.id }
        val all = ids.toSet()
        if (all.size != ids.size) return Violation.DuplicateItem
        val duration = timeline.duration
        if (duration < EditTime.ZERO || duration.seconds > EditTime.LIMIT) return Violation.Duration
        val lengths = HashMap<String, Double>()
        for (source in sources) lengths.putIfAbsent(source.id, source.duration)
        fun valid(map: SourcePlaybackMap, source: String): Boolean {
            val length = lengths[source] ?: return false
            return map.runs.isNotEmpty() && map.runs.all {
                it.duration > EditTime.ZERO && it.sourceStart.isFinite() && it.sourceEnd.isFinite() &&
                    it.sourceStart >= 0 && it.sourceEnd >= it.sourceStart && it.sourceEnd <= length + SOURCE_SLACK
            }
        }
        for (item in video) {
            if (item.start < EditTime.ZERO) return Violation.Start
            if (!valid(item.mapping, item.content.sourceID)) return Violation.Mapping
        }
        for (item in audio) {
            if (item.start < EditTime.ZERO) return Violation.Start
            if (!valid(item.mapping, item.sourceID)) return Violation.Mapping
            if (!item.volumeDB.isFinite() || item.volumeDB !in ClipAudio.MINIMUM_DB..ClipAudio.MAXIMUM_DB) return Violation.Level
            if (item.fadeIn?.valid == false || item.fadeOut?.valid == false) return Violation.Fade
        }
        val linked = HashSet<String>()
        for (link in timeline.links) {
            if (link.members.size < 2 || !all.containsAll(link.members) || link.members.any { it in linked }) return Violation.Link
            linked += link.members
        }
        if (timeline.subtitles?.valid == false) return Violation.Subtitles
        for (item in audio) {
            val words = item.subtitleWords ?: continue
            val length = lengths[item.sourceID] ?: 0.0
            if (words.size > MAXIMUM_WORDS || words.map { it.id }.toSet().size != words.size) return Violation.Words
            if (words.any { !it.valid || it.end > length + SOURCE_SLACK }) return Violation.Words
        }
        fun unique(ids: List<String>) = ids.toSet().size == ids.size
        if (!unique(timeline.links.map { it.id }) || !unique(timeline.bookmarks.map { it.id }) ||
            !unique(timeline.placements.map { it.id }) || !unique(timeline.transitions.map { it.id })) return Violation.DuplicateRelation
        val pictures = video.map { it.id }.toSet()
        for (placement in timeline.placements) {
            if (placement.participants.size < 2 || !pictures.containsAll(placement.participants) || placement.videos.keys != placement.participants) return Violation.Placement
            if (placement.videos.values.any { !it.valid }) return Violation.Placement
        }
        if (!unique(timeline.transitions.mapNotNull { it.incoming }) || !unique(timeline.transitions.mapNotNull { it.outgoing })) return Violation.TransitionSlot
        for (relation in timeline.transitions) {
            val participants = listOfNotNull(relation.outgoing, relation.incoming)
            if (participants.isEmpty() || !pictures.containsAll(participants) || !relation.settings.valid ||
                !relation.factor.isFinite() || relation.factor !in 0.0..1.0) return Violation.Transition
        }
        for (bookmark in timeline.bookmarks) {
            if (bookmark.label.length > MAXIMUM_LABEL) return Violation.Bookmark
            val inside = when (val anchor = bookmark.anchor) {
                is BookmarkAnchor.Source -> lengths[anchor.source]?.let { anchor.time.isFinite() && anchor.time >= 0 && anchor.time <= it + SOURCE_SLACK } ?: false
                is BookmarkAnchor.Local -> anchor.offset >= EditTime.ZERO && anchor.offset.seconds <= EditTime.LIMIT
                is BookmarkAnchor.Project -> anchor.time >= EditTime.ZERO && anchor.time.seconds <= EditTime.LIMIT
            }
            if (!inside) return Violation.Bookmark
        }
        val byID = HashMap<String, VideoTimelineItem>()
        for (item in video) byID.putIfAbsent(item.id, item)
        val links = linksByMember(timeline.links)
        for (item in audio) {
            item.followsVideo?.let { follows ->
                val picture = byID[follows] ?: return Violation.Follower
                if (links[follows].orEmpty().none { item.id in it.members }) return Violation.Follower
                if (picture.start != item.start || picture.mapping.duration != item.mapping.duration) return Violation.Follower
            }
            if (!AudioCensor.valid(item.censors, item.mapping.duration)) return Violation.Censor
            var end = EditTime.ZERO
            for (region in item.enhancements.sortedBy { it.interval.start }) {
                if (region.interval.start < end || region.interval.end > item.mapping.duration || region.interval.isEmpty ||
                    !AudioChain.valid(region.effects)) return Violation.Enhancement
                end = region.interval.end
            }
        }
        return null
    }

    /** Throws the project's "invalid editing data" for a timeline that breaks a rule. */
    fun validate(timeline: TrackTimeline, sources: List<SourceMedia>) {
        if (violation(timeline, sources) != null) throw ProjectException(ProjectException.Reason.InvalidDocument)
    }

    private fun linksByMember(links: List<TimelineLink>): Map<String, List<TimelineLink>> {
        val result = HashMap<String, MutableList<TimelineLink>>()
        for (link in links) for (member in link.members) result.getOrPut(member) { mutableListOf() } += link
        return result
    }

    /**
     * One transition per slot: a picture has one transition in and one out,
     * and of two that claim the same slot the one added last stays. Stretches
     * whose transition is gone go with it.
     * YouRec/TrackTimeline.swift:836-846.
     */
    fun retainingUniqueTransitionSlots(timeline: TrackTimeline): TrackTimeline {
        val incoming = HashSet<String>()
        val outgoing = HashSet<String>()
        val kept = timeline.transitions.asReversed().filter { relation ->
            if ((relation.incoming != null && relation.incoming in incoming) || (relation.outgoing != null && relation.outgoing in outgoing)) return@filter false
            relation.incoming?.let { incoming += it }
            relation.outgoing?.let { outgoing += it }
            true
        }.asReversed()
        val owners = kept.map { it.id }.toSet()
        return timeline.copy(transitions = kept, generated = timeline.generated.filter { it.owner in owners })
    }

    /**
     * What every edit ends with (YouRec/TrackTimeline.swift:847-899):
     *
     *  - a link loses the members that are gone and goes when under two are left;
     *  - a placement goes when one of its pictures is gone or they no longer all play at once;
     *  - a transition goes when a picture it names is gone. Between two
     *    pictures, when they no longer overlap as its factor says, the factor
     *    is found again from how far they do overlap; it goes when no factor
     *    gives that overlap, when a picture has less footage than it costs, or
     *    when the incoming picture is not where it needs it. Its origin is
     *    written from the pictures: by the end of the one it leads out of, or
     *    by the start of the one it opens;
     *  - one transition per slot ([retainingUniqueTransitionSlots]);
     *  - the stretches are written again from the transitions;
     *  - a sound stops following a picture that no link holds it with.
     */
    fun pruned(timeline: TrackTimeline): TrackTimeline {
        val ids = timeline.allIDs
        val links = timeline.links.mapNotNull { link ->
            val members = link.members.filterTo(LinkedHashSet()) { it in ids }
            if (members.size < 2) null else if (members.size == link.members.size) link else link.copy(members = members)
        }
        val pictures = HashMap<String, VideoTimelineItem>()
        for (item in timeline.video) pictures.putIfAbsent(item.id, item)
        val placements = timeline.placements.filter { region ->
            val members = region.participants.mapNotNull { pictures[it] }
            members.size == region.participants.size && members.size > 1 && members.maxOf { it.start } < members.minOf { it.end }
        }
        val transitions = timeline.transitions.mapNotNull { saved ->
            val a = saved.outgoing?.let { pictures[it] }
            val b = saved.incoming?.let { pictures[it] }
            if ((saved.outgoing != null && a == null) || (saved.incoming != null && b == null)) return@mapNotNull null
            if (a != null && b != null) {
                val required = (a.end - b.start).seconds
                var factor = saved.factor
                if (abs(TransitionTiming.of(saved.settings, factor).overlap - required) > 2.0 / EditTime.SCALE) {
                    val low = TransitionTiming.of(saved.settings, 0.0).overlap
                    val high = TransitionTiming.of(saved.settings, 1.0).overlap
                    if (required < minOf(low, high) - HANDLE_SLACK || required > maxOf(low, high) + HANDLE_SLACK) return@mapNotNull null
                    var lo = 0.0
                    var hi = 1.0
                    repeat(TransitionLayout.FIT_STEPS) {
                        val mid = (lo + hi) / 2
                        if ((TransitionTiming.of(saved.settings, mid).overlap < required) == (high >= low)) lo = mid else hi = mid
                    }
                    factor = (lo + hi) / 2
                }
                val timing = TransitionTiming.of(saved.settings, factor)
                if (timing.leftCost > a.mapping.duration.seconds + HANDLE_SLACK || timing.rightCost > b.mapping.duration.seconds + HANDLE_SLACK) return@mapNotNull null
                val origin = a.end - EditTime.of(timing.leftEnd)
                if (abs((origin + EditTime.of(timing.rightStart) - b.start).ticks) > 2) return@mapNotNull null
                saved.copy(origin = origin, factor = factor)
            } else {
                val timing = saved.timing
                // As the original: by the picture it leads out of, then by the one it opens.
                var origin = saved.origin
                if (a != null) origin = a.end - EditTime.of(timing.leftEnd)
                if (b != null) origin = b.start - EditTime.of(timing.rightStart)
                saved.copy(origin = origin)
            }
        }
        val unique = retainingUniqueTransitionSlots(timeline.copy(transitions = transitions)).transitions
        val byMember = linksByMember(links)
        val audio = timeline.audio.map { item ->
            val follows = item.followsVideo
            if (follows != null && byMember[follows].orEmpty().none { item.id in it.members }) item.copy(followsVideo = null) else item
        }
        return timeline.copy(audio = audio, links = links, placements = placements, transitions = unique, generated = unique.map { it.generated })
    }

    /** True when the prune changes nothing: the relations already agree with the items. */
    fun settled(timeline: TrackTimeline): Boolean = pruned(timeline) == timeline

    private const val HANDLE_SLACK = 0.00001
}
