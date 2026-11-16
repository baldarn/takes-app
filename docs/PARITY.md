# Feature parity inventory: YouRec (iOS) vs Takes (Android)

Audit date 2026-11-15. Read-only; nothing was built or run.

- Original: the YouRec iOS app (its own repository) at `faf1815`. Paths written `O/…` are under `YouRec/`.
- Port: this repository, branch `main` at `bd8ab3d`. `A/…` = `app/src/main/kotlin/app/takes/`, `C/…` = `core/src/main/kotlin/app/takes/core/`.

How it was made: every Swift file under `YouRec/` was assigned to one of five readers (projects/export, recorder, editor, audio/subtitles/titles, video effects); I read all of the Android `app` module's screens, controller and exporter, and the `core` declarations, and did the classification. Original line numbers come from those readers; I spot-checked 12 of them against the files and all 12 were right. Gaps in the readers' coverage are listed in the last section.

Status meanings:

- **DONE**: UI entry point and implementation both found on `main`.
- **CORE ONLY**: logic exists in `core`, nothing in `app` references it.
- **PARTIAL**: usable, but less than the original; the gap is named.
- **MISSING**: nothing found.
- **N/A**: not applicable on Android; reason given.

Size: S = hours, M = about a day, L = several days. "Emulator?" says whether the result can be verified on an emulator; "phone" means a real camera, microphone or ears are needed.

Brought up to date on 2026-11-15, status only and without a new reading of the original: section 6 (transitions), section 7 (titles), section 9 (subtitles, where no status changed) and, in section 4, the rows on selection, range, tool scope and clipboard, with `main` at `27746ad`; and the rows this change itself delivers (previous/next change, snap to cuts, the transition badge, the opening and the close staying with their clip). The other rows and their line numbers are as they were audited. What still differs inside the transitions is in `docs/TRANSITIONS-REVIEW.md`, inside the subtitles in `docs/SUBTITLES-REVIEW.md`.

Work in flight on other branches, not counted as done here: `agent/titles` (title tool with design, motion, timing, placement), `agent/deepfilter` (DeepFilterNet3 module), `agent/transitions-core`.

## Summary

| Status | Count |
|---|---|
| DONE | 72 |
| PARTIAL | 45 |
| CORE ONLY | 9 |
| MISSING | 77 |
| N/A | 6 |
| Total | 209 |

Per area:

| Area | DONE | PARTIAL | CORE ONLY | MISSING | N/A |
|---|---|---|---|---|---|
| 1. App shell and project list | 11 | 6 | 0 | 6 | 0 |
| 2. Recovery and storage | 2 | 4 | 0 | 1 | 0 |
| 3. Recorder and camera | 11 | 10 | 0 | 19 | 2 |
| 4. Editor: timeline, playback, structure | 12 | 9 | 1 | 11 | 1 |
| 5. Editor: picture tools | 1 | 4 | 7 | 6 | 0 |
| 6. Transitions | 6 | 1 | 0 | 0 | 0 |
| 7. Titles and fonts | 9 | 0 | 1 | 0 | 0 |
| 8. Audio | 3 | 6 | 0 | 12 | 0 |
| 9. Subtitles | 8 | 3 | 0 | 2 | 0 |
| 10. Video effects | 0 | 0 | 0 | 14 | 1 |
| 11. Export | 6 | 1 | 0 | 5 | 0 |
| 12. Settings, credits, localisation, accessibility | 3 | 1 | 0 | 1 | 2 |

The port goes beyond the original in three places, not counted above: Italian localisation (the original is English only, `YouRec.xcodeproj/project.pbxproj:644-647`), a lighter second speech model (Whisper small, 375 MB) beside the original's Qwen3-ASR, and "Save to gallery" plus "Play" on the export result.

## 1. App shell and project list

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Three destinations: Record, Projects, Edit | O/ContentView.swift:66-70 | DONE | A/MainActivity.kt:50-69 | — | | |
| Tabs disabled during a take or an import | O/ContentView.swift:541-550 | MISSING | A/MainActivity.kt:65-68 (always enabled; leaving the recorder ends the take via ON_STOP/detach, A/RecorderScreen.kt:117-121) | Guard the tab row while recording or importing | S | Yes |
| Landscape interface (editor side-by-side layout, recorder rotates) | O/YouRecApp.swift:77-110, O/EditorView.swift:354-383 | MISSING | `screenOrientation="portrait"` in app/src/main/AndroidManifest.xml:22 | Landscape layouts for recorder and editor | L | Yes |
| First launch creates a project; last project re-selected | O/ContentView.swift:341, O/ProjectStore.swift:64 | PARTIAL | A/CaptureController.kt:401 creates on first Record; A/ProjectStore.kt:95 `select` | Verify the selection is persisted across launches (not confirmed, see last section) | S | Yes |
| New project | O/ContentView.swift:629 | DONE | A/ProjectsScreen.kt:123, A/ProjectStore.kt:100 | — | | |
| Project list sorted by last update | O/ProjectStore.swift:328 | DONE | A/ProjectStore.kt:418 | — | | |
| Card: thumbnail, title, takes and duration | O/ContentView.swift:636-678 | DONE | A/ProjectsScreen.kt (`ProjectCard`, `summary`) | — | | |
| Rename (120 chars) | O/ContentView.swift:493-505 | DONE | A/ProjectsScreen.kt (rename dialog, tap on the title), A/ProjectStore.kt (`rename`) | — | | |
| Continue recording / Edit buttons on the card | O/ContentView.swift:591-622 | DONE | A/ProjectsScreen.kt:213-217 | — | | |
| Export from the project card | O/ContentView.swift:598, O/MovieExportPanel.swift:212 | MISSING | Export only from the editor (A/EditorScreen.kt:614) | Export icon on the card | S | Yes |
| Duplicate project with history | O/ProjectStore.swift:600-626, O/ProjectArchive.swift:178-217 | DONE | A/ProjectsScreen.kt:225, A/ProjectStore.kt:370, C/ProjectDuplicate.kt | — | | |
| Zip project (media, history, OTIO, checksums) | O/ProjectStore.swift:628-646, O/ProjectArchive.swift:219-296 | DONE | A/ProjectsScreen.kt:104-107, 230; A/ProjectStore.kt:165; C/ProjectArchive.kt, C/OpenTimeline.kt | Share sheet for the ZIP is replaced by the system "save as" picker (acceptable) | | |
| Open a project ZIP (validated restore) | O/ProjectArchive.swift:298-403 | DONE | A/ProjectsScreen.kt:109-111, 158; A/ProjectStore.kt:339; C/ProjectRestore.kt | — | | |
| Import a video as a new project | O/ProjectStore.swift:704-733 | DONE | A/ProjectsScreen.kt:99-101, 122; A/ProjectStore.kt:118 | Android picks several at once; original picks one file | | |
| Recently deleted: list, restore, delete permanently | O/ContentView.swift:506-533, O/Project.swift:771-824 | DONE | A/ProjectsScreen.kt:143-155, 179-187; C/ProjectDisk.kt:82-117 | — | | |
| "Delete older projects?" prompt and "Over 30 days" badge | O/ContentView.swift:701-713, O/Project.swift:716-720 | CORE ONLY | C/DeletionRules.kt (age rule and list order) | Prompt, badge and the list's order in the app; Android's deletion record has no "date is known" flag yet, so `DeletionRules.Entry` takes it from the caller | S | Yes |
| Original recordings: list and play each take | O/OriginalRecordingsView.swift:32-58 | DONE | A/OriginalRecordings.kt | The share sheet stands in for "Save to Files", with "Save to the gallery" beside it (Android 10 and later). The recorder here notes no hold or gap events, so the review lists its camera and microphone changes instead. Only the project's indexed sources are listed, not unindexed files | | |
| Original recordings: add a range of a take to the timeline; recover into a new project | O/OriginalRecordingsView.swift:60-88, 110-199; O/ProjectStore.swift:737-805 | PARTIAL | A/OriginalRecordings.kt (range, the whole take at first), A/ProjectStore.kt (`addOriginal`) | "Create recovered project" and "Recover this recording into a new project" (they need the salvage of damaged movies) | M | Yes |
| Repair media: relink missing takes by duration and SHA; remove missing takes | O/ContentView.swift:437-492, O/ProjectStore.swift:666-703 | MISSING | Export only reports a missing source (A/Exporter.kt:140-144) | Missing-media detection on the card, locate file, undoable removal | M | Yes |
| Unreadable project card | O/ContentView.swift:680-687 | PARTIAL | A/ProjectsScreen.kt:134-142 | No action on the card (original opens its recordings) | S | Yes |
| Progress with Cancel for import, duplicate, zip | O/ContentView.swift:693-699 | DONE | A/ProjectsScreen.kt (progress dialog), A/ProjectStore.kt (`cancelOperation`) | Progress is a percentage in a dialog rather than a bar above the list; opening a ZIP can be cancelled too. Videos picked together join the project only once all are copied | | |
| Privacy policy | O/ContentView.swift:755-771 | DONE | A/Credits.kt:31 (link) | — | | |
| Screen kept awake on the camera, while playing and exporting | O/ContentView.swift:102-105 | DONE | A/RecorderScreen.kt:116, A/EditorScreen.kt:422-426 | — | | |

