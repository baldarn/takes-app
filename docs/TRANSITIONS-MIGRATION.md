# Transitions: moving the saved project to `TransitionSettings`

Carried out with Option A (section 6); what follows is the plan as it was written, and section 10 says where the work departed from it. It moves the saved project from the two-style `ClipTransition` (`core/.../Project.kt:59-79`) to the original's full settings (`core/.../TransitionSettings.kt`), and the app from `TimelineEditing.overlap / fadeLength / weight / level` to the functions added in `TransitionPicture.kt`, `TransitionSound.kt` and `TransitionLayout.kt`.

Line numbers are those of the branch this was written on (main at 0.5 plus the transitions specification). Paths starting with `YouRec/` are in the original app's repository. See `docs/TRANSITIONS-SPEC.md` for the styles themselves.

## 1. What is saved today

A project is a manifest (`project.json`) and one JSON file per history step, named by the SHA-256 of its bytes (`Project.manifest`, `Project.kt:466-475`; `EditSnapshot.data`, `Project.kt:219`). Every save, ZIP export, restore and duplicate **writes the steps again from the model** and names them by their new hash. So an old step keeps its bytes and its file name only if the model encodes it exactly as before.

The encoder is `documentJson`: `encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true` (`Project.kt:12`). A transition is the `transition` field of the clip before the join:

```json
{"id":"…","nextClipID":"…","style":"dissolve","duration":1.0}
```

`style` is `"fadeBlack"` or `"dissolve"`; `duration` is at most 10. All four fields are always written, including `style` when it is the model's default: `encodeDefaults` has been on since the first commit and transitions arrived after it, so no saved transition lacks a `style`.

## 2. The pattern this repository uses for a format step

1. New fields are optional and null by default; `explicitNulls = false` leaves them out of the file (for example `TimelineClip.speed`, `crop`, `color`, `subtitleWords`, `Project.kt:133-144`).
2. `TimelineClip.schema` (`Project.kt:159-169`) names the oldest schema whose readers understand everything the clip uses.
3. `Project.commit` raises `schemaVersion` to the highest one used by the committed state, never lowering it (`Project.kt:339`); `addRecording` does the same (`Project.kt:385, 398`).
4. `Project.validate` refuses anything above `LATEST_READABLE_SCHEMA` (`Project.kt:412, 477`), so an older app refuses a project rather than silently dropping what it does not know.
5. `ProjectFormatTest` (`core/src/test/.../ProjectFormatTest.kt`) pins the bytes of projects that use none of the newer features.

The transition step follows all five.

## 3. Schema number

`LATEST_READABLE_SCHEMA` is **11** on this branch and on main (subtitles, `Project.kt:160, 477`). The title styling branch takes **12** (`texts.orEmpty().any { it.styled } -> 12` and `LATEST_READABLE_SCHEMA = 12` in its `Project.kt`). Transitions therefore take **13**, as expected.

Merge order matters: `LATEST_READABLE_SCHEMA = 13` says the app also reads 12. Either title styling is merged first, or this step is merged with the constant left at its current value plus the `-> 13` rule and raised only once 12 is understood.

## 4. Fields

### 4.1 `TimelineClip` (`Project.kt:126-144`)

| JSON name | Type | Default | Meaning |
|---|---|---|---|
| `transition` | `TransitionSettings?` (was `ClipTransition?`) | absent | The join to the clip after this one. On the **last** clip, with `nextClipID` absent, it is the closing transition of the timeline. Same key as today. |
| `openingTransition` | `TransitionSettings?` | absent | The transition into this clip from nothing. Honoured on the first clip only, as in the original (`YouRec/Project.swift:56`, `YouRec/Transitions.swift:207`). New. |

