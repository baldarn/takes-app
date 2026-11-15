# Picture in picture on the device: lanes, decoders, texture size, heat (probe P0)

Findings of 2026-11-15, Media3 1.11.1, with the probe in `probes/layers/`. The probe answers the P0 questions of `docs/PIP-AND-EFFECTS-PLAN.md` (sections 1.4 and 3.5): can the export compose more than two video sequences through a compositor like the app's, how many hardware decoders run beside the encoder, what three players cost the preview, and what a long export does to the phone's heat. Run on the emulator (function only) and on one phone. The app was not changed and not run.

## 1. In short

Measured on the Galaxy A54 (Exynos 1380, Android 16); the Mi 10T was not measured (section 7).

| Question | Galaxy A54 |
|---|---|
| `GL_MAX_TEXTURE_SIZE` | 16383 (emulator 8192) |
| Export with 3 and 4 video sequences plus the clock, black stills on the lanes | completes, every frame of every lane right, 1080p and 4K, H.264 and HEVC, column and grid; no texture-pool fault |
| Export speed, 1 / 2 / 3 / 4 lanes | 1080p 98 / 72 / 60 / 52 fps; 4K 44 / 33 / 26 / 21 fps; grid as fast as column |
| Column against grid past the limit | 4K portrait, 5 lanes: column (2160×19200) fails with `GlException: width or height is greater than GL_MAX_TEXTURE_SIZE 16383`, grid (4320×11520) right on every frame |
| Hardware decoders beside the encoder | 16 at 1080p (all `c2.exynos.h264.decoder`); 8 at 4K, the 9th fails `NO_MEMORY`; with fallback on it opens as `c2.android.avc.decoder` |
| Preview, 3 players, 1080p | 30 fps each, 0 dropped; seek of all three 264 ms median (414 max) paused, 318 (406) playing; 330-353 MB |
| Preview, 3 players, 4K | **only one plays** with ExoPlayer's default real-time decoder (the others `NO_MEMORY`, also with two players); with `KEY_PRIORITY` 1 and no operating rate all three play at 30 fps, 0 dropped, seek 285 / 543 ms median, 641-648 MB |
| 10 minutes, 3 lanes | 1080p: 257 s at a steady 70 fps; 4K: 628 s at a steady 28-30 fps; thermal status 0 throughout, headroom at most 0.74 |

**Proposed limit: 3 pictures at once on the A54** (section 5), with the preview's decoders asked for not in real time. Whether 3 is safe on both phones cannot be said from one.

## 2. Method

**The probe** is a build of its own (`probes/layers/`, application `app.takes.probe.layers`, debug only), like `probes/export/`. It borrows the app's version catalog, so it runs on the Media3 the app resolves. It copies from the app what the export does, not the app's files:

