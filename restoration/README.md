# restoration

The models of Enhance audio's two speech restoration stages, as the iPhone app has them:
**Speech detail restoration** with [LavaSR v2](https://github.com/ysharma3501/LavaSR) and
**Studio restoration** with [VoiceFixer](https://github.com/haoheliu/voicefixer). The iPhone
app carries Core ML conversions of both; Takes downloads published ONNX conversions of the
same checkpoints when the person adds the stage, and runs them with the ONNX Runtime the app
already carries for sherpa-onnx, through `deepfilter/`'s bridge (`OnnxSession`). The APK does
not grow by the models.

## What is where

- `core/` (`app.takes.core`): the signal processing, ported from the iPhone app's
  `ThirdParty/LavaSR/LavaDSP.cpp`, `ThirdParty/VoiceFixer/VoiceFixerDSP.cpp`,
  `StudioVoiceProcessor.swift` and `StudioRestorationProcessor.swift`: `LavaDsp`,
  `VoiceFixerDsp`, `Resampler`, `ReflectedWindows`, and the stages one channel at a time,
  `SpeechDetailChannel` and `StudioRestorationChannel`. Tested there with stand-in networks.
- `RestorationModels.kt`: the files of each model, their addresses, sizes and SHA-256, which
  `speech/`'s `SpeechModelFetcher` downloads and checks.
- `RestorationNetworks.kt`: the networks, `LavaNetwork` and `VoiceFixerNetwork`. Every file is
  checked against its SHA-256 again once per process before ONNX Runtime reads it.
- The app (`RestorationModelStore.kt`, `RestorationDownload.kt`, `Denoiser.kt`): where the
  models live, the download, and the stages in the chains.

## The models

| | LavaSR v2 | VoiceFixer |
|---|---|---|
| From | [Topping1/LavaSR-ONNX](https://github.com/Topping1/LavaSR-ONNX), release `Alpha-v0.1` | [TigreGotico/audiosronnx-voicefixer](https://huggingface.co/TigreGotico/audiosronnx-voicefixer), revision `aa259fa578b9d5f4c76fda3cd967f73542b0e15b` |
| Files | `enhancer_backbone.onnx` + `.data`, `enhancer_spec_head.onnx` + `.data` | `analysis.onnx`, `vocoder.onnx`, `mel_filters.npy` |
| Size | 56,235,055 bytes | 415,446,261 bytes |
| Weights | Yatharth Sharma, Apache-2.0 (checkpoint `d100db96…` on Hugging Face, the one the iPhone app converts) | Haohe Liu, CC BY 4.0 ([Zenodo 5600188](https://doi.org/10.5281/zenodo.5600188)); code MIT |
| Conversion | Apache-2.0 | stated as MIT by the converter; the weights stay CC BY 4.0 |

The VoiceFixer graphs fix their input at 501 mel frames (the converter's five-second window).
The iPhone app uses 256 (`context256`). Each graph's input dimension is a two-byte protobuf
varint, `f5 03` (501), at byte 282,015,972 of `analysis.onnx` and 132,905,242 of
`vocoder.onnx`; `VoiceFixerNetwork` maps the verified file privately and reads those two bytes
as `80 02` (256). The file on disk is not changed and no second copy of the weights is made.
Nothing else in either graph depends on 501 (checked by reading every constant, attribute and
value shape with the `onnx` package); with the change the graphs take `[1,1,256,128]`, the
analysis returns `[1,1,256,128]` and the vocoder `[1,1,114660]`, as the iPhone app's graph.
`mel_filters.npy` holds, after its 128-byte NumPy header, exactly the iPhone app's
`VoiceFixer-Mel.bin`.

## Tests

`./gradlew :restoration:testDebugUnitTest -Prestoration.models=<folder outside the repository>`
downloads the models once into that folder (about 470 MB) and a test recording, and runs both
stages on it on this computer. Without the property those tests are skipped.
