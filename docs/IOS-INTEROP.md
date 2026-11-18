# Exchanging project ZIPs with YouRec (iOS): specification

Goal: Takes restores a ZIP made by YouRec's "Zip project", and writes a ZIP
that the current YouRec restores, changing Takes only. YouRec is the
reference: `latestReadableSchema = 12`.

Path prefixes used throughout:

| Prefix | Path |
|---|---|
| `S/` | `/home/baldarn/Projects/YouRecApp/YouRec/` (Swift, read-only) |
| `ZF/` | `/home/baldarn/Projects/YouRecApp/ThirdParty/ZIPFoundation/Sources/ZIPFoundation/` (vendored ZIP library) |
| `K/` | `core/src/main/kotlin/app/takes/core/` |
| `A/` | `app/src/main/kotlin/app/takes/` |

Line numbers are those of YouRec `faf1815` and Takes `471edc74`.

---

## 0. Summary

The two models are close ports. Takes' `TrackTimeline`, the video-effect
model, the titles, subtitles, transitions, censor, colour, LUT, font and
audio-model types use the original's key names, units and raw values. Every
feature Takes has can be represented in iOS at schema 12 or below. The work
is a JSON codec plus archive rules, not a model change. What must be done,
by severity:

**Hard blockers.** If any of these is missing, YouRec refuses the whole ZIP.

1. **Manifest shape.**
   - Dates are a Double of seconds since 2001, not a Long of ms since 1970.
   - `captureLedger` is `[{clip, insertion}]`.
   - `schemaVersion` must be 12 or lower.
2. **Every history step is written in timeline form.** The Takes sequence
   form `{"state":{"clips":…}}` is read by iOS through a legacy path that
   silently drops sound items, subtitles, clip sound and bookmarks
   (S/Project.swift:179-184).
3. **`VideoPlacementRegion.videos`** is written as Swift's flat
   `[key, value, key, value]` array, not as an object.
4. **`ClipTransition`.** Write the six keys Takes leaves out when they hold
   their default (`axis`, `divider`, `reverse`, `audio`, `first`, `second`;
   K/TransitionSettings.kt:101-107). Swift requires them.
5. **`TextOverlay`.** Write `style` and `color`; Swift requires them.
6. **The archive holds exactly the files iOS computes as `mediaFiles`.**
   Restore refuses any extra entry (S/ProjectArchive.swift:334-341).
   - So effect analysis made by Takes must be stripped and its files left out.
   - `effect-*.webp` and `effect-*.m4v` must be converted or renamed, or
     `validate()` refuses (S/Project.swift:458-461).
7. **Every id is a UUID string.** Swift's `UUID` decoding fails otherwise.

**Silent damage if omitted.**
- Write `"automaticCanvasVersion":1` and `"recordingHistoryVersion":1`.
  Without them iOS rewrites the history and canvas sizes on load
  (S/Project.swift:639-640).

**Representable but changed.**
- Takes' captured-effect analysis is dropped, and iOS recomputes it.
- The subtitle timing fingerprint is recomputed, or set to `""`.
- iOS `canvasReference` becomes a fixed `outputSize`, or is dropped.
- iOS `SourceMedia.videoEffects` goes to the `.effects.json` sidecar.
- `.caf` and `.aif` audio is converted to WAV for Android.

---

## 1. Encoding rules, and the type graph

### 1.1 How each side encodes

| | YouRec (Swift) | Takes (Kotlin) |
|---|---|---|
| Encoder | `JSONEncoder()`, default strategies. Steps use `.sortedKeys` (S/Project.swift:211). The manifest is re-serialised by `JSONSerialization` with `.sortedKeys` (:554-557). | `documentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }` (K/Project.kt:13). |
| Unknown keys on read | Ignored. | Ignored. |
| Absent key on read | Synthesized `Decodable` **requires every non-Optional property, even one with a default value**. Optional properties use `decodeIfPresent`. Only types with a custom `init(from:)` relax this; they are listed per type below. | Absent → the Kotlin default. |
| nil / null on write | Optional properties are left out (`encodeIfPresent`). | Nulls are left out. Defaults are written, except the six `@EncodeDefault(NEVER)` keys of `TransitionSettings` (K/TransitionSettings.kt:101-107). |
| `Date` | Double, seconds since 2001-01-01T00:00:00Z (`timeIntervalSinceReferenceDate`). | `Long`, ms since 1970 (K/Project.kt:50, :589-590). |
| `UUID` | Uppercase string. Decoding accepts either case and **fails on anything that is not a UUID**. | `String`. `newID()` gives an uppercase UUID (K/Project.kt:11); `counterpart`/`alias` append `~sound`/`~item` to an id that is not a UUID (K/TrackTimeline.kt:393-434). |
| `[UUID: X]` | Flat array `[k1, v1, k2, v2, …]`. | Object `{"k1": v1}` (K/TrackTimeline.kt:41-47). |
| `[String: X]` | Object. | Object. |
| `Set<UUID>` | Array, in no defined order. | Sorted array (K/TrackTimeline.kt:33-39). Either side reads either. |
| Enum with associated values | `{"case":{"label":v}}`. An unlabelled value goes under `"_0"`; a case with no value is `{"case":{}}`. | Hand-written serialisers in the same shape (`BookmarkAnchorSerializer` K/TrackTimeline.kt:190-221, `EffectValueSerializer` K/VideoEffectModel.kt:158, `EffectParameterKindSerializer` :277). |
| `CGSize` | `[w, h]`. | `SizeArraySerializer` writes `[w, h]` (K/Project.kt:405-414). |
| `CGRect` | `[[x, y], [w, h]]`. | Not used in project JSON. |

**Date conversion:**
- `ios = takesMs / 1000.0 − 978307200.0`
- `takesMs = round((ios + 978307200.0) × 1000)`

**Implementation stance.** Do not write a second set of data classes.
Transform the `JsonElement` tree, and run Takes' own serialisers on the
Takes side. The transforms are listed per type in the tables below and
collected in §7.2.

### 1.2 Type graph

The root of YouRec's archive is `project.json`, a `Project` header with
`snapshotFiles` (S/Project.swift:229-235, :542-558). Each name in it is a
`History/<sha256>.json` holding an `EditSnapshot`. Takes' root is
`Manifest` (K/Project.kt:584-600), and its history files hold an
`EditSnapshot` (K/Project.kt:458-549).

```
Project ─┬─ exportSettings: ExportSettings
         ├─ sources[]: SourceMedia ─┬─ events[]: CaptureEvent
         │                          └─ videoEffects[]: VideoEffectSettings   (iOS only)
         ├─ captureLedger[]: CaptureLedgerEntry{clip: TimelineClip, insertion}   (Takes: [TimelineClip] + captureInsertions)
         ├─ recordingSite: RecordingInsertionSite{site: VideoInsertionSite, firstCapture}   (iOS only)
         └─ snapshotFiles[] → EditSnapshot{state: EditState, captureCursor}
EditState ─┬─ timeline: TrackTimeline   (Takes: optional; sequence form instead holds clips/audio/subtitles)
           ├─ outputSize: CGSize?
           └─ canvasReference: UUID?   (iOS only)
TrackTimeline ─┬─ video[]: VideoTimelineItem{content: TimelineClip, mapping: SourcePlaybackMap{runs[]: PlaybackRun}, start/localOrigin: EditTime}
               ├─ audio[]: AudioTimelineItem ─┬─ mapping, fadeIn/fadeOut: AudioEdgeFade
               │                              ├─ enhancements[]: AudioEnhancementRegion{interval: EditInterval, effects[]: AudioEffect{kind: EffectKind, model: AudioModel}}
               │                              ├─ censorRegions[]: AudioCensorRegion{settings: AudioCensorSettings}
               │                              └─ subtitleWords[]: SubtitleWord
               ├─ links[]: TimelineLink
               ├─ bookmarks[]: TimelineBookmark{anchor: BookmarkAnchor}
               ├─ placements[]: VideoPlacementRegion{videos: [UUID: VideoPlacement{framing: TransitionFraming, motion: EffectAnimation, edgeCrop: PlacementEdgeCrop}]}
               ├─ transitions[]: TimelineTransition{settings: ClipTransition{fadeColor: TransitionColor, first/second: TransitionFraming}}
               ├─ generated[]: TimelineGeneratedRegion
               └─ subtitles: SubtitleSettings{appearance: TitleDesign, motion: TitleMotion}
TimelineClip ─┬─ stabilization: StabilizationSettings
              ├─ speed: SpeedSettings
              ├─ color: ColorSettings{imported: ImportedLUT, motion: EffectAnimation}
              ├─ texts[]: TextOverlay{appearance: TitleDesign{gradient: TitleGradient{TitleInk}}, motion: TitleMotion}
              ├─ pauses: PauseSettings{cuts[]: SourceInterval, candidates[]: DetectedPause}
              ├─ crop: CropSettings{motion}
              ├─ mirror: MirrorSettings
              ├─ images[]: ImageOverlay{motion}
              ├─ openingTransition / transition: ClipTransition
              ├─ videoEffects[]: VideoEffectSettings ─┬─ parameters[]: EffectParameter{kind: EffectParameterKind, value: EffectValue, keyframes[]: EffectKeyframe}
              │                                       ├─ palette[]: EffectRGB, lockedPalette: EffectPalette
              │                                       ├─ motion: EffectAnimation{a/b: EffectPose{look: ColorLook, lut: ImportedLUT}, timing: EffectTiming, clock[]: StrobeTimePoint}
              │                                       └─ strobeTimeMap[]: StrobeTimePoint
              ├─ frozenFrame: FrozenFrameSegment
              └─ (Takes only) audio: ClipAudio, bookmarks[]: Bookmark, subtitleWords[], transitionFit/openingTransitionFit: TransitionFit
```

Not in project JSON, and so outside this spec:
- `ExportReceipt` (S/Project.swift:672; K/ExportReceipt.kt);
- `ProjectDeletion`;
- `ExportCheckpoint`;
- `CapturedEffectAnalysis`, which is an effect file (see §5);
- `DualCameraLayout`, which is a recorder preference on both sides (see §2.4).

### 1.3 Field tables: project, sources, ledger, steps

Legend:
- **R** = required on the Swift decode. **o** = optional.
- **=** = copy the value unchanged.
- "iOS→T" is the import direction; "T→iOS" is the export direction.

#### `Project` (S/Project.swift:229-271, decode :605-642, encode :559-571, header :542-558) ↔ `Manifest` (K/Project.kt:584-600)

