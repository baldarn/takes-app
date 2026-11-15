package app.takes.speech

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random

/**
 * The fetcher against a web server on this computer, with small invented files. No model
 * and no network are involved.
 */
class SpeechModelFetcherTest {
    private lateinit var server: FileServer
    private lateinit var folder: File
    private val content get() = server.content
    private val ranges get() = server.ranges
    private var honorRanges: Boolean
        get() = server.honorRanges
        set(value) { server.honorRanges = value }

    private val encoder = bytes(300_000, 1)
    private val decoder = bytes(500_000, 2)
    private val tokens = bytes(40_000, 3)
    private lateinit var model: SpeechModel

    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before fun start() {
        folder = Files.createTempDirectory("speech-fetch").toFile()
        server = FileServer()
        content["m-encoder.int8.onnx"] = encoder
        content["m-decoder.int8.onnx"] = decoder
        content["m-tokens.txt"] = tokens
        model = SpeechModel.whisper(
            id = "test@1", name = "m", base = server.base,
            encoder = encoder.size.toLong() to sha(encoder), decoder = decoder.size.toLong() to sha(decoder), tokens = tokens.size.toLong() to sha(tokens),
        )
    }

    @After fun stop() {
        server.close()
        folder.deleteRecursively()
    }

    /** A connection that breaks after [limit] bytes of the decoder. */
    private fun breaking(limit: Int) = SpeechModelSource { url, offset ->
        val opened = HttpSource.open(url, offset)
        if (!url.endsWith("decoder.int8.onnx")) return@SpeechModelSource opened
        var left = limit
        SpeechModelSource.Opened(object : FilterInputStream(opened.stream) {
            override fun read(buffer: ByteArray, from: Int, count: Int): Int {
                if (left <= 0) throw IOException("connection reset")
                return super.read(buffer, from, minOf(count, left)).also { if (it > 0) left -= it }
            }
        }, opened.offset)
    }

    private fun names() = folder.list()!!.sorted()

    @Test fun downloadsVerifiesAndMarksComplete() {
        assertFalse(model.installed(folder))
        val seen = mutableListOf<Long>()
        SpeechModelFetcher.download(model, folder, HttpSource, { done, total -> assertEquals(model.bytes, total); seen += done })
        assertTrue(model.installed(folder))
        assertEquals(listOf("m-decoder.int8.onnx", "m-encoder.int8.onnx", "m-tokens.txt", "ready"), names())
        assertArrayEquals(decoder, File(folder, "m-decoder.int8.onnx").readBytes())
        assertEquals(emptyList<String>(), model.damaged(folder))
        assertEquals(seen.sorted(), seen)
        assertEquals(model.bytes, seen.last())
        assertEquals(840_000L, model.bytes)

        // Nothing is fetched a second time.
        ranges.clear()
        SpeechModelFetcher.download(model, folder, HttpSource)
        assertEquals(emptyList<String>(), ranges)
    }

    @Test fun resumesAPartialFileWhereItStopped() {
        val failure = assertThrows(IOException::class.java) { SpeechModelFetcher.download(model, folder, breaking(200_000)) }
        assertEquals("connection reset", failure.message)
        // The finished file is kept, the broken one has only its temporary name.
        assertEquals(listOf("m-decoder.int8.onnx.part", "m-encoder.int8.onnx"), names())
        assertEquals(200_000L, File(folder, "m-decoder.int8.onnx.part").length())
        assertFalse(model.installed(folder))

        ranges.clear()
        val seen = mutableListOf<Long>()
        SpeechModelFetcher.download(model, folder, HttpSource, { done, _ -> seen += done })
        // The encoder is not asked for again; the decoder continues from byte 200,000.
        assertEquals(listOf("bytes=200000-", ""), ranges)
        // Progress counts what was already there: the encoder, then the decoder from where it stopped.
        assertEquals(300_000L, seen[0])
        assertTrue(seen[1] > 500_000)
        assertTrue(model.installed(folder))
        assertArrayEquals(decoder, File(folder, "m-decoder.int8.onnx").readBytes())
    }

