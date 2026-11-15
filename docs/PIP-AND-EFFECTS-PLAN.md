# Picture in picture and video effects: roadmap after 0.9.1

Written on 15 November 2026 from a reading of both code bases; nothing was built, run or measured for it. The decisions of section 3.4 are open until the owner takes them. Every device number below is unmeasured unless marked otherwise.

**Citation key.** `O/` = `YouRec/` in the original's repository, `OD` = its `docs/EDITOR-TRACKS-DESIGN.md`, `OV` = its `docs/VIDEO-EFFECTS.md`, `T` = `O/TrackTimeline.swift`, `A/` = `app/src/main/kotlin/app/takes/`, `C/` = `core/src/main/kotlin/app/takes/core/`, `PLAN` = `docs/TRACKS-PLAN.md`. Sizes are working days of one worker, estimates.

## 0. In one page

- **The two directions are independent in the saved format.** Effects live on the clip recipe, which exists unchanged in both the sequence and the timeline form. Effects can ship on today's format with a schema bump. Picture in picture needs the saved timeline (S3b).
- **In the original, Move is the main way into an overlap.** Add video, Record here, paste on a picture target and two-camera recording never create one. Picture in picture needs a reduced Move (one scope, drag, Done/Cancel) plus gap drawing and Close gap, not the whole of S10.
- **S4 as a visible stage is not a prerequisite.** Its core and renderer half (transitions by relation, not by join index) is, at 1–2 days.
- **Shortest path to a released picture in picture:** probe → S3a release → S3b → reduced S4 → S9 layers → reduced Move → reduced Placement. About 28–38 days.
- **Shortest path to released effects:** core model → one per-picture GL chain → editor → batch A of 11 single-pass effects. About 16–22 days, in parallel with the above.
- **Two things I found that the plan does not have:**
  1. Titles are drawn per layer, inside the window, in the original; Android draws them over the finished frame. That becomes visibly wrong with a placed window.
  2. Effects in the original run after framing at canvas size. The preview's crop-as-view-transform cannot keep that order, so the preview of an effect on a cropped clip needs a different path.

## 1. Direction 1: picture in picture

### 1.1 How two overlapping pictures arise in the original

| Route | Exists? | Evidence |
|---|---|---|
| **Move mode** | **Yes, the main route.** | `move` shifts the selection by one clamped offset (`T:703-725`). A newly formed overlap puts the moved pictures on the front layers (`promoteNewOverlaps`, `T:728-739`). Editor: `beginMove`, `updateMove`, `finishMove` (`O/EditorView.swift:1162-1239`), button at `:950`. Design: "The moved/inserted video starts in front when it creates a new overlap" (`OD:169-170`). |
| **Handle trim outwards** | Yes, as a by-product. | `extend`: "revealing into an occupied interval creates an editable overlap" (`T:1616-1617`), ending in `promoteNewOverlaps` (`T:1671-1673`). Called from `trimItem` (`O/EditorView.swift:1241-1247`). A UI test screenshot is named "Trim extension creates an editable overlap" (`Tests/EditorUITests.swift:4053`). Our parity table keeps this off until S4 (`docs/TRACKS-PARITY-DECISIONS.md`, row handle-trim-outwards-overlaps). |
| **Paste with no target, of a fragment with no linked picture+sound pair** | Yes, narrow. | `preparePaste` with `target == nil` opens time only when `clipboard.insertsTime` (`O/TimelinePaste.swift:136-142`). `insertsTime` is true only when a link joins a picture and a sound in the fragment (`:106-108`). Otherwise the fragment is laid at the playhead on new front layers (`T:1540-1542`). The editor passes `cuts.first`, nil when the playhead is on no cut (`O/EditorView.swift:793`). Reachable only for an unlinked picture or a still photo, which converts with no sound and no link (`T:514`). Design: "Component Paste overlays/inserts the copied item at the playhead without moving other items" (`OD:137-138`). |
| **Paste onto a "picture" target** | **No.** | `.between` with a picture calls `insertPictureTime`, which splits and pushes the tail (`O/TimelinePaste.swift:163-165`, `:174-194`). `.start` and `.gap` use free picture time, then ripple (`:146-156`). `.end` appends. |
| **Add video panel (import or Record here)** | **No.** | All go through `TimelineEditing.inserting`. Without a site it opens the whole length with `insertTime`. With a site it uses free picture time and ripples the overflow (`O/TimelineEditing.swift:112-121`, `:15-31`). Callers: `O/VideoBatchImport.swift:100-104`, `O/Project.swift:359-363`, `O/ProjectStore.swift:743`. One accidental exception: inserting at a playhead inside a transition leaves the two pieces overlapping, already recorded as "Not adopted" in `docs/TRACKS-PARITY-DECISIONS.md` (row insert-inside-a-transition). |
| **A take recorded later** | No. | Laid at `timeline.duration` on a new layer (`O/Project.swift:255-262`). |
| **Two-camera recording** | **No.** | The two feeds are composited into one picture at capture: "Composite into a bounded pixel-buffer pool… Run live effects after composition" (`docs/RECORDING-GAIN-MULTICAM.md:68`), `DualCameraMasks.composite` (`O/DualCameraMasks.swift:11`). One file, one clip. Its layouts are a capture feature, not timeline Placement. |
| **Unlink** | No, by itself. | Removes the link and `followsVideo` only (`T:575-579`). It makes the paste route above reachable. |
| **Removing a transition** | No, in the editor. | "Remove transition" calls `restoringStraightCut` (`O/TransitionEditor.swift:77`, `O/Transitions.swift:387-395`). `settingTransition(nil)` alone would leave the overlap, but only the paste code uses it that way. |
| **Speed change of a picture** | Unverified. | `replaceVideoContents` treats a concurrent overlay as not downstream (`T:1401-1415`). I did not trace whether lengthening can newly overlap. |

