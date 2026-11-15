package app.takes.speech

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One file of a model: where it comes from, how long it is and what it must hash to. */
data class SpeechModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/**
 * A speech model as sherpa-onnx reads it: a few files in one folder, and which of its
 * recognizers runs them. Only data is downloaded; the code that runs it ships with the app.
 *
 * Everything the rest of the program needs to know about a model is in this description:
 * nothing else names a model or asks which one it has.
 *
 * The models listed here are published by the sherpa-onnx project, at a fixed revision, so
 * the same bytes are fetched every time. None of them gives word times: with them
 * [Transcript.words] is null. A Whisper export made with sherpa-onnx's
 * `export-onnx-with-attention.py` has the same three file names and gives word times;
 * describe it with [whisper] and the hashes of your own files.
 */
data class SpeechModel(
    /** Names the model and its revision. It is written into the folder once every file is verified. */
    val id: String,
    /** What people call it: a proper name, the same in every language. */
    val name: String,
    /** The name of the folder an app keeps it in, beside the other models. */
    val folder: String,
    val kind: Kind,
    /** A [SpeechModelFile.name] may contain `/`: the file then lives in a folder inside the model's. */
    val files: List<SpeechModelFile>,
    /** False for the English-only models, which accept no other language. */
    val multilingual: Boolean = true,
) {
    /** Which recognizer of sherpa-onnx reads the files, and so which files there must be. */
    enum class Kind(internal val parts: List<String>) {
        /** `<name>-encoder.int8.onnx`, `<name>-decoder.int8.onnx`, `<name>-tokens.txt`. */
        Whisper(listOf(ENCODER, DECODER, TOKENS)),

        /** `conv_frontend.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx` and the tokenizer's three files in `tokenizer/`. */
        Qwen3Asr(listOf(FRONTEND, ENCODER, DECODER, "$TOKENIZER/vocab.json", "$TOKENIZER/merges.txt", "$TOKENIZER/tokenizer_config.json")),
    }

    init {
        for (part in kind.parts) require(files.count { it.name.endsWith(part) } == 1) { "$id: a ${kind.name} model has exactly one $part" }
    }

    val bytes: Long get() = files.sumOf { it.bytes }

    /** The file whose name ends with [part], one of the names its [kind] requires. */
    internal fun file(part: String): SpeechModelFile = files.single { it.name.endsWith(part) }

    /**
     * Whether [folder] holds this model, complete, as left by [SpeechModelFetcher.download]
     * or [adopt]. Cheap: it reads the marker and the file lengths, not the contents.
     */
    fun installed(folder: File): Boolean =
        runCatching { File(folder, MARKER).readText() }.getOrNull() == id &&
            files.all { File(folder, it.name).let { file -> file.isFile && file.length() == it.bytes } }

    /** The bytes of this model in [folder], finished files and the part of an interrupted one alike. */
    fun present(folder: File): Long =
        files.sumOf { minOf(it.bytes, maxOf(File(folder, it.name).length(), File(folder, it.name + PARTIAL).length())) }

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

    /** Deletes the model's files, finished or partial, the marker and the folders left empty. Other files stay. */
    fun remove(folder: File) {
        File(folder, MARKER).delete()
        File(folder, "$MARKER$PARTIAL").delete()
        for (file in files) {
            File(folder, file.name).delete()
            File(folder, file.name + PARTIAL).delete()
        }
        // Deepest first; a folder that still holds something refuses to go, and stays.
        files.mapNotNull { File(folder, it.name).parentFile }.distinct().sortedByDescending { it.path.length }
            .forEach { if (it != folder) it.delete() }
    }

    internal fun markComplete(folder: File) {
        val temporary = File(folder, "$MARKER$PARTIAL")
        temporary.writeText(id)
        moveInto(temporary, File(folder, MARKER))
    }

    companion object {
        internal const val MARKER = "ready"
        internal const val PARTIAL = ".part"

        internal const val ENCODER = "encoder.int8.onnx"
        internal const val DECODER = "decoder.int8.onnx"
        internal const val TOKENS = "tokens.txt"
        internal const val FRONTEND = "conv_frontend.onnx"
        internal const val TOKENIZER = "tokenizer"

        /**
         * A Whisper export in sherpa-onnx's layout: `<file>-encoder.int8.onnx`,
         * `<file>-decoder.int8.onnx` and `<file>-tokens.txt` under [base].
         */
        fun whisper(
            id: String, name: String, file: String, base: String,
            encoder: Pair<Long, String>, decoder: Pair<Long, String>, tokens: Pair<Long, String>,
            multilingual: Boolean = true,
        ): SpeechModel {
            fun part(suffix: String, size: Pair<Long, String>) =
                SpeechModelFile("$file-$suffix", "${base.trimEnd('/')}/$file-$suffix", size.first, size.second)
            return SpeechModel(id, name, file, Kind.Whisper, listOf(part(ENCODER, encoder), part(DECODER, decoder), part(TOKENS, tokens)), multilingual)
        }

        private const val WHISPER_TOKENS = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"

        /**
         * Qwen3-ASR 0.6B, 8-bit weights: 987,015,347 bytes. The model of the iPhone app, in
         * the export the sherpa-onnx project publishes; the three tokenizer files are byte
         * for byte the ones the iPhone app downloads.
         */
        val Qwen3Asr: SpeechModel = run {
            val base = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"
            fun part(name: String, bytes: Long, sha256: String) = SpeechModelFile(name, "$base/$name", bytes, sha256)
            SpeechModel(
                id = "qwen3-asr-0.6b-int8@68818b2313fe77bd06f6a7c5068ff3ef59d02b8a",
                name = "Qwen3-ASR",
                folder = "qwen3-asr-0.6b",
                kind = Kind.Qwen3Asr,
                // Small files first: a wrong address or a full disk shows before 756 MB are fetched.
                files = listOf(
                    part("$TOKENIZER/tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
                    part("$TOKENIZER/merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
                    part("$TOKENIZER/vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                    part(FRONTEND, 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                    part(ENCODER, 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                    part(DECODER, 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
                ),
            )
        }

        /** Multilingual Whisper small, 8-bit weights: 375,485,327 bytes. The lighter choice. */
        val WhisperSmall = whisper(
            id = "whisper-small-int8@8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            name = "Whisper small", file = "small",
            base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            encoder = 112_442_483L to "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            decoder = 262_226_114L to "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            tokens = 816_730L to WHISPER_TOKENS,
        )

        /** Multilingual Whisper base, 8-bit weights: 160,609,290 bytes. What the app offered before; no longer offered. */
        val WhisperBase = whisper(
            id = "whisper-base-int8@bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            name = "Whisper base", file = "base",
            base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            encoder = 29_120_534L to "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
            decoder = 130_672_026L to "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
            tokens = 816_730L to WHISPER_TOKENS,
        )

        /** Multilingual Whisper tiny, 8-bit weights: 103,609,903 bytes. For tests; it makes many mistakes. */
        val WhisperTiny = whisper(
            id = "whisper-tiny-int8@65176e2deb88badc814a94058666cadccc29b61c",
            name = "Whisper tiny", file = "tiny",
            base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c",
            encoder = 12_937_772L to "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            decoder = 89_855_401L to "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            tokens = 816_730L to WHISPER_TOKENS,
        )

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
