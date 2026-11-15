# speech

Speech to text on the device, for subtitles:
[Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR), the model of the iPhone app, and OpenAI's
[Whisper](https://github.com/openai/whisper) as the lighter one, both
run by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), behind a small Kotlin API.
The model is not in the app; it is fetched separately and verified. The module has no
Android permission and never opens a network connection by itself.

## What is inside

- `SpeechRecognizer.kt`: samples in, text out, and words with times when the model gives them.
- `SpeechModel.kt`: a model as data: its name, which recognizer runs it, which files it
  consists of, where they come from, their lengths and SHA-256, the memory it takes;
  whether a folder holds a complete model.
- `SpeechModels.kt`: which models are offered, which one a phone gets by its memory, and
  which one is in use given what is on the phone and what the person picked.
- `SpeechModelFetcher.kt`: puts a model into a folder through a `SpeechModelSource` the
  app supplies, resuming partial files. Plain JVM code, nothing from Android.
- sherpa-onnx 2.0.1 itself: its Kotlin classes and `libsherpa-onnx-jni.so` for arm64-v8a,
  armeabi-v7a and x86_64. Not stored in the repository, see "How sherpa-onnx gets in".

## Using it

```kotlin
val model = SpeechModels.selected(chosen = null, installed = emptySet(), totalRam)   // Qwen3-ASR or Whisper small
val folder = File(context.noBackupFilesDir, "speech/${model.folder}")
if (!model.installed(folder)) SpeechModelFetcher.download(model, folder, source, progress, cancelled)

SpeechRecognizer(folder, model, language = "it").use { recognizer ->   // null detects the language
    val transcript = recognizer.transcribe(samples)             // 16 kHz, one channel, floats in −1…1
    transcript.text       // "Gli uomini sono turbati non dalle cose ma dal modo in cui le vedono."
    transcript.language   // "it"
    transcript.words      // null with the published models, see "Word times"
}
```

The API as written:

```kotlin
class SpeechRecognizer(modelFolder: File, model: SpeechModel, language: String? = null,
                       threads: Int = min(4, processors)) : AutoCloseable {
    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000, language: String? = this.language): Transcript
    override fun close()
}
data class Transcript(val text: String, val words: List<Word>?, val language: String)
data class Word(val text: String, val start: Double, val end: Double)   // seconds from the first sample

data class SpeechModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)
data class SpeechModel(val id: String, val name: String, val folder: String, val kind: Kind,
                       val files: List<SpeechModelFile>, val memory: Long, val multilingual: Boolean = true) {
    enum class Kind { Whisper, Qwen3Asr }                // which recognizer, and so which files
    val bytes: Long
    fun installed(folder: File): Boolean                 // marker and lengths: cheap
    fun present(folder: File): Long                      // bytes there, a partial file included
    fun damaged(folder: File, cancelled): List<String>   // reads and hashes everything
    fun adopt(folder: File, cancelled): Boolean          // verify files put there by hand, then mark
    fun remove(folder: File)
    companion object { val Qwen3Asr; val WhisperSmall; val WhisperBase; val WhisperTiny
                       fun whisper(id, name, file, base, encoder, decoder, tokens, memory, multilingual) }
}
object SpeechModels {
    val offered: List<SpeechModel>                       // Qwen3-ASR, Whisper small
    val known: List<SpeechModel>                         // those and Whisper base, from earlier versions
    const val QWEN_MINIMUM_RAM_BYTES = 7_000_000_000L
    fun recommended(totalRam: Long): SpeechModel
    fun selected(chosen: String?, installed: Set<String>, totalRam: Long): SpeechModel
    fun choices(installed: Set<String>, selected: SpeechModel): List<SpeechModel>
    fun lighter(model: SpeechModel): SpeechModel?
    fun fits(model: SpeechModel, free: Long, low: Boolean = false): Boolean
}
fun interface SpeechModelSource { fun open(url: String, offset: Long): Opened }
object SpeechModelFetcher { fun download(model, folder, source, progress = …, cancelled = …) }
```