Once an overlap exists, the Placement bubble appears for any stretch with more than one picture that is not just a plain transition pair (`O/VideoTools.swift:612-622`, "Placement · default").

**Smallest honest first way on Android: Move.** The original's own flow is: add the second video (it is inserted and ripples), select it, Move, drag it over the other, Done, then Placement. Any "add as overlay" button would be an invention.

**The part of Move (S10) that picture in picture needs:**

- The "selected" scope only; a linked pair moves together (`T:705`).
- A horizontal drag with a ghost, clamped at zero (`T:707`).
- Done as one Undo step, Cancel (`O/EditorView.swift:1224-1239`).
- The preview reloaded from the draft (`previewMove`, `:1216-1223`).
- Snapping with what `C/TimelineSnapping.kt` and `C/TrackMove.kt:309` already have.

It can leave for the full S10: the other three scopes (`moveChoices`, `T:657-699`), the boundary picker (`placeMove`, `O/EditorView.swift:1185-1198`), the held snap, frame nudges and edge-scroll polish.

**It brings two things from S6.** A clip moved out of the middle leaves black time, so gaps must be drawn on the timeline and a Close gap command offered (`T:1588-1604`; the core port exists from S2).

**It does not need S5.** With at most two pictures at once, the two clip sounds already play at once exactly as in a dissolve: export `voices` on two sequences (`C/RenderPlan.kt:244`), preview sound from the two decks (`A/EditorScreen.kt:228-229`).

### 1.2 Is S4 a prerequisite of S9/S11?

Partly.

- **Needed:** "The frame description takes transitions by relation, not by join index" (`PLAN:511`). Today `TrackRenderPlan.of` returns null for a timeline that is not a sequence (`C/TrackRenderPlan.kt:54`), and `RenderPlan` numbers clips and alternates chains by `% DECKS` (`C/RenderPlan.kt:201-208`, `:280`). A layered timeline needs a plan built from `compile()`, where a transition names its two pictures (`T:989-998`). That is 1–2 days of core, and belongs in S9's core half either way.
- **Not needed:** the visible changes of S4 (trim as the original, N2; range edges inside transitions, N12; grips and badges).

**What breaks if the reduced part is skipped:**

1. A dissolve between two base clips under an overlay is resolved by sequence index and picks the wrong pair.
2. A moved clip with an opening or a close: the wrapper rule N1 ("opening only on the first clip", `C/TimelineEditing.kt:330-337` per `PLAN:317`) has no meaning when "first" is ambiguous between layers. The model keeps it on its picture (`T:881-885`).
3. A fade-in or fade-out of an overlay window is an opening or close on a non-first picture.

Whether the original fades an overlay from transparent or from an opaque fill over the lower picture is unverified. `renderTracks` composites `TransitionRenderer.composite(fill, picture)` over the result (`O/VideoToolCompositor.swift:283-302`), which suggests an opaque fill.

**Recommendation:** do "S4r" (N1 and N13 dropped in the wrappers, relations in the plan and in `C/TransitionScene.kt`), 1–2 days, pure core plus an emulator comparison. Defer the visible S4.

### 1.3 What S3b must contain, and the intermediate release

**Must contain** (`PLAN:499-507`, `:383-414`):

- The step serializer writing `timeline` alone.
- The schema floor.
- Guard 2: revisions of lower schema moved out of `.revisions`, and a pinned manifest.
- `materialized`, `validate`, receipts compared after conversion.
- The decisions deferred to S3b in `docs/TRACKS-PARITY-DECISIONS.md` ("After S2-B"): split on the grid, insert-new-identity, bookmark at the last second, the fit's last digits.

**For picture layers specifically, the saved form must carry:**

- `video[].layer` and `start` in ticks.
- `placements` (participants, one window per picture, `order`, optional `edgeCrop` and `motion`).
- Transitions as relations with `origin`.
- `generated`.

All of these are already types in `C/TrackTimeline.kt` and `C/VideoPlacement.kt` (S1). No new type is needed.

