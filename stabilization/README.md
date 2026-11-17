# stabilization

The camera motion between two frames of a clip, measured by the iPhone app's own motion
estimator, for the editor's Stabilize tool. It is the same C++ code the original runs, so
both apps measure the same path from the same footage.

## What is inside

- `src/main/cpp/YouRec/` holds `StabilizationMotion.cpp` and `StabilizationMotion.h` from
  the original's `YouRec/` folder at commit `faf1815`, unchanged: grid-distributed
  Shi–Tomasi corners, pyramidal Lucas–Kanade with a forward/backward check, and a RANSAC
  similarity weighted toward the edges of the frame. It is the original's own source, under
  the same MIT licence as this repository, and uses no third-party code.
- `src/main/cpp/stabilization_jni.cpp` connects it to Kotlin. It reduces each decoded
  frame's luma to the analysis size (the longer side 640 pixels, as
  `YouRec/VideoStabilizer.swift:129-130`) by area averaging, stretches limited-range luma
  to full range as the original reads its frames, keeps the previous frame and compares
  the next one with it.
- `MotionEstimator.kt` is the Kotlin API: one estimator per clip being measured, fed its
  frames in order.

Chaining the motions into a camera path, the smoothing of each level and the crop are in
`core` (`StabilizationPath.kt`), and decoding the frames and keeping the path in the
project's cache in the app (`Stabilizer.kt`).

## Building

`./gradlew :stabilization:assembleRelease` builds `libstabilization_jni.so` for arm64-v8a,
armeabi-v7a and x86_64 with NDK 29.0.14206865 and CMake 3.22.1, linked for 16 KB memory
pages, as the `rnnoise` module.

## Testing

`./gradlew :stabilization:testDebugUnitTest` needs no phone. It builds the same code for
this computer with the system C++ compiler, runs the original's own test of the estimator
(`src/test/cpp/Tests/StabilizationMotionTests.cpp`, copied unchanged from its `Tests/`
folder) and checks that it passes, then runs frames through the JNI bridge the app uses:
a move and a turn at the analysis size, and a 1920 × 1080 picture, 8-bit and 10-bit,
limited range, reduced and measured. Linux and macOS only.
