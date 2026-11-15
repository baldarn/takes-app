# The export with two sequences: what goes wrong and why

Findings of 2026-11-15 on `main` at `d7ab7a1`, Media3 1.11.1 (`gradle/libs.versions.toml`). Everything below was run: on an emulator (Android 17, API 37, x86_64; software H.264 and AAC encoders `c2.android.avc.encoder`, `c2.android.aac.encoder`), with the probe in `probes/export/`, and the movies were read back frame by frame with ffmpeg. Nothing was run on a phone; section 7 says what that leaves open.

The app's files were only read. `Exporter.kt` cannot be compiled apart from the app, so the probe rebuilds its composition line for line (`probes/export/probe/src/main/kotlin/app/takes/probe/AppMirror.kt`) and compiles the app's own `TransitionRendering.kt` into itself: the transition effect and the compositor that ran are the app's.

## 1. In short

Three separate faults, all in how the two sequences ("decks") are handed to Media3, none in the transition shader:

| | What is seen | Cause | Where |
|---|---|---|---|
| A | While the first deck is empty the movie runs at 30 frames a second, whatever the footage on the second deck: 60 fps footage loses every other frame, 25 fps footage has one frame in five doubled | Media3 composes one frame for every frame of the **first** sequence, at that frame's time. Where the first sequence is a gap, Media3 fills it with blank frames at a fixed 30 a second | `SequenceAssetLoader.java:81`, `:429-438`; `DefaultVideoCompositor.java:143-145`, `:314` |
| B | The last frame of a clip arrives black; a clip that follows an empty stretch on the same deck is drawn two frames ahead of its time, sometimes for a few frames, sometimes to its end | A gap's frames are 16 by 16 pixels, a clip's are the movie's size. When a deck goes from one to the other, its processor deletes its two output textures and makes new ones, while the compositor is still waiting to draw frames that live in the deleted ones | `TexturePool.java:87-98`; `FinalShaderProgramWrapper.java:611`, `:163-175`; `MultipleInputVideoGraph.java:118` |
| C | (Latent; seen in the probe as soon as a still shared a deck with a clip.) A clip drawn with the proportions of another item | One `Presentation` is given to every clip. A `Presentation` keeps the matrix it worked out for the size of its last input | `Exporter.kt:267`, `:283`; `Presentation.java:291-297`, `:327` |

A fix for all three was run in the probe (section 5): a sequence of its own that only gives the frame times, a black still fitted like a clip wherever a deck is empty, a `Presentation` per item, and the decks' frame times made three microseconds later. With it every frame of every clip is shown, on the frame grid of the movie, with no black frame. One fault is left that the first three changes neither cause nor cure, and that is in today's export too: a single wrong frame at the start of some overlaps (section 8). The fourth change made it worse in one movie and is not yet safe to adopt.

The opening still that comes out black (section 6) is not a fault of the export: black for the length of the freeze is what the original does and what the core's scene says, on `v0.7` and on `main` alike.

## 2. How it was reproduced

Footage made by `probes/export/tools/make-clips.sh`: clips at 24, 25, 30 and 60 frames a second, 640 by 360, every frame carrying its own number as a row of blocks at the top (left half for clips played on the first deck, right half for the second). `probes/export/tools/verify.py picture` decodes a movie and reports, for each stretch of the plan, how many frames it has, which source frames they show, which are missing, shown twice or black, and how far the frame shown is from the frame due.

The edit, in seconds of the movie: the first deck plays a clip from 0 to 3, the second deck a clip from 2 to 9, the first deck another from 8 to 11. From 3 to 8 the second deck plays alone.

- `two`: the probe's own drawing of the two decks (during an overlap the left half of the first deck beside the right half of the second, so both numbers stay readable).
- `app`: the app's composition and `TransitionEffect`, with dissolves of one second. During a dissolve the numbers of both clips are mixed and cannot be read; the figures quoted are for the stretches outside the dissolves.

```
am instrument -w -e scenario two -e first 30 -e second 60 -e timing decks app.takes.probe/.ProbeInstrumentation
am instrument -w -e scenario app -e clips three -e first 30 -e second 60 -e ceiling 60 app.takes.probe/.ProbeInstrumentation
python3 probes/export/tools/verify.py picture two-30-60-3000000-decks.mp4 --plan two --first 30 --second 60
```