**Later schema numbers:** each visible stage raises the number when a committed timeline uses what earlier releases cannot play (`PLAN:389`). One for "more than one picture at once or a placement" at S9/S11, one for edge crop (the original's 7, `O/Project.swift:318-319`), one for placement motion (its 12, `:320-324`).

**May be trimmed if time presses:** keeping encoded bytes and not re-reading checked files (`PLAN:408`). Only if the speed budgets of `PLAN:677` are met without them.

**Not needed yet:** anything of sound lanes, D3 or D4 beyond "rows by first fit".

**Intermediate release: yes.** 0.9.1 already edits on the track model. S3a is merged but "Not yet run: a phone" (`PLAN:493`). Release S3a alone as 0.9.2, gated on one hardware-encoder export per phone compared with 0.9.1. Going back from it is installing the previous APK (`PLAN:453`, D2). It also separates "the renderer changed" from "the format changed" when a report comes in.

**How the app refuses newer projects today:**

- `validate` refuses a schema above `LATEST_READABLE_SCHEMA = 15` (`C/Project.kt:509`, `:575`).
- A newer head is not treated as damage and no older revision is opened in its place (`C/ProjectDisk.kt:48-50`).
- The message is "This project was saved by a newer version of Takes. Update the app to open it. Its files have been preserved." (`C/Project.kt:24`, `A/ProjectStore.kt:76`).
- That guard is in every release from v0.7 (commit `1d6c2df`). Only 0.6 and earlier have the silent fallback; guard 2 covers those.

### 1.4 The renderer: more than two pictures, and windows

**How the original composes.**

- **Compiler.** Each picture goes on the first free lane (`O/TrackRenderer.swift:81-87`), so tracks number the most simultaneous pictures, not the clips. `compile()` cuts the timeline at every edge and lists, per stretch, the pictures back to front, the placement whose participants equal exactly that set, and the transitions (`T:969-1000`). One instruction is built per stretch (`O/TrackRenderer.swift:140-161`).
- **Compositor**, `renderTracks` (`O/VideoToolCompositor.swift:272-316`). It starts from black and orders by `VideoPlacement.ordered` (`:276`). Then, for each id back to front:
  - a transition pair is mixed once, at its upper member, and composited over (`:283-302`);
  - any other layer is rendered whole at canvas size and, if it has a placement, moved into its window with `motion.pose(at: source time)` applied first, then composited over (`:303-311`).
- **`placed`** crops the source by framing, scales by fit or fill into the window, and clips to the visible window after edge crop (`O/TrackRenderer.swift:317-329`).
- **Captions** go over the whole frame last (`:236`).
- **Titles and images of a layer are drawn inside `render(layer)`, before placement** (`:409-411`), so they shrink with the window.

**What we have that already generalises:**

| Piece | State |
|---|---|
| `TransitionShader` | Takes the number of bands as `uHalves` (`A/TransitionRendering.kt:225-228`). |
| `DecksCompositor` | Takes `decks`, but its anchor formula covers only one or two (`:317`). |
| Sectioned export | Builds one sequence per deck from `drawing.stretch.decks` (`A/ExportMovie.kt:147-156`). Black stills stand in for gaps. Every composite effect is told the movie's own time (`:167-175`). |
| `RenderPlan.fits` | Checks canvas height × sequences against the largest texture (`C/RenderPlan.kt:166`); `largestTextureSide()` exists (`A/TransitionRendering.kt:280`). |
| Core geometry | `PlacementRendering.placed` (`C/VideoPlacement.kt`, per `PLAN:604`), `C/TrackPlacements.kt` (seed, retain, order), `C/EffectAnimation.kt` for motion, `C/TrackCompile.kt` (compile, lanes). |

**What must change:**

| Where | Change |
|---|---|
| `C/RenderPlan.kt` / `C/TrackRenderPlan.kt` | A plan from `compile()`: lanes first-free up to a limit; per stretch the back-to-front order, windows and the transition pair. `DECKS` (`:335`) becomes the device's limit. |
| `A/TransitionRendering.kt` | `DecksCompositor` anchors for N. `TransitionEffect`'s kept-frame arrays and `uHeld0/1` are fixed at two (`:347-349`, `:370`, `:223-224`). A layer loop in the shader: per layer a clip rectangle and a source origin and scale, composited back to front, with the existing `scene()` used for the pair in transition. |
| `A/ExportMovie.kt` | Titles of a clip under a placement move into that clip's own effect list (Media3 `OverlayEffect` works per item). Otherwise a title of a windowed clip is drawn full-frame, unlike the original. About 1–2 days; not in the current plan. |
| `A/TransitionPreview.kt` | `DeckPictures` is written for two (`for (index in 0..1)`, `:166`; layer twice as tall, `:150-154`; `HeldPictures` arrays of two, `:58-60`). Needs N decks. On Android 13 and later, the same layer loop in the AGSL text. Below 13, windows as view transforms plus clip, which is exact for covering and windows; only transitions are approximated there, as today (`:110-113`). |
| `A/EditorScreen.kt` | `EditorPlayer` has `List(2)` decks (`:226-229`): one ExoPlayer per lane. The timeline needs picture rows (model: `O/EditorTimeline.swift:52-73`). |

**Decoders at once.** Export: one per deck, plus the encoder. Preview: one ExoPlayer per lane. One overlay over a base that never dissolves under it needs 2. One overlay over any base needs 3 (two in a dissolve, plus the overlay).

**Texture sizes.** The stacked frame is canvas height × decks.

| Canvas | 2 decks | 3 decks |
|---|---|---|
| 1080p landscape | 2160 | 3240 |
| 4K landscape (2160 lines) | 4320 | 6480 |
| 4K portrait (3840 lines) | 7680 | 11520 |

4K portrait with 3 decks exceeds an 8192 limit, if that is what a phone reports (unverified for either test phone). The way out is a 2×2 grid instead of a column, a small change in the compositor anchors and in the `deck()` lookup.

**To measure on the phones** (`PLAN:617-630`), on both:

| Measurement | Galaxy A54 (Exynos 1380, Android 16) | Mi 10T (Snapdragon 865, Android 12) |
|---|---|---|
| `GL_MAX_TEXTURE_SIZE` | yes | yes |
| 2, 3, 4 hardware decoders plus encoder in one export, at 1080p and 4K: completes? frames per second? | yes | yes |
| 3 ExoPlayers playing and seeking at once at 1080p and 4K: dropped frames, seek latency, memory | yes (AGSL path) | yes (pre-13 view path) |
| A 10-minute export with 3 lanes: thermal status, speed over time | yes | yes |
| Shader cost of defocus or whip between two of three layers at 4K | yes | yes |

The Mi 10T is the only device that exercises the below-13 preview path.

### 1.5 Shortest sequence to a first released picture in picture

| # | Stage | Contains | Days | Pure core, parallel | Emulator | Phone | Gate |
|---|---|---|---|---|---|---|---|
| P0 | Probe (throwaway, `probes/`) | 3 and 4 video sequences plus the clock through `DecksCompositor`; 3 ExoPlayers; largest texture; a sequence of gaps only | 2 | — | yes | **both** | Numbers written into PLAN 5.4; the lane limit per device decided |
| P1 | Release S3a (0.9.2) | Nothing new; the phone half of the S3a gate | 0.5 | — | done | **both**, one export each | Same packets, or the same decoded frames within tolerance, as 0.9.1 |
| P2 | S3b | 1.3 above | 4–6 | migration tests | install-over tests with the 0.9.1 APK | real library; save and open times | `PLAN:507` (bytes, pixels, speed) |
| P3 | S4r | Relations in plan and scene; N1 and N13 dropped | 1–2 | all | exports of the goldens unchanged | no | `Tests/TrackTimelineTests.swift:936-938`; plan equality on goldens |
| P4 | S9, layers | Plan from `compile()` for N lanes; export and preview with N decks, upper covering; picture rows; black gaps drawn; refusal past the limit | 7–10 | plan for N lanes; row geometry | yes (pixel tests for 3 bands) | **both** | `Tests/TrackMediaTests.swift:392-400`; `Tests/TrackTimelineTests.swift:543-555`; measurements recorded |
| P5 | Reduced Move | 1.1 above; Close gap | 3–4 | move-session state type and tests | yes | feel of the drag, one phone | `Tests/TrackTimelineTests.swift:590-599`; Cancel leaves bytes unchanged |
| P6 | Reduced Placement | The bubble on an overlap; presets (cover, side by side, top/bottom, picture in picture); drag and pinch the window; Fit/Fill; front/back; Apply/Cancel/remove; windows in the shader and the preview; titles per layer | 6–8 | little (geometry exists) | pixel tests of windows against `PlacementRendering` | **both** | Persistence half of `placementCropTests` (`Tests/TrackTimelineTests.swift:255-323`) minus crop; preview equals export |
| | **Total** | | **≈ 28–38** | | | | |

Left for after the first release: edge crop with four handles, Reframe inside a window, motion (about 3–5 days together, each with its own schema number); the full S10; the visible S4.

P4 and P5 can be one release or two. "One video covers another" alone is of little use to a person, so I would release P4 to P6 together.

## 2. Direction 2: video effects

### 2.1 The original's 24 effects

Catalogue: `O/VideoEffects/VideoEffectCatalog.swift:193-390`. Shader branches: `O/VideoEffects/VideoEffects.metal:304-421`. Extra passes per effect: `O/VideoEffects/MetalVideoEffects.swift:433-545`.

Common to all:

- Every image effect has `intensity` 0…1, mixing input and output once (`VideoEffectModel.swift:197`, `.metal:420`).
- Every parameter is keyframable in source time unless marked "not animatable" (`VideoEffectModel.swift:108-141`): numbers and colours interpolate, toggles and pickers hold.
- An effect has `start` and `end` in source seconds and an optional A/B `motion` (`:165-180`).
- "Both" means capture and editor (`mode: .both`).

| Effect | What it does; parameters | Kind | Where |
|---|---|---|---|
| Color Correction | exposure, contrast, pivot, shadows, highlights, blacks, whites, temperature, tint, saturation, vibrance, hue (`:209-222`) | per-pixel | both |
| Silent Tint | one dye colour; tint (5), process Tint/Tone, contrast | per-pixel | both |
| Three-Strip | saturation, red depth, warmth; protects skin hue | per-pixel | both |
| Bleach Bypass | strength | per-pixel | both |
| Day for Night | darkness, blue, moonlight; sky and vignette by position | per-pixel with position | both |
| Noir | contrast, black crush, grain toggle, blue shadows | per-pixel; grain needs a per-frame noise texture | both |
| Expressionism | contrast, vignette, iris Oval/Circle, tint | per-pixel with position | both |
| Negative Strobe | cycles per second, limit duration, burst length (last two not animatable); the clock is playback seconds fixed at authoring (`strobeTimeMap`, `phaseOffset`, `VideoEffectModel.swift:186-187`; `OV:264-277`) | per-pixel plus a time rule | both |
| RGB Offset | X and Y per channel, ±12 % of the shorter side | spatial, 3 taps | both |
| Kaleidoscope | divisions 2–9, centre, split angle, source pan, source angle; on-picture guide (`KaleidoscopeGuide.swift`) | geometric map, single pass | both |
| Color Isolation | keep "A color" (hue, range, softness) or "The subject" | hue: per-pixel. New recipes default to algorithm 2, a temporal matte over 6 source frames (`VideoEffectModel.swift:191`; `OV:126-148`). Subject: **person segmentation** | both |
| Crash Zoom | zoom amount, push duration, hold, return, motion blur, target X/Y, follow face (default on) | geometric plus 8-tap radial blur plus a time curve; follow face needs **face tracking** (`VideoEffectSubjectTracking.swift:46-90`) | capture: trigger and tracked target; editor: timed move (`OV:50`) |
| Widescreen 1953 | ratio, bars Black/Blur fill, flares, flare colour, oval bokeh | bars: per-pixel. Flares and blur fill: multi-pass (highlights at quarter size, horizontal blur). Oval bokeh: **depth**, editor only | both |
| Halation | glow, halation red, shadow lift, flash colour | multi-pass: highlights, three blurs | both |
| 16mm | grain, grain size, softness, gate weave, frame edge, colour/B&W | one blur, noise, per-frame jitter | both |
| Super 8 | fade, grain, weave, frame rate 18/24, light leaks, border | blur, noise, cadence hold, time-random leaks | both |
| Quantization | colours 2–16, B&W, palette (from scene, Pop 1–3, saved), smoothing, dithering, face detail, lock palette | two bilateral passes; nearest palette colour in Oklab; **palette analysis** (k-means on 64×36 samples every six frames with a 0.5 s average, `OV:117-120`); face detail uses the person mask | both |
| Night fix | mode Fast/Medium/Precise (not animatable), shadow brightness, noise reduction, preserve detail | Fast: single-frame multi-pass (`nf_*`, `.metal:644-727`). Medium and Precise: 3 or 9 motion-aligned frames, **optical flow** (`NightFix.swift:269`) and caches | editor only |
| Freeze Frame | hold duration (not animatable), push-in, style Clean/Print, audio Cut/Continue/Fade | **structural**: inserts a hold clip (`VideoEffectTiming.swift:66-86`) | editor only |
| Step Printing | step frames 2–6, smear, speed 0.5/1 | **temporal**: blends blocks of neighbouring source frames (`VideoToolCompositor.swift:371-399`) and retimes | editor only |
| Undercranking | frame rate 12/16/18, speed-up 1–1.5, flicker, gate weave, mute | **temporal**: cadence hold plus retime and mute (`VideoEffectTiming.swift:18-60`) | capture: cadence preview; editor: cadence and speed |
| Chroma Key | 26 parameters: screen colour/type/auto, strength, balance, clips, softness, grow, despill, light wrap, person hint, clean plate, view, background (Solid/Image/Video/Blur original/Studio/Window) with framing, blur, brightness, sound, match colour | multi-pass keyer and morphology; temporal matte over 8 frames by default; clean plate is a captured average image; person hint is **segmentation**; a video background is a second decoder and sound in the mix | both |
| Auto Background | softness, grow, light wrap, portrait blur, view, the same backgrounds | **person segmentation** every frame, guided refinement, background estimate | both |
| Dolly Zoom | direction, strength, duration (not animatable), easing, subject X/Y | editor: two-layer simulation from **depth or person mask** plus background fill; capture: drives the real zoom from subject tracking (`OV:105-108`) | both, differently |

Fixed order of families: Key → Camera → Color → Quantization → Texture → Frame, stable within a family (`OV:77-78`; `VideoEffectModel.swift:350-353`).

### 2.2 Difficulty on Android (GLSL inside Media3 effects)

| Class | Effects | Notes |
|---|---|---|
| **Straightforward, one fragment pass** | Color Correction, Silent Tint, Three-Strip, Bleach Bypass, Day for Night, Expressionism, Noir (without grain at first, or with it), Negative Strobe, RGB Offset, Kaleidoscope, Color Isolation "A color" (algorithm 1), Widescreen bars, Crash Zoom with a manual target | Precedent: `TransitionEffect` (`A/TransitionRendering.kt:329`). Color Isolation as algorithm 1 is honest: the original keeps the spatial rendering for recipes that say so (`OV:126-127`). |
| **Multi-pass in one frame** | Halation, 16mm, Super 8, Widescreen flares and blur fill, Quantization smoothing, Night fix Fast, Chroma Key (spatial matte, image/solid/blur backgrounds) | Needs a small framework of ping-pong framebuffers, downsampled targets and a separable Gaussian. Half-float targets, as the original's rgba16Float (`OV:90-91`), need checking per device. |
| **History frames or a retimed clock** | Step Printing, Undercranking, Super 8 cadence, Freeze Frame, temporal mattes (Color Isolation and Chroma Key algorithm 2), Night fix Medium/Precise | Cadence hold is feasible inside a `GlShaderProgram` by keeping the last texture. Retime, mute and holds are the playback map and belong after S3b (`PLAN:650`). Step Printing reads neighbouring source frames out of band (`VideoEffectSampling.swift:101-137`), for which Media3 has nothing ready. Night fix temporal needs optical flow: defer. |
| **CPU analysis before rendering** | Quantization "From scene", B&W thresholds (histogram quantiles, `OV:159-160`), Chroma auto screen | k-means and windows are pure core (`VideoEffectMath.swift:105-163`). Needs a frame reader and a cache per source. |
| **Machine-learning models** | Auto Background, Color Isolation "The subject", Chroma person hint, Dolly Zoom, Quantization face detail (segmentation); Crash Zoom follow face (face detection) | Options in the next list. |
| **Not available on Android** | Widescreen oval bokeh, depth Dolly | Apple Cinematic depth tracks (`VideoEffectDepth.swift:65-79`). |

**Honest options for the model-dependent effects:**

1. **None for now.** The parameter is hidden with the reason, as the original does for missing depth ("Depth-only options explain missing depth", `OV:177`).
2. **ML Kit Selfie Segmentation.** Bundled, a few MB, real-time, closed source, mask quality below Apple's "accurate" mode. Sizes are from memory, unverified.
3. **An open segmentation model on the ONNX Runtime the app already ships for speech** (through sherpa-onnx, `README.md:97`, `:140-141`). Keeps the app MIT and Google-free. Needs a model choice, a licence check and speed measurements.

For either real option the mask must be computed once per source and cached, as the original's editor does ("accurate editor analysis… Cache by source, algorithm version and parameters", `OV:121-124`). The export must not depend on real-time inference.

Recommendation: option 1 now; option 3 as a later stage with a download on first use, like the speech models.

### 2.3 The rendering approach

**Order of operations in the original**, verified in `O/VideoToolCompositor.swift`. Per layer:

1. Night fix on the raw frame (`:324`).
2. Colour grade or LUT (`:143-152`).
3. Stabilisation, crop, mirror, fit to the canvas over black (`:153-162`).
4. The effect chain, at canvas size (`:361`).
5. Titles and images (`:409-411`).

Then across layers: transition mix (`:283-302`), placement window (`:303-311`), captions (`:236`).

So effects see the framed, canvas-shaped picture including any bars. They come before titles, transitions and windows.

**Where that is on Android.** At the end of each item's own effect list in `ExportMovie.piece`: `SingleColorLut` → `FramingEffect` → `fit()` → **effect chain** (`A/ExportMovie.kt:113-121`). Titles stay after it. The decks, transitions and later windows are untouched: the chain is a per-picture stage, the composite a separate one, as in the original.

**One pipeline for preview and export — recommended.**

- Put the chain in one `GlEffect` (new `A/EffectRendering.kt`).
- Give each preview deck the same per-item list (LUT, framing, fit, chain) through `ExoPlayer.setVideoEffects`.
- The deck then shows the canvas-shaped picture exactly as an export item does.
- This needs the crop view transform (`A/TransitionPreview.kt:121-137`) to be dropped for decks that run the chain. Otherwise a vignette, bars or a kaleidoscope centre would follow the source and not the frame.
- It works on every Android version, so effects need no below-13 fallback, and it would also give the colour preview to Android 8–12, which lacks it today (`docs/PARITY.md:190`).
- **Unverified:**
  - whether `setVideoEffects` gives usable times and seek latency with two (later N) players;
  - what `presentationTimeUs` an effect sees in a player;
  - whether `TextureView.bitmap` still gives held frames (`A/TransitionPreview.kt:69`).
- A 1–2 day probe decides.

**Probe result (emulator, Media3 1.11.1): the fallback.** Two players with `setVideoEffects` both ran, and a seek with an effect took as long as one without (about 0.1–0.4 s on the software renderer), but the `presentationTimeUs` an effect sees is the playlist position only for a run that began at its first item: after a seek into another item it is shifted by the offset Media3 gives the new period (+1.0 s and +2.0 s seen for the same item, from the start of the first stream), so one effect on a deck's playlist cannot tell which clip a frame belongs to, nor its source time. Per-item lists in the preview would need a deck per clip or `CompositionPlayer`; the preview therefore runs the chain as AGSL on each deck on Android 13 and later, from the same shader body as the export's GLSL, and held frames are untouched.

**Fallback if the probe fails.** An AGSL chain as `RenderEffect` on each deck, on 13 and later only, sharing the shader body text as `TransitionShader.BODY` does (`A/TransitionRendering.kt:125-255`). That means no effect preview below 13 (the export is still right, with a notice), multi-pass effects much harder (one input per `RenderEffect`), and a wrong order relative to crop.

**Parameters per frame.**

- Core gets a pure function `EffectFrame.of(clip, sourceTime, movieFrame)` returning the uniform block for each active effect, in pipeline order. It applies keyframes, `motion` and `isActive` (`VideoEffectModel.swift:122-141`, `:176-178`, `:269-275`). The uniform packing is the original's (`MetalVideoEffects.swift:268-291`).
- The GL program calls it in `drawFrame` with the source time derived as `FramingEffect` does today: `presentationTimeUs / 1e6 − begins`, then `clip.sourceTime(offset)` (`A/Exporter.kt:198-209`, `C/Project.kt:245`).
- Under the sectioned export an item's effects get deck time with the section's origin subtracted (`A/ExportMovie.kt:119`, `:153`), so a section computes the same values as the whole movie.

**Presentation-clocked terms.** Grain seed, strobe polarity, flicker, weave and light leaks use the presentation frame in the original ("Temporal shader noise uses integer presentation-frame seeds; freezes animate Print grain", `OV:110-112`). An item effect sees the deck's frame times, not the movie's snapped grid (the compositor pairs each clock frame with the nearest deck frame, `A/ExportMovie.kt:62-67`). With footage slower than the movie's rate, one deck frame serves two movie frames and its grain would repeat. Two ways:

1. Accept it and derive the frame index as `round(movieTime × rate)` from the deck time.
2. Apply only the presentation-clocked terms in the composite stage, where the movie time is exact.

Start with the first. A pixel test (24 fps footage in a 30 fps movie with grain) shows whether the second is needed.

**Noise.** The original's hash uses unsigned integer operations (`.metal:33-34`, `ve_grainNoise` `:554`), which GLSL ES 1.00 does not have. Either the chain runs in an ES 3 context (unverified for Media3's SDR path), or the noise lattice is generated in core and uploaded as a small texture per frame, which makes it bit-exact against core and testable.

