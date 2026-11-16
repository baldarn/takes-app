# Recorder: review against the original iOS app, and the plan to reach parity

Reviewed: this repository on `main` at `d54dc8a` against the original iOS app at its
commit `faf1815`. On the Android side everything the recorder touches was read in full;
on the original side the files listed under "Scope" were read in full.
`docs/PARITY.md` section 3 was used as a map and checked row by row (see 1.16).
No file other than this one was changed.

Paths: `O/` is `YouRec/` in the original repository, `T/` is its `Tests/`,
`A/` is `app/src/main/kotlin/app/takes/`, `C/` is `core/src/main/kotlin/app/takes/core/`.
Four files are cited so often that they have short names:

- `RV` = `O/RecorderView.swift`, `CC` = `O/CaptureController.swift`
- `aRS` = `A/RecorderScreen.kt`, `aCC` = `A/CaptureController.kt`

How a statement was established:

- no mark: read in the source on both sides;
- **[probe]**: read in the compiled CameraX 1.6.2 and Media3 muxer 1.11.1 artifacts
  that this build resolves (see "Probes" at the end);
- **[inferred]**: follows from the source but was not observed running;
- **to confirm**: an Android API claim made from memory, to be checked against the
  CameraX version in the build before relying on it.

Nothing was run on a phone or an emulator.

Scope, original: `RecorderView.swift`, `CaptureController.swift`, `CaptureAudio.swift`,
`CaptureSegmentWriter.swift`, `LiveMicrophone.swift`, `MultiCameraCapture.swift`,
`DualCameraLayout.swift`, `DualCameraMasks.swift`, `DualFaceDetector.swift`,
`HorizonLevel.swift`, `RecorderFrontLight.swift`, `RecorderZoomViewport.swift`,
`CameraCrop.metal`, `ZoomTransition.swift`, `RecordingSalvage.swift`, `VoiceRecorder.swift`,
`OriginalRecordingsView.swift`, the recorder parts of `ContentView.swift`,
`YouRecApp.swift`, `ProjectStore.swift`, `ProjectRecovery.swift` and `Project.swift`,
the live side of `VideoEffects/` (`LiveVideoEffects.swift`, `LiveEffectsPanel.swift`,
`CaptureZoomHistory.swift`, the catalogue), and the recorder tests.
Scope, Android: `RecorderScreen.kt`, `CaptureController.kt`, what they call in
`ProjectStore.kt`, `MainActivity.kt`, `ProjectsScreen.kt`, `EditorScreen.kt` and `core`,
the manifest, the build files and the string resources.

Build facts: `minSdk` 26, `targetSdk` and `compileSdk` 36 (`app/build.gradle.kts:18-23`);
CameraX 1.6.2 and Media3 1.11.1 (`gradle/libs.versions.toml:8-9`); CameraX modules
`camera-camera2`, `camera-lifecycle`, `camera-video`, `camera-view`
(`app/build.gradle.kts:67-70`).

## Summary

Status meanings: **matches** (same behaviour, differences noted are cosmetic),
**differs** (both have it, it behaves differently), **partial** (present, less than the
original), **missing**, **n/a** (cannot exist on Android as on iPhone; the closest
alternative is named).

| Status | Count |
|---|---|
| matches | 14 |
| differs | 12 |
| partial | 20 |
| missing | 37 |
| n/a | 4 |
| Total | 87 |

What a person who knows the iPhone app notices first, in order:

1. There are no settings at all: no quality, colour, stabilisation, mirror, fit or fill,
   focus, assists, microphone priority (U1, U2 and the rows they open).
2. The big button pauses; in the original it stops, and Pause is the small button (T2).
3. Tapping the picture does nothing: no tap to focus, no focus lock (F1 to F3).
4. Zoom feels different: the default move takes one second instead of being instant,
   the pinch has no dead zone and no read-out, the presets are another list (Z5, Z9, Z12).
5. Leaving the app or the Recorder tab finishes the take, and ends a "Record here" run;
   the original pauses and keeps both (R5, T9, T11).
6. The interface never turns, and a paused take can continue in another orientation
   (O1, O2).
7. Sound: no recording gain, no level before recording, no remembered microphone order
   (S3, S5, S6).
8. No red frame while recording, no haptics, no front light (T7, H1, A3).
9. The large pieces: level horizon, follow face, two cameras, live effects
   (A1, A2, D1 to D6, E1 to E4).

Three findings that change earlier assumptions:

- **An interrupted take is probably already recoverable.** `docs/PARITY.md:86` says CameraX
  writes a plain MP4 that is lost when the app is killed. CameraX 1.6.2 writes MP4 through
  Media3's muxer, which rewrites the index of the file about once a second, and CameraX
  itself calls that muxer "interruption resilient" **[probe]**. With the indexing the port
  already does at launch (`A/ProjectStore.kt:90,427-432`) the picture and sound of a killed
  take should come back, without its marks. This needs one check on the emulator (R9) and
  turns a several-day item into a small one.
- **The mirror defaults and the frame rate already match.** CameraX mirrors the front
  preview and does not mirror the recording by default, and its video default is 30 to 30
  frames per second **[probe]**; these are the original's defaults (P2, P6, P7).
- **Steps 0 to 15 of the plan need no new permission and no new library**, and steps 0 to
  16 touch none of the editor files.

## 1. Inventory

### 1.1 What the screen shows

Original (`RV:210-255`, `RV:435-513`, `RV:514-566`, `RV:579-631`), top to bottom:

- top bar: microphone pill (icon, name, seven-bar meter, a mark for clipping or no signal)
  that opens "Recording volume"; the clock pill; the live effects button; the settings
  button, with a cyan dot when an assist is on (`RV:504-508`);
- under it: the "iPhone is warm" note for effects and a yellow warning with a close button;
- above the bottom row: the project chip, the crash zoom and dolly zoom buttons when those
  effects are on, the zoom presets when opened, the camera strip (flip, camera menu, zoom
  read-out, focus button; with two cameras: swap and layout);
- bottom row: left, centre and right buttons that change with the state (table below);
- around everything: the red or yellow frame, and the front light ring when it is on.
- The status bar and the tab bar are hidden (`RV:333-334`); Projects and Edit are buttons.

Android (`aRS:124-137`): a status column at the top (`aRS:141-189`), the zoom row, the
camera chips and the controls row at the bottom (`aRS:131-135`), a message box in the
middle (`aRS:136`). The system status bar and the app's three tabs stay visible
(`A/MainActivity.kt:45,65-68`).

| State | Original: left, centre, right | Android: left, centre, right |
|---|---|---|
| Idle | Projects; red circle, starts; Edit (`RV:604-610`, `RV:591-592`, `RV:625-629`) | nothing, or "Record at the end" during an insertion; red circle, starts; "New project" when the project has takes (`aRS:274-279`, `aRS:301`, `aRS:305-307`) |
| Recording | Mark; red square, **stops**; Pause (`RV:427`, `RV:581`, `RV:612-617`) | "Stop"; two bars, **pauses**; "Mark" (`aRS:270-272`, `aRS:289,296-299`, `aRS:309-311`) |
| Paused | Mark, disabled; play triangle, resumes; Finish (`RV:80`, `RV:587-589`, `RV:618-623`) | "Stop"; red circle, resumes; nothing (`aRS:270-272`, `aRS:301`) |
| Saving | a spinner in the centre button and in the clock (`RV:586`, `RV:476`) | grey circle, disabled; "SAVING…" (`aRS:301`, `aRS:153`) |
| Top, idle | microphone name and live level, clock, effects, settings | "Ready", project line, "Mic: …" chip (`aRS:150,160-166,179`) |
| Top, recording | the same, clock on red, effects disabled (`RV:440`, `RV:498`) | "REC m:ss" on red, project line, level bar, active microphone (`aRS:145-178`) |

### 1.2 Take controls

