# speech

Speech to text on the device, for subtitles: OpenAI's [Whisper](https://github.com/openai/whisper)
run by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), behind a small Kotlin API.
The model is not in the app; it is fetched separately and verified. The module has no
Android permission and never opens a network connection by itself.

## What is inside

- `SpeechRecognizer.kt`: samples in, text out, and words with times when the model gives them.
- `SpeechModel.kt`: which files a model consists of, where they come from, their lengths
  and SHA-256; whether a folder holds a complete model.
- `SpeechModelFetcher.kt`: puts a model into a folder through a `SpeechModelSource` the
  app supplies, resuming partial files. Plain JVM code, nothing from Android.
- sherpa-onnx 2.0.1 itself: its Kotlin classes and `libsherpa-onnx-jni.so` for arm64-v8a,
  armeabi-v7a and x86_64. Not stored in the repository, see "How sherpa-onnx gets in".

## Using it

```kotlin
val model = SpeechModel.Default                       // Whisper base, 160.6 MB
val folder = File(context.noBackupFilesDir, "speech/base")
if (!model.installed(folder)) SpeechModelFetcher.download(model, folder, source, progress, cancelled)

SpeechRecognizer(folder, language = "it").use { recognizer ->   // null detects the language
    val transcript = recognizer.transcribe(samples)             // 16 kHz, one channel, floats in −1…1
    transcript.text       // "gli uomini sono turbati non dalle cose ma dal modo in cui le vedono"
    transcript.language   // "it"
    transcript.words      // null with the published models, see "Word times"
}
```

The API as written:

```kotlin
class SpeechRecognizer(modelFolder: File, language: String? = null, model: SpeechModel = SpeechModel.Default,
                       threads: Int = min(4, processors)) : AutoCloseable {
    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000, language: String? = this.language): Transcript
    override fun close()
}
data class Transcript(val text: String, val words: List<Word>?, val language: String)
data class Word(val text: String, val start: Double, val end: Double)   // seconds from the first sample

data class SpeechModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)
data class SpeechModel(val id: String, val encoder: SpeechModelFile, val decoder: SpeechModelFile,
                       val tokens: SpeechModelFile, val multilingual: Boolean = true) {
    val files: List<SpeechModelFile>; val bytes: Long
    fun installed(folder: File): Boolean                 // marker and lengths: cheap
    fun damaged(folder: File, cancelled): List<String>   // reads and hashes everything
    fun adopt(folder: File, cancelled): Boolean          // verify files put there by hand, then mark
    fun remove(folder: File)
    companion object { val WhisperBase; val WhisperSmall; val WhisperTiny; val Default = WhisperBase
                       fun whisper(id, name, base, encoder, decoder, tokens, multilingual) }
}
fun interface SpeechModelSource { fun open(url: String, offset: Long): Opened }
object SpeechModelFetcher { fun download(model, folder, source, progress = …, cancelled = …) }
```

- **Threads.** Everything blocks; call from a background thread. One `transcribe` runs at a
  time on a recognizer. A call cannot be interrupted, so cancel between pieces.
- **Lifetime.** Creating a recognizer loads the model (0.2–0.5 s on a desktop). Create it
  for a transcription job and `close()` it after: its memory is native and the garbage
  collector does not see it.
- **Language.** Whisper's codes. The iPhone app's list is Auto, it, en, fr, de, es, pt, zh,
  ja, ko, ru, ar. `null` detects, separately for every call (and every window of a long
  call): for a job cut into pieces, detect on the first piece and pass
  `transcript.language` to the rest, or two pieces can come out in two languages.
  A forced wrong language gives fluent nonsense or a translation, not an error.
- **Length.** Whisper reads 30 s at once and sherpa-onnx drops the rest without an error
  (tested: 80 of 132 words came back). `transcribe` therefore cuts longer audio into
  windows of at most 24 s at the quietest moment of each window's last third. The app's own
  plan of 12 s pieces at pauses is better: shorter pieces are faster and use less memory.
