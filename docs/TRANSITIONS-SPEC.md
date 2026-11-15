# Transitions: specification for the Android renderer

Source of truth: the original iOS app, YouRec (its own repository, read only). Paths below are relative to it unless they start with `core/` or `app/`, which are in the Android repository. Line numbers are those of the checkout read on 2026-11-15.

Main original files:

| File | What it holds |
|---|---|
| `YouRec/Transitions.swift` | Styles, settings, validation, `TransitionTiming` (picture and sound timing, progress), layout, fitting, `settingTransition` |
| `YouRec/TransitionRenderer.swift` | `composite(first, second, settings, progress, canvas, duration, framesPerSecond)`: the per-frame picture of every style (l. 189-267), split geometry (l. 276-292), blur downscale (l. 268-275) |
| `YouRec/VideoToolCompositor.swift` | Calls `composite` per frame (l. 237-256 sequence path, l. 272-317 track path); Core Image context (l. 194) |
| `YouRec/TrackRenderer.swift` | Live timeline builder: frozen frame for Still (l. 75-121), sound ramps (l. 271-294) |
| `YouRec/CutAudioEnvelope.swift` | Gain at a time, including transition ramps (l. 30-55) |
| `YouRec/TransitionEditor.swift` | The editor sheet: controls, ranges, defaults, labels (l. 267-432) |
| `Tests/TransitionTests.swift` | Numbers pinned by the original's tests |

Conventions used in this document:

- **A** is the outgoing ("first") frame, **B** the incoming ("second") frame, both already drawn at canvas size with their own colour, crop, mirror and titles.
- Canvas is `W × H` pixels. The original's canvas coordinates are **bottom-up** (origin bottom left, y upward). A top-down renderer flips y (`H − y`). Angles and offsets are restated in screen terms where it matters.
- `clamp01(x) = min(1, max(0, x))`. `mix(X, Y, s) = X·(1 − s) + Y·s` per channel.
- All mixes happen in **linear light**: the Core Image context works in extended linear sRGB, half float (`VideoToolCompositor.swift:194`). A renderer that blends gamma-encoded pixels gets darker midpoints in dissolves and softer wipe edges.

---

## 1. Styles at a glance

Order is the order of the style picker (`TransitionStyle.allCases`, `Transitions.swift:6-9`, grid in `TransitionEditor.swift:417-431`). Labels are `title` (`Transitions.swift:11`).

| # | Style (saved name) | UI label | SF symbol | Own parameters | Hold | Kotlin core | Android renderer today |
|---|---|---|---|---|---|---|---|
| 1 | `dissolve` | Dissolve | square.on.square | none | yes | timing yes; mix = `eased()` only, no dedicated function | Drawn, but **linear** (not eased), different clamping, no hold, no custom sound |
| 2 | `fadeBlack` (= fadeColor) | Fade through color | circle.lefthalf.filled | `fadeColor` | yes ("Hold on color") | timing yes; **no mix function** | Drawn to **black only**, no colour, no hold |
| 3 | `wipe` | Wipe | rectangle.lefthalf.inset.filled | `wipeAngle`, `hardEdge` (`axis`, `reverse` feed the angle) | yes | `TransitionGeometry.wipe` matches | Not drawn |
| 4 | `split` | Split screen | rectangle.split.2x1 | `axis`, `divider`, `first`, `second` framing, `audio` | yes | `splitRects`, `splitLine`, `splitMix`, `framed` match | Not drawn |
| 5 | `hitchcock` | Hitchcock | circle.lefthalf.striped.horizontal | `highlightsFirst` | yes | `hitchcock` matches | Not drawn |
| 6 | `whip` | Whip pan | wind | `axis`, `reverse` | yes | `whip`, `blurScale` match | Not drawn |
| 7 | `flashCut` | Flash Cut | bolt.fill | `flashCount`, `flashAcceleration` | no | `flashFrames`, `flashShowsSecond` match | Not drawn |
| 8 | `still` | still (lower case in the original) | pause.rectangle | `stillDissolve` (`duration` is the freeze length) | no | timing and progress match; frozen-frame time **missing** | Not drawn |
| 9 | `defocus` | Defocus | camera.aperture | `blurAmount` | yes | `defocus`, `blurScale` match | Not drawn |

Common to every style: `duration`, `hold` (where supported), `audioOffset`, `audioBlend`. Every join can also be an **opening** (before the first clip, edge −1) or a **closing** (after the last clip, edge +1) transition, where the missing side is a solid fill.

"Kotlin core" refers to `core/src/main/kotlin/app/takes/core/TransitionSettings.kt` and `TransitionGeometry.kt`. **Neither is called by the app**: no file under `app/` or elsewhere in `core/src/main` references `TransitionSettings`, `TransitionTiming`, `TransitionStyle` or `TransitionGeometry`. The app uses the older `ClipTransition` in `core/.../Project.kt:59-79` (two styles) and `TimelineEditing.overlap/fadeLength/weight/level/soundLevel` (`core/.../TimelineEditing.kt:189-223, 306-311`). See sections 16 and 17.

---

## 2. Settings: fields, defaults, ranges

`ClipTransition`, `Transitions.swift:39-93`. Validation is `valid`, l. 83-92.

