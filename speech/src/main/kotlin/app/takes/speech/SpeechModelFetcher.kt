package app.takes.speech

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * How bytes are obtained. This module never opens a connection itself: the app decides
 * whether there is a network at all, and what `url` means (HTTPS, a file the user picked,
 * a server on the local network).
 */
fun interface SpeechModelSource {
    /**
     * Opens [url] for reading from byte [offset]. A source that cannot start in the middle
     * returns the whole file and says so with [Opened.offset] = 0. Failures are IOExceptions.
     */
    fun open(url: String, offset: Long): Opened

    /** [stream] starts at byte [offset] of the file. The fetcher closes it. */
    class Opened(val stream: InputStream, val offset: Long)
}

/**
 * Puts a [SpeechModel] into a folder, file by file.
 *
 * A file has its final name only once its length and SHA-256 are right; until then it is
 * `<name>.part`, and an interrupted download continues from there the next time. The folder
 * counts as installed ([SpeechModel.installed]) only after every file is in place.
 *
 * Blocking: call it from a background thread. Not for two threads on the same folder.
 */
object SpeechModelFetcher {
    /**
     * Downloads what is missing of [model] into [folder]. Files already complete and correct
     * are kept. [progress] receives the bytes present so far out of [SpeechModel.bytes], from
     * the calling thread. [cancelled] is asked every 64 KB; when it answers true the call
     * ends with [SpeechModelCancelled] and the partial file stays for the next attempt.
     *
     * Throws [SpeechModelCorrupt] when a complete file has the wrong content (it is deleted,
     * so the next attempt starts that file again) and IOException when the source fails.
     */
    fun download(
        model: SpeechModel,
        folder: File,
        source: SpeechModelSource,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        if (model.installed(folder)) { progress(model.bytes, model.bytes); return }
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        // The marker goes first: from here on the folder is not a complete model.
        File(folder, SpeechModel.MARKER).delete()
        val need = model.bytes - model.present(folder)
        if (folder.usableSpace in 1 until need) throw IOException("Not enough free space: $need bytes needed")

        var before = 0L
        for (file in model.files) {
            val base = before
            fetch(file, folder, source, { progress(base + it, model.bytes) }, cancelled)
            before += file.bytes
            progress(before, model.bytes)
        }
        model.markComplete(folder)
    }

    private fun fetch(file: SpeechModelFile, folder: File, source: SpeechModelSource, progress: (Long) -> Unit, cancelled: () -> Boolean) {
        val target = File(folder, file.name)
        val partial = File(folder, file.name + SpeechModel.PARTIAL)
        if (target.isFile) {
            if (target.length() == file.bytes && SpeechModel.sha256(target, cancelled) == file.sha256) { partial.delete(); return }
            target.delete()
        }
        // A file of a model can live in a folder inside the model's.
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create $it") }
        // A part longer than the file cannot be the beginning of it.
        if (partial.length() > file.bytes) partial.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var have = partial.length()
        if (have < file.bytes) {
            val opened = source.open(file.url, have)
            opened.stream.use { input ->
                if (opened.offset != have) {
                    if (opened.offset != 0L) throw IOException("${file.url}: asked for byte $have, got byte ${opened.offset}")
                    have = 0
                }
                RandomAccessFile(partial, "rw").use { output ->
                    output.setLength(have)
                    // The hash covers the whole file, so what was fetched earlier is read again.
                    var hashed = 0L
                    while (hashed < have) {
                        if (cancelled()) throw SpeechModelCancelled()
                        val count = output.read(buffer, 0, minOf(buffer.size.toLong(), have - hashed).toInt())
                        if (count < 0) throw IOException("Cannot read $partial")
                        digest.update(buffer, 0, count)
                        hashed += count
                    }
                    while (true) {
                        if (cancelled()) throw SpeechModelCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        have += count
                        // More than promised: not this file. Stop before filling the disk.
                        if (have > file.bytes) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress(have)
                    }
                    output.fd.sync()
                }
            }
            // Ended early: the connection dropped. The part stays and the next call resumes.
            if (have < file.bytes) throw IOException("${file.url}: ended after $have of ${file.bytes} bytes")
        } else {
            // The whole file arrived earlier and was not checked yet.
            digest.update(ByteArray(0))
            partial.inputStream().use { input ->
                while (true) {
                    if (cancelled()) throw SpeechModelCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        val found = if (have == file.bytes) SpeechModel.hex(digest.digest()) else "a longer file"
        if (found != file.sha256) {
            partial.delete()
            throw SpeechModelCorrupt(file.name, "${file.url}: expected SHA-256 ${file.sha256}, got $found")
        }
        SpeechModel.moveInto(partial, target)
    }
}
