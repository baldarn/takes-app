# Segmentation probe

Findings of 2026-11-16, with the probe in `probes/segmentation/`. The probe answers the speed and quality question of decision V12 in `docs/PIP-AND-EFFECTS-PLAN.md`: which person-mask model should stand in for Apple Vision's `VNGeneratePersonSegmentationRequest` (`O/VideoEffects/VideoEffectAssets.swift:239-301`) on Android. Four effects depend on the answer: Auto Background, Color Isolation "The subject", Chroma Key's person hint and Quantization's face detail. The probe was run on two phones; the app was not changed.

## The question

The original asks Vision for a person mask:
- In the editor and export, `.accurate`: the frame scaled to 1536 px on its long side, the mask at most 1024 px.
- Live, `.balanced`: 512 px.

Everything after the mask follows the original, constant for constant, and is not part of this probe:
- the motion-gated EMA (`MetalVideoEffects.swift:695-746`)
- the guided filter and the coverage smoothstep (`:382-402`)
- the temporal window (`VideoEffectMath.swift:27-54`)

The only thing to choose is the model. It must meet three constraints:
- **Licence compatible with MIT.**
- **The app's permissions do not change.** It has CAMERA, RECORD_AUDIO and INTERNET, and INTERNET is documented as used only for the speech model. A bundled model needs nothing more. A downloaded one would need PRIVACY.md and the Play data-safety form changed.
- **The download per device does not grow much.** The release is 7.5 MB, and Play splits it per ABI.

## Candidates

| Model | Licence | File | Input → mask | Runtime |
|---|---|---|---|---|
| MediaPipe selfie segmenter, float16 (`selfie`) | Apache 2.0 (model card) | 250 KB | 256×256 → 256×256 | LiteRT 1.4.2 (Apache 2.0, ~5 MB native per ABI) |
| MediaPipe selfie multiclass (`multiclass`) | Apache 2.0 | 16.4 MB | 256×256 → 256×256×6 | LiteRT 1.4.2 |
| MODNet, ONNX of `Xenova/modnet` at `fa2fa54` (`modnet`) | Apache 2.0 (ZHKKKe/MODNet) | 25.9 MB | long side 512, to 32 → same | ONNX Runtime 1.27.0 (MIT, ~27 MB native per ABI) |
| MODNet quantized (`modnet_quantized`) | Apache 2.0 | 6.6 MB | as MODNet | ONNX Runtime 1.27.0 |

URLs and SHA-256 are in `probes/segmentation/tools/fetch-models.sh`.

Robust Video Matting is not a candidate: it is GPL-3.0.

Three things changed on the way:
- **LiteRT 2.x was not used.** Its `CompiledModel` API is compiled with Kotlin 2.4 metadata, which the app's Kotlin 2.2.21 cannot read. The GPU accelerator of 2.x starts at 2.3.0, so the probe uses LiteRT 1.4.2's `Interpreter` with its GPU delegate.
- **`selfie` needed rewriting.** It ends in MediaPipe's custom operator `Convolution2DTransposeBias`, which plain LiteRT cannot load ("unresolved custom op"). `probes/segmentation/tools/standard-selfie.py` replaces it with LiteRT's builtin `TRANSPOSE_CONV` with a bias (version 3): the same float16 weights and bias as float32 constants, padding SAME, stride 2. Checked on the computer against a direct evaluation of the custom operator on the layer's real input: largest difference 1.2e-4. Nothing else in the model changes; the file is 250 KB.
- **MediaPipe Tasks Vision was not used.** It would run the original file, but `tasks-core` 1.2.3 alone is 32 MB and it pulls in a 21 MB LLM runtime.

## How it was measured

- **`compare`** decodes each frame once with `MediaMetadataRetriever` (upright) and gives it to every model in turn.
  - **Time:** per frame, scaling and pre- and post-processing included, decoding not.
  - **Frame to frame:** the mean |Δα| and the fraction of pixels that cross 0.5, on a 320 px grid.
  - **Soft:** the share of pixels between 0.05 and 0.95.
  - **Agreement with the first model listed (MODNet):** IoU at 0.5 and the mean |Δα|.
  - **Sheets:** every 30th frame is saved with each mask over it.
- **`heat`** runs one model round and round on 30 frames.

Footage: the user's own takes recorded with Takes on the Pixel, 1920×1080 H.264.
- `personB`: a person facing the camera in a dim room, 518 frames.
- `front`: the front camera, recorded and exported with Takes on 2026-11-16. A person in daylight turns, moves close and waves a hand across the frame, with a desk and a lamp behind; 768 frames.
- `personA`: cables and a desk, no person, 258 frames.
- A daylight close-up from the Pixel's camera app, 11367 frames.

## Results

Mean ms per frame over 90 frames. The A54 is a Galaxy A54 (Exynos 1380, Android 16); the Pixel is a Pixel 9a (Tensor G4, Android 17).

| Model | A54 | Pixel 9a | personB vs MODNet: IoU, mean |Δα| | personB frame to frame |Δα| | Soft |
|---|---|---|---|---|---|
| modnet (reference) | 272 | 524 | — | 0.0059 | 0.047 |
| selfie, GPU | 23 | 25 | 0.962, 0.021 | 0.0032 | 0.041 |
| selfie, CPU (XNNPACK, 4 threads) | 20 | 18 | 0.962, 0.021 | 0.0032 | 0.041 |
| multiclass, GPU | 131 | 117 | 0.974, 0.065 | 0.0030 | 0.600 |
| modnet_quantized, CPU | 286 | 356 | 0.000 (an empty mask) | — | — |