| Field | Type | Default | Valid range (saved document) | UI control and range (`TransitionEditor.swift`) |
|---|---|---|---|---|
| `id` | UUID | new | | |
| `nextClipID` | UUID? | nil | set to the incoming clip when applied (l. 348); nil for a closing transition | |
| `style` | enum | `dissolve` | 9 values | Style button opens a 2-column grid sheet "Transition style" (l. 269-271, 417-431) |
| `duration` | s | 0.5 | finite, `> 0`, `≤ 604800` | "Blend duration" ("Freeze duration" for still). Presets 0.5, 1…10, 15, 30, 60 (`VideoTools.swift:170-172`) plus a slider from 1/30 to the join's maximum in steps of 1/30 (l. 283-296). Caption "Up to N s" |
| `axis` | horizontal / vertical | horizontal | | "Layout" segmented, shown for split and whip: "Left / right", "Top / bottom" (l. 300-302; titles `Transitions.swift:17`) |
| `divider` | fraction | 0.5 | 0.15…0.85 | "Divider" slider 0.15…0.85, shown as a percentage; also dragged on the preview (l. 336-337, 490) |
| `reverse` | Bool | false | | "Reverse direction" toggle, whip only (l. 303). The wipe angle slider resets it to false (l. 307) |
| `audio` | crossfade / first / second | crossfade | | "Audio" segmented, split only: "Crossfade", "First clip", "Second clip"; choosing one clears `audioOffset` and `audioBlend` (l. 338) |
| `first`, `second` | framing {x, y, zoom} | 0.5, 0.5, 1 | x, y in 0…1; zoom 1…4 | Split only: drag to pan, pinch to zoom (1…4), double tap resets that side, "Reset framing" button (l. 335, 339, 479-517) |
| `fadeColor` | sRGB {red, green, blue}? | nil = black | each 0…1 | "Fade color" picker without opacity; black is stored as nil (l. 272-280) |
| `hold` | s? | nil = 0 | 0…604800 | "Hold at halfway", or "Hold on color" for a fade between two clips; slider 0…max(10, min(60, maximum)) step 1/30; 0 is stored as nil (l. 331-333). Ignored for flashCut and still (`supportsHold`, `Transitions.swift:13, 65`) |
| `audioOffset` | s? | nil = 0 | abs ≤ 604800 | "Start offset" slider ±audioMaximum, step 1/30, with −0.1 s / "0 s" / +0.1 s buttons (l. 365-372). audioMaximum = min(60, max(2, longest clip)) (l. 14) |
| `audioBlend` | s? | nil = style default | 0…604800 | "Audio blend" slider 0…max(audioMaximum, shown value), with −0.1 s / "Cut" (= 0) / +0.1 s (l. 374-381) |
| `wipeAngle` | degrees clockwise on screen? | nil = 0 or 90 from `axis` | −360…360 | "Angle" slider −180…180 step 1, label "N°" (l. 306-307) |
| `hardEdge` | Bool? | nil = soft | | "Edge" segmented: "Soft edge", "Hard edge" (l. 305) |
| `highlightsFirst` | Bool? | nil = shadows first | | "Reveal" segmented: "Shadows first", "Highlights first" (l. 310) |
| `flashCount` | Int? | nil = 3 | 1…20 | Stepper "Flashes: N", 1…20 (l. 313) |
| `flashAcceleration` | Double? | nil = 2 | 0.25…4 | "Acceleration" slider 0.25…4 step 0.05, shown as "N.N×" (l. 315-316) |
| `stillDissolve` | s? | nil or 0 = cut | 0…604800 | "After the freeze": "Cut" / "Dissolve"; choosing Dissolve sets 0.3; then "Exit dissolve" slider 1/30…5 (l. 320-325) |
| `blurAmount` | Double? | nil = 0.75 | 0…1 | "Optical blur" slider 0.1…1, shown as a percentage (l. 327-330) |

Derived values (`Transitions.swift:54-71`):

- `resolvedFadeColor = fadeColor ?? black`.
- `holdDuration = supportsHold ? (hold ?? 0) : 0`.
- `customAudio = audioOffset != nil || audioBlend != nil`.
- `defaultAudioBlend = still ? 0 : duration + (fadeColor ? 0 : holdDuration)`.
- `angle = (wipeAngle ?? (axis == horizontal ? 0 : 90)) + (reverse ? 180 : 0)`.

Editor behaviour worth keeping:

- A new transition starts as `dissolve` with `duration = min(0.5, join.maximum)` (l. 64). On first open of a new transition the axis follows the canvas: vertical when the canvas is taller than wide (l. 106).
- The first time a style is chosen in a session: whip sets `duration = 8/30`, still sets `duration = 1` (l. 52-59).
- Apply is refused while the fitted picture is shorter than one frame (l. 41).
- Notes shown: total length and "Project N s shorter/longer" or "Project length unchanged" (l. 46-51); when fitted, "Picture and audio timing reduced to N% to fit the neighboring clips. Your requested settings are kept." (l. 298); for flash cut "N flashes fit at this duration." (l. 317-318).
- "Remove transition" restores a straight cut (l. 342-346).
- Sheet title "Transition"; header "Start · fade in", "End · fade out" or "Clip N → N+1" (l. 44).

---

## 3. Time: from the clock to progress

### 3.1 Timing of one join

`TransitionTiming(settings, factor, edge)`, `Transitions.swift:112-156`. `factor` (0…1) shrinks a transition that does not fit; `edge` is −1 opening, +1 closing, 0 between two clips. Times are relative to the **origin**, the start of the picture effect. `frame = 1/30` (`TimelineEditing.swift:42`).

```
still   = style == still
fade    = style == fadeColor
motion  = (still ? stillDissolve ?? 0 : duration) · factor
hold    = still ? duration · (customAudio && edge != +1 ? factor : 1)
                : holdDuration · (fade && edge == 0 && !customAudio ? 1 : factor)
if !still && motion + hold < frame − 1e-6:  motion = 0; hold = 0      // too short: no transition
videoDuration = motion + hold

aNeed = videoDuration; bNeed = 0                    // footage needed from A after origin, where B must have started
if fade && edge == 0:  aNeed = motion/2; bNeed = motion/2 + hold
if still:              aNeed = 0;        bNeed = hold

offset = (audioOffset ?? 0) · factor
blend  = (audioBlend ?? (edge != 0 && fade ? duration + holdDuration : defaultAudioBlend)) · factor
if split && audioOffset == nil && audioBlend == nil:
    audio == first :  offset = max(0, videoDuration − 0.008); blend = min(0.008, videoDuration)
    audio == second:  blend = min(0.008, videoDuration)
```