**Pixel tests.**

- The harness exists: `Bench`, a real GL context off screen (`app/src/androidTest/kotlin/app/takes/pixels/ShaderStage.kt`), with `PixelRecipes.kt` and `TransitionPixelCases.kt`.
- For each effect, core holds the reference formula in Kotlin (a port of the `.metal` branch, with the constants).
- The test draws a synthetic input (ramps, primaries, three skin tones) through the GL program at three parameter sets, plus intensity 0 (identity, `OV:80-81`) and 0.5, reads back, and compares with the core value per pixel within a tolerance for 8-bit and mediump.
- Multi-pass effects compare against a core separable blur on a small image.
- Preview against export is one more case per effect: the same frame from a deck and from an export.

### 2.4 Where effects are stored, and what that means for the format

- **Per clip.** `TimelineClip.videoEffects: [VideoEffectSettings]?` (`O/Project.swift:58`). Each has `start` and `end` in source seconds and keyframes in source time: "Source-time values survive trims, pauses, speed changes and clip splits" (`VideoEffectModel.swift:3`). A split copies the list to both pieces. A range is clipped to the clip when shown (`O/VideoTools.swift:582-585`).
- **Per source**, for takes recorded with effects: `SourceMedia.videoEffects` (`O/Project.swift:35`), copied onto the new clip (`:346`).
- **Freeze Frame** is a clip of its own with `frozenFrame` (`O/Project.swift:59`).
- Analysis and palette files are project-local, named by the effect (`VideoEffectModel.swift:167-171`).

