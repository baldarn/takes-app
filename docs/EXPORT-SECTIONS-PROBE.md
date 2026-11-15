# Can the export be made in sections that survive a pause?

A probe of 2026-11-15 on `main` at `d7ab7a1`, Media3 1.11.1 (`gradle/libs.versions.toml`). The original pauses an export when the app leaves the foreground and goes on from the twelve-second sections already saved (`YouRec/ExportCheckpoint.swift:96-146`); the arithmetic and the bookkeeping are ported (`core/.../ExportCheckpoint.kt`, `ExportProgress.kt`) but the Android exporter makes one pass over the whole movie (`app/.../Exporter.kt`). This asks whether Media3 can do the rest.

**Yes, with two conditions that also repair faults of today's export** (`docs/EXPORT-TWO-SEQUENCES.md`): the movie must get its frame times from a sequence of its own, and a deck must hold a black still, not a Media3 gap, where it has no clip. With those, sections of twelve seconds came out with exactly the frames they should hold, joined without encoding into a movie that is frame for frame the one pass, with sound that is sample for sample the one pass.

Everything was run on an emulator (Android 17, API 37, x86_64) with the software encoders `c2.android.avc.encoder`, `c2.android.hevc.encoder` and `c2.android.aac.encoder`, on a machine that was busy with other work the whole time. Movies were read back with ffmpeg and ffprobe. Nothing was run on a phone; section 7 lists what that leaves open.

## 1. What was tried

The probe is a build of its own in `probes/export/` (its own `settings.gradle.kts`; the app's build files are untouched). It borrows the app's version catalog and its `core` module, so it runs on the Media3 the app resolves and on the real `ExportSections`.

```
sh probes/export/tools/make-clips.sh <folder>           # footage whose frames carry their numbers
./gradlew -p probes/export :probe:assembleDebug
adb install probes/export/probe/build/outputs/apk/debug/probe-debug.apk
# the footage goes to files/in of app.takes.probe (adb push to /data/local/tmp, then run-as cp)
adb shell am instrument -w -e scenario sections -e fps 30 app.takes.probe/.ProbeInstrumentation
python3 probes/export/tools/verify.py picture joined-30.mp4 --plan movie --fps 30
python3 probes/export/tools/verify.py sound joined-30.mp4 --fps 30 --ref whole-clock-30.mp4
```

**The movie.** 40 s and 9 frames (40.3 s at 30 fps), on two decks as the app lays out clips that overlap: a clip on the first deck from 0 to 13 s, one on the second from 11 to 30 s, one on the first from 29 s to the end; music under all of it as a third, audio-only sequence. The sections are 0-12, 12-24, 24-36 and 36-40.3 s: the first boundary falls inside an overlap, the second where only the second deck plays, the third where only the first does. Two things are drawn over the decks, both depending on time: the frame is made from the two decks by an effect that asks the plan which deck plays (it stands for `TransitionEffect`), and an overlay writes the number of the movie's frame along the bottom (it stands for the titles and subtitles). Every frame of the footage carries its own number, so a decoded frame of the movie says which source frame it shows and which frame of the movie the overlay took it for.

**A section** is a composition of its own for the stretch [from, to): every clip cut to the part that lies in the stretch (`ClippingConfiguration` moved by the same amount as the stretch begins after the clip), stills where a deck is empty, a clock of exactly `ExportSections.pictures(lengthTicks, fps)` frames, and the effects told `from` (`probes/export/.../Timeline.kt`, `Compositions.of`). Media3 has no way to render a range of a composition; `Transformer.start` takes a whole one.

**The join** reads the sections with `MediaExtractor` and writes them end to end with Media3's `Mp4Muxer` (and, to compare, the platform's `MediaMuxer`), giving frame n of the movie the time n / fps (`probes/export/.../Joiner.kt`).

## 2. The answers

### 2.1 Can Transformer render an exact time range? Yes

Frame counts of the sections, read from the files (`ExportSections.isComplete` and `canJoin` true for every one):

