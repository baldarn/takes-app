package app.takes.core.yourec

import app.takes.core.BookmarkAnchor
import app.takes.core.EditTime
import app.takes.core.ExportSettings
import app.takes.core.Project
import app.takes.core.TitleInk
import app.takes.core.TitleCard
import app.takes.core.LabelHeadline
import app.takes.core.TimelineClip
import app.takes.core.ProjectException
import app.takes.core.Size
import app.takes.core.SourceMedia
import app.takes.core.StabilizationLevel
import app.takes.core.TextColor
import app.takes.core.TextStyle
import app.takes.core.TitleDesign
import app.takes.core.TrackTimeline
import app.takes.core.TransitionAudio
import app.takes.core.TransitionAxis
import app.takes.core.TransitionStyle
import app.takes.core.VideoEffectID
import app.takes.core.documentJson
import app.takes.core.toTimeline
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YouRecImportTest {
    private fun uid(n: Int) = "5A1E0000-0000-4000-8000-%012X".format(n)
    private val s1 = uid(0x5001)
    private val s2 = uid(0x5002)
    private val c1 = uid(0xC1)
    private val c2 = uid(0xC2)
    private val mov1 = "1731400000000-$s1.mov"
    private val mov2 = "1731400004000-$s2.mov"

    private fun import(case: String, probe: (String) -> Size? = { null }): YouRecCodec.Imported {
        val fixture = YouRecFixtures.load(case)
        return YouRecCodec.import(fixture.manifest, { fixture.steps.getValue(it) }, probe)
    }

    private fun kinds(imported: YouRecCodec.Imported) = imported.notes.map { it.kind }.toSet()

    @Test fun everyFixtureIsYouRecAndTakesManifestsAreNot() {
        for (case in YouRecFixtures.cases) {
            val manifest = documentJson.parseToJsonElement(YouRecFixtures.load(case).manifest.decodeToString()) as JsonObject
            assertTrue(case, YouRecDetect.isYouRec(manifest, setOf("project.json"), null))
            // Without the key every build writes, the dates still tell.
            assertTrue(case, YouRecDetect.isYouRec(JsonObject(manifest - "automaticCanvasVersion" - "recordingHistoryVersion" - "recordingSite" - "captureLedger"), emptySet(), null))
        }
        val takes = Project("Trip").addRecording(SourceMedia(filename = "a.mp4", duration = 2.0)).addRecording(SourceMedia(filename = "b.mp4", duration = 2.0))
        val manifest = documentJson.parseToJsonElement(takes.manifest { _, _ -> }.decodeToString()) as JsonObject
        assertFalse(YouRecDetect.isYouRec(manifest, setOf("project.json", "integrity.json"), null))
        // The manifest decides over the archive around it.
        val recovery = "Recovery/${uid(1)}/take.mov"
        assertFalse(YouRecDetect.isYouRec(manifest, setOf(recovery), documentJson.parseToJsonElement("""{"version":2,"sha256":{}}""") as JsonObject))
        // A manifest that says nothing either way is told by the archive.
        val bare = JsonObject(mapOf("title" to kotlinx.serialization.json.JsonPrimitive("?")))
        assertFalse(YouRecDetect.isYouRec(bare, emptySet(), null))
        assertTrue(YouRecDetect.isYouRec(bare, setOf(recovery), null))
        assertTrue(YouRecDetect.isYouRec(bare, emptySet(), documentJson.parseToJsonElement("""{"version":2,"sha256":{}}""") as JsonObject))
    }

    @Test fun recoveryEntriesAreTheOriginals() {
        assertTrue(YouRecDetect.isRecoveryEntry("Recovery/${uid(1)}/take.mov"))
        assertTrue(YouRecDetect.isRecoveryEntry("Recovery/${uid(1).lowercase()}/take.events.json"))
        assertTrue(YouRecDetect.isRecoveryEntry("Recovery/${"a".repeat(64)}/Voice.CAF"))
        for (name in listOf("Recovery/x/take.mov", "Recovery/${uid(1)}/take.txt", "Recovery/${uid(1)}/.take.mov", "Recovery/${uid(1)}/a/take.mov",
            "Recovery/${uid(1)}/", "recovery/${uid(1)}/take.mov", "Recovery/${"a".repeat(63)}/take.mov")) {
            assertFalse(name, YouRecDetect.isRecoveryEntry(name))
        }
    }

    @Test fun takesTheLedgerAndAnInsertion() {
        val imported = import("takes")
        val project = imported.project
        project.validate()
        assertEquals("Holiday", project.title)
        assertEquals(Math.round((784111111.25 + 978307200) * 1000), project.createdAt)
        assertEquals(Math.round((784222222.5 + 978307200) * 1000), project.updatedAt)
        assertEquals(Math.round((784111200.5 + 978307200) * 1000), project.sources[0].recordedAt)
        assertEquals(listOf(mov1, mov2), project.sources.map { it.filename })
        assertEquals(ExportSettings(ExportSettings.Codec.HEVC, ExportSettings.Resolution.FullHD, 25), project.exportSettings)
        assertEquals(listOf(c1, c2), project.captureLedger.map { it.id })
        assertEquals(setOf(1), project.captureInsertions)
        // The marks made while recording, within the take, on its clip.
        assertEquals(listOf(uid(0xE1) to 1.5), project.captureLedger[0].bookmarks!!.map { it.id to it.time })
        assertNull(project.captureLedger[1].bookmarks)
        // The run of takes recorded at a place is not carried.
        assertNull(project.recordingInsertion)
        assertNull(project.recordingInsertionStep)
        assertTrue(YouRecNote.Kind.RecordingRunEnded in kinds(imported))
        // The first step replays the appended take, not the inserted one; the second holds both.
        assertEquals(1, project.historyCursor)
        assertEquals(listOf(c1), project.history[0].toTimeline().video.map { it.id })
        assertEquals(uid(0xE1), project.history[0].toTimeline().bookmarks.single().id)
        val timeline = project.snapshots[1].timeline!!
        assertEquals(listOf(c1, c2), timeline.video.map { it.id })
        assertEquals(BookmarkAnchor.Source(c1, s1, 1.5), timeline.bookmarks.single().anchor)
        // Read as a sequence, its bookmark is on the clip, with the original's longest label.
        assertTrue(project.snapshots[1].state.isSequence)
        assertEquals("B".repeat(300), project.edit.clips[0].bookmarks!!.single().label)
        assertTrue(project.schemaVersion >= TrackTimeline.SCHEMA)
        // The steps are written again in Takes' form.
        for (step in project.snapshots) assertEquals(app.takes.core.sha256(step.data()) + ".json", step.filename())
        assertEquals(listOf(mov1, mov2), imported.mediaFiles)
        assertEquals("Timeline.otio", imported.timelineName)
    }

    @Test fun layersPlacementsTransitionsAndLongBookmarks() {
        val imported = import("layers")
        val project = imported.project
        project.validate()
        val step = project.snapshots.single()
        assertTrue(!step.state.isSequence)
        assertTrue(project.schemaVersion >= TrackTimeline.LAYERED_SCHEMA)
        val timeline = step.timeline!!
        val placement = timeline.placements.single()
        assertEquals(setOf(c1, c2), placement.participants)
        assertEquals(setOf(c1, c2), placement.videos.keys)
        val window = placement.videos.getValue(c2)
        assertEquals(0.1, window.edgeCrop!!.left, 0.0)
        assertEquals(0.2, window.edgeCrop!!.right, 0.0)
        assertEquals(2.0, window.motion!!.duration, 0.0)
        assertEquals(1, window.order)
        assertNull(placement.videos.getValue(c1).edgeCrop)
        val transition = timeline.transitions.single()
        assertEquals(c1, transition.incoming)
        assertNull(transition.outgoing)
        with(transition.settings) {
            assertEquals(TransitionStyle.Wipe, style)
            assertEquals(TransitionAxis.Vertical, axis)
            assertEquals(0.25, divider, 0.0)
            assertTrue(reverse)
            assertEquals(TransitionAudio.First, audio)
            assertEquals(1.2, first.zoom, 0.0)
            assertEquals(30.0, wipeAngle!!, 0.0)
            assertEquals(0.9, fadeColor!!.red, 0.0)
        }
        // A label of 300 characters, which the original allows on a bookmark.
        assertEquals(300, timeline.bookmarks[0].label.length)
        assertEquals(BookmarkAnchor.Project(EditTime.of(1.0)), timeline.bookmarks[0].anchor)
        assertEquals(BookmarkAnchor.Local(c2, EditTime.of(0.5)), timeline.bookmarks[1].anchor)
        assertTrue(imported.notes.isEmpty())
    }

    @Test fun titlesCaptionsAndSound() {
        val imported = import("titles")
        val project = imported.project
        project.validate()
        val timeline = project.snapshots.single().timeline!!
        val texts = timeline.video.single().content.texts!!
        // A title saved without a design gets the look the original draws it with.
        assertEquals(TitleDesign.legacy(TextStyle.Box, TextColor.Yellow), texts[0].appearance)
        assertEquals("import:${"ab".repeat(32)}:0:Lobster-Regular", texts[1].appearance!!.face)
        assertEquals(600, texts[1].appearance!!.weight)
        assertEquals(0.5, texts[1].motion!!.outSeconds, 0.0)
        // Shortened to what Takes keeps, never through a character.
        assertEquals("é".repeat(499), texts[2].text)
        assertEquals(TitleDesign.legacy(TextStyle.Clean, TextColor.White), texts[2].appearance)
        val sound = timeline.audio.single()
        assertEquals(0.5, sound.fadeIn!!.seconds, 0.0)
        assertEquals(0.25, sound.fadeOut!!.elapsed, 0.0)
        assertEquals(1, sound.censors.size)
        assertEquals(listOf(3, 3), sound.enhancements.map { it.effects.size })
        assertEquals(listOf("Hello", "there"), sound.subtitleWords!!.map { it.text })
        // The fingerprint is Swift's hash of Swift's bytes: regrouping waits for the next transcription.
        assertEquals("", timeline.subtitles!!.timingFingerprint)
        assertEquals("en", timeline.subtitles!!.language)
        assertEquals(setOf(YouRecNote.Kind.TitlesShortened, YouRecNote.Kind.NeuralStagesUnprocessed), kinds(imported))
        assertEquals(listOf(mov1, "font-${"ab".repeat(32)}.sfnt"), imported.mediaFiles)
    }

    @Test fun effectsAndClipTools() {
        val imported = import("effects")
        val project = imported.project
        project.validate()
        val analysis = uid(0xA1A1)
        // The effects the take was recorded with go beside its movie, with their analysis.
        val recipe = imported.sourceEffects.getValue(mov1).single()
        assertEquals(VideoEffectID.CrashZoom, recipe.kind)
        assertEquals("effect-$analysis.json", recipe.analysisFile)
        assertEquals("effect-$analysis.bin", recipe.analysisDataFile)
        assertEquals(analysis, recipe.capturedEffectID)
        val timeline = project.snapshots.single().timeline!!
        val clip = timeline.video[0].content
        assertEquals(listOf(VideoEffectID.CrashZoom, VideoEffectID.Noir), clip.videoEffects!!.map { it.kind })
        assertEquals(0.8, clip.videoEffects!![1].number("intensity", 0.0), 0.0)
        assertTrue(clip.mirror!!.horizontal)
        assertEquals(1, clip.pauses!!.cuts.size)
        assertEquals(StabilizationLevel.Standard, clip.stabilization!!.level)
        assertEquals("effect-lut-${"cd".repeat(32)}.cube", clip.color!!.imported!!.filename)
        val held = timeline.video[1].content
        assertEquals(3.0, held.frozenFrame!!.duration, 0.0)
        // Stabilisation is the newest thing this project holds.
        assertEquals(TimelineClip.STABILIZATION_SCHEMA, project.schemaVersion)
        assertEquals(
            listOf(mov1, mov2, "effect-$analysis.bin", "effect-$analysis.json", "effect-lut-${"cd".repeat(32)}.cube", "image-${uid(0x1111)}.png"),
            imported.mediaFiles,
        )
    }

    @Test fun aFrameKeptFromAnotherTakeBecomesAFixedSize() {
        val asked = ArrayList<String>()
        val imported = import("canvas") { name -> asked += name; Size(1079.4, 1920.6) }
        val project = imported.project
        project.validate()
        assertEquals(listOf(mov2), asked)
        assertEquals(Size(1080.0, 1920.0), project.snapshots[0].state.outputSize)
        // The first picture anyway, a size already fixed, or a take no longer on the timeline: nothing to keep.
        assertNull(project.snapshots[1].state.outputSize)
        assertEquals(Size(1280.0, 720.0), project.snapshots[2].state.outputSize)
        assertNull(project.snapshots[3].state.outputSize)
        assertEquals(listOf(YouRecNote(YouRecNote.Kind.CanvasFixed, listOf(mov2))), imported.notes)
        assertTrue(project.schemaVersion >= app.takes.core.EditState.OUTPUT_SIZE_SCHEMA)

        val unread = import("canvas") { null }
        assertNull(unread.project.snapshots[0].state.outputSize)
        assertEquals(listOf(YouRecNote(YouRecNote.Kind.CanvasDropped, listOf(mov2))), unread.notes)
    }

    @Test fun stillPhotosAndSoundFiles() {
        val project = import("audio").project
        project.validate()
        assertEquals(true, project.sources[0].isStillPhoto)
        assertEquals(true, project.sources[1].audioOnly)
        assertEquals("Voice recording.caf", project.sources[1].originalName)
        val timeline = project.history[0].toTimeline()
        assertEquals(1, timeline.video.size)
        assertEquals(-3.0, timeline.audio.single().volumeDB, 0.0)
    }

    /** [case] with the step's text changed by [edit] and the manifest's schema set to [schema], the step named by its new hash. */
    private fun changed(case: String, schema: Int, edit: (String) -> String): YouRecFixtures.Fixture {
        val fixture = YouRecFixtures.load(case)
        val (name, bytes) = fixture.steps.entries.single()
        val step = edit(bytes.decodeToString()).toByteArray()
        val renamed = app.takes.core.sha256(step) + ".json"
        val manifest = fixture.manifest.decodeToString().replace(name, renamed).replace(Regex("\"schemaVersion\":\\d+"), "\"schemaVersion\":$schema")
        return YouRecFixtures.Fixture(manifest.toByteArray(), mapOf(renamed to step))
    }

    // Tests/TitleDesignTests.swift:54-59, YouRec/Project.swift:333-335 at dadc258: a card of the original's schema 14 opens with both texts.
    @Test fun aCardOfALabelAndAHeadlineComesFromTheIPhone() {
        val card = LabelHeadline().let {
            it.copy(label = "Location 04", card = it.card.copy(fill = TitleInk(0x916235, alpha = 218), labelOffset = TitleCard.Offset(0.6, 0.15)))
        }
        val swift = documentJson.encodeToString(LabelHeadline.serializer(), card)
        // Swift leaves out an Optional that is nil, as here; every other key is written.
        assertFalse("headlineOffset" in swift)
        val fixture = changed("titles", 14) { it.replace("\"text\":\"Designed\"", "\"text\":\"Designed\",\"labelHeadline\":$swift") }
        val imported = YouRecCodec.import(fixture.manifest, { fixture.steps.getValue(it) })
        val project = imported.project
        project.validate()
        val title = project.snapshots.single().timeline!!.video.single().content.texts!![1]
        assertEquals(card, title.labelHeadline)
        assertEquals("Designed", title.text)
        assertEquals(TimelineClip.LABEL_HEADLINE_SCHEMA, project.schemaVersion)
        // A label longer than Takes keeps is shortened as a title's text is.
        val long = changed("titles", 14) { it.replace("\"text\":\"Designed\"", "\"text\":\"Designed\",\"labelHeadline\":" + swift.replace("Location 04", "x".repeat(600))) }
        val cut = YouRecCodec.import(long.manifest, { long.steps.getValue(it) })
        assertEquals(500, cut.project.snapshots.single().timeline!!.video.single().content.texts!![1].labelHeadline!!.label.length)
        assertTrue(YouRecNote.Kind.TitlesShortened in kinds(cut))
    }

    // Schema 13 (YouRec/Project.swift:332 at dadc258) holds colour regions, which Takes does not read yet: refused, not dropped.
    @Test fun colourRegionsAreRefusedUntilTheyAreRead() {
        val fixture = changed("titles", 14) { it.replace("\"texts\":[", "\"colorRegions\":[],\"texts\":[") }
        val error = assertThrows(ProjectException::class.java) { YouRecCodec.import(fixture.manifest, { fixture.steps.getValue(it) }) }
        assertEquals(ProjectException.Reason.InvalidDocument, error.reason)
        val none = changed("titles", 13) { it }
        assertEquals(1, YouRecCodec.import(none.manifest, { none.steps.getValue(it) }).project.snapshots.size)
    }

    @Test fun whatTheOriginalWouldNotReadIsRefused() {
        val fixture = YouRecFixtures.load("takes")
        val manifest = fixture.manifest.decodeToString()
        fun refused(text: String, steps: Map<String, ByteArray> = fixture.steps) {
            val error = assertThrows(ProjectException::class.java) { YouRecCodec.import(text.toByteArray(), { steps.getValue(it) }) }
            assertEquals(ProjectException.Reason.InvalidDocument, error.reason)
        }
        refused(manifest.replace("\"schemaVersion\":6", "\"schemaVersion\":15"))
        refused(manifest.replace("\"recordingHistoryVersion\":1", "\"recordingHistoryVersion\":2"))
        refused(manifest.replace("\"historyCursor\":1", "\"historyCursor\":2"))
        refused(manifest.replace("784111111.25", "\"yesterday\""))
        // A step that does not match its name.
        val first = fixture.steps.keys.first()
        refused(manifest, fixture.steps + (first to (fixture.steps.getValue(first).decodeToString() + " ").toByteArray()))
        // Flat dictionaries of an odd length, or with a key twice.
        val layers = YouRecFixtures.load("layers")
        val step = layers.steps.values.single().decodeToString()
        for (videos in listOf("\"videos\":[\"$c1\"]", "\"videos\":[\"$c1\",{},\"$c1\",{}]")) {
            val broken = step.replace(Regex("\"videos\":\\[.*?\\}\\]"), videos)
            val text = layers.manifest.decodeToString().replace(layers.steps.keys.single(), app.takes.core.sha256(broken.toByteArray()) + ".json")
            val error = assertThrows(ProjectException::class.java) {
                YouRecCodec.import(text.toByteArray(), { broken.toByteArray() })
            }
            assertEquals(ProjectException.Reason.InvalidDocument, error.reason)
        }
    }
}