| ID | Item | Original | Android today | Status | Defaults, ranges, where stored |
|---|---|---|---|---|---|
| T1 | Start a take | Centre button; needs the camera ready and the microphone running, else "Waiting for the microphone" (`RV:579-603`, `O/ContentView.swift:557-571`, `CC:1357-1376`, `CC:1396-1400`) | Centre button; creates a project when there is none (`aRS:287-303`, `aCC:398-425`) | **matches** | — |
| T2 | Stop and Pause | Centre button stops; a small button on the right pauses (`RV:581`, `RV:612-617`) | Centre button pauses; "Stop" is a text button on the left (`aRS:270-272`, `aRS:281-303`) | **differs** | — |
| T3 | Pause closes a segment, Resume opens the next one of the same take | `CC:1487-1507`, `CC:3098-3117`, `CC:1367-1370` | `aCC:434-451`, `aCC:487-507` | **matches** | — |
| T4 | Finish a paused take | Right button "Finish paused recording" (`RV:618-623`, `CC:1474-1486`) | "Stop" (`aRS:270-272`, `aCC:437-439,453-462`) | **matches** | — |
| T5 | One file per segment, never rewritten, in the project folder | `<ms>-<uuid>.mov` (`CC:1404-1406`) | `<ms>-<id>.mp4` (`aCC:408-409`) | **matches** | Container and codec: P5 |
| T6 | Status and clock | One pill: time of the edit plus the running segment, "REC" or "PAUSED", dark red when the project already has footage (`RV:438-493`) | Pill "Ready", "REC m:ss" (the take), "PAUSED", "SAVING…"; a second line with title, captured time, insertion place and "Landscape" or "Portrait" (`aRS:143-166`) | **differs** | Original counts the edited length (`RV:443`), Android the captured length (`aRS:161`) |
| T7 | Frame around the screen: red while recording, yellow while paused | 2 pt line, 3 pt from the edge (`RV:281-291`) | absent | **missing** | — |
| T8 | "Start new project" in the recorder | Chip, when the project has footage and no take runs (`RV:406-410`, `O/ContentView.swift:579-583`) | "New project" on the right (`aRS:305-307`) | **matches** | — |
| T9 | Leaving the recorder during a take | Not possible: during a take the side buttons are Mark and Pause or Finish, and Projects, Edit and the tabs are disabled (`RV:427`, `RV:609`, `RV:629`, `O/ContentView.swift:547`) | Tabs always work; changing tab disposes the screen and finishes the take (`A/MainActivity.kt:65-68`, `aRS:117-121`, `aCC:191-192`) | **differs** | — |
| T10 | Mark a moment (bookmark) | Left button while recording and paused (disabled when paused); time read from the clock at the tap; written at once beside the take; haptic; the icon fills for 0.7 s (`RV:68-84`, `CC:2716`, `O/CaptureSegmentWriter.swift:108-122`) | "Mark", then "Mark · n", only while recording; time of the last status report from the recorder; kept in memory until the segment is saved; no haptic (`aRS:309-311`, `aCC:428-432`, `aCC:494`) | **partial** | Event kind `bookmark` on both |
| T11 | Record here (takes go in at the playhead) | "Record here" in the editor; the recorder shows "Insert at m:ss · Back to edit"; after Stop the app returns to the editor; backgrounding keeps the run (`O/AddVideoPanel.swift:50-52`, `O/ContentView.swift:45-48,381-383,551-556`, `RV:400-405`) | "Record here" in the editor; the recorder shows "Inserting at m:ss" and "Record at the end"; after Stop it stays in the recorder; Stop, backgrounding and leaving the tab all end the run (`A/EditorScreen.kt:994`, `A/ProjectStore.kt:304-307`, `aRS:162,274-279`, `aCC:455`) | **partial** | Stored in the project on both (`C/Project.kt:316-318,383-387`) |
| T12 | Screen stays on | While the recorder shows and while a take runs (`O/ContentView.swift:102-105,377-378`) | While the recorder shows (`aRS:116-119`) | **matches** | — |
| T13 | Full screen | Status bar and system overlays hidden (`RV:333-334`) | Status bar visible, tabs under the controls (`A/MainActivity.kt:45`, `aRS:130`) | **differs** | — |
| T14 | Telling the person about a problem | Yellow banner under the top bar with a close button, an alert for errors (`RV:632-639`, `O/ContentView.swift:388-391`) | A box in the middle of the picture with "OK"; red lines for microphone problems (`A/MainActivity.kt:78-88`, `aRS:136,180-187`) | **matches** | Same purpose; the box covers the picture until dismissed |
| T15 | Permissions | Camera and microphone both required; one message for both (`CC:813-821`) | Camera required, with a way to Settings; without the microphone it records silent and says so (`aRS:79-96`, `aCC:416-417,421`) | **differs** | — |

### 1.3 Camera, lens and zoom

| ID | Item | Original | Android today | Status | Defaults, ranges, where stored |
|---|---|---|---|---|---|
| Z1 | Choice of camera and lens | Menu of every wide, ultrawide and tele camera, front and back (`CC:1057,1088-1093`, `RV:533-549`) | A row of chips, one per camera CameraX lists, named Rear, Front, "ultrawide", "tele" (`aRS:249-263`, `aCC:205-223`) | **matches** | On phones that expose one logical rear camera (most Pixels) the row is Rear and Front, and the ultrawide is reached by zooming below 1× |
| Z2 | Flip to the camera last used on the other side | Button; remembers the last front and the last rear camera (`RV:525-532`, `CC:1255-1271`) | absent; the chips do it when there are only two | **missing** | Original: `lastFrontCamera`, `lastRearCamera`, global |
| Z3 | Change camera during a take, inside the same segment | The preview freezes on the last frame, the picture is held in the file, the sound goes on, an event `cameraSwitch` is saved (`CC:1513-1647`, `CC:3356-3383`, `O/CaptureSegmentWriter.swift:124-129,176-180`) | Unbind and bind under a persistent recording; an event `camera` is saved; nothing covers the preview during the change (`aCC:225-256`) | **partial** | What the file contains during the change was not determined (section 6) |
| Z4 | Camera at launch | Always the rear main camera (`CC:1058`) | The camera used last (`aCC:185-188,241`) | **differs** | Android: `camera` in the `recorder` preferences. The port does more |
| Z5 | Zoom read-out and presets | A button shows "1.0×" and opens the presets; presets 0.5, 1, 2, 3, 5, 6 inside the lens range; range capped at 10× (`RV:936-947`, `RV:1049-1053`, `CC:1785-1786`) | Presets always shown: the lens minimum when below 1, then 1, 2, 3, 5, 10 inside the range; the value is written only when it is not a preset; no cap (`aRS:234-245`, `C/ZoomTransition.kt:40-43`, `aCC:243`) | **differs** | Selected within 0.04 (`RV:1078`) against 0.005 (`aRS:238`) |
| Z6 | Preset: tap uses the saved time, double tap takes one second | `RV:1094-1128`, `RV:1079` | `aRS:238-241` | **matches** | — |
| Z7 | "1× Main": from the ultrawide, 1× changes to the main camera | `RV:1077,1089`, `CC:1508-1511,1741-1744` | absent | **missing** | Only meaningful where the ultrawide is listed as a camera of its own |
| Z8 | Zoom transition time: range | 0 to 30 s in steps of 0.5; menu values 0, 0.5, 1, 2, 3, 5, 10, 15, 30 (`O/ZoomTransition.swift:71-78`) | The same (`C/ZoomTransition.kt:29-37`) | **matches** | — |
| Z9 | Zoom transition time: default | Instant (`RV:1046`, `CC:3127`) | 1 s (`aCC:98`) | **differs** | Original `zoomTransitionSeconds`, global; Android `zoomDuration`, global |
| Z10 | Zoom transition time: how it is changed | Drag the time up or down beside the presets, 14 pt per half second, with a haptic tick; a tap shows a hint for 2.5 s; also a picker in More settings (`RV:1131-1189`, `RV:1056-1065`, `RV:1255-1264`) | A menu of the nine values on the chip (`aRS:224-233`) | **partial** | — |
| Z11 | Timed zoom: the curve | Quintic ease on the logarithm of the magnification, driven by elapsed time (`O/ZoomTransition.swift:53-63`, `CC:2326-2345,2366-2421`) | The same curve, stepped every 16 ms (`C/ZoomTransition.kt:12-26`, `aCC:368-382`) | **matches** | Core test `CoreTest.kt:218-266` |
| Z12 | Pinch | A dead zone against finger jitter, measured from the start of the gesture; a badge "2.0×"; when a time is set, the pinch only composes the move ("2.0× · 3s on release") and it runs on release (`O/RecorderZoomViewport.swift:116-214`) | Each pinch event multiplies the zoom; always instant; no badge (`aRS:125-127`, `aCC:384-388`) | **partial** | — |
| Z13 | Two-finger pan of the picture inside the lens, with the real surroundings shown while composing | `O/RecorderZoomViewport.swift:139-276`, `CC:2109-2246`, `O/DualFaceDetector.swift:157-166` | absent | **missing** | Original: `panX`, `panY` per camera (`CC:1661-1664,1748-1752`) |
| Z14 | Each camera remembers its zoom | `camera.<id>` (`CC:1648-1660,1740-1745`) | `zoom.<id>` (`aCC:244,365`) | **matches** | Global on both |
| Z15 | Hiding the sensor mode change during a zoom out | `CC:2301-2345`, `O/ZoomTransition.swift:21-49` | — | **n/a** | CameraX has one zoom ratio; the phone changes lens or mode itself |

### 1.4 Picture format

| ID | Item | Original | Android today | Status | Defaults, ranges, where stored |
|---|---|---|---|---|---|
| P1 | Quality: 1080p or 4K | Picker in More settings; fixed until Stop; cameras that cannot do 4K are marked "· 1080p only" (`CC:6-11,1841-1864`, `RV:1295-1301`, `RV:575`) | 1080p, with a fallback to what the camera has (`aCC:265`) | **missing** | Original default 1080p, `captureQuality`, global |
| P2 | 30 frames per second | Set on the device (`CC:1672-1675,1714-1715`) | Not set; CameraX's default range is 30 to 30 **[probe]** | **matches** | **[inferred]**; what a dark scene does is in section 6 |
| P3 | Colour: SDR or HDR (HLG, 10 bit) | Segmented control, changeable when idle or paused (`RV:4-43`, `CC:120-130,1676-1709,1806-1839`) | SDR | **missing** | Original default **HDR** where the camera has it (`CC:120,125`), `captureColorMode`, global |
| P4 | Colour: Log | Apple Log or Apple Log 2 (`CC:1697-1708`) | — | **n/a** | No Log profile in Android's camera API; the closest is HLG |
| P5 | Codec, bit rate, container, sound format | H.264 for SDR, HEVC Main10 for HDR and Log; width × height × 6 bits per second (12.4 Mbit/s at 1080p, 49.8 at 4K); a key frame every second, no frame reordering; AAC 48 kHz stereo 192 kbit/s; fragmented MOV (`O/CaptureSegmentWriter.swift:215-248`) | CameraX defaults for the chosen quality; MP4 (`aCC:264-267,409,420`) | **partial** | — |
| P6 | Mirror selfie preview | Switch, on by default, can change during a take (`CC:131-133,141`, `RV:1249`) | The front preview is mirrored (CameraX default **[probe]**); no switch | **partial** | Original `mirrorFrontPreview`, global |
| P7 | Save mirrored selfies | Switch, off by default, locked during a take and while paused (`CC:134-140,1347-1351`, `RV:1250`) | Not mirrored (CameraX default **[probe]**); no switch | **partial** | Original `mirrorFrontRecording`, global |
| P8 | Fit the whole recorded frame, or fill the screen | Switch, fit by default (`RV:58,158,176,1302-1305`) | Always fit (`aRS:100-103`) | **partial** | Original `fitRecordedFrame`, global |
| P9 | Stabilisation | Tile "Stabilize"; the least delayed mode the format has, never the cinematic ones; a status line; changeable when idle or paused (`RV:798-801`, `CC:823-832,860-869`, `O/MultiCameraCapture.swift:109-112`) | absent | **missing** | Original default off, `dualStabilization`, global |
| P10 | Landscape selfie with the phone upright | `CC:1866-1971` | — | **n/a** | Depends on a square front sensor and an iOS 26 mode |

