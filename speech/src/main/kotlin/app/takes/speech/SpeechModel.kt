package app.takes.speech

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One file of a model: where it comes from, how long it is and what it must hash to. */
data class SpeechModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/**
 * A Whisper model as sherpa-onnx reads it: an encoder, a decoder and a token table, all in
 * one folder. Only data is downloaded; the code that runs it ships with the app.
 *
 * The models listed here are the exports published by the sherpa-onnx project, at a fixed
 * revision, so the same bytes are fetched every time. Those exports do not contain the
 * attention outputs that word timestamps need: with them [Transcript.words] is null. A model
 * made with sherpa-onnx's `export-onnx-with-attention.py` has the same three file names
 * and gives word times; describe it with [whisper] and the hashes of your own files.
 */
data class SpeechModel(
    /** Names the model and its revision. It is written into the folder once every file is verified. */
    val id: String,
    val encoder: SpeechModelFile,
    val decoder: SpeechModelFile,
    val tokens: SpeechModelFile,
    /** False for the English-only models, which accept no other language. */
    val multilingual: Boolean = true,
) {
    val files: List<SpeechModelFile> get() = listOf(encoder, decoder, tokens)
    val bytes: Long get() = files.sumOf { it.bytes }

    /**
     * Whether [folder] holds this model, complete, as left by [SpeechModelFetcher.download]
     * or [adopt]. Cheap: it reads the marker and the file lengths, not the contents.
     */
    fun installed(folder: File): Boolean =
        runCatching { File(folder, MARKER).readText() }.getOrNull() == id &&
            files.all { File(folder, it.name).let { file -> file.isFile && file.length() == it.bytes } }

    /**
     * Reads every file in [folder] and compares its SHA-256. Slow (the whole model is read);
     * [cancelled] is asked between blocks. Returns the names that are missing or wrong.
     */
    fun damaged(folder: File, cancelled: () -> Boolean = { false }): List<String> = files.filter {
        val file = File(folder, it.name)
        !(file.isFile && file.length() == it.bytes && sha256(file, cancelled) == it.sha256)
    }.map { it.name }

    /**
     * Accepts files that got into [folder] some other way than [SpeechModelFetcher], such as
     * copied by the user: verifies all of them and marks the folder complete. Returns false,
     * changing nothing, when a file is missing or wrong.
     */
    fun adopt(folder: File, cancelled: () -> Boolean = { false }): Boolean {
        if (damaged(folder, cancelled).isNotEmpty()) return false
        markComplete(folder)
        return true
    }

    /** Deletes the model's files, finished or partial, and the marker. Other files stay. */
    fun remove(folder: File) {
        File(folder, MARKER).delete()
        for (file in files) {
            File(folder, file.name).delete()
            File(folder, file.name + PARTIAL).delete()
        }
    }

    internal fun markComplete(folder: File) {
        val temporary = File(folder, "$MARKER$PARTIAL")
        temporary.writeText(id)
        moveInto(temporary, File(folder, MARKER))
    }

    companion object {
        internal const val MARKER = "ready"
        internal const val PARTIAL = ".part"

        /**
         * A Whisper export in sherpa-onnx's layout: `<name>-encoder.int8.onnx`,
         * `<name>-decoder.int8.onnx` and `<name>-tokens.txt` under [base].
         */
        fun whisper(
            id: String, name: String, base: String,
            encoder: Pair<Long, String>, decoder: Pair<Long, String>, tokens: Pair<Long, String>,
            multilingual: Boolean = true,
        ): SpeechModel {
            fun file(suffix: String, size: Pair<Long, String>) =
                SpeechModelFile("$name-$suffix", "${base.trimEnd('/')}/$name-$suffix", size.first, size.second)
            return SpeechModel(id, file("encoder.int8.onnx", encoder), file("decoder.int8.onnx", decoder), file("tokens.txt", tokens), multilingual)
        }

        private const val TOKENS = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"

        /** Multilingual Whisper base, 8-bit weights: 160,609,290 bytes. The default. */
        val WhisperBase = whisper(
            id = "whisper-base-int8@bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            name = "base",
            base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            encoder = 29_120_534L to "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
            decoder = 130_672_026L to "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
            tokens = 816_730L to TOKENS,
        )

        /** Multilingual Whisper small, 8-bit weights: 375,485,327 bytes. More accurate, about three times slower. */
        val WhisperSmall = whisper(
            id = "whisper-small-int8@8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            name = "small",
            base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            encoder = 112_442_483L to "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            decoder = 262_226_114L to "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            tokens = 816_730L to TOKENS,
        )

        /** Multilingual Whisper tiny, 8-bit weights: 103,609,903 bytes. For tests; it makes many mistakes. */
        val WhisperTiny = whisper(
            id = "whisper-tiny-int8@65176e2deb88badc814a94058666cadccc29b61c",
            name = "tiny",
            base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c",
            encoder = 12_937_772L to "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            decoder = 89_855_401L to "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            tokens = 816_730L to TOKENS,
        )

        val Default = WhisperBase

        internal fun sha256(file: File, cancelled: () -> Boolean = { false }): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    if (cancelled()) throw SpeechModelCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }

        internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** Replaces [target] with [source] in one step: a reader sees the old file or the new one, never a part. */
        internal fun moveInto(source: File, target: File) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** The download or the verification was stopped on request. What was fetched so far is kept. */
class SpeechModelCancelled : IOException("Cancelled")

/** A file arrived complete and is not the expected one. It was deleted. */
class SpeechModelCorrupt(val file: String, message: String) : IOException(message)