Nothing else is added to the clip, the edit state or the manifest. In particular the original's stored `origin` and `factor` (`TimelineTransition`, `YouRec/TrackTimeline.swift:462-470`) are **not** saved: the Android timeline is a plain sequence, and `TransitionLayout.sequence` derives both from the clips, exactly as the original's own sequence layout does (`TimelineLayout(clips:)`, `YouRec/Transitions.swift:198-252`). `TransitionLayout.Arrangement` / `Relation` are there for the day clips can be placed freely; they would then need two more fields (`origin`, `factor`) and a later schema.

### 4.2 `TransitionSettings` (one saved object, replacing `ClipTransition`)

| JSON name | Type | Default when absent | Written |
|---|---|---|---|
| `id` | string | new id | always |
| `nextClipID` | string? | null (closing) | when set |
| `style` | `"dissolve"`, `"fadeBlack"`, `"wipe"`, `"split"`, `"hitchcock"`, `"whip"`, `"flashCut"`, `"still"`, `"defocus"` | `"dissolve"` | always |
| `duration` | number, seconds, `> 0`, `≤ 604800` | 0.5 | always |
| `axis` | `"horizontal"` / `"vertical"` | `"horizontal"` | **only when not the default** (change, see below) |
| `divider` | number 0.15…0.85 | 0.5 | only when not the default |
| `reverse` | bool | false | only when not the default |
| `audio` | `"crossfade"` / `"first"` / `"second"` | `"crossfade"` | only when not the default |
| `first`, `second` | `{x, y, zoom}` | `{0.5, 0.5, 1.0}` | only when not the default |
| `fadeColor` | `{red, green, blue}` 0…1 | absent = black | when set |
| `hold` | number 0…604800 | absent = 0 | when set |
| `audioOffset` | number, abs ≤ 604800 | absent = 0 | when set |
| `audioBlend` | number 0…604800 | absent = style default | when set |
| `wipeAngle` | number −360…360 | absent = 0 or 90 from `axis` | when set |
| `hardEdge` | bool | absent = soft | when set |
| `highlightsFirst` | bool | absent = shadows first | when set |
| `flashCount` | int 1…20 | absent = 3 | when set |
| `flashAcceleration` | number 0.25…4 | absent = 2 | when set |
| `stillDissolve` | number 0…604800 | absent or 0 = cut | when set |
| `blurAmount` | number 0…1 | absent = 0.75 | when set |

**The one change needed in an existing file.** Today `TransitionSettings` writes `axis`, `divider`, `reverse`, `audio`, `first` and `second` always (they are not nullable and `encodeDefaults` is on; pinned by `TransitionGeometryTest.settingsWriteTheOriginalFieldsAndReadTheExistingTransition`, `TransitionGeometryTest.kt:323-329`). Used as the saved type as it is, every old transition would be written with six more fields: new bytes, a new file name for every history step that has a transition. To keep old bytes, mark those six properties `@EncodeDefault(EncodeDefault.Mode.NEVER)` (kotlinx.serialization; needs `@OptIn(ExperimentalSerializationApi::class)`). The declaration order `id, nextClipID, style, duration` already matches the old type, so the result for an old transition is the same string. This was tried on a copy of the type: an old dissolve and an old fade, read and written back, give identical JSON. The first assertion of the test named above then changes to the four-field form.

The original's Swift type writes those six fields always and needs them when reading. Android projects are not opened by the iPhone app today (different manifest; the restore refuses the iPhone archive version, `ProjectRestore.kt:198-199`), so this is not a regression. If that exchange is ever wanted, write the six fields whenever the project is at schema 13 or above.

### 4.3 When a clip needs schema 13

Add first in `TimelineClip.schema`:

```
openingTransition != null || transition?.legacy == false -> 13
```

with `TransitionSettings.legacy` true only when an older app would read and play it the same way it was made: `style` is dissolve or fade through color; `nextClipID` is set; `duration ≤ 10`; `axis`, `divider`, `reverse`, `audio`, `first`, `second` at their defaults; every optional field null. (The original's `newFeatures` is not this: it is relative to the iPhone app's first four styles.) `transition != null -> 2` stays as the last resort for legacy ones.