| Swift key / JSON | R? | Kotlin key / JSON | iOS→T | T→iOS | Absent |
|---|---|---|---|---|---|
| `exportSettings` obj | o (`decodeIfPresent`, :607) | `exportSettings` (default) | = | = | defaults |
| `schemaVersion` Int | R, 1…12 (:440) | `schemaVersion` Int, 1…24 | Recompute; see §2.3. | Recompute; see §2.2. | — |
| `id` UUID | R | `id` String | = (restore gives a new one anyway) | = | — |
| `title` String | R | `title` | = | = | — |
| `createdAt`, `updatedAt` Double (2001 s) | R | Long ms 1970 | convert (§1.1) | convert | — |
| `sources` [SourceMedia] | R | `sources` | see SourceMedia | see SourceMedia | — |
| `historyCursor` Int | R; must index `snapshotFiles` (:618) | `historyCursor` | = | = | — |
| `snapshotFiles` [String] (64 lowercase hex + `.json`) | o, but the only form written since the split (:556) | `snapshotFiles` R | The names change: steps are re-encoded (§1.4). | Names = sha256 of the bytes written. | iOS falls back to `snapshots` (inline array) or `history` (:631-633). Takes: refuse with a message. |
| `captureLedger` [`{clip: TimelineClip, insertion: Bool}`] (:215-218) | o (`?? []`, :615) | `captureLedger` [TimelineClip] + `captureInsertions` [Int]? | `ledger = entries.map{clip}`; `captureInsertions = indices where insertion`. Then add bookmarks to each clip, see the note below the table. | §1.4: `{clip: stripped(clip), insertion: i ∈ captureInsertions}` | `[]` |
| `automaticCanvasVersion` Int | o; **absent → `repairImplicitCanvasSizes()` rewrites `outputSize`** (:640) | — | Ignore. It is also the detector, §3.4. | **Always write `1`.** | — |
| `recordingHistoryVersion` Int? | o; must be nil or 1 (:440). **Absent → `protectRecordingHistory()` compacts the history** (:411-435, :639). | — | ignore | **Always write `1`.** | — |
| `recordingInsertion` Double? | o | `recordingInsertion` | Drop: a recording run is not carried. | Do not write. | — |
| `recordingInsertionStep` Int? | o | `recordingInsertionStep` | Drop. | Do not write. | — |
| `recordingSite` `{site: VideoInsertionSite, firstCapture: Int}` | o | — | Drop. | Do not write. | — |
| — | — | `captureInsertions` [Int]? | from the `insertion` flags | Fold into the `insertion` flags; do not write the key. | — |
| `snapshots`, `history` (legacy inline) | o | — | Accept `snapshots` (same shape as the files) if seen; refuse `history`. iOS's migrations of such old documents (`protectRecordingHistory`, `repairImplicitCanvasSizes`, S/Project.swift:411-435, :575-600) run only when `recordingHistoryVersion` or `automaticCanvasVersion` is absent, which every current build writes; Takes does not replay them (deferred). | never | — |

`VideoInsertionSite` (S/TimelineEditing.swift:3-4) is written as
`{"start":{}}`, `{"gap":{"after":{"ticks":n}}}` or `{"end":{}}`. It is only
needed if `recordingSite` is ever carried, which it is not.

**Ledger bookmarks on import.** For the first ledger clip of each source that
is not an insertion, add
`bookmarks = source.events.filter{kind=="bookmark" && 0 <= time < duration}.map{Bookmark(id = event.id, time = event.time)}`.
Takes does this when it records (K/Project.kt:746). iOS instead derives the
marks when it replays the ledger: `includeCaptureBookmarks`,
S/TrackTimeline.swift:1463-1469, called at S/Project.swift:264. It keeps only
events inside the take (:1466) and never adds an id twice (:1467), hence
only the first appended clip of a source; an inserted take got its marks in
the step that inserted it (S/Project.swift:361, :364). One difference: iOS
skips a frozen-frame item (:1464). Note that both sides refuse a source
whose events lie past `duration + 1` (S/Project.swift:466; K/Project.kt:820).

#### `SourceMedia` (S/Project.swift:29-40, synthesized) ↔ (K/Project.kt:45-55)

| Swift | R? | Kotlin | iOS→T | T→iOS |
|---|---|---|---|---|
| `id` UUID | R | `id` | = | must be a UUID |
| `filename` | R | `filename` | = (except a converted `.caf`/`.aif`, §4) | = (after the renames in §4/§5) |
| `duration` Double | R, 0 < d ≤ 604800 | `duration` | = | = |
| `recordedAt` Date | **R** | `recordedAt` Long ms | convert | convert |
| `events` [CaptureEvent]? | o | `events` | = | = |
| `sha256` String? (64 hex) | o | `sha256` | Restore overwrites it with the integrity hash (S/ProjectArchive.swift:342). | the hash of the bytes archived |
| `videoEffects` [VideoEffectSettings]? | o (raises schema 5) | **absent** | Write to the sidecar `<movie>.effects.json` (`CaptureEffectsFile`, K/CameraMoves.kt:208-227). Otherwise it is lost silently. | From the sidecar if present, stripped as in §5; else omit. |
| `audioOnly` Bool? | o (raises schema 9) | `audioOnly` | = | = |
| `isStillPhoto` Bool? | o | **absent** | Add an optional `isStillPhoto: Boolean? = null` to Takes' `SourceMedia` (recommended), or drop it. | `true` for Takes `photo-*` sources (§4.3). |
| `originalName` String? (≤255) | o | `originalName` | = | = |

#### `CaptureEvent` (S/Project.swift:16-21) ↔ (K/Project.kt:31-37)

`id` (UUID, R), `time` (R), `kind` (R), `detail` (R). Identical on both
sides. A bookmark is the kind `"bookmark"` on both sides
(S/TrackTimeline.swift:1465; K/Project.kt:872).

#### `ExportSettings` (S/Project.swift:645-670) ↔ (K/Project.kt:552-582)

`codec` (`"H.264"` or `"H.265"`), `resolution` (`"Project size"`, `"720p"`,
`"1080p"`, `"4K"`), `frameRate` (Int: 24, 25, 30, 50 or 60). All three are
R on the Swift side, but `exportSettings` as a whole is optional. Same raw
values on both sides: copy.

#### `TimelineClip` (S/Project.swift:42-61, synthesized) ↔ (K/Project.kt:208-353)

| Swift key | R? | Kotlin | iOS→T | T→iOS |
|---|---|---|---|---|
| `id`, `sourceID` UUID | R | same | = | UUID |
| `start`, `end` Double (source s) | R | same | = | = |
| `stabilization`, `speed`, `color`, `texts`, `pauses`, `crop`, `mirror`, `images`, `openingTransition`, `transition`, `videoEffects`, `frozenFrame` | o | same names | per the type tables, §1.5 and §5 | per the type tables |
| — | — | `audio` ClipAudio, `bookmarks`, `subtitleWords`, `transitionFit`, `openingTransitionFit` | — | They exist only in the sequence form. Inside a timeline they are null (K/TrackConversion.kt:78-81). Delete the keys if present. |

Inside a timeline, iOS also sets `openingTransition` and `transition` to
nil on `content` (S/TrackTimeline.swift:513). The transitions live in
`TrackTimeline.transitions`.

#### `EditSnapshot` (S/Project.swift:196-214) ↔ (K/Project.kt:458-549)

| iOS file bytes | Takes |
|---|---|
| `{"captureCursor":n,"state":{"timeline":{…},"outputSize":[w,h]?,"canvasReference":"UUID"?}}`, sorted keys | Two forms. Sequence: `{"state":{"clips":…,"audio"?,"subtitles"?,"outputSize"?},"captureCursor":n}`. Timeline: `{"state":{"timeline":…,"outputSize"?},"captureCursor":n}` (`EditSnapshotSerializer`, :513-549). |

- **Import.** After the transforms of §7.2, an iOS step is a valid Takes
  timeline step. A step without `timeline` (legacy `clips` + `effects`,
  S/Project.swift:179-184) is read as a Takes sequence step of those clips;
  a non-empty `effects` is dropped with the note `LegacyEffectsDropped`. `ReadState` ignores `canvasReference`; handle that key
  first, see `EditState` below. Decode it with `EditSnapshot.decoded`, then
  re-encode it. The file name changes because the bytes change.
- **Export.** Always write the timeline form; see §1.4.

#### `EditState` (S/Project.swift:141-192; decode :175-185, encode :186-191) ↔ (K/Project.kt:359-402)

| Swift key | R? | Kotlin | iOS→T | T→iOS |
|---|---|---|---|---|
| `timeline` TrackTimeline | o. **Absent → legacy `clips` + `effects` converted by `TrackTimeline(clips:effects:)`**, which drops everything else. | `timeline` (`layered`, :375-376) or the sequence keys | = (after §1.5) | **Always write it** (§1.4). |
| `outputSize` CGSize? | o; even, 2…16384 (:470-473) | `outputSize` `[w,h]` | = (raises Takes schema 21) | = |
| `canvasReference` UUID? | o (raises schema 10) | **absent** | See the rule below. | never written |
| `clips`, `effects` (legacy) | read only | `clips`, `audio`, `subtitles` (sequence form) | iOS no longer writes them | **must not be the only content** |

**`canvasReference` on import** (decision D1). On iOS the canvas takes its shape from
`canvasSourceID`: `canvasReference` if that source is still on the
timeline, otherwise the first picture by `start`, the first listed on a tie
(S/Project.swift:147-151). An `outputSize` wins over both
(S/MediaRenderer.swift:509-511). Takes always uses the first picture.
- If the step has an `outputSize`, or the reference equals the first
  picture's source, or no picture of the step has that source, drop it.
- Otherwise the app layer probes that source's upright size, and core
  rounds it to even pixels as iOS does (S/MediaRenderer.swift:516, half
  away from zero; `ExportFormat.canvas`, K/ExportFormat.kt:23) and sets it
  as `outputSize`, with the note `CanvasFixed`. The movie then keeps its
  frame, but a later edit treats it as fixed, as a Crop would. The probe is
  only asked about a file whose hash matches the integrity.
- If probing fails, drop it with the note `CanvasDropped`. See risk R9.

#### Steps and the ledger: semantics

**iOS materialises** a state as follows (S/Project.swift:254-268):
- it replays the ledger entries after `snapshot.captureCursor` that are not
  insertions;
- each one is added at the timeline's end, on a new top layer (max+1),
  together with its bookmarks.

**Takes does the same** (K/Project.kt:625-631, K/TrackEditState.kt:170-183):
- appending skips `captureInsertions`;
- each take goes on a top layer, at the end of the picture.

One difference: Takes appends at the end of the picture, iOS at
`timeline.duration`, which includes sound and the stretches transitions
occupy. So a lossless import maps the ledger 1:1.

### 1.4 Export of history: fold the ledger

Takes ledger clips can carry what iOS ledger clips cannot: the recording
gain as `audio.volumeDB` (K/Project.kt:748-751) and bookmarks. So export
writes **fully materialised steps**:

For each `i in snapshots.indices`:

```
state_i    = project.history[i]                          // K/Project.kt:634, the ledger already appended
timeline_i = if (state_i.isSequence) EditSnapshot.saved(state_i).toTimeline()
             else TrackEditState.merged(state_i, state_i.layered!!)   // = state_i.toTimeline()
step_i     = {"captureCursor": captureLedger.size,
              "state": {"timeline": iosTimeline(timeline_i), "outputSize": [w,h]?}}
```

- Encode the step with keys sorted, as Swift does (not required, but it
  keeps the output deterministic). Name it `sha256(bytes) + ".json"`.
- If two steps give the same bytes, write one file, but list it in
  `snapshotFiles` once per step. iOS deduplicates entries
  (S/ProjectArchive.swift:250-256).
- Ledger: `captureLedger = ledger.mapIndexed { i, clip -> {"clip": iosClip(clip), "insertion": i in captureInsertions} }`.
  - Each `clip` is stripped of `audio`, `bookmarks`, `subtitleWords` and
    the fits.
  - Because every cursor equals `ledger.size`, iOS replays nothing, and
    `canCommit` holds (S/Project.swift:308-311).
