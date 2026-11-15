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

    // MARK: Transitions

    private val oldDissolve = """{"id":"J1","nextClipID":"C2","style":"dissolve","duration":1.0}"""
    private val oldFade = """{"id":"J2","nextClipID":"C3","style":"fadeBlack","duration":0.5}"""

    /** A step as the app wrote it while it knew two transition styles. */
    private val oldStep = """{"state":{"clips":[{"id":"C1","sourceID":"S1","start":0.0,"end":3.0,"transition":$oldDissolve},""" +
        """{"id":"C2","sourceID":"S1","start":3.0,"end":6.0,"transition":$oldFade},{"id":"C3","sourceID":"S1","start":6.0,"end":10.0}]},"captureCursor":1}"""

    private fun oldProject(): Project {
        val manifest = """{"schemaVersion":2,"id":"P","title":"Trip","createdAt":1,"updatedAt":3,"sources":[{"id":"S1","filename":"take.mp4","duration":10.0,"recordedAt":5}],""" +
            """"historyCursor":0,"captureLedger":[{"id":"C0","sourceID":"S1","start":0.0,"end":10.0}],"snapshotFiles":["${sha256(oldStep.toByteArray())}.json"],""" +
            """"exportSettings":{"codec":"H.264","resolution":"Project size","frameRate":30}}"""
        return Project.readManifest(manifest.toByteArray()) { oldStep.toByteArray() }
    }

    @Test fun aProjectWithTheTwoOldTransitionsIsReadAndWrittenBackByteForByte() {
        val project = oldProject()
        assertEquals(2, project.schemaVersion)
        assertEquals(TransitionSettings(id = "J1", nextClipID = "C2", style = TransitionStyle.Dissolve, duration = 1.0), project.edit.clips[0].transition)
        assertEquals(TransitionSettings(id = "J2", nextClipID = "C3", style = TransitionStyle.FadeColor, duration = 0.5), project.edit.clips[1].transition)
        val names = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val manifest = project.manifest { name, data -> names += name; steps += data.decodeToString() }.decodeToString()
        assertEquals(listOf(oldStep), steps)
        assertEquals(listOf(sha256(oldStep.toByteArray()) + ".json"), names) // the history file keeps its name
        assertEquals(true, manifest.startsWith("""{"schemaVersion":2,"""))
    }

    @Test fun editingAnOldTransitionWithinWhatItCouldSayKeepsTheOldFormat() {
        val project = oldProject()
        val clips = project.edit.clips
        // A longer dissolve, and the fade turned into a dissolve: both are things the old format says.
        val edited = project.commit(project.edit.copy(clips = listOf(
            clips[0].copy(transition = clips[0].transition!!.copy(duration = 2.0)),
            clips[1].copy(transition = clips[1].transition!!.copy(style = TransitionStyle.Dissolve)), clips[2],
        )), now = 4)
        assertEquals(2, edited.schemaVersion)
        val (_, steps) = saved(edited)
        assertEquals(oldStep, steps[0]) // the earlier step is not rewritten
        assertEquals(
            oldStep.replace(""""style":"dissolve","duration":1.0""", """"style":"dissolve","duration":2.0""").replace("fadeBlack", "dissolve"),
            steps[1],
        )
    }

    @Test fun whatTheOldFormatCannotSayTakesSchemaThirteen() {
        val project = oldProject()
        val clips = project.edit.clips
        fun schemaAfter(change: (List<TimelineClip>) -> List<TimelineClip>) = project.commit(project.edit.copy(clips = change(clips)), now = 4).schemaVersion
        val join = clips[0].transition!!
        fun joined(settings: TransitionSettings) = schemaAfter { listOf(it[0].copy(transition = settings), it[1], it[2]) }
        assertEquals(13, joined(join.copy(style = TransitionStyle.Wipe)))
        assertEquals(13, joined(join.copy(hold = 0.5)))
        assertEquals(13, joined(join.copy(duration = 12.0)))
        assertEquals(13, joined(join.copy(style = TransitionStyle.FadeColor, fadeColor = TransitionColor(1.0, 0.0, 0.0))))
        assertEquals(13, joined(join.copy(audioOffset = -0.2)))
        assertEquals(13, joined(join.copy(axis = TransitionAxis.Vertical)))
        // The opening and the close of the timeline.
        assertEquals(13, schemaAfter { listOf(it[0].copy(openingTransition = TransitionSettings(id = "O", nextClipID = "C1")), it[1], it[2]) })
        assertEquals(13, schemaAfter { listOf(it[0], it[1], it[2].copy(transition = TransitionSettings(id = "E"))) })
        // The earlier step keeps its bytes whatever the later one uses.
        val wiped = project.commit(project.edit.copy(clips = listOf(clips[0].copy(transition = join.copy(style = TransitionStyle.Wipe, wipeAngle = 45.0)), clips[1], clips[2])), now = 4)
        val (_, steps) = saved(wiped)
        assertEquals(oldStep, steps[0])
        assertEquals(true, steps[1].contains(""""transition":{"id":"J1","nextClipID":"C2","style":"wipe","duration":1.0,"wipeAngle":45.0}"""))
    }

    @Test fun aTransitionWithEverySettingSurvivesSavingAndReading() {
        val full = TransitionSettings(
            id = "J", nextClipID = "C2", style = TransitionStyle.Split, duration = 2.5, axis = TransitionAxis.Vertical, divider = 0.3, reverse = true,
            audio = TransitionAudio.Second, first = TransitionFraming(0.2, 0.8, 2.0), second = TransitionFraming(0.6, 0.1, 3.5),
            fadeColor = TransitionColor(0.2, 0.7, 0.4), hold = 1.0, audioOffset = -0.5, audioBlend = 0.25, wipeAngle = 37.0, hardEdge = true,
            highlightsFirst = true, flashCount = 5, flashAcceleration = 3.0, stillDissolve = 0.5, blurAmount = 0.6,
        )
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val whole = base.edit.clips[0].copy(id = "C1")
        val clips = listOf(
            whole.copy(end = 5.0, transition = full, openingTransition = TransitionSettings(id = "O", nextClipID = "C1", style = TransitionStyle.Still, duration = 1.0)),
            whole.copy(id = "C2", start = 5.0, transition = TransitionSettings(id = "E", style = TransitionStyle.FadeColor, duration = 1.0, hold = 0.5)),
        )
        val project = base.commit(EditState(clips, base.edit.captureBase), now = 3)
        assertEquals(13, project.schemaVersion)
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        val read = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(project.edit.clips, read.edit.clips)
        assertEquals(13, read.schemaVersion)
    }

    @Test fun aTransitionOutOfRangeIsRefused() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val whole = base.edit.clips[0]
        for (bad in listOf(
            whole.copy(transition = TransitionSettings(duration = 0.0)),
            whole.copy(openingTransition = TransitionSettings(divider = 0.05)),
            whole.copy(openingTransition = TransitionSettings(flashCount = 30)),
        )) {
            val project = base.commit(EditState(listOf(bad), base.edit.captureBase), now = 3)
            try { project.validate(); org.junit.Assert.fail("accepted") } catch (_: ProjectException) { }
        }
    }
}