**Consequence.** The clip recipe is the same object in our sequence form and as `content` of a picture in the timeline form (`PLAN:232`). So effects ship on the present sequence format with one new nullable field on `TimelineClip` and one schema number, set through `TimelineClip.schema` (`C/Project.kt:249-262`). Nothing needs converting when S3b lands. The two directions are independent.

**One collision.** `TrackTimeline.SCHEMA = 16` is reserved (`C/TrackTimeline.kt:407`). Whichever ships first takes 16 and the other 17. They must not merge concurrently (the merge-order caution, `PLAN:387`).

**Need S3b:** Freeze Frame, Step Printing, Undercranking (holds and changing rates are the playback map, `PLAN:650`). **Need S5:** background video sound (`O/TrackRenderer.swift:308-312`).

### 2.5 Order, in releasable batches

| Batch | Contents | Days | Core first (parallel) | Emulator | Phone |
|---|---|---|---|---|---|
| **E0** core | #77 colour conversions (0.5); #78 values, keyframes, catalogue, order, activity, visibility rules (3–4); reference formulas of batch A (2–3) | 6–8 | all of it; three workers on new files | no | no |
| **E1** pipeline | Probe (1–2); `EffectRendering.kt` chain; the clip field and schema; `RenderPlan.Picture` carries effects; navigation stops; pixel harness for effects | 5–7 | plan and format pins | yes | both: speed at 1080p and 4K, Android 12 preview |
| **E2** editor | Tools → Video effects: library in four sections (`VideoEffectsEditor.swift:81-152`, sections at `VideoEffectCatalog.swift:44-67`), applied list (`:174-208`), inspector from the parameter kinds, enable/disable, compare with original, help texts, scope by range or selection | 5–7 | — | yes | feel, one phone |
| **A** (first release) | Color Correction, Silent Tint, Three-Strip, Bleach Bypass, Day for Night, Noir, Expressionism, Color Isolation "A color", RGB Offset (sliders first, pads `RGBOffsetPads.swift` after), Negative Strobe, Kaleidoscope (sliders; guide after) | in E0–E2, plus 2 | formulas | pixel tests | both GPUs |
| **B** | Keyframes UI (`VideoEffectsEditor.swift:535-560`, `:1209-1336`), 3 days; multi-pass framework, 3 days; Halation, 16mm, Super 8 (without cadence), Widescreen (bars, flares, blur fill), Crash Zoom (manual target), Kaleidoscope guide, RGB pads | 10–13 | blur and cadence maths | yes | both: multi-pass at 4K, heat |
| **C** (after S3b) | Freeze Frame, Undercranking, Super 8 cadence, Step Printing; Quantization with palette analysis; Chroma Key (spatial matte; solid, image, blur backgrounds; clean plate from a photo); Night fix Fast | 15–20 | timing spans (`C/PlaybackSpan.kt` exists), k-means, window maths | yes | both |