- **Load and memory:**
  - `selfie`: loads in 16–26 ms on the CPU, about 1 s on the GPU (shader compilation), and costs +20 MB on the CPU, +57 MB on the GPU.
  - `modnet`: +135 MB.
On `front` (A54 only: the Pixel was unplugged), with a lot of movement:

| Model | ms a frame | vs MODNet 512: IoU, mean |Δα| | Frame to frame |Δα| |
|---|---|---|---|
| modnet 512 (reference) | 318 | — | 0.059 |
| selfie, CPU | 25 | 0.686, 0.099 | 0.073 |
| selfie, GPU | 26 | 0.686, 0.099 | 0.072 |
| modnet 1024 | 1294 | 0.738, 0.084 | 0.082 |

- **Heat** (A54, two minutes on 30 frames): the thermal status stayed 0 throughout and the rate held steady:

  | Model | Frames a second |
  |---|---|
  | selfie, CPU | 53.5 |
  | selfie, GPU | 38.5 |
  | modnet | 3.4 |

- **Decoding is the slow part on the Pixel.** `MediaMetadataRetriever.getFrameAtIndex` took 680–700 ms a frame there against 35 ms on the A54. The app's analysis must read frames with a decoder in sequence, as the original reads them in chunks (`VideoEffectCaptureAnalysis.swift:312-397`), not by index.

What the sheets show:
- **personB:**
  - `selfie` follows the head, the hair line and the shoulders as closely as MODNet, and fills the bottom of the frame where MODNet leaves a hole in the shirt.
  - `multiclass` has the right silhouette, but leaves the whole background at a low α (61 % of the pixels between 0.05 and 0.95). The model gives scores, and the person is one minus the background after a softmax. LiteRT's Python interpreter on the computer gives the same on the same frame, so it is the model as it is used here, not the phone. It is slower and 65 times larger, so it was not pursued.
  - `modnet_quantized` returned an empty mask on this take and is out.
- **personA (no person):** every model marks part of the cables and the box as a person. MODNet marks least (10 % of the frame); `selfie` marks 25 % and `multiclass` a soft haze. Vision's behaviour on the same frame is not known.
- **front:**
  - MODNet at 512 is the best mask: the whole hand, every finger, the hood, the arm.
  - `selfie` gets the head and body, but:
    - it loses the fingers of a hand moving fast;
    - it leaves part of the hood and the lamp halfway;
    - its edge is blocky, from 256 px stretched over a 1080×1920 frame.
  - MODNet at 1024 is worse than at 512: it punches holes in the body and marks the wall. It was trained at 512, and a larger input does not stand in for Vision's 1024 px mask.
- **The daylight close-up:** MODNet and `multiclass` agree at IoU 0.99 (`selfie` was not yet runnable when this was measured). MODNet keeps strands of hair at the edge.

## Recommendation

**`selfie` on LiteRT 1.4.2, on the CPU, bundled in the app.**
- Its mask is worse than MODNet's on fast hands and at the edge (IoU 0.69 on `front`, 0.96 on a still person).
- It is the only candidate that is practical. At 300–500 ms a frame, MODNet would analyse a one-minute clip at 30 fps in 9 to 15 minutes; `selfie` does it in about 40 s.
- It takes about 20 ms a 1080p frame on both phones, with no heat, so the editor's analysis runs faster than real time.
- **Size:** about +5 MB of native code per ABI and 250 KB of model.
- **Permissions:** no new permission and no network.
- **CPU rather than GPU:** the CPU is as fast as the GPU on both phones, loads 50 times faster and costs a third of the memory. The GPU delegate would add `litert-gpu` for nothing.

**Departures from the original, to be written into docs/PARITY.md:**
- **Mask resolution.** The mask is 256×256 from a stretched frame, where Vision accurate gives up to 1024 px at the frame's shape. The original's guided filter (`refinedMask`, guide = the frame's luma at up to 1024 px) puts the edge back on the picture's own edges, which is what it does to Vision's mask too.
- **False positives.** Where there is no person, `selfie` marks more of the picture as a person than MODNet does.

**MODNet** stays the better mask: hair, fingers, fast movement. It could become a later "precise" choice, downloaded on first use like the speech models, if the user asks for one. It would cost ONNX Runtime's +27 MB per ABI, so it is not part of this round.

## Still open

- `front` on the Pixel (it was unplugged during that run).
- How much of `selfie`'s blocky edge and lost fingers the original's guided filter recovers: Stage 1 of the plan ports it, and the pixel tests will show it on these same frames.
- The 32-bit ABI (armeabi-v7a), which the A54 lists but which was not run.

## Running it

```
probes/segmentation/tools/fetch-models.sh <models>            # SCHEMA=<folder of schema_py_generated.py> also writes selfie_standard.tflite
JAVA_HOME=<JDK 17> ./gradlew -p probes/segmentation :probe:installDebug
SERIAL=<serial> probes/segmentation/tools/run.sh <models> <clip> compare -e models modnet:cpu:512,selfie:cpu,selfie:gpu -e frames 90 -e every 30
adb shell am instrument -w -e scenario heat -e clip <clip name> -e model selfie:cpu -e seconds 120 app.takes.probe.segmentation/.SegmentationProbe
```
