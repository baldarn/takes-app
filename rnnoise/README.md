# rnnoise

Noise reduction for recorded voice: the [RNNoise](https://github.com/xiph/rnnoise) C
library with a small Kotlin API. It is the same library, network and adapter the iPhone
app uses, so both apps clean audio the same way.

## What is inside

- `src/main/cpp/rnnoise/` is a copy of `ThirdParty/RNNoise` from the iPhone app, file for
  file: RNNoise v0.1, commit `cdf196b1e9de2f8ff1003328ebf9a4316477429d`, with the
  pretrained weights unchanged. The only change to upstream is the one the iPhone app
  made (two assertions in `rnn.c` became `abort()`); see `PROVENANCE.md` there.
- `src/main/cpp/rnnoise_jni.c` connects it to Kotlin.
- `RNNoise.kt` is one denoiser state: 480 samples in, 480 out, plus the probability that
  the frame is speech.
- `Denoise.kt` cleans a whole recording in place with an amount from 0 to 1, as
  `OfflineAudioPipeline.denoise` does in the iPhone app.

## Using it

```kotlin
denoise(samples, amount = 1f)   // one channel, 48 kHz, floats in −1…1, changed in place
```

The audio must be 48 kHz. Resample anything else before and after. For stereo, split the
channels and call `denoise` once for each. The length and timing of the audio do not
change. An `RNNoise` object must be closed, and is for one channel of one recording.

## Building

`./gradlew :rnnoise:assembleRelease` builds `librnnoise_jni.so` for arm64-v8a,
armeabi-v7a and x86_64. It needs NDK 29.0.14206865 and CMake 3.22.1 in the Android SDK
(`sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"`). The libraries are linked for 16 KB
memory pages.

## Testing

`./gradlew :rnnoise:testDebugUnitTest` needs no phone. It builds the same C code for this
computer with the system C compiler (`cc`), loads it into the test JVM and runs real
audio through it. Linux and macOS only.

## Licenses

RNNoise is under the BSD 3-Clause license, with parts under BSD 2-Clause (KISS FFT, CELT
and Opus routines, the network code). The texts are in
[`src/main/cpp/rnnoise/COPYING`](src/main/cpp/rnnoise/COPYING) and
[`src/main/cpp/rnnoise/NOTICES.txt`](src/main/cpp/rnnoise/NOTICES.txt); the second is the
RNNoise part of the notices the iPhone app shows. An app that ships this module has to
show them too.