Then, by edge (l. 133-154):

- **Opening (−1)**: `audioIn = begin…begin + blend` with `begin = offset + (still && !customAudio ? hold : 0)`; `rightStart = min(bNeed, audioIn.lower)`; `lower = min(0, rightStart)`; `upper = max(videoDuration, audioIn.upper)`; `rightCost = upper − rightStart`.
- **Closing (+1)**: `audioOut = offset…offset + blend`; `leftEnd = max(aNeed, audioOut.upper)`; `lower = min(0, audioOut.lower)`; `upper = max(videoDuration, leftEnd)`; `leftCost = leftEnd − lower`.
- **Between (0)**: if `(fade || still) && !customAudio`: `audioOut = offset…offset + blend/2`, `audioIn = offset + blend/2 + hold…offset + blend + hold`; otherwise `audioIn = audioOut = offset…offset + blend`. `leftEnd = max(aNeed, audioOut.upper)`; `rightStart = min(bNeed, audioIn.lower)`; `lower = min(0, rightStart, audioOut.lower)`; `upper = max(videoDuration, leftEnd, audioIn.upper)`; `leftCost = leftEnd − lower`; `rightCost = upper − rightStart`.

`cut = still ? hold : videoDuration/2` is the moment that counts as the boundary (playhead snap, marker). `overlap = leftEnd − rightStart`: positive when the clips play at once, negative when time is inserted (colour hold, freeze).

### 3.2 Placement on the timeline

- `origin = A.end − leftEnd`; `B.start = origin + rightStart` (`Transitions.swift:229-230, 373-377`). For an opening, `origin = max(0, B.start − rightStart)` (l. 373-374). The origin and the factor are stored with the relation and never recomputed while rendering (`TrackTimeline.swift:462-470`).
- Picture effect runs over `origin … origin + videoDuration`; the whole affected stretch (picture and sound) is `origin + lower … origin + upper` (l. 189-191, 245).
- The clips keep their source trims; only B (and everything linked after it) moves (l. 375-380; test `TransitionTests.swift:17`).

Resulting lengths for two 4 s clips (`TransitionTests.swift:14-16, 44-48, 279-289`):

| Join | Timeline | Notes |
|---|---|---|
| Dissolve 1 s | 7 s | B starts at 3, join at 3.5 |
| Fade through black 1 s | 8 s | range 3.5…4.5; each clip gives 0.5 s |
| Dissolve 1 s + hold 2 s | 5 s | overlap 3 s |
| Fade 1 s + hold 2 s | 10 s | the colour hold inserts 2 s and uses no footage |
| Still 2 s + dissolve 0.5 s | 10 s | picture effect 4…6.5 |
| Opening still 1 s | 9 s | 1 s of black before the first clip |
| Closing still 2 s + 0.5 s | 10.5 s | |
| Opening + closing dissolve 1 s | 8 s | ranges 0…1 and 7…8 |

### 3.3 Progress

`progress(at elapsed)`, `Transitions.swift:157-163`, with `elapsed = time − origin` (`VideoToolCompositor.swift:248, 296`):

```
still:
    motion > 0 ? clamp01((elapsed − hold) / motion) : (elapsed < hold ? 0 : 1)
others:
    motion <= 0            -> elapsed < videoDuration/2 ? 0 : 1
    elapsed < motion/2     -> clamp01(elapsed / motion)
    elapsed <= motion/2+hold -> 0.5                      // the hold sits at the midpoint
    else                   -> clamp01((elapsed − hold) / motion)
```

Progress is **linear in time**. Easing is applied inside the picture recipe, per style:

```
t     = clamp01(progress)
eased = t·t·(3 − 2·t)            // smoothstep, TransitionRenderer.swift:191
```

| Style | Uses |
|---|---|
| dissolve, still, wipe, hitchcock | `eased` |
| whip | `eased` for position and mix, `t` for blur |
| fadeColor, split, flashCut | `t` (linear) |
| defocus | `t` for blur and for the mix window |

For every style: `t <= 0` returns A untouched and `t >= 1` returns B untouched (l. 192). No blur, mask or line is drawn at the ends.

During a midpoint hold the pictures keep playing (both clips run under the 50% state); only progress stands still (`TransitionTests.swift:308`). A colour hold and a freeze are visually constant (l. 309).

### 3.4 Fitting, clip lengths, speed, neighbours

- Durations are in **timeline seconds**, after speed and pause removal. Clip length means played length: two 4 s clips at 0.25× are 16 s each and accept a 12 s dissolve (`TransitionTests.swift:30-33`). Speed 2× on one side and removed pauses on the other compose without drift (l. 164-172).
- Each picture has one incoming and one outgoing slot; setting a transition on a join replaces whatever used those two slots (`Transitions.swift:343-347`).
- Free footage: `leftFree = A.length − rightCost of A's incoming transition`, `rightFree = B.length − leftCost of B's outgoing transition` (l. 354-357). The new transition fits when `leftCost <= leftFree + 1e-8` and `rightCost <= rightFree + 1e-8`.
- If it does not fit at factor 1, the factor is found by 48 steps of bisection on 0…1 (largest that fits) (l. 363-368). The factor scales picture and sound together; the user's settings are kept as entered (l. 369, 381).
- If the fitted `videoDuration` is under one frame the transition is not created (l. 370).
- Two transitions on one clip can never need the same footage, so three clips never show at once and transition windows never cross (`TransitionTests.swift:49-54`: of two 3 s dissolves set one after the other around a 4 s clip, the first keeps its 3 s and the second is fitted into the 1 s left). The older sequence layout shortens both by a shared factor instead, to 2 s each (`Transitions.swift:213-222`). See `docs/TRANSITIONS-FIT.md`.
- Slider maximum for a join: `min(604800, leftSpace/cost, rightSpace/cost)` with `cost = 0.5` for a fade between two clips and 1 otherwise; for still it is unlimited (the editor uses `max(60, project length, duration)`) (`Transitions.swift:238-243`, `TransitionEditor.swift:11`).
- A trim that removes the footage a transition needs removes the transition; extending again does not bring it back (`TransitionTests.swift:40-42`). When clip positions change, a relation is refitted by bisection on `overlap` or dropped (`TrackTimeline.swift:856-887`).
- Splitting inside a transition is refused; a selection that starts or ends inside one is not edited (`TransitionTests.swift:20, 55-56`).
- Removing a transition in the editor ("Remove transition") restores the straight cut and the original length (`Transitions.swift:387-396`).

