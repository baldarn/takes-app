package app.takes.restoration

import app.takes.core.Resampler
import app.takes.core.SpeechDetailChannel
import app.takes.core.StudioRestorationChannel
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechModelFetcher
import app.takes.speech.SpeechModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The two restoration models as published, and their networks run by ONNX Runtime on this computer with the
 * core's signal processing, on a real recording. The models (about 470 MB) and the recording are downloaded
 * once into the folder -Prestoration.models names; without it these tests are skipped. The printed speeds
 * and levels are what was measured: a check that the pieces fit, not of how it sounds.
 */
class RestorationNetworksTest {
    private val folder = System.getProperty("restoration.models")?.let(::File)

    private object Https : SpeechModelSource {
        override fun open(url: String, offset: Long): SpeechModelSource.Opened {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Takes")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            return when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> SpeechModelSource.Opened(connection.inputStream, offset)
                HttpURLConnection.HTTP_OK -> SpeechModelSource.Opened(connection.inputStream, 0)
                else -> throw java.io.IOException("HTTP ${connection.responseCode} for $url")
            }
        }
    }

    private fun installed(model: SpeechModel): File {
        assumeTrue("No -Prestoration.models=<folder>: model tests skipped", folder != null)
        assumeTrue("no ONNX Runtime for this computer", System.getProperty("takes.onnxruntime.library") != null)
        val at = File(folder, model.folder)
        // Files put there by hand are checked and taken; otherwise what is missing is fetched.
        if (!model.installed(at) && !model.adopt(at)) SpeechModelFetcher.download(model, at, Https)
        return at
    }

    /** Whisper's English test recording 0.wav, 16 kHz speech, as 48 kHz: nothing in it above 8 kHz. */
    private fun speech(): FloatArray {
        val file = File(folder, "recordings/0.wav")
        val url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c/test_wavs/0.wav"
        val hash = "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!file.isFile || sha(file) != hash) {
            file.parentFile.mkdirs()
            Https.open(url, 0).stream.use { input -> file.outputStream().use { input.copyTo(it) } }
            assertEquals(hash, sha(file))
        }
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        // 16-bit mono PCM after a 44-byte header, as this file is.
        assertEquals(16000, bytes.getInt(24))
        val pcm = FloatArray((bytes.limit() - 44) / 2) { bytes.getShort(44 + 2 * it) / 32768f }
        val out = ArrayList<Float>()
        Resampler(16000, 48000).apply {
            write(pcm, 0, pcm.size) { b, c -> for (i in 0 until c) out += b[i] }
            finish { b, c -> for (i in 0 until c) out += b[i] }
        }
        return out.toFloatArray()
    }

    private fun run(input: FloatArray, write: (FloatArray, Int, Int, (FloatArray, Int) -> Unit) -> Unit, finish: ((FloatArray, Int) -> Unit) -> Unit): FloatArray {
        val out = FloatArray(input.size)
        var made = 0
        val take = { b: FloatArray, c: Int -> System.arraycopy(b, 0, out, made, c); made += c }
        var at = 0
        while (at < input.size) {
            val n = minOf(4096, input.size - at)
            write(input, at, n, take)
            at += n
        }
        finish(take)
        assertEquals(input.size, made)
        return out
    }

    /** The power of [x] between [low] and [high] Hz, summed over 50 Hz steps, in dB. */
    private fun band(x: FloatArray, low: Double, high: Double): Double {
        var total = 0.0
        var hz = low
        while (hz < high) {
            var re = 0.0; var im = 0.0
            for (i in x.indices) { val a = 2 * PI * hz * i / 48000; re += x[i] * cos(a); im += x[i] * sin(a) }
            total += re * re + im * im
            hz += 50
        }
        return 10 * log10(total + 1e-20)
    }

    /** The loudness of 20 ms frames, for comparing the shape of speech over time. */
    private fun envelope(x: FloatArray) = DoubleArray(x.size / 960) { f -> sqrt((0 until 960).sumOf { x[f * 960 + it].toDouble() * x[f * 960 + it] } / 960) }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb) }
        return ab / sqrt(aa * bb)
    }

    @Test fun describesThePublishedModels() {
        assertEquals(56_235_055L, RestorationModels.Lava.bytes)
        assertEquals(415_446_261L, RestorationModels.VoiceFixer.bytes)
        for (model in listOf(RestorationModels.Lava, RestorationModels.VoiceFixer)) {
            for (file in model.files) {
                assertTrue(file.url.startsWith("https://") && file.url.endsWith("/" + file.name))
                assertTrue(Regex("[0-9a-f]{64}").matches(file.sha256))
            }
        }
        // Each patch lies inside its file.
        for ((name, offset) in RestorationModels.CONTEXT_PATCHES) assertTrue(offset + 2 <= RestorationModels.VoiceFixer.files.single { it.name == name }.bytes)
    }

    @Test fun lavaAddsDetailAboveWhatTheRecordingHolds() {
        val network = LavaNetwork.open(installed(RestorationModels.Lava))
        val input = speech()
        val started = System.nanoTime()
        val channel = SpeechDetailChannel(network, 1f)
        val output = run(input, channel::write, channel::finish)
        val seconds = (System.nanoTime() - started) / 1e9
        network.close()
        val lowBefore = band(input, 100.0, 3900.0); val lowAfter = band(output, 100.0, 3900.0)
        val highBefore = band(input, 9000.0, 16000.0); val highAfter = band(output, 9000.0, 16000.0)
        println("LavaSR: %.1f s of speech in %.2f s (%.1f× real time); 0.1–3.9 kHz %.1f → %.1f dB, 9–16 kHz %.1f → %.1f dB"
            .format(input.size / 48000.0, seconds, input.size / 48000.0 / seconds, lowBefore, lowAfter, highBefore, highAfter))
        assertTrue(output.all { it.isFinite() })
        // The voice itself is the recording's; above it, where it had nothing, there is now sound.
        assertEquals(lowBefore, lowAfter, 0.5)
        assertTrue(highAfter > highBefore + 20)
    }

    @Test fun voiceFixerResynthesizesTheSameSpeech() {
        val network = VoiceFixerNetwork.open(installed(RestorationModels.VoiceFixer))
        val input = speech()
        val started = System.nanoTime()
        val channel = StudioRestorationChannel(network, network.bank, 1f)
        val output = run(input, channel::write, channel::finish)
        val seconds = (System.nanoTime() - started) / 1e9
        network.close()
        val similarity = correlation(envelope(input), envelope(output))
        println("VoiceFixer: %.1f s of speech in %.2f s (%.2f× real time); 20 ms loudness correlation %.3f"
            .format(input.size / 48000.0, seconds, input.size / 48000.0 / seconds, similarity))
        assertTrue(output.all { it.isFinite() })
        // Neural resynthesis: not the same samples, but the same words at the same times.
        assertTrue(similarity > 0.9)
    }
}