## 3. What the export does today

"Shown" counts the distinct source frames that reach the movie, of those the stretch should show.

| Footage (deck 1 / deck 2) | Movie while deck 1 plays | Movie from 3 to 8 s (deck 2 alone) | Other faults in the same movie |
|---|---|---|---|
| 30 / 60, probe's drawing | 30 fps | **30 fps: 150 frames, 150 of 300 source frames shown** | last frame of the first clip black (frame at 2.967 s); the third clip two frames ahead from its first frame to its last (frames 240 and 241 never shown) |
| 30 / 60, app's effect, export setting 60 | 30 fps | **30 fps, 150 of 300** | third clip two frames ahead |
| 30 / 60, app's effect, export setting 30 | 30 fps | 30 fps, 150 of 300 | (the ceiling of 30 would thin this footage anyway) |
| 60 / 60, probe's drawing | 60 fps | **30 fps, 150 of 300** | last frame of the second clip black |
| 25 / 25, probe's drawing | 25 fps | **30 fps: 150 frames for 125 source frames, 25 of them shown twice** | last frame of the second clip black |
| 30 / 25, probe's drawing | 30 fps | 30 fps, 125 source frames, 25 shown twice | last frame of the second clip black |
| 30 / 30, first clip 3.02 s long | 30 fps | 30 fps, every frame one ahead of its time | one frame of 20 ms at the cut; last frame of the second clip black |

So the frame rate of the movie changes inside one file: a 60/60 movie at export setting 60 has frames 16.7 ms apart for 6 seconds and 33.3 ms apart for 5.

The faults of row B do not happen on every run. Of the five movies made with the probe's drawing, the last frame of a clip on the second deck was black in four, the last frame of the first clip in one, and the third clip was two frames ahead in one; a forty-second movie of the same kind had a black last frame, a black frame after a gap and nine frames two ahead in one run, and in another the whole of its last clip two ahead. They depend on how far the deck's processor is ahead of the compositor when the deck changes from a clip to a gap or back, which is a matter of timing between threads.

## 4. The causes

### A. The first sequence gives the movie its frame times

`Exporter.kt:387-401` builds two sequences of clips and gaps and gives them to one `Composition`. With more than one video sequence Media3 uses `MultipleInputVideoGraph` (`VideoSampleExporter.java:142-145`), whose `DefaultVideoCompositor` takes the first sequence registered as the *primary* one (`DefaultVideoCompositor.java:143-145`). It draws one output frame for each primary frame and gives it that frame's time (`:302-315`); from every other sequence it takes the frame whose time is nearest (`:357-395`).

A gap in a sequence that carries video is filled with a black bitmap of 16 by 16 pixels repeated at `BLANK_IMAGE_FRAME_RATE = 30.0f` (`SequenceAssetLoader.java:70-81`, `:383-386`, `:429-438`). There is no setting for that rate.

So while deck 1 plays footage the movie has that footage's frame times, and while deck 1 is empty it has 30 frames a second; deck 2 is resampled to whichever applies, by nearest time. The app knows this (`TransitionScene.kt:55-60`, `:63-80`: "An export here has no frame rate of its own") and works around it for the flash cut only.

The nearest-time rule also explains the "one frame ahead" row: when the first clip is 3.02 s long, the blank frames of deck 1 start at 3.02 s and fall 20 ms after the frames of deck 2; the frame of deck 2 that is nearest is then the *next* one (13 ms ahead), not the one showing.

### B. A deck that changes from a clip to a gap throws away textures still in use

Each sequence has a processor of its own that renders into a pool of two textures and hands them to the compositor (`MultipleInputVideoGraph.java:118`, `PRE_COMPOSITOR_TEXTURE_OUTPUT_CAPACITY = 2`, `:281-286`). The compositor keeps the frames it has been handed in a queue until the primary sequence reaches their time, then tells the processor which textures are free by time (`FinalShaderProgramWrapper.java:163-175`).

The pool is sized to the processor's output. Before each frame the processor calls `outputTexturePool.ensureConfigured(glObjectsProvider, outputWidth, outputHeight)` (`FinalShaderProgramWrapper.java:611`), and when the size differs from the pool's:

```java
// TexturePool.java:93-97
GlTextureInfo texture = getIteratorToAllTextures().next();
if (texture.width != width || texture.height != height) {
  deleteAllTextures();
  createTextures(glObjectsProvider, width, height);
}
```

A clip's frames leave its processor at the movie's size (`Exporter.kt:283`, the `Presentation`). A gap's frames have no effects and leave it at 16 by 16. So at the end of every clip that is followed by a gap, and at the start of every clip that follows one, the pool is deleted and made again (`TexturePool.java:154-161`), and nothing tells the compositor:

- A frame of the clip still waiting in the compositor's queue now names a deleted texture. Drawn, it is black: **the last frame of the clip is black**. This is the frame the app's `TransitionEffect` then replaces with the one before (`TransitionRendering.kt:351-354`, `:458-459`), so the movie shows the second last frame twice and never the last.
- The new pool starts with both textures free although the compositor still holds two frames, and the list of times by which textures are released (`outputTextureTimestamps`) still has the old entries. From then on every texture is freed two frames early and is drawn over by a later frame before the compositor has used it: **the clip is two frames ahead** (two is the size of the pool), until the queue happens to run empty.

In the probe the second showed as "shown minus due: 2" on every frame of the third clip, with its frames 240 and 241 missing and its last two frames right again (nothing came after them to draw over them).

### C. One `Presentation` for all clips

`Exporter.kt:267` makes one `Presentation` and `:283` adds it to every clip. `Presentation.configure(inputWidth, inputHeight)` stores a matrix for that input size in the object (`Presentation.java:291-297`) and `getMatrix` returns it (`:327`). The two decks' processors run at the same time on their own threads, each configuring the same object when its item changes. Two clips of different proportions that play at once, one on each deck, can therefore be drawn with each other's matrix.

This was not reproduced with footage (all the probe's clips have one shape). It was met when the black still of section 5 was given the same `Presentation` as the clips: the clip on the other deck came out squeezed into a square of 360 pixels in the middle of the frame, on every frame.

## 5. The proposed fix, and the same movies with it

Four changes in how `Exporter.kt` builds the composition; nothing in the shader or in `TransitionEffect`.

1. **A sequence that only gives the time.** Before the two decks, a video-only sequence holding one black still for the length of the movie, with `EditedMediaItem.Builder.setFrameRate(settings.frameRate)` and `setDurationUs(pictures × 1/frameRate)`. Being first, it is the primary sequence: the movie gets exactly one frame per tick of the export's frame rate and both decks are sampled to it. `DecksCompositor.getOverlaySettings` (`TransitionRendering.kt:307-309`) then has three inputs: input 0 is not drawn (`getAlphaScale() = 0f`) and decks are inputs 1 and 2. The probe's `StackCompositor` (`probes/export/.../Effects.kt`) is that.
   - `frames` (`Exporter.kt:266`) becomes the export's frame rate for every join, and `FrameCeilingEffect` (`:272`) is no longer needed: the clock thins footage that is faster and repeats frames of footage that is slower.
   - The still states no frame rate, so Media3 tells the encoder 30 frames a second and picks the bitrate for 30 (`DefaultEncoderFactory.java:328-329`, `:615`); today both come from the first clip. An export at 60 should set its bitrate itself (`VideoEncoderSettings.Builder.setBitrate`). Key frames came every 30 frames at every rate.
   - With one chain (`Exporter.kt:370-382`) the same holds: clock plus one deck. An export without transitions then also goes through the compositor; section 4 of `docs/EXPORT-SECTIONS-PROBE.md` has the cost.
2. **A black still in place of every gap in a sequence that carries video** (`Exporter.kt:377`, `:379`, `:391`): the same still as an image item of the gap's length, with the same fitting effect as a clip, so its frames are the movie's size and the pool is never remade. The audio-only sequences (`:305`, `:330`) keep their gaps. Media3 adds silence for an image in a sequence that also carries sound (`SequenceAssetLoader.java:387-390`); the movies below have their sound. The still must be black to the last bit: `TransitionEffect.keep` (`TransitionRendering.kt:430-433`) tells an empty deck by that.
3. **A `Presentation` for each item** (`Exporter.kt:267`: a function in place of the value).
4. **The decks' frame times three microseconds later**: an effect after the `Presentation` of every clip and still on a deck that passes each frame on with its time plus 3 µs (`LaterEffect` in the probe's `Effects.kt`, a `PassthroughShaderProgram` of six lines). Without it footage at half the export's frame rate is uneven, as follows. This one has a caveat: section 8.

