# deepfilter

Noise reduction for recorded voice with [DeepFilterNet3](https://github.com/Rikorose/DeepFilterNet),
run by [ONNX Runtime](https://onnxruntime.ai), with a small Kotlin API. It is the model the
iPhone app offers as "DeepFilterNet3" beside RNNoise, and it is used the same way: on
recorded audio at 48 kHz, never during capture, with an amount from 0 to 1.

## What is inside

- `DeepFilter.kt` is one stream: 480 samples in, cleaned samples out. It is a port of
  `DeepFilterNetEngine.swift` from the iPhone app (`ThirdParty/DeepFilterNet3`): the same
  960-sample frames with a Vorbis window, the same 32 band levels and 96 low bins shown to
  the network, the same gains and five-tap filter applied to the sound, the same handling
  of the 20 ms the network looks ahead.
- `Denoise.kt` cleans a whole channel in place, as `DeepFilterVoiceProcessor.process` does
  in the iPhone app. `ChannelDeepFilter.kt` does the same for audio that arrives in pieces.
- `DeepFilterModel.kt` loads the network. `Fft.kt` is the transform for 960 samples.
- `OnnxSession.kt` and `src/main/cpp/onnx_session.cpp` run it: a few calls of ONNX Runtime's
  C API, on the `libonnxruntime.so` the app carries for sherpa-onnx (see "Building").
- `src/main/resources/app/takes/deepfilter/streaming-graph.onnx` (61 KB) describes the
  network frame by frame. It holds no weights; see below.

## The model file

The weights are not in git. The build fetches them once (`fetchModel`, into `models/`,
which git ignores), checks their SHA-256, and packs them into the library as the resource
`app/takes/deepfilter/deepfilter.onnx`, so the app carries them inside its APK and the
phone never downloads anything. `DeepFilterModel.bundled()` loads them from there.

| | |
|---|---|
| File | `deepfilter.onnx` |
| From | <https://huggingface.co/soniqo/DeepFilterNet3-ONNX>, revision `63d8ba442ba900143c468b798e94a04009b2f0c9` |
| Size | 8 608 859 bytes |
| SHA-256 | `e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3` |
| License | MIT (DeepFilterNet3 v0.5.6 by Hendrik Schröter, converted by soniqo) |

The name, address, size and hash are constants on `DeepFilterModel`. Any other file is
refused with a `DeepFilterModelException`. The other files published beside it are not needed.

The published file computes a whole recording in one call, which takes memory in
proportion to its length. `streaming-graph.onnx` is the same network rearranged to take
one 10 ms frame at a time, with what its recurrent layers and convolutions remember passed
in and out. It comes from [speech-core](https://github.com/soniqo/speech-core)
(`src/models/deepfilter/deepfilter_streaming_graph.inc` at commit
`ad0f4ede90bb2bb06f4b4410eef1cb0759f7b5a1`, Apache-2.0), was generated from exactly the
file above, and reads its weights out of that file by position. A test checks that it
gives the same numbers as the published file.

## Using it

```kotlin
val model = DeepFilterModel.bundled()!!   // once; about 9 MB

deepFilter(model, samples, amount = 1f)   // one channel, 48 kHz, floats in −1…1, changed in place

// or, for audio that arrives in pieces, with the same calls as rnnoise's ChannelDenoiser:
ChannelDeepFilter(model, amount).use { channel ->
    channel.write(block, 0, count) { cleaned, valid -> /* copy `valid` samples out */ }
    channel.finish { cleaned, valid -> /* the rest */ }
}

model.close()
```

The audio must be 48 kHz. Resample anything else before and after. For stereo, split the
channels and clean each on its own with the same model; that may be done on two threads.
The length and timing of the audio do not change. At an amount of 0 the model is not run
and the samples are left exactly as they were, as in the iPhone app; a new effect there
starts at an amount of 1. There are no other settings.

To stop early, throw from the progress callback of `deepFilter`, or stop calling `write`.

## Not the same numbers as the iPhone

The steps are the same, but the result is not bit for bit what an iPhone produces: the
network there is a Core ML conversion working in 16-bit floats, here it is the 32-bit
ONNX conversion, and the two start a recording slightly differently (see the comments in
`DeepFilter.kt`).

On a Galaxy A54 (Exynos 1380) a minute of mono speech with room and pink noise is cleaned
in 9.7 s, 6.2 times faster than real time, on one thread; loading the network takes 130 ms
(`DeepFilterRunner` in the app's instrumentation tests).

## Building

`./gradlew :deepfilter:assembleRelease`, with the NDK the build file names. This module
carries no ONNX Runtime of its own. `src/main/cpp/onnx_session.cpp` (240 KB for arm64-v8a,
84 KB compressed) opens the `libonnxruntime.so` that sherpa-onnx brings into the app
(`speech/`, ONNX Runtime 1.28.2) and calls its C API, which runtimes from 1.28 on serve. So the
app has one copy of the runtime, used by the speech recognizers and by DeepFilterNet3 alike.
The C headers come from ONNX Runtime's repository at the commit tagged v1.28.2
(`33ca9628233dc8f002435e868d4c2e9f82766ca1`); the build fetches the three it needs once
(`fetchOnnxRuntimeHeaders`) into `.onnxruntime/`, which git ignores, and checks their SHA-256.

ONNX Runtime's Java API (`com.microsoft.onnxruntime:onnxruntime-android`) would bring a second
runtime of about 36 MB for arm64-v8a: its bridge cannot be pointed at sherpa-onnx's copy,
whose exported symbols are versioned 1.28.2 where the bridge wants 1.28.0, and sherpa-onnx
publishes no build on a newer runtime. sherpa-onnx's own speech enhancement (GTCRN, DPDFNet)
cannot run this network either. The runtime as a library of its own makes sherpa-onnx's
part of the app about 2.7 MB larger than the variant with the runtime linked in (see
`speech/README.md`).

There is no ONNX Runtime for 32-bit x86 in the app, as there is no RNNoise or sherpa-onnx:
there `DeepFilterModel` cannot be made and the app plays the recording as it is.

In the Play bundle DeepFilterNet3 adds about 9 MB to an arm64-v8a phone's download (8.0 MB of
weights, 0.9 MB for the runtime as a library of its own, 0.08 MB for this module's library)
and about 12 MB to the installed app.

## Testing

`./gradlew :deepfilter:testDebugUnitTest` needs no phone. `DeepFilterTest` covers
everything around the network with a stand-in for it. `DeepFilterModelTest` runs the real
network on this computer and is skipped when the weights are missing; fetch them once with
`./gradlew :deepfilter:fetchModel` (into `deepfilter/models/`, which git ignores), or point
`TAKES_DFN3_MODEL` at the file. It builds `src/main/cpp` for this computer with the system C++
compiler and the SDK's CMake, and runs it on sherpa-onnx's ONNX Runtime for this computer,
which the speech module unpacks; on a computer sherpa-onnx has no library for, it is skipped.
It gives the same samples, bit for bit, as ONNX Runtime 1.32's Java API did.

On a phone, beside sherpa-onnx's recognizers in the same process:

    adb shell am instrument -w -e clip /data/local/tmp/take.mp4 app.takes.dev.test/app.takes.sound.DeepFilterRunner

## Licenses

DeepFilterNet and its weights are under the MIT license (or Apache-2.0, at your choice),
speech-core's frame-by-frame graph under Apache-2.0, ONNX Runtime under MIT. The notices
are in [`NOTICES.txt`](NOTICES.txt) and [`LICENSE-APACHE`](LICENSE-APACHE). An app that ships this module has to show them.
