package app.takes.core

/**
 * How linked groups are stitched on the timeline, from the lanes as they are
 * drawn, after transitions are folded and rows packed: the cuts a group's
 * parts share in a row, the group outlined where it is glued along a row, and
 * the stretches where its picture and sound share the border between a row of
 * pictures and the row of sound under it
 * (YouRec/TrackTimeline.swift:1707-1772 at 57e5d91).
 */
class TimelineLinkLayout(
    /** The lanes it was worked out from, which the timeline draws it on. */
    val lanes: List<TimelineLane>,
    links: List<TimelineLink>,
) {
    /** What a lane shares with its group's other parts. */
    data class Edges(
        /** The part of the group just before it in its row, touching it, and how long that part is. */
        val previous: String? = null,
        val previousDuration: EditTime = EditTime.ZERO,
        /** The part of the group just after it in its row, touching it. */
        val next: String? = null,
        /** Whether all of it shares its upper or lower border with the group's sound or picture. */
        val joinsAbove: Boolean = false,
        val joinsBelow: Boolean = false,
        /** How many parts the group has. */
        val memberCount: Int = 0,
        /** Whether the group is glued along a row, which outlines each of its parts. */
        val outlinedGroup: Boolean = false,
    )

    /** A stretch where group [group]'s picture on row [upperRow] and its sound on the row under it share their border. */
    data class Bridge(val group: String, val upperRow: Int, val interval: EditInterval)

    val edges: Map<String, Edges>
    val bridges: List<Bridge>

    init {
        val edges = HashMap<String, Edges>()
        val bridges = ArrayList<Bridge>()
        if (links.isNotEmpty()) {
            val groups = HashMap<String, String>()
            for (link in links) if (link.members.size > 1) for (member in link.members) {
                groups[member] = link.id
                edges[member] = Edges(memberCount = link.members.size)
            }
            val rows = lanes.groupBy { it.row }.mapValues { (_, row) -> row.sortedBy { it.interval.start } }
            val horizontal = HashSet<String>()
            for ((index, row) in rows) {
                for ((left, right) in row.zipWithNext()) {
                    if (left.interval.end != right.interval.start) continue
                    val group = groups[left.id] ?: continue
                    if (group != groups[right.id]) continue
                    horizontal += group
                    edges[left.id] = edges.getValue(left.id).copy(next = right.id)
                    edges[right.id] = edges.getValue(right.id).copy(previous = left.id, previousDuration = left.interval.duration)
                }
                // Only adjacent picture and sound lanes can share a physical border.
                val lower = rows[index + 1]
                if (row.first().audio || lower == null || !lower.first().audio) continue
                var i = 0
                var j = 0
                val coverage = HashMap<String, EditTime>()
                while (i < row.size && j < lower.size) {
                    val picture = row[i]
                    val sound = lower[j]
                    val group = groups[picture.id]
                    val common = picture.interval.intersection(sound.interval)
                    if (group != null && group == groups[sound.id] && common != null) {
                        coverage[picture.id] = (coverage[picture.id] ?: EditTime.ZERO) + common.duration
                        coverage[sound.id] = (coverage[sound.id] ?: EditTime.ZERO) + common.duration
                        val last = bridges.lastOrNull()
                        if (last != null && last.group == group && last.upperRow == index && last.interval.end == common.start) {
                            bridges[bridges.lastIndex] = last.copy(interval = EditInterval(last.interval.start, common.end))
                        } else {
                            bridges += Bridge(group, index, common)
                        }
                    }
                    if (picture.interval.end <= sound.interval.end) i++
                    if (sound.interval.end <= picture.interval.end) j++
                }
                for (lane in row) if (coverage[lane.id] == lane.interval.duration) edges[lane.id]?.let { edges[lane.id] = it.copy(joinsBelow = true) }
                for (lane in lower) if (coverage[lane.id] == lane.interval.duration) edges[lane.id]?.let { edges[lane.id] = it.copy(joinsAbove = true) }
            }
            for (link in links) if (link.id in horizontal) for (member in link.members) edges[member]?.let { edges[member] = it.copy(outlinedGroup = true) }
        }
        this.edges = edges
        this.bridges = bridges
    }

    companion object {
        /**
         * The stitching of [state] as the editor's timeline draws it: its pictures on their rows ([pictureRows], by id,
         * row 0 when not given) and its sounds of their own, [sounds], on the tracks under them, by their own ids. A
         * picture's own sound is drawn on the picture's card here, so it has no lane: its group counts it, and shares
         * no border on the timeline.
         */
        fun drawn(state: EditState, pictureRows: Map<String, Int>, sounds: List<AudioItem>): TimelineLinkLayout {
            val timeline = state.layered ?: state.toTimeline()
            if (timeline.links.isEmpty()) return TimelineLinkLayout(emptyList(), emptyList())
            val lanes = timeline.lanes()
            val pictures = lanes.filter { !it.audio }.map { it.copy(row = pictureRows[it.id] ?: 0) }
            val below = (pictures.maxOfOrNull { it.row } ?: 0) + 1
            val ids = sounds.associate { (if (state.layered != null) it.id else TrackSequenceView.soundID(state, it.id)) to it }
            val ownSounds = lanes.filter { it.audio }.mapNotNull { lane ->
                val item = ids[lane.id] ?: return@mapNotNull null
                lane.copy(id = item.id, row = below + item.track)
            }
            val links = timeline.links.map { link -> link.copy(members = link.members.mapTo(LinkedHashSet()) { ids[it]?.id ?: it }) }
            return TimelineLinkLayout(pictures + ownSounds, links)
        }
    }
}