### 1.5 Focus

| ID | Item | Original | Android today | Status | Defaults, ranges, where stored |
|---|---|---|---|---|---|
| F1 | Tap to focus | Sets the focus point and keeps continuous autofocus there; a yellow 48 pt box until focus settles (0.85 s at least, 4 s at most); hints for fixed-focus lenses (`CC:2442-2472,2702-2709,3318-3349`) | absent | **missing** | — |
| F2 | Focus lock | The focus button shows AF, Locked, MF or Fixed; a tap changes between auto and locked; a hint appears for 1.8 s (`RV:949-1041`, `CC:2422-2430,2473-2503`) | absent | **missing** | Original: `locked` per camera (`CC:1658,1765-1771`) |
| F3 | Manual focus | Swipe up or down on "MF" (fine for the first 40 pt, faster after), "Focus N%" bubble; a Near to Far slider in More settings (`RV:992-1012`, `RV:1191-1214`) | absent | **missing** | Lens position 0 to 1, `lens` per camera |
| F4 | Focus peaking | Hold the focus button 0.45 s; sharp edges are drawn in red on the preview only (`RV:964-968,1008`, `CC:2431-2435`) | absent | **missing** | Original default off, `focusPeaking`, global |

### 1.6 Assists

| ID | Item | Original | Android today | Status | Defaults, ranges, where stored |
|---|---|---|---|---|---|
| A1 | Level horizon | Rear cameras; keeps small tilts level inside a fixed crop; "Max angle" with a live preview of the crop (`RV:802-807,774-794,1230-1241`, `CC:93-108,833-850`, `O/HorizonLevel.swift:8-282`) | absent | **missing** | Off by default, `levelHorizon`; angle 0.5° to 10° in steps of 0.5, default 9° (`O/HorizonLevel.swift:9-17`), `levelHorizonDegrees`; global |
| A2 | Follow face (one camera) | Keeps a face centred by moving a crop over the whole lens; at the widest zoom it crops 1.2× to have room; can be turned on during a take; a manual pan turns it off (`RV:808-812`, `CC:851-904`, `O/DualFaceDetector.swift:56-197`, `O/DualCameraLayout.swift:128-180`) | absent | **missing** | Off by default, `dualFaceFollow`, global |
| A3 | Front light | A ring of light at the edge of the display, not recorded; width Thin, Medium or Wide (4.5, 8.5, 14 % of the short side); White, Warm, Cool or a custom colour; opacity; the screen goes to full brightness while it is on; works during a take (`O/RecorderFrontLight.swift:4-241`, `RV:204-208,813-815`) | absent | **missing** | Width Medium, White, opacity 1 (0 to 1 in steps of 0.05), all stored globally; being on is not stored (`RV:50`) |
| A4 | Explanation of each assist | Hold a tile for half a second (`RV:834-849,718-720`) | absent | **missing** | — |

### 1.7 Two cameras

All absent on Android.

| ID | Item | Original | Status | Defaults, ranges, where stored |
|---|---|---|---|---|
| D1 | "Use two cameras" and the choice of the pair | Any pair the phone can run together at 1080p, 30 fps, SDR; First and Second menus offer only possible partners (`RV:644-681,819-833,850-882`, `CC:957-1017`, `O/MultiCameraCapture.swift:93-170`) | **missing** | Changed only when idle or paused |
| D2 | Layouts | Floating, side by side, top and bottom, diagonal; the divider is dragged between 15 % and 85 %; the diagonal can be flipped (`O/DualCameraLayout.swift:5-10,27,67-109`, `RV:292-320,1386-1422`) | **missing** | Default floating, dividers 0.5; `dualCameraLayout`, global; can change during a take and is recorded |
| D3 | The floating picture | Rectangle or circle; drag to move; pinch to resize, a near-horizontal pinch changes only the width, a near-vertical one only the height (`O/DualCameraLayout.swift:28-65`, `RV:1489-1531`) | **missing** | Centre (0.77, 0.28), size 0.32 of the short side, minimum 0.18 |
| D4 | Pan and zoom of each picture | Two fingers; zoom 1 to 4 (`O/DualCameraLayout.swift:11-17,118-123`, `RV:1502-1530`) | **missing** | Centre 0.5, 0.5, zoom 1 |
| D5 | Swap | `RV:517-519` | **missing** | — |
| D6 | Stabilise, follow face and level horizon with two cameras | `O/MultiCameraCapture.swift:230-254,321-328` | **missing** | — |

The composed picture is recorded as one movie; the masks are an antialiased diagonal with
a two-pixel separator and a round inset (`O/DualCameraMasks.swift:5-40`,
`O/MultiCameraCapture.swift:272-307`); frames of the two sensors are paired when they are
within 1/60 s (`O/DualCameraLayout.swift:182-189`).

### 1.8 Live effects

All absent on Android.

| ID | Item | Original | Status | Notes |
|---|---|---|---|---|
| E1 | Effects while recording | 21 of the 24 effects (all but Night fix, Freeze frame, Step printing: `O/VideoEffects/VideoEffectCatalog.swift:60,193-371`); a panel with library, applied list and inspector (`RV:495-498,258-267`, `O/VideoEffects/LiveEffectsPanel.swift:4-321`) | **missing** | The movie is recorded clean; the effects are saved beside it as a recipe and arrive in the editor as the take's effects (`O/VideoEffects/LiveVideoEffects.swift:281-338`, `O/ProjectStore.swift:360-376`). Stored globally as `liveVideoEffects`; the button is disabled during a take |
| E2 | Crash zoom and dolly zoom buttons, tap to choose the subject | `RV:105-133`, `CC:212-233,2005-2106,3331-3355` | **missing** | One camera only |
| E3 | Frame tools: pick a colour, sample a palette, capture an empty background after a 3 s countdown, place a background | `CC:288-366` | **missing** | The only countdown in the recorder |
| E4 | Lighter processing when the phone is warm, with a note | `RV:430-434`, `O/VideoEffects/LiveVideoEffects.swift:495-498,566-571` | **missing** | — |

### 1.9 Sound

| ID | Item | Original | Android today | Status | Defaults, ranges, where stored |
|---|---|---|---|---|---|
| S1 | The microphone control | A pill in the top bar, always there, orange "No microphone" when none; opens "Recording volume" with the gain, a meter in dBFS and the microphone menu (`RV:445-470,1319-1345`) | A chip "Mic: …" when idle or paused; the name of the active input while recording (`aRS:174-179,199-215`) | **partial** | — |
| S2 | Choosing the microphone | From the pill or More settings, also during a take; the choice lasts until Stop (`RV:1332-1338,1271-1279`, `CC:2553-2572`, `O/CaptureAudio.swift:30-33`) | From the chip, when idle or paused; "Automatic" or one input; lasts until the app is closed or the input goes away; Android 12 or newer only (`aCC:270-293,325-334`) | **partial** | Android: not stored |
| S3 | Microphone priority | An ordered list, remembered, including microphones that are not connected; arrows move an entry (`RV:1280-1294`, `CC:2573-2596`, `O/CaptureAudio.swift:22-28`) | absent | **missing** | Original `microphonePriority`, `microphoneNames`, global |
| S4 | When the microphone in use goes away | Waits 3 s (0.5 s when the system reported the removal), tries the next ones in order with retries, saves an event and warns "X disconnected. Using Y." (`O/CaptureAudio.swift:41-108`, `CC:2596-2622`) | Warns that the recording comes from another input; returns to "Automatic" after the segment (`aCC:136-143,340-347`) | **partial** | — |
| S5 | Level meter | Runs whenever the recorder shows; seven bars from −54 dBFS in steps of 8, the last red; quick attack, release of 1.5 dB per buffer (about 70 dB/s); "Clipping" for 0.35 s after a peak at 0.988; "No signal" after 0.8 s without sound (`RV:913-934,1363-1367`, `CC:2664-2669,2859-2879`) | A bar while recording, linear, red above 0.95, no ballistics (`aRS:167-173`, `aCC:472-473`) | **partial** | — |
| S6 | Recording gain | −40 to +12 dB in steps of 0.5, changeable during a take, with a 20 ms ramp; "Reset to 0" (`RV:1348-1369`, `O/CaptureAudio.swift:144-185`, `CC:2830-2838`) | absent | **missing** | Original default 0, `recordingGain.<microphone>`, global |
| S7 | Sound independent of the camera | Its own engine: continuous through camera changes, always 48 kHz stereo, silence where input is missing, slow clock correction for external microphones, events `videoHold`, `audioGap`, `audioClockCorrection` (`O/LiveMicrophone.swift:11-66`, `O/CaptureAudio.swift:190-321`, `O/CaptureSegmentWriter.swift:176-180,315-336`) | The sound CameraX's recorder captures itself; no events for held picture or missing sound (`aCC:420-424`) | **partial** | See section 6 |
| S8 | When no sound arrives | Tries to restart the microphone five times; after 1 s without sound pauses the take and says why (`CC:2523-2551,2676-2681`) | Says that the take may be silent and goes on (`aCC:474-480`) | **partial** | — |
| S9 | "Protect recording audio" from call banners | `RV:1265-1270`, `CC:2504-2510` | — | **n/a** | No Android API; a call banner does not stop a recording there |
| S10 | Voice recording in the editor | A sheet: microphone menu, Record, Record more, Play, Start over; passes are joined; inserted at the playhead (`O/VoiceRecorder.swift:7-414`, `O/AudioToolsEditor.swift:20`) | absent | **missing** | 48 kHz, 16 bit, mono |

### 1.10 Orientation