- `historyCursor` is unchanged.

### 1.5 `TrackTimeline` and its parts (S/TrackTimeline.swift) ↔ (K/TrackTimeline.kt, K/EditTime.kt, K/PlaybackMap.kt, K/AudioLevels.kt)

All of these are synthesized in Swift, so every non-Optional key is R.
Kotlin writes every default (encodeDefaults).

| Type | Swift | Kotlin | Keys (R unless marked o) | Transform |
|---|---|---|---|---|
| `EditTime` | :5-15 | EditTime.kt:13-14 | `ticks` Int64 (240 000 per second) | = |
| `EditInterval` | :17-30 | EditTime.kt:36-37 | `start`, `end` (EditTime) | = |
| `PlaybackRun` | :34-44 | PlaybackMap.kt:15-20 | `duration` (EditTime), `sourceStart`, `sourceEnd`, `silent` | = (iOS recomputes `silent` on load: `restoreSourceMute`, :1268-1274) |
| `SourcePlaybackMap` | :45-48 | PlaybackMap.kt:36-37 | `runs` | = |
| `VideoTimelineItem` | :111-125 | TrackTimeline.kt:62-83 | `occurrenceID`, `start`, `content` (TimelineClip), `layer` (Int), `localOrigin`, `mapping`. The id is `content.id` and is not stored. | = |
| `AudioEnhancementRegion` | :126-131 | :93-98 | `id`, `interval` (item ticks), `effects` [AudioEffect] | = |
| `AudioEdgeFade` | :133-151 | AudioLevels.kt:258 | `seconds`, `elapsed` (R; Kotlin default 0, written) | = |
| `AudioTimelineItem` | :166-212 | :109-155 | R: `id`, `occurrenceID`, `sourceID`, `start`, `mapping`, `localOrigin`, `volumeDB` (−60…12), `muted`, `enhancements` (always written, `[]` when empty). o: `followsVideo`, `fadeIn`, `fadeOut`, `censorRegions`, `subtitleWords`. | = |
| `TimelineLink` | :224-227 | :162-166 | `id`, `members` (a set of ≥2 ids) | = (order free) |
| `BookmarkAnchor` | :341-345 | :178-221 | `{"source":{"occurrence","source","time"}}`, `{"local":{"occurrence","offset"}}` or `{"project":{"_0":EditTime}}` | = |
| `TimelineBookmark` | :346-350 | :224-229 | `id`, `label` (R, ≤500), `anchor` | = (R11, closed by D2) |
| `PlacementEdgeCrop` | :360-376 | VideoPlacement.kt:17-18 | `left`, `right`, `top`, `bottom` | = |
| `VideoPlacement` | :377-388 | VideoPlacement.kt:52-69 | R: `x`, `y`, `width`, `height`, `framing`, `fill`, `order` (Int). o: `motion`, `edgeCrop`. | = |
| `VideoPlacementRegion` | :457-461 | :242-247 | `id`, `participants` (a set), **`videos` `[UUID: VideoPlacement]`** | **iOS→T: flat array → object.** Refuse an odd length or a repeated key. **T→iOS: object → flat array.** Sorted keys are fine. |
| `TimelineTransition` | :462-470 | :256-285 | o: `outgoing`, `incoming`. R: `settings` (ClipTransition), `origin`, `factor` (0…1). The id is `settings.id`. | settings per §1.6 |
| `TimelineGeneratedRegion` | :471-476 | :292-298 | R: `id`, `owner`, `interval`. o: `freezeVideo`. | = |
| `TrackTimeline` | :487-503 | :310-320 | R: `video`, `audio`, `links`, `bookmarks`, `placements`, `transitions`, `generated` (CodingKeys :490; all synthesized, so all R). o: `subtitles`. `captureBase` is not coded. | = |
| `AudioEffect` | S/Project.swift:119-139 | AudioEffect.kt:114-124 | R: `id`, `kind`, `enabled`, `amount`, `frequency`, `implementationVersion`. o: `model`. | = |
| `EffectKind` | S/Project.swift:63-97 (custom: an unknown name becomes `.unavailable(name)`) | AudioEffect.kt:17-74 | `voiceIsolation`, `studioVoice`, `studioRestoration`, `highPass`, `equalizer`, `gain` | = |
| `AudioModel` | S/Project.swift:99-115 | AudioEffect.kt:76-112 | `id`, `weightsSHA256`, `runtime` | = (§6) |

Both sides check the same timeline invariants: Swift `TrackTimeline.validate`
(S/TrackTimeline.swift:1175-1248) and Kotlin `TrackInvariants.violation`
(K/TrackInvariants.kt:68-148). These cover:
- unique item ids, and runs inside the source;
- level in −60…12, and valid fades;
- links disjoint, with ≥2 members;
- placements: keys equal participants, geometry in range;
- transition slots unique, factor in 0…1;
- bookmark range;
- followers: same start and length, linked;
- censors, enhancement regions and words.

Takes only checks these on layered steps (K/Project.kt:821). The exporter
must run them on every exported timeline before writing (§7.3).

### 1.6 Clip tools, titles, subtitles, transitions, censor

Unless a row says otherwise, keys and defaults are identical and the
transform is **=**. "R" means required in Swift.

| Type | Swift | Kotlin | Keys | Transform / note |
|---|---|---|---|---|
| `CropSettings` | VideoTools.swift:6-13; **custom decode :64-73** | Crop.kt:16-25 | R: `id`, `x`, `y`, `zoom`. o: `rotation` (absent → 0), `motion`. | = |
| `MirrorSettings` | VideoTools.swift:75-78 | Crop.kt:114-115 | `id`, `horizontal`, `vertical` | = |
| `ImageOverlay` | VideoTools.swift:84-93 | ImageOverlay.kt:9-20 | R: `id`, `filename`, `start`, `end`, `x`, `y`, `width`, `opacity`. o: `motion`. | = (file rules §4) |
| `SourceInterval` | VideoTools.swift:116-119 | Pauses.kt:19-20 | `start`, `end` | = |
| `SpeedSettings` | VideoTools.swift:121-124 | Speed.kt:6-7 | `id`, `rate` | = |
| `ColorLook` | VideoTools.swift:125-126 | ColorSettings.kt:8-23 | natural, warm, cool, cinema, mono, portrait, daylight, goldenHour, mutedFilm, richContrast, pastel, night, silver, ink | = |
| `ColorSettings` | VideoTools.swift:136-146 | ColorSettings.kt:43-54 | R: `id`, `look`, `amount`. o: `imported`, `exposure`, `highlights`, `localRecovery`, `warmth`, `tint`, `motion`. | = (export also runs the colour-motion check, §1.7) |
| `LUTInput` | ColorGrading.swift:5-6 | ColorSettings.kt:27-31 | rec709, appleLog, appleLog2 | = |
| `ImportedLUT` | ColorGrading.swift:11-20 | ColorSettings.kt:35-39 | `filename` (`effect-lut-…cube`), `name`, `input` | = |
| `TextStyle` | VideoTools.swift:159 | TitleDesign.kt:10-15 | clean, bold, box, highlight | — |
| `TextColor` | VideoTools.swift:160-168 | TitleDesign.kt:19-26 | white, black, yellow, coral, mint, blue | — |
| **`TextOverlay`** | VideoTools.swift:218-232 | K/Project.kt:61-78 | R: `id`, `text` (≤500), `start`, `end`, `x`, `y`, `size`, **`style`**, **`color`**. o: `appearance`, `motion`. | **iOS→T:** if `appearance` is absent, set it to `TitleDesign.legacy(style, color)` (K/TitleDesign.kt:176-187). iOS draws a title without one that way (VideoTools.swift:231); Takes would draw it as `TitleDesign.plain`. Then drop `style` and `color`. **T→iOS:** write `"style":"bold","color":"white"`. If `appearance` is null, write `TitleDesign.plain` in full (K/TitleDesign.kt:173: outlined, borderWidth 0.06, no shadow); otherwise iOS draws a bold white title with a shadow. |
| `TitleInk` | TitleDesign.swift:6-7 | TitleDesign.kt:54-55 | `rgba` (UInt32 RRGGBBAA) | = |
| `TitleGradient` | TitleDesign.swift:23-27 | TitleDesign.kt:78-83 | `first`, `last`, `angle` | = |
| `TitleAlignment` / `TitlePlate` | TitleDesign.swift:53-54 | TitleDesign.kt:108-120 | left/center/right; off/block/lines | = |
| `TitleDesign` | TitleDesign.swift:56-75 | TitleDesign.kt:129-151 | All 19 are R: `face`, `weight`, `italic`, `alignment`, `tracking`, `lineSpacing`, `fill`, `gradient` (o), `outlined`, `border`, `borderWidth`, `shadow`, `shade`, `shadowBlur`, `shadowOffset`, `plate`, `backing`, `padding`, `radius`. | = (`face` values below) |
| `TitleDirection` / `TitleMove` | TitleDesign.swift:222-223 | TitleMotion.kt:8-22 | up/down/left/right; none/fade/drift/scale/wipe | = |
| `TitleMotion` | TitleDesign.swift:224-229 | TitleMotion.kt:26-36 | R: `entrance`, `exit`, `inSeconds`, `outSeconds`. o: `inDirection`, `outDirection`. | = |
| `DetectedPause` | VideoTools.swift:233-236 | Pauses.kt:25-26 | `raw`, `enabled` | = |
| `PauseLevelMode` | VideoTools.swift:237 | Pauses.kt:28-31 | automatic, manual | = |
| `PauseSettings` | VideoTools.swift:238-248 | Pauses.kt:40-92 (`StoredPauseSettings`) | R: `id`, `thresholdDB`, `minimum`, `before`, `after`, `cuts`. o: `candidates`, `analyzed`, `levelMode`. | = |
| `StabilizationLevel` | StabilizationPath.swift:4-5 | StabilizationPath.kt:21-32 | off, light, standard, strong | = |
| `StabilizationSettings` | StabilizationPath.swift:24-32 | StabilizationPath.kt:45-60 | R: `level`, `version` (=1). o: `id`, `referenceStart`, `referenceEnd`. | = (stabilisation analyses are caches, §5) |
| `TransitionStyle` | Transitions.swift:6-9 | TransitionSettings.kt:11-37 | dissolve, fadeBlack, wipe, split, hitchcock, whip, flashCut, still, defocus | = |
| `TransitionAxis` / `TransitionAudio` | Transitions.swift:15-20 | TransitionSettings.kt:43-54 | horizontal/vertical; crossfade/first/second | = |
| `TransitionFraming` | Transitions.swift:23-26 | TransitionSettings.kt:58-63 | `x`, `y`, `zoom` | = |
| `TransitionColor` | Transitions.swift:31-34 | TransitionSettings.kt:73-74 | `red`, `green`, `blue` | = |
| **`ClipTransition`** | Transitions.swift:39-64 | TransitionSettings.kt:94-123 | R: `id`, `style`, `duration`, **`axis`, `divider`, `reverse`, `audio`, `first`, `second`**. o: `nextClipID`, `fadeColor`, `hold`, `audioOffset`, `audioBlend`, `wipeAngle`, `hardEdge`, `highlightsFirst`, `flashCount`, `flashAcceleration`, `stillDissolve`, `blurAmount`. | **T→iOS: write the six bold keys** whenever they are missing: `"horizontal"`, `0.5`, `false`, `"crossfade"`, `{"x":0.5,"y":0.5,"zoom":1}` twice. This applies in `TimelineTransition.settings`, and on clip `transition`/`openingTransition` if any survive. iOS→T: =. |
| `SubtitleWord` | Subtitles.swift:7-13 | Subtitles.kt:14-27 | `id`, `phraseID`, `text`, `start`, `end` | = |
| `SubtitleFont` | Subtitles.swift:15-16 | TitleDesign.kt:33-39 | system, modern, classic, mono, condensed | = |
| `SubtitleSettings` | Subtitles.swift:29-46 | Subtitles.kt:42-89 | R: `id`, `maxWords`, `style`, `color`, `size`, `y`, `language`, `timingFingerprint`. o: `x`, `font`, `appearance`, `motion`. | = apart from `timingFingerprint`, see below |
| `CensorWaveform` / `CensorEnvelope` | AudioCensor.swift:3-4 | AudioCensor.kt:11-23 | sine/triangle/square; short/soft/pulse | = |
| `AudioCensorSettings` | AudioCensor.swift:6-10 | AudioCensor.kt:27-37 | `frequency`, `levelDB`, `waveform`, `envelope` | = |
| `AudioCensorRegion` | AudioCensor.swift:52-57 | AudioCensor.kt:118-125 | R: `id`, `interval`, `phaseOrigin`, `settings`. o: `envelopeInterval`. | = |