**Deferred, with the reason:**

| Effect or option | Reason |
|---|---|
| Auto Background, Color Isolation "The subject", Chroma person hint, Dolly Zoom, Quantization face detail | Segmentation model (decision V3) |
| Crash Zoom follow face | Face detection |
| Night fix Medium/Precise | Optical flow |
| Temporal mattes (algorithm 2) | History frames |
| Video backgrounds with sound | S5 |
| Oval bokeh, depth Dolly | Not available on Android |

### 2.6 Effects while recording

**What the original does.** The raw take is never altered. The recorder shows the effect on the live picture and saves the recipe beside the take, which is attached to the new clip (`OV:68-70`; `O/Project.swift:346`). On top of that:

- live analysis sidecars (masks, palettes, accepted-frame times, `OV:145-146`);
- real camera moves for Dolly and Crash (`OV:105-108`);
- a budget that switches to a recorded lighter configuration (`OV:83-86`; `VideoEffectMath.swift:295`);
- its own panel (`LiveEffectsPanel.swift`, `LiveVideoEffects.swift`, about 1,300 lines).

**On Android.** The recorder is CameraX with a `PreviewView` (`A/RecorderScreen.kt:168`, `A/CaptureController.kt:89`, `:437`). A live look needs a `CameraEffect` on the preview stream running the same GLSL chain, the recipe stored on the source, and the panel. About 8–12 days for batch A looks only. Phone required for everything (latency, heat, dropped frames while recording).