| ID | Item | Original | Android today | Status | Notes |
|---|---|---|---|---|---|
| O1 | The interface turns | Portrait and both landscapes; in landscape the three buttons form a rail at the end away from the cameras, and the bars keep out of the picture when it fits (`RV:100-104,141-255,270-280`, `O/YouRecApp.swift:90-110`) | Portrait only (`app/src/main/AndroidManifest.xml:22`); the recorded orientation follows the phone and is named on the status line (`aCC:114-127`, `aRS:163`) | **missing** | Done for issue 62 (decision 5): the activity turns, the take buttons form the rail, the take is recorded as the interface stands |
| O2 | The orientation is fixed for the whole take | From the first Record to Stop, pauses included; later segments keep the first one's frame (`RV:135,350-355`, `CC:1207-1209,1364-1366,1462`, `O/CaptureSegmentWriter.swift:146-175`) | Fixed while a segment is written; free again while paused (`aCC:117`) | **differs** | A take can mix portrait and landscape segments |

### 1.11 Settings

| ID | Item | Original | Android today | Status |
|---|---|---|---|---|
| U1 | "Camera settings" panel | Four assist tiles, "Use two cameras" with the pair, "Recording color", then "More settings" (`RV:644-766,796-833`) | absent | **missing** |
| U2 | "More settings" | In order: This project; Level horizon (Max angle); Two cameras (when on); Picture (two mirror switches); Focus (lock, lens slider); Zoom (transition); Interruptions (protect audio); Microphone; Microphone priority; Recording quality; Framing (fit); About (Credits) (`RV:1216-1312`) | absent; Credits is reached elsewhere (`A/Credits.kt`) | **missing** |

Every setting of the original recorder is global (`UserDefaults`); none is stored in the
project. The complete list is in 1.15.

### 1.12 Feedback

| ID | Item | Original | Android today | Status |
|---|---|---|---|---|
| H1 | Haptics | Mark (`RV:71`), assist tiles (`RV:836`), touching a zoom preset (`RV:1119`), each half second of zoom time (`RV:1187`), focus peaking (`RV:966`), crash zoom (`CC:216`) | none | **missing** |
| H2 | Screen reader | Every control has a label, a value and often named actions (for example `RV:600-602`, `RV:1121-1126`, `RV:1170-1181`) | The record button and the level have labels, the chips have action labels; the selected zoom preset and camera are shown by colour only (`aRS:170,192-197,290-293`) | **partial** |

### 1.13 Storage, interruptions and recovery

| ID | Item | Original | Android today | Status | Notes |
|---|---|---|---|---|---|
| R1 | Free space before a segment | 75 MB for 1080p, 170 MB for 4K, above a floor of 2 % of the storage kept between 512 MB and 2 GB (`CC:1401-1403`, `O/ProjectRecovery.swift:421-423,452-464`) | 300 MB (`aCC:403-406,520`) | **differs** | On a 128 GB phone the original stops at 2.075 GB free |
| R2 | Space running out during a take | Every 5 s: pauses and saves when what is left above the floor is less than 32 MB plus 15 s of the measured rate; a 4 MB reserve file is freed first (`CC:2682-2695`, `O/ProjectRecovery.swift:299`) | CameraX ends the recording under 50 MiB free **[probe]**; the segment is saved and the take is left paused with a message (`aCC:500-505,510`) | **partial** | — |
| R3 | The camera is taken away or the capture service restarts | Pauses the take, restarts the preview, checks that frames and sound really arrive before saying ready (`CC:634-688,1119-1162`) | A failed bind shows a message and pauses; a finished recording names the reason (`aCC:230-238,509-517`) | **partial** | — |
| R4 | A call or another app takes the microphone | The take is paused (`CC:617-622`) | The take goes on silent with a warning (`aCC:477`) | **differs** | — |
| R5 | The app goes to the background | The take is paused and its file is saved inside a background task; coming back shows "Paused" (`O/ContentView.swift:363-366`, `CC:1165-1180`) | The take is finished; an insertion run ends (`aRS:108-113`, `aCC:437-439,455`) | **differs** | — |
| R6 | Heat | Only the effects and the face detector do less (`O/VideoEffects/LiveVideoEffects.swift:495-498`, `O/DualFaceDetector.swift:19,145`) | nothing | **missing** | Belongs with A2 and E4 |
| R7 | A receipt for every segment | Written before recording starts: project, take, segment, sequence, start time, where it goes in the edit (`O/ProjectRecovery.swift:231-300`, `CC:1420-1427`) | none; a recovered take gets a new id and the file's date (`A/ProjectStore.kt:430`) | **missing** | — |
| R8 | Events saved beside the take | `<take>.events.json`, rewritten at each event, read back when the take is indexed (`O/CaptureSegmentWriter.swift:116-122`, `O/ProjectStore.swift:351-359`) | In memory until the segment is saved (`aCC:72,253,430,494`) | **missing** | Marks of an interrupted take are lost |
| R9 | A take interrupted by a crash or a kill | The file is written in one-second fragments; at launch the playable part is copied or remuxed into a new file, within 64 MB per file and 256 MB in all without asking (`O/CaptureSegmentWriter.swift:215-219`, `O/RecordingSalvage.swift:123-257`, `O/ProjectStore.swift:485-566`) | Nothing written for it; the file is very likely playable to within a second **[probe]** and is then indexed at launch like any finished take (`A/ProjectStore.kt:90,427-432`) | **partial** | **[inferred]**, to be checked once (section 3) |
| R10 | A finished take the project does not list | Indexed at launch (`O/ProjectStore.swift:485-566`) | Indexed at launch (`A/ProjectStore.kt:427-432`, `C/ProjectDisk.kt:73-80`) | **matches** | — |
| R11 | A leftover that cannot be read | Moved to `Recovery/`; "Unfinished recordings" lets the person save or delete each file (`O/Project.swift:726-752`, `O/ContentView.swift:392-419,662`) | Skipped at every launch, stays in the folder, never shown (`A/ProjectStore.kt:44-56,429`) | **missing** | — |
| R12 | A note about what was recovered | A bar with "Review" (`O/ContentView.swift:86-94`) | none | **missing** | — |
| R13 | Original recordings | List, in-app review with the held-picture and silence events, share one or all, add a range to the timeline, recover into a new project (`O/OriginalRecordingsView.swift:4-200`) | A list under the project with the number of camera changes; a tap opens another app's player (`A/ProjectsScreen.kt:235-250`) | **partial** | — |

### 1.14 Asked about, in neither app

Read for on both sides and not found: exposure bias or lock, white balance, torch, a grid
or guides on the picture, a self-timer or countdown before recording (the only countdown
is E3), sounds (the original only allows haptics and system sounds during recording,
`CC:1056`), a general "phone too hot" stop. Nothing to port.

### 1.15 Where settings are stored

Original, all in `UserDefaults`, all global:

| Key | Meaning | Default |
|---|---|---|
| `captureQuality` | 1080p or 4K | 1080p |
| `captureColorMode`, `captureHDR` | SDR, HDR, Log | HDR |
| `dualStabilization` | Stabilize, one or two cameras | off |
| `levelHorizon`, `levelHorizonDegrees` | Level horizon and its angle | off, 9 |
| `dualFaceFollow` | Follow face | off |
| `mirrorFrontPreview`, `mirrorFrontRecording` | the two mirror switches | on, off |
| `fitRecordedFrame` | fit or fill | fit |
| `focusPeaking` | focus peaking | off |
| `zoomTransitionSeconds` | zoom transition time | 0 |
| `protectRecordingAudio` | interruptions | off |
| `camera.<id>` | per camera: `zoom`, `panX`, `panY`, `locked`, `lens` | 1, centre, auto |
| `lastFrontCamera`, `lastRearCamera` | for the flip button | — |
| `microphonePriority`, `microphoneNames` | order and names, also of absent microphones | — |
| `recordingGain.<microphone>` | gain in dB | 0 |
| `dualCameraLayout` | the two-camera layout | floating |
| `liveVideoEffects` | the live effects and their values | none |
| `frontLightWidth`, `frontLightTone`, `frontLightCustomColor`, `frontLightOpacity` | front light | Medium, White, rose, 1 |

Android, in the `recorder` shared preferences, all global: `camera` (`aCC:185,241`),
`zoom.<id>` (`aCC:244,365`), `zoomDuration` (`aCC:98,392`). The chosen microphone is not
stored (`aCC:109`). Only the insertion place is stored in the project, on both sides.

Recommendation: keep every new recorder setting global, in the same preferences. It is
what the original does, and it keeps `Project.kt` out of the recorder work.

### 1.16 Manifest, strings, and corrections to `docs/PARITY.md`

Manifest (`app/src/main/AndroidManifest.xml`): `CAMERA` (5), `RECORD_AUDIO` (6), `INTERNET`
for the subtitle model only (8), any camera required (4), the activity locked to portrait
(22; no longer, since issue 62 and decision 5). The original also declares motion use, for the horizon; Android needs no permission
for the gravity sensor. Nothing in this review requires a new permission.

Strings: 56 `recorder_` keys in each of `values/strings.xml:428-484` and
`values-it/strings.xml:432-488`, the same set in both, all used. "Bluetooth" and "USB" are
written in code (`aCC:167-168`), which is fine for both languages.

One thing to fix beside the plan: `AudioStats.getAudioAmplitude()` is marked
`ExperimentalAudioApi` in CameraX 1.6.2 **[probe]** and is used at `aCC:473` without the
opt-in that `aCC:64` has for the other two experimental APIs.

Rows of `docs/PARITY.md` section 3 that this review reads differently:

| PARITY row (line) | There | Here | Why |
|---|---|---|---|
| Record, pause, resume, stop (96) | DONE | T2 differs, O2 differs | The big button has the other role; a paused take can change orientation |
| Record here (98) | DONE | T11 partial | No return to the editor; background and Stop end the run |
| Status pill and clock (99) | DONE | T6 differs | Different time, different layout |
| Zoom presets (103) | DONE | Z5 differs | Other list, no 10× cap, always open |
| Zoom transition time (104) | DONE | Z9 differs, Z10 partial | Default 1 s against Instant |
| Mirror selfie (111) | MISSING | P6, P7 partial | The defaults already match; only the switches are missing |
| Mark while recording (130) | DONE | T10 partial | Time is less exact, marks are lost with an interrupted take, hidden while paused |
| Backgrounding saves the take (133) | DONE | R5 differs | Saved, but finished instead of paused |
| Permission flow (134) | DONE | T15 differs | The original requires the microphone |
| Interrupted recording (86, section 2) | MISSING, size L | R9 partial | The file is probably playable already |
| Free-space floor (88, section 2) | "CameraX only reports the failure afterwards" | R2 partial | CameraX stops by itself under 50 MiB and the segment is saved |
| Tabs disabled during a take (57, section 1) | MISSING | T9 differs | Same finding, with what happens instead |