| Frame rate | Movie | Sections | Frames in each | In the joined movie |
|---|---|---|---|---|
| 30 | 40.300 s | 4 | 360, 360, 360, 129 | 1209 of 1209 on the grid |
| 30, length off the grid | 25.310 s | 3 | 360, 360, 40 | 760 of 760 on the grid (the last section is 39.3 frames long and holds 40, as `ExportSections.pictures` says; the picture is 25.333 s) |
| 24 | 25.375 s | 3 | 288, 288, 33 | 609 of 609 on the grid |
| 25 | 25.360 s | 3 | 300, 300, 34 | 634 of 634 on the grid |
| 60 | 25.150 s | 3 | 720, 720, 69 | 1509 of 1509 on the grid |
| 30, HEVC | 25.300 s | 3 | 360, 360, 39 | 759 of 759 on the grid |

The shorter movies end inside the second clip, so they have the first two joins (inside an overlap, and where the second deck plays alone) and a short last section. In each, every source frame due is shown ("shown minus due" 0 throughout), the overlay's number is the frame's own on every frame, and the sound of the joined movie is the sound-only pass sample for sample (checked at 60: 0 of 1 208 768 samples differ). Each has the one wrong frame described below, at the first frame of the second clip.

The emulator's HEVC encoder does not take 640 by 360: Media3 fell back to 512 by 288, the same in all three sections, and the join went through.

Read back frame by frame, the joined 30 fps movie has:

- 1209 frames, every one within a microsecond of n / 30 (in the file: 3000 units of 1/90 000 s each, no exception);
- the overlay's number equal to the frame's own number on all 1209, so the effects were told the right time in every section;
- on each deck, in each stretch of the plan, every source frame that is due and no other: 330 of 330, 60 of 60, 480 of 480, 30 of 30, 309 of 309 on the stretches where the numbers can be read, "shown minus due" 0 on all of them. The frames on both sides of the three joins (359/360, 719/720, 1079/1080) are the right ones;
- **one frame wrong**: frame 332 (11.067 s, the third frame after the second deck begins to play) shows the first deck's picture where the second deck's should be. It is not a fault of the sections nor of the clock or the stills: the one-pass export of the same movie has it, and so has the movie built as the app builds it today. See section 6.

What made it exact, each found by a run that was not:

1. **A clock.** A video-only sequence before the decks, one black still with `setFrameRate(fps)` and `setDurationUs(frames / fps)`. Media3 composes one frame per frame of its first sequence (`DefaultVideoCompositor.java:143-145`, `:314`), so the section has exactly that many frames at exactly those times, whatever the footage. Without it a section has the frame times of whatever lies on the first deck, 30 a second where that is empty.
2. **Stills, not gaps.** With Media3's own gaps the clock gave the right count but frames were black or two ahead of their time at every change between a clip and a gap (`docs/EXPORT-TWO-SEQUENCES.md`, cause B), which a section has more of than a whole movie: a section that begins inside a gap begins with one.
3. **A `Presentation` per item**, since a still and a clip have different sizes going in.
4. **The time offset in every effect that reads the time.** Frame times in a section start at zero.
5. **Cuts in microseconds rounded down**, as `cutMicros` already does (`Exporter.kt`), for the source position a section starts a clip at.

Not possible: `Transformer.resume(composition, outputFilePath, oldFilePath)` (`Transformer.java:1183-1215`), Media3's own continuation of a cancelled export. It takes a composition of one sequence without speed changes ("The Composition contains a single EditedMediaItemSequence having continuous audio and video tracks", "do not contain speed changing effects"); the app's has two decks, sound tracks and clip speed.

### 2.2 Can the sections be joined without encoding? The picture yes; the sound must be made once for the whole movie