**Recommendation: defer** until batches A and B are released. The person loses nothing irreversible: the same look can be applied in the editor to the untouched take. `docs/PARITY.md:124` already lists it as dependent on this area.

## 3. Both together

### 3.1 Files and collisions

| File | Picture in picture | Effects | Collision |
|---|---|---|---|
| `C/Project.kt` | S3b: `EditState`, commit, schema | clip field, `schema` | **Yes.** Sequence them: the effects format first (small), then S3b. |
| `C/RenderPlan.kt`, `C/TrackRenderPlan.kt` | lanes, order, windows | `Picture` gains effects | Small; effects add a field only. |
| `C/TransitionScene.kt`, `C/TrackPlacements.kt`, `C/TrackMove.kt`, `C/ProjectDisk.kt` | yes | no | — |
| New `C/VideoEffect*.kt` | no | yes | — |
| `A/ExportMovie.kt` | sequences for N decks, titles per layer | one entry in `piece`'s list (`:113-121`) | Small, different functions. |
| `A/TransitionRendering.kt` | compositor, layer loop | no (new `A/EffectRendering.kt`) | None, if the chain stays per-picture. |
| `A/TransitionPreview.kt` | N decks, windows | deck renders through the chain; crop transform dropped | **Yes**, `DeckPictures`. |
| `A/EditorScreen.kt` (2,136 lines) | `EditorPlayer` (`:226`), rows, Move, Placement bubble | tool entry, preview wiring | **Yes, the main one.** |
| `app/src/androidTest/.../pixels/` | new cases | new cases | Files apart; the emulator is shared. |

**Before the lanes start (1 day, no behaviour change):** move `EditorPlayer` and the timeline drawing out of `EditorScreen.kt` into files of their own, and agree the per-picture seam: "a deck item's effect list is built by one function, used by export and preview".

### 3.2 Lanes

| Lane | Owns | Exclusive resource |
|---|---|---|
| **1. Core** (one or two workers) | E0, then S3b migration tests, the plan from `compile()`, the move-session type | host only |
| **2. Effects renderer and UI** | `EffectRendering.kt`, `EffectsPanel.kt`, the per-picture seam | emulator A; the **Mi 10T** (Android 12 exercises the below-13 preview) |
| **3. Layers renderer and UI** | `TransitionRendering.kt`, `TransitionPreview.kt` composite, `EditorPlayer`, rows, Move, Placement | emulator B; the **Galaxy A54** (4K camera footage, Android 16) |

Cross-checks on the other phone are booked at each gate, not held continuously. `C/Project.kt` has one owner at a time (lane 1).

### 3.3 Proposed versions