## 2. How to do each item on Android

### 2.1 What was checked in the libraries of this build

Read in the CameraX 1.6.2 artifacts **[probe]**; these are public, not restricted:

- `Recorder.Builder`: `setQualitySelector`, `setTargetVideoEncodingBitRate`,
  `setVideoCapabilitiesSource`, `setAspectRatio`. Restricted (library group):
  `setAudioSource` (already used, `aCC:263-267`), `setMuxerFactory`,
  `setRequiredFreeStorageBytes`. There is no public choice of codec.
- `Recorder.getVideoCapabilities(CameraInfo)`: `getSupportedDynamicRanges()`,
  `getSupportedQualities(DynamicRange)`, `isQualitySupported(…)`, `isStabilizationSupported()`.
- `VideoCapture.Builder`: `setMirrorMode`, `setDynamicRange`, `setTargetFrameRate`,
  `setVideoStabilizationEnabled`. Defaults: frame rate 30 to 30, SDR, mirror off.
- `Preview.Builder`: `setMirrorMode`, `setDynamicRange`, `setTargetFrameRate`,
  `setPreviewStabilizationEnabled`; default mirror "front only".
  `Preview.getPreviewCapabilities(CameraInfo).isStabilizationSupported()`.
- `CameraControl`: `startFocusAndMetering`, `cancelFocusAndMetering`, `setZoomRatio`,
  `setExposureCompensationIndex`, `enableTorch`.
- `CameraInfo`: `isFocusMeteringSupported`, `getSupportedFrameRateRanges`,
  `getIntrinsicZoomRatio`, `isLogicalMultiCameraSupported`, `getPhysicalCameraInfos`,
  `isSessionConfigSupported(SessionConfig)`; `SessionConfig.Builder` with
  `setRequiredFeatureGroup(GroupableFeature.HDR_HLG10, PREVIEW_STABILIZATION, FPS_60)` and
  `addEffect`; `ProcessCameraProvider.bindToLifecycle(owner, selector, SessionConfig)`.
- Two cameras: `ProcessCameraProvider.getAvailableConcurrentCameraInfos()`,
  `bindToLifecycle(List<ConcurrentCamera.SingleCameraConfig>)`, `CompositionSettings`
  (alpha, offset, scale).
- Effects: `CameraEffect` with targets `PREVIEW`, `VIDEO_CAPTURE`, `IMAGE_CAPTURE` and a
  `SurfaceProcessor` (`onInputSurface`, `onOutputSurface`); `UseCaseGroup.Builder.addEffect`.
- Camera2 interop: `Camera2CameraControl.setCaptureRequestOptions` and
  `clearCaptureRequestOptions`, `Camera2CameraInfo.getCameraCharacteristic`,
  `Camera2Interop.Extender.setCaptureRequestOption` and `setSessionCaptureCallback`.
- `PreviewView`: `getMeteringPointFactory`, `getBitmap`, `setScaleType`,
  `getPreviewStreamState`.
- `Recording`: `pause`, `resume`, `mute`, `stop`.
- Inside `Recorder`: free space below 52,428,800 bytes ends the recording; MP4 is written
  by `Media3MuxerImpl`, whose `isInterruptionResilient()` is true; Media3's `Mp4Writer`
  updates the `moov` box every 1,000,000 µs.

Android API levels below are from the platform documentation as remembered; the ones that
gate a control are named so they can be checked. `minSdk` is 26.

### 2.2 Take controls, zoom

| ID | How | Gate | API level | Exact? | Core |
|---|---|---|---|---|---|
| T2 | Compose only: centre button stops, a round Pause button on the right, Mark on the left | — | — | exact | — |
| T6 | Show `project.edit.duration` plus the running segment in one pill | — | — | exact | — |
| T7 | A `border` of 2 dp on the root box, red or yellow by phase | — | — | exact | — |
| T9 | Disable the tabs while the phase is Recording or Saving (`A/MainActivity.kt:65-68`) | — | — | exact in effect | — |
| T10 | Time = duration of the last status report plus the real time since it; write the events file at each event (R8); show the button while paused, disabled; haptic | — | — | exact to a frame | `CaptureEvent` exists (`C/Project.kt:28-38`) |
| T11 | After the take ends with an insertion active, return to the editor tab; keep the run across a pause (R5) | — | — | exact | exists (`C/Project.kt:383-420`, tested in `RecordingInsertionTest.kt`) |
| T13 | `WindowInsetsControllerCompat.hide(statusBars())` while the recorder shows | — | 26 through `androidx.core` | exact | — |
| Z2 | Remember the last front and rear camera ids in the preferences; one button | at least one camera on each side | — | exact | — |
| Z3 | Cover the preview with `previewView.bitmap` until the stream state is `STREAMING` again | — | — | close; the cover is a still, as in the original | — |
| Z5, Z9 | Constants; see decisions 2 and 3 | — | — | exact | `C/ZoomTransition.kt:40-43`, `aCC:98` |
| Z7 | When the list has a rear camera with `intrinsicZoomRatio < 0.9` and a rear main one, the 1× preset changes camera | that list | — | exact where it applies | — |
| Z10 | `draggable(Orientation.Vertical)` on the time chip, 14 dp per half second, tick on each step; a tap shows the hint for 2.5 s | — | — | exact | `boundedDuration` exists |
| Z12 | A two-pointer `pointerInput` that keeps the span at the start of the gesture; zoom = start × exp(s·t²(3−2t)) with s = ln(span/start span) and t = clamp((abs(s) − 0.022)/0.033, 0, 1) (`O/RecorderZoomViewport.swift:151-153`); a badge at 27 % of the height; with a time set, call `zoomTo` on release | the camera has a zoom range | — | mapping exact; the composed move is shown by the badge only, not by scaling the picture | new `PinchZoom` |
| Z13 | See 2.5 | | | | |

### 2.3 Picture format

| ID | How | Gate | API level | Exact? |
|---|---|---|---|---|
| P1 | `QualitySelector.from(Quality.UHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))`; rebuild the `Recorder` and `VideoCapture` and bind again when idle, as `useAudioSource` already does (`aCC:351-357`) | `Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)` contains `UHD`; mark the other cameras "1080p only" | 26 | exact |
| P2 | `VideoCapture.Builder.setTargetFrameRate(Range(30, 30))`, stating what is now a default | `info.supportedFrameRateRanges` contains 30 to 30 | 26 | exact where the range exists |
| P3 | `VideoCapture.Builder.setDynamicRange(DynamicRange.HLG_10_BIT)`; the preview follows | `getSupportedDynamicRanges()` contains `HLG_10_BIT`; with stabilisation or 4K ask `info.isSessionConfigSupported(…)` | 33 in practice (10-bit camera output) | exact as a format; the port exports SDR only (`A/Exporter.kt:150-151`), so the gain is seen in the editor preview, not in the exported movie |
| P4 | Not possible. Offer SDR and HDR only | — | — | n/a |
| P5 | `Recorder.Builder.setTargetVideoEncodingBitRate(width × height × 6)`; CameraX clamps to what the encoder accepts. Codec: CameraX chooses (H.264 for SDR, HEVC for 10 bit, to confirm). Sound format: from the device profile | — | 26 | bit rate exact, the rest approximate |
| P6 | Rebuild `Preview` with `MIRROR_MODE_OFF` or `MIRROR_MODE_ON_FRONT_ONLY` | — | 26 | exact, but changing it during a take interrupts the picture; allow it when idle or paused |
| P7 | `VideoCapture.Builder.setMirrorMode(MIRROR_MODE_ON_FRONT_ONLY)` | — | 26 | exact |
| P8 | `previewView.scaleType = FILL_CENTER` or `FIT_CENTER` | — | 26 | exact |
| P9 | Prefer `Preview.Builder.setPreviewStabilizationEnabled(true)` (preview and recording are stabilised alike, so the preview shows the recorded crop); else `VideoCapture.Builder.setVideoStabilizationEnabled(true)` (recording only) | `Preview.getPreviewCapabilities(info).isStabilizationSupported()`; `Recorder.getVideoCapabilities(info).isStabilizationSupported()` | preview stabilisation 33; video stabilisation 26 | approximate: the phone chooses the strength; no "low latency" mode to ask for |

All of P1 to P9 change the use cases, so they follow the rule the microphone already
follows: applied between segments, never under a running one (`aCC:349-357`).

### 2.4 Focus

| ID | How | Gate | API level | Exact? | Core |
|---|---|---|---|---|---|
| F1 | `previewView.meteringPointFactory.createPoint(x, y)`, `FocusMeteringAction.Builder(point, FLAG_AF)`, `cameraControl.startFocusAndMetering(action)`; draw the box until the future completes, 4 s at most | `info.isFocusMeteringSupported(action)`; otherwise the "fixed focus" hint | 26 | approximate: CameraX focuses once and returns to the default region after 5 s; the original keeps continuous focus on the point. Exact would be `CONTROL_AF_REGIONS` through Camera2 interop | — |
| F2 | Camera2 interop: `CONTROL_AF_MODE = OFF` with `LENS_FOCUS_DISTANCE` set to the last reported distance; unlock with `clearCaptureRequestOptions()`. Read the current distance from `TotalCaptureResult.LENS_FOCUS_DISTANCE` in a session capture callback | `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` and `CONTROL_AF_AVAILABLE_MODES` contains `OFF`; else show "Fixed" | 26 | exact | — |
| F3 | The same request with distance = minimum focus distance × (1 − position); swipe mapping min(dy, 40)/320 + max(0, dy − 40)/160 (`RV:998-999`) | as F2 | 26 | approximate: the original's 0 to 1 scale is not in dioptres | swipe mapping |
| F4 | An `ImageAnalysis` use case (latest frame only, about 640 px, luma plane), an edge threshold, a red mask drawn over the preview | binding three use cases can fail on some cameras: try, and hide the control when it does | 26 | approximate: lower resolution, a frame or two late; preview only, as in the original | edge mask on a byte array |

