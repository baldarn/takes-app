# Adopting the original's track timeline: plan

Written 2026-11-15. A plan, not a specification of code: nothing in `core` or `app` changes with this document.

The decision it plans is taken: Android gives up the gapless sequence of clips plus two sound tracks and adopts the original's independent-tracks timeline, by porting it and following the original's own migration path, in stages, starting after release 0.7. Whether to do it is not discussed here.

**What was read.** The original iOS app at its commit `faf1815`; this repository's `main` at `d54dc8a` (0.6 plus documents).

**How places are cited.**

- `T:n` is line n of `YouRec/TrackTimeline.swift` in the original's repository, the file cited most.
- `O/…` = `YouRec/…`, `OT/…` = `Tests/…`, and `OD:n` is line n of `docs/EDITOR-TRACKS-DESIGN.md`, all in the original's repository.
- `C/…` = `core/src/main/kotlin/app/takes/core/…`, `CT/…` = `core/src/test/kotlin/app/takes/core/…`, `A/…` = `app/src/main/kotlin/app/takes/…`, in this one.
- A bare `:n` continues the file named just before it. A table may say whose lines its bare numbers are.

**How each statement was established.** [R] read in the source cited; this is the default and is not marked. [P] a throwaway probe on the host, not committed. [E] an estimate. Nothing was run on an emulator or a phone, and the original's tests were read, not run. Section 8 lists what could not be determined.

Two probes are used below:

- `./gradlew :core:test` on `main` at `d54dc8a`: **973 tests, 0 failures**, in 48 test files. This is the suite the stages must keep green. [P]
- The arithmetic of the original's conversion rule on 2,000 random sequences: with clip lengths that are not on the 1/30 s grid, **23.9 % of plain cuts come out one tick apart** (half gaps, half overlaps); with lengths on the grid, none. Section 3.2 says why that matters and what to do. [P]

## 0. In one page

**What changes.** The saved edit stops being `clips` + `audio` (`C/Project.kt:201-211`) and becomes one `timeline` object: picture items and sound items, each at its own place on a clock of 240 000 ticks per second, with links, bookmarks, placements and transitions stored as relations between items by id (`T:487-503`). Everything the two apps differ in today because of the model follows from that: layers and Placement, Unlink/Link that leaves gaps, Move with scopes, paste targets, opening and closing transitions that stay on their clip, range edges inside transitions, censor and enhancement regions and caption words on sound items, any number of overlapping sounds.

**Where Android cannot simply follow the original.** The original waived compatibility: "The user has waived legacy compatibility: do not maintain parallel old/new editing engines. Any development-format conversion must be explicit and limited" (`OD:396-399`). Its one-time conversion (`O/Project.swift:175-185`) only ever met development projects. Android has shipped six versions; every project of every user goes through the conversion, and it must cover what the original's old format never had (section 3.2). That, and the renderer (section 5), are the two places where this is more than a port.

**Stages** (sizes: S hours, M a day, L several days; days are working days of one person, [E]):

| Stage | Result | Visible | Size | Days |
|---|---|---|---|---|
| S0 | Golden projects, and the arrangement of preview and export lifted into a core "render plan" | no | L | 2–3 |
| S1 | The model, conversion both ways, invariants, in new core files | no | L | 4–6 |
| S2 | The editing operations on the model; the present functions become wrappers where they agree | no | L | 10–15 |
| S3a | Preview and export are built from the converted timeline; the saved format is unchanged | no | L | 2–3 |
| S3b | The app saves the timeline (schema 16: 14 was taken after this plan by the rotated and animated crop, 15 by fades of any length and fades cut by a split) | no | L | 4–6 |
| S4 | Transitions stay with their clips; trim as the original; range edges inside transitions | yes | M–L | 2–4 |
| S5 | Sound lanes: any number of sounds, mixed by one audio engine | yes | L | 8–12 |
| S6 | Selecting picture or sound, Unlink/Link, gaps, Close gap, Restore sync | yes | L | 6–10 |
| S7 | Enhance audio and Censor as regions of a sound | yes | L | 7–11 |
| S8 | Captions owned by sound items; transcribing the mix, or a marked range | yes | M–L | 3–5 |
| S9 | Picture layers: overlapping video, the upper one covering | yes | L | 8–12 |
| S10 | Move mode with scopes and snapping | yes | L | 6–10 |
| S11 | Placement: side by side, picture in picture, edge crop, order | yes | L | 8–12 |
| S12 | Paste targets, adding video in a gap, bookmarks anywhere | yes | M–L | 3–5 |

About 75 to 115 days in all [E]. S1 and S2 are pure core in new files and split well between parallel workers; from S5 on most of the work is in `app` and in listening and looking on a phone.

**The three largest risks**, with section 7 for the rest:

1. An older version of the app does not refuse a newer project: it falls back to an older manifest revision and opens the project as it was before (`C/ProjectDisk.kt:42-54`). After five more saves the newer work is gone. This exists today between any two schema steps; the switch makes every edited project cross one. The guard is in 3.4.
2. The renderer. Media3 has never been run here with more than two video sequences, and the preview's sound is two players whose volume is set once per display frame (`A/EditorScreen.kt:242-250`, `:633-649`). Layers and mixed sound need more than a port (section 5).
3. Size and speed of the saved history. A plain clip takes about 7.5 times the bytes in the track form [E], and every save re-encodes, re-reads and re-hashes every step of the history (`C/ProjectDisk.kt:34-37`).

**Decisions for the product owner** are in 7.3, each with a recommended answer.

## 1. The original's model

### 1.1 The clock

- `EditTime` is a whole number of ticks; 240 000 ticks make a second (`T:5-15`). That is exact for 48 kHz sound (5 ticks a sample) and for 24, 25, 30, 50, 60 fps and their 1000/1001 variants (`OD:292-299`; `OT/TrackTimelineTests.swift:122-125`).
- From seconds: limited to ±604 800 s, multiplied, rounded half away from zero; a value that is not a number becomes 0 (`T:9`).
- `EditInterval` is half-open: `start ≤ t < end` (`T:17-30`). It is empty when `end ≤ start`.
- Source positions stay in seconds as `Double` and are never put on the tick clock (`T:32-44`).
- "Gaps are empty time, not fabricated black/silent source files. Duration is the maximum end across both audio and video items" (`OD:300-302`). The code also counts the stretch of a transition (`T:503`).

### 1.2 What is stored

Every type below is `Codable` with synthesized coding, so a field that is not optional is always written and is required when reading, even when it is declared with a default. A `?` marks an optional key, left out when nil.

| Type | JSON key | Type and unit | Notes |
|---|---|---|---|
| `EditTime` (`T:5-15`) | `ticks` | integer | |
| `EditInterval` (`T:17-30`) | `start`, `end` | `EditTime` | |
| `PlaybackRun` (`T:34-44`) | `duration` | `EditTime`, local | Length of the run on the timeline. |
| | `sourceStart`, `sourceEnd` | seconds of the source | Equal ends are a hold: one frame kept, no sound. Speed is `(sourceEnd − sourceStart) / duration`. |
| | `silent` | bool | True where the source's sound is muted for this stretch. |
| `SourcePlaybackMap` (`T:45-109`) | `runs` | `[PlaybackRun]` | The item's length is the sum of the runs' ticks. Not empty in a valid timeline. |
| `VideoTimelineItem` (`T:111-125`) | `occurrenceID` | UUID | Shared by the pieces of one split; a copy gets a new one. |
| | `start` | `EditTime`, project | |
| | `content` | `TimelineClip` | The picture's recipe: source, source bounds, speed, color, crop, titles and the rest (`O/Project.swift:42-61`). Its `transition` and `openingTransition` are always nil here (`T:513`, `T:1354`). **The item's id is `content.id`** and is not stored again (`T:112`). |
| | `layer` | integer | Stacking order, higher in front; equal layers are ordered by id (`T:740-742`). |
| | `localOrigin` | `EditTime` | How far into its occurrence this piece begins (`T:1016`, `T:1646`). |
| | `mapping` | `SourcePlaybackMap` | The timing in force. Derived from `content` when made (`T:121`) and whenever the recipe's timing changes (`T:1355-1359`). |
| `AudioTimelineItem` (`T:166-212`) | `id`, `occurrenceID`, `sourceID` | UUID | |
| | `start`, `mapping`, `localOrigin` | as above | |
| | `followsVideo`? | UUID | The picture whose timing this sound copies. Only inside a link with that picture, with the same start and length (`T:1227-1230`). |
| | `volumeDB` | number, −60…12 | |
| | `muted` | bool | |
| | `fadeIn`?, `fadeOut`? | `AudioEdgeFade` | |
| | `enhancements` | `[AudioEnhancementRegion]` | Always written, empty when none. |
| | `censorRegions`? | `[AudioCensorRegion]` | Absent when none (`T:179-183`). |
| | `subtitleWords`? | `[SubtitleWord]` | Times in source seconds (`O/Subtitles.swift:7-13`). |
| `AudioEdgeFade` (`T:133-151`) | `seconds`, `elapsed` | seconds | `0 < seconds ≤ 604 800`, `0 ≤ elapsed < seconds`. `elapsed` is the part of the fade already behind the edge after a split. |
| `AudioEnhancementRegion` (`T:126-131`) | `id`, `interval`, `effects` | `interval` in the item's own ticks | One ordered chain per stretch. |
| `AudioCensorRegion` (`O/AudioCensor.swift:52-66`) | `id`, `interval`, `phaseOrigin`, `settings`, `envelopeInterval`? | the item's own ticks | Pieces of one censor share id, clock and envelope. |
| `TimelineLink` (`T:224-227`) | `id`, `members` | a set of item ids | At least two; an item is in at most one link (`T:1185-1189`). |
| `TimelineBookmark` (`T:346-350`) | `id`, `label`, `anchor` | `label` at most 500 characters | |
| `BookmarkAnchor` (`T:341-345`) | one of `source`, `local`, `project` | | `source(occurrence, source, time)`: a source second of an occurrence. `local(occurrence, offset)`: a tick inside a hold. `project(time)`: a tick of the timeline. |
| `VideoPlacement` (`T:377-388`) | `x`, `y`, `width`, `height` | fractions of the canvas | Center and size of the window, y from the top. |
| | `framing` | `{x, y, zoom}` | |
| | `fill`, `order` | bool, integer | |
| | `motion`?, `edgeCrop`? | | `edgeCrop` is `{left, right, top, bottom}`, fractions of the picture (`T:360-376`). |
| `VideoPlacementRegion` (`T:457-461`) | `id`, `participants`, `videos` | a set of picture ids; a placement per picture id | Belongs to exactly that set of pictures; where they overlap is derived. |
| `TimelineTransition` (`T:462-470`) | `outgoing`?, `incoming`? | picture ids | Opening: only `incoming`. Close: only `outgoing`. |
| | `settings` | `ClipTransition` | Its `id` is the relation's id. |
| | `origin` | `EditTime` | Where the picture effect starts. |
| | `factor` | number 0…1 | The fit. |
| `TimelineGeneratedRegion` (`T:471-476`) | `id`, `owner`, `interval`, `freezeVideo`? | | The stretch a transition occupies; `freezeVideo` is the picture a still holds. |
| `TrackTimeline` (`T:487-503`) | `video`, `audio`, `links`, `bookmarks`, `placements`, `transitions`, `generated` | arrays | The coding keys are listed at `T:490`. |
| | `subtitles`? | `SubtitleSettings` | |
| `EditState` (`O/Project.swift:141-192`) | `timeline` | | Written always. |
| | `outputSize`?, `canvasReference`? | | |