- **Silence.** A piece with no signal at all is skipped. Quiet hiss is not, and Whisper can
  invent a word or two for it (`1/2"` in a test), so only hand over stretches with speech.

## Word times

`Transcript.words` is null when the model gave no times, and then only the text is known.

**It is null for every model listed in `SpeechModel`.** sherpa-onnx 2.0.1 can compute a
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
one file at a time. (The GitHub release `asr-models` has the same models as `.tar.bz2`
archives that also contain the unquantized networks: 208 MB for base, 639 MB for small.)

| | base (default) | small | tiny (tests) |
|---|---|---|---|
| Repository | `csukuangfj/sherpa-onnx-whisper-base` | `csukuangfj/sherpa-onnx-whisper-small` | `csukuangfj/sherpa-onnx-whisper-tiny` |
| Revision | `bb53ee204431c90d314c1cc08d28d23e5b7927cc` | `8f3c18b358db4d1f2fc1eae49d75cd20989e4309` | `65176e2deb88badc814a94058666cadccc29b61c` |
| `<name>-encoder.int8.onnx` | 29,120,534 bytes | 112,442,483 bytes | 12,937,772 bytes |
| `<name>-decoder.int8.onnx` | 130,672,026 bytes | 262,226,114 bytes | 89,855,401 bytes |
| `<name>-tokens.txt` | 816,730 bytes | 816,730 bytes | 816,730 bytes |
| Total | 160,609,290 bytes | 375,485,327 bytes | 103,609,903 bytes |

The SHA-256 of every file is in `SpeechModel.kt`; the token table is the same file
for all three (`b34b360d…1f2fa126`). URLs are
`https://huggingface.co/<repository>/resolve/<revision>/<file>`. The tiny and base files
were downloaded and matched; the hashes of small are the ones Hugging Face reports and
were not downloaded here.

All three are multilingual (99 languages). English-only exports (`tiny.en`, `base.en`,
`small.en`) and larger ones (medium, large-v3, turbo, distil) exist in the same place.

Base is the default: on the test recordings it got names and words right that tiny got
wrong ("Hester", "parent"), at 1.5 times tiny's time and 57 MB more to download. Small is
described for a "better quality" choice; it was not run here.

## Versions and licenses

| Part | Version | License |
|---|---|---|
| sherpa-onnx | 2.0.1 (26 October 2026) | Apache 2.0 |
| ONNX Runtime, linked into it | 1.28.2 | MIT |
| Whisper weights (OpenAI), as converted by the sherpa-onnx project | revisions above | MIT |

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
only Whisper; a build of sherpa-onnx with only offline recognition would be smaller and
was not attempted. Not measured in a real APK: `:app` does not use the module yet.

## Building and testing

`./gradlew :speech:assembleRelease` builds the library. No NDK is needed: nothing is
compiled, the native libraries are sherpa-onnx's own.

`./gradlew :speech:testDebugUnitTest` needs no phone. Always run: the fetcher against a
web server on this computer (verification, resume, a server that cannot resume, wrong and
overlong content, cancelling, files copied by hand, a source that reads local files), the
word and window arithmetic, and the refusal of files that are not a model.

The tests with real speech download models, so they run only when told where to keep
them, which must be outside the repository:

```
./gradlew :speech:testDebugUnitTest -Pspeech.models=/some/folder
./gradlew :speech:testDebugUnitTest -Pspeech.models=/some/folder -Pspeech.timedModel=/folder/with/a/base/export/with/attention
```

They fetch tiny and base (264 MB) and four short recordings from Hugging Face through
`SpeechModelFetcher`, load sherpa-onnx built for this computer through the same Kotlin
classes the phone uses, and print what was recognized. Linux x64 was run; macOS and Linux
arm64 are wired the same way and were not.

Measured on a desktop (Ryzen AI 9 HX 370, 24 threads, other work running; three runs
varied by the ranges shown). **A phone will be several times slower; nothing was run on
one.**

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