While focus is locked, tap to focus is off, as in the original (`CC:2443`).

### 2.5 Framing: pan, follow face, level horizon

Three features need to move or turn the recorded picture: Z13, A2, A1. Two ways, of
different cost.

**Sensor crop (pan and follow, approximate).** `CaptureRequest.SCALER_CROP_REGION` through
`Camera2CameraControl.setCaptureRequestOptions`: the phone crops a window of the sensor,
for preview and recording together, with no drawing code and in any colour mode. The
controller then owns zoom and pan as one rectangle: width = sensor width ÷ zoom, centre
clamped so the window stays on the sensor (`O/VideoEffects/CaptureZoomHistory.swift:27-31`).
Limits: only on one physical lens (no lens change while panned, which is also what the
original does), a few frames of delay, and some phones round the window, which can show
as small steps in slow moves; the original has a shader exactly because of that
(`O/CameraCrop.metal:4-5`). How it combines with CameraX's own zoom control, which uses the
zoom ratio from Android 11 and the crop region before, is to confirm. API 26.

**Drawing every frame (exact, needed for the horizon).** A `CameraEffect` for `PREVIEW`
plus `VIDEO_CAPTURE` whose `SurfaceProcessor` draws the camera texture with a matrix that
changes per frame: the crop of Z13 and A2, and the rotation and fixed magnification of A1
(`O/HorizonLevel.swift:19-30`). The preview then shows exactly what is recorded. Whether
such an effect can pass 10-bit frames is to confirm; plan it for SDR first.

| ID | Detection or sensor | Gate | API level | Exact? | Core (all pure) |
|---|---|---|---|---|---|
| Z13 | The pinch gesture also reports the travel of the midpoint | zoom above the lens minimum ("Zoom in to pan") | 26 | approximate with the sensor crop, exact with drawing | `CameraFraming` (`O/VideoEffects/CaptureZoomHistory.swift:5-32`); centre interpolation of `ZoomTransition` (`O/ZoomTransition.swift:64-69`) |
| A2 | The camera's own face detection: `STATISTICS_FACE_DETECT_MODE` and `STATISTICS_FACES` through Camera2 interop. No library, no model, no network | `STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES` has more than `OFF` | 26 | approximate: other detector, boxes about 10 times a second or more | face choice and hold (`O/DualCameraLayout.swift:128-156`), smoothing with time constant 0.22 s, dead zone 4.5 %, speed limit 1.5 widths per second (`:158-180`), the 1.2× reserve at 0.6 per second (`O/DualFaceDetector.swift:129-141`) |
| A1 | `SensorManager`, `TYPE_GRAVITY` at 60 Hz, kept for 1.5 s and read at the time of each frame; no permission | the sensor exists; rear camera | 26 | approximate in timing: frame and sensor clocks agree only when the camera reports the `REALTIME` timestamp source | roll from gravity folded to the nearest quarter turn, soft limit, 35° to 45° taper, time constants 0.075 s, 0.22 s stabilised (`O/HorizonLevel.swift:45-106`); the line finder (`:110-188`); crop scale cos a + max(w/h, h/w)·sin a, 1.27 at 9° for 16:9 (`:19-23`) |

Other detectors for A2, if the camera's is missing or poor: ML Kit face detection with a
bundled model works offline but adds several megabytes and brings Google's usage reporting,
which the privacy policy excludes ("no analytics", `PRIVACY.md`); the Play Services variant
downloads its model and needs Play Services. A small bundled model run by a library the app
already ships would cost about a third of a megabyte; whether the speech module's runtime
can be used for that is to confirm. Recommendation in decision 9.

### 2.6 Front light, haptics, interface

| ID | How | Gate | API level | Exact? |
|---|---|---|---|---|
| A3 | A Compose `Canvas` with an even-odd path (screen minus a rounded rectangle), above the preview and below the controls; `window.attributes.screenBrightness = 1f` while it is on and the activity is resumed, back to "none" after; control plates turn opaque (`RV:67`) | — | 26 | exact, except the custom colour: Android has no system colour picker, so a hue slider |
| A4 | A long click on each tile opens a dialog with the text | — | — | exact |
| H1 | `view.performHapticFeedback(…)`: `CLOCK_TICK` for steps and presets, `CONFIRM` for Mark and toggles | follows the system setting | `CLOCK_TICK` since 21; `CONFIRM` since 30, `VIRTUAL_KEY` before that | approximate (other motors, other strengths); no permission |
| H2 | `semantics { selected = … }` on the chips, state descriptions on the record button | — | — | exact |
| O1 | Either turn the controls in place from the orientation listener the controller already has (`aCC:114-127`), or let the activity turn (see decision 5). Decided: the activity turns, without being made again, and is held with `SCREEN_ORIENTATION_LOCKED` from Record to Stop | — | 26 | exact, except that the tabs and the status bar of the port are laid out for landscape (`docs/PARITY.md`, section 1) |
| O2 | Add `Phase.Paused` to the guard at `aCC:117` | — | — | exact |
| U1, U2 | A bottom sheet for the quick panel and a full-screen list for More settings; values in a small class over the `recorder` preferences | each row by its own gate | — | exact in content |

### 2.7 Sound

| ID | How | Gate | API level | Exact? | Core |
|---|---|---|---|---|---|
| S1, S5 | While idle: an `AudioRecord` with the source the recorder will use, read for its peak only and released before a segment starts. While recording: `AudioStats.audioAmplitude` (opt in). Both go through one meter: dBFS = 20·log10(peak), release 1.5 dB per 21 ms, seven bars, "Clipping" and "No signal" by the original's numbers | microphone permission | 26 | approximate while recording (CameraX reports less often than every buffer) | new `LevelMeter` |
| S2 | Allow the choice during a take only with an own sound path (S6); keep today's rule until then | Android 12 for Bluetooth routing, as today | 31 | partial by design | — |
| S3, S4 | Port the route logic as it is: the ordered list, the 3 s and 0.5 s waits, the retry delays 0.25, 0.5, 1, 1.5 s, refused fallbacks (`O/CaptureAudio.swift:6-109`). Identify a microphone by type and product name, because Android's device id changes at every connection | `availableCommunicationDevices` | 31 | approximate: two microphones of the same model are one entry | new `MicrophoneRoute`; tests in `T/CoreTests.swift:557-595` |
| S6 | Not possible on CameraX's recorder: it takes no sound from the app. Needs the app's own writer (2.9) | — | — | — | new `RecordingGain` (`O/CaptureAudio.swift:144-185`) |
| S7 | First measure what CameraX does during a camera change (section 6). Exact parity needs the own writer | — | — | — | the normaliser (`O/CaptureAudio.swift:190-321`) only with the own writer |
| S8 | When the audio state is not active for more than a second, call `pause()` and show the reason | — | 26 | exact in effect | — |
| S10 | A sheet with `AudioRecord` to a WAV file per pass, the passes joined, then the existing import of a sound file at the playhead (`A/ProjectStore.kt:140`) | microphone permission | 26 | exact | — |

### 2.8 Storage, interruptions and recovery

| ID | How | API level | Exact? | Core |
|---|---|---|---|---|
| R1 | One function: floor = clamp(total/50, 512 MB, 2 GB); need 75 MB or 170 MB above it. Decision 7 says whether to copy the floor | 26 | exact if copied | new `RecordingSpace` |
| R2 | Check every 5 s while recording and call `pause()` when free − floor < 32 MB + 15 s of the measured rate; CameraX's own 50 MiB stop remains behind it | 26 | exact | same |
| R3 | Observe `cameraInfo.cameraState`; on an error that is recoverable bind again when the screen is in front | 26 | approximate | — |
| R4, R5 | `pause()` instead of `finishTake()` at `aRS:111`, and on the "silenced" audio state | 26 | exact in effect | — |
| R6 | `PowerManager.getCurrentThermalStatus()` and its listener; from "severe" on, halve the face detection rate as the original does, and say so once | 29; nothing below | approximate | — |
| R7 | A small JSON file written before `pending.start`: project, take id, segment id, sequence, start time, insertion place | 26 | exact | the record in `core`, the file in `ProjectDisk` |
| R8 | Rewrite `<name>.events.json` at each event; read it in `indexFinishedTakes` and in `finalize` | 26 | exact | `CaptureEvent.recovered` exists (`C/Project.kt:35-36`) |
| R9 | First the check in section 3. If the file is playable: nothing more than R7 and R8. If not: the original's prefix scan (`O/RecordingSalvage.swift:123-152`) does not apply to this file layout, and the choice is between a muxer of the app's own behind a restricted CameraX call and the own writer | 26 | — | — |
| R11, R12 | Move a file that cannot be read to `Recovery/` once, list such files in a sheet with "Save a copy" and "Delete", and show one line on the project card | 26 | exact | `ProjectDisk` |
| R13 | An in-app player sheet with the events of the take, the system share sheet, and the existing "unused takes" insertion for a whole take | 26 | partial until ranges are added | `UnusedTakes` exists |

### 2.9 The app's own writer, two cameras, live effects

**Own writer.** Four items cannot be done on CameraX's `Recorder`: S6 (gain), exact S7
(sound through camera changes with the picture held), S2 during a take, and the full D1 to
D6. They need what the original has in `CaptureSegmentWriter`: frames into a `MediaCodec`
encoder, sound from `AudioRecord` through the gain into an AAC encoder, a Media3 muxer,
and the events file. It is the largest and riskiest step: CameraX's recorder carries
workarounds for the encoders and clocks of many phones, and an own writer starts without
them. `VideoOutput`, the interface CameraX offers for a custom output, is public only in
its one-method form in 1.6.2 **[probe]**; the rest is restricted.

**Two cameras.** Gate: `getAvailableConcurrentCameraInfos()` not empty (Android 11, and the
phone must declare concurrent cameras; usually one front and back pair, at reduced
resolution). Two levels:

- through CameraX: `SingleCameraConfig` for each camera with `CompositionSettings`. It
  places two whole frames by offset and scale, so it gives the floating rectangle and
  nothing else of D2 to D4: no crop to fill a half, no circle, no diagonal, no pan or zoom
  inside a picture. Swap is a new bind. Medium size.
- the original's compositor: two previews into textures, the layout drawn by the app
  (`DualCameraLayout`, the masks), and the own writer. Large.

The layout, the face framing and the frame pairing are pure and tested in the original
(`T/CoreTests.swift:481-554`, `T/DualCameraMaskTests.swift`) and can be ported to `core`
before either level.

**Live effects.** The original records clean footage and a recipe. The same design on
Android is a `CameraEffect` on the preview only, running the editor's effect shaders, and a
recipe file read when the take is indexed. It depends on the editor's video effects, none
of which exist yet (`docs/PARITY.md` section 10), and it touches the editor files.

### 2.10 Core: what exists and what to add

Exists and is tested: `ZoomTransition` (curve, duration bounds, presets, labels),
`CaptureEvent.recovered`, the insertion of takes (`Project.beginRecordingInsertion`,
`addRecording`), `ProjectDisk.unindexedRecordings`, `InsertionSites`.

To add, each a new file with a host test taken from the original's tests:

| New in `core` | From | Original test | Needed by |
|---|---|---|---|
| `PinchZoom` | `O/RecorderZoomViewport.swift:151-153` | UI tests only | Z12 |
| `LevelMeter` | `CC:2664-2669,2859-2879`, `RV:925-931` | — | S5 |
| `MicrophoneRoute` | `O/CaptureAudio.swift:6-109` | `T/CoreTests.swift:557-595` | S3, S4 |
| `RecordingSpace` | `O/ProjectRecovery.swift:421-423`, `CC:2692` | — | R1, R2 |
| `CameraFraming`, centre moves in `ZoomTransition` | `O/VideoEffects/CaptureZoomHistory.swift:5-32`, `O/ZoomTransition.swift:64-69` | `T/SingleCameraFollowTests.swift:144-147,240`, `T/ZoomTransitionTests.swift:42` | Z13, A2 |
| `FaceTarget`, `FaceFraming` | `O/DualCameraLayout.swift:128-180` | `T/CoreTests.swift:485-507` | A2, D6 |
| `HorizonFraming`, `HorizonEstimate`, `HorizonLine` | `O/HorizonLevel.swift:8-188` | `T/SingleCameraFollowTests.swift:259-336` | A1 |
| `RecordingGain` | `O/CaptureAudio.swift:144-185` | `T/CoreTests.swift:596-611` | S6 |
| `DualCameraLayout`, `DualFramePairing` | `O/DualCameraLayout.swift:4-124,182-189` | `T/CoreTests.swift:481-554`, `T/DualCameraMaskTests.swift` | D1 to D6 |

## 3. Verification

"Emulator" is what the Android emulator's virtual camera is expected to show; the first
step of the plan prints what it really offers. "Phone" is the Pixel 9a. Items that match
are not listed.

| ID | Emulator | Phone needed | Two-minute check on the phone |
|---|---|---|---|
| T2, T6, T7, T9, T13 | yes, all of it | no | — |
| T10 | yes: mark at a spoken count, see the mark in the editor | no | — |
| T11, R5 | yes: start a run from the editor, press Home during a take, come back | no | — |
| Z2, Z5, Z9, Z10 | yes | no | — |
| Z3 | yes for the cover and for what the file contains (front and back virtual cameras) | for real cameras | Count aloud, change camera at "five", play the take: is a number missing, does the picture hold? |
| Z7 | no | only on a phone that lists the ultrawide separately | See whether the report lists it; the Pixel probably does not |
| Z12 | yes, with the emulator's two-finger gesture | for the feel | Pinch slowly: nothing for the first millimetres, then smooth; badge shows the value |
| P1 | the gate only (4K greyed) | yes | Record 5 s in 4K, open the take's details in a player: 3840 × 2160 |
| P2 | yes: frame count of a 10 s take | for dark scenes | Record 10 s with the lens covered; the file still has about 300 frames |
| P3 | the gate only | yes | Record a window against a dark room in HDR and SDR; the HDR one keeps the sky in the editor preview |
| P5 | yes: bit rate of the file | no | — |
| P6, P7 | yes on the front virtual camera | to be sure | Front camera, hold up a page: readable in the saved take, mirrored in the preview; flip both switches and repeat |
| P8 | yes | no | — |
| P9 | no | yes | Walk ten steps with it off and on; compare; check the preview shows the same framing as the take |
| F1 | the box and the call; no visible focus | yes | Tap a near object, then a far one: each becomes sharp, the box goes away |
| F2, F3 | no | yes | Lock, move the phone toward a near object: it stays blurred; swipe on MF: focus travels |
| F4 | yes: the virtual scene has edges | for real optics | Hold the focus button: red on what is sharp |
| A1 | the maths in host tests; the picture partly, by turning the virtual phone | yes | Tilt the phone 5° while recording: the take stays level and the crop does not breathe |
| A2 | host tests; the detector depends on the report | yes | Step from side to side at arm's length: the picture follows, holds when the face leaves |
| A3 | the look | for the light | In a dim room, front camera: the face is lit; leaving the recorder restores the brightness |
| D1 to D6 | host tests for the layout | yes | Two pictures appear, the take contains both |
| H1 | no | yes | Mark and presets tick; record while tapping and listen for the motor in the take |
| O1, O2 | yes: turn the emulator | no | — |
| U1, U2 | yes | no | — |
| S1, S5 | yes with the host microphone | for levels | Clap near the phone: "Clipping"; cover the microphone: bars fall |
| S2 to S4 | no | yes, with a headset | Record on a headset, switch it off during the take: the warning names both, the take continues after the wait |
| S6, S7 | host tests for the gain | yes | — |
| S8 | no | yes | Start another app that records, come back |
| S10 | yes | no | — |
| R1, R2 | host tests | no | — |
| R4 | no | yes | Call the phone during a take and answer |
| R9 | **yes: record 20 s and, while it records, run `adb shell am force-stop app.takes`; open the app again.** Leaving the app first would finish the take normally (`aRS:111`) and test nothing | no | The same over USB, once, to see a real encoder |
| R7, R8, R11, R12 | yes | no | — |
| R13 | yes | no | — |
| R3 | no | yes | Covered by the R5 check; a camera taken away while the app stays in front cannot be staged simply |
| R6 | no | yes | Not practical in two minutes |
| E1 to E4 | no | yes | After the editor's effects exist |

## 4. Work plan

Order: first what every user meets, then what needs the phone least, then the pieces that
depend on others. Size: S hours, M about a day, L several days. "Editor files" are
`EditorScreen.kt`, `Exporter.kt`, `Titles.kt`, `TimelineEditing.kt`, `Project.kt`.

| Step | What | Items | Files touched | New files | Size | Without a phone | Phone | Editor files |
|---|---|---|---|---|---|---|---|---|
| 0 | Camera report: one screen that lists what each camera offers (qualities per colour mode, stabilisation, frame rates, focus modes and nearest distance, face detection, concurrent pairs, zoom range, timestamp source), with "Copy" | gates of all later steps | `A/Credits.kt` (one row) | `A/CameraReport.kt` | S | build, run on the emulator | 2 min: open, copy, send | none |
| 1 | Core maths for steps 2 to 9 | `PinchZoom`, `LevelMeter`, `MicrophoneRoute`, `RecordingSpace` | — | four files in `core`, four tests | M | all of it (`./gradlew :core:test`) | no | none |
| 2 | Take behaviour: orientation fixed until Stop; background pauses; tabs off during a take; return to the editor after an inserted take; mark time, mark while paused; events file; space rule; red and yellow frame; zoom default | O2, R5, R4, T9, T11, T10, R8, R1, R2, T7, Z9 | `aCC`, `aRS`, `A/MainActivity.kt`, `A/ProjectStore.kt` | — | M | all of it on the emulator | no | none |
| 3 | Stop on the big button, Pause beside it, one clock pill, full screen | T2, T6, T13 | `aRS`, store screenshots | — | S | all of it | no | none |
| 4 | Settings: the store, the quick panel, More settings with the rows that need no camera work (This project, Zoom transition, Framing, Microphone, About) | U1, U2, P8, A4 | `aRS`, `aCC` | `A/RecorderSettings.kt`, `A/RecorderSettingsSheet.kt` | M | all of it | no | none |
| 5 | Picture format: quality, frame rate, bit rate, the two mirror switches, stabilisation, HDR; one "rebuild and bind" for all | P1, P2, P3, P5, P6, P7, P9 | `aCC`, the sheet | — | M | gates, mirror, bit rate | 8 min | none |
| 6 | Focus: tap, box, lock, manual, hints, memory per camera | F1, F2, F3 | `aCC`, `aRS`, the sheet | `A/CameraFocus.kt` | M | the box and the states | 5 min | none |
| 7 | Zoom: pinch mapping and badge, timed pinch, time by dragging, flip button, 1× Main, presets, cover during a camera change | Z12, Z10, Z2, Z7, Z5, Z3 | `aRS`, `aCC`, `C/ZoomTransition.kt` | — | M | nearly all | 3 min for the feel | none |
| 8 | Front light, haptics, screen reader states | A3, H1, H2 | `aRS`, the sheet | `A/FrontLight.kt` | S to M | the look | 3 min | none |
| 9 | Sound on CameraX: meter with ballistics, level when idle, the pill and its panel, priority list and fallback, pause when sound stops | S1, S5, S3, S4, S8 | `aCC`, `aRS`, the sheet | `A/MicrophoneMeter.kt` | M to L | meter with the host microphone | 6 min with a headset | none |
| 10 | Recovery: the kill check, receipts, leftovers to `Recovery/`, "Unfinished recordings", the note | R9, R7, R11, R12 | `aCC`, `A/ProjectStore.kt`, `A/ProjectsScreen.kt`, `C/ProjectDisk.kt` | one small file in `core` | M | all of it | no | none |
| 11 | Controls follow the phone | O1 (first form) | `aRS` | — | M | all of it | no | none |
| 12 | Focus peaking | F4 | `aCC`, `aRS` | `A/FocusPeaking.kt` | M | yes, on the virtual scene | 2 min | none |
| 13 | Pan and follow face on the sensor crop; core framing | Z13, A2, R6 | `aCC`, `aRS` | `A/CameraFramingControl.kt`, `CameraFraming`, `FaceTarget`, `FaceFraming` in `core` | M to L | core tests | 6 min | none |
| 14 | Level horizon by drawing each frame; pan and follow move onto it | A1, exact Z13 and A2 | `aCC`, the sheet | `A/FramingEffect.kt`, `A/GravitySource.kt`, horizon files in `core` | L | core tests | 6 min | none |
| 15 | Two cameras through CameraX: floating rectangle, swap; core layout | D1, part of D2, D5 | `aCC`, `aRS`, the sheet | `DualCameraLayout` in `core` | M | core tests | 5 min | none |
| 16 | Own writer: gain, sound through camera changes, choice of microphone during a take; then the full two-camera layouts | S6, S7, S2, D2 to D6 | `aCC` | `A/SegmentWriter.kt`, `A/DualCompositor.kt`, `RecordingGain` in `core` | L, high risk | core tests | several sessions | none |
| 17 | Original recordings: in-app review, share, a whole take back to the timeline | R13 | `A/ProjectsScreen.kt` | `A/OriginalRecordings.kt` | M | all of it | no | none for the first form; ranges need `TimelineEditing.kt` |
| 18 | Voice recording in the editor | S10 | `A/EditorScreen.kt` | `A/VoiceRecorder.kt` | M | all of it | 2 min | **yes** |
| 19 | Live effects | E1 to E4 | editor and export | several | L | — | yes | **yes**, after the editor's effects |

