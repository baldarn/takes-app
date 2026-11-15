# Takes

A camera and video editor for Android: record a project take by take, then cut it,
grade it, add titles and sound, and export, all on the phone. Takes is a Kotlin
rewrite of [YouRec](https://github.com/antirez/YouRecApp), antirez's iPhone app. The
YouRec name and icon are reserved by its author, so this port has its own.

<p align="center">
  <a href="fastlane/metadata/android/en-US/images/phoneScreenshots/1-recorder.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1-recorder.png" width="250" alt="Recorder while recording: timer, project name, microphone in use, zoom presets, rear and front camera buttons, stop, pause and mark."></a>
  <a href="fastlane/metadata/android/en-US/images/phoneScreenshots/2-editor.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2-editor.png" width="250" alt="Editor with a title over the picture, the thumbnail timeline with its waveform, a fade and a dissolve, and the editing tools below."></a>
  <a href="fastlane/metadata/android/en-US/images/phoneScreenshots/3-projects.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3-projects.png" width="250" alt="Projects list with the actions for each project."></a>
</p>

## Install

- [Google Play](https://play.google.com/store/apps/details?id=app.takes)
- or the APK attached to each [GitHub release](https://github.com/baldarn/takes-app/releases).
  The two are signed with different keys, so one cannot update the other.

Android 8 or newer. Recordings, projects and what is said in them stay on the device.
The app uses the network for one thing: downloading the speech model for subtitles (about
161 MB, from Hugging Face) when you ask for it.

The port is still in progress. What works today is listed under
[What it does](#what-it-does), what is missing under [Not ported yet](#not-ported-yet).

## What it does

- Projects as the durable unit: record, pause, resume, stop, and continue the same
  project later, including after the app restarts. Each pause or stop finalizes one
  immutable segment file.
- Camera switching while recording, kept inside the same segment with a `camera` event.
- Per-camera zoom memory, zoom presets, timed zoom transitions (tap: saved time,
  double tap: one second), pinch to zoom.
- Recordings stay outside editor undo/redo, as in the original model.
- Unused takes: footage that is in the project but no longer in the edit can be put
  back at the playhead, in a chosen order, as one Undo step.
- Record here: from the editor, new takes go into the edit at the playhead instead of
  at the end, and one Undo takes the whole run back out.
- Microphone choice on Android 12 or newer: automatic, the phone, or a Bluetooth, wired
  or USB headset, with a warning when the recording comes from a different input.
- English and Italian, following the phone's language.
- Basic editor: preview of the edit, scrolling thumbnail timeline under a fixed
  playhead with audio waveforms, split at the playhead, trim handles on the current
  clip, delete and reorder clips, persistent undo/redo.
- The original's nine transitions between neighboring clips and at the start and
  the end of the movie: dissolve, fade through a color, wipe, split screen,
  Hitchcock, whip pan, flash cut, still and defocus, each with its settings, a
  hold, and its own audio timing (J and L cuts). Picture and sound are identical in
  preview and export; before Android 13 the preview shows the styles that need a
  shader as dissolves.
- Titles attached to their footage, identical in preview and export.
- Title design and motion as in the original: its seven font families and the phone's
  own, solid or gradient fill, outline, shadow, a box behind the words or each line,
  alignment and spacing, entrance and exit (fade, drift, scale, wipe), placing by
  dragging, and channel styles saved for every project.
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
- Crop and reframing: zoom into a clip and move the window, with the picture following
  the sliders live; a cropped clip fills the movie's frame.
- Clip speed from a quarter to four times, picture and sound, with the pitch kept, in
  preview and export.
- Subtitles: the speech of the clips is transcribed on the phone with Whisper (through
  sherpa-onnx), in the language you choose or one it detects. The model is downloaded
  once on request, verified, kept out of backups, and can be removed. Captions follow
  their footage through splits, trims, moves and speed changes; their text can be
  corrected or deleted, regrouped by words per caption, and styled (plain, bold, box or
  highlight, six colors, five fonts, size and position). Preview and export draw them
  with the same code, and everything done in the tool is one Undo step.
- Duplicate a project with its recordings and editing history.
- Bookmarks: one tap while recording marks the moment; the editor shows the marks on
  the timeline, steps between them, and adds or removes them with undo.
- Zip project: the recordings, sounds, imported LUTs, editing history and the current
  edit as an OpenTimelineIO file, saved wherever the system file picker allows.
  Opening such a ZIP brings the project back as a new one, after checking every file.
- Recently Deleted with restore and permanent deletion; damaged manifests fall back to
  the previous revision; finished takes missing from a manifest are indexed at launch.

## Not ported yet

More than one video layer, unlinking a clip's sound from its picture,
importing fonts for titles, Log footage
and highlight recovery, subtitles for sound on the audio tracks or for a selected range
only, placing captions by dragging them, caption designs and motion, rotated and animated crops, video
effects, audio restoration models, stabilization, dual camera, follow face, HDR and Log
capture, manual focus.

The project file format is this port's own and is not interchangeable with the iPhone
app.

## Build

Needs JDK 17, the Android SDK (API 36), and the NDK (29.0.14206865) with CMake 3.22.1
for the `rnnoise` module.

    ./gradlew :core:test            # project model, storage and zoom curve
    ./gradlew :speech:testDebugUnitTest   # speech model download and recognizer; see speech/README.md
    ./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk

## Layout

- `core/` — pure Kotlin, no Android. `Project` (sources, capture ledger, undo/redo
  snapshots), `ProjectDisk` (atomic manifest, content-addressed `History/`, revisions,
  Recently Deleted), `ZoomTransition` (quintic ease on log magnification).
  `TimelineEditing` (split, delete, move, trim, transitions, titles, bookmarks), `WaveformBuilder` (audio peaks).
- `rnnoise/` — the RNNoise C library with a small Kotlin API, built with the NDK.
- `speech/` — speech recognition for subtitles: Whisper through sherpa-onnx, and the
  verified download of its model. The first build fetches sherpa-onnx from its GitHub release.
- `app/` — Compose UI, CameraX and Media3. `CaptureController` owns the camera, zoom and
  take lifecycle; `ProjectStore` owns the library; `Exporter` renders an edit with
  Media3 Transformer; `RecorderScreen`, `ProjectsScreen` and `EditorScreen`.

## License

MIT, like the original: see [LICENSE](LICENSE). The licenses of the bundled libraries
are in the app's Credits screen.