- **The clock**: a first sequence holding one black still for the movie's length at 30 frames a second; Media3 composes one frame per clock frame (`docs/EXPORT-TWO-SEQUENCES.md`, section 5).
- **The lanes**: one video-only sequence per lane, each clip fitted with a `Presentation` of its own; with `-e gaps yes` lanes 1 and up open with a black still of half a second per lane and close with one of a second, fitted like a clip, as the app fills an empty deck.
- **`LanesCompositor`**: the app's `DecksCompositor` for N lanes. The clock is input 0 and not drawn; lane k is laid untouched in a cell of the movie's size, in a column (`-e layout column`, as the app lays two decks) or in a grid two cells wide (`-e layout grid`).
- **`LayersEffect`**: one pass with a layer loop that draws lane 0 over the whole frame and every other lane in a window along the bottom (a third of the frame each, smaller beyond four lanes), back to front, sampling the composed frame. It stands for the shader the plan describes; it has no transitions.
- **`CheckEffect`**: after the layer effect, reads back from every frame the row of blocks at the top of every lane's picture (`glReadPixels` of one line per window row) and compares the frame number it carries with the one due. A lane plays the clip from its second k, so the lanes' numbers differ and a lane showing another's picture, an old frame, a torn frame (unreadable blocks) or black is told apart. The check costs speed; speed is measured without it (`-e check no`).
- **Decoders**: Media3's `DefaultDecoderFactory`, with a listener that logs the name of every codec it opens; `-e decoders software|hardware` restricts the choice, `-e fallback yes` lets it try the next codec on a failure.
- **Preview**: a screen of N `TextureView`s, one above the other (the app's decks are `PlayerView`s with texture views), an `ExoPlayer` on each, all playing the same clip. Measured: the players' own decoder counters (rendered, dropped), `dumpsys gfxinfo` over the same seconds, the seek latency until every player has drawn its first frame after a `seekTo` of all at once, paused and playing, and the process's memory (`dumpsys meminfo`). The screen notes whether it left the front during a measurement (pause or loss of window focus); such a measurement is marked invalid and repeated.
- **Switches added on the phone**: `-e realtime yes` asks the export's decoders for real time (`setCodecPriority(0)`, `setShouldConfigureOperatingRate(true)`); for the preview `-e realtime no` asks ExoPlayer's video decoder for `KEY_PRIORITY` 1 and no operating rate (`Renderers.kt`), and `-e stagger yes` prepares the players one after another; `-e loop 15` makes a lane of the clip's 15 s stretch again and again, for a 4K movie longer than the clip.
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

## 4. The phone: Galaxy A54

Samsung SM-A546B, Exynos 1380 (`s5e8835`), Mali-G68, OpenGL ES 3.2, Android 16 (API 36), 7.8 GB of memory. On the owner's instruction one phone was measured; the Mi 10T was not (section 7).

### 4.1 Texture size and codecs

`GL_MAX_TEXTURE_SIZE` **16383** (viewport and renderbuffer the same). Hardware codecs `c2.exynos.h264.decoder`, `c2.exynos.hevc.decoder`, `c2.exynos.h264.encoder`, `c2.exynos.hevc.encoder`, each stating 16 instances at most. Achievable rates as the device states them: H.264 decoder 411 fps at 1080p, 104 fps at 4K; HEVC decoder 415 and 86; H.264 encoder 29-63 at 1080p and 7-16 at 4K (the measured exports below are faster than that).

### 4.2 Exports with 3 and 4 sequences, every frame checked

Each lane plays its own copy of the clip from a second of its own; lanes 1 and up open with a black still and close with one (`-e gaps yes`), so every lane changes between still and clip twice. 8 s of movie, 240 frames; "right" is the frame due, read back from that lane's window in every composed frame. Media3 picked the hardware decoders and the hardware encoder in every run.

| Footage | Lanes | Layout (composed frame) | Frames | Every lane right on every frame | fps (with the check) |
|---|---|---|---|---|---|
| 1080p H.264 | 3 | column (1920×3240) | 240 of 240 | yes | 60.2 |
| 1080p H.264 | 4 | column (1920×4320) | 240 of 240 | yes | 53.2 |
| 4K H.264 | 3 | column (3840×6480) | 240 of 240 | yes | 26.0 |
| 4K H.264 | 4 | column (3840×8640) | 240 of 240 | yes | 22.3 |
| 4K H.264 | 4 | grid (7680×4320) | 240 of 240 | yes | 22.5 |
| 4K HEVC | 3 | column (3840×6480) | 240 of 240 | yes | 26.2 |
| 4K landscape footage on a 4K **portrait** canvas | 3 | column (2160×11520) | 180 of 180 | yes | 23.8 |
| same | 3 | grid (4320×7680) | 180 of 180 | yes | 23.9 |
| same | 5 | column (2160×19200) | fails at once | — | `GlException: width or height is greater than GL_MAX_TEXTURE_SIZE 16383` |
| same | 5 | grid (4320×11520) | 180 of 180 | yes | 16.5 |

No black frame, no frame ahead or behind, no torn frame: the texture-pool fault of `docs/EXPORT-TWO-SEQUENCES.md` did not show with 3 or 4 sequences and the black stills fitted to the movie's size. The grid costs nothing against the column (22.5 against 22.3 fps, 25.7 against 25.9).

### 4.3 Speed by number of lanes (10 s, 300 frames, no check)

| Lanes | 1080p H.264 | 4K H.264 |
|---|---|---|
| 1 | 97.9 fps | 43.5 fps |
| 2 | 71.9 | 32.9 |
| 3 | 60.5 | 25.9 |
| 4 | 52.1 | 21.1 |
| 3, grid | — | 25.7 |

Reading back every lane of every frame cost nothing measurable (4K, 3 lanes: 26.0 fps with it, 25.9 without). So a 4K movie with three pictures exports at about real time and slower with four; at 1080p four pictures export at 1.7 times real time. Each lane takes about 7 ms a frame at 4K and 3 ms at 1080p.

### 4.4 Decoders at once

4K or 1080p footage drawn into a 1080p movie (so the composed frame stays small), grid, 6 s, beside the hardware encoder:

| Footage | Lanes | Hardware only (`-e decoders hardware`) | fps |
|---|---|---|---|
| 1080p H.264 | 6 | 6 × `c2.exynos.h264.decoder`, completes | 40.8 |
| 1080p H.264 | 8 | completes | 34.6 |
| 1080p H.264 | 12 | completes | 25.0 |
| 1080p H.264 | 16 | 16 × `c2.exynos.h264.decoder`, completes | 18.1 |
| 4K H.264 | 6 | completes | 17.3 |
| 4K H.264 | 8 | 8 × `c2.exynos.h264.decoder`, completes | 13.0 |
| 4K H.264 | 9, 12, 16 | the 9th fails: `ERROR_CODE_DECODER_INIT_FAILED ... name=c2.exynos.h264.decoder (CodecException)`; log `Codec reported err 0xfffffff4/NO_MEMORY ... while in state 5/STARTING` | — |
| 4K H.264 | 10, `-e fallback yes` | 8 hardware, then `c2.android.avc.decoder` for lanes 9 and 10 "after Codec exception", completes | 10.9 |

So the decoder is not what limits the export: eight 4K hardware decoders run beside the encoder, sixteen at 1080p. Without fallback (Media3's default, and the app's) the ninth 4K decoder fails the export; with it Media3 falls to the software decoder quietly. Asking the export's decoders for real time as ExoPlayer does (`setCodecPriority(0)`, `setShouldConfigureOperatingRate(true)`) changed nothing at 4K with three lanes (24.0 fps, three hardware decoders).

### 4.5 Preview: three players at once

Three `TextureView`s one above the other, an `ExoPlayer` on each, the probe's screen in front for every reading (checked by `dumpsys activity` in the same run, and by the screen itself: no reading left the front). 20 s of play, then 10 seeks of all three at once to the same moment, paused and then playing; the latency is until the last of the three has drawn its first frame after the seek.

| Footage | Decoder request | Players that play | Rendered (20 s) | Dropped | Seek, paused: median / max | Seek, playing: median / max | Memory (PSS, of it graphics) |
|---|---|---|---|---|---|---|---|
| 1080p H.264 | ExoPlayer's default | 3 | 601 each, 30.0 fps | 0 | 264 / 414 ms | 318 / 406 ms | 330-353 MB (128 MB) |
| 4K H.264 | ExoPlayer's default | **1** | 601 / 0 / 0 | 0 | none completes | none completes | 412-426 MB (284 MB) |
| 4K HEVC | ExoPlayer's default | **1** | 602 / 0 / 0 | 0 | none completes | none completes | 441-457 MB (320 MB) |
| 4K H.264, two players | ExoPlayer's default | **1** of 2 | | | | | 401 MB |
| 4K H.264, three started one after another | ExoPlayer's default | **1** | | | | | |
| 4K H.264 (10 s, 4 seeks) | `KEY_PRIORITY` 1, no operating rate | 3 | 301-302 each, 30.1 fps | 0 | 285 / 393 ms | 543 / 716 ms | 641-648 MB (463 MB) |

Before the players the process was 98-117 MB. `gfxinfo` over the playing seconds: at 1080p median 6 ms, 99th percentile 11 ms a frame (47.6% counted janky against the 120 Hz display's deadline); three 4K players median 13 ms, 99th 17 ms (77.8% janky), so the screen is drawn at about 60 to 75 a second while the players keep their 30.

**A second 4K player does not start with ExoPlayer's defaults.** ExoPlayer configures its decoder with `priority=0, operating-rate=30.0` (real time). The first 4K decoder opens; the next fails:

```
E MediaCodec: Codec reported err 0xfffffff4/NO_MEMORY, actionCode 0, while in state 5/STARTING
D CCodec  : Component "c2.exynos.h264.decoder" returned error: 0xc
player 0 ERROR ERROR_CODE_DECODER_INIT_FAILED: MediaCodecVideoRenderer error, index=0, format=Format(1, null, video/mp4, video/avc, avc1.640033, 32277278, und, [3840, 2160, 30.0, ...]), format_supported=YES <- DecoderInitializationException: Decoder init failed: c2.exynos.h264.decoder ... <- CodecException
```

The same 4K decoder asked for at `priority=1` without an operating rate (a `MediaCodecVideoRenderer` with `getMediaFormat` and `getCodecOperatingRateV23` overridden, `probes/layers/.../Renderers.kt`) opens three times and plays all three at 30 fps. So the device has decoders enough; it refuses a second *real-time* 4K session. This matters already for today's two-deck preview of 4K footage on this phone, which was not checked in the app.

### 4.6 Ten minutes with three lanes: heat and speed over time

Three lanes, no check, sampled every 10 s. `headroom10s` is `PowerManager.getThermalHeadroom(10)`, the forecast in ten seconds where 1.0 is where severe throttling begins. The 4K movie is made of the clip's 15 s stretch again and again on each lane (`-e loop 15`), so each lane also opened a new decoder every 15 s (40 per lane), as a movie of many clips does.

| | 1080p, 10 min (18000 frames) | 4K, 10 min (18000 frames) |
|---|---|---|
| Took | 257 s, 70.1 fps on average | 628 s, 28.7 fps on average |
| Speed over time (fps over 10 s) | 62.0 in the first 10 s, then 69.8-70.5 to the end | 25.6 in the first 10 s, then 28.3-29.6 to the end, no downward trend |
| Thermal status | 0 (none) throughout | 0 (none) throughout |
| Headroom | 0.54 to 0.68 | 0.63 to 0.74 |
| Temperatures before → after (`dumpsys thermalservice`, °C) | AP 32.5 → 42.2, skin 31.2 → 35.3, battery 28.1 → 28.6 | AP 38.3 → 43.6, skin 33.9 → 37.2, battery 28.9 → 30.5 |
| Movie | 653 MB | 2.6 GB |

The phone was connected for adb; where it lay and the room's temperature were not recorded (the battery read 28-29 °C at the start). Ten minutes of three 4K lanes did not throttle it and did not slow down; the 4K movie took a little longer than its length.

## 5. The proposed lane limit

**Three pictures at once, on this phone, at 1080p and 4K, landscape and portrait; and the preview's players must ask for their decoders not in real time.**

- The export is not the limit. Four lanes at 4K were right on every frame and the decoders held to eight at 4K; the texture limit of 16383 would take a column of four 4K portrait lanes (15360, not run) and took a grid of five. The cost is speed: each lane adds about 7 ms a frame at 4K (44, 33, 26, 21 fps for 1-4 lanes), so three 4K lanes export at about real time and four at 0.7 times, before transitions, titles and effects are added to the frame.
- The preview is the limit, and at 4K it is already below three with ExoPlayer's defaults: a second real-time 4K decoder does not open. With non-real-time decoders three 4K players play at 30 fps with none dropped, seek in 0.3-0.7 s, and the process holds about 650 MB, 460 MB of it graphics. That is the margin left: three 4K players took 3.6 times the graphics memory of three 1080p ones, so a fourth 4K picture would put the preview near a gigabyte on a phone with 7.8 GB.
- So `DECKS` (`C/RenderPlan.kt`) becomes 3 for the A54: two pictures in a dissolve plus one overlay, the case the plan names (section 1.4, "decoders at once"). Four can be offered for 1080p footage on this phone only if wanted; it was not measured in the preview.
- Lay the lanes in a grid two cells wide rather than a column whenever canvas height × lanes exceeds `largestTextureSide()`: it costs nothing measurable and every frame was right in it.
- Whether 3 is safe **on both phones** is not known: only the A54 was measured (section 7).

## 6. Failures seen

| Where | What | Log |
|---|---|---|
| A54, preview | Second and third 4K player do not start with ExoPlayer's default (real-time) decoder; one player of two or three plays | `MediaCodec: Codec reported err 0xfffffff4/NO_MEMORY, actionCode 0, while in state 5/STARTING`; `ERROR_CODE_DECODER_INIT_FAILED: MediaCodecVideoRenderer error ... Decoder init failed: c2.exynos.h264.decoder` |
| A54, export | The ninth 4K hardware decoder | `ERROR_CODE_DECODER_INIT_FAILED: Codec exception: CodecInfo{type=VideoDecoder, ... name=c2.exynos.h264.decoder} (CodecException: )`, same `NO_MEMORY` line; with `setEnableDecoderFallback(true)` the 9th and 10th open as `c2.android.avc.decoder` |
| A54, export | A composed frame above the texture limit (4K portrait, five lanes in a column, 2160×19200) | `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED: Video frame processing error (VideoFrameProcessingException: @UNSET <- GlException: width or height is greater than GL_MAX_TEXTURE_SIZE 16383)` |
| Emulator, export | Four lanes on the goldfish decoder: one lane torn or late, or no frame at all | `ERROR_CODE_MUXING_TIMEOUT: ... no output sample written in the last 25000 milliseconds`; `c2-service-goldfish: decoder function returned error but continuing for this codec`. Not seen with the software decoder or on the phone |
| Emulator, export | Eight lanes in a column (1920×8640) above the limit of 8192 | `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` |

## 7. Not determined, and what the other phone would add

- **The Mi 10T was not measured**, by the owner's choice of one phone. It would add: its texture limit (the plan assumed 8192 might be reported; at 8192 a column of three 4K landscape lanes, 6480, still fits, but a column of three 4K portrait lanes, 11520, does not and needs the grid); whether its Snapdragon decoders refuse a second real-time 4K session as the Exynos does; three players on 6 GB of memory, where 650 MB of preview is a larger share; the preview path below Android 13 (view transforms, no AGSL), which only it runs; and heat on a phone that warms more (its touch fault gets worse when warm).
- The app's own two-deck preview with two 4K clips playing at once on the A54 (a dissolve between two 4K clips) was not run; by section 4.5 the second deck's decoder should fail to open with the app's ExoPlayer defaults.
- Whether real-time priority matters to smoothness: the three non-real-time 4K players rendered every frame in 10 s of plain play, but a longer play, a scrub and the app's own player settings were not tried.
- The cost of the layer shader with transitions (defocus or whip between two of three layers at 4K), titles per clip and effects: the probe's shader only samples and places.
- Sound: the probe's exports carry no sound; the app makes the sound in a pass of its own.
- HDR footage tone-mapped with three lanes; footage of different sizes and frame rates on the lanes; footage from the phone's own camera (bitrates near 50 Mbps at 4K against about 32 Mbps here).
