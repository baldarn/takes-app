package app.takes.core

/**
 * How release 0.9.1 committed an edit and recorded a take at a chosen place:
 * every step a sequence of clips, under the schema of the newest thing it
 * used. The golden projects are built with these, so they stay the projects
 * users have on disk, and a test can make an old project to open in this one.
 */
object LegacyProject {
    fun commit(project: Project, state: EditState, now: Long = System.currentTimeMillis()): Project = with(project) {
        if (!canCommit(state)) return this
        val sound = state.audio?.ifEmpty { null }
        if (state.clips == edit.clips && sound == edit.audio && state.subtitles == edit.subtitles) return this
        val cursor = (state.captureBase ?: captureLedger.size).coerceIn(0, captureLedger.size)
        return copy(
            schemaVersion = maxOf(schemaVersion, state.clips.maxOfOrNull { it.schema } ?: 1, if (sound != null) 5 else 1, if (state.subtitles != null) 11 else 1,
                if (sound.orEmpty().any { it.audio?.reshaped(it.duration) == true }) 15 else 1),
            snapshots = snapshots.take(historyCursor + 1) + EditSnapshot(EditState(state.clips, audio = sound, subtitles = state.subtitles), cursor),
            historyCursor = historyCursor + 1,
            updatedAt = now,
        )
    }

    fun addRecording(project: Project, source: SourceMedia, now: Long): Project = with(project) {
        val insertion = recordingInsertion ?: return addRecording(source, now)
        val marks = source.events.orEmpty().filter { it.kind == Project.BOOKMARK_EVENT }.map { Bookmark(id = it.id, time = it.time) }.ifEmpty { null }
        val clip = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration, bookmarks = marks)
        val continuing = recordingInsertionStep == historyCursor && historyCursor > 0 && historyCursor == snapshots.lastIndex
        val current = edit
        val next = TimelineEditing.insert(current, listOf(clip), insertion) ?: current.copy(clips = current.clips + clip)
        val snapshot = EditSnapshot(EditState(next.clips, audio = next.audio, subtitles = next.subtitles), captureLedger.size + 1)
        val step = if (continuing) historyCursor else historyCursor + 1
        return copy(
            schemaVersion = maxOf(schemaVersion, next.clips.maxOf { it.schema }, 10),
            sources = sources + source,
            captureLedger = captureLedger + clip,
            captureInsertions = captureInsertions + captureLedger.size,
            snapshots = snapshots.take(step) + snapshot,
            historyCursor = step,
            recordingInsertion = TimelineEditing.clipStart(next.clips, next.clips.indexOfFirst { it.id == clip.id } + 1),
            recordingInsertionStep = step,
            updatedAt = now,
        )
    }
}
