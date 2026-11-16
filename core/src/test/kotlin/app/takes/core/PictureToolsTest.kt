package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror of a clip (YouRec/Project.swift:54, YouRec/EditorTools.swift:713),
 * the movie's fixed frame (YouRec/Project.swift:141-189) and log footage
 * through the grade (YouRec/VideoToolCompositor.swift:505-529).
 */
class PictureToolsTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 20.0, recordedAt = 5)
    private val flip = MirrorSettings(id = "M1", horizontal = true)

    private fun project() = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)

    private fun files(project: Project): Pair<ByteArray, Map<String, ByteArray>> {
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        return manifest to files
    }

    private fun reopened(project: Project): Project {
        val (manifest, files) = files(project)
        return Project.readManifest(manifest) { files.getValue(it) }
    }

    // MARK: Mirror

    @Test fun aClipWithoutAMirrorIsWrittenAsBefore() {
        val clip = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0)
        assertEquals("""{"id":"C1","sourceID":"S1","start":0.0,"end":10.0}""", documentJson.encodeToString(TimelineClip.serializer(), clip))
        assertEquals(1, clip.schema)
    }

    @Test fun aMirrorIsSavedAsTheOriginalSavesItAndRaisesTheSchema() {
        val clip = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0, mirror = flip)
        val json = documentJson.encodeToString(TimelineClip.serializer(), clip)
        assertTrue(json, json.endsWith(""""mirror":{"id":"M1","horizontal":true,"vertical":false}}"""))
        assertEquals(clip, documentJson.decodeFromString(TimelineClip.serializer(), json))
        assertEquals(20, clip.schema)
        assertEquals(20, TimelineClip.MIRROR_SCHEMA)
    }

    @Test fun anEditFlipsAClipAndAFlipOfNeitherWayIsNone() {
        val base = project()
        val flipped = TimelineEditing.setMirror(base.edit, 0, flip)!!
        assertEquals(flip, flipped.clips[0].mirror)
        assertNull(TimelineEditing.setMirror(flipped, 0, flip))
        assertNull(TimelineEditing.setMirror(flipped, 0, MirrorSettings())!!.clips[0].mirror)
        val saved = base.commit(flipped, now = 3)
        assertEquals(20, saved.schemaVersion)
        saved.validate()
        val read = reopened(saved)
        assertEquals(flip, read.edit.clips[0].mirror)
        // Both pieces of a split keep it.
        assertEquals(listOf(flip, flip), TimelineEditing.split(read.edit, 4.0)!!.clips.map { it.mirror })
        // An older reader refuses the project rather than showing it the wrong way round.
        assertEquals(17, project().commit(TimelineEditing.split(base.edit, 4.0)!!, now = 3).schemaVersion)
    }

    @Test fun theFrameOfAFlippedClipIsFlipped() {
        val source = Size(1920.0, 1080.0)
        val clip = TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0, mirror = MirrorSettings(horizontal = true, vertical = true))
        val frame = PictureFraming.frame(clip, 1.0, source, source)
        val corner = Point(0.0, 0.0).applying(frame.transform)
        assertEquals(1920.0, corner.x, 1e-9)
        assertEquals(1080.0, corner.y, 1e-9)
    }

    /** The preview's layer puts every source point where the export's transform does, flipped, turned and zoomed. */
    @Test fun theLayerOfAFrameMatchesItsTransform() {
        val source = Size(1920.0, 1080.0)
        for (canvas in listOf(Size(1920.0, 1080.0), Size(1080.0, 1920.0), Size(1080.0, 1080.0))) {
            for (mirror in listOf(null, MirrorSettings(horizontal = true), MirrorSettings(vertical = true), MirrorSettings(horizontal = true, vertical = true))) {
                for (crop in listOf(null, CropSettings(x = 0.3, y = 0.6, zoom = 1.7, rotation = 23.0))) {
                    val frame = PictureFraming.frame(crop, 0.0, source, canvas, mirror)
                    val layer = frame.layer
                    val fitted = minOf(canvas.width / source.width, canvas.height / source.height)
                    val radians = layer.rotation * Math.PI / 180
                    for (p in listOf(Point(0.0, 0.0), Point(1920.0, 0.0), Point(700.0, 900.0))) {
                        // The view fits the picture about its middle, then scales, turns and moves it about the view's middle.
                        val x = (p.x - source.width / 2) * fitted * layer.scaleX
                        val y = (p.y - source.height / 2) * fitted * layer.scaleY
                        val shown = Point(
                            canvas.width / 2 + layer.translationX + x * kotlin.math.cos(radians) - y * kotlin.math.sin(radians),
                            canvas.height / 2 + layer.translationY + x * kotlin.math.sin(radians) + y * kotlin.math.cos(radians),
                        )
                        val expected = p.applying(frame.transform)
                        assertEquals("$canvas $mirror $crop $p", expected.x, shown.x, 1e-6)
                        assertEquals("$canvas $mirror $crop $p", expected.y, shown.y, 1e-6)
                    }
                }
            }
        }
    }

    // MARK: The movie's frame

    @Test fun anEditWithoutAFixedFrameIsWrittenAsBefore() {
        val base = project()
        val cut = base.commit(TimelineEditing.split(base.edit, 4.0)!!, now = 3)
        val (_, files) = files(cut)
        assertFalse(files.values.any { it.decodeToString().contains("outputSize") })
        assertEquals(17, cut.schemaVersion)
        assertNull(cut.edit.outputSize)
    }

    @Test fun theCropToolFixesTheFrameAndLaterEditsKeepIt() {
        val base = project()
        val tall = Size(1080.0, 1920.0)
        val shaped = base.commit(base.edit, now = 3, outputSize = tall)
        assertEquals(2, shaped.snapshots.size) // a change of shape alone is a step
        assertEquals(tall, shaped.edit.outputSize)
        assertEquals(21, shaped.schemaVersion)
        val (_, files) = files(shaped)
        assertTrue(files.values.any { it.decodeToString().contains(""""outputSize":[1080.0,1920.0]""") })
        // An edit made from a timeline knows nothing of the frame, and keeps it.
        val cut = shaped.commit(TimelineEditing.split(shaped.edit.toTimeline().toEdit(shaped.edit.captureBase), 4.0)!!, now = 4)
        assertEquals(tall, cut.edit.outputSize)
        val read = reopened(cut)
        assertEquals(tall, read.edit.outputSize)
        read.validate()
        // "Original" with no crop frees it; Undo brings it back.
        val freed = read.commit(read.edit, now = 5, outputSize = null)
        assertNull(freed.edit.outputSize)
        assertEquals(tall, freed.undo().edit.outputSize)
        assertNull(base.edit.outputSize)
    }

    @Test fun aFrameOfAnOddOrImpossibleSizeIsRefused() {
        val base = project()
        assertEquals(base, base.commit(base.edit, now = 3, outputSize = Size(1081.0, 1920.0)))
        assertEquals(base, base.commit(base.edit, now = 3, outputSize = Size(0.0, 1920.0)))
        assertTrue(EditState.validOutputSize(Size(2.0, 16384.0)))
        assertFalse(EditState.validOutputSize(Size(2.0, 16386.0)))
    }

    @Test fun aSequenceStepKeepsTheFrameToo() {
        val state = EditState(listOf(TimelineClip(id = "C1", sourceID = "S1", start = 0.0, end = 10.0)), outputSize = Size(1080.0, 1080.0))
        val json = documentJson.encodeToString(EditState.serializer(), state)
        assertTrue(json, json.endsWith(""""outputSize":[1080.0,1080.0]}"""))
        assertEquals(state, documentJson.decodeFromString(EditState.serializer(), json))
        val step = EditSnapshot.committed(state, 0)
        assertEquals(state.outputSize, EditSnapshot.decoded(step.data()).state.outputSize)
    }

    // MARK: Log footage

    /** Middle grey recorded in Apple Log shows as middle grey: the viewing transform keeps 0.18 (LogColor.swift:86-89). */
    @Test fun logFootageIsDecodedToDisplayBeforeTheGrade() {
        val code = LogColor.encodeValue(0.18)
        val shown = ColorGrade.applyLog(ColorSettings(), null, null, LUTInput.AppleLog, code, code, code)
        for (channel in shown) assertEquals(LogColor.toDisplay(0.18), channel, 1e-9)
        // The table takes code values and gives the picture as viewed.
        val table = ColorGrade.table(ColorSettings(), sourceLog = LUTInput.AppleLog)
        val last = ColorGrade.DIMENSION - 1
        val white = ((last * ColorGrade.DIMENSION + last) * ColorGrade.DIMENSION + last) * 3
        val expected = LogColor.display(LogColor.decode(doubleArrayOf(1.0, 1.0, 1.0), LUTInput.AppleLog)).map { LogColor.toDisplay(it) }
        for (c in 0..2) assertEquals(expected[c], table[white + c].toDouble(), 1e-6)
        // Exposure acts on the decoded light, with its headroom: a stop up on grey is grey twice as bright, as viewed.
        val brighter = ColorGrade.applyLog(ColorSettings(exposure = 1.0), null, null, LUTInput.AppleLog, code, code, code)
        assertEquals(LogColor.toDisplay(LogColor.display(doubleArrayOf(0.36, 0.36, 0.36))[0]), brighter[0], 1e-9)
        // A look applies to the picture as viewed, at the grade's strength.
        val mono = ColorGrade.applyLog(ColorSettings(look = ColorLook.Mono, amount = 0.5), null, null, LUTInput.AppleLog, code, code, code)
        assertEquals(LogColor.toDisplay(0.18), mono[0], 1e-9)
    }
}