    @Test fun startsAgainWhenTheServerCannotResume() {
        assertThrows(IOException::class.java) { SpeechModelFetcher.download(model, folder, breaking(200_000)) }
        honorRanges = false
        SpeechModelFetcher.download(model, folder, HttpSource)
        assertTrue(model.installed(folder))
        assertArrayEquals(decoder, File(folder, "m-decoder.int8.onnx").readBytes())
    }

    @Test fun aConnectionThatEndsEarlyIsNotACompleteFile() {
        // The server closes politely after half the file: no exception from the stream itself.
        val half = SpeechModelSource { url, offset ->
            val opened = HttpSource.open(url, offset)
            if (!url.endsWith("decoder.int8.onnx")) return@SpeechModelSource opened
            var left = 250_000
            SpeechModelSource.Opened(object : FilterInputStream(opened.stream) {
                override fun read(buffer: ByteArray, from: Int, count: Int): Int {
                    if (left <= 0) return -1
                    return super.read(buffer, from, minOf(count, left)).also { if (it > 0) left -= it }
                }
            }, opened.offset)
        }
        assertThrows(IOException::class.java) { SpeechModelFetcher.download(model, folder, half) }
        assertEquals(250_000L, File(folder, "m-decoder.int8.onnx.part").length())
        assertFalse(File(folder, "m-decoder.int8.onnx").exists())
        SpeechModelFetcher.download(model, folder, HttpSource)
        assertTrue(model.installed(folder))
    }

    @Test fun rejectsWrongContent() {
        content["m-decoder.int8.onnx"] = decoder.copyOf().also { it[123_456] = (it[123_456] + 1).toByte() }
        val failure = assertThrows(SpeechModelCorrupt::class.java) { SpeechModelFetcher.download(model, folder, HttpSource) }
        assertEquals("m-decoder.int8.onnx", failure.file)
        // Neither under its name nor as a part to resume: the next attempt starts it again.
        assertEquals(listOf("m-encoder.int8.onnx"), names())
        assertFalse(model.installed(folder))

        content["m-decoder.int8.onnx"] = decoder
        SpeechModelFetcher.download(model, folder, HttpSource)
        assertTrue(model.installed(folder))
    }

    @Test fun rejectsAFileLongerThanDescribed() {
        content["m-tokens.txt"] = tokens + ByteArray(100_000)
        assertThrows(SpeechModelCorrupt::class.java) { SpeechModelFetcher.download(model, folder, HttpSource) }
        assertFalse(File(folder, "m-tokens.txt").exists())
        assertFalse(File(folder, "m-tokens.txt.part").exists())
        assertFalse(model.installed(folder))
    }

    @Test fun aPartOfAnotherFileIsFoundOut() {
        // A leftover that is not the beginning of this file: resumed, hashed, rejected, and fetched whole the next time.
        folder.mkdirs()
        File(folder, "m-decoder.int8.onnx.part").writeBytes(ByteArray(1000))
        assertThrows(SpeechModelCorrupt::class.java) { SpeechModelFetcher.download(model, folder, HttpSource) }
        SpeechModelFetcher.download(model, folder, HttpSource)
        assertArrayEquals(decoder, File(folder, "m-decoder.int8.onnx").readBytes())
    }

    @Test fun cancellingKeepsWhatArrived() {
        var done = 0L
        assertThrows(SpeechModelCancelled::class.java) {
            SpeechModelFetcher.download(model, folder, HttpSource, { bytes, _ -> done = bytes }, { done >= 500_000 })
        }
        assertFalse(model.installed(folder))
        assertTrue(File(folder, "m-decoder.int8.onnx.part").length() >= 200_000)
        ranges.clear()
        SpeechModelFetcher.download(model, folder, HttpSource)
        // The decoder continues; the token table had not been started.
        assertEquals(2, ranges.size)
        assertTrue(ranges[0].startsWith("bytes="))
        assertEquals("", ranges[1])
        assertTrue(model.installed(folder))
    }

    @Test fun httpErrorsLeaveNothingBehind() {
        content.remove("m-encoder.int8.onnx")
        val failure = assertThrows(IOException::class.java) { SpeechModelFetcher.download(model, folder, HttpSource) }
        assertTrue(failure.message!!.endsWith("HTTP 404"))
        assertEquals(emptyList<String>(), names())
    }