`Project.validate` checks both fields with `TransitionSettings.valid` (`Project.kt:450`).

## 5. Reading existing projects

- An old file decodes into the new type with no conversion step: the four old fields are the first four new ones, the rest take their defaults. `fadeBlack` is the saved name of fade through color; black is an absent `fadeColor`.
- Written back, it is the same bytes (section 4.2), so the history file names, the manifest and the schema number do not change. Opening and saving a project does not touch it.
- It stays so until the person uses something the old format cannot say: a third style, a colour, a hold, custom sound, a duration over 10 s, an opening or a closing transition. That commit raises the schema to 13 and older apps then refuse the project instead of flattening it. Changing an old dissolve from 1 s to 2 s, or a fade to a dissolve, keeps schema 2.
- Old steps in the undo history are not rewritten when a later step reaches schema 13; only the manifest's `schemaVersion` changes.

Guard: a new `ProjectFormatTest` case with a project holding one old dissolve and one old fade, asserting the snapshot string byte for byte and `schemaVersion == 2`; and one asserting that a wipe, a hold, an opening and a 12 s dissolve each give 13.

## 6. How the two old styles map

| Old | New | Picture | Length | Sound |
|---|---|---|---|---|
| `FadeBlack`, d | `fadeBlack` (fade through color), `duration` d, no colour, no hold | Same: linear to black and back in both | Same while d ≤ both clips; see below | Same |
| `Dissolve`, d | `dissolve`, `duration` d | **Curve changes**: linear today, t²(3 − 2t) in the original | Same while d ≤ half of both clips; see below | Same |

Defaults: the old model's default style is `FadeBlack` (`Project.kt:62`, `TimelineEditing.kt:173`), the original's is dissolve. No saved file depends on the default (section 1), so only new transitions are affected, and the dialog already starts on dissolve (`EditorScreen.kt:1449`). `setTransition` should lose its default style.

Two things render differently for a transition saved by today's app:

1. **The dissolve curve.** The share of the incoming picture is `t` today and `t²(3 − 2t)` in the original. They agree at the start, the middle and the end; in between they differ by at most 0.096 (at 21% and 79% of the way). Sound is a linear ramp in both, unchanged (`TransitionSoundTest.aDefaultDissolveSoundsAsItDoesToday`, `aDefaultFadeThroughBlackSoundsAsItDoesToday`).
2. **Short clips.** Today a dissolve overlaps by `min(d, A/2, B/2)` and a fade takes `min(d/2, A/2, B/2)` on each side (`TimelineEditing.kt:189-201`). The original lets a transition take all the footage the neighbouring transitions leave free (`TransitionLayout.sequence`). Whenever today's half-clip limit was not reached the two give the same timing, also with a transition on each side of a clip (two halves always fit). Where it was reached, the original's dissolve is longer and the movie shorter: a 2 s dissolve out of a 1 s clip overlaps 0.5 s today and 1 s in the original (`TransitionSoundTest.shortClipsAreWhereTodaysSoundAndTheOriginalsPartWays`).

### Option A: old projects adopt the original's curve and fitting (recommended)

- Nothing is added to the format. One renderer, one layout, the same as the original.
- Consequence: an existing project's dissolves ease in and out instead of moving evenly, and a dissolve that was limited by a short clip gets longer, so that movie gets shorter by the difference. Fades through black look the same; a fade limited by a short clip gets longer on each side without changing the movie's length. Sound keeps its shape.
- A project saved by the new app with only old-style transitions stays at schema 2; an older app still opens it and plays it its own way (linear, half-clip limit). The same file can therefore look slightly different in two app versions. This is the price of leaving old files untouched.

### Option B: old transitions keep today's look

- Needs a mark in the file. Absence must mean "old", since old files cannot be changed, so every transition made by the new app carries a new field (say `"timing":2`) and takes schema 13, even a plain dissolve.
- Consequence: old projects render exactly as before, for ever. The core keeps two dissolve curves and two fitting rules (`overlap`/`fadeLength` stay), the renderer two paths, and the saved object gains a field the original does not have. Editing an old transition has to decide whether it stays old.