**`face` values.** A title or caption face is one of:
- one of 8 built-in family ids, the same on both sides;
- `import:<sha256>:<index>:<postScriptName>` (S/ImportedFonts.swift:13-25 ↔ K/TitleDesign.kt:255-271);
- an iOS system PostScript name (`AvenirNext-*`, `Georgia*`, `Menlo-*`, `DINCondensed-Bold`). Android draws these in the default typeface (A/…/TitleRendering.kt:84). Keep the value; the difference is cosmetic.

**Subtitle `timingFingerprint`.** It is a SHA-256 over the items' timing,
`[{id, source, start, mapping, muted}]`, sounds then pictures.
- iOS hashes Swift `JSONEncoder` bytes with sorted keys
  (S/Subtitles.swift:183-189). Takes hashes its own JSON
  (K/TrackConversion.kt:158-173): same fields, different bytes (key order of
  `PlaybackRun`, number formatting).
- iOS only uses it to allow "regroup words" (S/Subtitles.swift:190-195).
- **Rule:** in each direction, if the stored fingerprint matches the
  source side's own formula on that step's timeline, write the target's
  formula; otherwise write `""`.
- Takes can compute the target formula only if it reproduces Swift's byte
  output, including how Swift writes Doubles. Until a fixture proves that
  (§8), write `""` on export. The only cost is that iOS shows regrouping as
  unavailable for those captions.
- On import, use `TrackConversion.timingFingerprint` on the imported
  timeline, provided the iOS fingerprint is recomputed and verified first.
  Otherwise write `""`. **Implemented: `""` always**, until Q2. In Takes
  that only makes regrouping wait for the next transcription
  (K/SubtitleEditing.kt:72, K/TrackSubtitles.kt:55).

### 1.7 Validity rules that differ (export must enforce iOS's)

| Rule | iOS | Takes | Action |
|---|---|---|---|
| Colour motion | `color.valid` and `motion.valid(for: pose, accepts: applying(p).valid)` (S/Project.swift:490) | `color.valid` only (K/Project.kt:844) | Export: run the motion check. A failure blocks the export with a message. |
| Frozen frame | needs a `freezeFrame` effect on the same clip, and `start ≤ sourceTime < end` (S/Project.swift:483-486) | `frozenFrame.valid` only | Export: check it; block on failure. |
| Effect file names | `effect-` prefix plus an extension in {json, png, jpg, jpeg, heic, mov, mp4, bin, cube} (S/Project.swift:458-461) | any safe name | §5.3 |
| Image file names | `image-` prefix plus png, jpg, jpeg or heic (:454-457) | any safe name; Takes writes `image-<UUID>.png` (K/ImageOverlay.kt:68) | Check; Takes output already complies. |
| Title text length | ≤500 grapheme clusters (:510) | ≤500 UTF-16 units (K/Project.kt:80) | Import: shortened to 500 UTF-16 units, never through a surrogate pair, with the note `TitlesShortened`. See R12. |
| Caption words | ≤4000 graphemes; ≤200 000 per item (S/TrackTimeline.swift:1192-1194) | ≤4000 UTF-16 (K/Subtitles.kt:22-27) | same as above |
| Clip bookmark label | — (≤500 on a timeline bookmark, S/TrackTimeline.swift:1220) | ≤500 since D2 (K/Project.kt `Bookmark.valid`, `TrackInvariants.MAXIMUM_LABEL`) | Was ≤100: a label of 101–500 characters on a sequential step failed `Project.validate` through the view. Resolved by D2. |
| Source names | reserved: project.json, integrity.json, cache, processing, previews, exports (S/Project.swift:446) | reserved: project.json, history, recovery, cache, exports (K/Project.kt:875) | Export: refuse a Takes source named `integrity.json`, `processing` or `previews`. In practice these never occur. |

---

## 2. Schema numbers

### 2.1 YouRec (S/Project.swift)

| # | Raised when | Line |
|---|---|---|
| 1 | — (the lowest readable) | :440 |
| 2 | a recording is added (`addRecording`) | :343 |
| 3, 4 | **never assigned.** The repository is a single squashed commit (`git log -S` finds nothing). | — |
| 5 | a source carries `videoEffects` | :338, :345 |
| 6 | the floor of every commit and the default of a new project (`timeline` is the saved form) | :235, :319 |
| 7 | a placement has an `edgeCrop` | :319 |
| 8 | the timeline has `subtitles` | :325 |
| 9 | an audio item has censors, or a source is `audioOnly` | :330, :378 |
| 10 | an edit keeps a `canvasReference` | :327, :597 |
| 11 | an audio item has `fadeIn` or `fadeOut` | :328 |
| 12 | a motion on a crop, colour, image, clip video effect or placement | :320-324 |

Restore and open refuse anything above 12 (S/Project.swift:440;
S/ProjectRecovery.swift:156, :280). Mirror, pauses, images, stabilisation,
frozen frames, time effects, layers, placements, transitions, title designs
and caption designs raise nothing on iOS.

### 2.2 Takes (K/Project.kt:315-339, K/TrackTimeline.kt:407-417, K/Project.kt:395; history in docs/TRACKS-PLAN.md §3.4)

| # | Feature |
|---|---|
| 1 | base |
| 2 | transitions, titles |
| 3 | bookmarks on clips |
| 4 | clip sound (`ClipAudio`) |
| 5 | sound items (historical; no longer raised in code) |
| 6 | noise reduction (`denoise`) |
| 7 | colour |
| 8 | crop |
| 9 | speed |
| 10 | takes inserted at a place (historical) |
| 11 | words on clips (subtitles) |
| 12 | title designs or motions |
| 13 | the original's transition settings (opening, or non-legacy) |
| 14 | crop reframed (rotation or motion) |
| 15 | reshaped fades |
| 16 | video effects |
| 17 | a timeline step, which every commit now writes (`TrackTimeline.SCHEMA`) |
| 18 | a layered (non-sequence) step |
| 19 | a frozen frame or a time effect (stepPrinting, undercranking) |
| 20 | mirror |
| 21 | fixed output size |
| 22 | pauses |
| 23 | images |
| 24 | stabilisation |
| 25 | a title made a card of a label and a headline (`LATEST_READABLE_SCHEMA`, K/Project.kt `TimelineClip.LABEL_HEADLINE_SCHEMA`) |

**The numbers overlap with iOS's but mean different things. Never compare
them across sides.**

### 2.3 What Takes writes for iOS

Compute over every exported step's timeline, every ledger clip and every
source:

```
v = 6
if any source.videoEffects non-empty                                     -> max(v, 5)   (below the floor)
if any placement.videos[*].edgeCrop != null                              -> max(v, 7)
if any timeline.subtitles != null                                        -> max(v, 8)
if any audio item censorRegions non-empty || any source.audioOnly == true -> max(v, 9)
if any audio item fadeIn/fadeOut != null                                 -> max(v, 11)
if any motion on clip.crop / clip.color / clip.images[] / clip.videoEffects[] (in timeline content AND ledger clips)
   or on placement.videos[*]                                             -> max(v, 12)
```

10 is never written, because Takes never writes `canvasReference`. The
result is always 6 to 12.

**On import:**

```
schemaVersion = max(TrackTimeline.SCHEMA, max over snapshots of EditSnapshot.schema, max over ledger of TimelineClip.schema)
```

This is ≥17, ≥18 for a layered step, and 21 for an `outputSize`.

### 2.4 Takes features with no iOS representation at schema ≤ 12

**There is no Takes editing feature that iOS 12 cannot represent.** Layers,
placements, gaps, mirror, pauses, images, stabilisation, frozen frames,
time effects, all 24 video effects, motions, all nine transition styles,
title and caption designs, censors, fades and enhancement chains are all in
YouRec's schema-12 model. What needs handling on export:

| Takes thing | iOS has | Export rule |
|---|---|---|
| Sequence-form steps | timeline only | Convert (§1.4). Never written as a sequence. |
| `ClipAudio.volumeDB`, `muted`, fades | `AudioTimelineItem` fields | Through the conversion (K/TrackConversion.kt:105-115). |
| `ClipAudio.denoise` | a `voiceIsolation` + RNNoise region over the item | Through the conversion (K/TrackConversion.kt:111-113). |
| `AudioItem.track` | none (lanes are derived) | Dropped by the conversion; no loss of what is heard. |
| Recording gain on ledger clips | none in the ledger | Folded into the materialised steps (§1.4). |
| `captureInsertions` | `insertion` flags | Converted. |
| Recording run (`recordingInsertion`, `recordingInsertionStep`) | `recordingInsertion`, `recordingInsertionStep`, plus `recordingSite` | Dropped: the next take is appended at the end. |
| Ids that are not UUIDs (`…~sound`, `…~item`) | UUID only | Remap each to `UUID.nameUUIDFromBytes(id.utf8)` in uppercase, consistently in every reference (§7.2 T6). |
| Takes-made captured-effect analysis (`effect-*.json`/`.bin`) | an incompatible format | Strip the references; leave the files out (§5). |
| `effect-*.webp` media, `effect-*.m4v` | refused names | PNG re-encode or rename (§5.3). |
| Media in `.ogg`, `.webm`, `.mkv`, `.3gp`(?), or VP8/VP9/AV1 video | AVFoundation cannot play them | **Block the export** and list the files (or transcode later) (§4). |
| Photo sources `photo-*.mp4` | `isStillPhoto` | Set `isStillPhoto: true` (§4.3). |
| Subtitle fingerprint | Swift-hash formula | `""` (§1.6). |
| Dual camera | — | Nothing to do: on both sides it is one composited movie, and the layout is a recorder preference, not project data (S/MultiCameraCapture.swift:241-287; K/DualCameraLayout.kt; A/…/RecorderSettings.kt:72-76). |

