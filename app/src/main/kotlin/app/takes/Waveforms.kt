package app.takes

import android.util.LruCache
import app.takes.core.ProjectDisk
import app.takes.core.WaveformBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