    @Test fun installedNoticesMissingShortAndForeignFiles() {
        SpeechModelFetcher.download(model, folder, HttpSource)
        // Another revision of the model does not count as this one.
        assertFalse(model.copy(id = "test@2").installed(folder))

        val file = File(folder, "m-tokens.txt")
        file.writeBytes(tokens.copyOf(1000))
        assertFalse(model.installed(folder))
        assertEquals(listOf("m-tokens.txt"), model.damaged(folder))
        // Downloading repairs exactly that file.
        ranges.clear()
        SpeechModelFetcher.download(model, folder, HttpSource)
        assertEquals(1, ranges.size)
        assertTrue(model.installed(folder))

        // Same length, other content: only reading the files finds it.
        file.writeBytes(tokens.copyOf().also { it[5] = (it[5] + 1).toByte() })
        assertTrue(model.installed(folder))
        assertEquals(listOf("m-tokens.txt"), model.damaged(folder))

        model.remove(folder)
        assertEquals(emptyList<String>(), names())
    }

    @Test fun adoptsFilesCopiedByHand() {
        folder.mkdirs()
        File(folder, "m-encoder.int8.onnx").writeBytes(encoder)
        File(folder, "m-decoder.int8.onnx").writeBytes(decoder)
        assertFalse(model.adopt(folder))
        assertFalse(model.installed(folder))
        File(folder, "m-tokens.txt").writeBytes(tokens)
        // Complete, and still not trusted until verified.
        assertFalse(model.installed(folder))
        assertTrue(model.adopt(folder))
        assertTrue(model.installed(folder))
    }

    @Test fun worksFromFilesWithoutAnyNetwork() {
        // The same model on a memory card or in a folder the user picked.
        val origin = Files.createTempDirectory("speech-origin").toFile()
        try {
            for ((name, bytes) in content) File(origin, name).writeBytes(bytes)
            val local = model.copy(
                encoder = model.encoder.copy(url = File(origin, "m-encoder.int8.onnx").toURI().toString()),
                decoder = model.decoder.copy(url = File(origin, "m-decoder.int8.onnx").toURI().toString()),
                tokens = model.tokens.copy(url = File(origin, "m-tokens.txt").toURI().toString()),
            )
            val files = SpeechModelSource { url, offset ->
                val stream: InputStream = File(java.net.URI(url)).inputStream()
                var skipped = 0L
                while (skipped < offset) skipped += stream.skip(offset - skipped)
                SpeechModelSource.Opened(stream, offset)
            }
            File(folder, "m-decoder.int8.onnx.part").writeBytes(decoder.copyOf(123_457))
            SpeechModelFetcher.download(local, folder, files)
            assertTrue(local.installed(folder))
            assertEquals(emptyList<String>(), local.damaged(folder))
            assertEquals(emptyList<String>(), ranges)
        } finally {
            origin.deleteRecursively()
        }
    }

    @Test fun describesThePublishedModels() {
        for (model in listOf(SpeechModel.WhisperTiny, SpeechModel.WhisperBase, SpeechModel.WhisperSmall)) {
            assertEquals(3, model.files.size)
            for (file in model.files) {
                assertTrue(file.url.startsWith("https://huggingface.co/csukuangfj/sherpa-onnx-whisper-"))
                assertTrue(file.url.endsWith("/" + file.name))
                // A fixed revision, never "main": the hashes below belong to exactly these bytes.
                assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(file.url))
                assertTrue(model.id.endsWith(Regex("/resolve/([0-9a-f]{40})/").find(file.url)!!.groupValues[1]))
                assertTrue(Regex("[0-9a-f]{64}").matches(file.sha256))
            }
        }
        assertEquals(103_609_903L, SpeechModel.WhisperTiny.bytes)
        assertEquals(160_609_290L, SpeechModel.WhisperBase.bytes)
        assertEquals(375_485_327L, SpeechModel.WhisperSmall.bytes)
        assertEquals(SpeechModel.WhisperBase, SpeechModel.Default)
    }
}
