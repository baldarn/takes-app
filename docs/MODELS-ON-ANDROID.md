# Running the original app's neural models on Android

Research notes from 2026-11-15. Nothing here was run or measured on a phone;
Android speed and memory figures are third-party or estimates. Verify before
relying on them.

## What the original does

- Every audio model runs offline on files at 48 kHz, never during capture, so
  speed only affects waiting time.
- Subtitle word timings are not produced by the recognizer: the app spreads
  words over energy-detected speech by word length. Any recognizer that
  returns text is a drop-in; one with real timestamps would do better.
- The DSP around LavaSR and VoiceFixer (`LavaDSP.cpp`, `VoiceFixerDSP.cpp`) is
  portable C++ with its own FFT and compiles under the NDK unchanged.

## Summary

| Model | Feature | Android route | Size | Difficulty | Main risk |
|---|---|---|---|---|---|
| RNNoise v0.1 | Default noise reduction | C through the NDK | under 1 MB | done separately | — |
| DeepFilterNet3 | Better noise reduction | ONNX Runtime, `soniqo/DeepFilterNet3-ONNX` with the C++ runtime from `soniqo/speech-core` | 8.6 MB | easy to medium | not bit-identical to iOS |
| LavaSR v2 | Speech detail restoration | own ONNX export from `scripts/convert-lavasr.py`, reuse `LavaDSP.cpp` | about 56 MB | medium | CPU speed unmeasured |
| VoiceFixer | Studio restoration | own ONNX export, reuse `VoiceFixerDSP.cpp` | 200–400 MB | hard | may be too slow and memory-hungry (0.5× real time and 590 MB on an iPhone 14 Pro Max) |
| Qwen3-ASR-0.6B | Subtitles | sherpa-onnx (official Android and Kotlin support, ready int8 model) | 879 MB download | easy to medium | download size, about 1 GB of RAM, no language option in sherpa's config |

All licenses allow redistribution with notices (MIT, Apache 2.0, CC BY 4.0 for
VoiceFixer weights). Anything above a few tens of megabytes should be
downloaded on first use.

## Alternatives that give the same feature with less

- Subtitles with Whisper through sherpa-onnx: same library, int8 base about
  160 MB or small about 375 MB, language option and token timestamps,
  multilingual.
- Subtitles with NVIDIA Parakeet TDT 0.6b v3 through sherpa-onnx: 487 MB,
  real per-token timestamps, 25 European languages only.

## Suggested order

1. DeepFilterNet3: smallest, and it sets up ONNX Runtime, JNI and the 48 kHz
   file pipeline the others reuse.
2. Subtitles through sherpa-onnx; decide between Qwen (parity with iOS) and
   Whisper or Parakeet (smaller, real timestamps). The chunking and grouping
   code to port from Swift is the same either way.
3. LavaSR v2, then measure on a mid-range phone.
4. VoiceFixer last, and only after measuring; it is the one to skip.

## Not determined

Phone timings and memory for LavaSR, VoiceFixer and Qwen3-ASR; whether the
ONNX exports hit unsupported operators; whether GPU or NNAPI delegates help;
whether Qwen through sherpa-onnx can be forced to a language.

Since determined for Qwen3-ASR (2026-11-15): it runs through sherpa-onnx 2.0.1 on a
phone sold with 8 GB at 0.5 to 0.6 of real time, takes 2.1 GB of memory, and takes a language
with each piece (the stream option `language`). The download is 987 MB file by file.
See `speech/README.md` and `docs/SUBTITLES-REVIEW.md`, finding 1.
