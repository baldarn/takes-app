@file:OptIn(UnstableApi::class)

package app.takes.sound

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.takes.Denoiser
import app.takes.MovieSound
import app.takes.core.AudioItem
import app.takes.core.ClipAudio
import app.takes.core.EditState
import app.takes.core.SpeedSettings
import app.takes.core.TimelineClip
import app.takes.core.TrackAudioRegions
import app.takes.core.TrackMix
import app.takes.core.TrackRenderPlan
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import app.takes.core.toTimeline
import app.takes.mixedSound
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sound gate of the audio engine (docs/TRACKS-PLAN.md, S5): projects that are sequences exported both ways,
 * by the export's sound before the engine ([legacySound]) and by the engine ([MovieSound]), and the samples
 * each hands to the encoder compared one by one, as 16-bit values. Then two purpose-made projects show the
 * differences the mix plan names: sound past the last picture, and sound unlinked from a clip carrying on
 * into the next.
 *
 *     adb shell am instrument -w app.takes.dev.test/app.takes.sound.SoundGateRunner
 *
 * Synthetic recordings are made first (AAC, 48 kHz, stereo, a sound that changes every sample), in the
 * test's own cache folder; no project of the app is touched.
 */
class SoundGateRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private val thread = HandlerThread("sound-gate").apply { start() }

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        try {
            val folder = File(targetContext.cacheDir, "sound-gate").apply { deleteRecursively(); mkdirs() }
            val a = synthetic(File(folder, "a.m4a"), 20.0, 1)
            val b = synthetic(File(folder, "b.m4a"), 20.0, 2)
            val c = synthetic(File(folder, "c.m4a"), 30.0, 3)
            val files = mapOf("A" to a, "B" to b, "C" to c)
            for ((name, state) in sequences()) {
                val line = runCatching { compare(name, state, files, folder) }.getOrElse { Log.getStackTraceString(it).also { failed++ } }
                Log.i(TAG, line)
                report.append(line).append('\n')
            }
            // The headroom of a sound over clips and another sound, from the same engine.
            val (_, under) = sequences()[2]
            val headroom = runBlocking { app.takes.Headroom.measure(under, files, folder, setOf("M1"), 1.0..9.0) }
            val said = "headroom of the first sound track over the clips and the second: " +
                (headroom?.let { "${"%.2f".format(it.minimum)} to ${"%.2f".format(it.maximum)} dB over ${it.samples} samples" } ?: "not measured")
            Log.i(TAG, said)
            report.append(said).append('\n')
            if (headroom == null || headroom.silent) failed++
            for ((name, state) in differences()) {
                val line = runCatching { show(name, state, files, folder) }.getOrElse { Log.getStackTraceString(it).also { failed++ } }
                Log.i(TAG, line)
                report.append(line).append('\n')
            }
        } catch (problem: Throwable) {
            failed++
            report.append(Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        thread.quitSafely()
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }

    // MARK: Projects

    private fun clip(id: String, source: String, start: Double, end: Double, audio: ClipAudio? = null, transition: TransitionSettings? = null, speed: Double? = null) =
        TimelineClip(id = id, sourceID = source, start = start, end = end, audio = audio, transition = transition, speed = speed?.let { SpeedSettings(rate = it) })

    private fun dissolve(next: String, seconds: Double) = TransitionSettings(nextClipID = next, style = TransitionStyle.Dissolve, duration = seconds)

    /** Four sequences whose sound the mix plan plays as today's export does. */
    private fun sequences(): List<Pair<String, EditState>> = listOf(
        "cuts, levels and fades" to EditState(listOf(
            clip("C1", "A", 1.0, 4.0), clip("C2", "B", 2.0, 5.0, ClipAudio(volumeDB = -6.0)),
            clip("C3", "A", 8.0, 10.0, ClipAudio(fadeIn = 0.5, fadeOut = 1.0)),
        )),
        "dissolves" to EditState(listOf(
            clip("D1", "A", 1.0, 5.0, transition = dissolve("D2", 1.0)), clip("D2", "B", 3.0, 7.0, transition = dissolve("D3", 0.5)), clip("D3", "A", 10.0, 13.0),
        )),
        "sound tracks under clips" to EditState(
            listOf(clip("S1", "A", 0.0, 6.0), clip("S2", "B", 0.0, 4.0)),
            audio = listOf(
                AudioItem("M1", "C", start = 1.0, sourceStart = 0.0, sourceEnd = 8.0, track = 0, audio = ClipAudio(volumeDB = -3.0, fadeIn = 1.0)),
                AudioItem("M2", "C", start = 2.0, sourceStart = 10.0, sourceEnd = 12.0, track = 1),
            ),
        ),
        "speed and noise reduction" to EditState(listOf(
            clip("R1", "A", 0.0, 4.0, speed = 2.0), clip("R2", "B", 0.0, 3.0, ClipAudio(denoise = 0.6)), clip("R3", "A", 5.0, 8.0),
        )),
    )

    /** Two projects where the mix plan plays something else, on purpose. */
    private fun differences(): List<Pair<String, EditState>> = listOf(
        "sound past the last picture" to EditState(
            listOf(clip("P1", "A", 0.0, 3.0)),
            audio = listOf(AudioItem("P2", "C", start = 1.0, sourceStart = 0.0, sourceEnd = 5.0, track = 0)),
        ),
        // The sound of U1 unlinked from it, and U2 carrying it on from where it leaves off.
        "unlinked sound carrying on" to EditState(
            listOf(clip("U1", "A", 2.0, 5.0, ClipAudio(muted = true)), clip("U2", "A", 5.0, 8.0)),
            audio = listOf(AudioItem("U1", "A", start = 0.0, sourceStart = 2.0, sourceEnd = 5.0, track = 0)),
        ),
    )

    // MARK: Both ways

    private class Pcm(val samples: ShortArray, val rate: Int, val channels: Int)

    private fun legacy(state: EditState, files: Map<String, File>, folder: File): Pcm {
        val plan = TrackRenderPlan.of(state)
        val clean = plan.pictures.filter { it.soundRemoved }.mapNotNull { picture ->
            runBlocking { Denoiser.process(files.getValue(picture.sourceID), plan.clips[picture.index].audio?.denoise ?: 0.0) }?.let { picture.index to it }
        }.toMap()
        return tapped(legacySound(TrackRenderPlan.of(state, clean.keys), files, files.mapValues { true }, clean), File(folder, "legacy.mp4"))
    }

    private fun engine(state: EditState, files: Map<String, File>, folder: File, duration: Double? = null): Pair<Pcm, Pcm> {
        val timeline = state.toTimeline()
        for (request in TrackAudioRegions.requests(timeline).values) runBlocking { Denoiser.process(files.getValue(request.sourceID), request.effects) }
        val sound = MovieSound(TrackMix.plan(timeline), files)
        val length = duration ?: sound.duration(state.duration)
        val wav = File(folder, "mix.wav")
        sound.write(length, wav, {}) {}
        return readWav(wav) to tapped(mixedSound(wav), File(folder, "engine.mp4"))
    }

    private fun compare(name: String, state: EditState, files: Map<String, File>, folder: File): String {
        val old = legacy(state, files, folder)
        val (mix, encoded) = engine(state, files, folder, state.duration)
        val plan = TrackMix.plan(state.toTimeline())
        val cuts = plan.pieces.flatMap { listOf(it.start.seconds, it.end.seconds) } + plan.pieces.flatMap { piece -> listOfNotNull(piece.envelope.inRamp?.start, piece.envelope.outRamp?.endInclusive) }
        val line = StringBuilder("$name: ${mix.samples.size / 2} frames by the engine, ${old.samples.size / old.channels} before it (${old.rate} Hz, ${old.channels} channels)")
        line.append("\n        the engine's file as the encoder hears it: ").append(difference(mix, encoded, emptyList()))
        line.append("\n        the engine against the reference mixer on the recordings decoded whole: ").append(reference(state, files, mix))
        line.append("\n        before the engine and with it: ").append(difference(old, mix, emptyList()))
        line.append("\n        the same, 20 ms either side of every cut left out: ").append(difference(old, mix, cuts))
        if (old.rate == mix.rate) for (piece in plan.pieces) line.append("\n        ").append(aligned(old, mix, piece.owner, piece.start.seconds, piece.end.seconds))
        return line.toString()
    }

    private fun show(name: String, state: EditState, files: Map<String, File>, folder: File): String {
        val old = legacy(state, files, folder)
        val (mix, _) = engine(state, files, folder)
        fun rms(pcm: Pcm, from: Double, until: Double): Double {
            val a = (from * pcm.rate).toInt() * pcm.channels
            val b = minOf(pcm.samples.size, (until * pcm.rate).toInt() * pcm.channels)
            if (b <= a) return 0.0
            var sum = 0.0
            for (index in a until b) sum += pcm.samples[index].toDouble() * pcm.samples[index]
            return Math.sqrt(sum / (b - a)) / 32768
        }
        val line = StringBuilder("$name: ${"%.3f".format(old.samples.size / old.channels.toDouble() / old.rate)} s before the engine, ${"%.3f".format(mix.samples.size / 2.0 / mix.rate)} s with it")
        val cut = when {
            name.startsWith("unlinked") -> 3.0
            else -> state.duration
        }
        for (window in listOf(cut - 0.02 to cut - 0.006, cut - 0.004 to cut + 0.004, cut + 0.006 to cut + 0.02, cut + 1.0 to cut + 1.5)) {
            line.append("\n        loudness ${"%.3f".format(window.first)}…${"%.3f".format(window.second)} s: before ${"%.4f".format(rms(old, window.first, window.second))}, with the engine ${"%.4f".format(rms(mix, window.first, window.second))}")
        }
        return line.toString()
    }

    /**
     * Where the sound of one piece lies in [a] against [b]: the shift, in frames, that brings the two closest over the
     * middle of the piece, and how far apart they are with it over all of it but 30 ms at each end.
     */
    private fun aligned(a: Pcm, b: Pcm, owner: String, from: Double, until: Double): String {
        val first = ((from + 0.03) * a.rate).toInt()
        val last = minOf(((until - 0.03) * a.rate).toInt(), b.samples.size / 2 - 1)
        if (last <= first) return "$owner: too short"
        fun at(pcm: Pcm, frame: Int, channel: Int): Int {
            val index = frame * pcm.channels + minOf(channel, pcm.channels - 1)
            return if (frame >= 0 && index < pcm.samples.size) pcm.samples[index].toInt() else 0
        }
        val middle = (first + last) / 2
        val probe = minOf(4800, (last - first) / 2)
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (lag in -2400..2400) {
            var sum = 0L
            var frame = middle - probe / 2
            while (frame < middle + probe / 2) { sum += abs(at(a, frame + lag, 0) - at(b, frame, 0)); frame += 3 }
            if (sum < bestSum) { bestSum = sum; best = lag }
        }
        var worst = 0
        var over = 0
        for (frame in first..last) for (channel in 0 until 2) {
            val d = abs(at(a, frame + best, channel) - at(b, frame, channel))
            worst = maxOf(worst, d)
            if (d > 1) over++
        }
        return "$owner ${"%.3f".format(from)}…${"%.3f".format(until)} s: before the engine ${best} frames ${if (best >= 0) "later" else "earlier"}; so aligned, at most $worst apart, $over samples more than 1 apart of ${(last - first + 1) * 2}"
    }

    /**
     * The engine's mix against [TrackMixer.mix] of the same plan on every recording decoded from its start, without
     * a seek, at the frames where every piece is at speed (the reference does not keep pitch): what the engine reads
     * after a seek is the recording's own sound at the plan's frame.
     */
    private fun reference(state: EditState, files: Map<String, File>, mix: Pcm): String {
        val timeline = state.toTimeline()
        val plan = TrackMix.plan(timeline).let { it.copy(duration = app.takes.core.EditTime.of(mix.samples.size / 2.0 / mix.rate)) }
        val decoded = HashMap<File, FloatArray>()
        fun whole(file: File): FloatArray = decoded.getOrPut(file) {
            var data = FloatArray(0)
            var size = 0
            var channels = 2
            var first = -1L
            app.takes.decodeAudio(file, {}, begin = { channels = it.channels }) { block, time ->
                if (first < 0) { first = Math.round(time / 1e6 * 48_000); size = (first * 2).toInt(); data = FloatArray(size) }
                val frames = block.remaining() / channels
                if (data.size < size + frames * 2) data = data.copyOf(maxOf(data.size * 2, size + frames * 2))
                for (frame in 0 until frames) for (c in 0 until 2) data[size + frame * 2 + c] = block.get(block.position() + frame * channels + minOf(c, channels - 1)) / 32768f
                size += frames * 2
            }
            data.copyOf(size)
        }
        val expected = app.takes.core.TrackMixer.mix(plan, 48_000, 2) { piece ->
            if (piece.runs.any { !it.atSpeed }) null
            else whole((if (piece.chain.isEmpty()) null else Denoiser.ready(files.getValue(piece.sourceID), piece.chain)) ?: files.getValue(piece.sourceID))
        }
        val skipped = plan.pieces.filter { piece -> piece.runs.any { !it.atSpeed } }.map { it.start.seconds - 0.001..it.end.seconds + 0.001 }
        var worst = 0
        var over = 0
        var compared = 0
        for (frame in 0 until minOf(expected.size / 2, mix.samples.size / 2)) {
            val time = frame / 48_000.0
            if (skipped.any { time in it }) continue
            compared++
            for (c in 0 until 2) {
                val want = (expected[frame * 2 + c] * 32768f).roundToInt().coerceIn(-32768, 32767)
                val d = abs(want - mix.samples[frame * 2 + c])
                worst = maxOf(worst, d)
                if (d > 1) over++
            }
        }
        return "$compared frames, at most $worst apart, $over samples more than 1 apart"
    }

    /** How far apart two mixes are, frame by frame, in 16-bit steps; frames within 20 ms of [cuts] are left out when there are any. */
    private fun difference(a: Pcm, b: Pcm, cuts: List<Double>): String {
        val frames = minOf(a.samples.size / a.channels, b.samples.size / b.channels)
        var worst = 0
        var worstAt = -1
        var over = 0
        var compared = 0
        val windows = ArrayList<Pair<Int, Int>>()
        val near = cuts.map { (it * a.rate).roundToInt() }.sorted()
        val margin = (0.02 * a.rate).toInt()
        var cursor = 0
        for (frame in 0 until frames) {
            while (cursor < near.size && near[cursor] + margin < frame) cursor++
            if (cursor < near.size && abs(near[cursor] - frame) <= margin) continue
            compared++
            for (channel in 0 until 2) {
                val x = a.samples[frame * a.channels + minOf(channel, a.channels - 1)].toInt()
                val y = b.samples[frame * b.channels + minOf(channel, b.channels - 1)].toInt()
                val d = abs(x - y)
                if (d > worst) { worst = d; worstAt = frame }
                if (d > 1) {
                    over++
                    if (windows.isEmpty() || frame - windows.last().second > a.rate / 100) windows += frame to frame else windows[windows.lastIndex] = windows.last().first to frame
                }
            }
        }
        val places = windows.take(8).joinToString { "${"%.3f".format(it.first.toDouble() / a.rate)}…${"%.3f".format(it.second.toDouble() / a.rate)}" }
        return "$compared frames, at most $worst apart (at ${"%.4f".format(worstAt.toDouble() / a.rate)} s), $over samples more than 1 apart" +
            (if (windows.isNotEmpty()) " in ${windows.size} stretches: $places" else "") +
            "; lengths ${a.samples.size / a.channels} and ${b.samples.size / b.channels}"
    }

    // MARK: Media

    /** Runs [composition] through the transformer to [output] and keeps the samples the encoder is handed. */
    private fun tapped(composition: Composition, output: File): Pcm {
        val tap = Tap()
        val tapped = Composition.Builder(composition.sequences).setEffects(Effects(listOf<AudioProcessor>(tap), emptyList())).build()
        val done = CountDownLatch(1)
        var failure: Exception? = null
        Handler(thread.looper).post {
            val transformer = Transformer.Builder(targetContext).setLooper(thread.looper).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    failure = exportException; done.countDown()
                }
            }).build()
            output.delete()
            transformer.start(tapped, output.path)
        }
        done.await()
        failure?.let { throw it }
        return Pcm(tap.samples(), tap.rate, tap.channels)
    }

    /** Keeps every sample that goes through it as 16-bit values. */
    private class Tap : BaseAudioProcessor() {
        private var kept = ShortArray(1 shl 20)
        private var size = 0
        var rate = 0; private set
        var channels = 0; private set
        private var float = false

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            rate = inputAudioFormat.sampleRate
            channels = inputAudioFormat.channelCount
            float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            if (!float && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val bytes = inputBuffer.remaining()
            val order = inputBuffer.order(ByteOrder.nativeOrder())
            val count = bytes / (if (float) 4 else 2)
            if (kept.size < size + count) kept = kept.copyOf(maxOf(kept.size * 2, size + count))
            if (float) { val f = order.asFloatBuffer(); for (i in 0 until count) kept[size + i] = (f.get(i) * 32768f).roundToInt().coerceIn(-32768, 32767).toShort() }
            else order.asShortBuffer().get(kept, size, count)
            size += count
            val out = replaceOutputBuffer(bytes)
            out.put(inputBuffer)
            out.flip()
        }

        fun samples(): ShortArray = kept.copyOf(size)
    }

    private fun readWav(file: File): Pcm {
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = header.getShort(22).toInt()
        val rate = header.getInt(24)
        val samples = ShortArray((bytes.size - 44) / 2)
        ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return Pcm(samples, rate, channels)
    }

    /** A recording of [seconds] of sound that changes every sample, AAC at 48 kHz in two channels, as a phone records. */
    private fun synthetic(file: File, seconds: Double, seed: Int): File {
        val rate = 48_000
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val total = (seconds * rate).toLong()
        var written = 0L
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        var random = seed * 7919L
        while (true) {
            if (!inputDone) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    val frames = minOf((buffer.capacity() / 4).toLong(), 1024L, total - written).toInt()
                    if (frames <= 0) {
                        encoder.queueInputBuffer(index, 0, 0, written * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (frame in 0 until frames) {
                            val t = (written + frame).toDouble() / rate
                            random = random * 6364136223846793005L + 1442695040888963407L
                            val noise = ((random ushr 40).toInt() and 0xffff) / 65536.0 - 0.5
                            val left = 0.25 * sin(2 * Math.PI * (220.0 * seed + 30 * sin(t)) * t) + 0.05 * noise
                            val right = 0.25 * sin(2 * Math.PI * (330.0 * seed) * t + seed) + 0.05 * noise
                            buffer.putShort((left * 32767).toInt().toShort()); buffer.putShort((right * 32767).toInt().toShort())
                        }
                        encoder.queueInputBuffer(index, 0, frames * 4, written * 1_000_000 / rate, 0)
                        written += frames
                    }
                }
            }
            val out = encoder.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(encoder.outputFormat); muxer.start() }
            else if (out >= 0) {
                val data = encoder.getOutputBuffer(out)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, data, info)
                encoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        encoder.stop(); encoder.release()
        muxer.stop(); muxer.release()
        return file
    }

    private companion object {
        const val TAG = "SoundGate"
    }
}