## 2. Recovery and storage

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Atomic manifest, content-addressed history, five rotating revisions with fallback | O/ProjectRecovery.swift:115-226 | DONE | C/ProjectDisk.kt:27-51, 119-131 | — | | |
| Finished takes missing from the manifest are indexed at launch | O/ProjectStore.swift:485-566 | DONE | A/ProjectStore.kt:427-432, C/ProjectDisk.kt:73 | — | | |
| Interrupted (unfinalised) recording salvaged: fragmented file, prefix copy or remux, quarantine | O/CaptureSegmentWriter.swift:213-264, O/RecordingSalvage.swift:123-257 | PARTIAL | CameraX 1.6.2 writes through Media3's muxer, which rewrites the index about once a second, so a killed take is readable as it is and is indexed at launch with its marks (A/ProjectStore.kt `indexFinishedTakes`). Checked on a Galaxy A54 on 2026-11-15: a take killed about 17 s in came back as 16.06 s with both marks, one killed right behind Stop as 8.04 s, a kill during a pause duplicated nothing. A leftover that cannot be read (a take killed in its first second) is moved with its events to `Recovery/` (C/ProjectDisk.kt `quarantineRecording`) and listed in "Unfinished recordings" in the project's menu with "Save file" and "Delete" (A/UnfinishedRecordings.kt), as O/ContentView.swift:392-419 | No repair of a file without an index (the original copies the playable prefix or remuxes it); no receipts, no repair-space budget, no banner with "Review" | M | Yes |
| "Changes not saved" banner with Retry and an in-memory draft journal | O/ContentView.swift:76-85, O/ProjectRecovery.swift:373-401 | PARTIAL | A/ProjectStore.kt:416-423 shows a one-off error | Retry, draft kept and journalled | M | Yes (fill the disk) |
| Free-space floor before recording | O/ProjectRecovery.swift:421-464 (75 MB / 170 MB per quality at O/CaptureController.swift:1357) | PARTIAL | A/CaptureController.kt:403-406 (fixed 300 MB) | Auto-pause while recording when space runs low (O/CaptureController.swift:2682-2695); CameraX only reports the failure afterwards | S | Partly |
| Free-space checks for zip, duplicate, restore | O/ProjectStore.swift:628-646 | PARTIAL | C/ProjectRestore.kt (NotEnoughSpace), C/ProjectDuplicate.kt | Not confirmed for zip | S | Yes |
| Processing caches wiped or trimmed (previews, cleaned audio) | O/ProjectStore.swift:138-141, O/SourceMediaCache.swift:108 | PARTIAL | A/Denoiser.kt:26 keeps cleaned WAVs in the project cache | No quota or trim found | S | Yes |

## 3. Recorder and camera

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Record, pause, resume, stop; one immutable file per segment | O/CaptureController.swift:1391-1487 | DONE | A/CaptureController.kt:398-507, A/RecorderScreen.kt:266-314 | — | | |
| Continue the same project later; "New project" from the recorder | O/RecorderView.swift:400-411 | DONE | A/RecorderScreen.kt:305-307, A/ProjectsScreen.kt:213 | — | | |
| Record here (insert at the playhead) and return to the end | O/AddVideoPanel.swift:50, O/ContentView.swift:557-571 | DONE | A/EditorScreen.kt:749, A/RecorderScreen.kt:274-279, A/ProjectStore.kt:304-307 | — | | |
| Status pill and clock (project duration + elapsed) | O/RecorderView.swift:472-493 | DONE | A/RecorderScreen.kt:141-166 | — | | |
| Red/yellow frame border while recording/paused | O/RecorderView.swift:281-291 | MISSING | — | Border | S | Yes |
| Switch camera while recording, inside the same segment, with an event | O/CaptureController.swift:1513-1647 | PARTIAL | A/CaptureController.kt:247-256 (persistent recording, `camera` event) | Preview is not frozen on the last frame during the switch; "flip to last-used lens on the other side" button | M | Phone |
| Lens menu (main, ultrawide, tele, front) | O/RecorderView.swift:533-549 | DONE | A/RecorderScreen.kt:249-263, A/CaptureController.kt:205-223 | — | | |
| Zoom presets, tap = saved time, double tap = 1 s | O/RecorderView.swift:1049-1128 | DONE | A/RecorderScreen.kt:219-247, C/ZoomTransition.kt:40 | "1× Main" jump from the ultrawide to the main lens | | |
| Zoom transition time, 0–30 s | O/RecorderView.swift:1131-1189, O/ZoomTransition.swift:71-78 | DONE | A/RecorderScreen.kt:224-233 (menu of presets) | Vertical-drag stepping in 0.5 s (presets only) | | |
| Pinch zoom | O/RecorderZoomViewport.swift:116-214 | PARTIAL | A/RecorderScreen.kt:125-127, A/CaptureController.kt:384-388 | Timed-mode preview of the move ("2.0× · 3s on release"), two-finger pan of the crop centre | M | Yes |
| Per-camera zoom memory | O/CaptureController.swift:1648-1664 | PARTIAL | A/CaptureController.kt:244, 365 | Pan centre, focus lock and lens position are not remembered (those features are missing) | S | Yes |
| Quality: 1080p or 4K, 30 fps | O/RecorderView.swift:1295-1301 | MISSING | Fixed FHD at A/CaptureController.kt:265 | Quality setting and 4K | S | Phone |
| Recording colour: SDR / HDR (HLG 10-bit) / Log | O/RecorderView.swift:4-43, O/CaptureController.swift:1665-1739 | MISSING | SDR only | HDR via CameraX `DynamicRange.HLG_10_BIT` where supported; Log has no general Android equivalent (see N/A row) | M | Phone |
| Apple Log / Apple Log 2 capture | O/LogColor.swift:5-9 | N/A | — | Apple-specific encoding; vendor Log modes are not exposed through CameraX | | |
| Codec choice follows colour (HEVC Main10 / H.264), bitrate rule | O/CaptureSegmentWriter.swift:225-248 | PARTIAL | CameraX defaults | No explicit bitrate or codec | S | Phone |
| Mirror selfie preview / save mirrored selfies | O/RecorderView.swift:1249-1252 | MISSING | — | Two settings; CameraX `MirrorMode` | S | Phone |
| "Fit entire recorded frame" vs fill | O/RecorderView.swift:1303 | PARTIAL | Always fits (A/RecorderScreen.kt:102) | The fill option | S | Yes |
| Capture stabilisation toggle | O/RecorderView.swift:798-801 | MISSING | — | CameraX video stabilisation toggle | S | Phone |
| Level horizon with max angle | O/RecorderView.swift:802-807, O/HorizonLevel.swift:8-282 | MISSING | — | Needs a frame-processing capture path (see dual camera) | L | Phone |
| Follow face (single camera crop follow) | O/RecorderView.swift:808-812, O/DualFaceDetector.swift:56-197 | MISSING | — | Face detection + crop pipeline | L | Phone |
| Front light (screen-edge lamp: width, tone, opacity) | O/RecorderFrontLight.swift:8-241 | MISSING | — | Pure UI overlay plus brightness | M | Yes (look), phone (effect) |
| Tap to focus | O/CaptureController.swift:2442-2472, 3331-3348 | MISSING | — | `FocusMeteringAction` on tap, focus box | S | Phone |
| Focus lock / manual focus with lens slider | O/RecorderView.swift:949-1041, 1191-1214 | MISSING | — | Camera2 interop `LENS_FOCUS_DISTANCE` | M | Phone |
| Focus peaking | O/RecorderView.swift:966, O/CaptureController.swift:2422-2435 | MISSING | — | Frame analysis overlay | L | Phone |
| Dual camera: two cameras into one picture, 4 layouts, inset shape, per-feed pan/zoom, swap | O/MultiCameraCapture.swift:93-330, O/DualCameraLayout.swift:5-124, O/RecorderView.swift:292-320, 1386-1531 | MISSING | — | CameraX concurrent camera + own compositor and encoder path | L | Phone (few devices support it) |
| Front landscape 16:9 selfie with the phone upright | O/CaptureController.swift:1871-1971 | N/A | — | Depends on an iOS 26 sensor mode | | |
| Live video effects while recording (21 effects, panel, frame tools) | O/VideoEffects/LiveEffectsPanel.swift:20-321, O/VideoEffects/LiveVideoEffects.swift | MISSING | — | Depends on area 10 and a GL capture path | L | Phone |
| Crash zoom / dolly zoom buttons, tap to select the subject | O/RecorderView.swift:105-133, O/CaptureController.swift:2005-2106 | MISSING | — | Same | L | Phone |
| Microphone choice | O/RecorderView.swift:1332-1338, O/CaptureController.swift:2553-2636 | PARTIAL | A/RecorderScreen.kt:200-215, A/CaptureController.kt:270-357 (Android 12+, per session) | Remembered priority list including disconnected microphones; fallback timing rules (O/CaptureAudio.swift:6-109) | M | Phone |
| Active microphone name and mismatch warning | O/RecorderView.swift:445-470 | DONE | A/RecorderScreen.kt:174-187, A/CaptureController.kt:136-143 | — | | |
| Microphone level meter | O/RecorderView.swift:913-934 | PARTIAL | A/RecorderScreen.kt:167-173 (while recording only) | Level before recording, "Clipping" and "No signal" states | S | Phone |
| Recording gain −40…+12 dB per microphone | O/RecorderView.swift:1348-1369, O/CaptureAudio.swift:144-185 | MISSING | — | Needs an own audio path; CameraX has no gain | L | Phone |
| Audio keeps running across camera switches (independent engine, gap fill, clock correction) | O/CaptureAudio.swift:190-321, O/LiveMicrophone.swift:22-58 | PARTIAL | CameraX persistent recording | Not verified what the sound does during a switch | S | Phone |
| "Protect recording audio" | O/RecorderView.swift:1265-1270 | MISSING | — | Setting | S | Phone |
| Mark (bookmark) while recording | O/RecorderView.swift:68-84 | DONE | A/RecorderScreen.kt:309-311, A/CaptureController.kt:428-432 | — | | |
| Orientation follows the device, locked for the take | O/RecorderView.swift:341-390 | PARTIAL | A/CaptureController.kt:114-127 (recorded orientation follows; interface stays portrait) | Interface rotation | M | Yes |
| Warnings: microphone off, silenced, camera lost, storage full | O/CaptureController.swift:617-687, 2672-2695 | PARTIAL | A/CaptureController.kt:474-516 | Thermal banner; auto-pause on missing audio | S | Partly |
| Backgrounding saves the take | O/CaptureController.swift:1165-1180 | DONE | A/RecorderScreen.kt:108-113 | — | | |
| Camera and microphone permission flow | Info settings in project.pbxproj:972-983 | DONE | A/RecorderScreen.kt:79-96 | — | | |
| Haptics on mark, presets, toggles | O/RecorderView.swift:71, 836, 1119 | MISSING | — | Haptic feedback | S | Phone |
| Quick settings panel and "More settings" sheet | O/RecorderView.swift:723-766, 1225-1310 | MISSING | — | The sheet itself; its rows are the items above | M | Yes |
| Long-press explanation of each setting | O/RecorderView.swift:846 | MISSING | — | Help texts | S | Yes |

