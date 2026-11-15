package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The decoded sound a transcription leaves in the cache when the app dies under it. */
class SubtitleScratchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun whatAnEarlierJobLeftIsRemoved() {
        val cache = temporary.root
        val left = File(cache, "subtitles-1f0e").apply { mkdirs() }
        File(left, "0.pcm").writeBytes(ByteArray(64))
        val other = File(cache, "export-black.png").apply { writeText("picture") }
        val folder = File(cache, "image_cache").apply { mkdirs() }
        val named = File(cache, "subtitles-notes.txt").apply { writeText("a file, not a job's folder") }

        assertEquals(1, SubtitleScratch.clear(cache))

        assertFalse(left.exists())
        assertTrue(other.exists())
        assertTrue(folder.exists())
        assertTrue(named.exists())
        assertEquals(0, SubtitleScratch.clear(cache))
    }

    @Test fun aRunningJobKeepsItsFolder() {
        val cache = temporary.root
        val left = File(cache, "subtitles-old").apply { mkdirs() }
        val first = SubtitleScratch.open(cache)
        val second = SubtitleScratch.open(cache)
        File(first, "0.pcm").writeBytes(ByteArray(64))
        assertTrue(first.isDirectory && second.isDirectory && first != second)
        assertEquals(cache, first.parentFile)

        assertEquals(1, SubtitleScratch.clear(cache))
        assertFalse(left.exists())
        assertTrue(File(first, "0.pcm").exists())
        assertTrue(second.exists())

        SubtitleScratch.close(first)
        assertFalse(first.exists())
        assertTrue(second.exists())
        // A folder that is closed is no longer protected, should something put it back.
        first.mkdirs()
        assertEquals(1, SubtitleScratch.clear(cache))
        assertTrue(second.exists())
        SubtitleScratch.close(second)
        assertEquals(0, SubtitleScratch.clear(cache))
    }

    @Test fun aCacheThatIsNotThereHasNothingToClear() {
        assertEquals(0, SubtitleScratch.clear(File(temporary.root, "missing")))
    }
}