`EditState` reads `timeline` when present and otherwise `clips` (and `effects`), converting once (`O/Project.swift:175-185`); it never writes `clips` again (`O/Project.swift:186-191`). `EditState.clips` survives as a computed view (`O/Project.swift:159-162`): reading projects the pictures back to clips (`T:1259-1267`), writing merges changed clips into the timeline (`T:1339-1437`). The source calls that "Compatibility at the *tool API*, not a second saved timeline" (`T:1337-1338`).

`TrackTimeline.captureBase` is runtime provenance and is not coded (`T:488-490`).

### 1.3 Stored, yet recomputed

Three stored things are rewritten from the rest by `pruneRelations` (`T:847-899`), which every edit ends with:

- a transition's `origin` (`T:879`, `T:883-884`); its `factor` only when its two pictures no longer overlap as it says (`T:863-876`);
- every `generated` region (`T:889-895`);
- `followsVideo`, cleared when no link holds both (`T:896-898`).

Reading a document also re-applies source mute to the maps and keeps one transition per slot (`O/Project.swift:179`).

### 1.4 Derived, never stored

| What | Where | Used by |
|---|---|---|
| `CompiledTimelineInterval`: for each stretch between two edges, the pictures showing (back to front), the sounds playing, the placement and the transitions | `compile()`, `T:969-1000` | renderer (`O/TrackRenderer.swift:140-161`), navigation (`O/TimelineNavigation.swift:49`), tool regions (`O/VideoTools.swift:612`) |
| `TimelineLane`: each item's row, its visible card and its full extent | `timelineLanes`, `T:1679-1778` | drawing, hit testing, accessibility (`O/EditorTimeline.swift:55`, `:272`, `:530`) |
| `TimelineLayout`: joins and placements in playing order | `O/Transitions.swift:271-332` | transition editor, paste targets, lanes |
| `ResolvedBookmark` | `T:901-914` | bookmark buttons, snapping |
| Projected clips | `T:1259-1267` | every tool that edits a clip's recipe |
| Captions | `O/Subtitles.swift:155-180` | renderer, subtitle editor |
| Censor spans, with which sound carries the tone | `O/AudioCensor.swift:114-130` | audio mix |
| `TimelineSelection`, move choices and boundaries, clipboard, viewport | `T:228-239`, `T:253-330`, `T:1457-1461`, `T:1442-1455` | editor state |
| Paste targets | `O/TimelinePaste.swift:5-36` | editor state |

### 1.5 Invariants

`validate(sources:)` (`T:1175-1248`) refuses a timeline unless:

1. Item ids are unique across pictures and sounds; the duration is within one week (`T:1176-1177`).
2. Every map has runs; every run is longer than zero with `0 ≤ sourceStart ≤ sourceEnd ≤ source length + 0.001` (`T:1179-1182`).
3. No item starts before zero; levels are within −60…12 dB; fades are valid (`T:1183-1184`).
4. Links have at least two members, all existing, and no item is in two links (`T:1185-1189`).
5. Caption settings are valid; an item has at most 200 000 words with unique ids, text of 1…4000 characters, times inside the source (`T:1190-1195`).
6. Ids are unique among links, among bookmarks, among placements and among transitions (`T:1196-1198`).
7. A placement names more than one picture, all existing, with exactly one window each, inside the ranges at `T:1203-1209`.
8. A picture has at most one transition in and one out (`T:1212-1213`); a transition names at least one existing picture, valid settings and a factor in 0…1 (`T:1214-1218`).
9. Bookmarks point inside their source or inside one week (`T:1219-1225`).
10. A sound that follows a picture is linked to it and has its start and length (`T:1227-1230`).
11. Censor regions and enhancement regions are in order, do not overlap, lie inside the item and are valid (`T:1232-1246`).

Not checked, by design: gaps, overlaps, layer numbers, whether a placement's pictures still overlap or a transition's pictures still meet. Those are repaired by the prune, not refused.

### 1.6 Operations

| Area | Function | Lines | In short |
|---|---|---|---|
| **Construction** | `init(clips:effects:pictureOnlySources:)` | `T:510-534` | Sequence layout, then one picture, one following sound and one link per clip; transitions become relations. |
| | `counterpart` | `T:535-539` | The sound's id from the clip's id, so converting twice agrees. |
| | `SourcePlaybackMap(clip)` | `T:49-64` | Runs from the clip's speed spans, rounding cumulative boundaries. |
| | appended takes | `O/Project.swift:255-268` | A take recorded later is laid at the timeline's end on a new layer. |
| | `includeCaptureBookmarks` | `T:1463-1469` | Marks made while recording become source bookmarks. |
| **Selection** | `expanded`, `selection(of:)`, `selection(ids:)`, `entireSelection`, `toggling`, `interval(for:)` | `T:540-574` | A selection is picture ids, sound ids and an optional range; a link selects together. |
| **Split and scope** | `split(at:selection:)` | `T:1007-1079` | Cuts the chosen items at a tick; links, followers, transitions and placements follow the pieces. |
| | `scoped` | `T:1080-1094` | Cuts at both ends of a range and returns the pieces inside. |
| | `remapping(_:from:)` | `T:1097-1144` | Finds a marked scope again after a tool cut or retimed its items. |
| | `AudioTimelineItem.sliced` | `T:196-211` | Fades, censors and enhancements of a piece. |
| **Delete** | `remove(_:preserving:)` | `T:771-827` | Complete linked groups close their gap; a lone picture or sound leaves one. Returns where the playhead goes. |
| | `gap(at:)`, `closeGap(at:)` | `T:1588-1604` | Removes time that is empty on every lane. |
| **Insert** | `insertTime` | `T:1145-1161` | Cuts what crosses a tick and moves everything after it. |
| | `TimelineEditing.inserting` | `O/TimelineEditing.swift:86-127` | New clips with fresh identities, at the playhead or at a site. |
| | `VideoInsertionSite.placement`, `videoCoverage` | `O/TimelineEditing.swift:3-39` | Start, gap or end; free picture time is used before anything moves. |
| | `insertAudio` | `T:217-222` | A sound laid over what is there; nothing moves. |
| **Trim** | `extend` | `T:1618-1675` | Reveals trimmed footage; nothing else moves, so it may create an overlap. |
| | inward trim | `O/EditorView.swift:1241-1258` | A range delete of the trimmed part. |
| **Speed, pauses, recipes** | `replaceVideoContents` | `T:1339-1437` | Merges edited clips. A length change ripples the linked sequence after it; a concurrent overlay is not downstream (`T:1401-1415`). Transitions that no longer fit are removed after a trim and scaled together after a speed change (`T:1370-1385`). |
| | `synchronizeFollowers` | `T:1275-1336` | Following sounds take their picture's timing; fades, enhancements and censors are carried through the new map. |
| | `restoreSourceMute` | `T:1268-1274` | |
| **Move** | `moveChoices` | `T:657-699` | The four scopes: selected, with the left side, with the right side, both. |
| | `move` | `T:703-725` | One offset for the whole group, clamped at zero; a new overlap comes to the front (`T:728-739`). |
| | `TimelineMoveBoundaries` | `T:301-330` | The stops a moved group can be placed on. |
| | `snapTarget`, `snappedMove` | `T:1470-1528` | Snapping measured on screen, with a held snap. |
| **Link** | `unlink` | `T:575-579` | Removes the link and `followsVideo`; nothing else. |
| | `linkPlan`, `joining` | `T:583-639` | Each side must be one unbroken run; where picture and sound cover different times, linking their intersection needs a confirmation. |
| | `syncOffset`, `restoreSync` | `T:1605-1615` | |
| **Sound** | `setVolume`, `setFade` | `T:743-745`, `T:191-195` | |
| | `enhance` | `T:746-766` | A chain on a selection or a range; regions under it are cut back. |
| | `censor`, `censorSpans` | `O/AudioCensor.swift:92-130` | |
| **Transitions** | `settingTransition`, `restoringStraightCut` | `O/Transitions.swift:335-418` | Slot rule, fit, origin, and which items move. |
| | `retainUniqueTransitionSlots`, `pruneRelations` | `T:836-899` | |
| **Placement** | `placementSeed`, `retainPlacementAcrossNewOverlaps`, `placementPreviewInterval` | `T:917-968` | A new overlap inherits the windows of the pictures it shares with an old one. |
| | `VideoPlacement` geometry | `T:377-456` | |
| **Bookmarks** | `addBookmark`, `resolvedBookmarks` | `T:1162-1174`, `T:901-914` | |
| **Clipboard** | `copied`, `paste` | `T:1529-1584` | Every paste gets fresh ids for items, recipes, words, regions and relations. |
| | `pasteTargets`, `clipboardFragment`, `cutToClipboard`, `preparePaste` | `O/TimelinePaste.swift:39-194` | Picture sequence, sound sequence or project edge as the destination. |
| **Captions** | cues, fingerprint, regroup, correct, install | `O/Subtitles.swift:154-247` | A word goes to the loudest unmuted sound playing at its middle (`O/Subtitles.swift:219-238`). |
| **Compile and lanes** | `compile`, `timelineLanes` | `T:969-1000`, `T:1679-1778` | |

### 1.7 Schema numbers of the original

`Project.schemaVersion` only ever rises. The numbers the current source assigns; the lines are those of `O/Project.swift`:

| Number | Raised when | Line |
|---|---|---|
| 2 | a recording is added | `:343` |
| 5 | a source carries video effects | `:338`, `:345` |
| **6** | any edit is committed: the floor of every commit, and the default of a new project. This is the number under which `timeline` is the saved form. | `:235`, `:319` |
| 7 | a placement has an edge crop ("Older readers must not silently discard independent placement crops") | `:318-319` |
| 8 | the timeline has subtitles | `:325` |
| 9 | a sound has a censor region, or a source is audio only | `:330`, `:378` |
| 10 | an edit keeps a canvas reference | `:327`, `:597` |
| 11 | a sound has a fade | `:328` |
| 12 | a crop, a color, an image, a video effect or a placement has a motion | `:320-324` |

`latestReadableSchema` is 12 (`O/Project.swift:230`). 1, 3 and 4 are not assigned anywhere in the source; the repository has a single commit, so what they once meant could not be read.

## 2. Mapping from Android today

### 2.1 Saved types

| Android today | Becomes |
|---|---|
| `EditState(clips, audio, subtitles)` (`C/Project.kt:201-211`) | Holds `timeline`. `clips`, `audio` and `subtitles` are read from old steps and offered as a view (3.1, 3.3). |
| `TimelineClip` (`C/Project.kt:139-194`) | Kept: it is `VideoTimelineItem.content`. Inside a timeline its `transition`, `openingTransition`, `transitionFit`, `openingTransitionFit`, `audio`, `bookmarks` and `subtitleWords` are null; they live on relations, on the clip's sound item and in `timeline.bookmarks`. The fields stay in the type for old steps and for the view. |
| `ClipAudio` (`C/Project.kt:105-122`) | A view type. Stored as the sound item's `volumeDB`, `muted`, `fadeIn`, `fadeOut`; `denoise` becomes an enhancement region. |
| `TransitionFit` (`C/Project.kt:133-136`) | `TimelineTransition.factor`. `origin`, which `docs/TRANSITIONS-FIT.md` (section 5) left out until "the day clips are placed freely", is now stored. |
| `AudioItem` (`C/Project.kt:219-235`) | An `AudioTimelineItem` with no `followsVideo` and no link. `track` is not stored; rows are derived. |
| `Bookmark` (`C/Project.kt:99-101`) | `TimelineBookmark` with a `source` anchor. A null label is `""`. |
| `TextOverlay`, `ColorSettings`, `CropSettings`, `SpeedSettings` | Unchanged, on `content`. |
| `SubtitleSettings` (`EditState.subtitles`) | `timeline.subtitles`. |
| `EditSnapshot`, the manifest, the capture ledger (`C/Project.kt:242-295`) | Unchanged in shape. |

