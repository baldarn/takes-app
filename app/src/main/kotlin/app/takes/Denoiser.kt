package app.takes

import app.takes.core.AudioChain
import app.takes.core.AudioChainProcessor
import app.takes.core.AudioEffect
import app.takes.core.AudioEffectKind
import app.takes.core.AudioModel
import app.takes.core.TrackAudioRegions
import app.takes.core.TrackMixPiece
import app.takes.rnnoise.ChannelDenoiser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * The renderer of Enhance audio's chains: the sound of a recording through a
 * chain of stages, made once per source and chain whatever plays it
 * ([TrackAudioRegions.cacheKey]), as a 48 kHz WAV in the project's `Cache`
 * folder; the recording itself is never changed. The mixer plays the
 * processed file in place of the recording for every piece with that chain.
 *
 * The stages this device plays are the filters ([AudioChainProcessor]) and
 * noise reduction (RNNoise, [AudioEffectKind.VoiceIsolation]); a chain with
 * another model is not made, and its pieces play the recording. A chain that
 * is noise reduction alone keeps the name of the file of cleaned sound it
 * always had, so what was made before is found again.
 */
object Denoiser {
    private val working = Dispatchers.Default.limitedParallelism(1)

    private fun folder(file: File) = File(file.parentFile, "Cache")

    /** The noise reduction of a clip at [amount], as a chain: the stage a clip's `denoise` stands for. */
    fun noiseReduction(amount: Double): List<AudioEffect> =
        listOf(AudioEffect(id = "", kind = AudioEffectKind.VoiceIsolation, amount = amount, model = AudioModel.RNNoise))

    /** The file [file]'s sound through [effects] is kept in. */
    private fun cached(file: File, effects: List<AudioEffect>): File {
        val active = AudioChain.active(effects)
        val alone = active.singleOrNull()?.takeIf { it.kind == AudioEffectKind.VoiceIsolation }
        if (alone != null) return File(folder(file), "${file.name}.clean-${(alone.amount * 100).roundToInt()}.wav")
        val key = TrackAudioRegions.cacheKey("", active)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(folder(file), "${file.name}.chain-$digest.wav")
    }

    /** Whether this device plays every stage of [effects] that changes the sound. */
    fun playable(effects: List<AudioEffect>): Boolean = AudioChain.active(effects).all {
        it.supported && (!it.kind.isNeural || (it.kind == AudioEffectKind.VoiceIsolation && it.model == AudioModel.RNNoise))
    }

    /** The processed sound of [file] through [effects], if it has already been made; null too for a chain that changes nothing. */
    fun ready(file: File, effects: List<AudioEffect>): File? =
        if (AudioChain.active(effects).isEmpty()) null else cached(file, effects).takeIf { it.isFile }

    /** The cleaned sound of a clip at [amount], if it has already been made. */
    fun ready(file: File, amount: Double): File? = ready(file, noiseReduction(amount))

    /** What a piece of the mix plays, of the recordings in [files]: its chain's processed sound when made, null for the recording. */
    fun input(files: Map<String, File>): (TrackMixPiece) -> File? = { piece -> files[piece.sourceID]?.let { ready(it, piece.chain) } }

    /** Makes the cleaned sound of a clip at [amount] if needed. */
    suspend fun process(file: File, amount: Double, progress: (Float) -> Unit = {}): File? = process(file, noiseReduction(amount), progress)

    /**
     * Makes the processed sound of [file] through [effects] if needed. Null when
     * the chain changes nothing, when this device cannot play one of its
     * stages, or when the recording has no sound that can be processed.
     */
    suspend fun process(file: File, effects: List<AudioEffect>, progress: (Float) -> Unit = {}): File? = withContext(working) {
        val active = AudioChain.active(effects)
        if (active.isEmpty() || !playable(active)) return@withContext null
        ready(file, active)?.let { return@withContext it }
        val output = cached(file, active)
        output.parentFile?.mkdirs()
        // Hidden until complete: a half-written file must never be played.
        val partial = File(output.parentFile, ".${output.name}.tmp")
        try {
            val context = coroutineContext
            if (render(file, active, partial, progress) { context.ensureActive() } && partial.renameTo(output)) output else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null // no native library for this processor: the recorded sound is used
        } finally {
            partial.delete()
        }
    }

