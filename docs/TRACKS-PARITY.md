# Parity of the editing functions on the track model with what they did before

Written by `TrackParityTest` (`core/src/test/kotlin/app/takes/core/TrackParity*.kt`); do not edit by hand.

## Running it

The suite is one test of the core tests: `./gradlew :core:test`, or by itself with `--tests app.takes.core.TrackParityTest`. What it does is chosen in the environment:

- **Nothing set.** It makes the calls of a sample of the edits, about a seventh of them and the same every run, and compares what it finds with the section [The sample](#the-sample) of this file. It fails when that section is not what it finds now, or when a function answers an edit whose timeline the invariants refuse. It does not fail on a difference that is written down here.
- **`TAKES_PARITY_FULL=1`.** It makes every call and compares the whole of this file, examples included, in the same way. Run it before and after a change to the editing functions or to the track model.
- **`TAKES_RECORD_PARITY=1`.** It makes every call and writes this file anew instead of comparing. Read what changed (`git diff docs/TRACKS-PARITY.md`) and commit the file with the change that caused it.
- **`TAKES_PARITY_GATE=1`.** As `TAKES_PARITY_FULL=1`, and it also fails on every call that shows a kind of difference not listed in `TrackParityAccepted.kt`, or a difference no kind explains: the failure names each such kind with the number of calls that show it, then what is not explained. The list holds the kinds that `docs/TRACKS-PARITY-DECISIONS.md` decides as Unreachable or Invisible, and those adopted since ("At S4"); every other kind is one a function keeps today's result for, and must not appear.

## What is compared

Since stage S2-B of `docs/TRACKS-PLAN.md` the functions of `TimelineEditing` and `SubtitleEditing` that change an edit run on the track model: the edit is converted to a timeline, an operation of the track model is run, and what it changed is written back into the sequence (`TrackWrapping`). `RangeEditing` cuts, removes and joins clips through them. What each did before is kept in the tests, unchanged, as it was in release 0.8 (`LegacyTimelineEditing`, `LegacyRangeEditing`, `LegacySubtitleEditing`, `LegacyAudioUnlinking`).
Every such function, and the questions among them, is called twice on the same edit with the same arguments: as it was ("today" below, the name the kinds were written with), and as it is now ("through the track model").
Both answers are read as the conversion reads them (fits saved, settings that do not play dropped, sound tracks repacked), so only what plays differently counts.
Ids made during the call, the track of a sound item, the order of a clip's bookmarks, and times less than three ticks (1/80000 s) apart are not differences. Where a sound is muted on both sides, its level, fades and noise reduction are compared as one thing: what it would sound like unmuted. The section [Saved bytes](#saved-bytes) counts the calls without any of these allowances.

- **identical**: the same edit and the same answer, or neither applies.
- **different**: both give a sequence, and the two differ.
- **not expressible**: the result is a valid timeline that no sequence of clips with two sound tracks plays. No function answers one while a project saves a sequence.

The edits: 18 small ones made for one situation each, 128 steps of the histories of the golden projects (all but the long one), and 300 made by 30 seeded sequences of the functions as they were. 91323 calls.

## By function

| Function | Calls | Identical | Different | Not expressible | Kinds |
|---|---:|---:|---:|---:|---|
| `TimelineEditing.split` | 1931 | 1165 | 766 | 0 | [split-refused-near-an-edge](#split-refused-near-an-edge), [split-cuts-sound-items](#split-cuts-sound-items), [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound) |
| `TimelineEditing.delete` | 621 | 597 | 1 | 23 | [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.move` | 1396 | 1335 | 19 | 42 | [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [move-finds-no-cut-among-unlinked-pictures](#move-finds-no-cut-among-unlinked-pictures), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.insert` | 2822 | 2355 | 467 | 0 | [split-refused-near-an-edge](#split-refused-near-an-edge), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound) |
| `TimelineEditing.trim` | 3080 | 2485 | 246 | 349 | [handle-trim-inwards-is-a-deletion](#handle-trim-inwards-is-a-deletion), [handle-trim-outwards-overlaps](#handle-trim-outwards-overlaps), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.removeIntervals` | 2464 | 2464 | 0 | 0 |  |
| `TimelineEditing.setSpeed` | 2464 | 2444 | 20 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.toggleBookmark` | 1250 | 1250 | 0 | 0 |  |
| `TimelineEditing.setJoin` | 15917 | 15804 | 38 | 75 | [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [close-that-holds-pushes-nothing](#close-that-holds-pushes-nothing), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.setTransition` | 5253 | 5233 | 20 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.setText` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.setColor` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.setCrop` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.placeTitle` | 610 | 610 | 0 | 0 |  |
| `TimelineEditing.setAudio` | 3708 | 3698 | 10 | 0 | [fade-no-longer-than-its-item](#fade-no-longer-than-its-item) |
| `AudioUnlinking.setSound` | 3708 | 3696 | 12 | 0 | [fade-no-longer-than-its-item](#fade-no-longer-than-its-item) |
| `TimelineEditing.addAudio` | 1062 | 1062 | 0 | 0 |  |
| `RangeEditing.delete` | 4519 | 3721 | 499 | 299 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [later-piece-new-identity](#later-piece-new-identity), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound), [playhead-in-time-a-transition-holds](#playhead-in-time-a-transition-holds), [bookmark-outlives-its-footage](#bookmark-outlives-its-footage) |
| `RangeEditing.copy` | 4519 | 4519 | 0 | 0 |  |
| `RangeEditing.cut` | 4519 | 4519 | 0 | 0 |  |
| `RangeEditing.paste` | 13557 | 13557 | 0 | 0 |  |
| `RangeEditing.scoped` | 4519 | 3688 | 630 | 201 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `SubtitleEditing.install` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.cues` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.transcript` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.groupingAvailable` | 446 | 446 | 0 | 0 |  |
| `AudioUnlinking.unlink` | 613 | 613 | 0 | 0 |  |
| `AudioUnlinking.relink` | 613 | 613 | 0 | 0 |  |
| `AudioUnlinking.restoreSync` | 613 | 613 | 0 | 0 |  |
| `AudioUnlinking.syncOffset` | 613 | 613 | 0 | 0 |  |
| `TimelineEditing.duration` | 446 | 446 | 0 | 0 |  |
| `TimelineEditing.clipStart` | 446 | 446 | 0 | 0 |  |
| `RangeEditing.joinTimes` | 446 | 446 | 0 | 0 |  |
| `TimelineEditing.bookmarkTimes` | 446 | 446 | 0 | 0 |  |
| `RangeEditing.transitionAt` | 1931 | 1931 | 0 | 0 |  |
| `TimelineEditing.moveAudio` | 900 | 900 | 0 | 0 |  |
| `TimelineEditing.trimAudio` | 900 | 575 | 325 | 0 | [split-refused-near-an-edge](#split-refused-near-an-edge), [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `TimelineEditing.setAudioItemSound` | 900 | 888 | 12 | 0 | [fade-no-longer-than-its-item](#fade-no-longer-than-its-item) |
| `TimelineEditing.removeAudio` | 300 | 284 | 16 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `SubtitleEditing.remove` | 81 | 81 | 0 | 0 |  |
| `SubtitleEditing.restyle` | 81 | 81 | 0 | 0 |  |
| `SubtitleEditing.regroup` | 243 | 243 | 0 | 0 |  |
| `SubtitleEditing.correct` | 93 | 93 | 0 | 0 |  |
| `SubtitleEditing.delete` | 93 | 93 | 0 | 0 |  |
| `TimelineEditing.removeText` | 14 | 14 | 0 | 0 |  |
| **all** | 91323 | 87253 | 3081 | 989 | |

## The kinds of difference

Only the kinds that are found, in the order a user would notice them; the kinds that are kept as today are absent, and described in `TrackParityKinds.kt` and `docs/TRACKS-PARITY-DECISIONS.md`. A call can show several kinds; it is counted under each. "Plan" is the row of `docs/TRACKS-PLAN.md`, 2.4. The rule is where the original does what the track model does, as a path in its repository.

### unlinked-picture-does-not-ripple

**A clip whose sound is unlinked stays where it is, and nothing closes up behind it.**

- Today: A clip is always part of the sequence: when it or an earlier clip is deleted, trimmed, retimed, moved, has pauses taken out or gets a transition, every later clip moves to close the picture, whether or not its sound was unlinked.
- Track model: Only items in a link of picture and sound ripple. A picture whose sound was unlinked is linked to nothing: deleting it or cutting a range out of it leaves a gap, and when an earlier clip changes length it stays behind, so the timeline gets a gap before it or an overlap with it.
- Visible effect: After Unlink, an ordinary delete, trim, speed change or transition leaves black time or two pictures on top of each other, where today the clips stay one after another. The result cannot be saved as a sequence.
- Rule: YouRec/TrackTimeline.swift:774-799 (only complete links close their time, only links of picture and sound move); YouRec/Transitions.swift:400-410; YouRec/TrackTimeline.swift:1361 (plan: N9)
- Seen in: `TimelineEditing.insert` 92, `RangeEditing.delete` 123, `RangeEditing.scoped` 123, `TimelineEditing.delete` 1, `TimelineEditing.move` 8, `TimelineEditing.trim` 26, `TimelineEditing.setSpeed` 20, `TimelineEditing.setJoin` 38, `TimelineEditing.setTransition` 20 (100 not expressible; 123 together with another kind)
- Accepted: yes

`TimelineEditing.insert(a clip of 2.5 s at 0.05)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c4 @0 S1 0–2.5
    c1 @2.5 S1 0–12
    c2 @14.5 S2 0–8 [muted]
    c3 @22.5 S3 0–6
    sound of c2 @12 S2 0–8
    (28.5 s of picture)
through the track model
    c1 @0 S1 0–0.05
    c4 @0.05 S1 0–2.5
    c5 @2.55 S1 0.05–12
    c2 @14.5 S2 0–8 [muted]
    c3 @22.5 S3 0–6
    sound of c2 @12 S2 0–8
    (28.5 s of picture)
```

### move-finds-no-cut-among-unlinked-pictures

**A clip cannot be moved to a place between pictures that do not meet.**

- Today: Clips are a list: a clip is moved to any place in it, and the others close up around it.
- Track model: The clip is lifted out and put back at the cut between the two pictures it is to go between. When lifting it leaves a picture whose sound is unlinked where it was while the linked pictures close up, those two no longer meet: there is no cut to go to, and the move does not apply.
- Visible effect: Moving a clip to a place in the order stops working around a clip whose sound is unlinked. The clip can still be dragged to a time.
- Rule: YouRec/TimelinePaste.swift:39-44 (a place to paste picture is a join of two pictures that meet); YouRec/TrackTimeline.swift:774-799 (plan: N9)
- Seen in: `TimelineEditing.move` 11 (0 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.move(clip 1 to place 2)` on `golden unlinked-sound, step 2`:

```
before
    c1 @0 S1 0–6
    c2 @6 S2 0–6 [muted, -3 dB, fades 0/0.5]
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
today
    c2 @0 S2 0–6 [muted]
    c1 @6 S1 0–6
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
through the track model
    does not apply
```

### range-inside-a-transition

**A range that begins or ends inside a transition cuts both clips there.**

- Today: An end of a range inside a transition is put on the join of the transition, and one on the first instant of an opening of a clip that is not the first is put where that opening ends. A tool on a range with an end inside a transition, its first instant included, applies to nothing and cuts nothing.
- Track model: Both pictures playing there are cut at the exact second. The pieces keep overlapping; the transition stays when both its pictures still have its footage and is removed otherwise, and what is copied can be two pictures that play at once. A range from the first instant of an opening takes the clip with its opening.
- Visible effect: Delete, Cut, Copy and a tool on a range marked into a dissolve leave two pictures on top of each other for the rest of the dissolve's time, or a short gap, where today the edit snaps to the join. What is copied begins where the range does, not at the join, so its sound items lie that much earlier against its picture. A range marked exactly over a clip that carries an opening and is not first takes the whole clip, where today the length of the opening is left behind.
- Rule: YouRec/TrackTimeline.swift:1080-1094 (the items are cut at the range's ends), :857-895 (the prune keeps or drops the relation) (plan: N12)
- Seen in: `RangeEditing.delete` 352, `RangeEditing.scoped` 430 (397 not expressible; 39 together with another kind)
- Accepted: yes

`RangeEditing.scoped(range 10–11.5)` on `a dissolve`:

```
before
    c1 @0 S1 0–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
today
    c1 @0 S1 0–10
    c4 @10 S1 10–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
    clips a tool applies to: [2]
through the track model
    no sequence: two pictures play at once without a transition between them
    picture c1 0–10 layer 0
    picture c5 10–11.5 layer 0
    picture c2 11–11.5 layer 1
    picture c6 11.5–12 layer 0
    picture c7 11.5–19 layer 1
    picture c3 19–25 layer 2
    sound of c1 0–10
    sound of c5 10–11.5
    sound of c6 11.5–12
    sound of c2 11–11.5
    sound of c7 11.5–19
    sound of c3 19–25
    clips a tool applies to: [2, 3]
```

### handle-trim-outwards-overlaps

**A trim outwards by the handle reveals footage over the neighbour instead of pushing it.**

- Today: Extending a clip makes it longer and every later clip moves later.
- Track model: Revealing footage moves nothing else: the clip grows over the clip after it (or, at its start, back over the clip before it) and comes in front of it.
- Visible effect: Dragging a handle outwards covers the beginning of the next clip instead of making the movie longer. An edge under a transition, which has no grip in the editor, revealed by the function all the same, loses the transition or gets a larger fit.
- Rule: YouRec/EditorView.swift:1241-1258; YouRec/TrackTimeline.swift:1618-1675 (plan: N2)
- Seen in: `TimelineEditing.trim` 208 (190 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.trim(clip 1, the end out by 1 s)` on `two short clips`:

```
before
    c1 @0 S1 0–1
    c2 @1 S2 0–0.6
    (1.6 s of picture)
today
    c1 @0 S1 0–2
    c2 @2 S2 0–0.6
    (2.6 s of picture)
through the track model
    no sequence: a picture or a sound plays in several runs, or a sound of its own plays at another speed
    picture c1 0–2 layer 2
    picture c2 1–1.6 layer 1
    sound of c1 0–2
    sound of c2 1–1.6
```

### handle-trim-inwards-is-a-deletion

**A trim inwards by the handle is a deletion of the trimmed part, with the deletion's rule for transitions.**

- Today: The clip gets new source bounds. Its transitions are both removed when together they need more footage than it is left with, and are otherwise kept as they are.
- Track model: The trimmed part is cut off and deleted as a range of the linked picture and sound. The transition on the trimmed side has lost the edge it was on: it is removed, or stays when the picture still overlaps its neighbour as it needs. The one on the other side is not touched.
- Visible effect: A trim of the edge a dissolve is on removes the dissolve even when the clip stays long enough for it; a trim of the other edge never removes one.
- Rule: YouRec/EditorView.swift:1241-1258; YouRec/TrackTimeline.swift:771-827, :857-895 (plan: N2)
- Seen in: `TimelineEditing.trim` 361 (159 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.trim(clip 1, the end in by a third)` on `a dissolve`:

```
before
    c1 @0 S1 0–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
today
    c1 @0 S1 0–8 (into next: dissolve 1)
    c2 @7 S2 0–8
    c3 @15 S3 0–6
    (21 s of picture)
through the track model
    c1 @0 S1 0–8
    c2 @8 S2 0–8
    c3 @16 S3 0–6
    (22 s of picture)
```

### words-of-unlinked-sound

**The words of a clip whose sound is unlinked are on the sound, and show.**

- Today: Words belong to clips. A clip whose sound is unlinked is muted, and a muted clip shows no captions, gets no words from a transcription, and is not listened to; its unlinked sound, an item on a sound track, has no words.
- Track model: Words belong to sounds. The unlinked sound of a clip is an ordinary sound that is not muted: it keeps the words, shows them where it now plays, is transcribed, and loses them when it is deleted. Cut in pieces, each piece keeps them. A copy of the picture alone has no words, and so does not carry the look of the captions.
- Visible effect: After Unlink the captions of that clip stay on screen and follow the sound when it is moved, where today they disappear. Deleting the unlinked sound deletes its captions.
- Rule: YouRec/TrackTimeline.swift:184; YouRec/Subtitles.swift:155-180, :205-246 (plan: N10)
- Seen in: `TimelineEditing.split` 89, `TimelineEditing.trimAudio` 135, `TimelineEditing.insert` 22, `TimelineEditing.removeAudio` 16, `RangeEditing.delete` 3 (3 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.split(at 20.05)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    does not apply
through the track model
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–0.05
    c4 @20.05 S3 0.05–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
```

### playhead-in-time-a-transition-holds

**A playhead in time a transition holds stays where it is.**

- Today: After a deletion the playhead is carried to the frame of footage it was on. In the time an opening or a still holds, no clip plays: the playhead is put on the edge of the clip that time belongs to, the first frame after an opening or the last before a still, even when nothing before it moved.
- Track model: The playhead moves only by the time that was closed before it, or with the picture or the sound it was on.
- Visible effect: Deleting something later in the movie, or a sound item, while the playhead stands in the held time of an opening or a still makes the playhead jump to the clip, where through the track model it stays.
- Rule: YouRec/TrackTimeline.swift:807-826
- Seen in: `RangeEditing.delete` 1 (0 not expressible; 1 together with another kind)
- Accepted: yes

`RangeEditing.delete(range 0.02–4, the playhead at 25.123)` on `generated 6, step 8`:

```
before
    c1 @0 S2 0–8 (into next: flashcut 0.3)
    c2 @7.7 S1 0–12 (into next: fadecolor 2.5) marks 8.69
    c3 @19.7 S3 0–5.075 (closes: still 1)
    a1 @4 M1 0–30
    a2 @18.367 M2 0–5
    (25.775 s of picture)
today
    c4 @0 S2 4–8 (into next: flashcut 0.3)
    c2 @3.7 S1 0–12 (into next: fadecolor 2.5) marks 8.69
    c3 @15.7 S3 0–5.075 (closes: still 1)
    a1 @4 M1 0–30
    a2 @18.367 M2 0–5
    (21.775 s of picture)
    playhead: 20.775
through the track model
    c1 @0 S2 0–0.02
    c5 @0.02 S2 4–8 (into next: flashcut 0.3)
    c2 @3.72 S1 0–12 (into next: fadecolor 2.5) marks 8.69
    c3 @15.72 S3 0–5.075 (closes: still 1)
    a1 @4 M1 0–30
    a2 @18.367 M2 0–5
    (21.795 s of picture)
    playhead: 25.123
```

### removed-transition-leaves-its-time

**Time a transition had inserted stays empty when the transition goes.**

- Today: Clips always follow one another: when a transition that held the next clip back (a still, a fade through a color with a hold, an opening whose sound leads) is removed, replaced, or goes with its clip, the later clips close up.
- Track model: A deletion closes the time its pictures covered and no more, and setting another opening moves no picture: the time the transition had inserted stays, without picture.
- Visible effect: A stretch of black where the still or the hold was, after its clip is deleted, moved or cut away, or after the opening is changed.
- Rule: YouRec/TrackTimeline.swift:771-799 (what closes is the time the removed pictures covered); YouRec/Transitions.swift:364-377
- Seen in: `RangeEditing.delete` 72, `TimelineEditing.move` 42, `TimelineEditing.delete` 23, `TimelineEditing.setJoin` 74 (139 not expressible; 72 together with another kind)
- Accepted: yes

`TimelineEditing.delete(clip 1)` on `golden everything, step 2`:

```
before
    c1 @0 S1 0–9 (opens: fadecolor 0.5) (into next: still 1)
    c2 @10 S2 0–7
    c3 @17 S3 0–8
    c4 @25 S4 0–5
    (30 s of picture)
today
    c2 @0 S2 0–7
    c3 @7 S3 0–8
    c4 @15 S4 0–5
    (20 s of picture)
through the track model
    no sequence: there is time without picture before a picture
    picture c2 1–8 layer 1
    picture c3 8–16 layer 2
    picture c4 16–21 layer 3
    sound of c2 1–8
    sound of c3 8–16
    sound of c4 16–21
```

### close-that-holds-pushes-nothing

**A close that takes time of its own, set on a clip that is not the last, pushes nothing later.**

- Today: Clips follow one another: a close that holds a frame or a color after its clip makes the next clip begin that much later.
- Track model: Setting a transition moves the picture it leads into and what follows that. A close leads into nothing, so nothing moves, and the time it holds lies over the beginning of the next clip.
- Visible effect: The still or the hold of the close plays together with the first moments of the next clip. It takes a clip that carries a close and is not the last, which moving the last clip earlier leaves.
- Rule: YouRec/Transitions.swift:375-380
- Seen in: `TimelineEditing.setJoin` 1 (1 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.setJoin(the close of clip 1, still of 1.5 s)` on `generated 24, step 10`:

```
before
    c1 @0 S3 0–6 (closes: hitchcock 1) [fades 0.5/1] 3 words
    c2 @6 S1 0–5.633 10 words 1 titles
    c3 @11.633 S1 5.633–12 10 words
    c4 @18 S2 0–7.071 marks 3.758 5 words
    c5 @25.071 S2 7.071–8
    a1 @8.5 M2 0–5
    a2 @23.733 M1 0–30
    (26 s of picture, with the look of its captions)
today
    c1 @0 S3 0–6 (closes: still 1.5) [fades 0.5/1] 3 words
    c2 @7.5 S1 0–5.633 10 words 1 titles
    c3 @13.133 S1 5.633–12 10 words
    c4 @19.5 S2 0–7.071 marks 3.758 5 words
    c5 @26.571 S2 7.071–8
    a1 @8.5 M2 0–5
    a2 @23.733 M1 0–30
    (27.5 s of picture, with the look of its captions)
through the track model
    no sequence: two pictures play at once without a transition between them
    picture c1 0–6 layer 0
    picture c2 6–11.633 layer 1
    picture c3 11.633–18 layer 2
    picture c4 18–25.071 layer 3
    picture c5 25.071–26 layer 4
    sound of c1 0–6 [fade in 0.5 from 0, fade out 1 from 0]
    sound of c2 6–11.633
    sound of c3 11.633–18
    sound of c4 18–25.071
    sound of c5 25.071–26
    sound a1 8.5–13.5 (follows nothing)
    sound a2 23.733–53.733 (follows nothing)
    transition c1 → – from 6
```

### split-refused-near-an-edge

**A cut within 0.1 s of an edge is made instead of refused.**

- Today: A split, a trim of a sound item or a pause removal that would leave a piece shorter than 0.1 s is refused, or the short piece is dropped; footage inserted that close to a cut goes to the cut.
- Track model: A cut is refused only when a piece would be shorter than one tick; pieces of any length are kept, and footage is inserted exactly where asked.
- Visible effect: Clips and sound pieces a few frames long, which today cannot be made.
- Rule: YouRec/TrackTimeline.swift:1011-1014 (plan: N8)
- Seen in: `TimelineEditing.split` 325, `TimelineEditing.insert` 346, `TimelineEditing.trimAudio` 190 (0 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.split(at 0.05)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    does not apply
through the track model
    c1 @0 S1 0–0.05
    c2 @0.05 S1 0.05–12
    (12 s of picture)
```

### range-end-within-a-frame-of-a-cut

**A range end within a frame of a cut is not put on the cut.**

- Today: An end of a range closer than one frame to a clip's or an item's edge, or to where a transition begins or ends, is that place: no sliver is cut, copied or left behind, and a paste that close goes exactly there.
- Track model: The items are cut exactly where the range ends, or where the paste is asked for, so a piece shorter than a frame is made, kept or copied. An opening stays on the sliver, away from what is copied.
- Visible effect: A range marked a hair inside a clip leaves or copies a sliver of a few hundredths of a second, and what is copied comes without the opening of its clip.
- Rule: YouRec/EditorView.swift:1125-1130; YouRec/TrackTimeline.swift:1080-1094
- Seen in: `RangeEditing.delete` 314, `RangeEditing.scoped` 389 (0 not expressible; 163 together with another kind)
- Accepted: yes

`RangeEditing.scoped(range 0.02–6)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    c1 @0 S1 0–6
    c2 @6 S1 6–12
    (12 s of picture)
    clips a tool applies to: [1]
through the track model
    c1 @0 S1 0–0.02
    c3 @0.02 S1 0.02–6
    c4 @6 S1 6–12
    (12 s of picture)
    clips a tool applies to: [2]
```

### split-cuts-sound-items

**A cut at the playhead also cuts the sound items under it.**

- Today: Only the clip is cut; music or a voice-over running under the playhead stays one item, and so does the unlinked sound of the clip.
- Track model: With nothing selected, everything under the playhead is cut: the picture, its sound, and every other sound playing there.
- Visible effect: The music is in two pieces after a split. They play on without a break, and each can then be moved or deleted by itself.
- Rule: YouRec/TimelineEditing.swift:45-62
- Seen in: `TimelineEditing.split` 352 (0 not expressible; 12 together with another kind)
- Accepted: yes

`TimelineEditing.split(at 15.2)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c1 @0 S1 0–12
    c2 @12 S2 0–3.2 [muted]
    c4 @15.2 S2 3.2–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
through the track model
    c1 @0 S1 0–12
    c2 @12 S2 0–3.2 [muted]
    c5 @15.2 S2 3.2–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–3.2
    sound of c5 @15.2 S2 3.2–8
    (26 s of picture)
```

### bookmark-outlives-its-footage

**A bookmark on footage that is cut away is kept.**

- Today: A bookmark inside footage that pauses or a range take out is deleted with it.
- Track model: The bookmark stays in the timeline without a place, and shows again when that footage comes back.
- Visible effect: None until the footage is revealed again by a trim outwards, when the mark reappears. In the sequence form it is listed on the piece before the footage.
- Rule: YouRec/TrackTimeline.swift:1162-1174 (resolved bookmarks leave out a mark whose footage is not played)
- Seen in: `RangeEditing.delete` 1 (0 not expressible; 0 together with another kind)
- Accepted: yes

`RangeEditing.delete(range 16.66–22.66, the playhead at 17.834)` on `generated 25, step 7`:

```
before
    c1 @0 S1 0–2
    c2 @2 S1 2–2.412
    c3 @2.412 S1 2.412–10.336
    c4 @10.336 S1 11.176–12 [muted]
    c5 @11.16 S2 0–8 (into next: hitchcock 2.5) marks 6.989
    c6 @16.66 S3 0–6
    a1 @0.667 M1 0–30
    (22.66 s of picture)
today
    c1 @0 S1 0–2
    c2 @2 S1 2–2.412
    c3 @2.412 S1 2.412–10.336
    c4 @10.336 S1 11.176–12 [muted]
    c5 @11.16 S2 0–5.5
    a1 @0.667 M1 0–15.994
    a2 @22.66 M1 21.994–30
    (16.66 s of picture)
    playhead: 16.66
through the track model
    c1 @0 S1 0–2
    c2 @2 S1 2–2.412
    c3 @2.412 S1 2.412–10.336
    c4 @10.336 S1 11.176–12 [muted]
    c5 @11.16 S2 0–5.5 marks 6.989
    a1 @0.667 M1 0–15.994
    a3 @22.66 M1 21.994–30
    (16.66 s of picture)
    playhead: 16.66
```

### unlinked-clip-remembers-no-sound

**A clip whose sound is unlinked remembers no sound of its own.**

- Today: Unlinking mutes the clip and leaves on it the level and the fades it had, for linking again. A piece cut from that clip, and the clip itself once its unlinked sound is deleted, is an ordinary muted clip with those settings: unmuting it plays the sound of its footage again.
- Track model: A picture whose sound is unlinked has no sound. Its pieces are pictures without sound, and deleting the unlinked sound leaves a picture without sound; in the sequence form each is a muted clip that remembers nothing.
- Visible effect: None while the clip is muted. Today Unmute on such a piece brings the recorded sound back at the level it had (a second time, beside the unlinked sound, while that is still there); through the track model there is no sound to unmute.
- Rule: YouRec/TrackTimeline.swift:575-579 (unlinking removes the link; level, mute and fades are the sound's, :174-177), :771-776 (a sound deleted by itself is gone) (plan: N9)
- Seen in: `TimelineEditing.split` 12, `TimelineEditing.insert` 7 (0 not expressible; 12 together with another kind)
- Accepted: yes

`TimelineEditing.insert(a clip of 2.5 s at 9.967)` on `golden unlinked-sound, step 2`:

```
before
    c1 @0 S1 0–6
    c2 @6 S2 0–6 [muted, -3 dB, fades 0/0.5]
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
today
    c1 @0 S1 0–6
    c2 @6 S2 0–3.967 [muted]
    c4 @9.967 S1 0–2.5
    c5 @12.467 S2 3.967–6 [muted, -3 dB, fades 0/0.5]
    c3 @14.5 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (20.5 s of picture)
through the track model
    c1 @0 S1 0–6
    c2 @6 S2 0–3.967 [muted]
    c4 @9.967 S1 0–2.5
    c6 @12.467 S2 3.967–6 [muted]
    c3 @14.5 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (20.5 s of picture)
```

### fade-no-longer-than-its-item

**A fade set longer than its item is cut to the item's length.**

- Today: The seconds asked for are saved, whatever the length of the clip or the item.
- Track model: A fade is set no longer than the item it is on.
- Visible effect: None is heard: beyond the item's length a fade has nothing to play on. The number shown for the fade is the item's length.
- Rule: YouRec/TrackTimeline.swift:191-195
- Seen in: `TimelineEditing.setAudio` 10, `AudioUnlinking.setSound` 12, `TimelineEditing.setAudioItemSound` 12 (0 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.setAudio(clip 4, fades of 0.3 s)` on `generated 19, step 2`:

```
before
    c1 @0 S1 0–10.741
    c2 @10.741 S1 10.741–12
    c3 @12 S2 0–8
    c4 @20 S3 0–0.133
    c5 @20.133 S3 0.133–6
    (26 s of picture)
today
    c1 @0 S1 0–10.741
    c2 @10.741 S1 10.741–12
    c3 @12 S2 0–8
    c4 @20 S3 0–0.133 [fades 0.3/0.3]
    c5 @20.133 S3 0.133–6
    (26 s of picture)
through the track model
    c1 @0 S1 0–10.741
    c2 @10.741 S1 10.741–12
    c3 @12 S2 0–8
    c4 @20 S3 0–0.133 [fades 0.133/0.133]
    c5 @20.133 S3 0.133–6
    (26 s of picture)
```

### later-piece-new-identity

**What is left after the beginning of an item is cut away has a new id.**

- Today: A clip or a sound item whose beginning is trimmed or cut away keeps its id.
- Track model: A cut gives the later piece a new id and the earlier piece keeps the old one, so when the earlier piece is then deleted, what stays has the new id.
- Visible effect: None on screen. A selection, or anything else that remembers the item by id, no longer finds it.
- Rule: YouRec/TrackTimeline.swift:1015-1017, :1036-1039
- Seen in: `RangeEditing.delete` 34 (0 not expressible; 25 together with another kind)
- Accepted: yes

`RangeEditing.delete(range 17.75–20, the playhead at 16.344)` on `generated 6, step 6`:

```
before
    c1 @0 S2 0–8
    c2 @8 S1 0–12 (into next: fadecolor 2.5) marks 8.69
    c3 @20 S3 0–5.075
    a1 @4 M1 0–30
    a2 @18.367 M2 0–5
    (25.075 s of picture)
today
    c1 @0 S2 0–8
    c2 @8 S1 0–9.75 marks 8.69
    c3 @17.75 S3 0–5.075
    a1 @4 M1 0–13.75
    a3 @20 M1 16–30
    a2 @20 M2 1.633–5
    (22.825 s of picture)
    playhead: 16.344
through the track model
    c1 @0 S2 0–8
    c2 @8 S1 0–9.75 marks 8.69
    c3 @17.75 S3 0–5.075
    a1 @4 M1 0–13.75
    a4 @20 M1 16–30
    a5 @20 M2 1.633–5
    (22.825 s of picture)
    playhead: 16.344
```

## Not explained

Every difference found is of one of the kinds above.

## Fades

A fade longer than half its item, which the sequence form used to play for half the item only, was set in 1598 calls: 1564 identical, 34 not, of which 34 differ in a fade. Those are fades asked longer than the whole item (fade-no-longer-than-its-item).
The sequence form now holds a fade with its length and how far into it the item begins, so a fade capped at half its item is no kind of difference here.

## Ids across the whole edit

Today the id of a bookmark and of a transition is unique within its clip; the track model needs each unique across the timeline (`TrackInvariants`, `DuplicateRelation`). Measured on every step of every golden project, the long one included, on the edits the seeded sequences made, and on every edit a function as it was answered in the suite, each converted to a timeline:

| Edits | Counted | With a bookmark id used twice | With a transition id used twice | Converted timelines the invariants refuse |
|---|---:|---:|---:|---|
| Golden projects, every step | 450 | 0 | 0 | none |
| Seeded sequences | 300 | 0 | 0 | none |
| Answers of the functions as they were, in the suite | 72296 | – | – | none |

## Timelines of the answers

Of the 73130 edits the functions answered in the suite, each converted to a timeline, the invariants (`TrackInvariants.violation`) refuse 0.

## Saved bytes

In 72272 calls both sides answer an edit. In 68440 of them the two edits are the same JSON, byte for byte, once the ids made during the call are renamed in the order they appear: nothing is read through the conversion and no time is rounded. The others, by function:

| Function | Both answer | Not the same bytes | Of which identical as compared above |
|---|---:|---:|---:|
| `TimelineEditing.split` | 1120 | 353 | 1 |
| `TimelineEditing.delete` | 621 | 24 | 0 |
| `TimelineEditing.move` | 1244 | 50 | 0 |
| `TimelineEditing.insert` | 2822 | 467 | 0 |
| `TimelineEditing.trim` | 2102 | 1208 | 630 |
| `TimelineEditing.setSpeed` | 1868 | 20 | 0 |
| `TimelineEditing.setJoin` | 10812 | 113 | 0 |
| `TimelineEditing.setTransition` | 3676 | 20 | 0 |
| `TimelineEditing.setAudio` | 3123 | 10 | 0 |
| `AudioUnlinking.setSound` | 3104 | 12 | 0 |
| `RangeEditing.delete` | 4371 | 694 | 23 |
| `RangeEditing.scoped` | 4519 | 829 | 7 |
| `TimelineEditing.trimAudio` | 579 | 4 | 0 |
| `TimelineEditing.setAudioItemSound` | 900 | 12 | 0 |
| `TimelineEditing.removeAudio` | 300 | 16 | 0 |

## The sample

What the tests check when nothing is set in the environment: the calls on 68 of the edits (the 18 small ones, every 8th step of the golden projects, every 9th seeded edit), the same calls the whole suite makes on them.
14967 calls: 14385 identical, 455 different, 127 not expressible, 0 with something not explained.

| Kind | Calls in the sample |
|---|---:|
| unlinked-picture-does-not-ripple | 69 |
| range-inside-a-transition | 106 |
| handle-trim-outwards-overlaps | 25 |
| handle-trim-inwards-is-a-deletion | 52 |
| words-of-unlinked-sound | 43 |
| removed-transition-leaves-its-time | 27 |
| split-refused-near-an-edge | 122 |
| range-end-within-a-frame-of-a-cut | 108 |
| split-cuts-sound-items | 53 |
| unlinked-clip-remembers-no-sound | 3 |
| fade-no-longer-than-its-item | 6 |
| later-piece-new-identity | 4 |

## Not compared

- `TimelineEditing.removeIntervals`, the functions of `RangeEditing` and those of `AudioUnlinking` keep the bodies they had (`RangeEditing` over the functions that changed); they are compared all the same.
- `UnusedTakes.add` is `TimelineEditing.insert` with whole takes, then a search for the first inserted clip by its id; `insert` keeps the ids it is given.
- `TimelineEditing.layout`, `fitted`, `join`, `transitions`, `entering`, `leaving`, `locate`, `chains`, `overlap`, `joinMaximum`, `joinSettings`, `transition`, `titlesAt`, `titleRange`, `clipRange`, `newTitleRange`, `soundLevel`, `audioAt`, `nextBookmark`, `previousBookmark`, `bookmarkAt`, `trimmed`; `RangeEditing.quantize`, `snap`, `markTime`, `interval`; `SubtitleEditing.active`, `fingerprint`; `AudioUnlinking.detachedSound`, `isDetached`, `clipIndex`, `refusal`, `matches`, `valid`: questions about a sequence that are asked of the sequence form, or whose answer is pinned by the render plan of the golden projects.
- What is drawn and heard is not compared here: that is the render plan (`RenderPlan`), compared when the renderer reads the timeline.