---

## 4. Compositing around the transition

`VideoToolCompositor.swift:272-317` (track path, the live one: `MediaRenderer.swift:193`) and l. 237-256 (sequence path, used for single-source sub-renders: `MediaRenderer.swift:209-211`).

1. A missing side is a solid fill: `fill = fadeColor style ? resolvedFadeColor : black`, sRGB, opaque, canvas size (`TransitionRenderer.swift:184-187`; l. 291-293). This covers openings, closings, and the part of a colour hold or freeze where no clip is active.
2. **A fade through color at an edge is drawn as a dissolve** between the fill colour and the clip (l. 294; also l. 247). So an opening "Fade through color" in red is an eased dissolve from red to the clip, and its hold sits at a 50% mix ("Hold at halfway").
3. `progress = timing.progress(time − origin)`; `duration` passed to the recipe is the fitted `videoDuration`; `framesPerSecond` is the output frame rate (l. 295-297).
4. Titles and image overlays: for every style except split, each clip is drawn with its own overlays before mixing, so they follow their clip through the transition. For split, clips are drawn without overlays and cropped to their content (no letterbox bars: `contentBounds`, `TransitionRenderer.swift:293-297`); overlays of both clips are drawn once over the finished split picture (l. 282-283, 298-301).
5. The mixed picture replaces both clips in the layer stack, at the position of the upper of the two (l. 279, 286-302).
6. If no layer is active at all, the frame is the fill (l. 315).

---

## 5. Dissolve

`TransitionRenderer.swift:194-195`.

- `s = eased`. Output `= mix(A, B, s)` over the whole canvas. No transform, crop or blur.
- p = 0: A. p = 1: B. Hold: an even 50/50 mix while both clips keep playing.
- Timing: `motion = duration`, overlap = `duration + hold`. Each clip gives `duration + hold` seconds.
- Sound (default): one linear crossfade over `0 … duration + hold` (section 14).
- Edges: opening = eased dissolve from black to the clip; closing = from the clip to black.

## 6. Fade through color

`TransitionRenderer.swift:196-199`.

- `C` = solid `resolvedFadeColor` (sRGB components, default black).
- `t < 0.5`: output `= mix(A, C, min(1, 2t))`. Otherwise: output `= mix(C, B, max(0, 2t − 1))`. **Linear, not eased.**
- t = 0.5 is exactly C (test: midpoint black, `TransitionTests.swift:80-81`; chosen colour l. 84-86).
- Timing between two clips: no overlap. A gives `motion/2`, B gives `motion/2`; a hold inserts `hold` seconds of solid colour, uses no footage and is **not shortened by fitting** unless custom sound is set (`Transitions.swift:120-121, 125`). During the hold no clip is active.
- Sound (default): A falls linearly over the first half, silence through the hold, B rises over the second half.
- At an edge: see section 4, item 2 (drawn as an eased dissolve from/to the colour; sound blend default is `duration + hold`).
- Saved style name is `fadeBlack` for compatibility (`Transitions.swift:8`); black is saved as an absent `fadeColor`.

## 7. Wipe

`TransitionRenderer.swift:200-209`. Kotlin: `TransitionGeometry.wipe` (identical).

```
θ   = angle · π/180                        // degrees clockwise on screen; 0 = travels right, 90 = travels down
dx  = cos θ ;  dy = −sin θ                 // bottom-up coordinates; in top-down use dy = +sin θ
span    = |dx|·W + |dy|·H
feather = hardEdge ? 0.5 : max(1, 0.035·span)          // half-width of the soft band, pixels
dist    = (span + 2·feather)·eased − span/2 − feather
centre  = (W/2 + dx·dist, H/2 + dy·dist)
p0 = centre − (dx, dy)·feather ;  p1 = centre + (dx, dy)·feather
```

- Mask: a linear gradient, white at `p0`, black at `p1`, clamped beyond both. Share of B at a pixel `q`: `clamp01(1 − ((q − p0)·(dx, dy)) / (2·feather))`. Output `= mix(A, B, share)`.
- B is uncovered **behind** the moving edge; A remains ahead of it. With angle 0 the left side becomes B first.
- At eased = 0 the band is wholly off the trailing side, at 1 wholly off the leading side, for any angle (the span covers the corners of a diagonal).
- Hard edge: a 1 pixel band. Test: 45°, hard, p = 0.5 on 160 × 90: pixel (20, 80) bottom-up is B, (140, 10) is A (`TransitionTests.swift:90-92`).
- The UI only sets `wipeAngle` (and clears `reverse`); `axis` and `reverse` matter only for documents where `wipeAngle` is nil.
- Hold: the edge stops at the centre.

## 8. Split screen

`TransitionRenderer.swift:252-266, 276-292`. Kotlin: `splitRects`, `splitLine`, `splitMix`, `framed` (identical).

Rectangles, bottom-up:

- Horizontal ("Left / right"): `s = W·divider`; A in `(0, 0, s, H)`, B in `(s, 0, W − s, H)`. A is on the **left**.
- Vertical ("Top / bottom"): `s = H·divider`; A in `(0, H − s, W, s)`, B in `(0, 0, W, H − s)`. A is on **top**, taking `divider` of the height.