    /** Processed samples of one channel waiting for the other channels, so frames can be written interleaved. */
    private class Pending {
        var samples = FloatArray(8192)
        var size = 0
        fun take(block: FloatArray, count: Int) {
            if (size + count > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + count))
            System.arraycopy(block, 0, samples, size, count)
            size += count
        }
    }

    /**
     * One channel through the stages in order, as it arrives: each noise reduction gives back exactly as many
     * samples as it is given, one frame late, and the filters follow it sample by sample.
     */
    private class ChannelChain(stages: List<AudioEffect>, private val sink: Pending) : AutoCloseable {
        private sealed interface Step
        private class Neural(val denoiser: ChannelDenoiser) : Step
        private class Filter(val processor: AudioChainProcessor) : Step
        private val steps: List<Step> = stages.map {
            if (it.kind.isNeural) Neural(ChannelDenoiser(it.amount.toFloat())) else Filter(AudioChainProcessor(listOf(it), channels = 1))
        }

        fun write(samples: FloatArray, count: Int) = pass(0, samples, count)

        private fun pass(from: Int, samples: FloatArray, count: Int) {
            if (from == steps.size) { sink.take(samples, count); return }
            when (val step = steps[from]) {
                is Neural -> step.denoiser.write(samples, 0, count) { block, valid -> pass(from + 1, block, valid) }
                is Filter -> { step.processor.process(samples, 0, 0, count); pass(from + 1, samples, count) }
            }
        }

        fun finish() = finish(0)

        private fun finish(from: Int) {
            if (from == steps.size) return
            val step = steps[from]
            if (step is Neural) step.denoiser.finish { block, valid -> pass(from + 1, block, valid) }
            finish(from + 1)
        }

        override fun close() { for (step in steps) if (step is Neural) runCatching { step.denoiser.close() } }
    }

    private fun render(file: File, stages: List<AudioEffect>, target: File, progress: (Float) -> Unit, checkpoint: () -> Unit): Boolean {
        var chains = emptyList<ChannelChain>()
        val output = BufferedOutputStream(FileOutputStream(target), 1 shl 16)
        try {
            var inputs = emptyList<Channel48k>()
            var pending = emptyList<Pending>()
            var length = 0L
            var bytes = ByteBuffer.allocate(0)
            var frames = 0L
            output.write(ByteArray(44)) // the header is filled in at the end

            // Writes every frame all channels have delivered.
            fun drain() {
                val ready = pending.minOf { it.size }
                if (ready == 0) return
                val channels = pending.size
                if (bytes.capacity() < ready * channels * 2) bytes = ByteBuffer.allocate(ready * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.clear()
                for (frame in 0 until ready) for (channel in pending) {
                    bytes.putShort((channel.samples[frame] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
                output.write(bytes.array(), 0, ready * channels * 2)
                frames += ready
                for (channel in pending) {
                    System.arraycopy(channel.samples, ready, channel.samples, 0, channel.size - ready)
                    channel.size -= ready
                }
            }

            val decoded = decodeAudio(
                file, checkpoint,
                begin = { format ->
                    length = format.durationUs
                    inputs = List(format.channels) { Channel48k(it, format.channels, format.sampleRate) }
                    pending = List(format.channels) { Pending() }
                    // Each channel has its own state, as in the original.
                    chains = List(format.channels) { ChannelChain(stages, pending[it]) }
                },
                samples = { block, time ->
                    for (channel in inputs.indices) {
                        val count = inputs[channel].read(block)
                        chains[channel].write(inputs[channel].samples, count)
                    }
                    drain()
                    if (length > 0) progress((time.toFloat() / length).coerceIn(0f, 1f))
                },
            )
            if (decoded != true) return false
            for (chain in chains) chain.finish()
            drain()
            output.close()
            if (frames == 0L) return false
            writeWavHeader(target, Channel48k.RATE, chains.size, frames)
            progress(1f)
            return true
        } finally {
            runCatching { output.close() }
            chains.forEach { runCatching { it.close() } }
        }
    }
}