**Picture.** The joined movie has the same 1209 frames as listed above: no frame dropped or doubled at 12, 24 or 36 s. Its video track is 40.300 s. Both muxers gave the same picture and the same sound; the files differ in size (Media3's `Mp4Muxer` 6 160 277 bytes, `MediaMuxer` 5 777 958) and not in content.

The join needs every section to carry the same codec setup data, since an MP4 track holds one. The four sections had it byte for byte (`csd-0` and `csd-1` hashed: `2bc160d86be7+f9988e417843` in all), and the same profile, level, size and colour (BT.709, limited range, SDR transfer).

**Sound, section by section: no.** Sections rendered with their own sound and joined the same way gave a broken track:

- 1857 AAC packets where the one pass has 1893, 39.583 s of sound for a 40.3 s movie (36 864 samples short);
- packet times 85 to 1115 samples apart at the joins where they must be 1024;
- the sound differs from the one pass from 11.977 s on (1 325 056 of 1 899 968 samples), with the largest jump between two samples of the whole track 22 ms after the first join;
- the third section's sound came out at 44 100 Hz, the others at 48 000: in that section the first deck begins empty, and 44 100 Hz is the format Media3 gives a track that starts with silence (`SequenceAssetLoader.java:63-68`); so the sections do not even agree on a format. (The sound-only pass of a movie that opens on an empty first deck would take that rate too, as today's one pass does.)

The reasons are in the format: 12 s is 562.5 AAC frames at 48 kHz, every encoder run begins with its own lead-in (the first packet of each section is at -33 333 µs) and ends with a padded frame, and neighbouring AAC frames overlap, so two runs do not meet.

**Sound once, as the original does: yes.** One audio-only pass over the whole composition (the same sequences, `setRemoveVideo`, no clock), its packets copied into the joined movie with their own times. Decoded, the joined movie's sound is **identical to the one pass, sample for sample**: 0 of 1 936 832 samples differ; 1893 packets 1024 apart, the first at -1600 (the encoder's lead-in, as in the one pass). So nothing is lost and nothing gained against today's export: the sound is 50.7 ms longer than the picture in both (1 936 832 samples for 1 934 400), and each clip's sound lies where it lies today.

### 2.3 What the encoder does at the start of a section

| | Found (software encoders) |
|---|---|
| Key frames | Every section begins with one; then one every 30 frames, at every frame rate: 12 in a 12 s section at 30 fps, 24 at 60, and at 24 fps the first section's key frames are at 0, 30, … 270 and the second section begins at frame 288 with one of its own. Media3 asks for one a second and tells the encoder 30 frames a second, since the first input, the clock's still, states no frame rate (`DefaultEncoderFactory.java:59`, `:328-329`). The bitrate Media3 picks comes from the same 30 (`:615`, `:959-963`: width × height × frame rate × 0.14), so a 60 fps export gets the bitrate of a 30 fps one unless the app sets it (`VideoEncoderSettings.Builder.setBitrate`). At 30 fps the joined movie has 41 key frames in the same places as the one pass |
| B-frames | None: decode order is presentation order in every file. Media3 asks for none unless `VideoEncoderSettings.setMaxBFrames` is set |
| Times | Each section starts at 0 and steps by exactly one frame. The join gives frame n the time n / fps from its number, not from the section's own times, so rounding does not add up over sections |
| Colour, size, profile, rotation | Equal in all sections and equal to the one pass: H.264 Constrained Baseline level 4.1, 640 by 360, BT.709 limited, no rotation |
| Encoded size | The picture of the four sections is 5 098 573 bytes, of the one pass 4 991 760: 2.1 % more, the rate control starting again in every section. Each file the transformer writes also holds about 400 000 bytes of reserved room (a 12 s section is 1 890 313 bytes on disk for 1 490 261 of picture), which the join does not carry over; the space check before a section should count it |

**An interrupted section is a movie that plays.** Media3's muxer writes the index of the file again every second of media (`Mp4Writer.java:50`, `MOOV_BOX_UPDATE_INTERVAL_US`), so that a crash leaves something readable. For a checkpoint that is the trap:

- `Transformer.cancel()` 45 s into a pass: a file of 1 867 102 bytes that opens, with 341 frames from 0 to 11.333 s, a key frame first, the same setup data as a whole section.
- The process killed (`am force-stop`) 50 s into a pass: a file of 2 015 485 bytes that opens, with 361 frames from 0 to 12.000 s.
- Earlier than the first encoded frame there is no file at all (a cancel after 8 s and a kill after 20 s on the busy machine left none).

So "the file opens" proves nothing. What tells a whole section is its frame count, `ExportSections.isComplete`, and the rule the core already has: an encoder writes under `Processing/` and the file is moved into `.paused-export` only when it is complete (`ExportCheckpoint.publish`).

### 2.4 Time against the one pass

The machine was shared and its load changed by the minute, so these say "about the same" and no more.

| | Wall time |
|---|---|
| One pass, clock and stills, picture and sound | 188.7 s |
| One pass, as the app builds it today (two decks, gaps), picture and sound | 198.0 s |
| Sections: sound once | 28.6 s |
| Sections: picture, 4 sections (25.2 + 31.4 + 41.8 + 21.0 s) | 119.5 s |
| Sections: join | 31.0 s (`Mp4Muxer`), 35.6 s (`MediaMuxer`) |
| Sections in all | 179.1 s |

- Starting a transformer four times in place of once did not cost anything that could be measured here.
- The join took as long as a section in this run, for 6 MB; in the runs of the shorter movies it took 3.9 to 15.5 s. That is far more than copying a few thousand samples should take and moved with the machine's load; it has to be measured on a phone before the bar's share for it (`ExportProgress.Step.AssembleMovie`) is trusted.
- The clock and the stills did not make the one pass slower than today's layout (188.7 against 198.0 s; an earlier pair on a quieter machine, the clock then with gaps, gave 94.2 and 119.6 s).
- At 640 by 360 the software encoder is the cost. At 1080p on a phone the extra pass through the compositor for a movie that has no transition (which today goes straight from decoder to encoder) is the thing to measure.

## 3. Recommended design

Tied to the core API as it stands; nothing in `ExportSections` or `ExportCheckpoint` needs to change.

1. **Frame rate.** The movie has `settings.frameRate` frames a second, all on the grid. `ExportSections.frameTicks` already demands a rate that divides 240 000 (24, 25, 30, 50, 60 do).
2. **One description of the movie, any stretch of it.** A function from (project, from, to) to a `Composition`: the clock, the decks with stills for the empty stretches, each clip cut to the stretch, the time-dependent effects given `from`. The whole movie is the stretch from 0 to the end. This is the "plan" S0 of `docs/TRACKS-PLAN.md` asks for, and `Compositions.of` in the probe is its shape. In the app the effects that read the time are `TransitionEffect` (`TransitionRendering.kt:449`), `TitleOverlay`, `SubtitleOverlay`, `FramingEffect` (its `begins`) and `SoundProcessor`; the last is not needed in a section, which has no sound.
3. **Order of work**, as `ExportCheckpoint.swift:96-146`:
   1. `checkpoint.prepare(project)`; the bar starts at `checkpoint.startingProgress(total)`.
   2. If `checkpoint.audioFile` is missing: one audio-only pass over the whole movie into `Processing/`, then `checkpoint.publish(partial, checkpoint.audioFile)`. (The noise reduction and the peak, `savePeak`, come before it as today.)
   3. For each of `checkpoint.remaining(total)`: a video-only pass of the section into `Processing/`, the checks of point 5, then `checkpoint.publish(partial, checkpoint.section(index))`. Progress is `ExportProgress.picture(section.start, value, section.length, total)`.
   4. The join into `Exports/.name.mp4`, the checks of point 6, the rename. `checkpoint.finish(paused, published)` decides what happens to the saved work.