### 2.2 Core files

In this table a bare `:n` is a line of the file in the left column.

| File | Becomes |
|---|---|
| `C/Project.kt` | `EditState`, `commit` (`:356-368`), `materialized` (`:320-324`), `validate` (`:435-488`) and the schema rule change in S3b. `materialized` becomes a port of `O/Project.swift:255-268`. |
| `C/TimelineEditing.kt` | Every function keeps its signature and becomes a wrapper, or is deleted, as listed in 2.2.1. |
| `C/TransitionLayout.kt` | `fit`, `free`, `fits`, `Join`, the constants: kept. `sequence` (`:152-205`) is kept for good: it is the conversion's layout rule and equals both `TimelineLayout(clips:)` (`O/Transitions.swift:199-253`) and the original's own migration oracle (`OT/LegacyTimelineOracle.swift`). `Picture`, `Relation`, `Arrangement`, `setting`, `restoringStraightCut`, `refitted` (`:210-373`) are **deleted** once the ports of `settingTransition` and `pruneRelations` pass their tests: they are the same functions without ticks, links or sound. |
| `C/TransitionSound.kt` | Kept as is. The ramps apply only to a sound that follows its picture (`O/TrackRenderer.swift:271-279`). |
| `C/RangeEditing.kt` | `TimeRange`, `EditTargets`, `EditScope`, `RangeMarks` (`:7-135`) are **kept**: they are the editor's state and are `TimelineSelection` under other names (`EditTargets(clips, audio)` plus `Range` are `video`, `audio`, `range`). `delete`, `copy`, `cut`, `paste`, `scoped` become wrappers over `scoped` (`T:1080-1094`), `remove`, `clipboardFragment`, `cutToClipboard`, `preparePaste` and `paste`. `ClipFragment` (`:140-152`) is replaced by `TimelineClipboard`. `quantize`, `markTime`, `snap` kept. |
| `C/TimelineNavigation.kt` | Kept. Its builder (`:58`) takes cuts and treatments and does not know the model; what feeds it changes from clips to `compile()` and the layout, as in `O/TimelineNavigation.swift:49`. |
| `C/TimelineSnapping.kt` | `TimelineViewport`, `target`, `snap`, `move` kept as is: they are already ports of `T:1442-1455` and `T:1488-1525`. `points(state, …)` (`:94-117`) is replaced by a port of `snapPoints` (`T:1470-1487`) with the same result. |
| `C/InsertionSites.kt` | `VideoInsertionSite`, `union`, `placement` kept as is (`placement` at `:67-80` already takes any coverage). `coverage` (`:54`) is replaced by `videoCoverage`; `sites` (`:82-83`) gains the gaps. |
| `C/UnusedTakes.kt` | Kept; `of` reads the pictures' sources, `add` calls the port of `inserting`. |
| `C/AudioUnlinking.kt` | **Deleted** at S6. Unlink is `T:575-579`, relink is `joining`, sync is `T:1605-1615`. The shape it saves (a muted clip and an `AudioItem` with the clip's id, `:9-19`) stays readable by the conversion for good. |
| `C/EditTime.kt` | Kept; the saved form already matches. Gains the helpers the model needs. |
| `C/AudioLevels.kt` | Kept. `AudioGainSelection` (`:319`) is replaced by `TimelineSelection`. |
| `C/AudioCensor.kt` | Kept. `AudioCensorItem` (`:172-178`) is filled from an `AudioTimelineItem`; `censored` (`:196`) becomes the body of `censor(selection)`; `retimed` (`:237`) is the censor half of `synchronizeFollowers` (`T:1311-1333`). |
| `C/AudioChain.kt`, `C/AudioEffect.kt` | The processing is kept. `AudioEnhancement` (`C/AudioEffect.kt:182-191`) is replaced by `AudioEnhancementRegion` with a tick `interval`. |
| `C/VideoPlacement.kt` | Kept as is. `VideoPlacementRegion`, `placementSeed` and the retention across new overlaps are added beside it. |
| `C/SubtitleEditing.kt` | `cues`, `fingerprint`, `install`, `remove`, `regroup`, `correct` become ports of `O/Subtitles.swift:154-247` on sound items; `stretches` (`:59-76`) is deleted when the mix is transcribed (S8). `SubtitlePlacement` kept. |
| `C/Subtitles.kt` | Kept. `SubtitleTiming.cues` (`:278-304`) already takes a run index: "The original walks every run of an item's playback map". |
| `C/ProjectDuplicate.kt` | Kept. It copies the snapshots as they are and regenerates no item id (`:98-101`), so nothing needs remapping. |
| `C/ProjectRestore.kt`, `C/ProjectArchive.kt` | Kept. An archive of an older version converts on read like any project. |
| `C/OpenTimeline.kt` | Reads the view in S3b (`:53`, `:185`); rewritten on lanes at S5 and S9, where a lane is a track of the exchange file. |
| `C/ExportReceipt.kt` | Kept. `matching` (`:124-132`) compares edits after conversion, or every project loses its "Exported" mark once. |
| `C/ExportCheckpoint.kt`, `C/ExportProgress.kt` | Kept; not on the timeline model. |
| `C/Speed.kt`, `C/Pauses.kt`, `C/PlaybackSpan.kt` | Kept. `SpeedMapping.spans` (`C/Speed.kt:35`) is what `SourcePlaybackMap(clip)` is built from. |
| `C/TransitionScene.kt` | Its `decks` and the alternation by chain are replaced by the render plan (S0, S3a); `Moment`, the description of one frame, stays. |

#### 2.2.1 `TimelineEditing`, function by function

In the left column a bare `:n` is a line of `C/TimelineEditing.kt`.

| Android | Port of |
|---|---|
| `layout`, `fitted`, `pruned`, `refitted`, `join`, `clipStart`, `duration`, `locate` (`:21-131`) | `TimelineLayout(timeline:)` (`O/Transitions.swift:271-332`) and `pruneRelations`. `fitted` disappears: the fit is always stored. |
| `chains` (`:141-153`) | Deleted; the render plan assigns lanes as `O/TrackRenderer.swift:81-87` does. |
| `split` (`:160-180`) | `TimelineEditing.split` (`O/TimelineEditing.swift:45-62`) over `split(at:selection:)`. |
| `delete(index)` (`:183-186`) | `remove` of the clip's linked selection. |
| `move(from, to)` (`:189-194`) | A wrapper (remove, then insert at the join) until S10, then deleted for `move` and `moveChoices`. |
| `insert`, `opened` (`:207-247`) | `inserting` and `insertTime`; inside a transition, `insertPictureTime` (`O/TimelinePaste.swift:176-194`). |
| `trimmed`, `trim` (`:253-281`) | Until S4 a wrapper over `replaceVideoContents`, the path it copies today (`T:1370-1375`). From S4: inwards a range delete, outwards `extend`. |
| `removeIntervals` (`:296-320`) | A wrapper (cut, then delete the ranges) while pauses make pieces; `PauseSettings.cuts` on the clip when pauses become a setting (7.3, D6). |
| `setJoin`, `setTransition`, `joinSettings`, `transition`, `overlap` (`:340-393`) | `settingTransition`, `restoringStraightCut`. |
| titles (`:404-510`) | Kept; they edit `content.texts`. `placeTitle` reads places from the layout. |
| bookmarks (`:518-540`) | `addBookmark`, `resolvedBookmarks`; `toggleBookmark` is a wrapper. |
| `setAudio`, `soundLevel` (`:547-568`) | `setVolume` and `setFade` on the clip's sound; the level is the envelope of that item. |
| sound items (`:572-632`) | `insertAudio`, `move`, `scoped` + `remove`, `extend`, `setVolume`, `remove`. The two-track rule (`fits`, `placed`, `:574-579`) is deleted at S5. |
| `setColor`, `setCrop` (`:637-652`) | Kept; recipe edits through the view. |
| `setSpeed` (`:663-669`) | The speed branch of `replaceVideoContents` (`T:1376-1415`) and `synchronizeFollowers`. |

### 2.3 Ports that already anticipate the model

| Android | Original | Saved form matches? |
|---|---|---|
| `EditTime`, `EditInterval` (`C/EditTime.kt:13-53`) | `T:5-30` | **Yes**: `{"ticks": n}`, same rounding. |
| `AudioEdgeFade` (`C/AudioLevels.kt:258-283`) | `T:133-151` | **Yes**: `seconds`, `elapsed`. |
| `AudioCensorRegion` (`C/AudioCensor.kt:117-141`) | `O/AudioCensor.swift:52-66` | **Yes**. |
| `AudioEffect` (`C/AudioEffect.kt:115-123`) | `O/Project.swift:119-139` | **Yes**. |
| `AudioEnhancement` (`C/AudioEffect.kt:182-191`) | `AudioEnhancementRegion`, `T:126-131` | **No**: `start` and `end` in seconds instead of `interval` in ticks, and another name. Nothing in `app` uses it; replace it in S1. |
| `VideoPlacement`, `PlacementEdgeCrop` (`C/VideoPlacement.kt:17-70`) | `T:360-388` | **Yes**, field for field. The region that holds them is missing. |
| `PlacementEditing`, `PlacementRendering` (`C/VideoPlacement.kt:201-413`) | `O/TrackEditors.swift:773-841`, `O/TrackRenderer.swift:317-329` | Not saved. |
| `TransitionLayout.Arrangement`, `Relation` (`C/TransitionLayout.kt:210-259`) | `TimelineTransition` and the picture half of the timeline | Not saved; seconds instead of ticks. Superseded (2.2). |
| `SubtitleWord`, `SubtitleSettings` (`C/Subtitles.kt:15-56`) | `O/Subtitles.swift:7-47` | **Yes** for the words. The settings keep the original's `appearance` and `motion` as raw JSON. |
| `TimelineViewport`, `TimelineSnapping.move` (`C/TimelineSnapping.kt:16-38`, `:151`) | `T:1442-1455`, `T:1492-1525` | Not saved. |
| `VideoInsertionSite`, `InsertionSites.placement` (`C/InsertionSites.kt:7-80`) | `O/TimelineEditing.swift:3-32` | The original saves the site of a recording run (`O/Project.swift:219-222`); Android does not yet. |
| `EffectPlaybackSpan`, `FrozenFrameSegment` (`C/PlaybackSpan.kt`) | `O/VideoEffects/VideoEffectTiming.swift:5-16` | `FrozenFrameSegment` yes; not on the clip yet. |

### 2.4 Where Android behaves differently today, and when each difference ends

The wrappers of S2 must keep these, or a release "with no visible change" changes something. Each is a line of code in a wrapper that a later stage deletes.

| | Android today | The original | Ends at |
|---|---|---|---|
| N1 | The opening lives only on the first clip, the close only on the last (`C/TimelineEditing.kt:330-337`) | They belong to their picture wherever it is (`T:881-885`) | S4 |
| N2 | A trim changes source bounds and removes both transitions only when together they no longer fit (`C/TimelineEditing.kt:270-281`) | A handle trim inwards deletes a range (`O/EditorView.swift:1241-1258`); outwards it extends, and may lengthen a shortened transition (`T:1618`, `T:863-876`) | S4 |
| N3 | Removing pauses leaves separate clips (`C/TimelineEditing.kt:296-320`) | One clip with a pause setting (`T:1376-1385`) | D6 |
| N4 | ~~A split drops the fade from the piece that loses its edge~~ Closed before the switch (schema 15): a split, a trim and a range leave each piece what falls in it of a fade (`ClipAudio.sliced`) | The later piece inherits the slope and the level at the cut (`T:132`, `T:201-202`) | done |
| N5 | ~~Each fade takes at most half the item~~ Closed before the switch (schema 15): a fade may be as long as its item, and the lower of the two counts where they cross (`C/SoundEnvelope.kt`) | Up to the whole item (`T:191-195`) | done |
| N6 | The movie ends with its picture; later sound is not played (`C/Project.kt:209-210`; `A/Exporter.kt:228-231`) | The movie lasts as long as its longest item (`T:503`) | S5, D3 |
| N7 | Two sound tracks, nothing overlapping on a track (`C/Project.kt:233`, `:464-468`) | Any number, mixed (`OD:29-30`) | S5 |
| N8 | A cut is refused within 0.1 s of an edge and is not put on a frame (`C/TimelineEditing.kt:6`, `:164`) | Put on the 1/30 s grid (`O/TimelineEditing.swift:42-47`); refused only under one tick (`T:1014`) | kept |
| N9 | Unlinked sound is a muted clip plus an item with its id; refused for a clip with speed or noise reduction or busy tracks (`C/AudioUnlinking.kt:34-44`) | A link removed, nothing else (`T:575-579`) | S6 |
| N10 | Words belong to clips; sound items are not transcribed (`C/SubtitleEditing.kt:11-14`) | Words belong to sound items (`T:184`) | S8 |
| N11 | Footage inserted inside a transition removes it and cuts the clip (`C/TimelineEditing.kt:207-217`) | `inserting` leaves the first piece of the incoming clip playing over the outgoing one (`docs/TRANSITIONS-FIT.md`, open point 2) | S9 at the earliest |
| N12 | A tool on a range that begins or ends inside a transition applies to nothing (`C/RangeEditing.kt:717-735`) | The items are cut there (`T:1080-1094`) and the prune keeps or drops the relation | S4 |
| N13 | A take recorded later goes after the last clip (`C/Project.kt:320-324`) | At the timeline's end, after a close and after any sound (`O/Project.swift:259`) | S4 for a close, S5 for sound |

## 3. Saved format and migration

### 3.1 The edit step

A step is written in the form it has.

**A step read from an older project** keeps its form in memory and encodes to the bytes it was read from. This is the rule the repository already follows for every format step ("an old step keeps its bytes and its file name only if the model encodes it exactly as before", `docs/TRANSITIONS-MIGRATION.md`, section 1), pinned by `CT/ProjectFormatTest.kt`. Nothing in the existing serializers changes.

**A step committed from S3b on** is written with `timeline` and without `clips`, `audio` or `subtitles` beside it:

```json
{"state":{"timeline":{"video":[…],"audio":[…],"links":[…],"bookmarks":[…],"placements":[],"transitions":[…],"generated":[…],"subtitles":{…}}},"captureCursor":3}
```

The encoder has `encodeDefaults = true` (`C/Project.kt:12`), so this needs its own serializer: the plain one would write `"clips":[]`.

- `EditState` offers `timeline` to everything new and `clips`, `audio`, `subtitles` as a view to everything not yet moved (3.3). For an old step the timeline is made on demand by the conversion, which is pure and gives the same ids every time.
- `captureBase` stays runtime provenance (`C/Project.kt:196-203`).
- The first, empty step of a new project stays `{"clips":[]}`: a project that was never edited is readable by every version.

Key names, units and optionality inside `timeline` are the original's (1.2). The containers are Kotlin's natural ones; 3.6 lists the places where that differs from what Swift writes.

### 3.2 From a sequence step to a timeline

The original's rule is `TrackTimeline(clips:effects:)` (`T:510-534`): lay the clips out as a sequence, give each picture the start the layout found, give each clip a sound with a derived id and a link, turn each transition into a relation with the layout's origin and factor. Android applies it with the additions below. Rules 2, 6, 7, 8 and 10 are not in the original because its old format had none of those things.

1. **Layout.** `TransitionLayout.sequence` with the saved fits (`C/TransitionLayout.kt:152-205`, called as at `C/TimelineEditing.kt:23-26`). A transition with a saved fit plays at it; one without is fitted by the layout's rule, which is the original's conversion.
2. **Boundaries first.** Each picture's start is the layout's start rounded to a tick, as in the original (`T:514`). Its length is **not** rounded separately: it is the distance to the boundary it must meet. At a plain cut that is the next picture's start. Across a transition it is `next start − ticks(rightStart) + ticks(leftEnd)`, the inverse of what `settingTransition` and the prune compute (`O/Transitions.swift:373-377`; `T:879-880`). The last picture's length is its duration rounded.

   Why: the original rounds starts and lengths independently. On its own cuts, which lie on the 1/30 s grid, they agree. Android's cuts do not lie on a grid (N8), and the probe finds one plain cut in four a tick apart. A cut that is a tick apart is not a cut to the model: no transition can be put on it (`O/Transitions.swift:296`), its two sides cannot be linked as one run (`T:589-594`), Move does not see them as neighbours (`T:670-684`) and it is not a paste target (`O/TimelinePaste.swift:40-43`). The original applies the same policy inside one item ("Round cumulative boundaries, not each run's length", `T:53-54`); this extends it across items. No position moves by more than half a tick from today's layout, so nothing drifts in a long project.
3. **Pictures.** One per clip, in order: `occurrenceID` = the clip's id, `layer` = its index (`T:514`), `localOrigin` 0, `content` = the clip without the fields listed in 2.1, `mapping` = one run from `clip.start` to `clip.end` of the length from rule 2.
4. **The clip's sound.** Unless rule 6 applies: a sound with `id = counterpart(clip id)` (`T:535-539`), the picture's occurrence, source, start and map, `followsVideo` = the clip's id, and a link `{id: clip id, members: [clip id, sound id]}` (`T:516-521`). From `clip.audio`: `volumeDB`, `muted`; each fade as it is saved, `AudioEdgeFade(seconds, elapsed)` from `ClipAudio.fades` (since schema 15 a clip keeps the original's fade: any length, and what a cut left behind its edge; `C/TrackConversion.kt` `fades`, and `withFades` for the way back, without loss). `denoise > 0` becomes one enhancement region over the whole item holding one noise-reduction stage at that amount, the shape the original gives a project-wide chain (`T:518`). `subtitleWords` move to the sound unchanged.
5. **Transitions.** Each join whose settings are in play becomes a relation: `outgoing` and `incoming` are the two clips' ids (one of them absent at the opening and at the close), `origin` in ticks from rule 2, `factor` from the layout. Settings that are not in play (invalid, or naming a clip that is no longer next) are dropped, as the layout ignores them (`C/TransitionLayout.kt:155-157`). `generated` is built by the prune's rule (`T:889-895`).
6. **Unlinked sound.** A muted clip with an `AudioItem` that has the clip's id (`C/AudioUnlinking.kt:73-74`): one sound with `id = counterpart(clip id)`, the clip's id as occurrence, the item's place, source range, level and fades; no `followsVideo`, no link. The clip gets no other sound. The level and fades the muted clip remembered "for relink" (`C/AudioUnlinking.kt:128-129`) are not carried: in the model relinking keeps the sound's own settings (`T:626-636`).
7. **Other sound items.** `id` and `occurrenceID` = the item's id, one run from `sourceStart` to `sourceEnd`, start and end rounded as boundaries, level and fades as in rule 4, no link. `track` is dropped. `denoise` on an item is dropped: it has no effect today (`C/Project.kt:110`). Today ids are unique among clips and among sound items, not across the two (`C/Project.kt:458`, `:461`); in the model they are unique across both (`T:1176-1177`). An item that has a clip's id without rule 6 applying gets a derived id.
8. **Bookmarks.** Each mark of a clip becomes `source(occurrence: clip id, source: clip's source, time)` with its id, as `includeCaptureBookmarks` makes them (`T:1463-1469`).
9. **Order.** `video` in clip order; `audio` with each clip's sound in clip order, then the other items in saved order. The order is not cosmetic: it numbers the lanes (`T:1725-1726`), breaks ties for which sound carries a censor tone (`O/AudioCensor.swift:118-121`), and decides which chain the legacy `effects` view shows (`O/Project.swift:165-168`).
10. **Captions.** `subtitles` moves into the timeline. Its `timingFingerprint` was made by Android's formula (`C/SubtitleEditing.kt:44-48`); the model's is another (`O/Subtitles.swift:183-189`). When the old one still matched, the new formula's value is written; otherwise an empty one, so regrouping stays refused exactly where it was.
11. **Derived ids.** Every id the conversion invents is a fixed function of an id in the step (the original flips one bit, `T:535-539`), so that converting any history step twice, on any device, gives equal timelines ("converting Undo snapshots agrees", `T:507-508`). Android ids are strings and `validate` does not require them to be UUIDs (`C/Project.kt:435-488`): the function must be defined for any string.