---

## 3. Archive

### 3.1 What YouRec writes (`ProjectArchive.export`, S/ProjectArchive.swift:219-296)

- **Writer.** ZIPFoundation, every entry **STORED** (`compressionMethod: .none`,
  :244, :272), UTF-8 flag. No data descriptor: the local header is
  rewritten with the CRC and sizes (ZF/Archive+Writing.swift:138-152).
- **Entry order:**
  1. `History/<sha256>.json` for each distinct snapshot (:250-256). The name
     is the lowercase hex SHA-256 of the bytes (S/Project.swift:546-548).
  2. `project.json` (:258).
  3. `Timeline.otio`, or `Timeline-N.otio` on a case-insensitive collision
     with a media name (:259; S/EditorialTimeline.swift:16-21).
  4. Media: `sources[].filename` in source order, then
     `Set(imageFiles + effectFiles + fontFiles).sorted()`, keeping only
     files that exist (:229-231).
  5. `Recovery/<UUID|64hex>/<name>` entries, if any (:106-125, :158-174).
  6. `integrity.json`, last (:291-293).
- **`integrity.json`** is `{"sha256":{"<name>":"<hex>",…},"version":1|2}`,
  pretty-printed with sorted keys (:48-51, :242).
  - `version` is 2 if and only if there are Recovery entries (:291).
  - Only media and Recovery files are hashed; `project.json`, History and
    the `.otio` are not.
- **Limits:** header and integrity ≤20 MB; one step ≤32 MB; the sum of all
  steps ≤256 MB (:53-63).

### 3.2 What YouRec restore requires (S/ProjectArchive.swift:298-403)