4. **Section length**: 12 s, `ExportSections.SECONDS`. It is a whole number of seconds, so key frames fall where they do in one pass, and a whole number of frames at every supported rate.
5. **Before a section is published**: it opens (`MediaExtractor`), has one video track, `ExportSections.isComplete(samples, section.lengthTicks, fps)`, its first sample is a key frame, and its setup data, size, profile and colour equal those of section 0 (or of a note kept beside the ticket, so a section made after an update of the system is not joined to older ones). A section that fails is deleted and rendered again; the original does the same for a section one frame short (`ExportCheckpoint.swift:119-125`).
6. **The join**: `androidx.media3.muxer.Mp4Muxer` (already on the class path through the transformer), the picture samples of every section in order with the time `round(n × 1e6 / fps)` for frame n counted across the sections, the sound samples with their own times, interleaved by time, and an empty end-of-stream sample at the movie's length so the last frame gets its duration. `ExportSections.canJoin` for every section first. After it: `ExportSections.durationMatches`, and decoding the frame at `lastPictureTime`.
7. **A pause** (the app leaves the foreground, the disk is full): `Transformer.cancel()`, delete the file in `Processing/`, `finish(paused = true, …)`. Nothing unfinished is ever in `.paused-export`, and `ExportCheckpoint.reclaim` empties `Processing/` at the next start.
8. **No sound per section, ever.** Sections are picture only; the sound is the one file.
9. **Whether the app is allowed to render in the background at all** (a foreground service) is a separate question from this one; the original's answer is to pause.

## 4. What the design costs and risks

- Every export goes through `MultipleInputVideoGraph`, also those with one chain and no transition. Seeking and the editor's preview are not concerned (`docs/COMPOSITION-PLAYER.md`); the maintainers' remark there that this graph "will likely be deprecated" is a risk for a later Media3.
- A still (freeze) keeps "the last frame drawn" of a deck (`TransitionEffect.keep`). A section that begins inside a freeze has not drawn it. Either the section starts its decks early enough to draw it, or the held frame becomes an item of its own (a picture read once from the clip). Not tried.
- A clip with a speed change cut by a section boundary: the source position is the clip's start plus the time into it times its rate. Not tried; the probe's clips play at their own speed.
- The rate control starts again every 12 s: the movie was 2 % larger here, and the picture may breathe at the joins on a hardware encoder at a tight bitrate.

## 5. What does not work

- Sound per section (section 2.2).
- Media3's gaps in a deck (section 2.1, point 2).
- `Transformer.resume` for this composition (section 2.1).
- One `Presentation` shared by the items of the decks.

## 6. Not determined

- **The one wrong frame.** Within the first three frames after the second deck begins its clip at 11 s, while the first deck is also drawn, one frame shows the first deck's picture in the second deck's place: in every rendering of this movie, in sections and in one pass, at every frame rate, with stills and with gaps, with the clock and in today's layout. The eleven-second movies of `docs/EXPORT-TWO-SEQUENCES.md`, whose second deck begins after 2 s, never had it. What is known and what is not is in section 8 there; it needs a phone.
- **Footage at half the export's frame rate** (30 fps in a 60 fps movie) is shown unevenly unless the decks' frame times are nudged (`LaterEffect`, change 4 of `docs/EXPORT-TWO-SEQUENCES.md`), and that nudge made the wrong frame above last for the whole overlap in this movie. The section runs in this document were made without it.
- Whether a section may begin inside a freeze or a sped-up clip (section 4).
- Why the join took 31 s in one run and 4 to 15 s in the others.
- The sound's place against the picture (25 to 39 ms late per clip, in one pass and in sections alike; `docs/EXPORT-TWO-SEQUENCES.md`, section 8).

## 7. What needs a real phone

Everything about the encoder:

- that a hardware encoder gives the same setup data (SPS/PPS) on every start with the same settings. If it does not, the join cannot be a plain copy and each section's parameter sets would have to travel in the samples;
- B-frames: none here. Some hardware HEVC encoders make them unasked; the join then has to write decode times as the original does (`ExportCheckpoint.swift:254-256`), which `Mp4Muxer` does from presentation times, and `isComplete` still counts samples;
- that the first frame of every section is a key frame and that a hardware encoder keeps exactly n frames when it is given n (some drop the last on end of stream; `isComplete` catches it and the original has the same guard);
- the time: starting a hardware codec four or forty times, the join on real storage, the compositor at 1080p and 4K, HDR footage tone-mapped;
- quality at the joins at the bitrate Media3 picks for the phone's encoder.

## 8. As carried out in the app's one pass

Added when the clock and the stills went into `Exporter.kt` (`docs/EXPORT-TWO-SEQUENCES.md`, section 9). Sections are still not built. What was found there bears on the design of section 3:

- **A deck carries the picture alone.** A deck with sound that opens on a still stops the export with "Asset loader error" whenever it takes its picture before the first sequence with sound has given its own (`SequenceAssetLoader.java:341-348`). The app's decks are now video only and the clips' sound plays from sequences of sound alone. A section, which is picture only, never had the fault; the one pass of sound over the whole movie (point 3.2) is those sound sequences and nothing else.
- **The clock's times are not on the grid.** They drift a microsecond every thousand frames at 30 a second (`ConstantRateTimestampIterator`). The app puts the composed frame's time on the grid before any effect reads it (`FrameTimeEffect`). In a section the same effect has to add where the section begins before it rounds, and the join already numbers the frames itself (point 3.6).
- **The frame rate** (point 3.1) is not the setting but `TransitionScene.frameRate(footage, ceiling)`: the fastest footage under the ceiling, one of 24, 25, 30, 50, 60, all of which `ExportSections.frameTicks` takes. The clock's length is `ExportSections.pictures` of the movie.
- **A movie of plain cuts has no clock** and keeps the frame times of its footage. Sections need the clock for every movie; such a movie would then change its frames where its footage is not all of one rate, which is to be decided when sections are built.
- **The held frame of a still** is kept from the frame on screen at the moment the model names. A section that begins inside a freeze still has not drawn it (section 4).

## 9. As built

Added when sections went into the app (`Exporter.kt`, `ExportMovie.kt`, `ExportJoin.kt`, `ExportCheck.kt`, `ExportPanel.kt`). Sections 1 to 8 are as they were written. Run on the emulator of the probe and on a Xiaomi Mi 10T (Android 12, hardware encoders) at 1080p.

**The design of section 3, with these differences.**