The same edits with changes 1 to 3 (`-e timing clock`; `-e later 3` adds the fourth):

| Footage | Export setting | Frames, all on the grid | From 3 to 8 s | Black frames | Frames ahead or missing |
|---|---|---|---|---|---|
| 30 / 60, probe's drawing | 60 | 660 of 660 | **60 fps, 300 of 300** | 0 | none missing; the 30 fps clips are shown unevenly (see "Why the fourth change") |
| 30 / 60, app's effect | 60 | 660 of 660 | **60 fps, 300 of 300** | 0 | none |
| 30 / 25, probe's drawing | 30 | 330 of 330 | 30 fps, 125 of 125, 25 shown twice | 0 | none |
| 30 / 30, first clip 3.02 s | 30 | 331 of 331 | 30 fps, every frame on its time | 0 | none |
| 25 / 30, app's effect, first clip 3.04 s | 30 | 332 of 332 | 30 fps, all shown | 0 | none |

The clock alone is not enough. With the clock and Media3's own gaps (the first attempt) the movies were on the grid but still had the faults of B: 25/25 at setting 25 gave 275 frames of 275, with the last frame of both clips black and the third clip two frames ahead; and 30/25 at setting 30 stopped with `ERROR_CODE_MUXING_TIMEOUT` ("no output sample written in the last 25000 milliseconds") before its first frame. With the stills the same export ran.

**Why the fourth change.** With 30 fps footage in a 60 fps movie each source frame should take two ticks. Media3 picks the deck's frame nearest in time; a file gives frame times cut down to the microsecond (33 333) and the clock rounds its own (16 667), so on the tick halfway between two frames the next one is nearer by one microsecond. With changes 1 to 3 only, the 30/60 movie showed a frame that had not begun yet on 90 of the 360 ticks where a 30 fps clip is drawn, so some frames last one tick and their neighbours three. With the frame times of the decks 3 µs later the frame that is showing is always the nearer one: the same movie has every 30 fps frame on exactly two ticks ("shown minus due" 0 on all 660 frames), with the probe's drawing and with the app's effect, and footage at the export's own rate is not affected (its frames are then 3 µs from their ticks and nothing else is near).

A smaller fix, **not run**: changes 2 and 3 without the clock. It should remove the black and displaced frames, and with the still at the export's frame rate the stretches where deck 1 is empty would run at that rate instead of 30. The movie would still take the frame times of deck 1's footage while that plays.

## 6. The opening still that is black

Report: a still (freeze frame) used as the opening transition comes out black for its whole second in the export; seen on the branch with the rotated and animated crop, since merged.

**It happens on both `v0.7` and `main`, and it is what the code is meant to do.**

- Reproduced with the app's composition and `TransitionEffect` (`-e scenario app -e clips still`): one clip of 4 s with `openingTransition = TransitionSettings(style = Still, duration = 1.0)`. The movie is 5 s, 150 frames; frames 0 to 29 are black to the last bit (mean brightness 0.0) and frame 30 is the clip's first. With `stillDissolve = 0.3` frame 30 is still black and the clip fades in from frame 31.
- The scene the core describes for those frames, printed by the probe: `first = Fill(black)`, `second = Clip(index 0, offset 0.0)`, `frame = First`, at 0.0, 0.5 and 0.99 s; at 1.0 s the clip. `TransitionScene.moment` gives the fill for the outgoing side when a join has no outgoing clip (`TransitionScene.kt:136`), a still's progress is 0 for the whole freeze, and `TransitionPicture.heldFrame` says "A still at the opening of the timeline has no outgoing clip: it holds [fill] instead".
- `v0.7` against `main`: `TransitionRendering.kt`, every `core/.../Transition*.kt` and `TimelineEditing.kt` are byte for byte the same (`git diff --quiet v0.7 main -- …`). `Exporter.kt` differs only in the crop (`FramingEffect` in place of `Crop`), which acts on the clip's own frames and not on the stretch before it. The probe was built on `main`; the same sources give the same build on `v0.7`. The app itself was not driven through its screens on either.
- The original does the same: the freeze of a still is the *outgoing* clip's last frame, and an opening has none (`TrackTimeline.swift:526`: `freezeVideo: transition.style == .still && join.index > 0 ? … : nil`), so the region is the fill, black (`TransitionRenderer.swift:184-187`). Its own test says so: "Opening still holds black before source footage" (`Tests/TransitionTests.swift:288`). `docs/TRANSITIONS-SPEC.md:143` and `:352` record it: "Opening still: black is held for `duration`, then cut or dissolve into the first clip."
- The preview draws the same scene (`TransitionPreview.kt:159-160`, `:183-184`), so it should be black there too. If the preview shows something else, the preview is the side that differs; that was not looked at.