A converted timeline must be a fixed point: `pruneRelations` changes nothing in it, and `validate` accepts it.

### 3.3 Back to the sequence form

Two different things are needed, and only the first is a round trip.

**An exact inverse on the sequential subset.** A timeline is *sequential* when it could have come out of 3.2: pictures in one run from the opening, meeting exactly or through a relation between neighbours; an opening only on the first and a close only on the last; one run per picture at one rate; each picture with its following sound, or with the unlinked sound of rule 6; no placements; only `source` bookmarks; other sounds with one run at recorded speed, without words or regions, fitting on two rows; fades with nothing elapsed; enhancements only as rule 4 makes them. On that subset `toSequence(fromSequence(step))` equals the step after the normalisations 3.2 states (dead transition settings gone, fades capped, `track` repacked). This inverse is what S1 to S3b are tested with and what lets S3a be compared with today's renderer. The original has the same test for its fast path: the renderer proves a timeline "is exactly one ordinary linked sequence" before using it (`O/TrackRenderer.swift:44-54`).

**A view with a merge-back, everywhere else.** From S4 on, timelines leave the subset, and the sequence form cannot hold them. A screen that has not been moved yet does not need it to: it needs to read clips and write changed clips. That is what the original kept: `projectedClips` always answers (`T:1259-1267`), and writing goes through `replaceVideoContents`, which finds each clip by id and merges its changes without touching positions, links, layers or anything it does not know (`T:1339-1437`). Android's view carries more than the original's, because Android's tools still edit `clip.audio`, `clip.bookmarks` and `clip.subtitleWords`: the view fills them from the clip's sound and the bookmarks, and the merge writes them back there.

So the app can be switched screen by screen. A screen on the view sees one clip per picture in playing order and cannot see gaps, layers or unlinked sound; it can still edit color, crop, titles, speed and the join it knows. It never destroys what it cannot see, which is the property to test.

### 3.4 Schema number, and older versions