**Recommendation: A.** It is the original's behaviour with no extra state, the difference is small and confined to dissolves, and it keeps old files byte-identical. If a visible change to finished projects is not acceptable, B is the fallback; it can also be added later for nothing, because an absent mark already means "old".

## 7. What changes in the code, in order

Each step leaves the full core suite green (`./gradlew :core:test`).

### Step 1. The saved type (core, format only)

- `TransitionSettings.kt`: `@EncodeDefault(NEVER)` on the six fields; add `legacy`.
- `Project.kt:59-79`: remove `ClipTransition` (a `typealias ClipTransition = TransitionSettings` keeps the rest compiling while the steps land). `Project.kt:132`: type of `transition`; add `openingTransition`. `Project.kt:159-169`: the `-> 13` rule. `Project.kt:450`: validate both. `Project.kt:477`: `LATEST_READABLE_SCHEMA = 13`.
- Guards: the two new `ProjectFormatTest` cases (section 5); `TransitionGeometryTest.kt:323-345` updated; a round trip of a transition with every field set through `Project.manifest` / `readManifest`; `ProjectArchiveTest`, `ProjectRestoreTest`, `ProjectDuplicateTest` unchanged and green.

### Step 2. Positions and joins (`TimelineEditing.kt`)

One layout per clip list replaces the per-call sums: `TransitionLayout.sequence(clips.map { it.duration }, listOf(clips.first().openingTransition) + clips.map { it.transition }, clips.map { it.id })`. It already ignores invalid settings and a transition whose `nextClipID` is not the next clip.