Each clip in its rectangle (`framed`, l. 284-292):

- Source = the clip's drawn picture cropped to its content (no bars), size `sw × sh`.
- Crop window (top-down source coordinates) `= CropSettings(x, y, zoom).rect(source, viewport)` with no rotation (`VideoTools.swift:23-33`): `scale = max(vw/sw, vh/sh) · min(8, max(1, zoom))`; `cw = vw/scale`, `ch = vh/scale`; centre `cx = clamp(x·sw, cw/2, sw − cw/2)`, `cy = clamp(y·sh, ch/2, sh − ch/2)`. `y` is measured from the top.
- Draw that window scaled by `vw / cw` at the rectangle's origin, clipped to the rectangle (aspect fill).

Pair picture: B's half over A's half over black, then a divider line of colour (0.25, 0.25, 0.25), width `max(1, 0.002·min(W, H))`, centred on the divider (l. 256-260).

Mix over time, **linear `t`**, `edge = 0.15` (l. 262-265):

- `t < 0.15`: `mix(A full frame, pair, t/0.15)`.
- `t > 0.85`: `mix(pair, B full frame, (t − 0.85)/0.15)`.
- Otherwise: pair.

Titles and image overlays of both clips are drawn over the result (section 4, item 4). A hold at the midpoint simply shows the pair longer. Tests: divider 0.35 on 160 × 90, p = 0.5: (20, 40) is A, (100, 40) is B; vertical: (50, 80) is A, (50, 20) is B (`TransitionTests.swift:74-79`).

Sound: "Crossfade" follows the default; "First clip" keeps A until the last 8 ms; "Second clip" switches to B in the first 8 ms (section 3.1).

## 9. Hitchcock (luminance reveal)

`TransitionRenderer.swift:210-222`. Kotlin: `TransitionGeometry.hitchcock` (identical).

```
feather   = 0.08
threshold = eased·(1 + 2·feather) − feather            // sweeps −0.08 … 1.08
luma      = 0.2126·r + 0.7152·g + 0.0722·b              // of A, sRGB-encoded (not linear), each component clamped 0…1 first
shadows first (default):  share = clamp01(−luma/feather + threshold/feather + 0.5)
highlights first:         share = clamp01(+luma/feather + (threshold − 1)/feather + 0.5)
output = mix(A, B, share)                               // per pixel
```

- The key is A's brightness only, taken from A's current frame, so it moves with A.
- Shadows first: dark areas of A turn into B first. Highlights first: bright areas first.
- HDR highlights above 1 are keyed as white, so they are replaced before the last frame (test at p = 0.99, `TransitionTests.swift:99-100`).
- p = 0.5, shadows first: luma 0.1 is B, luma ≥ 1 is A (l. 97-98); reversed for highlights first (l. 101-103).
- At an opening A is the black fill: luma 0 everywhere, so the picture pops in uniformly as the threshold passes (shadows first: early; highlights first: late). At a closing the key is the clip.

## 10. Whip pan

`TransitionRenderer.swift:223-234, 268-275`. Kotlin: `TransitionGeometry.whip`, `blurScale` (identical).

```
vertical = axis == vertical ;  sign = reverse ? −1 : +1
distance = (vertical ? H : W) · 0.65 · sign
radius   = min(W, H) · 0.12 · sin(π·t)                  // motion blur, peaks at the midpoint; uses linear t
offsetA  = −eased · distance
offsetB  = (1 − eased) · distance
```

For each of A and B (`pan`, l. 227-233):

1. Optionally shrink by `scale = min(1, max(960 / max(W, H), 2 / max(2, radius)))` (Lanczos) to save work; all following lengths are multiplied by `scale` and the result is scaled back by `1/scale`.
2. Extend the edge pixels outward without limit (clamp to edge). The area uncovered by the slide shows smeared edge pixels, **not black**.
3. Translate by the offset: horizontal `x += offset`; vertical: `offset` pixels **down the screen** (bottom-up `y −= offset`).
4. Directional motion blur of `radius` along the axis of travel (angle 0 for horizontal, 90° for vertical).
5. Crop to the canvas.

Output `= mix(panA, panB, eased)`.

- Default direction: both pictures move left (A leaves to the left, B arrives from the right); vertical: both move up; `reverse` flips it.
- p = 0: A at rest, no blur. p = 1: B at rest. Example 200 × 100 at p = 0.5: offsets −65 and +65, radius 12, mix 0.5.
- The editor sets the duration to 8 frames (8/30 s) the first time whip is chosen.
- The blur is Core Image's `CIMotionBlur` with `inputRadius = radius`. Its exact kernel is not in the source; a box blur along the axis spanning ±radius is the nearest neutral description.

## 11. Flash Cut

`TransitionRenderer.swift:235-238`, `Transitions.swift:72-82`. Kotlin: `flashFrames`, `flashShowsSecond` (identical).

- No blending: each output frame is entirely A or entirely B.
- `length` = fitted `videoDuration`; `fps` = output frame rate. `frame = floor(t · length · fps + 1e-6)` (equivalently the frame index since the origin). Show **B** if `frame` is in `flashFrames(length, fps)`, else **A**. After the transition (t ≥ 1) B stays.

```
frames = max(1, round(length · fps))                    // round half away from zero
count  = min(flashCount ?? 3, max(0, (frames − 1) / 2)) // integer division
for index in 1…count:
    u       = index / (count + 1)
    desired = round((1 − (1 − u)^(flashAcceleration ?? 2)) · (frames − 1))
    result[index] = min(frames − 2 − 2·(count − index), max(previous + 2, desired))    // previous = −1 before the first
```