## 4. Editor: timeline, playback, structure

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Preview of the edit with play/pause | O/EditorView.swift:449, 565 | DONE | A/EditorScreen.kt (`togglePlay`, a tap on the picture while no tool takes its gestures) | — | | |
| Scrolling thumbnail timeline with waveform under a fixed playhead; scrubbing pauses | O/EditorTimeline.swift:506-525 | DONE | A/EditorScreen.kt:934-1037 | — | | |
| Timeline zoom | O/EditorTimeline.swift:577-582, 186-191 | PARTIAL | A/EditorScreen.kt:683-684 (− / + buttons, 6–480 dp/s) | Pinch; "whole project / 5 min / 1 min / 10 s / 1 s" menu; time ruler | M | Yes |
| Detailed waveform at high zoom (1000 bins/s tiles) | O/WaveformDetail.swift:4-66 | PARTIAL | C/Waveform.kt:42 (50 peaks/s), A/EditorScreen.kt:1074-1088 | Detail tiles | M | Yes |
| Timecode and duration | O/EditorTimeline.swift:1052-1055 | DONE | A/EditorScreen.kt:656 | — | | |
| Review speed 1–3× with pitch kept | O/EditorView.swift:454-465 | DONE | A/Transport.kt (`ReviewSpeed`), A/EditorPlayer.kt (`play(time, speed)`); reviews of a cut, a transition or the playhead play at 1× | — | | |
| Double-tap play "quick review" with pre/post-roll and return; preview a cut | O/EditorView.swift:526-547, O/EditorController.swift:408-467 | DONE | A/EditorScreen.kt (`playTapped`, `quickReview`), C/ReviewWindows.kt; with a screen reader Play plays at once and the review is an action of the button | — | | |
| Full-screen preview | O/TrackEditors.swift:22-86 | DONE | A/FullPreview.kt: the editor's players in a full-screen window that turns with the phone, with speed, position, changes, Play and bookmarks; the controls hide while playing | No inspection zoom (missing in the editor too) | | |
| Preview inspection zoom (pinch 1–8×) | O/EditorView.swift:1586-1601 | MISSING | — | | S | Yes |
| Previous/next change (cuts and effects) | O/TimelineNavigation.swift:56-165 | DONE | A/Transport.kt (`ChangeButton`), C/TimelineNavigation.kt: tap for cuts and changes, hold for changes only | — | | |
| Split at the playhead | O/TimelineEditing.swift:45-62 | DONE | A/EditorScreen.kt:724, C/TimelineEditing.kt:56 | Not frame-quantised (original snaps to 1/30 s) | | |
| Trim handles | O/EditorTimeline.swift:583-602, 718-747 | DONE | A/EditorScreen.kt:484-510, 947-988 | No edge auto-scroll | | |
| Delete a clip | O/TrackTimeline.swift:771-826 | DONE | A/EditorScreen.kt:841 | — | | |
| Reorder clips | O/EditorView.swift:689-735 (Move mode) | PARTIAL | A/EditorScreen.kt:827-838 (one place earlier/later) | Move mode: drag, scopes "Selected only / With left / With right / Both sides", snapping, Cancel/Done | L | Yes |
| Select an item; multi-selection by long press | O/EditorTimeline.swift:538-576 | DONE | A/EditorScreen.kt, A/SelectionBar.kt, C/RangeEditing.kt (`RangeMarks`) | — | | |
| Range selection: Mark start / Mark end / Move end, delete range | O/EditorView.swift:1110-1135 | DONE | A/SelectionBar.kt, C/RangeEditing.kt (`markTime`, `delete`), with a preview of the cut | — | | |
| Tool scope: range, selected clips, or whole project | O/EditorView.swift:998-1020, O/VideoTools.swift:631-658 | PARTIAL | Sound, Color, Crop and Speed apply to the clips of a range or a selection as one edit, and a new title goes over a marked range (A/EditorScreen.kt, C/RangeEditing.kt `scoped`) | The other tools; shared effects that survive splits | M | Yes |
| Copy / Cut / Paste at the playhead | O/TimelinePaste.swift:5-195 | DONE | A/SelectionBar.kt, C/RangeEditing.kt (`copy`, `cut`, `paste`); a clipboard per project, kept while the app runs | — | | |
| Snap to cuts (toggle) | O/EditorView.swift:932-945, O/TrackTimeline.swift:1470-1491 | DONE | A/Transport.kt (`SnapToggle`, `SnapSetting`), A/EditorScreen.kt, C/TimelineSnapping.kt: the playhead, when a scroll ends | Snapping of moved parts belongs to Move mode, which is missing (see Reorder clips) | | |
| Close gap | O/TrackTimeline.swift:1588-1604 | N/A | — | The port's timeline is a gapless sequence; needed only with free placement | | |
| Undo / redo, persistent | O/Project.swift:305-335 | DONE | A/EditorScreen.kt:612-613, C/Project.kt | Playhead not remembered per step | | |
| Bookmarks: add/remove, previous/next, shown on the timeline | O/EditorView.swift:1259-1296 | PARTIAL | A/Transport.kt (`BookmarksMenu`: add or remove, previous, next, and each bookmark by its time), flags on the clips in A/EditorScreen.kt | Bookmark list with thumbnails and labels (`Bookmark.label` exists, C/Project.kt:100) | M | Yes |
| Add video or photo at the playhead from Photos | O/AddVideoPanel.swift:45-49, O/VideoBatchImport.swift:21-72 | DONE | The editor's Add video offers the system photo picker; what is picked goes in at the playhead as one Undo step (A/ProjectStore.kt `insertMedia`); a photo becomes a silent 3 s H.264 movie (A/PhotoMovie.kt, C/InsertionSites.kt `PhotoClip`) | The order of choice is kept only where the system picker keeps it (ordered selection is asked for); the Android 13 emulator's picker returned videos before photos. Nothing is added when one item fails, where the original offers to retry the ones that failed | | |
| Photos as 3 s clips | O/PhotoClip.swift:7-65 | MISSING | — | Encode a still to a clip, or model a still source | M | Yes |
| "+" insertion sites at the start, end and gaps | O/EditorTimeline.swift:318-367, O/TimelineEditing.swift:3-32 | MISSING | — | | S | Yes |
| Unused takes: choose in order and insert | O/AddVideoPanel.swift:59-67, 131-201 | DONE | A/EditorScreen.kt:762-801, C/UnusedTakes.kt | Drag to reorder (tap order only) | | |
| Independent video and audio lanes; any number of layers | O/TrackTimeline.swift:485-502, 1744-1761 | MISSING | One picture sequence + 2 audio tracks (C/Project.kt:195-211) | The track model; see `docs` note in the last section | L | Yes |
| Unlink / Link sound from picture, sync badge, restore sync | O/EditorView.swift:1136-1161, O/TrackTimeline.swift:575-639 | CORE ONLY | C/AudioUnlinking.kt:72-225 | UI, and playing/exporting the detached item at the clip's place | M | Yes |
| Overlapping videos: Placement editor (cover, side by side, top/bottom, picture in picture, edge crop, order, A/B motion) | O/TrackEditors.swift:440-839, O/TrackTimeline.swift:360-456 | MISSING | — | Needs video layers, a multi-input preview and compositor | L | Yes |
| Effect chips on the timeline, tap to edit, hold to remove | O/EditorView.swift:1677-1710 | PARTIAL | Titles open from their bars, sounds from the selection row, transitions from the chip beside Mark (A/EditorScreen.kt, A/SelectionBar.kt) | Chips for colour, crop and speed; hold to remove | M | Yes |
| Tools sheet with sections and "In this project" list | O/EditorTools.swift:33-106 | PARTIAL | Three button rows (A/EditorScreen.kt:723-843) | Sheet, list of applied tools | M | Yes |
| Before/After compare inside a tool | O/EditorTools.swift:1072-1151 | MISSING | — | | S | Yes |
| "?" help on each tool | README "Effect inspectors have a reachable ?" | MISSING | — | Help texts | M | Yes |
| Select cover: pick a frame and share it as JPEG | O/CoverPicker.swift:82-217, O/CoverFramePool.swift:39-47 | MISSING | — | | M | Yes |

