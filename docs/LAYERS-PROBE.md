# Picture in picture on the device: lanes, decoders, texture size, heat (probe P0)

Findings of 2026-11-15, Media3 1.11.1, with the probe in `probes/layers/`. The probe answers the P0 questions of `docs/PIP-AND-EFFECTS-PLAN.md` (sections 1.4 and 3.5): can the export compose more than two video sequences through a compositor like the app's, how many hardware decoders run beside the encoder, what three players cost the preview, and what a long export does to the phone's heat. Run on the emulator (function only) and on one phone. The app was not changed and not run.

## 1. In short

PHONE RESULTS PENDING.

## 2. Method

**The probe** is a build of its own (`probes/layers/`, application `app.takes.probe.layers`, debug only), like `probes/export/`. It borrows the app's version catalog, so it runs on the Media3 the app resolves. It copies from the app what the export does, not the app's files:

- **The clock**: a first sequence holding one black still for the movie's length at 30 frames a second; Media3 composes one frame per clock frame (`docs/EXPORT-TWO-SEQUENCES.md`, section 5).
- **The lanes**: one video-only sequence per lane, each clip fitted with a `Presentation` of its own; with `-e gaps yes` lanes 1 and up open with a black still of half a second per lane and close with one of a second, fitted like a clip, as the app fills an empty deck.
- **`LanesCompositor`**: the app's `DecksCompositor` for N lanes. The clock is input 0 and not drawn; lane k is laid untouched in a cell of the movie's size, in a column (`-e layout column`, as the app lays two decks) or in a grid two cells wide (`-e layout grid`).
- **`LayersEffect`**: one pass with a layer loop that draws lane 0 over the whole frame and every other lane in a window along the bottom (a third of the frame each, smaller beyond four lanes), back to front, sampling the composed frame. It stands for the shader the plan describes; it has no transitions.
- **`CheckEffect`**: after the layer effect, reads back from every frame the row of blocks at the top of every lane's picture (`glReadPixels` of one line per window row) and compares the frame number it carries with the one due. A lane plays the clip from its second k, so the lanes' numbers differ and a lane showing another's picture, an old frame, a torn frame (unreadable blocks) or black is told apart. The check costs speed; speed is measured without it (`-e check no`).
- **Decoders**: Media3's `DefaultDecoderFactory`, with a listener that logs the name of every codec it opens; `-e decoders software|hardware` restricts the choice, `-e fallback yes` lets it try the next codec on a failure.
- **Preview**: a screen of N `TextureView`s, one above the other (the app's decks are `PlayerView`s with texture views), an `ExoPlayer` on each, all playing the same clip. Measured: the players' own decoder counters (rendered, dropped), `dumpsys gfxinfo` over the same seconds, the seek latency until every player has drawn its first frame after a `seekTo` of all at once, paused and playing, and the process's memory (`dumpsys meminfo`). The screen notes whether it left the front during a measurement (pause or loss of window focus); such a measurement is marked invalid and repeated.
- **Heat**: `PowerManager.currentThermalStatus` and `getThermalHeadroom(10)`, the battery temperature, and the temperatures of `dumpsys thermalservice` before and after; frames done every 10 s during the export.

**Footage** (`probes/layers/tools/make-clips.sh`): ffmpeg's moving test pattern at 1920×1080 and 3840×2160, H.264 and HEVC, 30 fps, a key frame every second and no B-frames (as a phone records), a tone for sound; 20 s, and 10 min 10 s at 1080p H.264 for the heat run. The top twelfth of every frame is a row of 20 blocks with its number; the number is also written large in the middle.

```
probes/layers/tools/make-clips.sh <folder> long
./gradlew -p probes/layers :probe:assembleDebug
adb install probes/layers/probe/build/outputs/apk/debug/probe-debug.apk
adb exec-in run-as app.takes.probe.layers sh -c 'mkdir -p files/in; cat > files/in/F1080-h264.mp4' < F1080-h264.mp4
adb shell am instrument -w -e scenario info app.takes.probe.layers/.LayersProbe
adb shell am instrument -w -e scenario export -e lanes 3 -e clip F2160-h264 -e seconds 10 -e layout grid -e check no app.takes.probe.layers/.LayersProbe
adb shell am instrument -w -e scenario preview -e players 3 -e clip F1080-h264 app.takes.probe.layers/.LayersProbe
```

## 3. The emulator (function only)

Android 17 (API 37, x86_64), SwiftShader GL, `GL_MAX_TEXTURE_SIZE` 8192; decoders `c2.goldfish.h264.decoder` (host-backed, listed as hardware) and `c2.android.avc.decoder`; encoder `c2.android.avc.encoder`, at most 2048 wide, so no 4K export. Speeds here say nothing about a phone.

Exports at 1080p, every frame checked (`-e check yes`), a few seconds each:

| Lanes | Layout (composed frame) | Gaps | Decoders | Result |
|---|---|---|---|---|
| 2 | column (1920×2160) | yes | goldfish | 120 of 120 frames, every lane right on every frame |
| 3 | column (1920×3240) | no | goldfish | 300 of 300, all right (10 s, 6.1 fps) |
| 3 | column | yes | goldfish | 120 of 120, all right |
| 3 | grid (3840×2160) | no | goldfish | 120 of 120, all right |
| 4 | column (1920×4320) | no | goldfish | completed, but lane 2 wrong on 87 of 120 frames: 79 torn (blocks unreadable), 8 showing a frame 1 to 20 behind; another run stopped with no frame at all (`ERROR_CODE_MUXING_TIMEOUT`) |
| 4 | column | yes | goldfish | completed, lane 2 wrong on 19 of 120 from frame 32, just after its still (10 torn, 9 behind by 1 to 7) |
| 4 | grid (3840×2160) | no | goldfish | completed, lane 2 wrong on 53 of 120 (15 torn, 38 behind by 1 to 22 frames) |
| 4 | grid | yes | goldfish | lane 1 wrong from frame 15 (where its clip follows its still), then `ERROR_CODE_MUXING_TIMEOUT` after 104 frames |
| 4 | column | no | software (`c2.android.avc.decoder`) | twice 120 of 120, all right |
| 4 | grid | yes | software | 90 of 90, all right |
| 8 | grid (3840×4320) | no | software | 90 of 90, all eight lanes right |
| 8 | column (1920×8640, over the 8192 limit) | no | software | fails at once: `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` after 3.9 s |

The faults with four lanes went with the emulator's decoder, not with Media3: the same compositions with the software decoder were right on every frame, and the log had `c2-service-goldfish: decoder function returned error but continuing for this codec` for the fourth instance. The goldfish decoder handles three at once. So on the emulator nothing of `docs/EXPORT-TWO-SEQUENCES.md` fault B showed with three or more lanes and black stills: Media3 1.11.1 composes eight sequences plus the clock, and the stills fitted to the movie's size keep the texture pools whole.

A composed frame taller than the texture limit fails the export at its first frame; the grid two cells wide stays under it and is drawn as right as the column.

Preview, three players on the emulator: at 1080p all three played (29, 26 and 17 fps rendered, 15 to 140 frames dropped in 10 s; gfxinfo 20% janky); paused seeks of all three took 112 to 455 ms (median 266), seeks while playing 281 to 777 ms (median 621); the process 157 to 175 MB PSS. At 4K two players rendered 7 fps and the third none, and no seek got all three to a frame within 5 s: the emulator's decoders, again.

## 4. The phone

PENDING

## 5. The proposed lane limit

PENDING

## 6. Failures seen

PENDING

## 7. Not determined, and what the other phone would add

PENDING
