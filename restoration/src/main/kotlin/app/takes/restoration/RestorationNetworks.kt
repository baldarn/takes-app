package app.takes.restoration

import app.takes.core.LavaDsp
import app.takes.core.SpeechDetailNetwork
import app.takes.core.StudioRestorationNetwork
import app.takes.core.VoiceFixerDsp
import app.takes.deepfilter.OnnxSession
import app.takes.speech.SpeechModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.pow

/** A model's files are missing, damaged or refused by ONNX Runtime. */
class RestorationModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun floats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/** The threads a network runs on: up to four, as the phone has them. */
private fun threads(): Int = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

// The folders whose files were read and matched their SHA-256 in this process.
private val verified = HashSet<String>()

/**
 * Checks every file of [model] in [folder] against its SHA-256 before ONNX Runtime reads them, once per
 * process, as the original checks its models before loading them (YouRec/StudioVoiceProcessor.swift:13-34,
 * YouRec/StudioRestorationProcessor.swift:213-229).
 */
private fun verify(model: SpeechModel, folder: File) {
    synchronized(verified) {
        if (folder.path in verified) return
        if (!model.installed(folder)) throw RestorationModelException("${model.name} is not downloaded")
        val damaged = model.damaged(folder)
        if (damaged.isNotEmpty()) throw RestorationModelException("${model.name}: ${damaged.joinToString()} damaged")
        verified += folder.path
    }
}

private fun <T> loading(model: SpeechModel, open: () -> T): T = try {
    open()
} catch (error: IllegalStateException) {
    throw RestorationModelException("${model.name} could not be loaded", error)
} catch (error: LinkageError) {
    // No ONNX Runtime, as on 32-bit x86, for which the app carries none.
    throw RestorationModelException("${model.name} could not be loaded", error)
}

/**
 * LavaSR v2's network ([RestorationModels.Lava]) run by ONNX Runtime: the backbone takes `mel` `[1,80,375]` to
 * `hidden` `[1,375,512]`, the spectral head that to `real` and `imag` `[1,1025,375]`, magnitude clamped at 1000
 * as in the original's conversion (scripts/convert-lavasr.py:37-41). One window at a time; [close] after use.
 */
class LavaNetwork private constructor(private val backbone: OnnxSession, private val head: OnnxSession) : SpeechDetailNetwork, AutoCloseable {
    private val mel = floats(LavaDsp.MELS * LavaDsp.FRAMES)
    private val hidden = floats(LavaDsp.FRAMES * HIDDEN)
    private val real = floats(LavaDsp.BINS * LavaDsp.FRAMES)
    private val imaginary = floats(LavaDsp.BINS * LavaDsp.FRAMES)

    @Synchronized
    override fun spectrum(mel: FloatArray, real: FloatArray, imaginary: FloatArray) {
        this.mel.clear(); this.mel.put(mel, 0, LavaDsp.MELS * LavaDsp.FRAMES).rewind()
        backbone.run(arrayOf(this.mel), arrayOf(longArrayOf(1, LavaDsp.MELS.toLong(), LavaDsp.FRAMES.toLong())), arrayOf(hidden))
        hidden.rewind()
        head.run(arrayOf(hidden), arrayOf(longArrayOf(1, LavaDsp.FRAMES.toLong(), HIDDEN.toLong())), arrayOf(this.real, this.imaginary))
        this.real.rewind(); this.real.get(real, 0, LavaDsp.BINS * LavaDsp.FRAMES)
        this.imaginary.rewind(); this.imaginary.get(imaginary, 0, LavaDsp.BINS * LavaDsp.FRAMES)
    }

    override fun close() {
        backbone.close()
        head.close()
    }