Steps 0 to 16 touch none of the five editor files, so a second agent can work on the
editor while they are done. Inside the recorder, steps 5, 6, 7 and 9 all edit `aCC` and
`aRS` and are better done one after another; steps 1, 10 and the `core` parts of 13 to 16
can run beside any of them.

Files where the recorder work and the editor work will meet:

- `app/src/main/res/values/strings.xml` and `values-it/strings.xml`: every step adds
  strings, and both agents append near the end. Moving the 56 recorder strings to a file of
  their own (`strings_recorder.xml` in both folders) in step 2 removes this conflict.
- `A/MainActivity.kt`: the tab guard and the return to the editor (step 2).
- `A/ProjectStore.kt` and `C/ProjectDisk.kt`: events file, receipts, leftovers (steps 2
  and 10). Small, but the editor agent uses the same class.
- `A/ProjectsScreen.kt`: steps 10 and 17.
- `A/Credits.kt`: step 0.
- `app/src/main/AndroidManifest.xml`: only if decision 5 chooses a turning activity.
- `C/Project.kt`: not touched if settings stay global and receipts stay beside the takes.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`: not touched by steps 0 to 15. Step 16
  would declare `media3-muxer`, which is already in the APK through CameraX **[probe]**.
- `README.md` ("What it does", "Not ported yet"), `docs/PARITY.md` section 3, and the
  recorder screenshot of the store listing, which shows the buttons step 3 moves.

Phone sessions, to fit short windows: one after steps 0 to 5 (report, 4K, HDR,
stabilisation, mirror: about ten minutes), one after steps 6 to 9 (focus, pinch feel,
light, haptics, headset: about fifteen), one after each of 13, 14 and 15.

## 5. Risks and decisions for the owner

1. **Which button stops?** The original stops with the big button and pauses with the small
   one; the port does the opposite. Recommended: follow the original. It changes a habit of
   current users and the first store screenshot.
2. **Default zoom time.** Instant in the original, one second here. Recommended: Instant.
3. **Zoom presets.** The original shows 0.5, 1, 2, 3, 5, 6 and never goes past 10×; the
   port shows the lens minimum, 1, 2, 3, 5, 10 with no cap. Recommended: the lens minimum
   (it is 0.5 on most phones and true on the others), 1, 2, 3, 5, 6, capped at 10×, behind
   a zoom button as in the original once step 7 is done.
4. **Background and calls.** Pause the take, as the original, instead of finishing it.
   Recommended: yes; it also keeps a "Record here" run alive.
5. **The interface in landscape.** Turning only the controls keeps the activity in
   portrait and costs about a day. A really turning interface needs landscape layouts for
   the recorder and the editor and care that the camera is not rebuilt during a take.
   Recommended: turn the controls now; decide on the full version with the editor's
   landscape layout (`docs/PARITY.md:58`).
   **Decided (issue 62): the full version.** The controls turned in place first; then the
   maintainer approved the turning interface. The activity turns with the phone, except
   upside down, as the original's, and handles the change itself (`configChanges`), so
   nothing is made again: the camera, a take, the players and a tool's draft go on. The
   recorder holds the activity as it stands from Record to Stop and records the take the
   way the interface stands; the editor has the original's landscape layout.
6. **HDR by default?** The original records HDR where it can. The port exports SDR only,
   so HDR takes would be tone-mapped at export and cost more storage for little gain today.
   Recommended: offer HDR where the phone has it, off by default, until export keeps HDR.
   Log cannot be offered on Android.
7. **How much free space to demand.** The original keeps 2 % of the storage free, up to
   2 GB, before it records at all; the port asks for 300 MB. Recommended: keep a flat
   figure (300 MB for 1080p, 600 MB for 4K) and add the pause when space runs low during a
   take. The original's floor answers an iPhone problem and would refuse to record on
   Android phones that are simply fairly full.
8. **A level meter before recording** keeps the microphone open while the recorder shows,
   so Android's green indicator stays on, as on the iPhone. The privacy policy already says
   the microphone is used "while you are using the recorder". Recommended: accept.
9. **Face detection.** Recommended: the camera's own detection, which needs no library, no
   model and no network; decide after the camera report of the Pixel says whether it is
   there. Not recommended: ML Kit, which would add megabytes and Google's usage reporting
   to an app that promises none.
10. **Recording gain and the own writer.** Gain, unbroken sound through camera changes and
    the full two-camera layouts all need the app to write the movie itself. That is the one
    step that can make recording worse on some phones. Recommended: do steps 0 to 15 first,
    measure what CameraX does during a camera change, and decide then. Not recommended as
    a shortcut: storing the gain as the clip's volume in the editor, which touches the
    project format and is not what the original does.
11. **Two cameras.** The CameraX form gives the floating rectangle only. Recommended: ship
    that form if the phone report shows a pair, and say plainly that the other layouts
    wait for decision 10.
12. **"Protect recording audio"** has no Android counterpart. Recommended: leave it out
    rather than show a switch that does nothing.
13. **The microphone as a requirement.** The original will not record without it; the port
    records a silent take and says so. Recommended: keep the port's behaviour.
14. **Restricted CameraX calls.** The port already uses one (`setAudioSource`, `aCC:263`).
    Recovery might tempt a second (`setMuxerFactory`). Recommended: do not add one unless
    the kill check fails.
15. **APK size and permissions.** Steps 0 to 15 add no library and no permission. The
    only candidates for either are a face detection library (decision 9) and a turning
    activity's manifest change (decision 5).

## 6. Not determined

- What a take contains across a camera change on Android: whether the sound continues
  while no camera is bound, whether the picture is held or the time is cut out. It decides
  how far S7 and Z3 are from the original. One recording on the emulator answers it.
- Whether a killed take really opens after CameraX 1.6.2 with Media3 1.11.1, and how much
  of its end is missing (R9). The libraries say yes; nobody has watched it.
- Whether CameraX holds 30 frames per second in a dark scene without `setTargetFrameRate`
  (P2).
- What the Pixel 9a offers: 4K on each camera, HLG, both kinds of stabilisation and their
  combinations with 4K and HDR, manual focus distance, face detection modes, a concurrent
  pair. Step 0 answers all of it in two minutes.
- What the emulator's cameras offer for the same list; assumed here from general knowledge.
- Whether a custom `CameraEffect` can pass 10-bit frames, and whether one can be used with
  two cameras.
- How `SCALER_CROP_REGION` set through Camera2 interop combines with CameraX's zoom ratio.
- Whether haptics are heard in the recording on the Pixel.
- Which codec and sound format CameraX picks per quality on the Pixel (P5).
- The accessibility of the original was read, not compared control by control with
  TalkBack.

## Probes

Throwaway, not committed. The class files of `camera-core`, `camera-video`,
`camera-camera2`, `camera-lifecycle`, `camera-view` 1.6.2 and `media3-muxer` 1.11.1, the
versions this build resolves, were listed and disassembled with `javap`:

- public and restricted members of the classes named in 2.1, by their annotations;
- `VideoCapture$Defaults`: `DEFAULT_FPS_RANGE` is built from 30 and 30,
  `DEFAULT_DYNAMIC_RANGE` is `SDR`; `VideoCapture.getMirrorMode()` returns 0 (off) when
  nothing is set; `Preview$Defaults.DEFAULT_MIRROR_MODE` is 2 (front only);
- `Recorder`: `REQUIRED_FREE_STORAGE_DEFAULT_BYTES = 52428800`; its default muxer factory
  creates `Media3MuxerImpl` for MP4 and 3GPP and `MediaMuxerImpl` otherwise;
  `Media3MuxerImpl.isInterruptionResilient()` returns true, `MediaMuxerImpl`'s false;
  `Recorder$RecordingRecord` uses that flag to leave a MediaStore entry not pending;
- `androidx.media3.muxer.Mp4Writer`: `MOOV_BOX_UPDATE_INTERVAL_US = 1000000`, with
  `maybeExtendMdatAndRewriteMoov` and `safelyReplaceMoovAtEnd`;
- `AudioStats.getAudioAmplitude()` carries `ExperimentalAudioApi`.

The numbers quoted from the original's formulas (bit rates, the horizon crop at 9°, the
pinch dead zone, the meter's release) were recomputed from the cited lines.