Android numbers its own steps (`C/Project.kt:181-193`, `:363`, `:409`): 2 transitions and titles, 3 bookmarks, 4 clip sound, 5 sound items, 6 noise reduction, 7 color, 8 crop, 9 speed, 10 takes inserted at a place, 11 subtitles, 12 title designs, 13 the original's transition settings. `LATEST_READABLE_SCHEMA` is 13 (`C/Project.kt:502`). The numbers already mean other things than the original's (its 6 is the timeline, Android's 6 is noise reduction).

**The timeline takes the next free number: 14**, unless 0.7 lands a format step first. As with the original's 6 (`O/Project.swift:319`), it is the floor of every commit once S3b ships. The merge-order caution of `docs/TRANSITIONS-MIGRATION.md`, section 3, applies: a constant of 14 claims everything up to 14 is understood.

Later stages may need numbers of their own. The rule of both code bases decides: "Older readers must not silently drop what they do not know" (`C/Project.kt:362`; `O/Project.swift:318`, `:329`). S1 ports every type, so no later release *drops* anything; but a release that reads a timeline it cannot *render* (S3b reading layers made by S9) would show a wrong movie. Each visible stage therefore raises the number when a committed timeline uses what earlier releases cannot play. The numbers are assigned when each stage lands.

**What an older version does with a newer project.**

- It cannot read it: `validate` refuses a schema above its own (`C/Project.kt:436`), with "This project has unsupported or invalid editing data. Its files have been preserved." That refusal is the only guard: `ignoreUnknownKeys` is on (`C/Project.kt:12`), so without it an older reader would take a timeline step for an empty edit.
- **But it does not stop there.** `ProjectDisk.read` treats any head that fails as damaged and opens "the newest readable revision" (`C/ProjectDisk.kt:42-54`). For a project that has just crossed to 14, the five kept revisions (`C/ProjectDisk.kt:123-128`) still hold manifests of 13. The older version opens the project as it was before, without a word, and its next save writes that over the head. The newer manifest becomes one of five rotating revisions and is deleted five saves later. The history files are still there, but nothing names them.
- The original does not have this fault: a head newer than it can read is not damaged, it is "saved by a newer YouRec" (`O/ProjectRecovery.swift:83`, `:120`, `:156`).

Guards, all three:

1. **In 0.7, before any of this:** a head whose schema is newer than readable is refused and never replaced by a revision. A few hours. From then on, only versions before 0.7 have the fault.
2. **At the first save under 14:** move the revisions of lower schema out of the folder older versions scan (they list `.revisions/*.json` only, `C/ProjectDisk.kt:119-121`) into a kept, never rotated place, as the original pins a `schema-<n>-<hash>.json` before a schema rises (`O/ProjectRecovery.swift:123-128`). An older version then finds nothing to fall back to and refuses. The pinned manifest plus the untouched history files are the whole project as it was, should it ever be needed.
3. **Until the first edit, nothing changes on disk.** Opening an old project in the new version converts in memory only. Its steps keep their bytes and names, its schema stays, and every older version still opens it.

Releases are also published as APKs outside the store, so installing an older version over data of a newer one is a real case, not a theoretical one.

### 3.5 History, undo, archives, copies

- **History.** Steps are files named by the hash of their bytes; nothing deletes one on save (`C/ProjectDisk.kt:27-40`). After the switch a history holds old steps and new steps side by side. Undo to an old step converts it in memory; committing from there appends a timeline step. No step is ever rewritten in the other form.
- **Cost of saving.** Today every save encodes every step (`C/Project.kt:243`, `:492-495`), reads every history file back and hashes it (`C/ProjectDisk.kt:36`), and validates every step (`C/Project.kt:456`); opening the library reads every step of every project (`C/ProjectDisk.kt:56-71`; `C/Project.kt:518-528`). A timeline step is about 7.5 times a sequence step for plain clips (a clip of about 120 bytes becomes a picture, a sound and a link of about 900) [E]. The original avoids all three costs: a step keeps its encoded bytes (`O/Project.swift:196-214`), a file checked once is not read again (`O/ProjectRecovery.swift:9-23`), and the library opens with the current step only (`O/Project.swift:616-630`). S3b ports the first two; the third is a question of size (7.2).
- **Takes recorded later.** The capture ledger and its insertions keep their shape (`C/Project.kt:280-295`). `materialized` appends a later take as a picture, a sound and a link at the end, with the take's marks as bookmarks (`O/Project.swift:255-268`). Until S4 and S5 the wrapper keeps "after the last clip" (N13).
- **Undo.** Unchanged: a cursor over the steps (`C/Project.kt:429-433`). `commit` compares timelines instead of three fields (`C/Project.kt:359`).
- **ZIP.** The archive is the manifest and the history as saved (`C/ProjectArchive.kt:18-30`). An archive of an older version restores and converts on read. An archive of a 14 project is refused by older versions at `readManifest` (`C/ProjectRestore.kt:184-191`); there is no fallback on that path. `publish` (`C/ProjectRestore.kt:217-228`) deletes history files the restored project does not name: it must go on naming old steps by their old bytes.
- **Duplicate.** Copies the steps as they are (`C/ProjectDuplicate.kt:98-101`, `:142`). Nothing to convert.
- **Export receipts.** A receipt holds the edit it was made from (`C/ExportReceipt.kt:21-31`). One written before S3b holds a sequence step; compare after converting both sides.
- **The exchange file.** `Timeline.otio` is made from the current edit at every archive (`C/OpenTimeline.kt:46`); it follows the view until lanes exist.

### 3.6 Can Android and iOS projects become interchangeable?

The `timeline` object can be made the same, name for name and unit for unit; that is what 3.1 prescribes. A whole project cannot be exchanged, and this plan does not try. What would still differ:

| | iOS | Android |
|---|---|---|
| Schema numbers | 6…12 mean the things in 1.7 | 2…14 mean the things in 3.4 |
| Dates | `Date` as seconds since 2001 | milliseconds since 1970 (`C/Project.kt:46`, `:284-285`) |
| Capture ledger | entries `{clip, insertion}` and a recording site (`O/Project.swift:215-222`) | clips, and a list of inserted indices (`C/Project.kt:288-294`) |
| Picture recipe | stabilization, pauses, mirror, images, video effects, frozen frame (`O/Project.swift:42-61`) | none of those yet |
| Edit | `outputSize`, `canvasReference` | neither |
| A set of ids (`members`, `participants`) | an array in no defined order | an array; choose sorted |
| `videos` of a placement | Swift writes a dictionary keyed by UUID as one flat array: key, value, key, value | an object keyed by id |
| `BookmarkAnchor`, insertion site | Swift's coding of an enum with values: `{"source":{"occurrence":…,"source":…,"time":…}}`, `{"project":{"_0":{"ticks":n}}}` | Kotlin's own |
| Unknown content | — | dropped on the next save: `ignoreUnknownKeys` reads past it and nothing writes it back |
| Archive | integrity version 2 with recovery files | version 1; version 2 is refused (`C/ProjectRestore.kt:199`) |
| Media | `.mov`, possibly HDR or Log | `.mp4`, SDR |

The Swift shapes in the table follow from the language's synthesized coding; no saved iOS project was at hand to confirm them (section 8). The row that matters most is "unknown content": while Android does not model video effects, stabilization or images, opening an iOS project and saving it would strip them. A one-way import (iOS project in, Android project out, with a list of what was left behind) is possible later and is made cheaper by keeping names and units equal. Decision D1.

## 4. Stages and gates

The original's own six stages (`OD:401-433`) map as follows. Its stage 1, "Model and compiler … Compare unchanged sequential projects against the current renderer before changing the UI", is S0 to S3b here. Its stage 3 (selection, Unlink, Volume) is S5 and S6; stage 4 (Enhance audio) is S7; stage 5 (Move, overlaps, Placement) is S9 to S11; stage 6 (device and UX QA) is the phone gate of S5, S9 and S11. Its stage 2 (viewport, bookmarks, Full preview) does not depend on the model and is ordinary parity work (`docs/PARITY.md`, area 4).

Its closing rule is kept: "Each stage gets its own reversible commit. No feature is called complete based only on a successful build or a static screenshot" (`OD:432-433`).

Order and dependencies:

```
S0 → S1 → S2 → S3a → S3b → S4
                         ├→ S5 → S6 → S7 → S8
                         └→ S9 → S10 → S11
                    S6 + S9 → S12
```

The sound branch (S5…S8) and the picture branch (S9…S11) do not depend on each other. S4 is first because it is the smallest and makes the renderer take transitions by identity, which both branches need.

Until S3b ships, going back is installing the previous APK: nothing on disk has changed. S2 and S3a each put the new engine in front of real projects with the old format underneath. Decision D2 asks whether to release between them.

### S0. Baselines and the render-plan seam

- **What.** (a) Golden projects as test builders: plain takes; unquantised splits, trims and moves; all nine transition styles with opening, close and custom sound timing; speed and pause pieces; titles, bookmarks, captions; music on both tracks with fades, trimmed, running past the picture; color and crop; a history with takes inserted at a place; one long project (2,000 clips, 300 steps). (b) What the exporter and the preview decide today from the clips — which clip goes on which sequence, where the gaps are, which sound is removed, the level of each sound over time, which sequences carry the two sound tracks and the cleaned sound — moved out of `A/Exporter.kt:198-335` and `A/EditorScreen.kt:283-304` into a pure `RenderPlan` in core. The app builds Media3 objects from the plan and decides nothing.
- **Core.** New `RenderPlan.kt`; `GoldenProjects` in tests. **App.** `Exporter.kt`, `EditorScreen.kt` (`EditorPlayer`), `TransitionRendering.kt`.
- **Size.** L, 2–3 days.
- **Parallel, without the app.** The golden projects.
- **Emulator.** Yes: export each golden project before and after. **Phone.** No.
- **Gate.** For every golden project the export after the refactor has the same decoded frames at 30 sample times and the same sound samples as before, within the tolerance found by exporting one project twice from the same build. The plan of each golden project is pinned as a test.
- **Why first.** Every later "no visible change" is then a comparison of two plans on the host, and export checkpoints (section 6) get their time slices from the plan.
- **As built.** Only the core half was made at S0: `RenderPlan.kt` and its tests, describing what `Exporter.kt` and `EditorPlayer` arranged on that day. The app was not changed to read it, and went on deciding from the clips. The export was then rewritten twice beside it (a clock sequence for every movie, decks that carry no sound with every clip's sound on sequences of its own, and sections of twelve seconds, `docs/EXPORT-SECTIONS-PROBE.md`, section 9), so the plan described an export that no longer existed: its picture sequences were those of a whole movie in one pass, and its `cleaned` sequences and `Picture.soundRemoved` those of decks that still carried the sound of every other clip. The app half was made with S3a, below.

### S1. The model, conversion both ways, invariants

- **What.** Every type of 1.2 with its serializer; `validate`; duration; `SourcePlaybackMap` with `source(at:)`, `local(forSource:)`, `sliced`; `compile`; lanes; the conversion of 3.2; the sequential test and the inverse of 3.3; `AudioEnhancementRegion` in place of `AudioEnhancement`.
- **Core.** New `TrackTimeline.kt`, `PlaybackMap.kt`, `TrackCompile.kt`, `TrackConversion.kt` and their tests. Touched: `EditTime.kt` (helpers), `AudioEffect.kt` (the region). `Project.kt` is not touched. **App.** Nothing.
- **Size.** L, 4–6 days.
- **Parallel, without the app.** All of it.
- **Emulator, phone.** No.
- **Tests that port directly.** From `OT/TrackTimelineTests.swift`: stable identities and full duration after conversion (`:19-20`); capture bookmarks once (`:115-118`); JSON round trip (`:119-121`); the clock on the standard frame rates (`:122-125`); speed in the map (`:126-132`, the map half); the conversion against the old layout for every style, two holds and three sound offsets, with full coverage and no gaps in `compile()` (`:158-176`). The oracle there, `OT/LegacyTimelineOracle.swift`, is `TransitionLayout.sequence`.
- **Gate.** Those tests. Every step of every golden project converts, validates, is a fixed point of the prune, and inverts to itself. Every plain cut of a converted timeline is exact (the probe of 3.2 turned into a property test over 10⁵ random sequences). Converting twice gives equal timelines. `compile()`, lanes and layout of the long project within the budget of 7.2, written into the test.

### S2. The operations

- **What.** Everything in 1.6, as functions on the immutable timeline returning a new one, in the core's existing style ("Each returns a new state, or null when it does not apply", `C/TimelineEditing.kt:3`). The view and merge-back of 3.3. A parity suite: for each function of `TimelineEditing`, `RangeEditing`, `SubtitleEditing` and each golden project plus generated edit sequences, the present result against `toSequence(operation(fromSequence(state)))`. Where they are equal, the present function's body becomes a call to the port; where they differ, the difference is one of N1…N13 and the wrapper states it. The old body is deleted in the same change: one engine, as the original insists (`OD:396-397`).
- **Core.** New `TrackEditing.kt` (split, scope, remove, insert, extend, close gap), `TrackLinking.kt`, `TrackMove.kt`, `TrackTransitions.kt`, `TrackPlacements.kt`, `TrackClipboard.kt`, `TrackBookmarks.kt`, `TrackAudio.kt`, `TrackSubtitles.kt`, `TrackSequenceView.kt`, and tests. Bodies changed, signatures kept: `TimelineEditing.kt`, `RangeEditing.kt`, `TimelineSnapping.kt` (`points`), `InsertionSites.kt` (`coverage`), `SubtitleEditing.kt`, `UnusedTakes.kt`. **App.** Nothing.
- **Size.** L, 10–15 days: about 2,400 dense lines of Swift (`O/TrackTimeline.swift` from line 540, `O/TimelinePaste.swift`, `O/TimelineEditing.swift`, the timeline half of `O/Transitions.swift`, `O/Subtitles.swift:154-247`, `O/AudioCensor.swift:92-130`) and about 1,300 lines of tests. The largest stage.
- **Parallel, without the app.** All of it. Three or four workers after S1 is merged, one new file each: the functions are extensions of one type and meet only in `pruneRelations`, which goes first.
- **Emulator.** A smoke run only if the wrappers are released before S3b (D2). **Phone.** No.
- **Tests that port directly.** From `OT/TrackTimelineTests.swift`: the rest of `trackTimelineTests` (`:21-111`, `:133-157`, `:177-187`); `moveCompanionTests` (`:328-383`, 12 checks); `multiSelectionTests` (`:385-533`, 40); `overlapInteractionTests` (`:537-678`, 24); `transitionPresentationTests` (`:680-729`, 14); from `reviewRegressionTests` the cases at `:818-874`, `:913`, `:925-929`, `:936-938`, `:953`, `:959-961`, `:973-974`. All of `OT/MoveBoundaryChecks.swift` and `OT/TimelineClipboardChecks.swift`.
- **Tests that do not port yet.** In the same file: `placementCropTests` (`:192-325`), whose geometry is already in `CT/VideoPlacementTest.kt` and whose persistence half waits for S11; `timelineNavigationTests` (`:732-807`), for the stage where navigation is fed from the timeline, without the video-effect cases (`:760-771`); in `reviewRegressionTests`, undercranking mute (`:890-906`), frozen frames (`:944-946`) and effect recipes (`:879`), which need video effects, and `:932` and `:955`, which are renderer tests.
- **Gate.** The ported tests; the 973 existing tests untouched and green; the parity suite with its list of differences equal to N1…N13 and nothing else; generated edit sequences never produce a timeline `validate` refuses ("persistent Undo with generated edit sequences", `OD:405-406`).

### S3a. Render from the timeline

- **What.** A second builder of `RenderPlan`, from the converted timeline. The app uses it. The saved format is unchanged.
- **How, as built.** This plan first said "from `compile()` and the layout of the converted timeline". That and the gate below cannot both hold: `compile()` and `TrackLayout` are in ticks, a `RenderPlan` is in the seconds of `TimelineEditing.layout`, and the ticks are those seconds rounded (3.2), so a plan made of them differs from today's in its last digits. A `RenderPlan` is also a plan of a sequence: its clips are numbered and it answers about a moment from its clips. So the builder (`C/TrackRenderPlan.kt`) reads the timeline as the sequence it is — the clips in the timeline's order, with the relations as their transitions at the saved factors and the settings and words of the sounds that follow them — and plans that. The original does so only for a timeline that is one ordinary linked sequence with no transition, caption or level in it (`O/TrackRenderer.swift:44-68`) and composes every other from the ticks; here that waits for the ticks to be what is saved, so that S3a moves nothing by a last digit. Three things the timeline does not hold stay the saved ones until the stage that adopts the timeline's: the second and the track of a sound item (S3b, S5), what the captions were timed for (S8), and on a clip what the conversion does not carry (3.2: settings not in play, a fit never saved, what an unlinked clip remembers). `compile()` is the check, in the tests: in every stretch it gives, the plan plays the pictures, sounds and transitions the timeline has there, each picture within a tick of its item. A plan in ticks, and of timelines that are no sequence, is S3b and S4.
- **Core.** New `TrackRenderPlan.kt`; `RenderPlan.kt` is not touched. **App.** Nothing but the call.
- **The app half, as built.** There was no call to change (S0, as built), so this stage made it: `ExportMovie`, `Exporter`, `ExportCheck.peak`, `TransitionEffect`, `TitleOverlay`, `EditorPlayer` and the preview in `EditorScreen.kt` take a `RenderPlan` made by `TrackRenderPlan.of(state, cleaned)` and turn it into Media3 objects. `RenderPlan.kt` was touched after all, to say what the export takes today and the plan of S0 did not have; each is worked out from the plan's fields, so the equality of the two builders covers it: the part of the decks a section is drawn from, with the clips cut where the section cuts them and the frame it is drawn from (`section`, `stretch`); the sound of every clip that has sound on sequences of sound alone (`voices`); the level of a run of sound sample by sample (`clipEnvelope`, `soundEnvelope`, `envelopes`); the titles to draw with their poses (`titlesShown`, `titled`); and for the preview the clip a deck shows or holds and the chain it waits for or follows (`onDeck`, `held`, `coming`, `leading`). `sequences`, `cleaned` and `drawsTransitions` stay as S0 pinned them and nothing in the app reads the last two: every export draws through the transition effect since the clock. What stays in the app is what is not about the edit: the files and what they state (size, frame rate, whether they have sound), which cleaned sound is ready, the clock, the black stills, the frames kept on the GPU, and the players.
- **Checked, and not yet.** On the host the plan is held to what the app decided before, kept in the tests as the app wrote it (`RenderPlanExportTest`, `RenderPlanPreviewTest`): the decks of every section, the sequences of sound and their levels, and what each deck and track does at every moment, for every step of the golden projects. On the emulator twelve measurement projects (dissolves at 25, 30 and 60 frames a second, a still with a flash cut and a wipe, a moving crop with a title, captions and speed, noise reduction with two sound tracks, plain cuts, sound over full scale, and one made with the editing functions, with saved fits, a clip at double speed, a still whose clip sounds on under it and a clip whose sound is unlinked) were exported by the build before and the build after: the same encoded packets of picture and of sound in every one, and again in an export paused in the middle of a section and resumed. The pixel tests give the same report on both builds. In the editor, four of the projects stepped through every stop of "Next change" (48 stops, among them the middle of a dissolve and of a wipe and the inside of the still) show the same screen on both builds, pixel for pixel; played through, both show the same clips in the same order to the end, which on an emulator says no more than that. Not yet run: a phone.
- **Size.** L, 2–3 days.
- **Parallel, without the app.** All of it.
- **Emulator.** One export per golden project. **Phone.** One export with a hardware encoder.
- **Gate.** The plan from the timeline equals the plan from the sequence for every step of every golden project, on the host, exactly. This is the original's stage-1 gate. Then the frames and samples of S0 again.

### S3b. Save the timeline

- **What.** 3.1, 3.4 and 3.5: the step serializer, schema 14 as the floor of a commit, the revisions guard, the pinned manifest, encoded bytes kept with a step, no re-reading of checked files, `materialized`, `validate`, receipts compared after conversion.
- **Core.** `Project.kt`, `ProjectDisk.kt`, `ProjectRestore.kt`, `ExportReceipt.kt`, `OpenTimeline.kt` (reads the view); `ProjectFormatTest.kt` gains pins for timeline steps; new `TrackMigrationTest.kt`. **App.** `ProjectStore.kt` for the message of a newer project; no screen.
- **Size.** L, 4–6 days.
- **Parallel, without the app.** The migration tests, from the golden projects.
- **Emulator.** Yes: install the previous release, make projects by hand, upgrade, open, undo into old steps, edit, export; then install the previous release again and see the refusal, with nothing changed on disk.
- **Phone.** Yes: the same on a real library, and the time to open the library and to save the long project.
- **Gate.** *Bytes:* a project opened and not edited has no byte changed, in its manifest or its history. A project edited once has every old step unchanged, one new step, the pinned manifest, and no revision an older version can read. Archive and restore, duplicate, and undo and redo across the boundary give equal edits. A core built from tag `v0.6` refuses the new project and reads the pinned one. *Pixels:* the exports of S0, again. *Speed:* the budgets of 7.2.

### S4. Transitions stay with their clips

- **What.** The end of N1, N2, N12, and of N13 for a close: an opening or a close stays on its picture when a clip is put before or after it or the picture moves (`docs/TRANSITIONS-REVIEW.md`, F9); a handle trim behaves as the original's (F10); a tool on a range may begin or end inside a transition. The frame description takes transitions by relation, not by join index.
- **Core.** Wrappers lose four rules; `TransitionScene.kt`. **App.** `EditorScreen.kt` (grips, badges), `TransitionPanel.kt`.
- **Size.** M–L, 2–4 days.
- **Parallel, without the app.** The core half.
- **Emulator.** Yes. **Phone.** No.
- **Gate.** `OT/TrackTimelineTests.swift:936-938` ("Moved fades are anchored to their own picture"); the probes of F9 and F10 with the original's results; exports of the golden projects unchanged except where a project had a fade that today disappears.

### S5. Sound lanes

- **What.** Any number of sounds at once, mixed; the movie as long as its longest item (N6, D3); a fade up to the whole item and inherited across a split (N4, N5); levels, fades, 8 ms cut fades and gain ramps applied per sample in the preview as in the export. One audio engine for both (section 5.3).
- **Core.** `TrackAudio.kt` in use; a mix plan (lanes and envelopes: `AudioEnvelopeSegment`, `C/AudioLevels.kt:327`); `OpenTimeline.kt` on lanes. **App.** New audio engine; `Exporter.kt`; `EditorScreen.kt` (sound rows in place of two tracks; the volume and fade panels at `A/EditorScreen.kt:1541-1600`); `Waveforms.kt`.
- **Size.** L, 8–12 days. With S9 and S11, the largest app stages.
- **Parallel, without the app.** The mix plan and its tests; the exchange file.
- **Emulator.** For function. **Phone.** Required: clicks at cuts, sync of sound to picture, latency on play and seek, by ear and by recording the output.
- **Gate.** From `OD:413-420`: preview and export produce the same mixed samples for a golden project with four overlapping sounds. "Splitting continuous independent audio introduces no fade or volume dip" (`OT/TrackMediaTests.swift:362`); headroom with overlaps (`OT/TrackMediaTests.swift:183-187`).

### S6. Picture or sound: Unlink, Link, gaps

- **What.** A selection that says which material it holds (`OD:12-14`, `OD:93-105`); Unlink and Link with the intersection question (`T:583-639`); deleting a lone picture or sound leaves a gap, a linked pair closes it (`OD:16-25`); gaps drawn; Close gap; the sync badge and Restore sync.
- **Core.** `AudioUnlinking.kt` deleted. **App.** Selection in the timeline, the action row, gap drawing, black for picture gaps in preview and export.
- **Size.** L, 6–10 days.
- **Parallel, without the app.** Little: the core is done in S2.
- **Emulator.** Yes. **Phone.** For sync by ear.
- **Gate.** `OD:413-416`: "Prove unlink is sample-identical; edits to one component never mutate the other … linked-pair deletion closes its gap automatically, while unlinked video/audio deletion leaves a gap … a detached audio bed spanning a linked-pair deletion". `OT/TrackMediaTests.swift:305-308`, `:368-379`.

### S7. Enhance audio and Censor

- **What.** A chain on a selection or a range of a sound (`T:746-766`; `OD:216-236`); censor regions with one tone for overlapping sounds (`O/AudioCensor.swift:92-130`). Needs S5 (per-sample sound) and the range marks.
- **Core.** `TrackAudio.kt` in use; cache keys by source and chain (`O/TrackRenderer.swift:27-30`). **App.** The two panels; `Denoiser.kt` becomes the chain's renderer.
- **Size.** L, 7–11 days.
- **Parallel, without the app.** The cache keys and the region arithmetic.
- **Emulator.** For function. **Phone.** Required, by ear; time of the models.
- **Gate.** `OD:417-420`: "adjacent chains, two occurrences of one source with different chains, speed, overlapping audio, model latency, boundary clicks, cancellation and stale-job races. Compare preview and export against the same mixed PCM."

### S8. Captions on sound items

- **What.** Words owned by sound items (N10); transcribing the mix, and a marked range only (`docs/SUBTITLES-REVIEW.md`, findings 2, 3 and 8). The mix at 16 kHz comes from the engine of S5.
- **Core.** `TrackSubtitles.kt` in use; `stretches` deleted. **App.** `SubtitleTranscriber.kt`, `SubtitlesPanel.kt`.
- **Size.** M–L, 3–5 days.
- **Parallel, without the app.** The core half.
- **Emulator.** Yes. **Phone.** For the time a transcription takes.
- **Gate.** `OT/SubtitleTests.swift:46-55` (captions follow a moved sound; a range replaces only its words).

### S9. Picture layers

- **What.** Overlapping pictures, the upper covering the lower; picture rows on the timeline; black where no picture is; the limit a device can play (5.2, 5.4, D7). N11 can end here.
- **Core.** Lane assignment in the plan; the limit. **App.** `EditorScreen.kt` (`EditorPlayer`, rows), `TransitionPreview.kt`, `TransitionRendering.kt`, `Exporter.kt`.
- **Size.** L, 8–12 days, a third of it measuring.
- **Parallel, without the app.** The plan for more than two lanes; row geometry as a pure function (`O/EditorTimeline.swift:52-73` is its model).
- **Emulator.** For logic. **Phone.** Required, on more than one: decoders at once, texture size, heat.
- **Gate.** `OT/TrackMediaTests.swift:392-400` ("Foreground video covers background by default"); `OT/TrackTimelineTests.swift:543-555` (lanes agree with the compositor); the measurements of 5.4 written down.

### S10. Move mode

- **What.** `OD:106-113`: a mode with a ghost, the four scopes, snapping with a held snap, placing on a boundary, Done as one Undo step, Cancel.
- **Core.** `TrackMove.kt` in use, plus the session's state as a pure type (the logic at `O/EditorView.swift:1162-1215`). **App.** The mode.
- **Size.** L, 6–10 days.
- **Parallel, without the app.** The session type and its tests.
- **Emulator.** Yes. **Phone.** For the feel of dragging and edge scrolling.
- **Gate.** `OD:421-425`: "linked/independent drags, negative-time clamping, snapping, edge scrolling, Cancel, source bounds … No silent overwrite or unannounced truncation." `OT/TrackTimelineTests.swift:590-599`.

### S11. Placement

- **What.** `OD:143-188`: the Placement tool on an overlap, presets, window, framing, edge crop, order, motion.
- **Core.** `TrackPlacements.kt` in use. **App.** The editor (`O/TrackEditors.swift:440-841` is its model); windows in preview and export.
- **Size.** L, 8–12 days.
- **Parallel, without the app.** Little: the geometry is already in `C/VideoPlacement.kt`.
- **Emulator.** For function. **Phone.** Required.
- **Gate.** The persistence half of `placementCropTests` (`OT/TrackTimelineTests.swift:255-323`); `OT/TrackMediaTests.swift:421-435` (a crop reveals the lower picture; preview and export agree).

### S12. Paste targets, gaps, bookmarks

- **What.** Choosing the picture sequence, the sound sequence or the project edge when pasting on a cut (`O/TimelinePaste.swift:5-66`); adding video in a gap; a bookmark in empty time or on a sound.
- **Core.** `TrackClipboard.kt` and `TrackBookmarks.kt` in use; `InsertionSites.kt` (`sites`). **App.** The target chooser, the add buttons, the bookmark list.
- **Size.** M–L, 3–5 days.
- **Emulator.** Yes. **Phone.** No.
- **Gate.** `OT/TimelineClipboardChecks.swift:80-112`.

## 5. Renderer and player

### 5.1 What exists

- **Export.** One `Composition`. The clips are one sequence, or two when clips overlap or time is inserted: chains of clips alternate between two sequences with gaps (`A/Exporter.kt:302-335`). The two are not blended by Media3: `DecksCompositor` lays them one above the other in a frame twice as tall (`A/TransitionRendering.kt:281-287`) and one shader makes the movie's frame from the two halves (`TransitionEffect`, `:295-452`; `TransitionShader`, `:122-257`). The shader already takes the number of halves as a uniform (`uHalves`, `:224-227`). Color, crop and fit are effects of each item (`A/Exporter.kt:198-216`). Titles and captions go over the finished frame (`:280-286`).
- **Export sound.** Each clip's level is a function of time applied by `SoundProcessor`, a new value every 64 samples (`A/Titles.kt:117-147`; `A/Exporter.kt:217-219`). The two sound tracks are two more sequences of sound only, with gaps (`:227-251`); cleaned sound is up to two more (`:252-278`). So an export already mixes up to six sequences.
- **Preview.** Two players, the "decks", one per chain parity (`A/EditorScreen.kt:209-217`, `:283-304`), each seeking on its own (`:331-348`). On Android 13 and later their two views lie in one layer twice as tall and the same shader text draws the frame (`A/TransitionPreview.kt:80-101`); before 13 they are stacked and cross-faded (`:102-127`). Crop is a transform of the view (`:64-77`). Two more players carry the two sound tracks (`A/EditorScreen.kt:236`, `:259-272`). Levels are set as each player's volume, once per display frame, with a loudness enhancer above 1 (`:242-250`, `:633-649`). The clock is "the deck that began playing last"; where none plays it runs by itself (`:374-375`).
- **The standing decision.** `docs/COMPOSITION-PLAYER.md`: keep this preview; Media3's `CompositionPlayer` cannot seek with more than one video sequence (`:14-17`), and what it officially supports is "one video sequence plus audio-only sequences" (`:24-26`). That is for Media3 1.11.1, the version in use (`gradle/libs.versions.toml:9`).

### 5.2 What each capability needs

| | Export | Preview |
|---|---|---|
| **Picture gaps** | A gap in a sequence: silence, and a black frame for video (`docs/COMPOSITION-PLAYER.md:35`); used today around chains (`A/Exporter.kt:308-312`, `:321-330`). The effect tells a picture from "the black of a sequence with nothing in it" by reading five pixels (`A/TransitionRendering.kt:400-404`, `:450`); with the plan it knows, and that guess can go. A project with no picture at all needs a test: whether a video sequence made only of gaps encodes is not known. The original could not, and uses a small black movie (`O/TrackRenderer.swift:124-136`). | The clock that runs by itself during a hold serves a gap. With sound under the gap the clock must be the sound's (5.3). |
| **Overlapping pictures** | One sequence per lane, lanes assigned first free as in `O/TrackRenderer.swift:81-87`. `DecksCompositor` for N: a frame N times as tall, the shader sampling band k. The frame must fit the largest texture the device allows: a 2160-line canvas is 4320 for two lanes, 6480 for three, 8640 for four. Alternatives when it does not: a grid instead of a column, or scaling a small window down before stacking. | One player per lane, in the same layer. A covering layer and a placed window are transforms of the view, as crop is today, and work on every Android version; only a transition between two of the layers needs the shader. Each lane is a hardware decoder for as long as it shows. |
| **Placement** | The window, framing and edge crop of each lane in the shader; the recipe is in core (`PlacementRendering.placed`, `C/VideoPlacement.kt:218-226`, from `O/TrackRenderer.swift:317-329`). | View transforms and a clip. |
| **Mixed sound** | More sequences of sound only: the construct exists. Or one sequence holding the finished mix (5.3). | Not possible with what exists: two sound players, levels once per frame. See 5.3. |

### 5.3 The audio engine

Three ways to give the preview any number of sounds, per-sample levels, censor tones and chains:

1. **A player per lane.** Least work. No sample accuracy, the lanes drift against each other, 8 ms fades stay impossible, and it does not serve the export.
2. **A `CompositionPlayer` of sound only**, with the export's processors. The same graph as the export. But the API is experimental, `setComposition` makes its players anew on every edit (`docs/COMPOSITION-PLAYER.md:21-22`), and whether the positions a processor sees after a seek are timeline time is on that document's own list of things to test (`docs/COMPOSITION-PLAYER.md:52-53`).
3. **Our own mixer.** Decode each lane, apply the envelope, the censor and the chain — all of which are already pure functions in core (`AudioEnvelope`, `C/AudioLevels.kt:464`; `AudioCensor.render`, `C/AudioCensor.kt:353`; `AudioChainProcessor`, `C/AudioChain.kt:75`) — keep pitch at speed, write to one output. The same code renders the export's sound to one file (one sound sequence in the composition, every picture sequence without sound), the 16 kHz mix for transcription (S8), and the peak for the clipping warning (`C/ExportReceipt.kt:42-45`).

Recommended: 3, decided by a two-day comparison with 2 at the start of S5. With it the preview's clock becomes the sound's, as in any player.

### 5.4 Limits to measure before any number is put in the UI

The original's rule: "the data model has no two-video restriction. Device limits must be measured before setting a UI limit" (`OD:30-32`), and its stage 6 measures "decoder, memory and thermal load" (`OD:426-430`). Nothing below was measured for this plan.

| Limit | How to read it | Bounds |
|---|---|---|
| Hardware decoders at once | `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()` is an upper bound; the real number depends on resolution (performance points, API 29 and later). Measure by opening N at the project's size. | Picture lanes showing at once, in the preview and in the export, which also holds an encoder. |
| Largest texture | `GL_MAX_TEXTURE_SIZE` | Lanes times canvas height, in the export. |
| Shader cost | The defocus takes 48 samples a pixel and the whip pan 32, per side (`A/TransitionRendering.kt:140-164`). | A transition between two of several layers at 4K. |
| Memory | Each player buffers on its own. | Lanes in the preview. |
| Heat | A long export with three lanes. | Whether the limit holds for ten minutes. |
| Sound | Decoding and mixing N lanes against real time. | Sound lanes; expected not to bind. |

When an edit would pass the limit, the edit is refused with the reason. A layer is never dropped in silence, and the saved timeline never depends on the device: a project with more lanes than a phone can play opens there, shows what it can, and says so.

## 6. Before or after the switch

"Any time" means the timeline model does not touch it. "Before" means it is cheaper to do on the sequence model now. "After" means the original defines it on tracks and doing it now would be doing it twice.

| Item | When | Reason, from the source |
|---|---|---|
| **Range editing in the editor** | Before | The core is done on the sequence model (`C/RangeEditing.kt`, 748 lines). Its editor state is `TimelineSelection` under other names (2.2), so the screen survives the switch and only the bodies of five functions change. S6 needs this selection to exist in the UI. |
| **Still and flash-cut fixes** | Any time | Renderer work in `A/TransitionRendering.kt`. The held frame of a still becomes a generated region with `freezeVideo` (`T:471-476`, `T:889-895`), which the same kept-frame mechanism draws. |
| **Transcription of the edited mix** | After (S5, S8) | The words are attached to the sound items of the mix (`O/Subtitles.swift:205-247`), which Android's sound items cannot hold (`C/Project.kt:219-228`). Doing it now means a new field on `AudioItem`, a schema step, and a conversion for it. The 16 kHz mix itself is the engine of S5. |
| **Rotated and animated crop** | Any time | The crop is part of the picture's recipe (`O/Project.swift:53`; `C/Crop.kt:17-25` already has `rotation` and `motion`). Its clock is the clip's source time. Renderer work. |
| **Audio unlink in the editor** | After (S6) | The original's unlink "changes no samples, position, duration, gain or effects" (`OD:97-99`; `T:575-579`). Android's stand-in refuses clips with speed or noise reduction or busy tracks, turns transition ramps into fades approximately (`C/AudioUnlinking.kt:34-44`, `:115-122`), and loses the clip's captions at the next transcription (`docs/SUBTITLES-REVIEW.md`, the table of finding 8). Shipping it puts a shape into saved projects that 3.2 must then read for good, and a behaviour that S6 changes. |
| **0.5 dB levels** | Any time | A grid over `volumeDB` (`C/AudioLevels.kt:70-76`), a field both models have (`C/Project.kt:106`; `T:174`). |
| **8 ms cut fades** | Export: any time. Preview: S5 | In the export it is a term in the level function `SoundProcessor` already applies every 64 samples (`A/Titles.kt:117-147`). In the preview a level is a player's volume set once per display frame (`A/EditorScreen.kt:242-250`): 8 ms cannot be drawn with that. The original applies one envelope per sample in both (`O/TrackRenderer.swift:291`; `O/CutAudioEnvelope.swift:30-38`). |
| **Export progress and receipts** | Any time | Not on the timeline. A receipt stores the edit and compares it (`C/ExportReceipt.kt:124-135`); 3.5 covers the one seam. |
| **Export checkpoints** | Any time, on the plan of S0 | The sections are arithmetic on the tick clock (`C/ExportCheckpoint.kt:21-47`). Rendering one section needs the composition of a time slice; written against today's exporter it would be written again at S3a. |
| **Audio chain (Enhance audio)** | After (S7) | "There is one ordered chain per interval. Applying a different chain to part of an interval splits the interval" (`OD:225-229`); stored on the sound item (`T:178`), applied by `enhance` to a selection (`T:746-766`). The processing is ported (`C/AudioChain.kt`); the place to store it is not. If it must ship earlier, the cheap form is one chain for the whole project saved as the original's old `effects` key, which the conversion already defines (`O/Project.swift:183`; `T:518`). Decision D5. |
| **Censor** | After (S7) | Regions in a sound item's own ticks, with one tone shared between overlapping sounds by level and order (`O/AudioCensor.swift:52-66`, `:114-130`). `C/AudioCensor.kt` already matches the saved form and takes its items through an abstraction (`C/AudioCensor.kt:172-178`), so it drops in. It also needs per-sample sound in the preview. |
| **Recorder settings** | Any time | Capture ends at a source and its events (`C/Project.kt:27-51`). |
| **Video effects** | Looks: any time. Time effects: after S3b | An effect is part of the picture's recipe or of the source (`O/Project.swift:36`, `:58`). The exceptions are Freeze Frame, Step Printing and Undercranking: they *are* the playback map — holds, runs at changing rates and silent stretches (`O/VideoEffects/VideoEffectTiming.swift:5-60`, read by `T:49-79`) — and Android's clip has one rate and no holds (`C/Project.kt:168-172`). Effects with a background video bring sound into the mix (`O/TrackRenderer.swift:308-312`): after S5. |
| **Stabilisation** | Any time | A setting of the recipe that keeps its analysed range through splits (`O/StabilizationPath.swift:24-32`); the analysis is in source time. |

Three small things that are not on that list and are cheaper before:

- The guard of 3.4, point 1, in 0.7.
- S0: it is a refactor of the present exporter and player and helps checkpoints.
- Drawing the sound rows by first fit instead of by the saved `track` (D4).

## 7. Risks, unknowns, decisions

### 7.1 Losing data

| Risk | Guard | Stage |
|---|---|---|
| An older version opens a newer project as it was before and saves over it (3.4) | The three guards of 3.4. Test with a core built from `v0.6`. | 0.7, S3b |
| The conversion changes an edit | It is pure and deterministic; old steps are never rewritten; the inverse is exact on every golden step; `validate` and the prune's fixed point after every conversion. | S1 |
| A wrapper loses what it cannot see (a screen on the view saving over layers or unlinked sound) | The merge-back writes by id and touches nothing else. Property test: any edit through the view leaves every item the view cannot show as it was. | S2, then every visible stage |
| A plain cut a tick apart (3.2) | Boundaries first; property test. | S1 |
| A step that validates today and converts to a timeline that does not | The model asks for things today's check does not: ids unique across pictures and sounds (3.2, rule 7), at most 200 000 words on an item. Every step of every golden project, and of real libraries before release, must convert to a timeline that validates; a step that does not is reported and kept, never dropped. | S1, S3b |
| Restore or duplicate rewriting old steps in the new form under an old schema | Steps keep the form they were read in (3.1). Test: archive and restore a project with both kinds of step. | S3b |
| The movie of an existing project changes length (N6) | Kept as it is until S5; then D3. | S5 |
| A phone that cannot play a project's lanes | Refuse the edit that passes the limit; never depend on the device when saving (5.4). | S9 |

### 7.2 Speed on long projects

- **Compile, lanes, layout.** As written in Swift they are quadratic: `compile` filters every picture and sound at every edge (`T:986-999`), `timelineLanes` compares each item with those placed (`T:1744-1761`), the layout searches all pictures for each join (`O/Transitions.swift:278-297`), the prune searches the pictures for each relation (`T:856-859`). The original's timeline view calls `timelineLanes` at ten places (`O/EditorTimeline.swift:55` to `:972`). Port them with id indexes and one sweep over sorted edges, and compute them once per timeline: the type is immutable, so the results can be kept with it, as `TimelineEditing` keeps its last layout (`C/TimelineEditing.kt:10-29`). Budget for S1, on the host: all three for 2,000 pictures and 2,000 sounds in under 5 ms, written into a test.
- **Saving and opening.** See 3.5. Budget for S3b, on a mid-range phone: a commit of the long project (2,000 clips, 300 steps) under 100 ms; the library with ten such projects under a second. If that cannot be met by keeping encoded bytes and not re-reading checked files, the next step is the original's deferred history (`O/Project.swift:223-227`, `:616-630`): M to L, a stage of its own.
- **Disk.** 300 steps of a 500-clip project grow from about 18 MB to about 140 MB [E]. Neither app caps the history.

### 7.3 Decisions for the product owner

| | Question | Recommended |
|---|---|---|
| **D1** | Should an Android project and an iOS project be interchangeable? | No. Keep names and units equal so that a one-way import can be written later, and go on saying so in the README. Interchange would first need Android to keep content it does not model (3.6). |
| **D2** | Release the new engine with the old format (after S2 and S3a) before the format changes? | Yes, one release. It costs nothing in function, and going back from it is installing the version before. |
| **D3** | When sound runs past the last picture, does the movie go on in black (the original) or end with the picture (today)? | The original's rule, from S5. A project that has such sound keeps its length until its owner is shown the new one once and chooses to keep the sound or trim it to the picture. Never change the length of an existing movie in silence. |
| **D4** | In S3b a sound alone on the second track would be drawn on the first row, since `track` is not stored. Accept, or keep a row with each sound? | Store nothing. Draw the rows by first fit in the release before S3b, so the switch itself changes nothing. |
| **D5** | Enhance audio before S7, as one chain for the whole project? | No, unless it is wanted for a release before S5: the original itself went on to "Replace the global chain UI and lookup with interval-aware processing" (`OD:417-418`). |
| **D6** | Pauses as a setting of the clip (one clip, re-editable) instead of separate pieces (N3; F11 of the transitions review)? | Yes, as a small stage of its own after S4. It needs `PauseSettings` on the clip (`C/Pauses.kt:41`) and makes "remove pauses" something that can be opened again. |
| **D7** | How many picture lanes? | Whatever 5.4 measures, per device, with two as the least. Decide at S9 with the numbers. |
| **D8** | After S3b an edited project cannot be opened by 0.7 or earlier, and neither can its ZIP. Accept? | Yes. It is the same refusal every schema step has had; the pinned manifest keeps the earlier state. |
| **D9** | The order of the visible stages: transitions first (S4), then sound (S5…S8), then picture (S9…S11)? | Yes. Unlink before sound lanes would have to refuse wherever a third sound would play, as the stand-in does today. The picture branch can go before the sound branch if layers are wanted sooner; S4 stays first either way. |

### 7.4 The largest unknowns

1. Media3 with three or more video sequences in one export through a compositor of our own: never run here. S9 starts with that test.
2. Which audio engine (5.3). S5 starts with that comparison.
3. Device limits (5.4).
4. Whether real libraries hold steps whose timeline the model refuses (7.1).
5. How the 973 tests divide between "equal through the port" and "kept by a wrapper". The parity suite of S2 answers it; N1…N13 is the expectation, from reading, not from running.

## 8. Not determined

- **The JSON Swift writes** for a dictionary keyed by UUID, a set, and an enum with values (3.6) is stated from the language's rules. No saved iOS project was available, and there is no Swift toolchain on the machine this was written on.
- **Schema 1, 3 and 4 of the original**: its repository has one commit.
- **The original's tests** were read, not run. Line numbers of tests are those of the assertions.
- **Schema 14 is taken** (a crop with a rotation or a motion, on `main` since 0.8 was started), so wherever this plan says 14 for the track timeline, read 15.
- **Schema 15 is taken** as well (a fade longer than half its item, or cut by a split so that a piece goes on from inside it: `ClipAudio.reshaped`, `C/Project.kt`), so read 16 there; `TrackTimeline.SCHEMA` says 16.
- **Branches other than `main`** were not read. Whether 0.7 has taken schema 14, and whether unlinking has reached the editor there, is not known here; 3.2 covers the unlinked shape either way.
- **Every number about a device** — decoders, texture size, time to save, time to open — is absent or an estimate. The byte sizes in 3.5 and 7.2 are counted from the JSON shapes, not measured.
- **Whether a video sequence of gaps only can be exported** (5.2).
- **`CompositionPlayer` in Media3 1.12**: `docs/COMPOSITION-PLAYER.md` says to look again when it is stable. This plan does not depend on the answer.
- **The days in section 0** are estimates from line counts on both sides and from the 94 lines of `app` that call the editing functions of `core`. They assume one person who knows both code bases.
