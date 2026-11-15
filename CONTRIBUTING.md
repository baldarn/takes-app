# Contributing to Takes

Takes is an Android port of [YouRec](https://github.com/antirez/YouRecApp), antirez's
iPhone camera and video editor. It is unofficial and still in progress.

**The original app's behaviour is the reference.** A feature is done when it behaves as
the original does: the same controls, ranges, defaults, numbers and words, in the preview
and in the exported movie. Where the port differs, on purpose or because the work is not
done yet, the difference is recorded in [`docs/PARITY.md`](docs/PARITY.md). A change that
alters behaviour says which lines of the original it follows, or why it differs.

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Build and run

You need:

- JDK 17;
- the Android SDK with platform 36 (`compileSdk` 36; the app runs on Android 8, API 26, or
  newer);
- the NDK 29.0.14206865 and CMake 3.22.1, for the `rnnoise` module.

Install the SDK parts from Android Studio's SDK Manager, or with
`sdkmanager "platforms;android-36" "ndk;29.0.14206865" "cmake;3.22.1"`. Gradle finds the
SDK through `ANDROID_HOME` or a `local.properties` file with `sdk.dir=…` (not committed).

    ./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew :app:installDebug      # onto a running emulator or a connected phone

The first build downloads the Gradle distribution, the libraries from Google Maven and
Maven Central, and sherpa-onnx (about 38 MB) from its GitHub release into
`speech/.sherpa-onnx/`. That download is checked against a SHA-256 and survives
`./gradlew clean`. Nothing else is fetched, and there are no submodules.

An emulator is enough for most of the editor and for the recorder's logic: its camera is
a virtual scene. What a real camera, a microphone or ears decide needs a phone; issues
of that kind carry the `needs-device` label.

### The three app identities

| Build | Name on the phone | Application id | For |
|---|---|---|---|
| `debug` | Takes Dev | `app.takes.dev` | Development. Installs beside the store version, so trying a change never touches your own projects. |
| `smoke` | Takes Smoke | `app.takes.smoke` | The release exactly as it ships (minified), under another name, to try on a phone beside the store version. |
| `release` | Takes | `app.takes` | Google Play and the GitHub releases. |

`release` and `smoke` are signed with a key read from `keystore.properties`, which is not
in the repository. Without it they build unsigned and cannot be installed. Contributors
work with `debug`; nothing in a pull request needs the key.

## Tests and lint

    ./gradlew :core:test                     # the editing model, on the JVM, no Android
    ./gradlew :rnnoise:testDebugUnitTest     # the C library, built for your computer
    ./gradlew :speech:testDebugUnitTest      # model download and recognizer
    ./gradlew :app:lintDebug                 # Android lint

Before a pull request, run what the maintainer and the CI run:

    ./gradlew :core:test :rnnoise:testDebugUnitTest :speech:testDebugUnitTest :app:lintDebug :app:assembleDebug

- `core` holds nearly all the tests. They pin numbers taken from the original: its own
  test cases and the arithmetic of its source. A test says where its numbers come from
  in a comment, as a path in the original's repository with lines, for example
  `// Tests/TransitionTests.swift:14-16` or `YouRec/Transitions.swift:39-164`. New core
  tests do the same.
- The `rnnoise` and `speech` tests run native code on your computer, so they need a C
  compiler and are set up for Linux and macOS. The speech tests that recognize real
  recordings are skipped unless you pass a folder for their models; see
  [`speech/README.md`](speech/README.md).
- `app` has no host tests. Logic that can be tested goes into `core` as a pure
  function, and the screen calls it.
- The pixels of the transition shader are checked by instrumented tests in
  `app/src/androidTest`, on an emulator or a phone. They are not part of the CI; run them
  when you change `TransitionRendering.kt`:

      ./gradlew :app:installDebug :app:installDebugAndroidTest
      adb shell am instrument -w app.takes.dev.test/app.takes.pixels.TransitionPixelRunner

## Where things live

- `core/` — pure Kotlin, no Android. The saved project (`Project.kt`, `ProjectDisk.kt`,
  archive, restore, duplicate), the editing operations (`TimelineEditing.kt`,
  `RangeEditing.kt`, `TransitionLayout.kt`), and the numbers of every tool: transitions
  (`Transition*.kt`), titles (`TitleDesign.kt`, `TitleMotion.kt`), sound (`Audio*.kt`),
  subtitles (`Subtitle*.kt`), crop and color, export bookkeeping (`Export*.kt`), recorder
  maths (`ZoomTransition.kt`, `PinchZoom.kt`, `RecordingSpace.kt`), what the preview
  and the export draw and play (`RenderPlan.kt`), and the track timeline being adopted
  (`Track*.kt`, `PlaybackMap.kt`).
- `app/` — Compose UI, CameraX and Media3. `RecorderScreen.kt` and `CaptureController.kt`
  (camera, zoom, takes), `ProjectsScreen.kt` and `ProjectStore.kt` (the library),
  `EditorScreen.kt` (preview, timeline, tools) with one file per tool panel
  (`TransitionPanel.kt`, `TitlePanel.kt`, `CropPanel.kt`, `SubtitlesPanel.kt`),
  `Exporter.kt` (Media3 Transformer), and the drawing shared by preview and export
  (`TransitionRendering.kt`, `TitleRendering.kt`, `SubtitleRendering.kt`).
- `rnnoise/` — the RNNoise C library with a small Kotlin API, built with the NDK.
- `speech/` — Whisper through sherpa-onnx, and the verified download of its model.
- `docs/` — `PARITY.md` (what the original does and where the port stands),
  `TRACKS-PLAN.md` (the move to the original's track timeline, in stages), the reviews
  of the recorder, the transitions and the subtitles against the original, and research
  notes.
- `fastlane/metadata/` — the store listing and its screenshots.

Strings are in `app/src/main/res/values/` (English) and `values-it/` (Italian); the
recorder's are in `strings_recorder.xml` in both.

## Picking work

- **Issues.** Look for `good first issue` and `help wanted`. `core-only` issues need no
  Android knowledge and no device. Comment on an issue before starting, so two people do
  not do the same thing.
- **[`docs/PARITY.md`](docs/PARITY.md).** Every row that is MISSING, PARTIAL or CORE ONLY
  is work, with its place in the original, its size and whether an emulator is enough.
  If no issue exists for a row, open one with the "Parity gap" form.
- **The track timeline.** The saved edit is moving from a sequence of clips to the
  original's independent tracks, in the stages of
  [`docs/TRACKS-PLAN.md`](docs/TRACKS-PLAN.md). Each stage has a tracking issue.

**Areas in flux: ask first.** These files are being changed by work under way, and a
pull request that touches them without agreement will probably conflict:
`EditorScreen.kt`, `Exporter.kt`, `Titles.kt`, `RecorderScreen.kt`,
`CaptureController.kt` in `app`; `Project.kt`, `TimelineEditing.kt`, `RangeEditing.kt`
and every `Track*.kt` in `core`. New files in `core` with their tests, documents,
strings and accessibility are the safest places to begin.

## Pull requests

- **One change, small.** A pull request does one thing and is one commit when it is
  merged. Separate things go in separate pull requests.
- **Tests for core logic.** A change in `core` comes with tests; a change in `app` that
  contains arithmetic moves the arithmetic to `core` and tests it there.
- **Follow the original, and say where.** Cite the original's file and lines (paths
  relative to its repository) in the description. If the change differs from the
  original, say why.
- **Update `docs/PARITY.md`** when a gap closes or narrows, in the same pull request.
- **English and Italian.** Every new or changed string is in both `values/` and
  `values-it/`. Controls have a label for screen readers.
- **Preview and export agree.** What a tool shows in the editor is what the exported
  movie contains.
- **No new permission, dependency or network use without an issue first.** The app works
  offline; the one thing it downloads is the speech model, when the person asks for it.
  No analytics, no crash reporting, no update checks.
- **No change to the saved project format without an issue first.** Projects made by
  released versions must keep opening. A change of format raises the schema number
  (`Project.kt`), keeps old steps readable byte for byte (`ProjectFormatTest.kt`), and
  says what older versions of the app do with the new project. See
  [`docs/TRANSITIONS-MIGRATION.md`](docs/TRANSITIONS-MIGRATION.md) for how an earlier
  step was done.
- **Say how you checked it:** host tests, emulator, or a phone, and what you could not
  check.
- No personal data, device serials or paths of your own computer in code, commits,
  descriptions or attached files.

## Commits

One short English sentence in the imperative saying what the change does, with the
reason or the visible effect when that helps. No prefixes, no issue numbers in the
subject, no trailers. From the history:

    Pause the take when the app goes to the background, as the original does, where it was finished
    Add pause detection to core
    Reset the divider with the framing of a split screen

Tidy fixup commits on your branch before the merge.

## Review and merge

- Pull requests are reviewed by the maintainer.
- The CI (`.github/workflows/ci.yml`) must be green: it runs the command under
  [Tests and lint](#tests-and-lint).
- Merging is "Rebase and merge": the history of `main` is linear, one commit per change.
  The branch is deleted after the merge.
- `main` cannot be deleted or force-pushed.
- The maintainer pushes directly to `main` only for the version commit with its release
  tag, the store listing and privacy texts, and corrections to documents under `docs/`.
  Everything else goes through a pull request.

## Releases

Releases are made by the maintainer: the version commit and tag, the GitHub release with
its APK, and the upload to Google Play, each signed with a key that is not in the
repository. The store listing and the changelogs under `fastlane/metadata/` are written
at release time; a pull request does not need to touch them.

## Licence

Takes is under the MIT licence: see [`LICENSE`](LICENSE). The file carries the copyright
notice of the original app, from which this port is derived, and the original is MIT as
well. The YouRec name and icon are reserved by their author and are not used here.

Contributions are accepted under the same licence: by opening a pull request you agree
that your contribution is licensed under the MIT licence of this repository. There is no
contributor agreement to sign, and you keep the copyright of what you write.

Third-party material keeps its own licence. The libraries, fonts and model the app
bundles or downloads are credited in the app's Credits screen, from the texts in
`app/src/main/res/raw/`. A pull request that adds third-party code, a font, a model or
other assets names its licence and adds it to those credits. The original's repository
also contains third-party code under other licences (its `ThirdParty/` folder and
`THIRD_PARTY_NOTICES.md`): check there before porting anything that is not the
original's own source.

## Security and privacy

Report vulnerabilities privately: see [`SECURITY.md`](SECURITY.md). What the app does
with data is in [`PRIVACY.md`](PRIVACY.md).