1. **Entry names.** Every entry is a file (:303-309), and each name is one
   of:
   - a flat name: not starting with `.`, `/` or `\`, and equal to its own
     last path component;
   - `History/` + 64 lowercase hex + `.json`;
   - a Recovery path matching :95-104.

   Names are unique case-insensitively.
2. **The manifest decodes and passes `validate()`.** See §1 and §2.
   `schemaVersion` must be ≤12.
3. **Every step's bytes hash to its name** (S/Project.swift:625-629).
4. **The entry set is closed** (:334-341):
   - integrity `version` is 1 with no Recovery keys, or 2;
   - every entry ∈ loaded step paths ∪ **iOS-computed `mediaFiles`** ∪
     Recovery keys ∪ {`project.json`, `integrity.json`,
     `EditorialTimeline.filename(for: project)`};
   - every source filename is an integrity key;
   - every integrity key ∈ mediaFiles ∪ Recovery.

   **One extra file fails the restore.**
5. **Media.**
   - A media file listed in integrity but missing from the ZIP is skipped
     (:358); the source stays relinkable.
   - On a hash, CRC or size mismatch the file is deleted and the restore
     goes on (:387-392). The same mismatch aborts for a Recovery file.
   - A media file the archive holds but `integrity.json` does not list is
     deleted the same way: its hash is compared with nil (:387).
   - `sources[i].sha256` is set from integrity (:342).
6. **New id and date.** The project gets a new `id` and `updatedAt = now`
   (:344).
7. **Steps keep their bytes.** Saving rewrites the manifest in iOS form, but
   each step keeps the bytes it was read with (S/Project.swift:203-213).
   The steps Takes writes therefore stay on the iPhone byte for byte, so
   their canonical form matters.

**The `.otio` is optional.** It is allowed, never read, and not covered by
integrity. **Takes must not write one for iOS:** its name would have to
match iOS's collision rule, and omitting it removes that risk.

**`mediaFiles` as iOS computes it** (S/Project.swift:286-302):
- `sources[].filename`, then
- `imageFiles`: `images[].filename` of the projected clips of **every
  history state**;
- `effectFiles`: the `mediaFiles` of `sources[].videoEffects` and of every
  clip's `videoEffects` (media parameters and keyframes, `analysisFile`,
  `analysisDataFile`), plus `color.lutAssets` (the imported LUT and the
  LUTs at the ends of a moving grade);
- `fontFiles`: imported faces of the texts and subtitles of every step
  (S/ImportedFonts.swift:190-196), plus the texts of the ledger clips.

Takes needs a Kotlin `YouRecMediaFiles` that computes exactly this, on the
**converted** document.

### 3.3 What Takes restore requires today (K/ProjectRestore.kt)

These rules make it refuse every YouRec ZIP:

| Rule | Line |
|---|---|
| Only `History/<snapshot>` or flat names; **`Recovery/…` → NotABackup** | :135, :240-243 |
| Manifest dates must be `Long` → InvalidDocument | :184-191; K/Project.kt:896-902 |
| **integrity `version != 1` refused** | :199 |
| No unlisted top-level or History file | :204-205 |
| A missing source → MissingMedia | :209 |
| A missing or damaged integrity file → DamagedMedia | :218-221 |
| Fonts must be `font-<hash>.sfnt` (iOS uses the same names) | :211-214 |
| The project gets a new uppercase UUID | :91-92, :101 |
| `publish` removes the `.otio`, `integrity.json` and unreferenced History | :227-238 |

Takes writes entries in this order (K/ProjectArchive.kt:50-109):
`project.json`, `History/…`, the `.otio` (the same naming rule,
K/OpenTimeline.kt:36-42), media, then `integrity.json`
`{"version":1,"sha256":…}`. JSON entries use DEFLATE and media uses
DEFLATE level 0, both with data descriptors. There are no Recovery entries.

### 3.4 Telling a YouRec ZIP from a Takes ZIP

Read `project.json` as a `JsonObject`, and use the first rule that matches
(K/yourec/YouRecDetect.kt):

1. It has the key `automaticCanvasVersion` → **YouRec**. iOS always writes
   it (S/Project.swift:566, kept by :554-557); Takes' `Manifest` has no such
   field.
2. It has `recordingHistoryVersion` or `recordingSite`, `captureLedger[0]`
   has a `clip` key, or it has `snapshots` or `history` without
   `snapshotFiles` → **YouRec** (older builds).
3. `createdAt` is a number: **YouRec** if it has a fraction, **Takes** if it
   is whole. Takes writes whole milliseconds; a Swift `Date()` practically
   never falls on a whole second.
4. Only a manifest that says none of this is told by the archive around it:
   integrity version 2, or any `Recovery/` entry → **YouRec**.
5. Otherwise → **Takes**.

*Changed from the first draft*, which put the archive signals and
"`createdAt` below 1e11" before the Takes verdict: a Takes manifest with an
added `Recovery/` entry or integrity version 2 must stay a Takes archive and
be refused as one (ProjectRestoreTest), and Takes test projects use small
whole dates.

Never use `schemaVersion`: the ranges 1–12 overlap.

### 3.5 Restoring a YouRec ZIP in Takes

This is a branch in `ProjectRestore`, taken after detection:

Implemented in `ProjectRestore.restoreArchive`, which returns
`Restored(project, fromYouRec, notes)`; `restore` keeps its signature and
returns the project. The app passes a `MediaProbe(size, playable)`.

1. **unpack.** Also accept `Recovery/<UUID|64hex>/<name>` using iOS's
   predicate (S/ProjectArchive.swift:95-104), written to
   `<folder>/Recovery/<group>/<name>`. That is Takes' own layout
   (K/ProjectDisk.kt:208-233). Relax the parent check
   (K/ProjectRestore.kt:147-148) for these entries. The Takes branch still
   refuses any `Recovery/` entry.
2. **Read and verify the steps.** Read `project.json` and the History
   files, and check `sha256 + ".json" == name`.
3. **Convert** with `YouRecCodec.import` (§7.1). The result is a Takes
   `Project` and a list of **notes** (what was dropped or changed).
4. **Integrity.**
   - Accept versions 1 and 2. Version 2 is required if Recovery keys exist.
   - Verify Recovery hashes; a mismatch aborts.
   - Allowed entries are iOS's mediaFiles (`YouRecMediaFiles`, computed on
     the converted project plus the takes' recorded effects, before any
     rename), Recovery keys, `project.json`, `integrity.json`, and the
     `.otio` **only under the name `EditorialTimeline.filename(for:)` gives**
     (S/EditorialTimeline.swift:16-21; S/ProjectArchive.swift:339), not any
     `Timeline*.otio` as the first draft said. The `.otio` is ignored.
5. **Media conversions** (§4.2): convert `.caf`/`.aif`/`.aiff`/`.aifc` of
   linear PCM to `.wav` (`LinearPcm`, pure Kotlin: samples copied, swapped
   to little-endian, 64-bit floats to 32-bit; D4).
   - Sources only: nothing else names a sound file. The new name is the
     stem plus `.wav`, made unique case-insensitively.
   - Recompute `sha256` from the WAV.
   - Compressed CAF (AAC, ALAC) or AIFF-C is not converted: the file is
     left out, the source renamed to `.wav` with no `sha256` so that Repair
     media accepts a converted file, and the note `AudioNotConverted`.
   - `.heic` images to `.png` below API 28: **deferred** (minSdk 26 devices
     are rare; the picture is kept as it is).
   - Every source file is then asked `playable`; a track no decoder of the
     device takes (ProRes) gives the note `UnplayableVideo`. The file stays.
6. **Missing or damaged media** (decision D3): as iOS, on this branch only.
   A media file missing from the ZIP is skipped (note `MissingMedia` for a
   source); one whose hash does not match is deleted (note `DamagedMedia`);
   the source keeps the integrity hash and stays relinkable (Repair media).
   A Takes ZIP keeps the strict rule.
7. **Write.** Steps are written in Takes form with new names; the
   `sources[].videoEffects` go to each movie's `.effects.json`; then
   `publish`, which deletes the `.otio`, `integrity.json` and the original
   step files.

### 3.6 Writing a ZIP for iPhone in Takes

Since the owner's decision of 2026-11-18 there is one "Zip project", and it
writes this form whenever the project has no blocker: Takes opens it as it
opens any YouRec ZIP (§3.5). A project with a blocker is written in Takes'
own form, with the blockers named in the message that the ZIP opens in Takes
only. No dialog comes before the file is chosen; what the round trip back
into Takes changes is §7.5's round trip (the effects' analysis, which Takes
works out again, and a recording run, which ends).

1. Run `YouRecCodec.check(project)`, which returns **blockers** and
   **losses** (§2.4, §1.7). Any blocker stops the export.
2. Run `YouRecCodec.export(project)`, which returns the manifest JSON, the
   step byte arrays, and the file renames and conversions (§5.3).
3. Compute iOS `mediaFiles` on the converted document (§3.2) and archive
   exactly that set. Leave out any missing non-source file, as iOS does.
4. **Entries:**
   - `History/<sha>.json`, deduplicated;
   - `project.json`;
   - media;
   - `integrity.json` `{"sha256":{…},"version":1}`, where `sha256` holds
     media only;
   - the current edit as `Timeline.otio`, or `Timeline-N.otio` on a
     case-insensitive collision with a media name, after `project.json`, as
     the original writes it (S/ProjectArchive.swift:259;
     S/EditorialTimeline.swift:16-21), which both restores accept under
     that name and ignore;
   - **no `Recovery/`.**
5. **Compression.** Write every entry **STORED** with the CRC and sizes in
   the local header and no data descriptor: compute the CRC in a first
   pass, then use `ZipEntry.setMethod(STORED)` with `size`,
   `compressedSize` and `crc` set. That is exactly what iOS writes.
   DEFLATE with data descriptors should also be accepted by ZIPFoundation
   (ZF/Entry.swift:104, 136, 176-183), but it is untested: R4.
6. Enforce iOS's size limits: 20 MB, 32 MB per step, 256 MB for all steps.

---

## 4. Media

### 4.1 What each side writes

| | YouRec | Takes |
|---|---|---|
| Camera takes | `<ms>-<UUID>.mov`: fragmented QuickTime, H.264 SDR or HEVC Main10 HLG/Log, AAC 48 kHz (S/CaptureController.swift:1405; S/CaptureSegmentWriter.swift:215-246) | `<ms>-<id>.mp4`: CameraX, H.264 SDR or HEVC 10-bit HLG, AAC (A/…/CaptureController.kt:1933, :460-531) |
| Photos | `photos-import-<UUID>.mov`: H.264, 3 s at 30 fps, no audio; `isStillPhoto: true`; placed picture-only (S/PhotoClip.swift:7-65; S/VideoBatchImport.swift:13-37, :103-104) | `photo-<ms>-<id>.mp4`: H.264, 3 s at 30 fps, silent; no flag; placed with a sound item (A/…/PhotoMovie.kt:33-58; A/…/ProjectStore.kt:306, :650-656) |
| Imported video | `photos-import-<UUID>.<mov\|mp4\|m4v>`, moved as is: may be ProRes, Dolby Vision or MV-HEVC (S/VideoBatchImport.swift:29) | `import-*.<mov\|webm\|mkv\|3gp\|mp4>` (A/…/ProjectStore.kt:545-552) |
| Audio | `<UUID>.<ext>` with `audioOnly`; voice recordings are LPCM `.caf` (S/ProjectArchive.swift:15-36; S/VoiceRecorder.swift:115-117) | `audio-*.<mp3\|wav\|ogg\|flac\|aac\|m4a>`; voice recordings are `.wav` (A/…/ProjectStore.kt:356, :503-511) |
| Images | `image-*.png/jpg/jpeg/heic` | `image-<UUID>.png` |
| LUTs | `effect-lut-<sha>.cube` (S/ColorGrading.swift:117-127) | the same (A/…/ProjectStore.kt:491) |
| Fonts | `font-<sha>.sfnt` (S/ImportedFonts.swift:17, :99) | the same (K/TitleDesign.kt:256) |
| Effect media | `effect-<UUID>.<png\|jpg\|heic\|mov\|mp4>` | `effect-picture-<hash>.<png\|webp\|heic\|jpg>`, `effect-<uuid>.<mov\|m4v\|mp4>` (A/…/EffectMediaImport.kt:55-75) |

### 4.2 Decoding and conversions

**iOS → Takes:**
- `.mov` with H.264, HEVC HLG/Log and AAC: fine. Verify on a phone that a
  **fragmented** `.mov` plays and seeks (R6).
- ProRes cannot be decoded: note it, and leave the source relinkable or
  refuse.
- Dolby Vision 8.4 decodes as HEVC. MV-HEVC decodes the base view only.
- **`.caf` and `.aif`/`.aiff` do not play on Android.** Convert to WAV
  (LPCM CAF: rewrite the header; AIFF: swap byte order; AAC or ALAC in CAF:
  decode).
- HEIC images: decodable from API 28 only (minSdk is 26). Convert to PNG
  below 28.

**Takes → iOS:**
- `.mp4` with H.264, HEVC and AAC, and `.wav`, `.mp3`, `.m4a`, `.aac`,
  `.flac`: fine.
- **`.ogg`, `.webm`, `.mkv`, and VP8, VP9 or AV1 video: block** with a list
  of files. iOS restore does not decode media, so the project would open
  broken.
- Check `.3gp` (AMR audio is not supported by AVFoundation).

### 4.3 Still photos

- **Takes → iOS:** a source whose name starts with `photo-` gets
  `isStillPhoto: true`. The file is already a 3 s H.264 movie, as iOS makes
  them. Its sound item (silent) may stay. `isStillPhoto` has no other use
  on iOS (S/VideoBatchImport.swift:37).
- **iOS → Takes:** keep the flag in a new optional field. Nothing in Takes
  core depends on the `photo-` prefix, and the file name is kept.

---

## 5. Effect analysis and caches

### 5.1 Effect recipes

The video-effect model is a 1:1 port. Both sides have the same 24 kinds:

> `rgbOffset`, `negativeStrobe`, `nightFix`, `colorCorrection`, `silentTint`,
> `threeStrip`, `bleachBypass`, `dayForNight`, `colorIsolation`, `noir`,
> `expressionism`, `quantization`, `dollyZoom`, `crashZoom`, `kaleidoscope`,
> `widescreen`, `freezeFrame`, `stepPrinting`, `undercranking`, `halation`,
> `sixteenMM`, `superEight`, `chromaKey`, `autoBackground`

The catalogues (S/VideoEffects/VideoEffectCatalog.swift:189-391 ↔
K/VideoEffectCatalog.kt) match in every parameter id, kind, bound, default
and animatable flag.

Both sides drop an unknown kind on decode: Swift `SupportedVideoEffects`
(S/VideoEffects/VideoEffectModel.swift:363-387), Kotlin
`SupportedVideoEffects` (K/VideoEffectModel.kt:765-781).

| Type | Swift | Kotlin | Keys | Transform |
|---|---|---|---|---|
| `VideoEffectSettings` | VideoEffectModel.swift:162-232 (custom decode :207-232) | VideoEffectModel.kt:412-436, :710-755 | R: `id`, `kind`, `enabled`, `version` (=1), `start`, `end`, `parameters` (merged with the catalogue on decode on both sides). o: `analysisFile`, `analysisDataFile`, `capturedEffectID`, `palette` (2–16), `lockedPalette`, `motion` (colorCorrection only), `algorithm` (nil, 1 or 2), `strobeTimeMap`, `phaseOffset`, `releaseTime`. | = apart from the analysis keys (§5.2) and media names (§5.3) |
| `EffectParameter` | :109-147 | :318-326 | All six R: `id`, `title`, `kind`, `value`, `keyframes`, `animatable`. | = |
| `EffectKeyframe` | :104-108 | :311-312 | `id`, `time`, `value` | = |
| `EffectValue` | :64-74 | :131-207 | `{"number":{"_0":d}}`, `{"toggle":{"_0":b}}`, `{"choice":{"_0":s}}`, `{"color":{"_0":RGB}}`, `{"media":{"_0":"file"}}` or `{"media":{}}` | = |
| `EffectParameterKind` | :75-103 | :218-308 | slider{min,max,defaultValue,step}, toggle, stepper{values,defaultValue}, color, picker{options,defaultValue}, media{image,video} | = |
| `EffectRGB` | :55-63 | VideoEffectColor.kt:13-14 | `red`, `green`, `blue` | = |
| `EffectPalette` | VideoEffectMath.swift:81-85 | VideoEffectModel.kt:392-398 | `colors`, `thresholds` | = |
| `StrobeTimePoint` | :157-160 | EffectAnimation.kt:115-116 | `source`, `elapsed` | = |
| `FrozenFrameSegment` | VideoEffectTiming.swift:5-10 | PlaybackSpan.kt:16-20 | `sourceTime`, `duration` (0–10), `offset` (all R) | = |
| `EffectCurve` / `RampShape` | EffectAnimation.swift:8-24 | EffectAnimation.kt:11-33 | linear, easeIn, easeOut, smooth, riseAndHold, riseHoldReturn / linear, easeIn, easeOut, smooth | = |
| `EffectTiming` | EffectAnimation.swift:42-46 | EffectAnimation.kt:43-48 | `curve`, `rise`, `release`, `ramp` | = |
| `EffectPose` | EffectAnimation.swift:87-92 | EffectAnimation.kt:104-108 | R: `values` `{String: Double}`. o: `look`, `lut`. | = |
| `EffectAnimation` | EffectAnimation.swift:93-99 | EffectAnimation.kt:125-132 | `a`, `b`, `timing`, `duration`, `frame` (1/30), `clock` (all R) | = |

Pose keys are the same on both sides:

| Owner | Keys |
|---|---|
| crop | x, y, zoom, rotation |
| image | x, y, width, opacity |
| placement | x, y, width, height, fx, fy, zoom, left, right, top, bottom |
| colour | amount, exposure, highlights, localRecovery, warmth, tint, plus look/lut |

Sources: S/EffectAnimation.swift:182-236 ↔ K/EffectAnimation.kt:223,
K/ImageOverlay.kt:35, K/VideoPlacement.kt:187-188, K/ColorSettings.kt:70-77.

### 5.2 Captured analysis: never carried across; both sides recompute

| What | iOS | Takes | In the project? |
|---|---|---|---|
| Analysis metadata | `effect-<UUID>.json` `CapturedEffectAnalysis`. `[UUID: …]` maps are flat arrays; subject `box` is a CGRect (S/VideoEffects/VideoEffectCaptureAnalysis.swift:25-36, :66; VideoEffectSubjectTracking.swift:7-20) | `effect-<UUID>.json`. Maps are objects; subjects have flat x/y/width/height on the upright picture; has `maskGaps` (K/CapturedEffectAnalysis.kt:30-41, :124) | Yes, through `analysisFile` |
| Analysis data | `effect-<UUID>.bin`: **LZFSE** masks, temporal-matte ticks (:107-153) | `effect-<UUID>.bin`: **Deflate** masks, no temporal mattes (:236-243) | Yes, through `analysisDataFile` |
| Take recipe | `SourceMedia.videoEffects` | the `<movie>.effects.json` sidecar (not archived; K/ProjectArchive.kt:125-132) | iOS: yes. Takes: no. |
| Person masks, palettes, subjects, Night Fix, frame indexes, audio slices, stabilisation analyses, waveforms, processed audio | `Cache/…` (S/VideoEffects/VideoEffectAssets.swift:13-28; …) | `Cache/…` (K/PersonMaskChunks.kt:115-119; A/…/EffectAssets.kt:153-171) | No: never archived on either side |

Mask placement and the time base also differ (K/CapturedEffectAnalysis.kt:24-28
vs S/VideoEffects/VideoEffectAssets.swift:389-392).

**What each side does with the other's file:**
- **iOS reading a Takes analysis fails hard.** `EffectAnalysisReader`
  throws through `try` (S/MediaRenderer.swift:376;
  S/VideoEffects/VideoEffectAssets.swift:376-379). Preview and export of
  that clip fail.
- **Takes reading an iOS analysis falls back.** It returns null
  (K/CapturedEffectAnalysis.kt:383-388) and analyses the range itself
  (:411-416).

**What each side does when there is no analysis.**
- iOS recomputes masks, palettes and subjects
  (S/MediaRenderer.swift:376-424); temporal-matte histories are skipped.
- Takes recomputes from PersonMaskChunks, ScenePalettes and SubjectTracks
  (A/…/EffectAssets.kt:147-176).

The results are the editor's own, so Quantization "From scene" palettes and
the Crash Zoom face path can differ slightly from what the recorder showed.

**Rules:**
- **iOS → Takes.** Keep `analysisFile`, `analysisDataFile`,
  `capturedEffectID` and both files. Takes ignores them safely, and they
  make the way back to iOS lossless. Write `sources[].videoEffects` to the
  sidecar.
- **Takes → iOS:**
  1. Remove all three keys from every effect whose analysis was made by
     Takes. Do this in every step's timeline content, in every ledger clip,
     and in `sources[].videoEffects`.
  2. The files then leave `mediaFiles` and must not be archived (§3.2).
  3. **Who made it:** parse the `.json`.
     - Takes made it if `palettes` is an object, or `maskGaps` is present,
       or a subject sample has `x` and no `box`.
     - iOS made it if `palettes` is an array. Keep those references and
       files: they were carried in from iOS.

### 5.3 Effect-file names on export

iOS refuses any effect file whose name is not `effect-` plus an extension
in {json, png, jpg, jpeg, heic, mov, mp4, bin, cube} (S/Project.swift:458-461).

- `effect-picture-<hash>.webp`: re-encode to PNG as
  `effect-picture-<hash>.png`, or use the new content's hash.
- `effect-<uuid>.m4v`: read its `ftyp` box. Brand `qt  ` → copy it as
  `effect-<uuid>.mov`; any other brand (the ISO family) → `.mp4`; no
  `ftyp` → blocker `UnsupportedMedia`. A missing file is left out, as any.
- Rewrite every `{"media":{"_0":name}}` value and keyframe value, in every
  step, the ledger clips and `sources[].videoEffects`.
- Keep names unique case-insensitively (S/Project.swift:453).

These are export-time copies; the Takes project itself is not changed.

---

## 6. Audio models and enhance chains

The ids are identical, so copy them verbatim
(S/Project.swift:63-139 ↔ K/AudioEffect.kt:17-172):

| Kind | Model id | Runtime | Weights SHA |
|---|---|---|---|
| `voiceIsolation` | `rnnoise-v0.1-cdf196b1` | `yourec-rnnoise-c-v1` | `f0cdb52b…6b95` |
| `voiceIsolation` | `deepfilternet3-dfc12319` | `yourec-dfn3-coreml-cpu-v1-dsp3dd66dea-milc2141d48` | `1114ff97…425d` |
| `studioVoice` | `lavasr-v2-33ac0408` | `yourec-coreml-fp32-lava-dsp-v1` | `1ef7557e…d2cf` |
| `studioRestoration` | `voicefixer-aae2253c` | `yourec-voicefixer-coreml-44100-context256-overlap-v2` | `67971970…2360` |
| `highPass`, `equalizer`, `gain` | — | — | — |

- **Takes' `ClipAudio.denoise`** becomes a `voiceIsolation` stage with
  RNNoise and `amount = denoise`, over the whole item, under the item's id
  (K/TrackConversion.kt:111-113). The conversion already writes it, so
  nothing extra is needed.
- **Unplayable stages differ by side:**
  - iOS fails the render for an enabled unsupported stage
    (S/OfflineAudioPipeline.swift:43, :64-76).
  - Takes runs RNNoise and DFN3, and since 2026-11-18 LavaSR and
    VoiceFixer too, once their model is downloaded
    (A/…/Denoiser.kt `playable`, `restoration/`). Until then it plays
    those stages **unprocessed**, the stage's card saying so and
    offering the download (A/…/EnhanceInspector.kt `RestorationNotes`).
  - **Rule:** keep the stages verbatim. On import, add one note
    (`NeuralStagesUnprocessed`, naming the models by their ids) when any
    stage with the LavaSR or VoiceFixer model is enabled with an amount
    above 0 (at 0 it changes nothing, S/Project.swift:129-131); the app
    shows it only for the models the phone has not downloaded.

---

## 7. Implementation plan for Takes

### 7.1 New files (core, pure, host-testable)

| File | Contents |
|---|---|
| `K/yourec/YouRecDetect.kt` | `fun isYouRec(manifest: JsonObject, entries: Set<String>, integrity: JsonObject?): Boolean` (§3.4) |
| `K/yourec/YouRecJson.kt` | Tree transforms on `JsonElement`: dates, flat dictionary ↔ object, the ledger, `ClipTransition` defaults, `TextOverlay` style/colour/appearance, id remapping, analysis stripping, media renames. One function per type, each named after the Swift type. |
| `K/yourec/YouRecCodec.kt` | `fun import(manifest: ByteArray, load: (String) -> ByteArray): Imported(project, notes, renames)` and `fun export(project, sidecars, probe): Exported(manifest: ByteArray, steps: Map<String, ByteArray>, renames, conversions, notes)`, plus `fun check(project): List<Issue>` (blockers and losses). |
| `K/yourec/YouRecMediaFiles.kt` | iOS's `mediaFiles` on a converted document (§3.2). |
| `K/yourec/YouRecSchema.kt` | The iOS schema rule (§2.3). |
| `K/yourec/SwiftRequiredKeys.kt` | A table per type of the keys Swift requires (the R columns of §1). It is used by `check` and by the tests to assert that every exported object carries them. This is the guard against the class of the §0 blockers. |
| `K/yourec/YouRecArchive.kt` | Writes the STORED ZIP (§3.6). |
| `K/ProjectRestore.kt` | A YouRec branch (§3.5): Recovery entries, integrity v2, the `.otio` allowed, notes returned. |
| `K/Project.kt` | `SourceMedia.isStillPhoto: Boolean? = null` (optional; keeps round trips). |

**State after phase 2 (export).** `K/yourec/YouRecExport.kt` (`plan`:
the step and manifest trees, the media list with renames and PNG
re-encodes, and the issues; this is §3.6 steps 1-3 and §7.3's `check`, one
function rather than `YouRecCodec.export`/`check`), `YouRecSchema.kt`,
`SwiftRequiredKeys.kt` (`problems`, which every plan asserts empty),
`YouRecArchive.kt` (`write`, `requiredSpace`, `integrity`), the export
transforms in `YouRecJson.kt`, `YouRecMediaFiles.of(manifest, steps)` on
the original's form; in the app, `ProjectStore.archive`,
`YouRecMedia.exportProbe` (track MIME types) and `YouRecMedia.png`. "Zip
for iPhone" with its two dialogs was merged into "Zip project" on
2026-11-18 (§3.6).

Decided in phase 2:
- **Analysis (T8).** An analysis file is the original's when `palettes` is
  an array, `qualities` is absent or an array and there is no `maskGaps`;
  otherwise, or when it is missing or unreadable, its three references are
  removed and it is not archived (loss `AnalysisRecomputed`).
- **Blocked media (D4, §4.2).** Sources and effect movies with the
  extensions ogg, oga, opus, webm, mkv, mka, 3gp, 3gpp, 3g2, amr, awb, or a
  track of MIME VP8, VP9, AV1, Vorbis, Opus, AMR-NB/WB, WebM or Matroska
  (`MediaExtractor` in the app): blocker `UnsupportedMedia`. Nothing is
  transcoded but WebP effect pictures.
- **Size limits** count every reference to a step, as the original charges
  them (S/ProjectArchive.swift:315-321).
- **Ids (T6)** are remapped on the trees by key: the `ID_KEYS`, `members`,
  `participants` and the keys of `videos`; an `EffectParameter`'s and an
  `AudioModel`'s `id` are names and are left.

**State after phase 1 (import).** Written: `YouRecDetect.kt`, `YouRecJson.kt`
(import transforms, plus the date and flat-dictionary transforms both ways),
`YouRecCodec.kt` (`import` only), `YouRecMediaFiles.kt`, `YouRecNote.kt` (the
notes, as kinds plus file names, which the app words), `LinearPcm.kt`
(CAF/AIFF → WAV), the YouRec branch of `ProjectRestore.kt`,
`SourceMedia.isStillPhoto`; in the app, `A/YouRecMedia.kt` (probe and note
texts) and the dialog in `A/ProjectsScreen.kt`. Phase 2 (export):
`YouRecCodec.export`/`check`, `YouRecSchema.kt`, `SwiftRequiredKeys.kt`,
`YouRecArchive.kt`, "Zip project" in the original's form (§3.6).

### 7.2 Transforms, collected

| # | Transform | iOS→T | T→iOS |
|---|---|---|---|
| T1 | Dates (`createdAt`, `updatedAt`, `sources[].recordedAt`) | Double → Long | Long → Double |
| T2 | Ledger | `[{clip, insertion}]` → `[clip]` + `captureInsertions`; add bookmarks from events | §1.4 |
| T3 | Steps | `{state: {timeline, outputSize, canvasReference}}`; handle `canvasReference` (§1.3) | materialised timeline form (§1.4) |
| T4 | `placements[].videos` | flat array → object | object → flat array |
| T5 | `ClipTransition` | = | write `axis`, `divider`, `reverse`, `audio`, `first`, `second` |
| T6 | Ids | = | map any id that is not a UUID → `nameUUIDFromBytes` (uppercase) everywhere it appears. That covers item ids, `occurrenceID`, `followsVideo`, link members, placement participants and keys, transition ends, `generated.owner`/`freezeVideo`, bookmark anchors, and `nextClipID`. |
| T7 | `TextOverlay` | nil appearance → `legacy(style, color)`; drop style/colour | add style/colour; null appearance → `plain` |
| T8 | Effects | keep the analysis; `sources[].videoEffects` → sidecar | strip Takes analysis; webp→png, m4v→mp4; sidecar → `sources[].videoEffects` |
| T9 | `timingFingerprint` | recompute or `""` | `""` (until §8 Q2) |
| T10 | Manifest extras | drop `automaticCanvasVersion`, `recordingHistoryVersion`, `recordingInsertion`, `recordingInsertionStep`, `recordingSite`; schema per §2.3 | add `automaticCanvasVersion: 1` and `recordingHistoryVersion: 1`; drop `captureInsertions` and the recording run; schema per §2.3 |
| T11 | `isStillPhoto` | keep (new field) | `photo-*` → true |
| T12 | Audio files | `.caf`/`.aif` → `.wav` (rename and rehash) | block ogg/webm/mkv/VP9/AV1 |

### 7.3 Export checks (`check`)

**Blockers** (any one stops the export):
- a media container or codec that iOS cannot play (§4.2);
- the colour-motion check fails (§1.7);
- a frozen frame without a `freezeFrame` effect;
- an exported timeline fails `TrackInvariants.violation`;
- iOS `validate` name rules fail (§1.7, §3.2);
- a size limit is exceeded (§3.1);
- a schema result above 12 (impossible by construction; assert it).

**Losses** (confirm in a dialog):
- Takes effect analysis will be recomputed on iPhone;
- the recording run in progress is dropped;
- caption regrouping becomes unavailable on iPhone (fingerprint `""`).

### 7.4 UI

- **Import.** The existing "Open a project ZIP" button (A/…/ProjectsScreen.kt:171-176,
  launcher :111-113) calls `ProjectStore.openArchive` (A/…/ProjectStore.kt:938-975),
  which calls `ProjectRestore.restore` (:957).
  - Detection happens inside restore; no new entry point is needed.
  - Change the result to carry `notes`, and show them in a dialog after a
    YouRec restore ("Imported from YouRec: …").
- **Export.** *Superseded on 2026-11-18: "Zip project" writes the
  original's form itself (§3.6), and the steps below describe the first,
  separate "Zip for iPhone".* Add **"Zip for iPhone"** right after "Zip project"
  (A/…/ProjectsScreen.kt:306; strings.xml:100).
  - Add a new string in `values` and `values-it`.
  - Give it its own target state and `CreateDocument` launcher, modelled on
    `saveZip` (:104-109) and `zip` (:139-140). The suggested file name is
    `<title>.zip`.
  - Flow: call `YouRecCodec.check`. If there are blockers, show a dialog
    listing them. Otherwise show a confirmation listing the losses, then
    call the new `ProjectStore.archiveForIPhone`, which calls
    `YouRecArchive.write`. Compute `requiredSpace` from the converted file
    set, including the PNG re-encodes.

### 7.5 Test plan (host tests in `core`)

**Fixtures.** Put them under
`core/src/test/resources/yourec/<case>/project.json` and `History/*.json`.
- Write them by hand from the Swift encoders' rules in §1.1: sorted keys,
  Double dates, flat dictionaries, `{"case":{…}}` enums.
- One case per feature group:
  1. plain takes and ledger;
  2. an insertion with `recordingSite`;
  3. layers and placements with `edgeCrop` and motion;
  4. transitions of every style, at defaults and with all keys;
  5. titles with nil appearance and with a design, and an imported face;
  6. subtitles with words;
  7. censors and fades, enhancement chains with each model;
  8. every video effect, a frozen frame, captured analysis references;
  9. mirror, pauses, images, stabilisation;
  10. `canvasReference`;
  11. audio-only `.caf` and `isStillPhoto`;
  12. integrity v2 with `Recovery/`.
- The step files are kept as `History/step-<n>.json`, named so in
  `project.json`; the helper (`YouRecFixtures.load`) names each by the
  SHA-256 of its bytes and rewrites the manifest, so a fixture can be
  edited by hand.
- **Phase 1 cases** (core/src/test/resources/yourec): `takes` (1, 2: ledger,
  insertion, `recordingSite`, marks), `layers` (3, 4 in part: layers,
  placement with `edgeCrop` and motion, a wipe with every key, bookmarks of
  every anchor and a 300-character label), `titles` (5, 6, 7: nil and
  designed titles, an imported face, a title over 500 units, captions,
  fades, a censor, chains with all four models), `effects` (8, 9: Crash Zoom
  with its captured analysis on the source and the clip, Noir, a held
  frame, mirror, pauses, a picture, stabilisation, a LUT), `canvas` (10),
  `audio` (11). Case 12 (Recovery) is built in `YouRecRestoreTest`. Not yet:
  every one of the 24 effects and of the nine transition styles.
- **Ask for one real YouRec ZIP** to confirm the fixtures (§8 Q1).

**Tests:**
1. **Detection.** Every YouRec fixture is detected; every existing Takes
   archive fixture (`K/…/ProjectArchiveTest`) is not.
2. **Import.** Each fixture imports to a `Project` that passes `validate()`.
   Field-by-field assertions: dates, ledger, insertions, placement map,
   title appearance, transition settings, and the effect sidecar written.
3. **Export shape.** For a set of Takes projects built in tests (every
   feature of §2.2), the output satisfies all of these:
   - every object carries `SwiftRequiredKeys`;
   - every id parses as a UUID;
   - `schemaVersion` is ≤12 and equals the §2.3 rule;
   - no sequence-form step;
   - `automaticCanvasVersion` and `recordingHistoryVersion` are 1;
   - `placements[].videos` is an array of even length;
   - `mediaFiles` (iOS rule) equals the archived set exactly;
   - every name passes the iOS `validate` name rules.
4. **Round trip Takes → iOS → Takes.** `history[i].toTimeline()` is equal
   before and after, for every i, modulo the documented changes: ids
   remapped, fingerprint `""`, analysis stripped, the ledger folded (each
   step's materialised timeline equal).
5. **Round trip iOS → Takes → iOS.** The JSON tree is equal modulo
   (implemented for every step whose cursor is the ledger's end, the
   manifest's sources, ledger and export settings):
   - a title without a design gets the design the original draws it with;
   - the manifest's `id` (a restore gives a new one anyway);
   - each source's `sha256`, which the restore takes from integrity;
   - order inside sets and flat dictionaries;
   - key order;
   - Double formatting;
   - `recordingSite`/`recordingInsertion` dropped;
   - `canvasReference` → `outputSize`;
   - fingerprint `""`;
   - the ledger folded.
6. **Archive.**
   - Entries are STORED, with no data descriptor (read back with
     `java.util.zip.ZipFile` and check `method == STORED`).
   - `integrity.json` is exactly `{"sha256":{…},"version":1}`.
   - The `.otio` is there under the original's name; there is no `Recovery/`.
   - Step names are lowercase hex of their bytes.
   - A YouRec fixture ZIP with Recovery entries restores; one with an extra
     file is refused, as iOS does.
7. **Conversions.** `.caf` (LPCM) → `.wav` byte check; webp → png
   reference rewrite in steps, ledger and sources.

There is also a manual gate before release:
- restore a Takes "Zip project" on an iPhone with the current YouRec,
  open it, play and export;
- restore a YouRec ZIP on a Galaxy A54 and play it.

---

## 8. Open questions and risks, ranked

| # | Risk / question | Effect | Mitigation |
|---|---|---|---|
| R1 | **The fixtures are hand-written.** No real YouRec ZIP has been read (docs/TRACKS-PLAN.md §3.6 says the same). | Any mistake in synthesized-coding assumptions is a whole-project refusal. | **Q1: get one real YouRec ZIP per feature group** from a device, or from the Swift test target's `ProjectArchive` tests, and add them as fixtures before shipping. |
| R2 | A required Swift key is missing anywhere in the graph, as with the `ClipTransition` and `TextOverlay` blockers already found. | iOS refuses the whole project. | `SwiftRequiredKeys` table plus the export-shape test (§7.5 #3). |
| R3 | The archived set differs from what iOS recomputes as `mediaFiles` (an orphan analysis, an unused LUT, a font). | iOS refuses the whole restore. | Compute iOS `mediaFiles` on the converted document; assert set equality. |
| R4 | ZIPFoundation and Java ZIP framing (data descriptors, DEFLATE level 0). | Restore fails. | Write STORED with a CRC pre-pass, exactly like iOS. |
| R5 | `automaticCanvasVersion` or `recordingHistoryVersion` forgotten. | iOS silently rewrites `outputSize` and compacts history. | Tests. |
| R6 | Fragmented `.mov` from the iOS recorder on Android `MediaExtractor`: seeking and duration. ProRes and MV-HEVC imports. | Takes show wrongly or not at all. | Test on the A54 with a real take; note unsupported codecs on import. |
| R7 | `canvasReference` → `outputSize` changes later editing semantics (fixed frame). | A later edit keeps a frame iOS would have recomputed. | Accept and note it; or decision D1: add `canvasReference` to Takes' `EditState` (a model change: Takes would then need its own canvas rule). |
| R8 | Subtitle fingerprint formula (Swift Double formatting in `JSONEncoder`). | Only caption regrouping is affected. | Write `""` (Q2: emulate Swift's encoder later, verified on a fixture). |
| R9 | Probing the referenced source size for `canvasReference` happens in the app layer, not core. | Import needs a probe callback. | Done: `YouRecCodec.import(…, probe: (filename) -> Size?)`, fed by `ProjectRestore.MediaProbe.size` (A/YouRecMedia.kt). Its rounding on a phone with a rotated take is unverified. |
| R10 | LavaSR and VoiceFixer chains from iOS play unprocessed on Takes until their model is downloaded. | Different sound until then. | Ported on 2026-11-18 (`restoration/`): the import note names the models to download, and the stage's card offers them. The ONNX conversions are not the iPhone's Core ML ones: close, not bit-identical. |
| R11 | Bookmark labels of 101–500 characters from iOS failed Takes `validate` through the sequence view (K/Project.kt:104, :845-847). | Import refused. | **Closed by D2**: the Takes limit is 500, as the timeline bookmark's (K/TrackTimeline.kt:223). |
| R12 | Grapheme and UTF-16 length limits differ (titles ≤500, words ≤4000). | A rare import refusal. | Titles: shortened with a note (phase 1). Words: not yet; a word over 4000 UTF-16 units still refuses the import. |
| R13 | Takes' strict missing or damaged media rule versus iOS's skip-and-relink. | A YouRec ZIP with a missing overlay was refused on Takes. | **Closed by D3**: iOS's behaviour on the YouRec branch only. |
| R14 | iOS captured analysis is dropped from the preview fidelity on Takes (recomputed masks). | Small visual differences from what was recorded. | Accept; documented. |
| R15 | Ledger folding on export: iOS undo steps are fully materialised. | No visible change; an iOS "restore unused recording" sees the takes as used. | Accept. |
| R16 | iOS `includeCaptureBookmarks` skips frozen-frame items (S/TrackTimeline.swift:1464); Takes does not. Takes appends ledger takes at the end of the picture, iOS at `timeline.duration`. | Small placement difference for takes recorded after a sound overhang. | Only for ledger replay, which export folds; on import, replay happens in Takes. Note it. |

**Decisions** (taken 2026-11-18):
- **D1** `canvasReference`: on import, converted to a fixed `outputSize`
  through the app's probe, with the note `CanvasFixed` (or `CanvasDropped`
  when the size cannot be read). Takes' `EditState` does not model it.
  User-visible departure, in docs/PARITY.md.
- **D2** Bookmark label limit: raised to 500 on the clip `Bookmark`, as the
  original's timeline bookmark (S/TrackTimeline.swift:1220). Nothing else
  read the old 100; the editor still cuts a typed label to 120
  (`TrackBookmarks.EDITOR_LABEL`). Closes R11.
- **D3** Missing or damaged media in a YouRec ZIP: as iOS, skipped or
  deleted, the source left for Repair media, with a note; on the YouRec
  branch only. A Takes ZIP keeps the strict rule. Closes R13.
- **D4** No video or audio transcoding in this phase, except CAF/AIFF linear
  PCM → WAV on import (a rewrap, pure Kotlin). Compressed CAF is left out
  with a note; a codec the device cannot decode is imported with a note.
  webp→png for "Zip for iPhone" remains required in phase 2.

**Phase 2 tests** (YouRecExportTest): the shape (#3) of a Takes project with
a gain, marks, a dissolve, a title without a design, captions, a sound
file, a take inserted at a place and a photo, of an effects project, of a
project with ids that are not UUIDs, and of every phase-1 fixture; #4 on
that Takes project through the phase-1 restore, equal step by step but for
the caption fingerprint and the titles' designs; #5 on every fixture; #6
stored entries with no data descriptor (local headers read raw), integrity
exactly `{"sha256","version":1}` of the media, step names their hashes, no
`.otio` or `Recovery/`; WebP → PNG, `.m4v` → `.mp4`/`.mov` and the
analysis rule; the blockers. Not done: a real iPhone restore (manual gate).

**Also deferred from phase 1:** HEIC → PNG below API 28; R12 for caption
words (only titles are shortened); iOS's migrations of documents without
`recordingHistoryVersion` or `automaticCanvasVersion`.