If a freeze on the first clip's first frame is wanted instead, it is a departure from the original and belongs in the core: `TransitionScene.moment` would give `Source.Held` of the *incoming* clip for an opening still, and the exporter would need that frame before the clip plays, which the "keep the last frame drawn" of `TransitionEffect` cannot give (the frame has not been drawn yet).

## 7. What needs a real phone

- Fault B depends on timing between the decoder, the two processors and the compositor. On the emulator it showed in most runs. A phone's hardware decoder is faster and may show it more or less often; the cause (textures deleted on a change of size) does not depend on the device.
- The cost of the third sequence and of the stills on a phone's GPU, at 1080p and 4K, with HDR footage tone-mapped (`Exporter.kt:180`). The probe ran SDR 640 by 360 only.
- That a hardware encoder takes frames on an exact grid of 60 a second without dropping any.

## 8. Not determined

- **One wrong frame at the start of an overlap.** In the forty-second movie of the sections probe (`docs/EXPORT-SECTIONS-PROBE.md`), within the first three frames after the second deck begins to play while the first is still drawn, one frame shows the first deck's picture where the second deck's should be. It was there in every one of nine renderings of that stretch, in one pass and in sections, at 24, 25, 30 and 60 fps, with Media3's gaps and with the stills, with and without the clock; and in none of the eleven-second movies of this document, whose second deck begins after two seconds instead of eleven. In the app it would be one early frame of a transition's incoming side.
  - It is in today's layout too: the movie built as the app builds it (two decks, gaps, no clock) had it in both of its renderings (frame 330 in one, 332 in the other).
  - It is not the drawing effect: the probe's effect wrote to the log what it drew, and it drew "both decks" for that frame. The frame it was handed already held the first deck's picture in both halves, so the mix-up is before or in Media3's compositor.
  - **With change 4 it was worse in this movie**: in two of two renderings one deck's picture stood in both halves for the rest of the overlap (about 55 of its 60 frames both times), once the first deck's and once the second's. A third rendering of the same movie without change 4 had the single frame again. The two eleven-second movies rendered with change 4 were right on every frame. Change 4 puts a second stage into each deck's processor (the `Presentation` no longer being the last effect), which may be what matters.
  - Not understood. It may be the emulator's graphics (two decoders drawing into two surface textures of one GL context); it needs a phone. Until it is understood, change 4 should not go in on the strength of this document, or its 3 µs should be taken off the clock's frame times instead of added to the decks' (an effect on the one clock item, the drawing effects then reading the time 3 µs later), which leaves the decks' processors as they are. That variant was not run.

- Whether fault C shows in the app today with a portrait and a landscape clip dissolving into each other. By reading it can; it needs footage of two shapes.
- A still (freeze) *between* two clips with the clock: `TransitionEffect` keeps the last frame it drew of a deck. The probe ran dissolves only.
- Titles and subtitles over the app's effect with the clock. (An overlay that changes every frame was run over the probe's own drawing, in sections: `docs/EXPORT-SECTIONS-PROBE.md`.)
- The sound of each clip lies 25 to 39 ms later in the movie than the plan places it, and the music 9 ms (measured in the one-pass export of the sections probe: 1472, 1216, 1856 and 448 samples at 48 kHz). The same in every export, with or without the changes above. Whether that is the probe's clips (ffmpeg's AAC, with its lead-in) or Media3's cutting of sound was not looked into.

## 9. As carried out

Added when the fix went into the app (`Exporter.kt`, `TransitionRendering.kt`, `TransitionScene.kt`). Run on the same emulator with the app itself, on projects whose clips carry a frame counter; nothing on a phone. Sections 1 to 8 are as they were written.

