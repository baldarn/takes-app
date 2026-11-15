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
- `src/main/resources/app/takes/deepfilter/streaming-graph.onnx` (61 KB) describes the
  network frame by frame. It holds no weights; see below.

## The model file

The weights are not in this repository and not in the app. The caller supplies them:

| | |
|---|---|
| File | `deepfilter.onnx` |
| From | <https://huggingface.co/soniqo/DeepFilterNet3-ONNX>, revision `63d8ba442ba900143c468b798e94a04009b2f0c9` |
| Size | 8 608 859 bytes |
| SHA-256 | `e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3` |
| License | MIT (DeepFilterNet3 v0.5.6 by Hendrik Schröter, converted by soniqo) |

The name, address, size and hash are constants on `DeepFilterModel`. Any other file is
refused with a `DeepFilterModelException`. This module does not download anything; where
the file comes from (bundled as an asset, or fetched on first use) is for the app to
decide. The other files published beside it are not needed.

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
val model = DeepFilterModel(File(filesDir, DeepFilterModel.FILE_NAME))   // once; about 9 MB

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
`DeepFilter.kt`). Nothing here has been run on a phone yet, so speed and memory on Android
are not known.

## Building

`./gradlew :deepfilter:assembleRelease`. ONNX Runtime comes as
`com.microsoft.onnxruntime:onnxruntime-android`, which brings its own native libraries
for arm64-v8a, armeabi-v7a, x86 and x86_64 and adds several megabytes per architecture to
the app.

## Testing

`./gradlew :deepfilter:testDebugUnitTest` needs no phone. `DeepFilterTest` covers
everything around the network with a stand-in for it. `DeepFilterModelTest` runs the real
network on this computer and is skipped when the weights are missing; fetch them once with
`./gradlew :deepfilter:fetchModel` (into `deepfilter/models/`, which git ignores), or point
`TAKES_DFN3_MODEL` at the file.

## Licenses

DeepFilterNet and its weights are under the MIT license (or Apache-2.0, at your choice),
speech-core's frame-by-frame graph under Apache-2.0, ONNX Runtime under MIT. The notices
are in [`NOTICES.txt`](NOTICES.txt) and [`LICENSE-APACHE`](LICENSE-APACHE). An app that ships this module has to show them.