- **A section is drawn with half a second of the movie before it and after it, and those frames are left out before the encoder** (`ExportMovie.picture`, `SectionFramesEffect`). The clock runs over the wider stretch, every effect is told the movie's own time, and the last effect lets through the section's frames alone, timed from zero. So the frame nearest to a section's first one may be one that began before the section, and a still that a section begins inside is drawn again from before the frame it holds: the two things section 4 left open. The effects themselves did not change.
- **The sound and the peak are made beside the picture, not before it.** On the phone the sound of a forty-second movie took 10 s and its picture 10 s; one after the other an export took 25 s where the single pass took 13 s, side by side it takes 12 s.
- **Every movie goes through the clock**, the ones of plain cuts as well, which are then on one frame grid at `TransitionScene.frameRate`. Footage of one rate whose frames are on the grid comes out frame for frame as before (a camera's are not: see below); footage of several rates (25, 30 and 60 in one movie) used to keep each clip's own frame times and now repeats frames of the slower clips, as a movie with a transition does. On the phone a seventy-second movie of plain cuts took 17 s in sections and 23 s in one pass.
- **The join carries the orientation over** (`Mp4OrientationData`): an upright movie is encoded lying down and marked as turned, and the mark belongs to the file, not to the track's format.
- **The setup data of the sections is compared when they are joined**, not when each is saved; sections that do not agree are all rendered again, once.

**Measured.** Eleven projects (dissolves at 25, 30 and 60 frames a second, a still across a boundary, a flash cut, a wipe, a turned moving crop with a moving title and subtitles, clips at double and half speed, noise reduction, two sound tracks, plain cuts; 8 to 70 s), each exported by the single pass and in sections, on the emulator and on the phone: the same number of frames at the same times, the same source frame on every frame, and the sound equal sample for sample, in all but the movie of plain cuts at three rates, which differs as said above. The phone's hardware encoder gave every section its whole count of frames, a key frame first and the same setup data.

At 4K on the phone (H.265 and H.264) the forty-second movie with dissolves was again equal frame for frame and sample for sample; it took 18.0 and 16.4 s in sections against 15.9 and 15.8 s in one pass, the sixty-six-second one 27.7 and 25.5 s against 24.1 and 23.8 s, and the seventy seconds of plain cuts 24.9 and 24.2 s against 25.4 and 27.2 s. A section of twelve seconds was 4.1 to 4.9 MB there, of footage that is easy to encode.

**Interrupted.** On the emulator: Home and `am force-stop` in the middle of a section, at a section boundary, during the sound pass and during the join, then back and "Resume export"; Home and then a kill in one export. On the phone at 4K: Home in the middle of a section and during the join, and a kill in the middle of a section. Every resumed movie decoded to the same pictures and the same samples as the uninterrupted one. Nothing unfinished was in `.paused-export` at any of those moments; what a kill leaves in `Processing` is removed when the project's editor opens again.

**The phone's own camera footage.** Run on a Samsung Galaxy A54 (Android 16) with takes recorded in the app: the rear camera at 1080p and at 4K and the front camera, every take with a slow zoom so that no two frames are alike. The camera does not record on a grid: the rear camera gave 30.012 to 30.014 frames a second and the front one 30.001, the frames 33.2 to 33.4 ms apart (36.7 ms once).

- **A movie with transitions** (59.5 s: a dissolve across the first boundary, a wipe, a clip at double speed across the second, a fade through black; and a 4K movie with a dissolve): the single pass and the sections gave the same number of frames (1785; 1787 at 4K) at the same times, all on the grid, the same frame of the recording on every frame that one clip fills (1518 of 1518; 1723 of 1723 at 4K), no frame of the single pass missing or shown twice at any boundary, and the sound equal sample for sample.
- **A movie of plain cuts** (73 s, cuts on two boundaries and boundaries inside clips) differs from the single pass as the clock makes it differ, and in nothing else. The single pass kept each frame at the time the camera gave it (2191 frames, 21 to 65 ms apart at the cuts); the sections have 2192 on the grid, the same frames in the same order, each within 33 ms of where it was, and the sound is equal sample for sample. What the grid costs on real footage: a clip cut between two frames shows its first frame twice (the frame at the cut is the nearest one, which begins up to a frame later) and the frame at the next cut may still be the last of the clip before it; a rear-camera clip loses one frame in about eighty seconds, the camera being that much faster than 30; and a clip whose frames fall halfway between the grid's, where the camera's unevenness decides which is nearer, repeats and loses frames in pairs: a fourteen-second front-camera clip cut at such a place repeated three frames and lost three, about one cut position in a hundred does so, and the others none. A movie with a transition has always done all of this. Working the nearest frame out from the recording's frame times gives exactly the frames the phone drew, so nothing of it comes from the sections.
- **Interrupted**: Home in the middle of the third section of the movie with transitions, and `am force-stop` in the middle of the fourth of the plain one, then "Resume export": both movies decode to the frames and the samples of the uninterrupted ones, hash for hash.
- **The check** passed on every one of these movies; none was refused for its length or its last picture.
- **Time**: the sections were slower here than the single pass, 34 s against 20 s for the 73 s of plain cuts, 30 s against 20 s for the movie with transitions and 55 s against 41 s at 4K: about a second and a half more for every section.
- **Sound against picture** is the single pass's, the sound being the same: in the movie of plain cuts each clip's sound lies 10 to 42 ms after where the edit puts it, more with every clip (section 6), and its picture within half a frame of it.

**Still open.** A device that fills up in the middle of an export was not run.