- **Threads.** Everything blocks; call from a background thread. One `transcribe` runs at a
  time on a recognizer. A call cannot be interrupted, so cancel between pieces.
- **Lifetime.** Creating a recognizer loads the model (0.2–0.5 s on a desktop). Create it
  for a transcription job and `close()` it after: its memory is native and the garbage
  collector does not see it.
- **Language.** Two-letter codes. The iPhone app's list is Auto, it, en, fr, de, es, pt, zh,
  ja, ko, ru, ar. Whisper takes the code; Qwen3-ASR is told the language by its English
  name after the sound ("language Italian"), as the iPhone app does, and refuses a code
  outside the 30 languages it was trained on. `null` detects, separately for every call
  (and every window of a long call), so two pieces of one job can come out in two
  languages; the iPhone app accepts that, and so does the app. Whisper says what it
  detected, Qwen3-ASR does not. A forced wrong language gives fluent nonsense or a
  translation, not an error.
- **Length.** Qwen3-ASR as exported holds 512 tokens of sound and text together, 13 for
  each second of sound, and this module lets it write up to 256 (the iPhone app's limit);
  sherpa-onnx cuts off the sound that does not fit. Whisper reads 30 s at once and sherpa-onnx drops the rest without an error
  (tested: 80 of 132 words came back). `transcribe` therefore cuts longer audio into
  windows of at most 24 s at the quietest moment of each window's last third. The app's own
  plan of 12 s pieces at pauses is better: shorter pieces are faster and use less memory.
- **Silence.** A piece with no signal at all is skipped. Quiet hiss is not, and Whisper can
  invent a word or two for it (`1/2"` in a test), so only hand over stretches with speech.

## Word times

`Transcript.words` is null when the model gave no times, and then only the text is known.

**It is null for every model listed in `SpeechModel`.** Qwen3-ASR gives no times at all
(the iPhone app does not have them either and estimates, as the app does). sherpa-onnx 2.0.1 can compute a
time for every Whisper token from the model's attention, but only for a model exported
with `scripts/whisper/export-onnx-with-attention.py`, and the exports the sherpa-onnx
project publishes were made before that existed. With them the app has to place the words
itself, as the iPhone app does (`SubtitleTiming.estimate` in `:core`).

With an export that has the attention outputs (same three file names, about 0.1 MB larger)
the same code returns words with times. Tried with a third-party export of base, which
gave the same text as the official one on every recording:

```
gli[0.00–0.14] uomini[0.14–0.46] sono[0.46–0.70] turbati[0.70–1.30] non[1.30–1.50] dalle[1.50–1.78]
cose[1.78–2.46] ma[2.60–2.74] dal[2.74–2.90] modo[2.90–3.12] in[3.12–3.26] cui[3.26–3.38] le[3.38–3.50] vedono[3.50–4.12]
```

What is real and what is not: the start of a word is measured (where the model read it
from the sound). There is no measured end; the model's "durations" are the distance to
the next start. This module therefore trims each word to where there is sound, and moves
a word that swallowed the pause before it to after that pause (a rule of thumb, described
in `SpeechRecognizer.tightened`). Against the recordings: first and last word within
0.15 s and 0.25 s of where the sound starts and ends; a sentence placed after 2 s of
silence starts where it should; the same words recognized alone and inside a longer piece
differ by a few hundredths of a second for most words and by up to 0.23 s for one. No
recording with hand-checked word times was available, so there is no error figure.

To get such a model somebody has to run the export (Python with PyTorch, openai-whisper,
onnx and onnxruntime; it downloads OpenAI's weights and writes the same file names),
host the three int8 files and describe them with `SpeechModel.whisper(...)`. That was not
done here. The third-party export used for the trial,
`huggingface.co/clairemcw/sherpa-onnx-whisper-base-attention`, has no description, license
or history and should not be shipped on its word.

## Models

Files come from the sherpa-onnx author's Hugging Face repositories at a fixed revision,
one file at a time: `https://huggingface.co/<repository>/resolve/<revision>/<file>`.
(The GitHub release `asr-models` has the same models as `.tar.bz2` archives, 879 MB for
Qwen3-ASR, which a phone would have to unpack.) The SHA-256 of every file is in
`SpeechModel.kt`.

| | Qwen3-ASR 0.6B | Whisper small |
|---|---|---|
| Offered | yes; what a phone with 7 GB of memory or more gets by itself | yes; what the other phones get |
| Repository | `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` | `csukuangfj/sherpa-onnx-whisper-small` |
| Revision | `68818b2313fe77bd06f6a7c5068ff3ef59d02b8a` | `8f3c18b358db4d1f2fc1eae49d75cd20989e4309` |
| Files | `conv_frontend.onnx` 44,148,281; `encoder.int8.onnx` 182,491,662; `decoder.int8.onnx` 755,914,231; `tokenizer/vocab.json` 2,776,833; `tokenizer/merges.txt` 1,671,853; `tokenizer/tokenizer_config.json` 12,487 | `small-encoder.int8.onnx` 112,442,483; `small-decoder.int8.onnx` 262,226,114; `small-tokens.txt` 816,730 |
| Total | 987,015,347 bytes | 375,485,327 bytes |
| Languages | 30, Italian among them | 99 |

Two more are described and not offered: Whisper base
(`csukuangfj/sherpa-onnx-whisper-base` at `bb53ee204431c90d314c1cc08d28d23e5b7927cc`,
160,609,290 bytes), which earlier versions of the app downloaded and which keeps working
where it is; and Whisper tiny (`csukuangfj/sherpa-onnx-whisper-tiny` at
`65176e2deb88badc814a94058666cadccc29b61c`, 103,609,903 bytes), for the tests.

Qwen3-ASR is the iPhone app's model in another conversion: 8-bit ONNX in place of 4-bit
MLX, exported by Wasser1462 (`github.com/Wasser1462/Qwen3-ASR-onnx`) and published by the
sherpa-onnx project under its second Hugging Face account. Its three tokenizer files have
the SHA-256 the iPhone app checks for its own. All the files of Qwen3-ASR and of Whisper
small were downloaded by the app on a phone and passed verification; the hashes of Qwen3-ASR
were also compared with the archive of the GitHub release.

### On a phone

#### A phone sold with 8 GB

Samsung Galaxy A54 (Exynos 1380, sold with 8 GB, of which Android reports 7.6 GB), four
threads, the debug build, 88.5 s of speech (one person, Italian with English technical
words) in ten pieces of at most 12 s. Memory is what `dumpsys meminfo` reports for the
app, once a second; the app alone, before, is 0.2 GB.

| | Qwen3-ASR 0.6B | Whisper small |
|---|---|---|
| Load | 5.5 to 12.9 s (four runs) | 2.7 to 3.1 s (two runs) |
| Recognize 88.5 s | 44.6 to 55.2 s: 0.50 to 0.62 of real time | 56.5 to 58.2 s: 0.64 to 0.66 |
| Peak memory of the app (PSS) | 2.15 to 2.25 GB | 0.95 to 0.98 GB |
| … of which native heap | 1.96 to 2.06 GB | 0.76 to 0.79 GB |
| Memory the phone had left, before and at the lowest | 3.6 GB, 1.8 GB | 3.7 GB, 3.2 GB |
| Clearly wrong words of about 190, language set to Italian | about 9 | about 18 |
| … language Auto | about 12, three of them a sentence written in Spanish | about 18 (same text) |

The wrong words were counted by reading the texts against each other, without a
reference text: a reading, not a score. Qwen3-ASR wrote "porting dell'app", "computer",
"ram" and "input" right; Whisper small wrote "compiuto" and "ramba". With pieces of up to
24 s in place of 12 s (a build made for the trial, not kept) Qwen3-ASR wrote about 6
wrong words in place of 9, took 57.6 s and 2.46 GB.

After a job the app keeps 0.3 to 0.4 GB more than before it: the model is freed, the
pages are not all given back.

Whisper base, on the same phone and speech: loads in 1.1 s, recognizes in 16.5 s (0.19 of
real time), takes 0.35 GB, and writes far more wrong words than either (over 30, with
Auto one piece in German).

#### A phone sold with 6 GB

Xiaomi Mi 10T (Snapdragon 865, sold with 6 GB, of which Android 12 reports 5.9 GB, with
4 GB of compressed swap), four threads, the debug build. 139 s of English speech put
together from three of the sample recordings published with the Qwen3-ASR export
(`rap1.wav`, `f1_noise.wav`, `noise1-en.wav`: fast speech over music, a radio call, two
people over traffic and rain), 131 s of it recognized in 15 pieces, language set to
English. Memory of the app as above; of the phone, `availMem` of
`ActivityManager.MemoryInfo` (what the check reads) and `MemAvailable` of `/proc/meminfo`,
twice a second. On this phone Android calls its memory low under 0.68 GB available
(`MemoryInfo.threshold`), and with the phone idle `availMem` moves between 2.0 and 2.9 GB.

| Run | Before it | `availMem` at the start | … at the lowest | Low memory, part of the job | `MemAvailable`, start and lowest | Swap in use, start and peak | Peak of the app | Load | Recognize 131 s | Processes closed: by the phone's memory service, by the low memory killer |
|---|---|---|---|---|---|---|---|---|---|---|
| Whisper small | idle | 2.52 GB | 1.72 GB | none | 2.19, 1.38 GB | 1.28, 1.28 GB | 0.99 GB | 1.8 s | 63.8 s | 0, 0 |
| Qwen3-ASR 1 | idle | 2.36 GB | 0.69 GB | none | 2.03, 0.38 GB | 1.33, 1.33 GB | 2.25 GB | 5.1 s | 63.5 s | 17, 6 |
| Qwen3-ASR 2 | idle | 2.44 GB | not read | not read | 2.14, 0.39 GB | 1.70, 1.71 GB | 2.27 GB | 5.0 s | 64.0 s | 10, 5 |
| Qwen3-ASR 3 | three apps opened | not read | 0.62 GB | 3 of 58 readings | 2.00, 0.29 GB | 1.45, 1.95 GB | 2.25 GB, 2.37 with what was swapped | 5.1 s | 68.0 s | 25, at least 19 |
| Qwen3-ASR 4 | six apps opened | 2.00 GB | 0.54 GB | 20 of 76 readings | 1.73, 0.22 GB | 1.73, 1.98 GB | 2.25 GB, 2.39 with what was swapped | 5.1 s | 71.9 s | 35, 25 |
| Qwen3-ASR 5 | six apps opened | 1.75 GB | not read | not read | 1.49, 0.16 GB | 1.88, 2.09 GB | 2.23 GB, 2.31 with what was swapped | 5.4 s | 66.3 s | 18, 18 |

Every run completed; the app was never closed and stayed in front, and the phone answered
afterwards. The processes closed were mostly cached ones; in the runs after other apps
also services (down to priority 500, and two providers at 250), and Android's low memory
killer reported "critical pressure" and "direct reclaim". With less available memory at
the start more was closed, and the job took up to an eighth longer. In four earlier runs on
this phone (88.5 s of Italian speech) Qwen3-ASR also completed each time, with a peak of
2.35 GB.

Word errors against the reference texts that come with the recordings (494 words; lower
case, punctuation removed, word by word): Qwen3-ASR 128 (25.9 %: 19 of 79, 4 of 31 and
105 of 384 in the three recordings), the same in the two runs whose text was kept whole; Whisper small 195 (39.5 %: 49
of 79, 6 of 31, 140 of 384). Whisper small wrote "(upbeat music)" for the first eleven
seconds of speech over music. The recordings are hard on purpose; these are not the
figures of clean speech.

#### What follows from the figures

`SpeechModel.memory` holds what each model takes (2.1 GB, 0.8 GB and 0.35 GB; for Whisper
tiny the figure of a computer, below).

`SpeechModels.fits` decides whether a model is loaded at all. It used to want as much
memory available as the model takes, and on the phone sold with 6 GB that refused
Qwen3-ASR in two attempts of six, because 2.1 GB lies inside the range the figure moves
in by itself. Available memory is not all a phone can give: Android closes apps nobody is
looking at and compresses memory, and that is where the model's room came from in every
run above. The rule now: nothing is loaded while Android itself calls its memory low
(`MemoryInfo.lowMemory`, the check the platform documentation gives for memory-intensive
work), and otherwise three quarters of what the model takes must be available: 1.58 GB
for Qwen3-ASR, 0.6 GB for Whisper small. No run failed, so the floor is not where loading
breaks; it is kept just under the lowest start that was measured (1.75 GB), because
with less at the start more was closed. What happens below was not measured.

`QWEN_MINIMUM_RAM_BYTES` stays at 7 GB. On the phone sold with 8 GB Qwen3-ASR left 1.8 GB
of 3.6 GB. On the one sold with 6 GB it ran every time, but by having Android close
other processes 15 to 60 times a run, on a fast processor and with 4 GB of swap; a phone sold
with 6 GB that has less swap or a slower processor was not measured, nor any sold with
4 GB. Those phones get Whisper small by themselves and Qwen3-ASR when the person picks it.
From Android 17 the system also limits an app's memory by the phone's size (source.android.com,
"Memory Limiter": 4 GiB while the app is in front and 2 GiB while it is not on phones
reporting 4.8 to 6.8 GiB; 2 GiB and 1 GiB on those reporting 3.2 to 4.8 GiB), which Qwen3-ASR
passes in front on the first group and does not on the second. Not tried on Android 17.

## Versions and licenses

| Part | Version | License |
|---|---|---|
| sherpa-onnx | 2.0.1 (26 October 2026) | Apache 2.0 |
| ONNX Runtime, linked into it | 1.28.2 | MIT |
| Whisper weights (OpenAI), as converted by the sherpa-onnx project | revisions above | MIT |
| Qwen3-ASR weights (Alibaba Cloud), as converted by Wasser1462 and published by the sherpa-onnx project | revision above | Apache 2.0 |

The sherpa-onnx library also contains code from abseil, kaldi-decoder, kaldifst, OpenFst
and simple-sentencepiece (Apache 2.0), protobuf and RE2 (BSD), found by their names in the
binary; this list may be incomplete. Since 2.0.0 it no longer contains espeak-ng (GPL),
which earlier versions did, so do not go back to 1.x without checking. An app that ships
this module has to show these notices. The Hugging Face repositories of the converted
models carry no license text of their own.

## How sherpa-onnx gets in

sherpa-onnx is not on Maven Central or Google Maven. Its Android library is a file on the
GitHub release; JitPack serves those same files under `com.github.k2-fsa:sherpa-onnx`
but is one more party to trust and one more repository in `settings.gradle.kts`. So
`build.gradle.kts` downloads the release file itself, once, into `speech/.sherpa-onnx/`
(ignored by git), and refuses it unless the SHA-256 matches:

- `sherpa-onnx-static-link-onnxruntime-2.0.1.aar`, 38,577,050 bytes,
  SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`.
  The variant with ONNX Runtime inside the one library, so there is no `libonnxruntime.so`
  to clash with another copy if the app ever uses ONNX Runtime directly. From it:
  `classes.jar` (sherpa-onnx's Kotlin API, packed into this module's library) and
  `libsherpa-onnx-jni.so` for three phone types (x86 is left out, as in `:rnnoise`).
- For the tests, `sherpa-onnx-native-lib-linux-x64-2.0.1.jar` (10,417,663 bytes, SHA-256
  `744330a6…f1b4b2cb3f`) or its macOS or Linux arm64 sibling: the same code built for a
  computer.

The first build of the module therefore needs the network, as any dependency does.

| `libsherpa-onnx-jni.so` | Size | Compressed in the library | LOAD alignment |
|---|---|---|---|
| arm64-v8a | 24,177,488 bytes | 8,961,888 | 0x4000 (16 KB) |
| armeabi-v7a | 16,498,184 bytes | 8,075,397 | 0x4000 |
| x86_64 | 27,371,968 bytes | 9,978,129 | 0x4000 |

Checked with the NDK's `llvm-readelf -lW`: every LOAD segment of all three is aligned to
0x4000, which is what phones with 16 KB memory pages need. `speech-release.aar` is 27.3 MB.
The library needs Android 5 (API 21) or later; this module asks for 26 like the others.

Expected growth of the app: about 9 MB of download and 24 MB installed for an arm64
phone, mostly ONNX Runtime; a single APK for all three phone types grows by about 27 MB
compressed, 68 MB if native libraries are stored uncompressed. The library has every
sherpa-onnx feature in it (speech synthesis, speaker detection, other recognizers), not
only Whisper and Qwen3-ASR (which 2.0.1 already had: adding it changed nothing in the app's size); a build of sherpa-onnx with only offline recognition would be smaller and
was not attempted. Not measured in a real APK: `:app` does not use the module yet.

## Building and testing

`./gradlew :speech:assembleRelease` builds the library. No NDK is needed: nothing is
compiled, the native libraries are sherpa-onnx's own.

`./gradlew :speech:testDebugUnitTest` needs no phone. Always run: the fetcher against a
web server on this computer (verification, resume, a server that cannot resume, wrong and
overlong content, cancelling, files copied by hand, a source that reads local files), the
word and window arithmetic, the refusal of files that are not a model, a model whose
files live in a folder of their own, and the choice of model by memory and by what is on
the phone.

The tests with real speech download models, so they run only when told where to keep
them, which must be outside the repository:

```
./gradlew :speech:testDebugUnitTest -Pspeech.models=/some/folder
./gradlew :speech:testDebugUnitTest -Pspeech.models=/some/folder -Pspeech.timedModel=/folder/with/a/base/export/with/attention
```

They fetch tiny and base (264 MB) and four short recordings from Hugging Face through
`SpeechModelFetcher`. Qwen3-ASR (987 MB) is not fetched for a test: its tests run when
its six files are already in `<folder>/qwen3-asr-0.6b`, copied there by hand (they are
verified and adopted). They load sherpa-onnx built for this computer through the same Kotlin
classes the phone uses, and print what was recognized. Linux x64 was run; macOS and Linux
arm64 are wired the same way and were not.

Measured on a desktop (Ryzen AI 9 HX 370, 24 threads, other work running; three runs
varied by the ranges shown). For a phone see "On a phone" above. On another computer
(16 threads, other work running) Qwen3-ASR loaded in 7.5 s, recognized a 12 s piece in
7.4 s and grew the test process by 1.0 GB.

| | tiny, 4 threads | tiny, 1 thread | base, 4 threads | base, 1 thread |
|---|---|---|---|---|
| Load | 0.15–0.44 s | 0.14–0.46 s | 0.24–0.49 s | 0.31–0.33 s |
| One 12 s piece | 0.36–0.54 s (22–33× real time) | 0.53–1.04 s | 0.61–0.86 s (14–20×) | 1.06–1.27 s (9–11×) |
| 48.5 s in 24 s windows | 1.6–4.0 s | 2.3–3.1 s | 3.2–4.0 s (12–15×) | 4.6–4.9 s (10×) |
| Memory over the test process, loaded | 30–60 MB | | 100 MB | |
| … after 12 s pieces | 95–150 MB | | 280 MB | |
| … after 24 s windows | 250–300 MB | | 400–460 MB | |

Memory is the growth of the resident size of the whole test process, read from
`/proc/self/status`, so it is approximate.

## What the app has to add

1. `implementation(project(":speech"))`. Nothing else: the sherpa-onnx classes, the native
   libraries and the keep rules come with the module.
2. **A place for the model**: a folder under `noBackupFilesDir` (or `filesDir` excluded
   from backup), as the iPhone app keeps its model out of backups. `installed()` on every
   start is cheap; offer `remove()` to give the space back.
3. **16 kHz mono floats.** Decode the edited mix with the tools the app already has
   (MediaExtractor and MediaCodec, or Media3's audio processors), average the channels and
   resample to 16 kHz, into a temporary file as the iPhone app does, not into memory:
   an hour is 230 MB as floats. `transcribe` also accepts another sample rate and lets
   sherpa-onnx resample, which is simpler for a first version.
4. **Pieces.** Measure RMS per 20 ms of that audio, give it to
   `SubtitleSpeechPlan.chunks(levels, duration)`, and call `transcribe` once for each
   chunk's `range` with the samples of that range. Then, for each chunk:
   - `words == null` (published models): `SubtitleTiming.estimate(transcript.text,
     chunk.speech)`, exactly as now;
   - `words != null`: `SubtitleWord(text = word.text, start = chunk.range.start + word.start,
     end = chunk.range.start + word.end)`.
   Then `SubtitleTiming.group(words, maxWords)` either way. Check for cancellation between
   chunks, and save progress between them if the iPhone app's resume is wanted.
5. **The licenses** above in the app's notices.
6. **How the model gets onto the phone.** The app has no INTERNET permission today, and
   this module does not need one. The choices, none of them made here:
   - *Download in the app.* Add the INTERNET permission and a `SpeechModelSource` over
     HTTPS; `HttpSource.kt` in this module's tests is one, 20 lines with the platform's
     own client, and is what the tests download with. One request per file to
     huggingface.co, which redirects to its storage servers; the app's "no network at
     all" statement and `PRIVACY.md` would have to change. Hosting the same files under
     an address the project controls removes the dependence on someone else's repository.
   - *The user brings the file.* No permission. The user downloads the three files with a
     browser and picks them (or one folder) with the system file picker; a
     `SpeechModelSource` that opens `content://` addresses copies them through
     `SpeechModelFetcher`, which verifies them, or the app copies them and calls
     `adopt()`. Three files of which one is 131 MB is clumsy for a person; publishing a
     single archive and unpacking it in the app would be kinder and is not written.
   - *In the installation package.* 160 MB of assets makes the APK too large for a normal
     Play upload; Play Asset Delivery (an install-time or on-demand asset pack) carries it
     without the app itself having network access, but ties the feature to Play.
   - *From a computer.* `adb push` into the app's external files folder and `adopt()`;
     for development.

## Limits and things to know

- A damaged model file must never reach `SpeechRecognizer`. A broken network file is
  refused with an exception, but a token table that is not text made sherpa-onnx end the
  whole process (exit code 255 in a test). `installed()` is true only after every hash
  matched; do not bypass it.
- sherpa-onnx writes warnings to the log (logcat on a phone), including one line per
  recognizer that the model has no attention outputs.
- Seen in tests with base: the same sentence said twice with a pause between, in one
  piece, came back once. Two different sentences with up to 5 s of silence between came
  back whole. English followed by Italian in one piece with detection: the English was
  lost. One piece, one language.
- Whisper decodes greedily here (no beam search, no retry at a higher temperature as
  OpenAI's own program does), so it is somewhat less accurate than that program with the
  same weights. Whisper's own sentence times (`enableSegmentTimestamps`) exist in the C++
  code but do not reach the Kotlin result in 2.0.1.
- Each window or piece is recognized without knowing what was said before it.
- Chinese, Japanese and Thai: with word times, every token becomes a word; not tested
  with real speech. Only English and Italian were.
