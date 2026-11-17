package app.takes

import android.util.LruCache
import app.takes.core.ProjectDisk
import app.takes.core.WaveformBuilder
import app.takes.core.WaveformTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Audio peaks of a recording, decoded once and kept in the project's `Cache`
 * folder. Empty for a recording without sound; null when it cannot be decoded.
 */
object Waveforms {
    private val memory = LruCache<String, ByteArray>(64)
    private val decoding = Dispatchers.IO.limitedParallelism(1)

    suspend fun peaks(file: File): ByteArray? {
        memory.get(file.path)?.let { return it }
        return withContext(decoding) {
            val cache = File(File(file.parentFile, "Cache"), file.name + ".peaks")
            val peaks = if (cache.isFile) cache.readBytes() else runCatching { decode(file) }.getOrNull()?.also {
                cache.parentFile?.mkdirs()
                runCatching { ProjectDisk.writeAtomically(it, cache) }
            }
            peaks?.also { memory.put(file.path, it) }
        }
    }

    private fun decode(file: File): ByteArray? {
        var builder: WaveformBuilder? = null
        return when (decodeAudio(file, begin = { builder = WaveformBuilder(it.sampleRate, it.channels) }, samples = { block, _ -> builder?.add(block) })) {
            true -> builder?.finish()
            false -> ByteArray(0)
            null -> null
        }
    }
}

/**
 * The detailed waveform's four-second tiles, as the original's
 * `WaveformDetailCache` (YouRec/WaveformDetail.swift:19-66): only the tiles
 * the timeline shows are decoded, from the file's own sound and not from a
 * copy of the whole of it, and the last 64 are kept, about two megabytes.
 * A tile is known by the file's path, size and time of change, so a file
 * written again is decoded again. Null when the sound cannot be decoded; a
 * file without sound gives a silent tile.
 */
object WaveformDetails {
    private val tiles = LruCache<String, WaveformTile>(64)
    private val decoding = Dispatchers.IO.limitedParallelism(1)

    suspend fun tile(file: File, index: Int): WaveformTile? {
        val key = "${file.path}|${file.length()}|${file.lastModified()}|$index"
        tiles.get(key)?.let { return it }
        return withContext(decoding) {
            tiles.get(key) ?: decode(file, index)?.also { tiles.put(key, it) }
        }
    }

    private suspend fun decode(file: File, index: Int): WaveformTile? {
        val context = coroutineContext
        val start = index * WaveformTile.SECONDS
        val builder = WaveformTile.Builder(index)
        var channels = 1
        var rate = 48000.0
        var block = FloatArray(0)
        val decoded = runCatching {
            decodeAudio(
                file, checkpoint = { context.ensureActive() }, from = start, until = start + WaveformTile.SECONDS,
                begin = { channels = it.channels.coerceAtLeast(1); rate = it.sampleRate.toDouble() },
            ) { samples, time ->
                val count = samples.remaining()
                if (block.size < count) block = FloatArray(count)
                val first = samples.position()
                for (i in 0 until count) block[i] = samples.get(first + i) / 32768f
                builder.add(if (block.size == count) block else block.copyOf(count), channels, rate, time / 1e6)
            }
        }
        context.ensureActive()
        return when (decoded.getOrNull()) {
            null -> null
            else -> builder.tile()
        }
    }
}
