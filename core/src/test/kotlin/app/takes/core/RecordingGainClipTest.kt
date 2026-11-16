package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The recorder's gain reaches a take as the level of its clip, on the original's grid. */
class RecordingGainClipTest {
    private val take = SourceMedia(filename = "a.mp4", duration = 10.0)

    @Test
    fun noGainLeavesTheClipAsItWas() {
        val clip = Project("x").addRecording(take).captureLedger.single()
        assertNull(clip.audio)
    }

    @Test
    fun gainBecomesTheClipLevel() {
        val clip = Project("x").addRecording(take, gainDB = 6.2).captureLedger.single()
        assertEquals(6.0, clip.audio!!.volumeDB, 0.0)
    }

    @Test
    fun gainIsBoundedToTheRecordersRange() {
        assertEquals(-40.0, Project("x").addRecording(take, gainDB = -70.0).captureLedger.single().audio!!.volumeDB, 0.0)
        assertEquals(12.0, Project("x").addRecording(take, gainDB = 30.0).captureLedger.single().audio!!.volumeDB, 0.0)
    }

    @Test
    fun aLevelNeedsNoNewerSchemaThanTheEditorsOwnLevel() {
        val edited = TimelineClip(sourceID = take.id, start = 0.0, end = 10.0, audio = ClipAudio(volumeDB = -12.0))
        assertEquals(edited.schema, Project("x").addRecording(take, gainDB = -12.0).schemaVersion)
    }
}
