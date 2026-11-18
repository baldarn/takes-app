package app.takes

import app.takes.core.AudioChain
import app.takes.core.AudioChainProcessor
import app.takes.core.AudioEffect
import app.takes.core.AudioEffectKind
import app.takes.core.AudioModel
import app.takes.core.ProcessingCache
import app.takes.core.RestorationTwin
import app.takes.core.RestorationWindowStore
import app.takes.core.SpeechDetailChannel
import app.takes.core.StudioRestorationChannel
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
 * The stages this device plays are the filters ([AudioChainProcessor]),
 * noise reduction ([AudioEffectKind.VoiceIsolation]) by RNNoise or by
 * DeepFilterNet3, whose network the app carries ([DeepFilterModel.bundled]),
 * and the speech restoration stages, LavaSR's speech detail and VoiceFixer's
 * studio restoration, once their model is downloaded ([RestorationModelStore]);
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
        it.supported && when (it.kind) {
            AudioEffectKind.VoiceIsolation -> it.model == AudioModel.RNNoise || it.model == AudioModel.DeepFilter
            AudioEffectKind.StudioVoice, AudioEffectKind.StudioRestoration -> RestorationModelStore.plays(it)
            else -> !it.kind.isNeural
        }
    }

    private fun restores(effect: AudioEffect) = effect.kind == AudioEffectKind.StudioVoice || effect.kind == AudioEffectKind.StudioRestoration

    /**
     * Where the windows the speech restoration stage at [index] of [active] made of [file] are kept while the
     * whole recording is processed, so that a run stopped half way resumes ([SavedWindows]), as the original
     * keeps them (YouRec/OfflineAudioPipeline.swift:73-76). Named by the chain up to that stage; VoiceFixer's
     * windows are kept before the mix, so its amount is left out, as the original leaves it out of their
     * identity (YouRec/StudioRestorationProcessor.swift:250).
     */
    private fun windowsFolder(file: File, active: List<AudioEffect>, index: Int): File {
        val prefix = active.take(index + 1).mapIndexed { at, effect ->
            if (at == index && effect.kind == AudioEffectKind.StudioRestoration) effect.copy(amount = 1.0) else effect
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(TrackAudioRegions.cacheKey("", prefix).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(folder(file), "${file.name}.chain-$digest$WINDOWS")
    }

    private const val WINDOWS = ".windows"

    /**
     * One channel's windows of a restoration stage, each a file of floats named by the channel and the window's
     * position, written beside and renamed into place, so a window found is whole.
     */
    private class SavedWindows(private val folder: File, private val channel: Int) : RestorationWindowStore {
        override fun load(position: Long, count: Int): FloatArray? {
            val found = File(folder, "$channel-$position.f32")
            if (found.length() != count * 4L) return null
            return runCatching {
                FloatArray(count).also { ByteBuffer.wrap(found.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }.getOrNull()
        }

        override fun save(position: Long, samples: FloatArray, count: Int) {
            // A window that cannot be kept is made again next time: nothing else depends on it.
            runCatching {
                folder.mkdirs()
                val partial = File(folder, ".$channel-$position.tmp")
                val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(samples, 0, count)
                partial.writeBytes(bytes.array())
                if (!partial.renameTo(File(folder, "$channel-$position.f32"))) partial.delete()
            }
        }
    }

    /**
     * DeepFilterNet3's network, loaded on first use (about 9 MB, read from the app itself) and kept: every
     * channel of every chain shares it, each with its own memory. A failure is kept too, so it is not tried again.
     */
    private val deepFilter: Result<DeepFilterModel> by lazy {
        runCatching { DeepFilterModel.bundled() ?: error("This build carries no DeepFilterNet3 model") }
    }

    /**
     * One channel through one neural stage, with the calls of RNNoise's [ChannelDenoiser]: noise reduction by
     * RNNoise or DeepFilterNet3, speech detail by LavaSR, studio restoration by VoiceFixer. A restoration
     * stage shares [twin] with the recording's other channels and keeps its windows in [store].
     */
    private class NeuralStage(effect: AudioEffect, twin: RestorationTwin, store: RestorationWindowStore?) : AutoCloseable {
        private val amount = effect.amount.toFloat()
        private val rnnoise = if (effect.kind == AudioEffectKind.VoiceIsolation && effect.model != AudioModel.DeepFilter) ChannelDenoiser(amount) else null
        private val dfn = if (effect.kind == AudioEffectKind.VoiceIsolation && effect.model == AudioModel.DeepFilter) ChannelDeepFilter(deepFilter.getOrThrow(), amount) else null
        private val detail = if (effect.kind == AudioEffectKind.StudioVoice) SpeechDetailChannel(RestorationModelStore.lava(), amount, twin, store) else null
        private val studio = if (effect.kind == AudioEffectKind.StudioRestoration) {
            RestorationModelStore.voiceFixer().let { StudioRestorationChannel(it, it.bank, amount, twin, store) }
        } else null

        fun write(samples: FloatArray, offset: Int, count: Int, output: (FloatArray, Int) -> Unit) {
            when {
                dfn != null -> dfn.write(samples, offset, count, output)
                detail != null -> detail.write(samples, offset, count, output)
                studio != null -> studio.write(samples, offset, count, output)
                else -> rnnoise!!.write(samples, offset, count, output)
            }
        }

        fun finish(output: (FloatArray, Int) -> Unit) {
            when {
                dfn != null -> dfn.finish(output)
                detail != null -> detail.finish(output)
                studio != null -> studio.finish(output)
                else -> rnnoise!!.finish(output)
            }
        }

        override fun close() { dfn?.close(); rnnoise?.close() }
    }

    /**
     * The processed sound of [file] through [effects], if it has already been made; null too for a chain that changes
     * nothing. A file found is marked used now, as the original marks what it hands out (SourceMediaCache.swift:63-66),
     * so that [makeRoom] keeps it.
     */
    fun ready(file: File, effects: List<AudioEffect>): File? =
        if (AudioChain.active(effects).isEmpty()) null else cached(file, effects).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    // What was made or used since the app started is in use, and is never trimmed until it starts again.
    private val sessionStart = System.currentTimeMillis()

    /**
     * Trims the processed sound of every project to the original's quota before [required] bytes are written for
     * the recording [file], the cheapest to make again and the oldest first, as the original's cache does
     * (SourceMediaCache.swift:68-122, [ProcessingCache.trim]). Whether there is room for them then.
     */
    private fun makeRoom(file: File, required: Long): Boolean {
        val root = file.parentFile?.parentFile ?: return true
        val cached = root.listFiles().orEmpty().flatMap { project -> File(project, "Cache").listFiles().orEmpty().toList() }
        val entries = cached.filter { it.isFile && ProcessingCache.isProcessed(it.name) }
            .map { found ->
                val priority = runCatching { File(found.path + ".priority").readText().trim().toInt() }.getOrDefault(0)
                ProcessingCache.Entry(found.path, found.length(), found.lastModified(), priority, pinned = found.lastModified() >= sessionStart)
            } +
            // The windows of a restoration stopped half way, worth nothing until it is resumed.
            cached.filter { it.isDirectory && it.name.endsWith(WINDOWS) }.map { found ->
                val files = found.listFiles().orEmpty()
                val modified = files.maxOfOrNull { it.lastModified() } ?: found.lastModified()
                ProcessingCache.Entry(found.path, files.sumOf { it.length() }, modified, 0, pinned = modified >= sessionStart)
            }
        val trim = ProcessingCache.trim(entries, root.usableSpace, required)
        for (entry in trim.remove) {
            val found = File(entry.path)
            if (found.isDirectory) found.deleteRecursively() else if (found.delete()) ProcessingCache.COMPANIONS.forEach { File(entry.path + it).delete() }
        }
        if (!trim.enough) android.util.Log.w("Takes", "Not enough free space to process ${file.name}")
        return trim.enough
    }

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
        if (!makeRoom(file, ProcessingCache.required(span.end - span.start + SETTLE, active.size))) return@withContext null
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
        if (!makeRoom(file, ProcessingCache.required(mediaDuration(file) ?: 0.0, active.size))) return@withContext null
        try {
            val context = coroutineContext
            if (render(file, active, partial, progress, { context.ensureActive() }) && partial.renameTo(output)) {
                // What it is worth keeping, beside it, as the original's (SourceMediaCache.swift:446-450).
                runCatching { File(output.path + ".priority").writeText(ProcessingCache.priority(active.map { it.kind.isNeural }).toString()) }
                // The whole recording is made: its restoration windows are no longer needed (YouRec/OfflineAudioPipeline.swift:95-96).
                active.indices.filter { restores(active[it]) }.forEach { windowsFolder(file, active, it).deleteRecursively() }
                output
            } else null
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
     * One channel through the stages in order, as it arrives: each neural stage gives back exactly as many
     * samples as it is given, some time later (a frame for noise reduction, seconds for restoration), and the
     * filters follow it sample by sample. [twins] and [stores] are the restoration stages', stage by stage.
     */
    private class ChannelChain(stages: List<AudioEffect>, private val sink: Pending, twins: List<RestorationTwin>, stores: (Int) -> RestorationWindowStore?) : AutoCloseable {
        private sealed interface Step
        private class Neural(val denoiser: NeuralStage) : Step
        private class Filter(val processor: AudioChainProcessor) : Step
        private val steps: List<Step> = stages.mapIndexed { index, effect ->
            if (effect.kind.isNeural) Neural(NeuralStage(effect, twins[index], stores(index))) else Filter(AudioChainProcessor(listOf(effect), channels = 1))
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
        val restoring = stages.any(::restores)
        if (restoring) RestorationModelStore.use()
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
                    // Each channel has its own state, as in the original; equal channels share a restoration's windows.
                    val twins = stages.map { RestorationTwin() }
                    // Windows are kept to resume the whole recording, not a stretch of it.
                    val whole = from == 0.0 && until == Double.POSITIVE_INFINITY
                    chains = List(format.channels) { channel ->
                        ChannelChain(stages, pending[channel], twins) { index ->
                            if (whole && restores(stages[index])) SavedWindows(windowsFolder(file, stages, index), channel) else null
                        }
                    }
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
            if (restoring) RestorationModelStore.done()
        }
    }
}
