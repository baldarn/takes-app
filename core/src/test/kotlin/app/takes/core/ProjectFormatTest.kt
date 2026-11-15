package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The saved form of a project that uses none of the newer features, byte for
 * byte. A change to the model must not alter what such a project writes.
 */
class ProjectFormatTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 10.0, recordedAt = 5)
    private val music = SourceMedia(id = "S2", filename = "music.m4a", duration = 30.0, recordedAt = 6, audioOnly = true, originalName = "Music")

    private fun saved(project: Project): Pair<String, List<String>> {
        val snapshots = mutableListOf<String>()
        val manifest = project.manifest { _, data -> snapshots += data.decodeToString() }.decodeToString()
        return manifest to snapshots
    }

    @Test fun aPlainProjectIsWrittenAsBefore() {
        val project = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val clip = project.edit.clips[0].copy(id = "C1")
        val cut = project.copy(captureLedger = listOf(clip)).let { it.commit(TimelineEditing.split(it.edit, 4.0)!!.let { state -> state.copy(clips = listOf(state.clips[0], state.clips[1].copy(id = "C2"))) }, now = 3) }
        val (manifest, snapshots) = saved(cut)
        assertEquals(1, cut.schemaVersion)
        assertEquals(
            """{"schemaVersion":1,"id":"P","title":"Trip","createdAt":1,"updatedAt":3,"sources":[{"id":"S1","filename":"take.mp4","duration":10.0,"recordedAt":5}],"historyCursor":1,""" +
                """"captureLedger":[{"id":"C1","sourceID":"S1","start":0.0,"end":10.0}],"snapshotFiles":["H","H"],""" +
                """"exportSettings":{"codec":"H.264","resolution":"Project size","frameRate":30}}""",
            manifest.replace(Regex("[0-9a-f]{64}\\.json"), "H"),
        )
        assertEquals("""{"state":{"clips":[]},"captureCursor":0}""", snapshots[0])
        assertEquals("""{"state":{"clips":[{"id":"C1","sourceID":"S1","start":0.0,"end":4.0},{"id":"C2","sourceID":"S1","start":4.0,"end":10.0}]},"captureCursor":1}""", snapshots[1])
    }

    @Test fun aProjectWithTitlesSoundAndSpeedIsWrittenAsBefore() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2).addSource(music, now = 2)
        val clip = base.edit.clips[0].copy(
            id = "C1", texts = listOf(TextOverlay(id = "T", text = "Hello", start = 1.0, end = 3.0)),
            audio = ClipAudio(volumeDB = -6.0), speed = SpeedSettings(id = "V", rate = 2.0),
        )
        val state = EditState(listOf(clip), base.edit.captureBase, listOf(AudioItem(id = "A", sourceID = "S2", start = 0.5, sourceStart = 0.0, sourceEnd = 4.0)))
        val project = base.commit(state, now = 3)
        assertEquals(9, project.schemaVersion)
        assertEquals(
            """{"state":{"clips":[{"id":"C1","sourceID":"S1","start":0.0,"end":10.0,"texts":[{"id":"T","text":"Hello","start":1.0,"end":3.0,"x":0.5,"y":0.5,"size":0.065}],""" +
                """"audio":{"volumeDB":-6.0,"muted":false,"fadeIn":0.0,"fadeOut":0.0,"denoise":0.0},"speed":SPEED}],""" +
                """"audio":[{"id":"A","sourceID":"S2","start":0.5,"sourceStart":0.0,"sourceEnd":4.0,"track":0}]},"captureCursor":1}""",
            saved(project).second[1].replace(documentJson.encodeToString(SpeedSettings.serializer(), SpeedSettings(id = "V", rate = 2.0)), "SPEED"),
        )
    }
}