- Flashes are single frames, never adjacent, never the first or the last frame. With the default acceleration 2 they bunch towards the end ("closer together toward the cut"); 1 s at 30 fps with defaults gives frames 13, 22, 27.
- Preview and export must use the same `fps` here or flashes land on different frames; the original passes the composition's frame rate (`VideoToolCompositor.swift:250, 297`).
- No hold. Both clips overlap for the whole duration (A is shown up to the end, B's flashes show B at its own running time).
- Sound: default linear crossfade over the duration.

## 12. Still (freeze frame)

Picture recipe: same as dissolve (`TransitionRenderer.swift:194`), with A replaced by a frozen frame. Timing makes it different.

- `hold = duration` (the freeze), `motion = stillDissolve ?? 0` (the exit). Progress is 0 for the whole freeze, then runs 0…1 over the exit dissolve, **eased**; with no dissolve it cuts at `elapsed = hold` (section 3.3).
- The origin is the **end of A**: A gives no footage (`leftCost = 0`), time is inserted (`overlap = −duration`). B starts at `origin + duration`; when there is an exit dissolve B's first `motion` seconds play under it (`rightCost = motion`).
- The frozen frame is the last frame of A actually shown: `lastShown = max(A.start, (ceil(freezeStart·fps − 1e-6) − 1) / fps)` in timeline seconds, mapped into A's source clock; it is held from `freezeStart = clamp(origin, A.start, A.end)` to `origin + videoDuration`, with A's colour, crop and overlays as at that instant (`TrackRenderer.swift:75-79, 100-120`; test `TransitionTests.swift:310-319`: difference to the frame at `4 − 1/30` under 1/255 average).
- Freeze length is not shortened by fitting (it needs no footage) unless custom sound is set and the join is not a closing (`Transitions.swift:120`).
- Opening still: black is held for `duration`, then cut or dissolve into the first clip. Closing still: the last frame is held for `duration`, then dissolves to black over `stillDissolve` (or the movie ends on the frozen frame).
- Sound (default, `audioBlend` = 0): A's sound stops at its end and B's begins at `origin + duration`, each with the minimum 8 ms ramp; the freeze is silent. With custom sound the two can overlap under the frozen picture.
- UI: "Freeze duration"; "After the freeze" Cut / Dissolve; note "Holds the outgoing picture, then cuts or dissolves into the next scene." (`TransitionEditor.swift:320-326`).

## 13. Defocus

`TransitionRenderer.swift:239-251, 268-275`. Kotlin: `TransitionGeometry.defocus`, `blurScale` (identical).

```
radius = min(W, H) · 0.05 · (blurAmount ?? 0.75) · sin(π·t)      // linear t; peaks at the midpoint
blend  = clamp01((t − 0.35) / 0.3)                               // B replaces A between 35% and 65%
```

- `optical(X)`: optional downscale by `blurScale(radius)` as in whip, clamp edges outward, **disc (bokeh) blur** of `radius` with ring amount 0.12, ring size 0.1, softness 0.25, scale back, crop to canvas. The source comment insists on an aperture blur with highlight discs, not a Gaussian (l. 240-241).
- `blend <= 0`: `optical(A)`. `blend >= 1`: `optical(B)`. Otherwise `mix(optical(A), optical(B), blend)` (linear blend, not eased).
- p = 0 and p = 1: sharp A and B (radius 0 and the early return).
- Example 200 × 100, p = 0.5: radius 3.75 (5.0 at 100%), blend 0.5.
- Hold: both stay at peak blur, half mixed.

---

## 14. Sound during a transition

Gain of a clip's own sound at timeline time `x` (`CutAudioEnvelope.swift:30-55`); the transition part is l. 36-37:

```
inRamp  = origin + audioIn    (of the transition entering this clip)
outRamp = origin + audioOut   (of the transition leaving this clip)
gain *= clamp01((x − inRamp.lower)  / max(0.008, inRamp.upper  − inRamp.lower))
gain *= clamp01((outRamp.upper − x) / max(0.008, outRamp.upper − outRamp.lower))
```

- Ramps are **linear in amplitude** (not equal power) and multiply with the clip's own level, user fades and the 8 ms edge fades of each piece.
- A zero-length blend ("Cut") is still an 8 ms ramp.
- The ramps apply to the sound that follows a picture clip (`TrackRenderer.swift:271-294`). Sound that was detached from its picture is not shaped by the transition.
- Defaults by style (from section 3.1, between two clips, no custom sound):

| Style | A goes down | B comes up |
|---|---|---|
| dissolve, wipe, hitchcock, whip, defocus | `0 … duration + hold` | same range |
| flashCut | `0 … duration` | same |
| split, "Crossfade" | `0 … duration + hold` | same |
| split, "First clip" | last 8 ms of the picture effect | same |
| split, "Second clip" | first 8 ms | same |
| fadeColor | `0 … duration/2` | `duration/2 + hold … duration + hold` |
| still | cut at 0 | cut at `duration` (after the freeze) |

- Custom sound (`audioOffset` and/or `audioBlend` set): one crossfade `offset … offset + blend` for both clips, for every style (`TransitionTests.swift:332`). A negative offset is a J-cut (B's sound first), a positive offset beyond the picture is an L-cut. The clips are then overlapped further as needed: `leftEnd`, `rightStart`, costs and fitting all include the sound (section 3.1). An opening J-cut reserves its lead at time zero rather than moving the first clip negative (`Transitions.swift:371-374`).
- Opening: B rises over `audioIn`. Closing: A falls over `audioOut`. For an edge fade through color the default blend is `duration + hold`.
- Audio stays audible through every style's transition in the original's export test (`TransitionTests.swift:157-161`), and the audio track keeps the full timeline length (l. 156).

---

## 15. Timeline UI around transitions (for completeness)

- A join with a transition shows an orange marker with two inward triangles at `join.time`, filled when the playhead is on it, plus 2 pt orange lines under the participating clips spanning the picture effect (`EditorTimeline.swift:110-139, 953-967`). Tapping it moves the playhead to the join (l. 561-572).
- The Transition tool acts on the join under the playhead (`EditorView.swift:1012`); joins are titled "Start" and "Join N" (`Transitions.swift:193`). Accessibility label: "<style title> transition, <join title>" (`EditorTimeline.swift:420`).
- A document with a transition is saved with schema version at least 6 so older builds refuse it (`TransitionTests.swift:29, 89`).

---

## 16. Kotlin core against the original

### (a) Present and matching

`core/src/main/kotlin/app/takes/core/TransitionSettings.kt`:

| Kotlin | Original | Notes |
|---|---|---|
| `TransitionStyle` values, saved names, `title`, `supportsHold` (l. 10-39) | `Transitions.swift:6-14` | order identical; "still" lower case as in the original |
| `TransitionAxis`, `TransitionAudio` and titles (l. 42-53) | l. 15-22 | |
| `TransitionFraming` defaults, `crop`, `valid` (l. 57-68) | l. 23-30, 85 | uses `CropSettings.rect` (`core/.../Crop.kt:32-47`), same formula as `VideoTools.swift:23-33` |
| `TransitionColor`, `BLACK`, `valid` (l. 72-78) | l. 31-38 | |
| `TransitionSettings` fields and defaults (l. 89-118) | l. 39-64 | duration 0.5, divider 0.5, horizontal, crossfade |
| `resolvedFadeColor`, `holdDuration`, `customAudio`, `defaultAudioBlend`, `angle`, `newFeatures` (l. 119-135) | l. 54, 65-71 | |
| `flashFrames` (l. 143-155) | l. 72-82 | Swift rounding reproduced with `roundedAwayFromZero` |
| `valid` and `MAXIMUM_DURATION = 604800` (l. 158-174) | l. 42, 83-92 | |
| `TransitionTiming.of` (l. 226-282) | l. 112-156 | every branch identical; `PauseDetector.FRAME` = 1/30 (`core/.../Pauses.kt:114`) = `TimelineEditing.frame` |
| `TransitionTiming.progress`, `overlap` (l. 208-219) | l. 111, 157-163 | |

`core/src/main/kotlin/app/takes/core/TransitionGeometry.kt`:

| Kotlin | Original (`TransitionRenderer.swift`) |
|---|---|
| `eased` (l. 18-21) | l. 191 |
| `wipe`, `WipeEdge.secondShare` (l. 28-51) | l. 201-209 (share = the clamped linear gradient) |
| `splitRects` (l. 57-64) | l. 276-283 |
| `splitLine` (l. 67-72) | l. 257-259 (colour 0.25 grey is not in Kotlin) |
| `splitMix`, `SPLIT_EDGE` (l. 78-83) | l. 262-265 |
| `framed` (l. 88-91) | l. 284-292 |
| `hitchcock`, `LuminanceKey` (l. 98-116) | l. 211-221 |
| `whip` (l. 123-132) | l. 224-226, 234 |
| `flashShowsSecond` (l. 138-145) | l. 192, 236-238 |
| `defocus` (l. 152-160) | l. 242, 249 |
| `blurScale` (l. 168-169) | l. 268-272 |

All of this is exercised by the existing `TransitionGeometryTest` (23 tests) and by the new `TransitionOriginalParityTest` (12 tests), which pass.

### (b) Present but different

Nothing in `TransitionSettings.kt` or `TransitionGeometry.kt` differs numerically from the original. The differences are all in the **older model the app actually uses**:

| Topic | Android today | Original |
|---|---|---|
| Model in use | `ClipTransition` with `Style { FadeBlack, Dissolve }` (`core/.../Project.kt:59-79`) | nine styles, `ClipTransition` in full (= Kotlin `TransitionSettings`) |
| Default style of a new transition | `FadeBlack` in the model (`Project.kt:62`), `setTransition` default `FadeBlack` (`TimelineEditing.kt:173`); the dialog starts on Dissolve (`app/.../EditorScreen.kt:1449`) | `dissolve` (`Transitions.swift:45`) |
| Maximum duration | 10 s (`Project.kt:77`); a longer saved transition is invalid | 604800 s (`Transitions.swift:42`); test with 12 s (`TransitionTests.swift:32-33`) |
| `nextClipID` | required | optional (nil on a closing transition) |
| Dissolve curve | linear: `weight` = `level(offset, …)` (`TimelineEditing.kt:203-221`), used by `DissolveCompositor` (`app/.../Titles.kt:139-156`) and the preview (`EditorScreen.kt:536-538, 629`) | `eased = t²(3 − 2t)` (`TransitionRenderer.swift:191, 195`) |
| Dissolve length when clips are short | `min(duration, A/2, B/2)` (`TimelineEditing.kt:198-201`) | largest factor such that costs fit the footage left by the neighbouring transitions; a clip can give up to its whole length (`Transitions.swift:354-368`) |
| Fade length when clips are short | each side `min(duration/2, A/2, B/2)` (`TimelineEditing.kt:189-192`) | same bisection; each side `factor·duration/2` |
| Fade picture | RGB scaled to black (`FadeEffect`, `Titles.kt:87-92`); preview black overlay `1 − level` (`EditorScreen.kt:651`) | mix towards `fadeColor` (default black); linear in both, so black fades match when not clamped |
| Titles in a dissolve | drawn once over the blend with opacity = linear share (`TimelineEditing.titlesAt`, `TimelineEditing.kt:231-239`) | baked into each clip's picture and mixed with it (eased) |
| Minimum | none (presets 0.3 / 0.5 / 1 / 2 s, `EditorScreen.kt:1460`) | a fitted transition under one frame is refused (`Transitions.swift:370`); presets 0.5, 1…10, 15, 30, 60 and a slider in frames |
| Sound | `gain · ownFades · level · weight`, linear (`TimelineEditing.kt:306-311`) | linear ramps over `audioIn` / `audioOut`; equal to Android's for a default dissolve or fade between two clips; minimum ramp 8 ms |
| Blend colour space | Media3 compositor alpha (`DissolveCompositor`); not verified as linear light | linear light (`VideoToolCompositor.swift:194`) |

### (c) Missing in Kotlin core

1. **Mix functions for dissolve, still and fade through color.** There is `eased` but nothing that says "dissolve share = eased(p)" or returns the two-phase linear fade (`TransitionRenderer.swift:194-199`). Trivial, but the fade's lack of easing is easy to get wrong.
2. **Edge rule**: a fade through color at an opening or closing is drawn as a dissolve with the colour as the missing side, and the fill colour rule (`VideoToolCompositor.swift:291-294`, `TransitionRenderer.swift:184-187`).
3. **Layout and fitting**: no equivalent of `TimelineLayout`, `TransitionJoin`, the stored `origin` and `factor`, the 48-step bisection, the slot rule (one incoming, one outgoing per clip), join `maximum`, or `settingTransition` / `restoringStraightCut` (`Transitions.swift:195-270, 333-397`). `TransitionTiming.of` accepts `factor` and `edge` but nothing computes them.
4. **Opening and closing transitions**: `TimelineClip` has only `transition` (`Project.kt:132`); there is no `openingTransition` and no closing slot.
5. **Frozen-frame time for still**: the `lastShown` formula (`TrackRenderer.swift:112-113`).
6. **Sound envelope from the timing**: `clamp01` ramps over `origin + audioIn/audioOut` with the 8 ms minimum (`CutAudioEnvelope.swift:36-37`). `TimelineEditing.soundLevel` knows only the two old styles.
7. **Blur parameters**: bokeh ring amount 0.12, ring size 0.1, softness 0.25 (`TransitionRenderer.swift:246`); motion blur angle; clamp-to-edge before sliding and blurring (l. 229, 245). The split divider colour 0.25 grey (l. 260).
8. **Split content crop**: clips are cropped to their content bounds (no bars) before framing, and overlays are drawn once over the pair (`VideoToolCompositor.swift:282-283, 298-301`).
9. **Editor defaults**: whip 8/30 s and still 1 s on first selection, `min(0.5, maximum)` for a new transition, axis from the canvas orientation, UI ranges (section 2).
10. `TransitionSettings` and `ClipTransition` are two types for one saved object; they are not merged (the Kotlin comment at `TransitionSettings.kt:80-87` says so). JSON written by one is read by the other for the two shared styles (existing test `settingsWriteTheOriginalFieldsAndReadTheExistingTransition`).

---

## 17. What the Android renderer calls today, and what to add

Calls made today (all on the old model):

| Where | Call | Purpose |
|---|---|---|
| `app/.../Exporter.kt:163-164, 184` | `TimelineEditing.fadeLength`, `FadeEffect` | picture fade to black per clip |
| `app/.../Exporter.kt:186-187, 244` | `TimelineEditing.transition`, `soundLevel` | sound shaping per clip |
| `app/.../Exporter.kt:219, 258-280` | `TimelineEditing.chains`, `clipStart`, `duration` | two alternating sequences; clips that dissolve overlap |
| `app/.../Titles.kt:139-156` (`DissolveCompositor`) | `TimelineEditing.chains`, `clipStart`, `weight` | opacity of the top sequence = linear share |
| `app/.../EditorScreen.kt:534-547` | `TimelineEditing.level`, `weight`, `chains`, `soundLevel`, `overlap` | preview: deck alpha, black overlay, deck volume |
| `app/.../EditorScreen.kt:917-920, 1448-1460` | `TimelineEditing.transition`, `setTransition`, `TransitionDialog` | choose style (2) and duration (4 presets) |
| `app/.../EditorScreen.kt:1001-1002, 1127-1135` | `fadeLength`, `overlap` | timeline tags "Dissolve" / "Fade" |

No call reaches `TransitionSettings`, `TransitionTiming` or `TransitionGeometry`.

To draw every style, the renderer needs, per output frame at time `x`:

1. The join covering `x` with its `TransitionSettings`, `origin`, `factor`, `edge` (needs items 3 and 4 of section 16c, or an interim rule mapping the current `overlap` / `fadeLength` clamps to a factor).
2. `timing = TransitionTiming.of(settings, factor, edge)`; `p = timing.progress(x − origin)`.
3. Frames A and B (or the fill colour for a missing side; frozen A for still).
4. Per style: dissolve / still `TransitionGeometry.eased(p)`; fade: two-phase linear (to add); wipe `wipe(...)` then `secondShare` per pixel as a gradient mask; split `splitRects`, `framed`, `splitLine`, `splitMix`; hitchcock `hitchcock(...)` giving `scale`, `bias` for a luma-key shader; whip `whip(...)` plus `blurScale`; flash cut `flashShowsSecond(settings, p, timing.videoDuration, fps)`; defocus `defocus(...)` plus `blurScale`.
5. Sound: gains from `timing.audioIn` / `timing.audioOut` shifted by `origin`.

Renderer capabilities this requires beyond today's alpha-only `DissolveCompositor`: a two-input shader stage (gradient mask, luma key from A, crop-and-place into rectangles, solid colour and line), a directional blur and a disc blur with edge clamping, a held frame for still, and clips overlapping for styles other than dissolve (wipe, split, hitchcock, whip, flash cut, defocus all overlap by `duration + hold`; fade and still insert time instead).

---

## 18. Not determinable from the source

- The exact kernels of Core Image's `CIMotionBlur` and `CIBokehBlur` (weights, how `inputRadius` maps to extent, ring rendering) and of the Lanczos downscale. Only the parameters are in the source. The original's own preview/export tolerance is an average difference under 9/255 per channel (`TransitionTests.swift:150-151`), which suggests the bar for a visual match.
- Whether Media3's compositor on Android blends in linear light. The original does; this was not checked on a device (no device use allowed for this task).
- The intended capitalisation of the "still" label: the original shows it in lower case (`Transitions.swift:11`), unlike every other style.