| Function | Line | Change |
|---|---|---|
| `clipStart` | 11 | `placements[index].start`; `clipStart(clips, size)` (the end) becomes `duration` |
| `duration` | 17 | `Sequence.duration` (a closing freeze or an opening hold adds time) |
| `locate` | 24 | by `Placement.lower … upper` (the join's `time` is the boundary); a colour hold or a freeze has no clip showing, so callers that draw must ask the layout for the transition first |
| `chains` | 38 | by `Placement.incoming / outgoing > 0`; every overlapping style splits chains, not only dissolve |
| `split` | 56-76 | refuse inside any transition's `range` (today: inside a dissolve, l. 61); the first piece keeps `openingTransition`, the last keeps `transition` (l. 68, 153) |
| `delete`, `move`, `insert` | 78-81, 83-88, 99-112 | `withoutBrokenJoins` (l. 164) as today for joins between clips. Decide for the two ends: the original's sequence layout ignores an `openingTransition` that is not on the first clip and a closing one not on the last (`Transitions.swift:207`) without deleting them |
| `transition` | 169 | returns `TransitionSettings?` |
| `setTransition` | 173-183 | takes `TransitionSettings?` and a join index 0…count (0 opening, count closing); sets `nextClipID`; refuses a transition whose fitted picture is under one frame (`TransitionLayout.longEnough`); no default style; no 10 s cap |
| `fadeLength`, `overlap`, `weight`, `level(clips, …)` | 189-212 | removed once nothing calls them; `level(offset, duration, fadeIn, fadeOut)` (l. 215) stays for a clip's own fades |
| `titlesAt` | 231-239 | the original mixes each clip's titles with its picture; return each title with its clip so the renderer draws it into that side (for split screen: both, once, over the pair) |
| `soundLevel` | 306-311 | `audio.gain · own · TransitionSound.clipLevel(time, joins[index].audioIn, joins[index + 1].audioOut)`, with `null` where the join has no transition |

Guards: `TimelineEditingTest` unchanged for cuts and for dissolves and fades on ordinary clips (same starts, same lengths, same sound); `TransitionLayoutTest` for the fitting; new cases for split inside each style, for an insert at a join, for opening and closing; the short-clip cases re-pinned to the original's numbers (Option A).

Other core callers that follow from this step:

- `AudioUnlinking.kt:58-60` (`ramps`): the lengths of `audioIn` / `audioOut` instead of `fadeLength + overlap`. With a custom sound offset a ramp is no longer at the clip's edge and cannot become a plain fade; unlinking should then be refused or the offset dropped. In the original, detached sound is simply not shaped by the transition (`YouRec/TrackRenderer.swift:271-294`).
- `OpenTimeline.kt:145-159` (dissolve as `SMPTE_Dissolve`) and `:215-219` (fade as a marker): the other overlapping styles need a transition entry of their own kind, the inserted-time styles a marker; `:131-132, 149-150, 209` use `clipStart`.
- `SubtitleEditing.kt:26, 46`, `UnusedTakes.kt:51`, `Project.kt:392`: positions only; they follow `clipStart`.

### Step 3. The app (read only here; lines of `app/src/main/kotlin/app/takes/`)

| File:line | Today | Becomes |
|---|---|---|
| `EditorScreen.kt:50` | imports `ClipTransition` | `TransitionSettings`, `TransitionStyle` |
| `EditorScreen.kt:267-268` | two decks from `chains` | unchanged in shape; every overlapping style alternates decks |
| `EditorScreen.kt:534-538` | `level`, `weight`, front deck | `TransitionPicture.frame(settings, join.timing, playhead − join.origin, canvas, join.edge, fps)` |
| `EditorScreen.kt:540-548` | deck volume from `soundLevel` and `overlap` | `soundLevel` of each of the two clips at the playhead (step 2) |
| `EditorScreen.kt:629` | deck 0 alpha = linear share | the frame's numbers (shader or layer per style) |
| `EditorScreen.kt:651` | black overlay `1 − level` | the fade's `solid` weight in its colour |
| `EditorScreen.kt:734-735` | button enabled only before the last clip | also at the opening and the close |
| `EditorScreen.kt:915-924` | `TransitionDialog`, `setTransition(edit, index, seconds, style)` | the full editor; `TransitionDefaults.new`, `choosing`, `durationMaximum` |
| `EditorScreen.kt:1001-1002`, `1127-1136` | tags "Dissolve" / "Fade" from `fadeLength`, `overlap` | from `Placement.incoming` and the join's style title |
| `EditorScreen.kt:1448-1460` | two styles, four presets | nine styles and their controls (specification, section 2) |
| `EditorScreen.kt:284, 291-292, 324, 404, 479, 496, 502, 578, 815, 833, 841, 883, 920, 949, 993, 997` | `clipStart`, `locate`, `duration` | unchanged calls; they follow step 2 |
| `Titles.kt:72-74` (`TitleOverlay`) | titles once over the blend, opacity = linear share × fade level | titles drawn into each clip's picture before the mix |
| `Titles.kt:87-92` (`FadeEffect`) | RGB scaled to black per clip | the fade's weights and colour, applied in linear light |
| `Titles.kt:139-156` (`DissolveCompositor`) | alpha of the top sequence = `weight` | a compositor that takes a `TransitionFrame` per output frame |
| `Exporter.kt:163-164, 184` | `fadeLength`, `FadeEffect` | as above |
| `Exporter.kt:186-187, 238` | `transition`, `soundLevel` | `soundLevel` (step 2); "shaped" when either join has a transition |
| `Exporter.kt:165, 197, 220, 234, 258-280` | `clipStart`, `chains`, two alternating sequences | same structure; a still needs the held frame (`TransitionPicture.heldFrame`), a colour hold or an opening freeze needs a gap filled with `TransitionPicture.fill` |

Order inside the app: first compile against step 2 with the dialog still offering only dissolve and fade (no visible change except Option A's curve); then the renderer style by style (dissolve eased, fade with colour and hold, wipe, split, Hitchcock, flash cut, still, whip, defocus); the editor sheet last, one style at a time as its renderer lands, so no project can hold a style the app cannot draw.

Guards for the app: the host tests of the core for every number; on a device, the original's own bar, an average difference under 9/255 between preview and export at four moments around the join (`Tests/TransitionTests.swift:144-152`).

## 8. Undo history, ZIP export and restore, duplication

- **Undo history.** A step is a whole `EditState`; transitions travel inside its clips, so undo and redo need nothing new. Old steps keep their bytes and names (section 5). `schemaVersion` only rises: after a new-style transition is undone the project stays at 13 while the step is still in the redo history, and after it, as for every earlier format step. `Project.commit` compares `clips` (`Project.kt:335`), so changing only a transition's setting is a step, as today.
- **Takes recorded later.** `Project.materialized` (`Project.kt:296-300`) appends takes after a step's clips without going through `TimelineEditing`. A closing transition on what was the last clip is then no longer at the end and is ignored by the layout (its `nextClipID` is absent, the next clip exists). Decide whether that is wanted or whether the closing transition should follow the end; the original ties it to its clip.
- **ZIP export** (`ProjectArchive.kt:48-67`): the manifest and steps are the bytes `ProjectDisk.save` writes, so an old project's archive is unchanged and a new one carries schema 13. The OpenTimeline file inside follows step 2.
- **Restore** (`ProjectRestore.kt:184-229`): `readManifest` validates, so an archive at schema 13 is refused by an older app and accepted by the new one; steps are stored again under the names the current model gives them and others are deleted (`:224-227`), which for old steps are the same names.
- **Duplicate** (`ProjectDuplicate.kt:84-146`): the copy is the same model with a new project id; clip ids are kept, so every `nextClipID` still points at its clip. Nothing to change.
- Guards: one case each in `ProjectArchiveTest`, `ProjectRestoreTest` and `ProjectDuplicateTest` with an opening, a closing and a full-featured transition, asserting the edit is equal after the round trip and the history file names are equal for a project with only old transitions.

## 9. Open points for the integrator

1. Option A or B (section 6).
2. Opening and closing transitions when a clip is added before the first or after the last one (sections 7 and 8).
3. Unlinking a clip's sound under a transition with a custom offset (section 7, `AudioUnlinking`).
4. Whether the Android compositor blends in linear light. All the core's numbers assume it, as the original does (`YouRec/VideoToolCompositor.swift:194`).

## 10. As carried out

- Sections 4, 5 and 6 as written, Option A. `ClipTransition` is now a type alias of `TransitionSettings`.
- Section 7, step 2: `TimelineEditing.layout(clips)` is the one layout; `setJoin(state, join, settings)` sets any join (0 the opening, the number of clips the close) and `setTransition` remains as a short form for a join between two clips. `overlap` stays as a reading of the layout; `fadeLength`, `weight` and `level(clips, …)` are gone.
- Open point 2: moving, deleting or inserting clips removes an opening that is no longer on the first clip and a close that is no longer on the last, as it removes a join whose neighbor changed. A take recorded later is appended without an edit, so a close on the clip before it stays saved, unused, and counts again if that take is removed.
- Open point 3: `AudioUnlinking` takes the lengths of the two sound ramps; a ramp moved by a custom offset is still turned into a fade at the clip's edge. The app does not offer unlinking.
- Open point 4: Media3's own compositor lays one sequence over the other by opacity on encoded pixels. The app no longer uses it to blend: the two sequences are laid one above the other untouched and one shader (`TransitionShader`, the same text in the preview and in the export) makes each frame from them in linear light.
- New in the core: `TransitionScene` (what a moment shows: the two sides, their decks, a held frame, the fill) with `TransitionSceneTest`.
- Titles are still drawn once over the finished frame, each as opaque as its clip is showing, not into each clip's picture before the mix (section 7, `Titles.kt:72-74`).
- Section 4.1 said the fit is not saved. It is now: `transitionFit` and `openingTransitionFit` on the clip, each `{"factor": n}`, inside schema 13. `docs/TRANSITIONS-FIT.md` has the format and what every edit does to a fit; its section 8 says how it was carried out.
