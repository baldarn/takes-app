package app.takes

import app.takes.core.AudioChain
import app.takes.core.AudioChainProcessor
import app.takes.core.AudioEffect
import app.takes.core.AudioEffectKind
import app.takes.core.AudioModel
import app.takes.core.TrackAudioRegions
import app.takes.core.TrackMixPiece
import app.takes.deepfilter.ChannelDeepFilter
import app.takes.deepfilter.DeepFilterModel
import app.takes.rnnoise.ChannelDenoiser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
 * noise reduction ([AudioEffectKind.VoiceIsolation]) by RNNoise or by
 * DeepFilterNet3, whose network the app carries ([DeepFilterModel.bundled]);
 * a chain with another model is not made, and its pieces play the recording. A chain that
 * is RNNoise's noise reduction alone keeps the name of the file of cleaned sound it
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
        val alone = active.singleOrNull()?.takeIf { it.kind == AudioEffectKind.VoiceIsolation && it.model == AudioModel.RNNoise }
        if (alone != null) return File(folder(file), "${file.name}.clean-${(alone.amount * 100).roundToInt()}.wav")
        val key = TrackAudioRegions.cacheKey("", active)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(folder(file), "${file.name}.chain-$digest.wav")
    }

    /** Whether this device plays every stage of [effects] that changes the sound. */
    fun playable(effects: List<AudioEffect>): Boolean = AudioChain.active(effects).all {
        it.supported && (!it.kind.isNeural || (it.kind == AudioEffectKind.VoiceIsolation && (it.model == AudioModel.RNNoise || it.model == AudioModel.DeepFilter)))
    }

    /**
     * DeepFilterNet3's network, loaded on first use (about 9 MB, read from the app itself) and kept: every
     * channel of every chain shares it, each with its own memory. A failure is kept too, so it is not tried again.
     */
    private val deepFilter: Result<DeepFilterModel> by lazy {
        runCatching { DeepFilterModel.bundled() ?: error("This build carries no DeepFilterNet3 model") }
    }

    /** One channel's noise reduction by [effect]'s model, with the calls of RNNoise's [ChannelDenoiser]. */
    private class NoiseReduction(effect: AudioEffect) : AutoCloseable {
        private val rnnoise = if (effect.model == AudioModel.DeepFilter) null else ChannelDenoiser(effect.amount.toFloat())
        private val dfn = if (effect.model == AudioModel.DeepFilter) ChannelDeepFilter(deepFilter.getOrThrow(), effect.amount.toFloat()) else null

        fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
            if (dfn != null) dfn.write(samples, offset, count, output) else rnnoise!!.write(samples, offset, count, output)
        }

        fun finish(output: (FloatArray, Int) -> Unit) { if (dfn != null) dfn.finish(output) else rnnoise!!.finish(output) }

        override fun close() { dfn?.close(); rnnoise?.close() }
    }

    /** The processed sound of [file] through [effects], if it has already been made; null too for a chain that changes nothing. */
    fun ready(file: File, effects: List<AudioEffect>): File? =
        if (AudioChain.active(effects).isEmpty()) null else cached(file, effects).takeIf { it.isFile }

    /** The cleaned sound of a clip at [amount], if it has already been made. */
    fun ready(file: File, amount: Double): File? = ready(file, noiseReduction(amount))

    /**
     * What a piece of the mix plays, of the recordings in [files]: its chain's processed sound when made, else a
     * stretch of it processed for listening ([processWindow]) that holds the whole piece; null for the recording.
     */
    fun input(files: Map<String, File>): (TrackMixPiece) -> ProcessedSound? = { piece ->
        files[piece.sourceID]?.let { file ->
            ready(file, piece.chain)?.let { ProcessedSound(it) } ?: run {
                val from = piece.runs.minOf { minOf(it.sourceStart, it.sourceEnd) }
                val to = piece.runs.maxOf { maxOf(it.sourceStart, it.sourceEnd) }
                windows(file, piece.chain).firstOrNull { it.covers(from, to) }?.let { ProcessedSound(it.file, it.offset) }
            }
        }
    }

    /**
     * What a piece plays in Enhance audio's preview, which plays a few seconds only: its chain's processed
     * sound when made, else the stretch processed for listening that holds the most of the piece, read where
     * it has sound and silent around it; null for the recording.
     */
    fun previewInput(files: Map<String, File>): (TrackMixPiece) -> ProcessedSound? = { piece ->
        files[piece.sourceID]?.let { file ->
            ready(file, piece.chain)?.let { ProcessedSound(it) } ?: run {
                val from = piece.runs.minOf { minOf(it.sourceStart, it.sourceEnd) }
                val to = piece.runs.maxOf { maxOf(it.sourceStart, it.sourceEnd) }
                windows(file, piece.chain).maxByOrNull { minOf(to, it.end) - maxOf(from, it.offset + SETTLE) }
                    ?.takeIf { minOf(to, it.end) > maxOf(from, it.offset + SETTLE) }
                    ?.let { ProcessedSound(it.file, it.offset) }
            }
        }
    }

    /** A stretch of a recording processed for listening: its file, the source second its frame 0 is, and the end. */
    class Window(val file: File, val offset: Double, val end: Double) {
        /** Whether it holds source seconds [from] to [to], past the start a model needs to settle. */
        fun covers(from: Double, to: Double): Boolean = from >= offset + SETTLE - 0.001 && to <= end + 0.001
    }

    /** The stretches of [file] through [effects] processed so far. */
    fun windows(file: File, effects: List<AudioEffect>): List<Window> {
        if (AudioChain.active(effects).isEmpty()) return emptyList()
        val prefix = cached(file, effects).name.removeSuffix(".wav") + ".window-"
        return folder(file).listFiles().orEmpty().mapNotNull { candidate ->
            if (!candidate.name.startsWith(prefix) || !candidate.name.endsWith(".wav")) return@mapNotNull null
            val (start, end) = candidate.name.removePrefix(prefix).removeSuffix(".wav").split("-").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            Window(candidate, start / 1e6, end / 1e6)
        }
    }

    /**
     * Processes only [span] of [file] through [effects], for listening to a stretch of a long take without
     * processing all of it (YouRec/SourceMediaCache.swift:296-319, [app.takes.core.TrackAudioRegions.windowRequests]):
     * from [SETTLE] seconds before it, so a model has settled where the span begins. Null when it cannot be made.
     */
    suspend fun processWindow(file: File, effects: List<AudioEffect>, span: app.takes.core.SourceInterval, progress: (Float) -> Unit = {}): Window? = withContext(working) {
        val active = AudioChain.active(effects)
        if (active.isEmpty() || !playable(active)) return@withContext null
        windows(file, active).firstOrNull { it.covers(span.start, span.end) }?.let { return@withContext it }
        val base = cached(file, active)
        base.parentFile?.mkdirs()
        val partial = File(base.parentFile, ".${base.name}.window.tmp")
        try {
            val context = coroutineContext
            var first = -1L
            val made = render(file, active, partial, progress, { context.ensureActive() }, maxOf(0.0, span.start - SETTLE), span.end) { if (first < 0) first = it }
            if (!made || first < 0) return@withContext null
            val end = first / 1e6 + frames(partial) / Channel48k.RATE.toDouble()
            val target = File(base.parentFile, base.name.removeSuffix(".wav") + ".window-$first-${(end * 1e6).toLong()}.wav")
            if (partial.renameTo(target)) Window(target, first / 1e6, end) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            android.util.Log.w("Takes", "A chain could not be made", problem)
            null
        } catch (problem: LinkageError) {
            android.util.Log.w("Takes", "A chain could not be made", problem)
            null
        } finally {
            partial.delete()
        }
    }

    /** How long a model takes to settle at the start of a stretch it processes, in seconds. */
    const val SETTLE = 1.0

    /** The frames of a 16-bit WAV written here. */
    private fun frames(file: File): Long {
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val channels = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(22).toInt()
        return (file.length() - 44) / (2L * maxOf(1, channels))
    }

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
            if (render(file, active, partial, progress, { context.ensureActive() }) && partial.renameTo(output)) output else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            android.util.Log.w("Takes", "A chain could not be made", problem)
            null
        } catch (problem: LinkageError) {
            android.util.Log.w("Takes", "A chain could not be made", problem)
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
        private class Neural(val denoiser: NoiseReduction) : Step
        private class Filter(val processor: AudioChainProcessor) : Step
        private val steps: List<Step> = stages.map {
            if (it.kind.isNeural) Neural(NoiseReduction(it)) else Filter(AudioChainProcessor(listOf(it), channels = 1))
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

    private fun render(
        file: File, stages: List<AudioEffect>, target: File, progress: (Float) -> Unit, checkpoint: () -> Unit,
        from: Double = 0.0, until: Double = Double.POSITIVE_INFINITY, first: (Long) -> Unit = {},
    ): Boolean {
        var chains = emptyList<ChannelChain>()
        val began = System.nanoTime()
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
                from = from, until = until,
                samples = { block, time ->
                    first(time)
                    for (channel in inputs.indices) {
                        val count = inputs[channel].read(block)
                        chains[channel].write(inputs[channel].samples, count)
                    }
                    drain()
                    // Of the stretch processed: the whole recording, or a window of it.
                    val end = minOf(length / 1e6, until)
                    if (end > from) progress(((time / 1e6 - from) / (end - from)).toFloat().coerceIn(0f, 1f))
                },
            )
            if (decoded != true) return false
            for (chain in chains) chain.finish()
            drain()
            output.close()
            if (frames == 0L) return false
            writeWavHeader(target, Channel48k.RATE, chains.size, frames)
            // How fast a chain is made on this phone: seconds of sound, channels, and the time it took.
            android.util.Log.i(
                "Takes", "Chain ${stages.joinToString("+") { it.kind.rawValue + (it.model?.let { model -> ":" + model.id } ?: "") }}: " +
                    "%.1f s of sound, %d channels, in %.2f s".format(java.util.Locale.ROOT, frames / Channel48k.RATE.toDouble(), chains.size, (System.nanoTime() - began) / 1e9),
            )
            progress(1f)
            return true
        } finally {
            runCatching { output.close() }
            chains.forEach { runCatching { it.close() } }
        }
    }
}
