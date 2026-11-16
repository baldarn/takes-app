# Transitions in 0.6: review against the original

An independent review of the transitions shipped in Takes 0.6 (tag `v0.6`, commit `43db294`) against the original iOS app (read at its commit `faf1815`). Both sides were read from source; the existing documents (`docs/TRANSITIONS-SPEC.md`, `docs/TRANSITIONS-MIGRATION.md`, `docs/TRANSITIONS-FIT.md`) were used as maps and their claims checked, not trusted.

Paths starting with `YouRec/` or `Tests/` are in the original iOS app's repository; all others are in this one. `app/…` stands for `app/src/main/kotlin/app/takes/…` and `core/…` for `core/src/main/kotlin/app/takes/core/…`.

How each statement was established:

- **[R]** verified by reading both sides.
- **[P]** verified by running a throwaway probe against the Kotlin core on the host (JDK 17, not committed). The original's side of a probed finding is still read, not run.
- **[I]** inferred: follows from the code read, but depends on behaviour that is not in either repository (Core Image kernels, Media3 internals, a device's GPU) or was not observed on a screen.

Nothing here was run on a device or an emulator. Section 5 lists what that leaves open.

## 1. Verdict

| Area | Verdict | In short |
|---|---|---|
| **Core numbers** (timing, progress, easing, geometry, flash frames, sound ramps) | matches | `TransitionSettings.kt`, `TransitionGeometry.kt`, `TransitionPicture.kt`, `TransitionSound.kt` are line-for-line ports of `YouRec/Transitions.swift:39-164` and of the arithmetic in `YouRec/TransitionRenderer.swift:189-292`. No difference found. [R] |
| **Editor: controls per style** | matches, with exceptions | Same controls, order, ranges, steps and defaults. Exceptions: a still has no audio timing (F5), "Reset framing" leaves the divider (F12), no divider handle on the picture (F12), fade color is swatches plus hex (F15). |
| **Editor: duration maximum, length note, audition** | differs | F8, F13, F14. |
| **Editor: how it opens** | differs, deliberate | Nearest join instead of the join exactly under the playhead (`app/EditorScreen.kt:976-980` against `YouRec/EditorView.swift:1012`, `YouRec/Transitions.swift:254-257`). Known; not treated further. |
| **Picture: dissolve, fade through color, wipe, split screen, Hitchcock, flash cut (30 fps), still** | matches | Same recipe, in linear light. Residual differences are subtle (F18). |
| **Picture: whip pan, defocus** | differs | Fixed-tap approximations of `CIMotionBlur` and `CIBokehBlur` (F2, F3). |
| **Picture: titles during a transition** | differs | Drawn once over the finished frame with an opacity, not through the transition with their clip (F1). |
| **Picture: preview below Android 13** | differs, documented | Five styles are shown as cross-fades; the export is unaffected (F4). |
| **Sound: levels** | matches | Same ramps, same 8 ms minimum, same J/L cuts and split first/second, and preview and export read the same function. [R][P] |
| **Sound: how the levels are applied** | differs, subtle | Preview sets the players' volume once per display frame (F16). |
| **Layout: set, change, remove, split, delete, move, insert/paste, speed, undo** | matches | Within a timeline whose clips always follow one another. Checked against `docs/TRANSITIONS-FIT.md` and against the Swift. |
| **Layout: trim, extend, pause removal, opening and close** | differs | F9, F10, F11. |
| **Export: frame accuracy, 4K, frame-rate conversion** | not verifiable from source | The mechanisms are described in F6, F17, F19, F20; whether they hold needs a device. |
| **Export: HDR** | differs | Always tone-mapped to SDR (F7). |
| **Tests** | differs | The original has pixel tests of every recipe (`Tests/TransitionTests.swift:65-107`) and compares preview with export frame by frame (`:146-151`). The Android shader has none: `app` has no test source set, and the core tests cover the numbers only. |

## 2. Findings, most visible first

Severity: **every use** (seen whenever the feature is used), **specific cases**, **subtle**, **internal**.

### F1. Titles do not go through the transition with their clip — specific cases [R]

- **Original.** Each clip's picture is drawn with its titles before the two are combined, for every style but split screen (`YouRec/VideoToolCompositor.swift:282`, `:409-411`). A title is therefore wiped, keyed, slid and blurred with its picture. For a split screen the clips are drawn without titles and the titles of both go once over the pair (`:298-301`). Subtitles go over the finished frame (`:236`).
- **Android.** Titles are never part of a side. They are drawn once over the finished frame with an opacity, the "share" of their clip (`core/TransitionScene.kt:66-75`, `:113-126`): in the preview by a canvas above the picture (`app/EditorScreen.kt:779-785`), in the export by an overlay after the transition effect (`app/Exporter.kt:280-286`, `app/Titles.kt:64-82`).
- **Difference.** Same for dissolve, fade through color, flash cut, still, split screen. Different for:
  - wipe: the title fades over the whole frame by the eased progress instead of being uncovered or covered by the edge;
  - Hitchcock: it fades instead of being keyed, and it does not take part in the brightness key of the first picture;
  - whip pan: it stays in place and sharp while the picture slides and smears;
  - defocus: it stays sharp over a blurred picture, and changes over between 35 % and 65 % of the transition.
- **Fix.** Draw each side's titles into that side before the shader (a third and fourth sampler, or a per-deck title texture composited in `side()`), and keep the single overlay only for split screen and for subtitles.

### F2. Defocus is 48 samples of a disc, not an aperture blur — every use of the style [R] for the code, [I] for the look

- **Original.** `CIBokehBlur` with radius `min(W, H) · 0.05 · amount · sin(πt)`, ring amount 0.12, ring size 0.1, softness 0.25, on a picture first reduced to at most 960 px on its long side (`YouRec/TransitionRenderer.swift:239-251`, `:268-275`). The comment insists: "no Gaussian approximation".
- **Android.** `disc()` takes 48 samples on a sunflower spiral, the same spiral for every pixel, with a weight of 1.12 on the outer 10 % of the radius (`app/TransitionRendering.kt:150-164`). Softness is not passed to the shader (`:112-115`), and the picture is not reduced.
- **Difference.** The radius is the same (`core/TransitionGeometry.kt:154-160`). The fill of the disc is not: at 1080p and full amount the radius peaks at 54 px, so 48 samples are about 14 px apart; at 4K, 108 px and about 28 px apart. A point of light becomes 48 dots rather than a disc, and a hard edge becomes a stack of 48 copies. Because the pattern is the same at every pixel it reads as structure, not as noise. The change-over window (`(t − 0.35) / 0.3`) and the timing match.
- **Fix.** Blur a reduced copy (the original's `blurScale` is already computed in `core/TransitionPicture.kt:228-231` and unused), in two passes or with a few hundred samples at the reduced size; rotate the spiral per pixel if a single pass is kept.

### F3. Whip pan is 32 evenly spaced samples — every use of the style [R] for the code, [I] for the look

- **Original.** `CIMotionBlur` of radius `min(W, H) · 0.12 · sin(πt)` along the travel, on a reduced picture with its edges extended (`YouRec/TransitionRenderer.swift:223-234`).
- **Android.** `pan()` averages 32 samples spread evenly over ± radius (`app/TransitionRendering.kt:140-147`), at full size.
- **Difference.** Offsets, direction, reverse, axis, easing of the slide and of the mix, and the linear rise and fall of the blur all match (`core/TransitionGeometry.kt:125-132`). The smear does not: at 1080p the radius peaks at 130 px, so samples are 8 px apart; at 4K, 259 px and 16 px apart. A vertical edge at the peak is 32 distinct copies. How `CIMotionBlur` weights its extent is not in the source of either side, so the box shape itself is an assumption.
- **Fix.** As F2: blur a reduced copy, or raise the tap count with the radius.

### F4. Below Android 13 the preview draws five styles as cross-fades — every use on those devices [R]

- **Android.** `shadedPreview` is `SDK_INT >= 33` (`app/TransitionPreview.kt:35`). Below it (`:102-126`, minimum SDK 26):

  | Style | Preview below Android 13 |
  |---|---|
  | Cut, flash cut, still (freeze part) | exact |
  | Dissolve, exit dissolve of a still, fade through color | right weights, mixed on encoded pixels: the middle is darker than the export |
  | Wipe, Hitchcock | a cross-fade by the eased progress |
  | Whip pan | a cross-fade by the eased progress, no slide, no blur |
  | Defocus | sharp first picture until 35 %, cross-fade to 65 %, sharp second picture |
  | Split screen | an even double exposure of the two pictures for the whole transition (shares 1 and 1, normalised, `:110`) |

- **Export.** Does not look at the SDK level: `TransitionEffect` runs the GL shader on every version (`app/TransitionRendering.kt:295-437`, `app/Exporter.kt:297-336`). So the export truly differs from that preview, as the panel's note says (`app/TransitionPanel.kt:226`).
- **Difference from the original.** The original's preview and export are one compositor. On these devices a split screen cannot be framed by eye: the gestures work (`app/EditorScreen.kt:748-778`) on a picture that shows neither half.
- **Fix.** For split screen at least, draw the two halves with plain clipped and scaled views instead of the cross-fade; say in the note that framing is blind.

### F5. A still has no audio timing, and one inherited from another style previews wrongly — specific cases [R][P]

- **Original.** The audio controls are shown for every style, a still included (`YouRec/TransitionEditor.swift:341`), with the note "By default, sound follows the fade or freeze" (`:382`). With custom audio the outgoing clip's sound runs on under the frozen picture (`YouRec/Transitions.swift:120`, `:145-149`).
- **Android.** `AudioTiming` is not shown for a still (`app/TransitionPanel.kt:313`), and the note says "the fade" only (`:516`, string `transition_audio_default_note`). The reason given in the code is that the preview cannot hold the picture while its clip's sound runs on.
- **Difference.** (a) No J-cut, L-cut or blend can be set on a still. (b) Choosing Still keeps an offset or blend set under another style (`core/TransitionLayout.kt:403-411`; probe: a dissolve with offset −0.3 s and blend 0.2 s becomes a still with both), and then neither they nor "Reset" are on screen. (c) With such a still the outgoing clip plays past the start of the freeze (probe: freeze from 3.5 s, clip placed to 4.0 s). The preview keeps running that deck because a clip that is playing wins over the frame it holds (`app/EditorScreen.kt:335-347`, `app/TransitionPreview.kt:93`), so the "frozen" picture moves for that stretch; the export freezes it (`app/TransitionRendering.kt:422-425`). Preview and export disagree.
- **Fix.** Either show the audio controls for a still and make the preview hold the frame (keep the deck's last texture, as the export does), or clear `audioOffset` and `audioBlend` when Still is chosen.

### F6. Flash cut counts frames at 30 per second whatever the export's rate — specific cases [R][P]

- **Original.** `frame = floor(t · length · fps + 10⁻⁶)` and `flashFrames(length, fps)` with the composition's frame rate (`YouRec/VideoToolCompositor.swift:296-297`, `YouRec/TransitionRenderer.swift:235-238`): 30 in the editor (`YouRec/EditorController.swift:521`), the export's setting in the export (`YouRec/MediaRenderer.swift:750`). A flash is always one output frame.
- **Android.** `TransitionScene.FRAMES_PER_SECOND = 30.0` for preview and export alike (`core/TransitionScene.kt:56`, `:97`), while the export offers 24, 25, 30, 50 and 60 (`core/Project.kt:275`).
- **Difference.** Probe, a 0.5 s flash cut with three flashes: frames 6, 11, 13 of 15 at 30 fps. Sampled on other grids:

  | Export rate | Original (single frames) | Android (output frames showing the next clip) |
  |---|---|---|
  | 30 | 6, 11, 13 | 6, 11, 13 |
  | 60 | 13, 22, 27 | 12–13, 22–23, 26–27 (two frames each) |
  | 50 | — | 10–11, 19, 22–23 (two, one, two) |
  | 25 | — | 5, 11 (one flash lost) |
  | 24 | 5, 8, 10 | 5, 9, 11 |

  This assumes output frames on an even grid, which the Android export does not guarantee (F17). The Android choice does keep preview and export alike, which the original's 30 fps editor and 60 fps export are not.
- **Fix.** Pass the export's frame rate to `TransitionScene.moment` in `TransitionEffect`, as the original does, or keep 30 and say so in the panel when the export rate is not 30.

### F7. HDR footage is exported as SDR — every export of HDR footage [R]

- **Original.** The compositor works in extended linear sRGB, half float (`YouRec/VideoToolCompositor.swift:194`), and the movie is HLG BT.2020 when any clip is HDR (`YouRec/TrackRenderer.swift:166-168`). The Hitchcock key clamps HDR values so highlights are revealed before the last frame (`YouRec/TransitionRenderer.swift:215-221`; pixel test at `Tests/TransitionTests.swift:99-100`).
- **Android.** Every export tone-maps to SDR (`app/Exporter.kt:150-151`), the transition program uses 8-bit textures (`app/TransitionRendering.kt:311`, `:385`), and the shader clamps to 0…1 on the way in and out (`:125-132`).
- **Difference.** A whole-app difference, not one of transitions; within transitions it removes the headroom that makes bright points spread as discs in a defocus. The Hitchcock clamp is equivalent once everything is SDR.
- **Fix.** None within transitions; note it in the parity ledger.

### F8. The duration slider ends somewhere else — specific cases [R][P]

- **Original.** The editor reads the layout made from the stored timeline, where `maximum` is the shorter of the two clips' whole lengths, 604 800 s for a missing side (`YouRec/Transitions.swift:294`, `YouRec/TransitionEditor.swift:11`). A duration that does not fit is kept as entered and scaled, with the orange note "reduced to N %" (`:298`). A new transition starts at `min(0.5, maximum)` (`:64`).
- **Android.** `maximum` comes from the sequence layout: the footage the neighbouring transitions leave free, doubled for a fade between two clips (`core/TransitionLayout.kt:191-195`, read at `app/EditorScreen.kt:911`).
- **Difference.** Probe, 4 s clips:

  | Join | Original | Android |
  |---|---|---|
  | Between two clips, dissolve | 4 s | 4 s |
  | Between two clips, fade through color | 4 s | 8 s |
  | Second join of three clips, the first join holding a 1 s dissolve | 4 s (a 4 s request plays at 75 %) | 3 s |
  | Opening | 4 s | 4 s |

  The hold slider inherits the same maximum (`max(10, min(60, maximum))` on both sides). A still is the same on both: `max(60, project, duration)`.
- **Fix.** Decide which is wanted. To match, give `Join.maximum` the shorter clip's length when it is read by the editor (`docs/TRANSITIONS-FIT.md`, open point 5, already records the choice).

### F9. An opening or a close does not stay with its clip — specific cases [R][P]

- **Original.** They are relations with one side missing and belong to their picture. Moved, or with a clip put before the first or after the last, they play on where the picture is (`YouRec/TrackTimeline.swift:881-885`, `YouRec/Transitions.swift:299-322`).
- **Android.** The opening lives only on the first clip and the close only on the last (`core/TimelineEditing.kt:330-337`).
- **Difference.** Probe: a clip inserted at 0 before a clip with an opening fade removes the fade; a clip inserted at the end after a closing fade removes the fade, and deleting that clip does not bring it back. (`docs/TRANSITIONS-FIT.md` section 6.1 describes a take recorded later as leaving the close "inactive"; through `TimelineEditing.insert`, which `Project.addRecording` uses when a place is set, it is removed.)
- **Fix.** Not possible while clips only follow one another without also defining what a fade-out in the middle looks like; record it as a known difference in the panel's text or the ledger.
- **Status.** Fixed: an opening and a close stay with their clip and play where it is, and the render plan takes them by the picture they belong to (`docs/TRACKS-PLAN.md`, N1 and S4r).

### F10. Trim: a transition beside the trimmed edge survives, and one never grows — specific cases [R][P]

- **Original, as its editor does it.** Inwards is a range delete of the trimmed part (`YouRec/EditorView.swift:1241-1258`). The cut gives the first piece the clip's id and moves the leaving relation to the second (`YouRec/TrackTimeline.swift:1015-1016`, `:1059-1061`); the piece removed is the first for a head trim and the second for a tail trim, so the transition on the trimmed side names a picture that is gone and is dropped (`:860`). Outwards is `extend`, which leaves the others in place and lets the prune find a larger factor (`:1618`, `:863-876`). An edge under an overlapping transition has no grip (`:1689-1690`).
- **Android.** `trim` follows the original's other function, the one its tests pin (`YouRec/TrackTimeline.swift:1370-1375`): both transitions of the clip are removed only when together they cost more than the clip is left with (`core/TimelineEditing.kt:270-281`). The grips are hidden as in the original (`app/EditorScreen.kt:1178`, `:1376`).
- **Difference.** Probe: trimming 1 s off the head of a clip with an opening fade keeps the fade (the original removes it); trimming the tail of a clip that fades through color into the next keeps the fade (the original removes it). And an outward trim never lengthens a shortened transition; the original's can. `docs/TRANSITIONS-FIT.md` open point 1 records this choice; it is still a difference a person meets.
- **Fix.** If the original's handle behaviour is wanted: on an inward trim remove the transition on the trimmed side. Otherwise leave and document.

### F11. Removing pauses shortens a transition to its piece — specific cases [R][P]

- **Original.** Pauses are a setting of the clip: it stays one clip, shorter. Its transitions are scaled together only when they cost more than the whole clip now lasts (`YouRec/TrackTimeline.swift:1376-1385`).
- **Android.** The clip becomes separate pieces, and each transition is refitted to the piece it is left on (`core/TimelineEditing.kt:296-320`).
- **Difference.** Probe: two 4 s clips with a 1 s dissolve; a pause from 0.4 s to 1.0 s removed from the second. Android leaves pieces of 0.4 s and 3.0 s and shortens the dissolve to 0.4 s (factor 0.4). The original keeps one 3.4 s clip and the dissolve at 1 s.
- **Fix.** Inherent in pieces; the alternative is to refuse a cut that leaves the first or last piece shorter than the transition it carries, or to drop that sliver.

### F12. Split screen: "Reset framing" leaves the divider, and the divider has no handle — specific cases [R]

- **Original.** `resetFraming` sets both framings and `divider = 0.5` (`YouRec/TransitionEditor.swift:200`). The picture carries a round handle that drags the divider, within 22 pt of it (`:472-475`, `:483-490`), and the note says so (`:335`).
- **Android.** "Reset framing" resets the two framings only (`app/TransitionPanel.kt:308`). The divider moves by its slider only; the note was reworded to "double tap to reset it" (`transition_split_note`).
- **Fix.** Add `divider = 0.5` to the reset; optionally a drag near the divider line in `app/EditorScreen.kt:761-774`.

### F13. The length note of an existing transition is measured from a straight cut — specific cases [R]

- **Original.** "Project x s shorter / longer" compares with `settingTransition(nil)` (`YouRec/TransitionEditor.swift:46-51`), which removes the relation and leaves every picture where it is (`YouRec/Transitions.swift:340-342`). For a transition that already exists the note therefore reads "Project length unchanged" until something is changed.
- **Android.** Compares with `setJoin(saved, join, null)`, which closes the clips to a plain cut (`app/EditorScreen.kt:912`). An existing 1 s dissolve opens with "Project 1.00 s shorter".
- **Fix.** For an existing transition compare with `saved.duration`.
- **Done.** The note of an existing transition compares with the timeline as saved (`lengthChange` in `app/EditorScreen.kt`).

### F14. Audition stops at the end instead of returning, and there is no still or scrubber of its own — subtle [R]

- **Original.** Plays `range − 0.65 … range + 0.65`, and one frame before the end pauses and seeks back to the start (`YouRec/TransitionEditor.swift:66-72`, `:123`). It does not loop. While paused the sheet shows a still at the midpoint, and it has its own position slider (`:259`).
- **Android.** Same range (`app/EditorScreen.kt:915-923`); at the end it pauses and stays there (`:542`). The editor's own timeline is the scrubber and the playhead jumps to the midpoint when the tool opens or the length changes (`:900-902`).
- **Fix.** After the audition, `jump(middle())`.

### F15. Fade color: ten swatches and a hex field — subtle [R]

- **Original.** The system colour picker without opacity; black is stored as "no colour" (`YouRec/TransitionEditor.swift:272-280`).
- **Android.** Ten fixed colours and six hex digits; black stored the same way (`app/TransitionPanel.kt:147-149`, `:227`, `:448-475`). Any sRGB colour can be reached; there is no wheel and no eyedropper.

### F16. Preview sound follows the levels once per display frame — subtle [R] for the code, [I] for what is heard

- **Original.** One envelope applied per sample by an audio tap, in the editor and in the export (`YouRec/CutAudioEnvelope.swift:30-38`, `YouRec/TrackRenderer.swift:271-295`).
- **Android.** Export: a processor that reads `TimelineEditing.soundLevel` every 64 samples (`app/Titles.kt:117-150`, `app/Exporter.kt:219`). Preview: the same function sets each deck's volume when the playhead moves, once per display frame (`app/EditorScreen.kt:632-650`), with a loudness enhancer above 1.
- **Difference.** The levels agree (probe: a split screen keeping the first clip's sound holds 1/0 until 8 ms before the end, 0.5/0.5 at 4 ms, 0/1 at the end; a still's two cuts ramp over the 8 ms before and after the freeze). In the preview an 8 ms ramp is at most one step, so "Cut", "First clip", "Second clip" and a still can click where the export does not.
- **Outside transitions, for context.** The original also fades 8 ms at every plain cut (`YouRec/TrackRenderer.swift:291`); Android's `soundLevel` has no such fade (`core/TimelineEditing.kt:561-568`). Inside a transition the ramps already reach zero at every clip edge on both sides, so this changes nothing there.

### F17. The export has no frame grid of its own — internal [R] for the code, [I] for the effect

- **Original.** Every output frame is composed at `n / fps` (`YouRec/TrackRenderer.swift:164`); the held frame of a still is computed on that grid and decoded at that source time (`:111-120`).
- **Android.** Frames arrive at the footage's own times; a ceiling drops frames for rates up to 30 and nothing is added for 50 or 60 (`app/Exporter.kt:86-113`, `:202`). The transition effect is evaluated at each arriving frame's time plus one microsecond (`app/TransitionRendering.kt:416-420`). A still shows the last frame that actually arrived in the 0.2 s before the freeze, kept in a texture (`:299-326`, `:422-427`), not the frame `heldFrame` names (`core/TransitionPicture.kt:250-258`), which only the preview seeks to.
- **Difference.** Progress is exact for the time of each frame; which times exist depends on the footage and, with two sequences, on how Media3 pairs their frames. Preview and export can hold frames one apart on footage that is not 30 fps.

### F18. Linearisation, precision — subtle [I]

- The shader decodes and encodes with the sRGB curve (`app/TransitionRendering.kt:125-132`). The export's textures are in Media3's default SDR working space, which is encoded with the BT.709 / SMPTE 170M curve, and Core Image linearises BT.709 video with that curve. Mid-transition weights therefore differ slightly from the original; ends are untouched (mode 0 returns the pixel as it is, `:168`).
- 8-bit intermediates against half float: possible banding in long dissolves of dark pictures.
- Alpha: the shader reads and writes opaque colour only; a transparent deck area counts as black, which is what the original composites over (`YouRec/VideoToolCompositor.swift:162`). No premultiplication error is possible.

### F19. Two sequences are drawn into a frame twice as tall — internal [R] for the code, [I] for the limit

`DecksCompositor` makes the output `width × 2·height` (`app/TransitionRendering.kt:281-287`). A 4K landscape export with any overlapping transition needs a 3840 × 4320 texture, a portrait one 2160 × 7680. Nothing in `app/Exporter.kt` checks the GPU's largest texture; a device limited to 4096 would fail, with Media3's error shown as "export failed". The original has no such stage.

### F20. The kept frame of a still depends on a frame arriving in a 0.2 s window — internal [R] for the code, [I] for the case

`KEEP_WINDOW` is 0.2 s (`app/TransitionRendering.kt:446-447`), and a frame black at five sample points counts as "no frame" (`:400-404`). Footage whose frames are further apart than 0.2 s of movie time (variable frame rate imports with a static picture; under 20 fps at 0.25×) keeps nothing, and the freeze then shows the live deck, which is black once its clip has ended.

### F21. Smaller points [R] unless marked

- **Split at the very start of a transition.** The original's editor refuses on the half-open range (`YouRec/EditorView.swift:1097`) and its function within 10⁻⁵ s of the ends (`YouRec/TimelineEditing.swift:48`); it also rounds the cut to 1/30 s (`:42`, `:47`). Android refuses strictly inside only and cuts where asked (`core/TimelineEditing.kt:166`). [P]: a split exactly at `range.start` succeeds.
- **Step buttons.** "− 0.1 s", "+ 0.1 s" and "0 s" are literals (`app/TransitionPanel.kt:489-491`, `:503`); every other number follows the locale, so Italian shows "0.1 s" beside "0,10 s". The original formats them (`YouRec/TransitionEditor.swift:367-371`).
- **Extra on Android.** Previous / next join buttons (`app/TransitionPanel.kt:211-214`), the "simplified preview" note, the "not enough footage" note (the original disables Apply silently, `YouRec/TransitionEditor.swift:41`).
- **Debounce.** A change of length is shown after 150 ms (`app/EditorScreen.kt:904-907`); the original rebuilds after 200 ms (`YouRec/TransitionEditor.swift:118`).
- **`docs/PARITY.md` section 6** still lists seven styles as "core only", the opening and close as missing and the sound controls as core only. It describes a state before 0.6.

## 3. Checked and found the same

### 3.1 Editor controls, in the original's order

`YouRec/TransitionEditor.swift:267-349` against `app/TransitionPanel.kt:216-318`. "Same" means label, range, step and default agree.

| Control | Shown for | Original | Android |
|---|---|---|---|
| Style | all | sheet, two columns, nine styles in `allCases` order, "Done" (`:417-431`) | dialog, two columns, same order, "Done" (`:322-353`) — same |
| Fade color | fade through color | colour picker | swatches + hex (F15) |
| Blend / Freeze duration | all | presets 0.5, 1…10, 15, 30, 60 s; slider one frame … maximum in frames; disabled under two frames | same presets (`core/TitleMotion.kt:132-134`), same slider (`:236-241`); maximum differs (F8) |
| Length note, "reduced to N %" | all | `:297-298` | same (F13, fixed) |
| Layout: Left / right, Top / bottom | split screen, whip pan | `:300-302`; a new transition starts top / bottom on a portrait canvas (`:106`) | same (`:247-249`, `core/TransitionLayout.kt:392-395`) |
| Reverse direction | whip pan | `:303` | same |
| Edge: Soft / Hard; Angle −180…180°, step 1 | wipe | `:304-308`; setting the angle clears `reverse` | same (`:251-259`) |
| Reveal: Shadows first / Highlights first | Hitchcock | `:309-311` | same |
| Flashes 1…20 (default 3), Acceleration 0.25…4 step 0.05 (default 2), "n flashes fit" | flash cut | `:312-319` | same (`:266-281`) |
| After the freeze: Cut / Dissolve (0.3 s); Exit dissolve one frame … 5 s | still | `:320-326` | same (`:282-290`) |
| Optical blur 10…100 % (default 75 %) | defocus | `:327-330`, continuous | same, in steps of 1 % |
| Hold on color / Hold at halfway, 0 … max(10, min(60, maximum)) | all but flash cut and still | `:331-333` | same (`:295-301`) |
| Divider 15…85 %, Audio (Crossfade / First clip / Second clip), Reset framing | split screen | `:334-340` | same but for the reset (F12); choosing an audio side clears offset and blend on both |
| Audio timing: Reset, Start offset ± audio maximum, −0.1 / 0 / +0.1, Audio blend, −0.1 / Cut / +0.1, diagram | all | `:361-387`; audio maximum `min(60, max(2, longest clip))` (`:14`) | same for eight styles (`:483-550`, `core/TransitionLayout.kt:418`); absent for a still (F5) |
| Remove transition, "Restores a straight cut…" | existing | `restoringStraightCut` (`:76-78`, `:342-347`) | clears the join, which in a sequence is the straight cut (`app/EditorScreen.kt:933-938`) — same |

First choice of a style: a whip pan becomes 8/30 s and a still 1 s, once per opening of the tool, the style it opened with counting as visited (`YouRec/TransitionEditor.swift:52-59`, `:65`; `core/TransitionLayout.kt:403-411`, `app/TransitionPanel.kt:179`). Same.

Opening and close: all nine styles are offered on both sides; a fade through color there is drawn as a dissolve from or to the colour, the audio blend defaults to duration plus hold, one half of a split screen cannot be framed. Same (`YouRec/TransitionEditor.swift:15-17`, `:181`, `:247`; `core/TransitionPicture.kt:132-133`, `core/TransitionLayout.kt:421-422`, `app/EditorScreen.kt:753-755`).

### 3.2 Strings

The original is in English only; the Italian strings are Android's own and read correctly against the English ones. English against the original's labels:

| Original | Android | |
|---|---|---|
| "Flash Cut", "still" (`YouRec/Transitions.swift:11`) | "Flash cut", "Still" | capitalisation only; the core keeps the original's (`core/TransitionSettings.kt:31-34`) |
| "Drag each picture to pan · pinch to zoom. Drag the handle to move the divider." | "Drag each picture to pan · pinch to zoom · double tap to reset it." | follows F12 |
| "By default, sound follows the fade or freeze. …" | "By default, sound follows the fade. …" | follows F5 |
| "Preparing preview…", "Retry", "Transition preview position", "Frame split screen" | none | the sheet's own preview does not exist here |
| — | "Previous join", "Next join", "The preview of this style is simplified…", "There is not enough footage here…", "Fewer flashes", "More flashes", "Hex" | Android only |

Every other label in `app/src/main/res/values/strings.xml:310-395` is the original's text.

### 3.3 Picture, style by style

`YouRec/TransitionRenderer.swift:189-267` against the shader body (`app/TransitionRendering.kt:124-203`), the numbers it is given (`:61-117`) and where they come from (`core/TransitionPicture.kt`, `core/TransitionGeometry.kt`).

| Style | Easing | Recipe | Verdict |
|---|---|---|---|
| Dissolve | smoothstep | `mix(first, second, eased)` in linear light | same |
| Fade through color | linear | first → colour over the first half, colour → second over the second; the colour alone at the midpoint and for a hold; at the opening and close a dissolve with the colour | same |
| Wipe | smoothstep | band of half-width `max(1, 3.5 % of the span)` or 0.5 px, starting and ending wholly off the canvas, second behind the edge; angle clockwise on screen | same; y is turned once, at `:79` |
| Split screen | linear | each clip without its bars framed in its half over black, second half over first, a line of 0.25 grey `max(1, 0.2 %)` wide; 15 % blend in and out | same; content rectangles agree (`YouRec/TransitionRenderer.swift:293-297`, `app/Exporter.kt:289-295`, `app/EditorScreen.kt:724-731`) |
| Hitchcock | smoothstep | key from the first picture's encoded brightness, Rec. 709 weights, feather 0.08, threshold from −0.08 to 1.08 | same in SDR |
| Whip pan | smoothstep slide and mix, linear blur | slide 65 % of the axis, blur radius 12 % of the short side at the midpoint | geometry same, blur differs (F3) |
| Flash cut | none | whole frames | same at 30 fps (F6) |
| Still | smoothstep exit | last frame shown before the freeze, held; then cut or dissolve | same recipe; the frame is chosen differently in the export (F17) |
| Defocus | linear | radius 5 % of the short side × amount at the midpoint; change-over from 35 % to 65 % | radius and window same, blur differs (F2) |

At progress 0 and 1 both sides return the first or the second picture untouched (`YouRec/TransitionRenderer.swift:192`; `core/TransitionPicture.kt:153-155`, shader mode 0). A missing side is the fade's colour for a fade and black otherwise (`:184-187`; `core/TransitionPicture.kt:124-125`). Crop, color and speed are applied to each clip before the transition on both sides; clips of another shape are fitted with black bars that take part in the transition on both. Rotation and mirror of a clip do not exist in the Android app.

### 3.4 Sound

`TransitionTiming` gives the same ramps on both sides (`YouRec/Transitions.swift:127-154`, `core/TransitionSettings.kt:264-303`), and the level along a ramp is the same expression with the same 8 ms floor (`YouRec/CutAudioEnvelope.swift:36-37`, `core/TransitionSound.kt:27-32`). The ramps are multiplied into the clip's own level and fades on both sides. Only sound that follows its picture is shaped, on both. Preview and export on Android call the same `TimelineEditing.soundLevel` (`core/TimelineEditing.kt:561-568`), including for noise-reduced sound (`app/Exporter.kt:270`).

### 3.5 Layout and edits

`core/TimelineEditing.kt` against `docs/TRANSITIONS-FIT.md` and the Swift it cites. The citations of that document's sections 1 to 4 were spot-checked (`YouRec/TrackTimeline.swift:847-899`, `:1339-1437`, `:1007-1061`, `:771-806`; `YouRec/Transitions.swift:335-417`; `YouRec/TimelinePaste.swift:176-190`) and hold.

| Edit | Original | Android | |
|---|---|---|---|
| Set or change | fitted from factor 1 into what the far-side transitions leave, 48 halvings, nothing under a frame (`Transitions.swift:349-370`) | `setJoin` (`:366-382`), `TransitionLayout.fit` | same; the halvings omit the 10⁻⁸ s slack, as documented |
| Remove | straight cut | same | same |
| Split | refused inside; the leaving transition goes with the second piece, no factor changes | `:160-180` | same but for the edge of the range (F21) |
| Delete, move | a transition naming a removed or separated picture is dropped; others keep their fit | `:183-194`, `:330-337` | same |
| Insert, paste | on the join: between the clips, transition removed; inside: every crossing transition removed, then cut | `:207-247` | same |
| Speed | both transitions of the clip scaled together when they no longer fit; never grown | `:663-669`, `:88-104` | same |
| Undo, redo | a step is the whole state, fit included | fits are fields of the clip | same |
| Trim, extend | F10 | | differs |
| Pauses | F11 | | differs |
| Opening, close | F9 | | differs |

Probe figures: three 4 s clips with a 1 s dissolve at the first join leave 3 s free at the second; a fade of 8 s between two 4 s clips fits whole and keeps the project at 8 s, as the original's 1 s fade does (`Tests/TransitionTests.swift:43-45`).

## 4. Suggested order of work

1. F5 (b) and (c): a still with inherited audio timing shows one thing and exports another. Smallest fix: clear the two fields when Still is chosen.
2. F12 and F13: one line each.
3. F1: titles through wipe, whip pan, defocus and Hitchcock.
4. F2 and F3: blur quality, judged on a device first.
5. F6: decide between "preview equals export" and "one frame per flash".
6. F8, F10: decide and record.

## 5. Not determined without a device

- How the defocus and whip pan actually look beside the original's (F2, F3), and whether the sample patterns are visible at phone size.
- Whether the first and last frame of every clip reach the export, and the exact frame a still holds: the code's own comments describe a Media3 behaviour it works around (`app/Exporter.kt:86-91`, `app/TransitionRendering.kt:323-326`).
- How Media3's compositor pairs the frames of the two sequences in time, and what it emits while both are in a gap (a hold on a color, a freeze between clips).
- The largest texture on real devices and whether a 4K export with an overlapping transition completes (F19).
- The orientation of the export's samplers and of the stacked frame (the flips at `app/TransitionRendering.kt:227-234`): consistent on reading, unproven without a frame.
- Whether the preview's two players stay in step through a transition, and whether 8 ms ramps click in the preview (F16).
- The offset of a clip's first decoded audio sample from its cut, which shifts every ramp of the export by the same amount.
- The transfer function Media3 hands the shader, and the one Core Image uses for the same file (F18).
- HDR footage in the preview on Android 13 and later.
- The original's behaviour was read, not run: its tests and its editor were not executed for this review.
