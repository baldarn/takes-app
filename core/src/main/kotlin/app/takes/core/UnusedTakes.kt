package app.takes.core

/**
 * The takes of a project that are not on its timeline, and putting some of
 * them back.
 *
 * A take that was undone, deleted or cut out of the edit stays among the
 * project's [Project.sources] with its file. Nothing ever returns it to the
 * timeline by itself: it might have been removed on purpose. It comes back
 * only when the person picks it here.
 */
object UnusedTakes {
    /**
     * The footage that has no clip in the edit as it is now, in the order the
     * project holds it: the order it was recorded or imported in.
     *
     * Only the current edit counts. A take that an earlier or an undone step
     * of the history still shows is unused. Sound files are never takes. A
     * take whose file is missing is listed like any other: this looks at the
     * document only.
     */
    fun of(project: Project): List<SourceMedia> {
        val used = project.edit.clips.mapTo(HashSet()) { it.sourceID }
        return project.recordings.filter { it.id !in used }
    }

    /** The project after [add], and the timeline second where the first of the added takes begins. */
    data class Added(val project: Project, val start: Double)

    /**
     * Puts [takes] whole into the edit at timeline second [at], one after
     * another in the order given, as one Undo step. Later clips ripple, and a
     * clip showing at [at] is split around them, as [TimelineEditing.insert]
     * does. Pass 0 for the start of the movie and the edit's duration, or
     * anything past it, for the end.
     *
     * A take given twice goes in twice. Null, and nothing changes, when there
     * is nothing to add, [at] is not a number, or one of [takes] is not footage
     * of this project.
     */
    fun add(project: Project, takes: List<SourceMedia>, at: Double, now: Long = System.currentTimeMillis()): Added? {
        val footage = project.recordings.associateBy { it.id }
        if (takes.isEmpty() || takes.any { footage[it.id] == null }) return null
        // The project's own record of the take decides how long it is.
        val clips = takes.map { TimelineClip(sourceID = it.id, start = 0.0, end = footage.getValue(it.id).duration) }
        val next = TimelineEditing.insert(project.edit, clips, at) ?: return null
        val committed = project.commit(next, now)
        val placed = committed.edit.clips
        val index = placed.indexOfFirst { it.id == clips.first().id }
        if (index < 0) return null
        return Added(committed, TimelineEditing.clipStart(placed, index))
    }
}
