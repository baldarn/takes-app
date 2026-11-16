package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The video effects of a clip as saved: the original's field and shape
 * (YouRec/Project.swift:58), schema 16, and what a split does with them.
 */
class ClipVideoEffectsTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 20.0, recordedAt = 5)

    private val correction = VideoEffectSettings(VideoEffectID.ColorCorrection, start = 1.0, end = 9.0)
        .with("exposure", EffectValue.Number(0.5)).withKeyframe("contrast", 2.5, EffectValue.Number(1.3))

    private fun clip(effects: List<VideoEffectSettings>?) = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0, videoEffects = effects)

    @Test fun aClipWithoutEffectsIsWrittenAsBefore() {
        val json = documentJson.encodeToString(TimelineClip.serializer(), clip(null))
        assertEquals("""{"id":"C1","sourceID":"S1","start":0.0,"end":10.0}""", json)
        assertNull(documentJson.decodeFromString(TimelineClip.serializer(), json).videoEffects)
        assertEquals(1, clip(null).schema)
        assertEquals(1, clip(emptyList()).schema)
    }

    @Test fun anEffectIsKeptWholeAsTheOriginalWroteIt() {
        val json = documentJson.encodeToString(TimelineClip.serializer(), clip(listOf(correction)))
        val read = documentJson.decodeFromString(TimelineClip.serializer(), json)
        assertEquals(listOf(correction), read.videoEffects)
        assertEquals(json, documentJson.encodeToString(TimelineClip.serializer(), read))
    }

    @Test fun anEffectOfALaterVersionIsLeftOutWhenRead() {
        val json = documentJson.encodeToString(TimelineClip.serializer(), clip(listOf(correction)))
        val later = """{"kind":"hologram","parameters":[],"id":"E2","enabled":true,"version":1,"start":0,"end":5}"""
        val mixed = json.replace("\"videoEffects\":[", "\"videoEffects\":[$later,")
        assertEquals(listOf(correction), documentJson.decodeFromString(TimelineClip.serializer(), mixed).videoEffects)
    }

    /** Readers before schema 16 knew nothing of effects and would play the clip without them: the recipe says so itself. */
    @Test fun effectsRaiseTheSchemaOfTheRecipe() {
        assertEquals(16, clip(listOf(correction)).schema)
        assertEquals(16, clip(listOf(correction)).copy(audio = ClipAudio(fadeIn = 9.0), crop = CropSettings(rotation = 10.0)).schema)
        assertEquals(19, Project.LATEST_READABLE_SCHEMA) // the timeline and timelines that are no sequence came after, held frames and time effects after them
    }

    /** The effects live on the clip's recipe, which is the picture's content in the timeline a step is saved as. */
    @Test fun aTimelineStepWithEffectsKeepsThemByteForByte() {
        val state = EditState(listOf(clip(listOf(correction)), TimelineClip(id = "C2", sourceID = "S1", start = 10.0, end = 15.0)))
        val step = EditSnapshot.committed(state, 0)
        val read = EditSnapshot.decoded(step.data())
        assertEquals(step, read)
        assertEquals(listOf(listOf(correction), null), read.state.clips.map { it.videoEffects })
        assertEquals(step.data().toList(), EditSnapshot.committed(read.state, 0).data().toList())

        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val graded = base.commit(EditState(base.edit.clips.map { it.copy(videoEffects = listOf(correction)) }), now = 3)
        assertEquals(17, graded.schemaVersion)
        graded.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = graded.manifest { name, data -> files[name] = data }
        val reopened = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(graded.edit.clips, reopened.edit.clips)
        val again = mutableMapOf<String, ByteArray>()
        reopened.manifest { name, data -> again[name] = data }
        assertEquals(files.mapValues { it.value.toList() }, again.mapValues { it.value.toList() })
        val newer = manifest.decodeToString().replaceFirst("\"schemaVersion\":17", "\"schemaVersion\":${Project.LATEST_READABLE_SCHEMA + 1}")
        try { Project.readManifest(newer.toByteArray()) { files.getValue(it) }; fail("opened") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.NewerVersion, refused.reason)
        }
    }

    /** Ranges and keyframes are in source seconds, so each piece keeps the whole list (VideoEffectModel.swift:3). */
    @Test fun aSplitGivesBothPiecesTheEffects() {
        val state = EditState(listOf(clip(listOf(correction))))
        val split = TimelineEditing.split(state, 4.0)!!
        assertEquals(2, split.clips.size)
        for (piece in split.clips) assertEquals(listOf(correction), piece.videoEffects)
        assertEquals(listOf(4.0, 10.0), split.clips.map { it.end })
    }

    @Test fun anEditGivesAClipEffectsAndTakesThemAway() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val given = TimelineEditing.setVideoEffects(base.edit, 0, listOf(correction))!!
        assertEquals(listOf(correction), given.clips[0].videoEffects)
        val graded = base.commit(given, now = 3)
        assertEquals(listOf(correction), graded.edit.clips[0].videoEffects)
        assertNull(TimelineEditing.setVideoEffects(given, 0, listOf(correction)))
        assertNull(TimelineEditing.setVideoEffects(given, 0, null)!!.clips[0].videoEffects)
    }
}