    companion object {
        private const val HIDDEN = 512

        /** Loads the model from [folder], where [app.takes.speech.SpeechModelFetcher] put it. */
        fun open(folder: File): LavaNetwork {
            val model = RestorationModels.Lava
            verify(model, folder)
            return loading(model) {
                val backbone = OnnxSession.open(File(folder, "enhancer_backbone.onnx"), listOf("mel"), listOf("hidden"), threads())
                val head = try {
                    OnnxSession.open(File(folder, "enhancer_spec_head.onnx"), listOf("hidden"), listOf("real", "imag"), threads())
                } catch (error: Throwable) {
                    backbone.close(); throw error
                }
                LavaNetwork(backbone, head)
            }
        }
    }
}

/**
 * VoiceFixer's networks ([RestorationModels.VoiceFixer]) run by ONNX Runtime, as the original's single Core ML
 * graph (scripts/convert-voicefixer.py:36-41): the analysis network takes the mel magnitudes `mel_orig`
 * `[1,1,256,128]` to the restored log10 mel, which is clamped at 5 and raised to a power of ten, and the
 * vocoder takes that to `wav`, `[1,1,114660]` samples at 44.1 kHz. Both graphs are read with their input
 * fixed at 256 frames ([RestorationModels.CONTEXT_PATCHES]). [bank] is the model's mel filterbank. One window
 * at a time; [close] after use.
 */
class VoiceFixerNetwork private constructor(private val analysis: OnnxSession, private val vocoder: OnnxSession, val bank: FloatArray) :
    StudioRestorationNetwork, AutoCloseable {
    private val mel = floats(VoiceFixerDsp.FRAMES * VoiceFixerDsp.MELS)
    private val restored = floats(VoiceFixerDsp.FRAMES * VoiceFixerDsp.MELS)
    private val audio = floats(VoiceFixerDsp.OUTPUT)
    private val values = FloatArray(VoiceFixerDsp.FRAMES * VoiceFixerDsp.MELS)
    private val shape = longArrayOf(1, 1, VoiceFixerDsp.FRAMES.toLong(), VoiceFixerDsp.MELS.toLong())

    @Synchronized
    override fun restore(mel: FloatArray, audio: FloatArray) {
        this.mel.clear(); this.mel.put(mel, 0, values.size).rewind()
        analysis.run(arrayOf(this.mel), arrayOf(shape), arrayOf(restored))
        restored.rewind(); restored.get(values)
        for (i in values.indices) values[i] = 10.0.pow(min(5f, values[i]).toDouble()).toFloat()
        restored.rewind(); restored.put(values).rewind()
        vocoder.run(arrayOf(restored), arrayOf(shape), arrayOf(this.audio))
        this.audio.rewind(); this.audio.get(audio, 0, VoiceFixerDsp.OUTPUT)
    }

    override fun close() {
        analysis.close()
        vocoder.close()
    }

    companion object {
        /** Loads the model from [folder], where [app.takes.speech.SpeechModelFetcher] put it. */
        fun open(folder: File): VoiceFixerNetwork {
            val model = RestorationModels.VoiceFixer
            verify(model, folder)
            val bank = VoiceFixerDsp.bank(File(folder, RestorationModels.MEL_FILTERS).readBytes())
                ?: throw RestorationModelException("${model.name}: the mel filterbank is not the expected one")
            fun patch(name: String) = OnnxSession.Patch(RestorationModels.CONTEXT_PATCHES.getValue(name), RestorationModels.FRAMES_501, RestorationModels.FRAMES_256)
            return loading(model) {
                val analysis = OnnxSession.open(File(folder, RestorationModels.ANALYSIS), listOf("mel_orig"), listOf("mel"), threads(), patch(RestorationModels.ANALYSIS))
                val vocoder = try {
                    OnnxSession.open(File(folder, RestorationModels.VOCODER), listOf("mel"), listOf("wav"), threads(), patch(RestorationModels.VOCODER))
                } catch (error: Throwable) {
                    analysis.close(); throw error
                }
                VoiceFixerNetwork(analysis, vocoder, bank)
            }
        }
    }
}
