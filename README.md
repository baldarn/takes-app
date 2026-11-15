# Takes — Android port of YouRec (work in progress)

A Kotlin rewrite of [YouRec](https://github.com/antirez/YouRecApp), antirez's iPhone
camera and video editor. "Takes" is a placeholder name: the YouRec name and icon are
reserved by its author.

## Build

Needs JDK 17, the Android SDK (API 36), and the NDK (29.0.14206865) with CMake 3.22.1
for the `rnnoise` module.

    ./gradlew :core:test            # project model, storage and zoom curve
    ./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk

## Layout

- `core/` — pure Kotlin, no Android. `Project` (sources, capture ledger, undo/redo
  snapshots), `ProjectDisk` (atomic manifest, content-addressed `History/`, revisions,
  Recently Deleted), `ZoomTransition` (quintic ease on log magnification).
  `TimelineEditing` (split, delete, move, trim, transitions, titles, bookmarks), `WaveformBuilder` (audio peaks).
- `rnnoise/` — the RNNoise C library with a small Kotlin API, built with the NDK.
- `app/` — Compose UI, CameraX and Media3. `CaptureController` owns the camera, zoom and
  take lifecycle; `ProjectStore` owns the library; `Exporter` renders an edit with
  Media3 Transformer; `RecorderScreen`, `ProjectsScreen` and `EditorScreen`.

## Ported so far

- Projects as the durable unit: record, pause, resume, stop, and continue the same
  project later, including after the app restarts. Each pause or stop finalizes one
  immutable segment file.
- Camera switching while recording, kept inside the same segment with a `camera` event.
- Per-camera zoom memory, zoom presets, timed zoom transitions (tap: saved time,
  double tap: one second), pinch to zoom.
- Recordings stay outside editor undo/redo, as in the original model.
- Basic editor: preview of the edit, scrolling thumbnail timeline under a fixed
  playhead with audio waveforms, split at the playhead, trim handles on the current
  clip, delete and reorder clips, persistent undo/redo.
- Cross dissolves, where the next clip overlaps the end of the previous one, and
  fade-through-black transitions between neighboring clips (picture and sound), and
  titles attached to their footage, identical in preview and export.
- Export of the edit to a new movie in the project's `Exports` folder, with a choice
  of size (original, 720p, 1080p, 4K), H.264 or H.265, and a 30 or 60 fps ceiling, then
  play, share or save to the gallery. Sources are never modified.
- Import of videos from the device through the system picker, into a new or an
  existing project. HDR footage is tone-mapped on export.
- Credits and licenses screen with the bundled license texts.
- Clip sound: volume from silence to a 12 dB boost, mute, and a fade in and out,
  shown on the waveform and applied in preview and export.
- Sound on two separate audio tracks: import a music or voice file, place it at the
  playhead, cut it, move it, set its level and fades. It stays where it is when the
  clips are edited, and is mixed with the clips' sound in preview and export.
- Noise reduction on a clip's sound with RNNoise, off, light or full. The cleaned
  sound is made once in the background and kept in the project's cache.
- Remove pauses: the clip under the playhead is measured, its silences are found with
  the original's detector, and they are cut out as one undoable edit.
- Clip color: the original's fourteen looks or an imported `.cube` LUT at a chosen
  strength, with exposure, warmth and tint. One lookup table drives both the preview
  (Android 13 or newer) and the export.
- Bookmarks: one tap while recording marks the moment; the editor shows the marks on
  the timeline, steps between them, and adds or removes them with undo.
- Zip project: the recordings, sounds, imported LUTs, editing history and the current
  edit as an OpenTimelineIO file, saved wherever the system file picker allows.
- Recently Deleted with restore and permanent deletion; damaged manifests fall back to
  the previous revision; finished takes missing from a manifest are indexed at launch.

## Not ported yet

More than one video layer, unlinking a clip's sound from its picture, the other transitions (wipe, split screen and so on), title fonts,
colors and motion, Log footage and highlight recovery, subtitles, speed,
crop, video effects, audio restoration models, stabilization, dual camera, follow face,
HDR/Log capture, microphone selection, manual focus, bookmarks.
The project file format is this port's own and is not interchangeable with iOS.