**What went in.** Changes 1 to 3 of section 5, and change 4 in the variant section 8 names and had not run: the three microseconds are taken off the clock's frame times, not added to the decks'. A movie of plain cuts is left as it was, one sequence and no clock: its frames were measured equal to before, frame for frame. Every movie with a transition or a freeze goes through the clock, with one deck or two.

**The decks cannot carry sound.** With stills on decks that carry sound the export stopped with "Asset loader error", now and then. A sequence of sound and picture whose first item is a still asks for its silence in the same call that takes its picture, and the answer must not be null (`SequenceAssetLoader.java:341-348`, a `checkNotNull`). It is null until the lowest sequence that has sound has made the sound exporter (`TransformerInternal.java:673-685`, `:1002`). So a second deck that opens on a still fails whenever it gets its picture before the first deck has given its sound: with a still opening both decks about every other export, with a clip opening the first deck more rarely (it was not seen in the runs of section 5). Giving the clock a silent track does not help: it makes the picture exporter and the sound exporter in two calls, and a deck waiting for the first gets in between (2 failures in 12 exports). What works: the decks carry the picture alone, and the sound of every clip plays from sequences of sound alone at the clips' times, as the cleaned sound already did. Such a sequence waits and asks again (`SequenceAssetLoader.java:806-834`). 46 exports of 28 projects, none failed, and the decoded sound is the sound of `main` sample for sample in the seven projects compared.

**The clock's frame times drift, and the movie's are put on the grid.** Media3 gives a still's frames times counted in a frame length held as a `float` (`ConstantRateTimestampIterator.java`, `framesDurationUs = C.MICROS_PER_SECOND / frameRate`): at 30 a second they fall a microsecond behind the grid about every thousand frames. `TransitionEffect` allows one microsecond. Thirty-one seconds into a movie a flash cut with a flash on every other frame showed them on frames 2, 3, 4, 8, 9, 10 and 14 in place of 1, 3, … 13. `FrameTimeEffect` (`Exporter.kt`) is used twice: on the clock's still it makes each time three microseconds earlier, and as the first effect on the composed frame it puts the time on the grid of the movie's frame rate, so the transition effect, the titles, the subtitles and the encoder get exact times. With it footage at half the movie's rate is shown evenly (a 30 fps clip in a 60 fps movie: every frame on two frames of the movie, where without it one tick in three took the next frame), and the forty-second movie was right on every frame in five exports.

**The frame rate of the movie** (`TransitionScene.frameRate(footage, ceiling)`). The export setting is a ceiling, 30 or 60. A movie with a transition has the rate of its fastest footage, each clip's first brought under the ceiling by leaving out whole frames (60 under 30 is 30, 50 under 30 is 25), then the nearest of 24, 25, 30, 50 and 60 that is not above the ceiling; footage that does not say counts as 30, and a clip's speed does not count. The preview's flash cuts count in the same rate. Media3 is told the rate for the encoder (`ClockedEncoders`), which would otherwise take 30 from the still.

**The wrong frame of section 8** was not seen: neither before the change nor after it, in a forty-second movie made to show it (a dissolve of three frames, and flashes on every other frame, at the starts of the two overlaps). In an ordinary dissolve it cannot be seen, the incoming side having no weight yet in those frames.

**Still open.**

- Media3 takes the frame of a deck nearest in time, not the one showing. Footage slower than the movie (25 fps in a movie of 30 or 60) is shown up to half a frame of its own early, and a deck can be black for the last half frame of a clip; `TransitionEffect` still keeps the last frame for that.
- A still's held frame is counted in 30 frames a second by the model (`TransitionPicture.heldFrame`), in the preview and now in the export alike. In a movie of 60 the clip shows one frame more before the freeze, and the freeze steps back to the one before it. The original counts in its export's rate; doing so here reaches the preview's transport and `RenderPlan`.
- The clock holds a whole number of frames, so a movie whose length is not on the frame grid is up to one frame longer than its clips.
- Every clip is opened twice, once for its picture and once for its sound. On the emulator a forty-second movie took 17 to 18 s where it took 14 to 16; a phone at 1080p and 4K is to be measured.
- Time-stretched sound (a clip at another speed, now with its pitch kept) is Sonic's: a pure tone at half speed came out 10 Hz off and a tenth quieter.