| Version | The person sees | Verified by | Phone |
|---|---|---|---|
| **0.9.2** | Nothing new (S3a) | Host plan equality; emulator packet equality (done, `PLAN:493`); one hardware export per phone against 0.9.1 | both, 1 hour |
| **0.10** | **Video effects**: 11 looks, the effects tool, compare, help. Schema 16. | Core formulas against the original's constants; pixel tests on the emulator and on both phone GPUs; preview-equals-export frames; export speed with a three-effect chain | both |
| **0.11** | Effects: keyframes, film textures, widescreen, crash zoom. Under the hood: **the saved timeline** (schema 17), no visible change from it. | Batch B pixel tests; the S3b gate (bytes, install-over with 0.10 and 0.6, a real library, save and open budgets) | both |
| **0.12** | **Picture in picture**: clips can be moved over each other, picture rows, gaps and Close gap, Placement with presets, drag and pinch, order | P3–P6 gates; the measurements of 5.4; preview-equals-export for windows | both, required |
| **0.13** | Placement complete (edge crop, reframe, motion); full Move; time effects, Quantization, Chroma Key | Remaining `placementCropTests`; `OD:421-425`; batch C tests | both |
| later | Sound lanes (S5…), visible S4, model-based effects, effects while recording | | |

If picture in picture must come before any effect, swap: 0.10 = S3b alone, 0.11 = picture in picture. Effects then follow on schema 17 or later, with no rework.

### 3.4 Decisions for the owner

| | Question | Recommendation |
|---|---|---|
| **V1** | After S3b an edited project, and its ZIP, cannot be opened by an earlier version. Accept? (PLAN D8.) | Yes. From 0.7 on, older versions refuse with a clear message and touch nothing (`C/ProjectDisk.kt:48-50`); 0.6 and earlier are covered by the pinned manifest. Until the first edit nothing on disk changes (`PLAN:401`). |
| **V2** | Release S3a alone first (0.9.2)? | Yes. |
| **V3** | Which reaches people first, effects or the format change? | Effects (0.10). They are independent, smaller, and need no migration. |
| **V4** | Schema numbers: effects 16 and timeline 17? | Yes, by release order. Change `TrackTimeline.SCHEMA` when decided. |
| **V5** | The first way to create an overlap. | A reduced Move, as the original; no "add as overlay" button. Trim-outwards overlap stays off until the visible S4. |
| **V6** | Do S4 before layers? | Only its core and renderer half (S4r). Visible S4 later. |
| **V7** | How many pictures at once? | What P0 measures per device; at least 2. Aim for 3 so an overlay can lie over a dissolve. An edit past the limit is refused with the reason; the saved project never depends on the device (`PLAN:630`). |
| **V8** | First Placement without edge crop, reframe and motion? | Yes; they follow in the next version, each with its schema number. |
| **V9** | Titles of a clip in a window: inside the window (the original) or over the whole frame (today)? | The original's, for clips under a placement. A full-frame title belongs on the lower clip. |
| **V10** | Effect preview through the export's own GL chain in the players (all Android versions), or AGSL on 13 and later only? | The former, if the probe passes. Otherwise AGSL with a notice below 13. |
| **V11** | Color Isolation and Chroma Key as the original's "legacy spatial" algorithm first? | Yes; the temporal matte is added later under the same recipe version field (`algorithm`, `VideoEffectModel.swift:173`). |
| **V12** | Effects that need a person mask: none, ML Kit (closed, bundled), or an open model on the ONNX Runtime already shipped (downloaded on first use, like the speech models)? | None in 0.10–0.13, with the option hidden and explained. Then the open model, after a speed and quality probe. Size to be established. |
| **V13** | Effects while recording? | Defer until batches A and B are out. |
| **V14** | Night fix Medium/Precise, oval bokeh, depth Dolly. | Not planned. The last two are not available on Android. |

### 3.5 Largest unknowns, and the cheap probe for each

| Unknown | Probe |
|---|---|
| Media3 export with 3 or more video sequences through our compositor (`PLAN:696`), and the texture-pool fault of `docs/EXPORT-TWO-SEQUENCES.md` with N decks | P0: a throwaway export of three test clips plus the clock, on the emulator and both phones |
| Decoders at once, with heat | P0: open N at 1080p and 4K while encoding; a 10-minute run reading thermal status |
| `GL_MAX_TEXTURE_SIZE` on both phones | One call to `largestTextureSide()` (`A/TransitionRendering.kt:280`) |
| `ExoPlayer.setVideoEffects` on the decks: times seen by an effect, seek latency, held frames, two or three players at once | 1–2 days: one deck with LUT, framing and a tint effect, scrubbed and played on both phones |
| GL context version and half-float targets inside Media3's SDR pipeline (integer noise, multi-pass precision) | Half a day: query the version and extensions inside a `GlShaderProgram`; draw a ramp through an RGBA8 and an RGBA16F target and compare banding |
| Grain and strobe when one deck frame serves two movie frames | One pixel case: 24 fps footage, 30 fps movie, grain on; count distinct noise fields |
| Three ExoPlayers in the preview: memory and seek on a 6 GB phone | P0, on the Mi 10T |
| Whether real libraries hold steps the timeline refuses (`PLAN:699`) | A debug build that converts and validates every step of every project on the owner's phones and reports counts only |
| The original's fade of an overlay (opaque fill or transparent) | Read `TransitionRenderer.fill` and `composite`; one hour, not done here |
| A segmentation model's speed and mask quality on the two phones | Later: run a candidate over 10 s of 1080p footage, time it, look at hair edges |
| Sizes of ML Kit and of open segmentation models | Not checked; look up before V12 |

**Not verified in source here:** Media3 internals for N sequences; `setVideoEffects` behaviour; AGSL uniform arrays; `GL_MAX_TEXTURE_SIZE` of either phone; whether Android has photo import (which would make the paste-overlay route reachable); the speed-change route to an overlap; the original's tests (read, not run).