## 5. Editor: picture tools

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Speed 0.25–4× with pitch kept | O/EditorTools.swift:1311-1315, O/VideoTools.swift:121-124 | DONE | A/SpeedDialog.kt (slider in steps of 0.05, quick 0.5/1/1.5/2×, C/Speed.kt `stepped`), A/Exporter.kt (`steadySpeed`) | Applied with a button, in a dialog, rather than live in a tool sheet | | |
| Crop: zoom and position | O/CropEditor.swift:345-477, 628-660 | PARTIAL | A/CropPanel.kt (`CropPanel`, `cropGestures`, `cropGuides`), C/CropEditing.kt: zoom 1…8, Reset framing, source area, Position sliders, drag and pinch on the picture, border and thirds, on every clip of a range or selection; drawn by A/TransitionPreview.kt and A/Exporter.kt (`FramingEffect`) from C/PictureFrame.kt | The tool stays in the editor instead of a workspace of its own: no Frame/Preview switch, no dimmed footage around the frame, no clip stepper; VoiceOver zoom steps. The pinch could not be driven on the emulator | S | Phone for the pinch |
| Crop rotation (−180…180, 90° button) | O/CropEditor.swift:195-198, 440-454, O/VideoTools.swift:6-63 | DONE | A/CropPanel.kt (slider in steps of 0.1 snapping to upright, angle to tap, quarter turn), preview and export from `PictureFraming.frame`; a turned crop saves as schema 14 (C/Project.kt, C/Crop.kt `reframed`) | — | | |
| Animated crop (A/B with curves) | O/EffectAnimationControls.swift:14-130, O/EffectAnimation.swift:8-254, O/CropEditor.swift:34-72 | DONE | A/CropPanel.kt (`AnimationControls`: Animate, Edit A/B, curve, ramp, rise and return, Hold B), `Cropping` over C/CropEditing.kt `CropDraft`; sampled per frame in the preview (A/EditorScreen.kt `cropOf`) and the export (`FramingEffect`); schema 14 | The controls are in the Crop tool only; no other tool moves yet | | |
| Video shape for the project (Original, 16:9, 9:16, 1:1, 4:5) | O/CropEditor.swift:93, 189-194, 232-236, 418-436, O/VideoTools.swift:100-112 | CORE ONLY | C/Crop.kt (`OutputShape`), C/CropEditing.kt (`CropShape`) | `EditState.outputSize` with its validation and schema step, the canvas rule in A/Exporter.kt and A/EditorScreen.kt (both take the first clip's shape), and the menu in the Crop tool. Until then applying a crop does not pin the movie's shape as the original does | M | Yes |
| Mirror (flip horizontally / vertically) | O/EditorTools.swift:1319-1322 | CORE ONLY | C/Crop.kt:108 (`MirrorSettings`) | Clip field, UI, preview and export | S | Yes |
| Colour: 14 looks with strength | O/ColorGradeControls.swift:15-154, O/VideoToolCompositor.swift:456-529 | DONE | A/EditorScreen.kt:1206-1255, A/ColorLuts.kt, C/ColorGrade.kt | Preview needs Android 13 (A/EditorScreen.kt:621); look thumbnails | | |
| Imported `.cube` LUT | O/ColorGrading.swift:39-128 | PARTIAL | A/ProjectStore.kt:196, C/CubeLUT.kt | LUT library (Imported / Favorites tabs), "LUT expects" input choice (`LUTInput` exists, C/ColorSettings.kt:28) | M | Yes |
| Exposure, warmth, tint | O/ColorGradeControls.swift | PARTIAL | A/EditorScreen.kt:1242-1244 (exposure ±2) | Exposure range ±4 | S | Yes |
| Highlights slider | O/ColorGradeControls.swift | CORE ONLY | Field at C/ColorSettings.kt:51; not applied in C/ColorGrade.kt | Maths and slider | S | Yes |
| Local highlight recovery for Log footage | O/ColorGradeControls.swift, README:136-139 | MISSING | Field only (C/ColorSettings.kt:52) | Spatial filter; only meaningful with Log sources | L | Yes |
| Log-to-display conversion of Log footage | O/LogColor.swift:61-191 | MISSING | — | Needed only for imported Apple Log clips | M | Yes |
| Saved named grades | O/ColorGradeControls.swift (`color.saveGrade`) | MISSING | — | | M | Yes |
| Animated grade (A/B) | O/EffectAnimation.swift | CORE ONLY | C/ColorSettings.kt:55 (`motion`), `pose`/`applying` | UI; a per-frame LUT in preview and export | L | Yes |
| Colour Correction (exposure, contrast, tonal ranges, white balance, saturation, vibrance, hue; Light/Color tabs; keyframes) | O/VideoEffects/VideoEffectCatalog.swift:209, O/VideoEffects/VideoEffectsEditor.swift:1344-1419 | MISSING | — | Part of video effects; could be folded into the LUT pipeline | M | Yes |
| Stabilisation: Off / Light / Standard / Strong, compare, crop factor | O/StabilizationEditor.swift:325-335, O/StabilizationPath.swift:4-196, O/VideoStabilizer.swift:119-206, O/StabilizationMotion.cpp:140-179 | MISSING | — | Motion analysis (the C++ compiles under the NDK), path, per-frame transform in preview and export, cache | L | Yes |
| Image overlay (timed, size, opacity, position, A/B motion) | O/EditorTools.swift:1238-1254 | CORE ONLY | C/ImageOverlay.kt:7 | Import, clip field, UI, drawing | M | Yes |
| HDR kept through editing | README:48-49, O/MediaRenderer.swift:455-457 | MISSING | Tone-mapped to SDR (A/Exporter.kt:115) | HDR preview and export | L | Phone |

## 6. Transitions

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Dissolve | O/Transitions.swift:7-11, O/TransitionRenderer.swift:189-292 | DONE | A/TransitionPanel.kt, A/TransitionRendering.kt, C/TransitionPicture.kt | — | | |
| Fade through color | same | DONE | same; the color from ten swatches or six hex digits | The system color picker | | |
| Wipe, Split screen, Hitchcock, Whip pan, Flash cut, Still, Defocus | O/Transitions.swift:7-11, O/TransitionEditor.swift:273-339 | PARTIAL | A/TransitionPanel.kt, A/TransitionRendering.kt (one shader for preview and export), A/TransitionPreview.kt, C/TransitionScene.kt, C/TransitionGeometry.kt | Titles drawn inside each side of a transition; the blur of whip pan and defocus is an approximation; below Android 13 the preview shows five styles as cross-fades (docs/TRANSITIONS-REVIEW.md F1-F4) | L | Yes |
| Duration: any value from one frame, presets up to 60 s | O/TransitionEditor.swift:283-296 | DONE | A/TransitionPanel.kt, C/TimelineEditing.kt (`setJoin`, `joinMaximum`): up to the shorter clip's length, shortened to fit the neighbors | — | | |
| Sound of a transition: offset, blend length, cut; first/second clip for split | O/TransitionEditor.swift:338, 364-381 | DONE | A/TransitionPanel.kt (`AudioTiming`), C/TransitionSound.kt, C/TimelineEditing.kt (`soundLevel`) | — | | |
| Opening and closing transitions of the timeline | O/Project.swift:56, O/Transitions.swift:207 | DONE | C/TransitionLayout.kt (`sequence`, `fades`), C/TimelineEditing.kt: they stay with their clip when it is no longer first or last | — | | |
| Transition badge on the timeline, tap to edit; preview play inside the editor | O/EditorTimeline.swift:561, O/TransitionEditor.swift | DONE | The name on the clip puts the playhead on the join; the chip beside Mark adds or opens (A/SelectionBar.kt, A/EditorScreen.kt); the tool plays the transition on the editor's own picture | The tool's own still and scrubber; hold to remove | | |

## 7. Titles and fonts

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Add, edit, remove a title; follows its footage | O/EditorTools.swift:1334-1352, O/VideoTools.swift:218-232 | DONE | A/TitlePanel.kt, A/EditorScreen.kt, A/Titles.kt, C/TimelineEditing.kt (`placeTitle`) | — | | |
| Duration presets, "To end", start/end sliders | O/EditorTools.swift:1353-1394, O/VideoTools.swift:170-216 | DONE | A/TitlePanel.kt, C/TitleMotion.kt (`OverlayTiming`, `EditorDurationPresets`) | — | | |
| Position by drag, size by pinch, sliders, centre snap | O/EditorTools.swift:1180-1203 | DONE | A/EditorScreen.kt (drag and pinch on the picture), A/TitlePanel.kt | — | | |
| Looks (8 built in), saved channel styles | O/TitleInspector.swift:123-197, O/TitleDesign.swift:99-136 | DONE | A/TitlePanel.kt, A/TitleRendering.kt, C/TitleDesign.kt | — | | |
| Fonts: 7 bundled families, weights, italic, system fonts, favourites | O/TitleInspector.swift:216-276 | DONE | A/TitlePanel.kt (`Fonts`), A/TitleRendering.kt (`TitleFonts`), `app/src/main/assets` | — | | |
| Imported fonts library (TTF/OTF/TTC/OTC), kept in the project and ZIP | O/FontLibraryView.swift:27-174, O/ImportedFonts.swift:34-188 | CORE ONLY | C/TitleDesign.kt (`ImportedFontReference`) | Library, project copies, archive entries | M | Yes |
| Alignment, letter spacing, line spacing | O/TitleInspector.swift:236-240 | DONE | A/TitlePanel.kt, A/TitleRendering.kt | — | | |
| Fill: solid or gradient (12 presets), colour picker, hex, recents, eyedropper | O/TitleInspector.swift:280-342, 450-594 | DONE | A/TitlePanel.kt; the eyedropper samples the editor's picture | — | | |
| Outline, shadow, background box (one box / each line) | O/TitleInspector.swift:80-103, O/StyledTextRenderer.swift:73-101 | DONE | A/TitlePanel.kt, A/TitleRendering.kt | — | | |
| Motion: entrance and exit (fade, drift, scale, wipe), direction, duration | O/TitleInspector.swift:343-389, O/TitleDesign.swift:222-300 | DONE | A/TitlePanel.kt, A/TitleRendering.kt, C/TitleMotion.kt | — | | |

## 8. Audio

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Clip volume on the half-decibel grid −40…+12 dB with a stepper, mute, "Mixed levels" for a selection or a range | O/TrackEditors.swift:272-293, O/AudioGain.swift:6-11 | DONE | A/SoundPanel.kt `SoundDialog`, C/AudioLevels.kt `AudioGainGrid`; applied per sample in the export (A/Titles.kt `SoundProcessor`, C/SoundEnvelope.kt) | No live audition of the level while the dialog is open; above 0 dB the preview uses a loudness enhancer, not a plain gain | | |
| Fade in / fade out | O/TrackEditors.swift:88-150, 294-307, O/TrackTimeline.swift:132-151, 188-203 | PARTIAL | A/SoundPanel.kt `FadeRow` (any length up to the item, typed or on a logarithmic slider; the fade in on the first target, the fade out on the last); saved as the original saves it, schema 15 (C/Project.kt `ClipAudio`): a split, a trim and a range keep level and slope | "Listen to the fade" | S | Yes |
| Clipping warning from headroom analysis | O/AudioGain.swift:199-239 | DONE | A/Headroom.kt (the recordings decoded and mixed through the export's envelopes; C/AudioLevels.kt `AudioHeadroom`), shown in A/SoundPanel.kt and beside the finger of the waveform drag | An estimate of the export's mix, not a read of it: speed without the pitch kept, other sample rates resampled linearly | | |
| Waveform vertical swipe to set gain | O/EditorTimeline.swift:617-688, O/AudioGain.swift:16-62 | PARTIAL | A/SoundPanel.kt `dragLevel`, `Leveling`, `LevelReadoutLabel`; A/EditorScreen.kt (`Timeline`'s `level`); C/AudioLevels.kt `AudioGainDrag` | The level is not heard while it is dragged (the editor pauses); a drag inside a marked range does not reach the sounds on the audio tracks | S | Yes |
| Automatic 8 ms fades at cuts | O/CutAudioEnvelope.swift:30-55, O/TrackRenderer.swift:281-296 | PARTIAL | Export: A/Titles.kt `SoundProcessor`, on the first and last samples of every run of sound, clips and sounds on the audio tracks; C/SoundEnvelope.kt | The preview has none (a player's volume, once a frame); in the export a split nobody trimmed still dips for some 30 ms, since each item is decoded afresh | M | Phone (ears) |
| Add audio from Files as an independent layer | O/AudioToolsEditor.swift:5-65 | DONE | A/EditorScreen.kt:601-603, 737; A/ProjectStore.kt:140 | Two tracks; the original has no limit | | |
| Move, trim, level and fades of an audio item | README:117-120 | PARTIAL | A/EditorScreen.kt `AudioItemDialog` (move to playhead, cut before/after), A/SoundPanel.kt (level and fades as for a clip, the level also by dragging the item up or down) | Drag to move, trim handles, split | M | Yes |
| Voice recording in the editor ("Record now") | O/VoiceRecorder.swift:97-414 | MISSING | — | Recorder sheet, mic picker, takes joined, insert at the playhead | M | Phone |
| Noise reduction with RNNoise, amount | O/AudioChainControls.swift:402-411, O/OfflineAudioPipeline.swift:137 | PARTIAL | A/EditorScreen.kt:1359-1362 (Off/Light/Full), A/Denoiser.kt, `rnnoise/` | 0–100 % amount; clips only, not audio items (A/EditorScreen.kt:861) | S | Phone (ears) |
| Noise reduction with DeepFilterNet3 | O/DeepFilterVoiceProcessor.swift:7, ThirdParty/DeepFilterNet3 | MISSING | In flight on `agent/deepfilter`; route in docs/MODELS-ON-ANDROID.md | Module on main, model choice in the UI | L | Phone (ears) |
| Speech detail restoration (LavaSR v2) | O/StudioVoiceProcessor.swift:36 | MISSING | — | ONNX export and DSP port | L | Phone |
| Studio restoration (VoiceFixer) | O/StudioRestorationProcessor.swift:50 | MISSING | — | Likely too heavy; measure first (docs/MODELS-ON-ANDROID.md) | L | Phone |
| Low-cut filter 20–500 Hz | O/AudioDSP.swift:19-22 | MISSING | — | | S | Phone (ears) |
| Voice EQ band ±24 dB, 80–12000 Hz | O/AudioDSP.swift:23-28 | MISSING | — | | S | Phone (ears) |
| Output gain stage ±24 dB | O/AudioDSP.swift:18 | MISSING | — | | S | Yes |
| Enhance audio inspector: ordered chain, add/remove/reorder, per-stage bypass, fader strips | O/AudioChainControls.swift:182-446, O/TrackEditors.swift:152-328 | MISSING | — | Chain model on clips/items, offline render with per-stage cache, UI | L | Yes |
| Bypass/Enhanced comparison with matched listening levels | O/OfflineAudioPipeline.swift:225-300 | MISSING | — | | M | Phone (ears) |
| ±5 s preview window while a chain renders | O/EffectApplication.swift:9-18 | MISSING | Whole file is cleaned first (A/EditorScreen.kt:433-443) | | S | Yes |
| Censor audio (beep: waveform, frequency, level, envelope; per track) | O/AudioToolsEditor.swift:143-185, O/AudioCensor.swift:6-130 | MISSING | — | Needs range selection; tone generator in preview and export | M | Phone (ears) |
| Remove pauses: detect and cut as one undo step | O/EditorTools.swift:1395-1471, O/VideoTools.swift:315-436 | PARTIAL | A/EditorScreen.kt:1262-1300, A/PauseLevels.kt, C/Pauses.kt:112 | Manual threshold, 0.1–3 s minimum (4 presets now), keep-before/after sliders, per-pause Cut/Keep, listen, re-edit later (`PauseSettings.candidates` exists at C/Pauses.kt:41-51 but cuts are baked in) | M | Yes |
| Export checks audio for clipping and warns | O/MovieExportPanel.swift:42-57, O/Project.swift:691-695 | MISSING | — | Peak pass and warning | M | Yes |

## 9. Subtitles

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| On-device transcription, model downloaded on request, verified, removable | O/SubtitleModelPack.swift:9-82, O/SubtitleTranscriber.swift:24-83 | DONE | A/SubtitleModel.kt, A/SubtitleTranscriber.kt, `speech/` (Qwen3-ASR 0.6B as in the original, 987 MB; Whisper small, 375 MB, for phones with less memory; see "Departures in the recognizer" below) | — | | |
| Language choice (Auto + 11); Auto gives every piece to the model without a hint | O/SubtitleEditor.swift:47, O/SubtitleTranscriber.swift:70-71 | DONE | A/SubtitlesPanel.kt (`Languages`), A/SubtitleTranscriber.kt (`transcribe`) | — | | |
| Progress with sections and time left, cancel; leaving the app pauses a transcription, "Resume transcription" goes on from the last finished piece | O/SubtitleEditor.swift:150-157, 233-235, O/SubtitleTranscriber.swift:4-10, 45-50, 78 | DONE | A/SubtitlesPanel.kt (`checkpoint`, `paused`), A/SubtitleTranscriber.kt (`resume`, `save`), C/SubtitleCheckpoint.kt. Departure: a download is not interrupted when the app leaves the screen, as Android lets it run; the checkpoint is kept while the tool is open, as the original keeps it in the editor | — | | |
| Transcribe a marked selection only | O/SubtitleEditor.swift:232-236 | MISSING | Whole project | Needs range selection | M | Yes |
| Transcribes the mix, including sound on audio tracks | O/Subtitles.swift:205-247 | PARTIAL | Clips' own sound only (C/SubtitleEditing.kt:17, README "Not ported yet") | Audio items | M | Yes |
| Max words per subtitle | O/SubtitleEditor.swift:246-254 | DONE | A/SubtitlesPanel.kt (strings `subtitles_max_words`), C/SubtitleEditing.kt:106 | — | | |
| Correct or delete a caption's text in place ("Cancel edit", "Save text"); "Following playback" block of five captions; full list where a first tap seeks and a second edits | O/SubtitleEditor.swift:73-75, 143-147, 306-347 | DONE | A/SubtitlesPanel.kt (`CueRow`), C/SubtitleEditing.kt (`correct`, `delete`); an empty text removes the caption, as in the original | — | | |
| Copy full transcript | O/SubtitleEditor.swift:256-274 | DONE | A/SubtitlesPanel.kt (`subtitles_copy`) | — | | |
| Remove all subtitles | O/SubtitleEditor.swift:178-181 | DONE | A/SubtitlesPanel.kt (`subtitles_remove_all`) | — | | |
| Placement by dragging and pinching on the preview, outline of the largest caption, screen reader actions | O/SubtitleEditor.swift:84-89, 183-200, O/EditorView.swift:1413-1422, 1540-1550 | DONE | A/SubtitlePlacing.kt, C/SubtitleEditing.kt (`SubtitlePlacement.envelope`, `moved`, `pinched`); sliders and reset as well (A/SubtitlesPanel.kt). Departure: no switch to inspect the picture with a pinch, as the port's preview has no inspection zoom | — | | |
| Styles plain/bold/box/highlight, six colours, five fonts | O/Subtitles.swift:15-47 | DONE | A/SubtitlesPanel.kt:475, A/SubtitleRendering.kt:96 | — | | |
| Full title inspector for captions (looks, fonts, fill, outline, shadow, background) and per-caption motion | O/SubtitleEditor.swift:279-294 | MISSING | Kept as raw JSON so it is not lost (C/Subtitles.kt:36-59) | Depends on area 7 | M | Yes |
| Same drawing in preview and export, two stacked captions | O/SubtitleRenderer.swift:93-203 | DONE | A/SubtitleRendering.kt:33-131, A/Exporter.kt:249-254 | — | | |

### Departures in the recognizer

Brought up to date on 2026-11-15 with the change that brings the original's model. The original runs Qwen3-ASR 0.6B (4-bit, MLX) in pieces of at most 12 s, with no hint when the language is Auto and "language <name>" otherwise, at most 256 tokens per piece (`O/SubtitleTranscriber.swift:70-71`, `O/SubtitleSpeech.swift:34-46`, `ThirdParty/QwenASR/Sources/YouRecQwen/Qwen3ASR/Qwen3ASR.swift:492-498`). The port does the same with the same model, through sherpa-onnx. Where it departs, and why:

| Departure | Original | Port | Why |
|---|---|---|---|
| Another conversion of the same model | aufklarer's 4-bit MLX conversion, 713 MB, five files (`O/SubtitleModelPack.swift:7-16`) | The sherpa-onnx project's 8-bit ONNX export, 987 MB, six files (`S/SpeechModel.kt`, `Qwen3Asr`). The three tokenizer files are byte for byte the original's | Android forces it: MLX runs on Apple hardware only |
| A second, lighter model | One model for every iPhone | Whisper small (375 MB) is offered too, and is what a phone gets by itself when Android reports under 7 GB of memory (`S/SpeechModels.kt`, `QWEN_MINIMUM_RAM_BYTES`) | Android forces it: Qwen3-ASR takes 2.1 to 2.3 GB of memory while it works (measured, `speech/README.md`). On one phone sold with 6 GB it completed every run, with Android closing background processes to make the room; other phones of that size were not measured, so they get it only when the person picks it |
| A choice of model in the tool | None | A line "Model: … · n MB" at the foot of the tool opens a choice of the two, with removal for each; someone who does nothing sees one download button, as in the original (`A/SubtitlesPanel.kt`, `ModelChoice`) | Follows from the second model |
| Whisper base kept where it is | — | A phone that downloaded Whisper base with an earlier version keeps using it until the person picks another model; it is not offered for download any more | Not to take away, on an update, what the person downloaded |
| Refusal for lack of memory | Loads the model whatever the memory | Before loading, the tool asks Android whether its memory is low and whether three quarters of what the model takes is available (`S/SpeechModels.kt`, `fits`; less than the whole, because Android makes room by closing apps in the background: measured, `speech/README.md`); when not, the tool says so and offers the lighter model. A job the app did not survive is reported the next time the tool opens (`A/SubtitleTranscriber.kt`, `A/SubtitleModel.kt`, `interrupted`) | Android ends an app that takes more memory than the phone can give, without a chance to say anything |
| Language hint by code | The name the person picked goes to the model as it is | The same name reaches Qwen3-ASR ("language Italian"), by way of the two-letter code Whisper needs (`S/SpeechRecognizer.kt`, `QWEN_LANGUAGES`) | Two recognizers behind one interface; the model receives the same words |
| What is listened to | The finished mix of the timeline | Each clip's own recording | Older departure, unchanged here: `docs/SUBTITLES-REVIEW.md`, findings 2, 3 and 8 |

Not a departure any more: with Auto the port used to force the language of the first piece on all the others. Every piece is now recognized without a hint, as in the original, so two pieces can come out in two languages on both platforms.

## 10. Video effects

None of this exists in the port: no model in `core`, no renderer, no UI. The original's catalogue is 24 effects (`O/VideoEffects/VideoEffectModel.swift:34-37`, definitions at `O/VideoEffects/VideoEffectCatalog.swift:189-390`), drawn by one Metal file shared by preview and export.

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Effects editor: library in four sections, applied list, add, remove, enable/disable, compare with original | O/VideoEffects/VideoEffectsEditor.swift:38-208, 911-1055 | MISSING | — | Model, GL effect pipeline shared by preview and export, UI | L | Yes |
| Parameter keyframes ("Animate", per-parameter diamond, previous/next key) | O/VideoEffects/VideoEffectsEditor.swift:535-560, 1209-1336; O/VideoEffects/VideoEffectModel.swift:122-141 | MISSING | — | | M | Yes |
| Simple colour effects: RGB Offset (with pads), Negative Strobe, Silent Tint, Three-Strip, Bleach Bypass, Day for Night, Expressionism, Noir, Color Isolation "A color" | O/VideoEffects/VideoEffectCatalog.swift:193-259, O/VideoEffects/RGBOffsetPads.swift:3-69 | MISSING | — | One fragment shader each | L (all nine) | Yes |
| Kaleidoscope with on-preview guide | O/VideoEffects/VideoEffectCatalog.swift:287, O/VideoEffects/KaleidoscopeGuide.swift:41-300 | MISSING | — | | M | Yes |
| Crash Zoom | O/VideoEffects/VideoEffectCatalog.swift:276, O/VideoEffects/VideoEffectMath.swift:224 | MISSING | — | Face tracking variant is harder | M | Yes |
| Widescreen 1953 (bars, blur fill, flares) | O/VideoEffects/VideoEffectCatalog.swift:296 | MISSING | — | Oval bokeh needs Cinematic depth: N/A | M | Yes |
| Film texture: Halation, 16mm, Super 8 | O/VideoEffects/VideoEffectCatalog.swift:321-335 | MISSING | — | Multi-pass blur, grain, weave, cadence | L | Yes |
| Quantization (palettes, dithering, "from scene" palette, saved palette) | O/VideoEffects/VideoEffectCatalog.swift:259, O/VideoEffects/VideoEffectMath.swift:81-163 | MISSING | — | k-means palette analysis | L | Yes |
| Time effects: Freeze Frame, Step Printing, Undercranking | O/VideoEffects/VideoEffectCatalog.swift:303-314, O/VideoEffects/VideoEffectTiming.swift:18-68 | MISSING | `FrozenFrameSegment`, `EffectPlaybackSpan` only (C/PlaybackSpan.kt) | Retiming in player and export | L | Yes |
| Chroma Key with background (solid, image, video, blur, studio, window), clean plate, eyedropper | O/VideoEffects/VideoEffectCatalog.swift:343 | MISSING | — | | L | Yes |
| Auto Background, Color Isolation "The subject", person hints (person segmentation) | O/VideoEffects/VideoEffectCatalog.swift:371, O/VideoEffects/VideoEffectAssets.swift:346-353 | MISSING | — | Segmentation model (for example ML Kit selfie segmentation), temporal matte | L | Phone |
| Dolly Zoom | O/VideoEffects/VideoEffectCatalog.swift:268 | MISSING | — | Person/depth layers and warp | L | Phone |
| Night fix (Fast / Medium / Precise multi-frame denoise) | O/VideoEffects/NightFix.swift:51-297, O/VideoEffects/VideoEffectCatalog.swift:203 | MISSING | — | Optical flow fusion and chunk cache | L | Phone |
| Effects from Cinematic-mode depth (oval bokeh, depth dolly) | README:272-275 | N/A | — | Apple Cinematic depth tracks | | |
| Effect help with film-history notes | O/VideoEffects/VideoEffectCatalog.swift:7-32 | MISSING | — | Texts | M | Yes |

## 11. Export

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Export the edit to MP4, sources untouched | O/MediaRenderer.swift | DONE | A/Exporter.kt:128-298 | — | | |
| Resolution: project, 720p, 1080p, 4K | O/Project.swift:645-661, O/MovieExportPanel.swift:64-115 | DONE | A/EditorScreen.kt:1427-1433, C/Project.kt:233-246 | "Upscaled from" note | | |
| Frame rate 24, 25, 30, 50, 60 | O/Project.swift:645-670 | PARTIAL | A/EditorScreen.kt:1435 ("up to 30 / up to 60", frame dropping only, A/Exporter.kt:169) | Real constant frame-rate output | M | Yes |
| Codec H.264 / H.265 | same | DONE | A/EditorScreen.kt:1437, A/Exporter.kt:282 | — | | |
| HDR export (HLG, forced H.265 10-bit) | O/Project.swift:669, O/MediaRenderer.swift:455-457 | MISSING | Tone-mapped to SDR (A/Exporter.kt:115) | | L | Phone |
| Progress and Cancel | O/MovieExportPanel.swift:117-139, 193-199 | DONE | A/EditorScreen.kt:1471-1479, A/Exporter.kt:331 | Named steps | | |
| Export pauses on backgrounding and resumes from 12 s sections | O/ExportCheckpoint.swift:23-146 | MISSING | Runs in the activity's scope with no foreground service (A/Exporter.kt:122) | Foreground service (the Android answer) or checkpoints | M | Yes |
| Share the movie | O/MovieExportPanel.swift:24-41 | DONE | A/EditorScreen.kt:1494-1496 | — | | |
| Save to Photos / gallery | same | DONE | A/EditorScreen.kt:1497-1499, A/Exporter.kt:338 (Android 10+) | — | | |
| "Previous export" stays shareable; an unchanged edit offers sharing instead of exporting again | O/MovieExportPanel.swift:150-155, README:171-172 | MISSING | Result is held in memory only (A/Exporter.kt:126) | Receipts with edit and source checksums | M | Yes |
| Output verification (duration, first/last frame) | O/MediaRenderer.swift:792-806 | MISSING | — | | S | Yes |
| Completion chime | README:170 | MISSING | — | | S | Phone |

## 12. Settings, credits, localisation, accessibility

| Feature | Original | Status | Where in Android | What is missing | Size | Emulator? |
|---|---|---|---|---|---|---|
| Credits and licence notices | O/CreditsView.swift:6-58 | DONE | A/Credits.kt:38, res/raw | — | | |
| Editor settings: Snap to cuts, Match listening levels, Fonts | O/EditorTools.swift:92-106 | MISSING | — | Follows the features they control | S | Yes |
| Onboarding | not found in the original | N/A | — | The original has none | | |
| Localisation | English only (project.pbxproj:644-647) | DONE | values/, values-it/ | — | | |
| Accessibility labels on controls | identifiers throughout, e.g. O/RecorderView.swift | DONE | `contentDescription`/`semantics` in every screen read (e.g. A/RecorderScreen.kt:290-293, A/EditorScreen.kt:667-684) | No stable test tags comparable to the original's identifiers | | |
| Large-text layouts (2-column grids at accessibility sizes) | O/EditorView.swift:902-922 | PARTIAL | Short button rows (comment at A/EditorScreen.kt:720) | Not verified at large font scale | S | Yes |
| Reduce Motion respected in title previews | O/TitleInspector.swift:343-389 | N/A | — | Applies once title motion exists | | |

## Pure core work packages (new files in `core`, host tests, no edits to existing files)

Ranked by how much user-visible work each unblocks and how independent it is. Each is a new `C/<Name>.kt` plus `core/src/test/.../<Name>Test.kt`. None needs to touch `Project.kt`; wiring a result into the saved model is a separate, later step.

1. **AudioChain** — read `O/AudioDSP.swift:5-50`, `O/AudioChainControls.swift:33-56`, `O/Project.swift:63-139`. Deliver: the six stage kinds with defaults and validation; biquad coefficients and per-channel state for gain, 2nd-order high-pass and peaking EQ at 48 kHz; a `process(FloatArray, channels)` chain with per-stage bypass; fader↔value mappings (`20·25^n` Hz, `80·150^n` Hz, ±24 dB) and display text. The original's tests check exact bypass, repeated gain stages, filter stability and channel independence (`Tests/CoreTests.swift`).
2. **RangeEditing** (range marks, delete range, clipboard) — read `O/TimelineEditing.swift:42-160`, `O/TimelinePaste.swift:5-195`, `Tests/TimelineClipboardChecks.swift`. Deliver on the current `EditState`: `quantize` to 1/30 s, `deleteRange(state, from, to)`, `copy(state, from, to)` → fragment, `paste(state, fragment, at)` with independent copies of effects and new ids, paste through a transition replacing it.
3. **TimelineNavigation** — read `O/TimelineNavigation.swift:6-166`, `O/TrackTimeline.swift:1470-1491`, `O/EditorController.swift:408-467`. Deliver: change stops (cuts, transition edges, title/colour/crop/speed boundaries), `previous/next(time, effectsOnly)` with 1/120 s tolerance, snap target within a pixel distance with acquire/latch thresholds, quick-review window (1 s pre/post-roll) and "preview cut" time map.
4. **AudioLevels** — read `O/AudioGain.swift:6-62, 199-239`, `O/TrackTimeline.swift:133-151`, `O/CutAudioEnvelope.swift:4-149`. Deliver: 0.5 dB grid and common level of a selection, drag-to-dB with detent and edge repeat, headroom interval and `clips(at:)`, edge fades fitted to an item, 8 ms cut fades and 10 ms gain ramps as a per-sample gain function.
5. **AudioCensor** — read `O/AudioCensor.swift:6-130`. Deliver: settings with ranges, band-limited sine/triangle/square oscillator, short/soft/pulsed envelope, region slicing against clips and audio items, which track carries the tone, original-mute gain with 5 ms edges.
6. **PicturePlacement** — read `O/TrackTimeline.swift:360-456, 1204-1207`, `O/DualCameraLayout.swift:4-124`, `O/DualCameraMasks.swift:11-40`, `Tests/DualCameraMaskTests.swift`. Deliver: `VideoPlacement` (cover, side by side, top/bottom, picture in picture) → window rect, edge crop with validity, reframe zoom; dual-camera `regions`, `constrain`, `resizeInset`, diagonal and circle mask coverage. Serves both editor picture-in-picture and dual camera.
7. **VideoEffectModel** — read `O/VideoEffects/VideoEffectModel.swift`, `VideoEffectCatalog.swift:93-390`, `VideoEffectDefinitions.swift`, `VideoEffectTiming.swift`, `Tests/VideoEffectModelTests.swift`. Deliver: `EffectValue` and interpolation, parameter kinds with validation, keyframes (`value(at:)`, `setKeyframe`), the 24-effect catalogue with ranges and defaults, parameter visibility rules, render order (`ordered`), `isActive`, strobe polarity, `effectPlaybackSpans`, `sequenceRate`, `mutesSourceAudio`.
8. **VideoEffectMath** — read `O/VideoEffects/VideoEffectMath.swift`, `VideoEffectSampling.swift`, `KaleidoscopeGuide.swift:7-37`, `RGBOffsetPads.swift:11-39`. Deliver: crash-zoom curve and transform, cadence clock, held time/frame index, sample plan for step printing, sRGB↔linear↔Oklab, splitmix64, palette k-means with temporal blend, kaleidoscope pose and drag transform, background placement, live budget rungs.
9. **StabilizationPath** — read `O/StabilizationPath.swift:4-196`, `Tests/StabilizationTests.swift`. Deliver: levels with σ and zoom caps, pose algebra, `requiredZoom`, `StabilizationPath.make` (local-linear smoothing, segment fades, strength bisection, single crop zoom), `plan.transform(at:)`, split reuse via reference range. `O/StabilizationMotion.cpp` is plain C++ and can go into a separate new NDK module by another agent.
10. **ExportPlan** — read `O/MediaRenderer.swift:80-150, 792-806`, `O/ExportCheckpoint.swift:6-146`, `O/Project.swift:669-695`, `O/CoverFramePool.swift:5-57`. Deliver: named progress steps with monotonic `advanced(to:)`, 12 s section arithmetic and ticket equality, receipt (edit + source checksums → "unchanged, share instead"), audio-peak warning text rule, output verification tolerances, cover frame pool (interval merge, 30 fps grid, band picks).
11. **RecorderMath** — read `O/CaptureAudio.swift:6-185`, `O/HorizonLevel.swift:8-148`, `O/DualCameraLayout.swift:128-189`, `O/DualFaceDetector.swift:129-195`, `O/RecorderZoomViewport.swift:151-174`, `O/CaptureController.swift:2664-2695, 2874-2879`, `O/VideoEffects/CaptureZoomHistory.swift:5-178`, `Tests/SingleCameraFollowTests.swift`. Deliver: microphone priority/pin/fallback state machine, recording gain clamp and ramp, meter ballistics with clipping and no-signal holds, horizon framing/estimate/line, face target selection and follow smoothing, single-camera follow crop, pinch mapping with dead zone, storage auto-pause rule, zoom history lookup.
12. **RecoveryRules** — read `O/ProjectRecovery.swift:299-464`, `O/RecordingSalvage.swift:54-152`, `O/Project.swift:716-720`, `O/ProjectStore.swift:294-316`, `Tests/ProjectRecoveryTests.swift`. Deliver: free-space floor `max(512 MB, min(2 GB, total/50))` and messages, salvage copy budget, MP4 box scan for the last complete fragment, 30-day deletion rule and deleted-list order, take receipt validation, pending-draft rule.
13. **InsertionSites** — read `O/TimelineEditing.swift:3-32`, `O/PhotoClip.swift:7-65`, `O/VideoBatchImport.swift:13-19`. Deliver: start/gap/end insertion placement and overflow ripple, photo-clip plan (3 s, 30 fps, longest side ≤ 3840, even dimensions), batch ownership naming.
14. **LogAndHighlights** — read `O/LogColor.swift:61-191`, `O/VideoToolCompositor.swift:456-529`, `O/ColorGrading.swift:39-128`. Deliver: Apple Log / Log 2 decode/encode and primaries matrix, the highlights curve, 1D `.cube` support if `C/CubeLUT.kt` lacks it (check first), LUT input conversion.
15. **ListeningLevels and WaveformTiles** — read `O/OfflineAudioPipeline.swift:225-300`, `O/WaveformDetail.swift:4-17`, `O/EditorTimeline.swift:778-796`. Deliver: gated loudness matching over 400 ms windows and `allowsMatching`, min/max tile envelope at 1000 bins/s, peak pyramid.
16. **TrackTimeline** (the multitrack model) — read `O/TrackTimeline.swift` whole, `docs/EDITOR-TRACKS-DESIGN.md`, `Tests/TrackTimelineTests.swift`, `Tests/TrackMediaTests.swift`, `Tests/MoveBoundaryChecks.swift`. Deliver: tick time, items with own start, link groups, split/remove/insert/extend/move with scopes, lane packing, `compile()` time slices. Last because it is the largest and its adoption replaces the sequence model that everything in `app` uses; it needs a design decision before anyone starts.

Already in `core` and waiting only for app work (no core package needed): the nine transition styles, title design and motion, audio unlinking, effect animation (A/B), crop rotation, mirror, output shape, image overlay, pause Cut/Keep candidates, bookmark labels.

## Not classified with confidence

- **Selected project remembered across launches** on Android: `ProjectStore.select` was seen (A/ProjectStore.kt:95) but I did not read whether the id is persisted.
- **Zip free-space check**: seen for restore and duplicate, not confirmed for zip.
- **1D `.cube` LUTs** and the 32 MB / size 65 limits: `C/CubeLUT.kt` was not read line by line.
- **Sound during a camera switch** on Android (gap, click or continuous): needs a phone.
- **Colour preview below Android 13**: the look is not shown in the preview (A/EditorScreen.kt:621); counted as DONE with a note, could be argued PARTIAL.
- **`SubtitlesPanel.kt`** was read by outline and strings, not line by line; the caption rows marked DONE rest on its strings, `core` functions and the README.
- **Coverage gaps of the original's readers**: `AudioDiagnostics.swift`, `VideoEffectsDiagnostics.swift` (both `#if DEBUG`, no release features expected), `LiveVideoEffects.swift`, `MetalVideoEffects.swift`, `VideoEffectAssets.swift`, `VideoEffectDepth.swift`, `VideoEffectCaptureAnalysis.swift` were outlined rather than read in full; `TrackEditors.swift` was read to line 853 by one reader and in full by another. The per-effect porting difficulty in area 10 is inferred from the render pre-passes, not from the shader body.
- **Time-ranged video effects**: the model has start/end but no user control for in/out was found; whether a range-scoped tool request sets them was not traced.
- **Title z-order** and **number of simultaneous titles**: several per clip are allowed in both apps; no ordering control was found in the original.
- **Fade-through-colour**: the original lets the user pick the fade colour; counted inside the "Fade through black" row as a note rather than its own row.
- **Counting unit**: rows are features as a user would name them; some rows bundle several controls (for example the nine simple colour effects), so counts measure breadth, not effort. Use the size column for effort.
