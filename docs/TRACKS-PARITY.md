# Parity of today's editing functions with the track model

Written by `TrackParityTest` (`core/src/test/kotlin/app/takes/core/TrackParity*.kt`); do not edit by hand.

## Running it

The suite is one test of the core tests: `./gradlew :core:test`, or by itself with `--tests app.takes.core.TrackParityTest`. What it does is chosen in the environment:

- **Nothing set.** It makes the calls of a sample of the edits, about a seventh of them and the same every run, and compares what it finds with the section [The sample](#the-sample) of this file. It fails when that section is not what it finds now, or when the track model answers a timeline its invariants refuse. It does not fail on a difference between today and the track model: a difference that is written down here is expected. This is what the ordinary run of the tests, and the CI, pays for: the whole suite takes about as long as all the other core tests together.
- **`TAKES_PARITY_FULL=1`.** It makes every call and compares the whole of this file, examples included, in the same way. Run it before and after a change to the editing functions or to the track model.
- **`TAKES_RECORD_PARITY=1`.** It makes every call and writes this file anew instead of comparing. Read what changed (`git diff docs/TRACKS-PARITY.md`) and commit the file with the change that caused it.
- **`TAKES_PARITY_GATE=1`.** As `TAKES_PARITY_FULL=1`, and it also fails on every call that shows a kind of difference not listed in `TrackParityAccepted.kt`, or a difference no kind explains: the failure names each such kind with the number of calls that show it, then what is not explained. While that list is empty it fails on every kind below. A kind goes into the list once it is decided what the function that shows it does when it runs on the track model.

## What is compared

Every function of `TimelineEditing`, `RangeEditing`, `SubtitleEditing` and `AudioUnlinking` that changes an edit, and the questions among them that the track model also answers, is called twice on the same edit with the same arguments:
as it is today, and through the track model (`TrackSequenceView`: the edit is converted to a timeline, the operation the function is to become is run, and the result is brought back to a sequence).
Today's answer is read as the conversion reads it (fits saved, settings that do not play dropped, sound tracks repacked), so only what plays differently counts.
Ids made during the call, the track of a sound item, the order of a clip's bookmarks, and times less than three ticks (1/80000 s) apart are not differences. Where a sound is muted on both sides, its level, fades and noise reduction are compared as one thing: what it would sound like unmuted.

- **identical**: the same edit and the same answer, or neither applies.
- **different**: both give a sequence, and the two differ.
- **not expressible**: the track model's result is a valid timeline that no sequence of clips with two sound tracks plays.

The edits: 18 small ones made for one situation each, 128 steps of the histories of the golden projects (all but the long one), and 300 made by 30 seeded sequences of today's functions. 94997 calls.

## By function

| Function | Calls | Identical | Different | Not expressible | Kinds |
|---|---:|---:|---:|---:|---|
| `TimelineEditing.split` | 1931 | 681 | 1239 | 11 | [split-on-the-grid](#split-on-the-grid), [split-refused-near-an-edge](#split-refused-near-an-edge), [split-cuts-sound-items](#split-cuts-sound-items), [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [fades-through-retimes-and-cuts](#fades-through-retimes-and-cuts) |
| `TimelineEditing.delete` | 621 | 536 | 3 | 82 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [bookmark-also-marks-the-unlinked-sound](#bookmark-also-marks-the-unlinked-sound), [later-piece-new-identity](#later-piece-new-identity) |
| `TimelineEditing.move` | 1396 | 1231 | 11 | 154 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [move-finds-no-cut-among-unlinked-pictures](#move-finds-no-cut-among-unlinked-pictures) |
| `TimelineEditing.insert` | 2822 | 0 | 2579 | 243 | [insert-new-identity](#insert-new-identity), [split-refused-near-an-edge](#split-refused-near-an-edge), [insert-inside-a-transition](#insert-inside-a-transition), [insert-moves-sound-items](#insert-moves-sound-items), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [footage-put-after-the-picture](#footage-put-after-the-picture), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `TimelineEditing.trim` | 3080 | 2791 | 69 | 220 | [trim-removes-an-opening-of-a-middle-clip](#trim-removes-an-opening-of-a-middle-clip), [fades-through-retimes-and-cuts](#fades-through-retimes-and-cuts), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [noise-reduction-stays-on-its-footage](#noise-reduction-stays-on-its-footage) |
| `TimelineEditing.trim, as a handle` | 3080 | 1828 | 644 | 608 | [later-piece-new-identity](#later-piece-new-identity), [handle-trim-inwards-is-a-deletion](#handle-trim-inwards-is-a-deletion), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [handle-trim-outwards-overlaps](#handle-trim-outwards-overlaps) |
| `TimelineEditing.removeIntervals` | 2464 | 1303 | 642 | 519 | [split-refused-near-an-edge](#split-refused-near-an-edge), [pauses-leave-a-transition-without-footage](#pauses-leave-a-transition-without-footage), [fades-through-retimes-and-cuts](#fades-through-retimes-and-cuts), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [bookmark-outlives-its-footage](#bookmark-outlives-its-footage), [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `TimelineEditing.setSpeed` | 2464 | 2289 | 2 | 173 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.toggleBookmark` | 1250 | 634 | 453 | 163 | [bookmark-at-the-last-second](#bookmark-at-the-last-second), [bookmark-also-marks-the-unlinked-sound](#bookmark-also-marks-the-unlinked-sound) |
| `TimelineEditing.setJoin` | 15917 | 15312 | 12 | 593 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [close-that-holds-pushes-nothing](#close-that-holds-pushes-nothing) |
| `TimelineEditing.setTransition` | 5253 | 5003 | 6 | 244 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.setText` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.setColor` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.setCrop` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.placeTitle` | 610 | 610 | 0 | 0 |  |
| `TimelineEditing.setAudio` | 3708 | 3595 | 113 | 0 | [sound-settings-of-an-unlinked-clip](#sound-settings-of-an-unlinked-clip), [fade-no-longer-than-its-item](#fade-no-longer-than-its-item) |
| `AudioUnlinking.setSound` | 3708 | 3690 | 18 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound), [fade-no-longer-than-its-item](#fade-no-longer-than-its-item) |
| `TimelineEditing.addAudio` | 1062 | 925 | 54 | 83 | [third-sound](#third-sound), [captions-fingerprint](#captions-fingerprint) |
| `RangeEditing.delete` | 4519 | 3199 | 620 | 700 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [later-piece-new-identity](#later-piece-new-identity), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound), [bookmark-outlives-its-footage](#bookmark-outlives-its-footage), [playhead-in-time-a-transition-holds](#playhead-in-time-a-transition-holds), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [captions-fingerprint](#captions-fingerprint), [bookmark-also-marks-the-unlinked-sound](#bookmark-also-marks-the-unlinked-sound) |
| `RangeEditing.copy` | 4519 | 3636 | 666 | 217 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [caption-look-travels-with-any-words](#caption-look-travels-with-any-words), [words-of-unlinked-sound](#words-of-unlinked-sound), [fades-through-retimes-and-cuts](#fades-through-retimes-and-cuts), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [later-piece-new-identity](#later-piece-new-identity), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [bookmark-also-marks-the-unlinked-sound](#bookmark-also-marks-the-unlinked-sound), [copy-keeps-time-that-sound-fills](#copy-keeps-time-that-sound-fills) |
| `RangeEditing.cut` | 4519 | 3217 | 602 | 700 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [later-piece-new-identity](#later-piece-new-identity), [removed-transition-leaves-its-time](#removed-transition-leaves-its-time), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound), [playhead-in-time-a-transition-holds](#playhead-in-time-a-transition-holds), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [captions-fingerprint](#captions-fingerprint), [bookmark-also-marks-the-unlinked-sound](#bookmark-also-marks-the-unlinked-sound) |
| `RangeEditing.paste` | 13557 | 10041 | 1852 | 1664 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [third-sound](#third-sound), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [words-of-unlinked-sound](#words-of-unlinked-sound), [paste-opens-the-length-of-the-fragment](#paste-opens-the-length-of-the-fragment), [fades-through-retimes-and-cuts](#fades-through-retimes-and-cuts), [later-piece-new-identity](#later-piece-new-identity), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [captions-fingerprint](#captions-fingerprint), [bookmark-also-marks-the-unlinked-sound](#bookmark-also-marks-the-unlinked-sound) |
| `RangeEditing.scoped` | 4519 | 3190 | 1106 | 223 | [range-end-within-a-frame-of-a-cut](#range-end-within-a-frame-of-a-cut), [range-inside-a-transition](#range-inside-a-transition), [scope-cuts-sound-items](#scope-cuts-sound-items), [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple), [captions-fingerprint](#captions-fingerprint) |
| `SubtitleEditing.install` | 446 | 373 | 66 | 7 | [words-of-unlinked-sound](#words-of-unlinked-sound), [transcription-hears-sound-items](#transcription-hears-sound-items) |
| `SubtitleEditing.cues` | 446 | 429 | 17 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `SubtitleEditing.transcript` | 446 | 429 | 17 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `SubtitleEditing.groupingAvailable` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.stretches` | 446 | 239 | 207 | 0 | [transcription-hears-sound-items](#transcription-hears-sound-items), [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `AudioUnlinking.unlink` | 612 | 418 | 138 | 56 | [unlink-does-not-bake-the-transition](#unlink-does-not-bake-the-transition), [unlink-is-never-refused](#unlink-is-never-refused), [third-sound](#third-sound), [captions-fingerprint](#captions-fingerprint) |
| `AudioUnlinking.relink` | 612 | 612 | 0 | 0 |  |
| `AudioUnlinking.restoreSync` | 612 | 611 | 1 | 0 | [sync-is-measured-on-footage-that-shows](#sync-is-measured-on-footage-that-shows) |
| `AudioUnlinking.syncOffset` | 612 | 611 | 1 | 0 | [sync-is-measured-on-footage-that-shows](#sync-is-measured-on-footage-that-shows) |
| `EditState.duration` | 446 | 313 | 133 | 0 | [duration-with-sound](#duration-with-sound) |
| `TimelineEditing.clipStart` | 446 | 446 | 0 | 0 |  |
| `RangeEditing.joinTimes` | 446 | 446 | 0 | 0 |  |
| `TimelineEditing.bookmarkTimes` | 446 | 446 | 0 | 0 |  |
| `RangeEditing.transitionAt` | 1931 | 1931 | 0 | 0 |  |
| `TimelineEditing.moveAudio` | 897 | 839 | 54 | 4 | [captions-fingerprint](#captions-fingerprint), [third-sound](#third-sound), [words-of-unlinked-sound](#words-of-unlinked-sound), [sound-tracks-are-laid-anew](#sound-tracks-are-laid-anew) |
| `TimelineEditing.trimAudio` | 897 | 342 | 551 | 4 | [later-piece-new-identity](#later-piece-new-identity), [split-refused-near-an-edge](#split-refused-near-an-edge), [words-of-unlinked-sound](#words-of-unlinked-sound), [captions-fingerprint](#captions-fingerprint) |
| `TimelineEditing.setAudioItemSound` | 897 | 868 | 29 | 0 | [captions-fingerprint](#captions-fingerprint), [fade-no-longer-than-its-item](#fade-no-longer-than-its-item) |
| `TimelineEditing.removeAudio` | 299 | 262 | 37 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound), [captions-fingerprint](#captions-fingerprint) |
| `SubtitleEditing.remove` | 81 | 81 | 0 | 0 |  |
| `SubtitleEditing.restyle` | 81 | 81 | 0 | 0 |  |
| `SubtitleEditing.regroup` | 243 | 242 | 1 | 0 | [regroup-at-the-count-it-has](#regroup-at-the-count-it-has) |
| `SubtitleEditing.correct` | 174 | 174 | 0 | 0 |  |
| `SubtitleEditing.delete` | 174 | 174 | 0 | 0 |  |
| `TimelineEditing.removeText` | 14 | 14 | 0 | 0 |  |
| **all** | 94997 | 76386 | 11943 | 6668 | |

## The kinds of difference

In the order a user would notice them. A call can show several kinds; it is counted under each. "Plan" is the row of `docs/TRACKS-PLAN.md`, 2.4. The rule is where the original does what the track model does, as a path in its repository.

### unlinked-picture-does-not-ripple

**A clip whose sound is unlinked stays where it is, and nothing closes up behind it.**

- Today: A clip is always part of the sequence: when it or an earlier clip is deleted, trimmed, retimed, moved, has pauses taken out or gets a transition, every later clip moves to close the picture, whether or not its sound was unlinked.
- Track model: Only items in a link of picture and sound ripple. A picture whose sound was unlinked is linked to nothing: deleting it or cutting a range out of it leaves a gap, and when an earlier clip changes length it stays behind, so the timeline gets a gap before it or an overlap with it.
- Visible effect: After Unlink, an ordinary delete, trim, speed change or transition leaves black time or two pictures on top of each other, where today the clips stay one after another. The result cannot be saved as a sequence.
- Rule: YouRec/TrackTimeline.swift:774-799 (only complete links close their time, only links of picture and sound move); YouRec/Transitions.swift:400-410; YouRec/TrackTimeline.swift:1361 (plan: N9)
- Seen in: `TimelineEditing.delete` 48, `TimelineEditing.move` 112, `TimelineEditing.trim` 214, `TimelineEditing.trim, as a handle` 184, `TimelineEditing.removeIntervals` 238, `TimelineEditing.setSpeed` 175, `TimelineEditing.setJoin` 530, `RangeEditing.delete` 389, `RangeEditing.cut` 389, `RangeEditing.paste` 296, `TimelineEditing.insert` 12, `RangeEditing.copy` 11, `RangeEditing.scoped` 12, `TimelineEditing.setTransition` 250 (2830 not expressible; 4 together with another kind)
- Accepted: no

`TimelineEditing.delete(clip 1)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c2 @0 S2 0–8 [muted]
    c3 @8 S3 0–6
    sound of c2 @12 S2 0–8
    (14 s of picture)
through the track model
    no sequence: there is time without picture before a picture
    picture c3 8–14 layer 2
    picture c2 12–20 layer 1
    sound of c2 12–20 (follows nothing)
    sound of c3 8–14
```

### move-finds-no-cut-among-unlinked-pictures

**A clip cannot be moved to a place between pictures that do not meet.**

- Today: Clips are a list: a clip is moved to any place in it, and the others close up around it.
- Track model: The clip is lifted out and put back at the cut between the two pictures it is to go between. When lifting it leaves a picture whose sound is unlinked where it was while the linked pictures close up, those two no longer meet: there is no cut to go to, and the move does not apply.
- Visible effect: Moving a clip to a place in the order stops working around a clip whose sound is unlinked. The clip can still be dragged to a time.
- Rule: YouRec/TimelinePaste.swift:39-44 (a place to paste picture is a join of two pictures that meet); YouRec/TrackTimeline.swift:774-799 (plan: N9)
- Seen in: `TimelineEditing.move` 11 (0 not expressible; 0 together with another kind)
- Accepted: no

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

### trim-removes-an-opening-of-a-middle-clip

**A trim that takes the footage of an opening on a clip that is not the first sends the clip to the start of the timeline.**

- Today: The opening and the transition out of the clip are removed and the clip stays in its place, shorter; later clips close up.
- Track model: The merge of an edited recipe removes both transitions and then places the picture where the transition into it wants it. With the opening gone and no picture leading into it, the fallback is time zero: the picture, its sound and everything after it jump to the start, on top of the earlier clips.
- Visible effect: Trimming a clip that carries an opening (it was once first and was moved) down to less than the opening needs puts it over the first clip. This looks like a defect of the original: the fallback is right only for the first picture. The port follows it.
- Rule: YouRec/TrackTimeline.swift:1387-1391 (plan: N1)
- Seen in: `TimelineEditing.trim` 1 (1 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.trim(clip 2, down to 0.2 s)` on `an opening on a middle clip`:

```
before
    c1 @0 S3 0–6
    c2 @6 S1 0–12 (opens: fadecolor 1)
    c3 @18 S2 0–8
    (26 s of picture)
today
    c1 @0 S3 0–6
    c2 @6 S1 0–0.2
    c3 @6.2 S2 0–8
    (14.2 s of picture)
through the track model
    no sequence: two pictures play at once without a transition between them
    picture c1 0–6 layer 0
    picture c2 0–0.2 layer 3
    picture c3 0.2–8.2 layer 2
    sound of c1 0–6
    sound of c2 0–0.2
    sound of c3 0.2–8.2
```

### range-inside-a-transition

**A range that begins or ends inside a transition cuts both clips there.**

- Today: An end of a range inside a transition is put on the join of the transition, and one on the first instant of an opening of a clip that is not the first is put where that opening ends. A tool on a range with an end inside a transition, its first instant included, applies to nothing and cuts nothing.
- Track model: Both pictures playing there are cut at the exact second. The pieces keep overlapping; the transition stays when both its pictures still have its footage and is removed otherwise, and what is copied can be two pictures that play at once. A range from the first instant of an opening takes the clip with its opening.
- Visible effect: Delete, Cut, Copy and a tool on a range marked into a dissolve leave two pictures on top of each other for the rest of the dissolve's time, or a short gap, where today the edit snaps to the join. What is copied begins where the range does, not at the join, so its sound items lie that much earlier against its picture. A range marked exactly over a clip that carries an opening and is not first takes the whole clip, where today the length of the opening is left behind.
- Rule: YouRec/TrackTimeline.swift:1080-1094 (the items are cut at the range's ends), :857-895 (the prune keeps or drops the relation) (plan: N12)
- Seen in: `RangeEditing.delete` 355, `RangeEditing.copy` 364, `RangeEditing.cut` 355, `RangeEditing.paste` 1137, `RangeEditing.scoped` 431 (1441 not expressible; 86 together with another kind)
- Accepted: no

`RangeEditing.copy(range 10–11.5)` on `a dissolve`:

```
before
    c1 @0 S1 0–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
today
    c4 @0 S1 10–12
    (2 s of picture)
through the track model
    no sequence: two pictures play at once without a transition between them
    picture c5 0–1.5 layer 0
    picture c2 1–1.5 layer 1
    sound of c5 0–1.5
    sound of c2 1–1.5
```

### pauses-leave-a-transition-without-footage

**Pauses taken out of a clip remove a transition that no longer fits its piece, and leave the overlap.**

- Today: The clip becomes pieces; the transition into it stays on the first and the one out of it on the last, each scaled down to fit when its piece is shorter than it needs.
- Track model: Each stretch is deleted as a range of the linked picture and sound. A transition whose picture is left with less footage than it needs is removed by the prune, which moves nothing: the last piece and the next clip still overlap by what the transition took, with no transition between them.
- Visible effect: After Remove pauses on a clip with a dissolve, the dissolve is gone and the end of the clip plays under or over the start of the next for a moment, where today a shorter dissolve plays.
- Rule: YouRec/TrackTimeline.swift:857-895 (pruneRelations); the original keeps one clip with a pause setting and refits there, :1376-1385 (plan: N3)
- Seen in: `TimelineEditing.removeIntervals` 304 (284 not expressible; 3 together with another kind)
- Accepted: no

`TimelineEditing.removeIntervals(clip 1, most of the clip)` on `a dissolve`:

```
before
    c1 @0 S1 0–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
today
    c1 @0 S1 0–1.2
    c4 @1.2 S1 11.4–12 (into next: dissolve 1 ×0.60)
    c2 @1.2 S2 0–8
    c3 @9.2 S3 0–6
    (15.2 s of picture)
through the track model
    no sequence: two pictures play at once without a transition between them
    picture c1 0–1.2 layer 0
    picture c2 1.2–9.2 layer 1
    picture c5 1.6–2.2 layer 0
    picture c3 9.2–15.2 layer 2
    sound of c1 0–1.2
    sound of c5 1.6–2.2
    sound of c2 1.2–9.2
    sound of c3 9.2–15.2
```

### handle-trim-outwards-overlaps

**A trim outwards by the handle reveals footage over the neighbour instead of pushing it.**

- Today: Extending a clip makes it longer and every later clip moves later.
- Track model: Revealing footage moves nothing else: the clip grows over the clip after it (or, at its start, back over the clip before it) and comes in front of it.
- Visible effect: Dragging a handle outwards covers the beginning of the next clip instead of making the movie longer.
- Rule: YouRec/EditorView.swift:1241-1258; YouRec/TrackTimeline.swift:1618-1675 (plan: N2)
- Seen in: `TimelineEditing.trim, as a handle` 283 (266 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.trim, as a handle(clip 1, the end out by 1 s)` on `two short clips`:

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
- Seen in: `TimelineEditing.trim, as a handle` 358 (159 not expressible; 84 together with another kind)
- Accepted: no

`TimelineEditing.trim, as a handle(clip 1, the end in by a third)` on `a dissolve`:

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

### insert-inside-a-transition

**Footage inserted inside a transition leaves the two clips overlapping around it.**

- Today: The transition is removed, both clips get their whole length back, and the clip then showing is cut for the new footage.
- Track model: Time is opened at the place on every lane: both pictures playing there are cut, and the pieces before the place still overlap.
- Visible effect: The first part of the incoming clip plays over the outgoing one before the inserted footage.
- Rule: YouRec/TimelineEditing.swift:86-127; YouRec/TrackTimeline.swift:1145-1161 (plan: N11)
- Seen in: `TimelineEditing.insert` 356 (184 not expressible; 154 together with another kind)
- Accepted: no

`TimelineEditing.insert(a clip of 2.5 s at 11.314)` on `a dissolve`:

```
before
    c1 @0 S1 0–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
today
    c1 @0 S1 0–11.314
    c4 @11.314 S1 0–2.5
    c5 @13.814 S1 11.314–12
    c2 @14.5 S2 0–8
    c3 @22.5 S3 0–6
    (28.5 s of picture)
through the track model
    no sequence: two pictures play at once without a transition between them
    picture c1 0–11.314 layer 0
    picture c2 11–11.314 layer 1
    picture c6 11.314–13.814 layer 3
    picture c7 13.814–14.5 layer 0
    picture c8 13.814–21.5 layer 1
    picture c3 21.5–27.5 layer 2
    sound of c1 0–11.314
    sound of c7 13.814–14.5
    sound of c2 11–11.314
    sound of c8 13.814–21.5
    sound of c3 21.5–27.5
    sound of c6 11.314–13.814
```

### words-of-unlinked-sound

**The words of a clip whose sound is unlinked are on the sound, and show.**

- Today: Words belong to clips. A clip whose sound is unlinked is muted, and a muted clip shows no captions, gets no words from a transcription, and is not listened to; its unlinked sound, an item on a sound track, has no words.
- Track model: Words belong to sounds. The unlinked sound of a clip is an ordinary sound that is not muted: it keeps the words, shows them where it now plays, is transcribed, and loses them when it is deleted. Cut in pieces, each piece keeps them. A copy of the picture alone has no words, and so does not carry the look of the captions.
- Visible effect: After Unlink the captions of that clip stay on screen and follow the sound when it is moved, where today they disappear. Deleting the unlinked sound deletes its captions.
- Rule: YouRec/TrackTimeline.swift:184; YouRec/Subtitles.swift:155-180, :205-246 (plan: N10)
- Seen in: `TimelineEditing.split` 97, `TimelineEditing.trimAudio` 135, `SubtitleEditing.install` 66, `SubtitleEditing.stretches` 75, `TimelineEditing.delete` 9, `TimelineEditing.removeAudio` 16, `RangeEditing.paste` 165, `RangeEditing.delete` 53, `RangeEditing.copy` 67, `RangeEditing.cut` 53, `RangeEditing.scoped` 22, `SubtitleEditing.cues` 17, `SubtitleEditing.transcript` 17, `AudioUnlinking.setSound` 6, `TimelineEditing.removeIntervals` 8, `TimelineEditing.insert` 5, `TimelineEditing.moveAudio` 2 (261 not expressible; 8 together with another kind)
- Accepted: no

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
    c3 @20 S3 0–0.067
    c4 @20.067 S3 0.067–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
```

### transcription-hears-sound-items

**A transcription listens to sound items too.**

- Today: Only the footage that clips with sound play is listened to.
- Track model: Every sound that is not muted is listened to, music and voice-overs on the sound lanes included.
- Visible effect: A voice-over gets captions; music is sent to the recogniser as well. A sound item with words cannot be saved as a sequence.
- Rule: YouRec/Subtitles.swift:205-246 (plan: N10)
- Seen in: `SubtitleEditing.stretches` 132, `SubtitleEditing.install` 7 (7 not expressible; 0 together with another kind)
- Accepted: no

`SubtitleEditing.stretches(what a transcription listens to)` on `music and a voice`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8
    c3 @20 S3 0–6
    a1 @0 M1 0–30 [-12 dB, fades 1/2]
    a2 @3 M2 0–5
    (26 s of picture)
today
    stretches: [[S1, 0, 12], [S2, 0, 8], [S3, 0, 6]]
through the track model
    stretches: [[S1, 0, 12], [S2, 0, 8], [S3, 0, 6], [M1, 0, 30], [M2, 0, 5]]
```

### caption-look-travels-with-any-words

**A copy takes the look of the captions along whenever its sound has words, shown or not.**

- Today: A copied fragment carries the caption settings only when one of its clips shows a word in the footage that was copied.
- Track model: A sound keeps every word of its recording, also those outside the footage it plays. The fragment carries the caption settings whenever a copied sound has any.
- Visible effect: None within one project. Pasted into a project that has no captions yet, such a fragment brings the look of the captions it was copied from although it shows none.
- Rule: YouRec/TimelinePaste.swift:105
- Seen in: `RangeEditing.copy` 20 (0 not expressible; 4 together with another kind)
- Accepted: no

`RangeEditing.copy(range 25.871–26.419)` on `captions`:

```
before
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–8 5 words
    c3 @20 S3 0–6 3 words
    (26 s of picture, with the look of its captions)
today
    c4 @0 S3 5.871–6 3 words
    (0.129 s of picture)
through the track model
    c4 @0 S3 5.871–6 3 words
    (0.129 s of picture, with the look of its captions)
```

### copy-keeps-time-that-sound-fills

**A copy of a selection keeps the time between its items while something copied plays through it.**

- Today: In a copy of a selection or of everything, a sound item that begins after the last copied clip is put right where that clip ends, whatever else plays in between.
- Track model: Only time in which nothing that was copied plays is taken out. A sound item that begins after the picture while another copied sound is still playing keeps its place.
- Visible effect: Pasted, the later sound begins earlier against the other sound than it did where it was copied.
- Rule: YouRec/TimelinePaste.swift:81-103
- Seen in: `RangeEditing.copy` 15 (0 not expressible; 0 together with another kind)
- Accepted: no

`RangeEditing.copy(everything)` on `generated 26, step 8`:

```
before
    c1 @2.5 S1 0–12 (opens: still 2.5) marks 7.262 10 words
    c2 @14.5 S2 0–8 5 words
    c3 @22.5 S3 0–0.628 3 words
    a1 @20.533 M1 0–30
    a2 @26 S4 0–20
    (23.128 s of picture, with the look of its captions)
today
    c4 @2.5 S1 0–12 (opens: still 2.5) marks 7.262 10 words
    c5 @14.5 S2 0–8 5 words
    c6 @22.5 S3 0–0.628 3 words
    a3 @20.533 M1 0–30
    a4 @23.128 S4 0–20
    (23.128 s of picture, with the look of its captions)
through the track model
    c4 @2.5 S1 0–12 (opens: still 2.5) marks 7.262 10 words
    c5 @14.5 S2 0–8 5 words
    c6 @22.5 S3 0–0.628 3 words
    a3 @20.533 M1 0–30
    a4 @26 S4 0–20
    (23.128 s of picture, with the look of its captions)
```

### regroup-at-the-count-it-has

**Regrouping at the count the captions already have groups them again.**

- Today: Asking for the number of words a caption already has does not apply.
- Track model: The words are grouped again at that count, which undoes what typing a caption again or deleting one did to the grouping.
- Visible effect: Choosing the same number of words again splits and joins the captions as at transcription, where today nothing happens.
- Rule: YouRec/Subtitles.swift:192-196
- Seen in: `SubtitleEditing.regroup` 1 (0 not expressible; 0 together with another kind)
- Accepted: no

`SubtitleEditing.regroup(4 words a caption)` on `golden subtitles, step 2`:

```
before
    c1 @0 S1 0–6 10 words
    c2 @6 S2 0–4 5 words
    (10 s of picture, with the look of its captions)
today
    does not apply
through the track model
    c1 @0 S1 0–6 10 words
    c2 @6 S2 0–4 5 words
    (10 s of picture, with the look of its captions)
```

### footage-put-after-the-picture

**Footage can be put after the end of the picture, under sound that runs on.**

- Today: The movie ends with its last clip: footage inserted later than that goes right after the last clip.
- Track model: The timeline ends with its last picture or sound. Under sound that runs past the picture, footage goes in at the second asked for, and the time between the last clip and it has no picture.
- Visible effect: Black between the last clip and footage added further along the music, where today the footage follows the last clip.
- Rule: YouRec/TimelineEditing.swift:89; YouRec/TrackTimeline.swift:503 (plan: N6)
- Seen in: `TimelineEditing.insert` 39 (39 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.insert(a clip of 2.5 s at 13.5)` on `golden sound-tracks, step 5`:

```
before
    c1 @0 S1 0–6
    c2 @6 S2 0–6
    a1 @2.5 S3 2.5–30 [-12 dB, fades 0/2]
    a2 @6.5 S4 0–4
    (12 s of picture)
today
    c1 @0 S1 0–6
    c2 @6 S2 0–6
    c3 @12 S1 0–2.5
    a1 @2.5 S3 2.5–30 [-12 dB, fades 0/2]
    a2 @6.5 S4 0–4
    (14.5 s of picture)
through the track model
    no sequence: there is time without picture before a picture
    picture c1 0–6 layer 0
    picture c2 6–12 layer 1
    picture c4 13.5–16 layer 2
    sound of c1 0–6
    sound of c2 6–12
    sound a1 2.5–13.5 (follows nothing)
    sound a3 16–32.5 (follows nothing) [fade out 2 from 0]
    sound a2 6.5–10.5 (follows nothing)
    sound of c4 13.5–16
```

### paste-opens-the-length-of-the-fragment

**A paste opens the picture by the whole length of what was copied, sound included.**

- Today: A pasted fragment opens the picture by the length of its clips; its sound items are laid over what is there.
- Track model: The picture is opened by the length of the fragment, which is that of its longest item: a copied range whose sound (or whose opening or close) reaches further than its picture leaves time without picture after the pasted clips.
- Visible effect: A gap of black after a pasted range that held music running past its picture.
- Rule: YouRec/TimelinePaste.swift:136-150
- Seen in: `RangeEditing.paste` 225 (225 not expressible; 0 together with another kind)
- Accepted: no

`RangeEditing.paste(a copy of range 10.275–12.367, at 0)` on `golden sound-tracks, step 1`:

```
before
    c1 @0 S1 0–6
    c2 @6 S2 0–6
    a1 @0 S3 0–30
    (12 s of picture)
today
    c3 @0 S2 4.275–6
    c1 @1.725 S1 0–6
    c2 @7.725 S2 0–6
    a1 @0 S3 0–30
    a2 @0 S3 10.275–12.367
    (13.725 s of picture)
    begins at: 0
through the track model
    no sequence: there is time without picture before a picture
    picture c4 0–1.725 layer 2
    picture c1 2.092–8.092 layer 0
    picture c2 8.092–14.092 layer 1
    sound of c1 2.092–8.092
    sound of c2 8.092–14.092
    sound a1 0–30 (follows nothing)
    sound of c4 0–1.725
    sound a3 0–2.092 (follows nothing)
    begins at: 0
```

### playhead-in-time-a-transition-holds

**A playhead in time a transition holds stays where it is.**

- Today: After a deletion the playhead is carried to the frame of footage it was on. In the time an opening or a still holds, no clip plays: the playhead is put on the edge of the clip that time belongs to, the first frame after an opening or the last before a still, even when nothing before it moved.
- Track model: The playhead moves only by the time that was closed before it, or with the picture or the sound it was on.
- Visible effect: Deleting something later in the movie, or a sound item, while the playhead stands in the held time of an opening or a still makes the playhead jump to the clip, where through the track model it stays.
- Rule: YouRec/TrackTimeline.swift:807-826
- Seen in: `RangeEditing.delete` 12, `RangeEditing.cut` 12 (0 not expressible; 3 together with another kind)
- Accepted: no

`RangeEditing.delete(selected: clip 4, the playhead at 9.01)` on `golden everything, step 6`:

```
before
    c1 @0 S1 0–9 (opens: fadecolor 0.5) (into next: still 1)
    c2 @10 S2 0–7 at ×1.5 (into next: whip 0.267)
    c3 @14.4 S3 0–8 (into next: dissolve 1)
    c4 @21.1 S4 0–5 (closes: dissolve 0.75)
    (26.1 s of picture)
today
    c1 @0 S1 0–9 (opens: fadecolor 0.5) (into next: still 1)
    c2 @10 S2 0–7 at ×1.5 (into next: whip 0.267)
    c3 @14.4 S3 0–8
    (22.4 s of picture)
    playhead: 9
through the track model
    c1 @0 S1 0–9 (opens: fadecolor 0.5) (into next: still 1)
    c2 @10 S2 0–7 at ×1.5 (into next: whip 0.267)
    c3 @14.4 S3 0–8
    (22.4 s of picture)
    playhead: 9.01
```

### removed-transition-leaves-its-time

**Time a transition had inserted stays empty when the transition goes.**

- Today: Clips always follow one another: when a transition that held the next clip back (a still, a fade through a color with a hold, an opening whose sound leads) is removed, replaced, or goes with its clip, the later clips close up.
- Track model: A deletion closes the time its pictures covered and no more, and setting another opening moves no picture: the time the transition had inserted stays, without picture.
- Visible effect: A stretch of black where the still or the hold was, after its clip is deleted, moved or cut away, or after the opening is changed.
- Rule: YouRec/TrackTimeline.swift:771-799 (what closes is the time the removed pictures covered); YouRec/Transitions.swift:364-377
- Seen in: `RangeEditing.delete` 136, `RangeEditing.cut` 136, `TimelineEditing.move` 42, `TimelineEditing.delete` 23, `TimelineEditing.setJoin` 74, `TimelineEditing.insert` 3 (262 not expressible; 144 together with another kind)
- Accepted: no

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
- Accepted: no

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

### bookmark-also-marks-the-unlinked-sound

**A bookmark on footage also marks the unlinked sound of that footage.**

- Today: A bookmark is on its clip and nowhere else.
- Track model: A bookmark is on a source second of an occurrence, which the picture and its unlinked sound both play: a copy of the sound takes a copy of the mark along, and a mark whose picture is deleted stays on the sound.
- Visible effect: A second bookmark where the copied sound is pasted; a bookmark that survives the deletion of its clip. Neither can be saved in the sequence form.
- Rule: YouRec/TrackTimeline.swift:1162-1174; YouRec/TimelinePaste.swift:76-80
- Seen in: `TimelineEditing.toggleBookmark` 19, `RangeEditing.copy` 14, `RangeEditing.paste` 42, `RangeEditing.delete` 2, `RangeEditing.cut` 2, `TimelineEditing.delete` 2 (81 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.toggleBookmark(clip 3 at source second 6 (its last second))` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6 marks 6
    sound of c2 @12 S2 0–8
    (26 s of picture)
through the track model
    no sequence: a bookmark is on a sound, on a held frame or on the timeline itself
    picture c1 0–12 layer 0
    picture c2 12–20 layer 1
    picture c3 20–26 layer 2
    sound of c1 0–12
    sound of c2 12–20 (follows nothing)
    sound of c3 20–26
```

### third-sound

**A third sound playing at once is accepted.**

- Today: There are two sound tracks. Adding a sound where both are busy is refused, unlinking a clip there is refused, and a pasted sound item that fits on neither is left out.
- Track model: Any number of sounds play at once.
- Visible effect: Three or more sounds mixed where today the third is refused or dropped. The result cannot be saved as a sequence.
- Rule: YouRec/TrackTimeline.swift:217-222 (plan: N7)
- Seen in: `TimelineEditing.addAudio` 83, `RangeEditing.paste` 333, `AudioUnlinking.unlink` 15, `TimelineEditing.moveAudio` 4 (435 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.addAudio(30 s of sound at 0)` on `music and a voice`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8
    c3 @20 S3 0–6
    a1 @0 M1 0–30 [-12 dB, fades 1/2]
    a2 @3 M2 0–5
    (26 s of picture)
today
    does not apply
through the track model
    no sequence: more than two sounds of their own play at once
    picture c1 0–12 layer 0
    picture c2 12–20 layer 1
    picture c3 20–26 layer 2
    sound of c1 0–12
    sound of c2 12–20
    sound of c3 20–26
    sound a1 0–30 (follows nothing) [fade in 1 from 0, fade out 2 from 0]
    sound a2 3–8 (follows nothing)
    sound a3 0–30 (follows nothing)
```

### sound-tracks-are-laid-anew

**A sound is not kept from a place by the track it is on.**

- Today: A sound item stays on its track. A move is refused where its own track and the other are both busy, even when the items would fit on two tracks laid out again.
- Track model: There are no tracks: any sounds may play at once. Written as a sequence the sounds are laid on two tracks anew, so a move applies whenever no more than two sounds play at once after it.
- Visible effect: A move of a sound that today is refused goes through, and other items change track.
- Rule: YouRec/TrackTimeline.swift:217-222 (plan: N7)
- Seen in: `TimelineEditing.moveAudio` 1 (0 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.moveAudio(the item at 3.226 to 0)` on `generated 25, step 10`:

```
before
    c1 @0 S1 0–0.972
    c2 @0.972 S1 3.226–10.336
    c3 @8.082 S1 11.176–12 [muted]
    c4 @8.906 S2 0–8 (into next: hitchcock 2.5 ×0.72) marks 6.989
    c5 @15.106 S3 0–1.8
    c6 @16.906 S3 2.4–3.6
    c7 @18.106 S3 4.2–6
    a1 @0.667 M1 0–0.305
    a2 @3.226 M1 2.559–30
    a3 @18.233 M1 0–30
    (19.906 s of picture)
today
    does not apply
through the track model
    c1 @0 S1 0–0.972
    c2 @0.972 S1 3.226–10.336
    c3 @8.082 S1 11.176–12 [muted]
    c4 @8.906 S2 0–8 (into next: hitchcock 2.5 ×0.72) marks 6.989
    c5 @15.106 S3 0–1.8
    c6 @16.906 S3 2.4–3.6
    c7 @18.106 S3 4.2–6
    a2 @0 M1 2.559–30
    a1 @0.667 M1 0–0.305
    a3 @18.233 M1 0–30
    (19.906 s of picture)
```

### split-refused-near-an-edge

**A cut within 0.1 s of an edge is made instead of refused.**

- Today: A split, a trim of a sound item or a pause removal that would leave a piece shorter than 0.1 s is refused, or the short piece is dropped; footage inserted that close to a cut goes to the cut.
- Track model: A cut is refused only when a piece would be shorter than one tick; pieces of any length are kept, and footage is inserted exactly where asked.
- Visible effect: Clips and sound pieces a few frames long, which today cannot be made.
- Rule: YouRec/TrackTimeline.swift:1011-1014 (plan: N8)
- Seen in: `TimelineEditing.split` 367, `TimelineEditing.insert` 422, `TimelineEditing.removeIntervals` 541, `TimelineEditing.trimAudio` 189 (0 not expressible; 199 together with another kind)
- Accepted: no

`TimelineEditing.split(at 0.05)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    does not apply
through the track model
    c1 @0 S1 0–0.067
    c2 @0.067 S1 0.067–12
    (12 s of picture)
```

### split-on-the-grid

**A cut at the playhead is put on the 1/30 s grid.**

- Today: The clip is cut at the exact second asked for.
- Track model: The second is first put on the nearest thirtieth of a second, counted from the start of the timeline.
- Visible effect: The cut can fall up to half a frame (1/60 s) away from where today puts it; after it, the two pieces differ from today's by that much footage.
- Rule: YouRec/TimelineEditing.swift:42-47 (plan: N8)
- Seen in: `TimelineEditing.split` 596 (0 not expressible; 161 together with another kind)
- Accepted: no

`TimelineEditing.split(at 8.876)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    c1 @0 S1 0–8.876
    c2 @8.876 S1 8.876–12
    (12 s of picture)
through the track model
    c1 @0 S1 0–8.867
    c3 @8.867 S1 8.867–12
    (12 s of picture)
```

### range-end-within-a-frame-of-a-cut

**A range end within a frame of a cut is not put on the cut.**

- Today: An end of a range closer than one frame to a clip's or an item's edge, or to where a transition begins or ends, is that place: no sliver is cut, copied or left behind, and a paste that close goes exactly there.
- Track model: The items are cut exactly where the range ends, or where the paste is asked for, so a piece shorter than a frame is made, kept or copied. An opening stays on the sliver, away from what is copied.
- Visible effect: A range marked a hair inside a clip leaves or copies a sliver of a few hundredths of a second, and what is copied comes without the opening of its clip.
- Rule: YouRec/EditorView.swift:1125-1130; YouRec/TrackTimeline.swift:1080-1094
- Seen in: `RangeEditing.delete` 345, `RangeEditing.copy` 389, `RangeEditing.cut` 345, `RangeEditing.paste` 1238, `RangeEditing.scoped` 581 (0 not expressible; 262 together with another kind)
- Accepted: no

`RangeEditing.copy(range 0.02–6)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    c2 @0 S1 0–6
    (6 s of picture)
through the track model
    c2 @0 S1 0.02–6
    (5.98 s of picture)
```

### split-cuts-sound-items

**A cut at the playhead also cuts the sound items under it.**

- Today: Only the clip is cut; music or a voice-over running under the playhead stays one item, and so does the unlinked sound of the clip.
- Track model: With nothing selected, everything under the playhead is cut: the picture, its sound, and every other sound playing there.
- Visible effect: The music is in two pieces after a split. They play on without a break, and each can then be moved or deleted by itself.
- Rule: YouRec/TimelineEditing.swift:45-62
- Seen in: `TimelineEditing.split` 343 (0 not expressible; 160 together with another kind)
- Accepted: no

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

### scope-cuts-sound-items

**A tool on a range of everything cuts the sound items at the range's ends.**

- Today: A range cuts the clips at its two ends; sound items are left whole.
- Track model: A range over everything names every item: the sounds playing across an end of the range are cut there too.
- Visible effect: Music is in pieces after a tool was applied to a marked range. The pieces play on without a break.
- Rule: YouRec/TrackTimeline.swift:556-558, :1080-1094
- Seen in: `RangeEditing.scoped` 315 (0 not expressible; 20 together with another kind)
- Accepted: no

`RangeEditing.scoped(range 16.42–17.453)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c1 @0 S1 0–12
    c2 @12 S2 0–4.42 [muted]
    c4 @16.42 S2 4.42–5.453 [muted]
    c5 @17.453 S2 5.453–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
    clips a tool applies to: [3]
through the track model
    c1 @0 S1 0–12
    c2 @12 S2 0–4.42 [muted]
    c6 @16.42 S2 4.42–5.453 [muted]
    c7 @17.453 S2 5.453–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–4.42
    sound of c6 @16.42 S2 4.42–5.453
    sound of c7 @17.453 S2 5.453–8
    (26 s of picture)
    clips a tool applies to: [3]
```

### insert-moves-sound-items

**Inserted footage moves, and cuts, the sound items after it.**

- Today: Footage put in at a place moves the later clips; sound items stay where they are, uncut.
- Track model: Time is opened on every lane: a sound item playing across the place is cut there, and every sound from there on moves later with the pictures.
- Visible effect: Music keeps its place against the picture it was under, where today it keeps its place on the clock; a piece of music that crossed the place is in two pieces with silence between them.
- Rule: YouRec/TimelineEditing.swift:86-127; YouRec/TrackTimeline.swift:1145-1161
- Seen in: `TimelineEditing.insert` 1115 (0 not expressible; 1115 together with another kind)
- Accepted: no

`TimelineEditing.insert(a clip of 2.5 s at 19.408)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c1 @0 S1 0–12
    c2 @12 S2 0–7.408 [muted]
    c4 @19.408 S1 0–2.5
    c5 @21.908 S2 7.408–8 [muted]
    c3 @22.5 S3 0–6
    sound of c2 @12 S2 0–8
    (28.5 s of picture)
through the track model
    c1 @0 S1 0–12
    c2 @12 S2 0–7.408 [muted]
    c6 @19.408 S1 0–2.5
    c7 @21.908 S2 7.408–8 [muted]
    c3 @22.5 S3 0–6
    sound of c2 @12 S2 0–7.408
    sound of c7 @21.908 S2 7.408–8
    (28.5 s of picture)
```

### bookmark-at-the-last-second

**A bookmark at the very end of a clip goes to what plays next.**

- Today: A bookmark at the clip's last source second is saved on the clip.
- Track model: The bookmark is attached to what plays at that moment of the timeline, which is the next clip's first frame, or a sound, or the timeline itself after the last clip.
- Visible effect: The mark is drawn in the same place but belongs to the next clip and follows that one; after the last clip it is a mark on the timeline and no clip has it.
- Rule: YouRec/TrackTimeline.swift:1162-1174
- Seen in: `TimelineEditing.toggleBookmark` 597 (144 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.toggleBookmark(clip 1 at source second 12 (its last second))` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    c1 @0 S1 0–12 marks 12
    (12 s of picture)
through the track model
    no sequence: a bookmark is on a sound, on a held frame or on the timeline itself
    picture c1 0–12 layer 0
    sound of c1 0–12
```

### bookmark-outlives-its-footage

**A bookmark on footage that is cut away is kept.**

- Today: A bookmark inside footage that pauses or a range take out is deleted with it.
- Track model: The bookmark stays in the timeline without a place, and shows again when that footage comes back.
- Visible effect: None until the footage is revealed again by a trim outwards, when the mark reappears. In the sequence form it is listed on the piece before the footage.
- Rule: YouRec/TrackTimeline.swift:1162-1174 (resolved bookmarks leave out a mark whose footage is not played)
- Seen in: `TimelineEditing.removeIntervals` 26, `RangeEditing.delete` 19 (0 not expressible; 2 together with another kind)
- Accepted: no

`TimelineEditing.removeIntervals(clip 1, two stretches)` on `bookmarks and a title`:

```
before
    c1 @0 S1 0–12 marks 2,9 1 titles
    c2 @12 S2 0–8 marks 4
    c3 @20 S3 0–6
    (26 s of picture)
today
    c1 @0 S1 0–2.4 marks 2 1 titles
    c4 @2.4 S1 3.6–7.2 1 titles
    c5 @6 S1 9.6–12 1 titles
    c2 @8.4 S2 0–8 marks 4
    c3 @16.4 S3 0–6
    (22.4 s of picture)
through the track model
    c1 @0 S1 0–2.4 marks 2 1 titles
    c6 @2.4 S1 3.6–7.2 marks 9 1 titles
    c7 @6 S1 9.6–12 1 titles
    c2 @8.4 S2 0–8 marks 4
    c3 @16.4 S3 0–6
    (22.4 s of picture)
```

### unlinked-clip-remembers-no-sound

**A clip whose sound is unlinked remembers no sound of its own.**

- Today: Unlinking mutes the clip and leaves on it the level and the fades it had, for linking again. A piece cut from that clip, and the clip itself once its unlinked sound is deleted, is an ordinary muted clip with those settings: unmuting it plays the sound of its footage again.
- Track model: A picture whose sound is unlinked has no sound. Its pieces are pictures without sound, and deleting the unlinked sound leaves a picture without sound; in the sequence form each is a muted clip that remembers nothing.
- Visible effect: None while the clip is muted. Today Unmute on such a piece brings the recorded sound back at the level it had (a second time, beside the unlinked sound, while that is still there); through the track model there is no sound to unmute.
- Rule: YouRec/TrackTimeline.swift:575-579 (unlinking removes the link; level, mute and fades are the sound's, :174-177), :771-776 (a sound deleted by itself is gone) (plan: N9)
- Seen in: `TimelineEditing.split` 12, `TimelineEditing.insert` 7, `TimelineEditing.removeAudio` 4, `RangeEditing.scoped` 7, `RangeEditing.paste` 24, `RangeEditing.copy` 6, `RangeEditing.delete` 4, `RangeEditing.cut` 4 (0 not expressible; 25 together with another kind)
- Accepted: no

`RangeEditing.copy(selected: clip 2)` on `golden unlinked-sound, step 2`:

```
before
    c1 @0 S1 0–6
    c2 @6 S2 0–6 [muted, -3 dB, fades 0/0.5]
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
today
    c4 @0 S2 0–6 [muted, -3 dB, fades 0/0.5]
    (6 s of picture)
through the track model
    c4 @0 S2 0–6 [muted]
    (6 s of picture)
```

### fade-no-longer-than-its-item

**A fade set longer than its item is cut to the item's length.**

- Today: The seconds asked for are saved, whatever the length of the clip or the item.
- Track model: A fade is set no longer than the item it is on.
- Visible effect: None is heard: beyond the item's length a fade has nothing to play on. The number shown for the fade is the item's length.
- Rule: YouRec/TrackTimeline.swift:191-195
- Seen in: `TimelineEditing.setAudio` 8, `AudioUnlinking.setSound` 12, `TimelineEditing.setAudioItemSound` 12 (0 not expressible; 0 together with another kind)
- Accepted: no

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

### fades-through-retimes-and-cuts

**A fade is squeezed when its item gets shorter than it, and sliced by a cut.**

- Today: A clip keeps the fade seconds it has whatever is done to it; pieces made by pauses or a range keep a fade only at the clip's own two ends, from silence.
- Track model: When a clip gets shorter than its fade (a trim, a faster speed), the fade is scaled to the new length. A cut inside a fade leaves each piece its part of the slope.
- Visible effect: The same sound in most cases. Where a clip is trimmed inside its fade, the level at the new edge differs.
- Rule: YouRec/TrackTimeline.swift:133-151, :196-204, :1326-1329 (plan: N4)
- Seen in: `TimelineEditing.trim` 67, `TimelineEditing.removeIntervals` 47, `RangeEditing.copy` 15, `RangeEditing.paste` 37, `TimelineEditing.split` 15 (0 not expressible; 69 together with another kind)
- Accepted: no

`TimelineEditing.trim(clip 1, the end in by a third)` on `fades and levels`:

```
before
    c1 @0 S1 0–12 [fades 1/2]
    c2 @12 S2 0–8 [-6 dB]
    c3 @20 S3 0–6 [noise reduction 0.5]
    (26 s of picture)
today
    c1 @0 S1 0–8 [fades 1/0]
    c2 @8 S2 0–8 [-6 dB]
    c3 @16 S3 0–6 [noise reduction 0.5]
    (22 s of picture)
through the track model
    c1 @0 S1 0–8 [fades 1/2]
    c2 @8 S2 0–8 [-6 dB]
    c3 @16 S3 0–6 [noise reduction 0.5]
    (22 s of picture)
```

### unlink-does-not-bake-the-transition

**Unlinking does not write the transition's ramp into the sound.**

- Today: The unlinked sound gets a fade as long as the ramp of each transition at the clip's ends, since it no longer follows them.
- Track model: Unlinking removes the link and changes nothing else. The sound keeps its own fades; the transition's ramp stops applying to it.
- Visible effect: The unlinked sound of a clip in a dissolve starts and ends at full level, where today it fades as before.
- Rule: YouRec/TrackTimeline.swift:575-579 (plan: N9)
- Seen in: `AudioUnlinking.unlink` 120 (0 not expressible; 4 together with another kind)
- Accepted: no

`AudioUnlinking.unlink(clip 1)` on `a dissolve`:

```
before
    c1 @0 S1 0–12 (into next: dissolve 1)
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    (25 s of picture)
today
    c1 @0 S1 0–12 (into next: dissolve 1) [muted]
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    sound of c1 @0 S1 0–12 [fades 0/1]
    (25 s of picture)
through the track model
    c1 @0 S1 0–12 (into next: dissolve 1) [muted]
    c2 @11 S2 0–8
    c3 @19 S3 0–6
    sound of c1 @0 S1 0–12
    (25 s of picture)
```

### unlink-is-never-refused

**Unlinking a clip at another speed or with noise reduction is not refused.**

- Today: Unlink is refused for a clip that plays faster or slower than recorded, or that has noise reduction.
- Track model: The link is removed. The sound keeps the picture's timing and its chain of effects.
- Visible effect: Unlink works where today it is greyed out. The result (a sound of its own at another speed, or with a chain) cannot be saved as a sequence.
- Rule: YouRec/TrackTimeline.swift:575-579 (plan: N9)
- Seen in: `AudioUnlinking.unlink` 41 (41 not expressible; 0 together with another kind)
- Accepted: no

`AudioUnlinking.unlink(clip 3)` on `fades and levels`:

```
before
    c1 @0 S1 0–12 [fades 1/2]
    c2 @12 S2 0–8 [-6 dB]
    c3 @20 S3 0–6 [noise reduction 0.5]
    (26 s of picture)
today
    does not apply
through the track model
    no sequence: a sound has a chain of effects other than one noise reduction over all of it, or a censor
    picture c1 0–12 layer 0
    picture c2 12–20 layer 1
    picture c3 20–26 layer 2
    sound of c1 0–12 [fade in 1 from 0, fade out 2 from 0]
    sound of c2 12–20
    sound of c3 20–26 (follows nothing)
```

### sync-is-measured-on-footage-that-shows

**How far an unlinked sound is out of sync is known only while its picture shows the second the sound begins with.**

- Today: The offset is where second 0 of the recording would fall for the sound, less where it would fall for the picture, whatever either was trimmed to.
- Track model: The offset is the sound's start less the place where a picture of the same occurrence shows the source second the sound begins with. When the picture was trimmed past that second there is no such place: no offset is known, and restoring the sync does not apply.
- Visible effect: After the start of a clip is trimmed while its unlinked sound still begins earlier in the recording, Restore sync is not offered, where today it moves the sound by the difference.
- Rule: YouRec/TrackTimeline.swift:1605-1615 (plan: N9)
- Seen in: `AudioUnlinking.restoreSync` 1, `AudioUnlinking.syncOffset` 1 (0 not expressible; 0 together with another kind)
- Accepted: no

`AudioUnlinking.restoreSync(clip 5)` on `generated 9, step 6`:

```
before
    c1 @0 S1 0–3.6 (opens: whip 0.3)
    c2 @3.6 S1 4.8–7.2
    c3 @6 S1 8.4–12
    c4 @9.6 S2 0–8
    c5 @17.6 S3 0.295–4.423 [muted]
    c6 @21.728 S4 0–5.3
    c7 @27.028 S4 5.3–19.825
    sound of c5 @17.6 S3 0–6
    (41.553 s of picture)
today
    c1 @0 S1 0–3.6 (opens: whip 0.3)
    c2 @3.6 S1 4.8–7.2
    c3 @6 S1 8.4–12
    c4 @9.6 S2 0–8
    c5 @17.6 S3 0.295–4.423 [muted]
    c6 @21.728 S4 0–5.3
    c7 @27.028 S4 5.3–19.825
    sound of c5 @17.305 S3 0–6
    (41.553 s of picture)
through the track model
    does not apply
```

### noise-reduction-stays-on-its-footage

**Noise reduction does not reach footage a trim reveals.**

- Today: Noise reduction is a setting of the clip: it applies to whatever footage the clip plays, also after the clip is made longer.
- Track model: Noise reduction is a chain on a stretch of the sound, and stays on the recorded seconds it was set on. Footage revealed by a trim outwards is outside it.
- Visible effect: The revealed part of the clip is heard without noise reduction. The result, a chain over part of a sound, cannot be saved as a sequence.
- Rule: YouRec/TrackTimeline.swift:1275-1300
- Seen in: `TimelineEditing.trim` 7 (7 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.trim(clip 3, the start out by 1 s)` on `generated 16, step 9`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–4.423 [-6 dB]
    c3 @16.423 S2 5.957–8 at ×0.5 [noise reduction 0.6]
    c4 @20.509 S3 0–2.034 [muted]
    c5 @22.543 S3 2.034–6 [muted] marks 2.62
    sound of c4 @18.466 S3 0–6
    a1 @32.433 M2 0–5
    (26.509 s of picture)
today
    c1 @0 S1 0–12
    c2 @12 S2 0–4.423 [-6 dB]
    c3 @16.423 S2 4.957–8 at ×0.5 [noise reduction 0.6]
    c4 @22.509 S3 0–2.034 [muted]
    c5 @24.543 S3 2.034–6 [muted] marks 2.62
    sound of c4 @18.466 S3 0–6
    a1 @32.433 M2 0–5
    (28.509 s of picture)
through the track model
    no sequence: a sound has a chain of effects other than one noise reduction over all of it, or a censor
    picture c1 0–12 layer 0
    picture c2 12–16.423 layer 1
    picture c3 16.423–22.509 layer 5
    picture c4 20.509–22.543 layer 3
    picture c5 24.543–28.509 layer 4
    sound of c1 0–12
    sound of c2 12–16.423
    sound of c3 16.423–22.509
    sound of c4 18.466–24.466 (follows nothing)
    sound of c5 24.543–28.509
    sound a1 32.433–37.433 (follows nothing)
```

### sound-settings-of-an-unlinked-clip

**The sound settings of a clip whose sound is unlinked have nowhere to go.**

- Today: The muted clip remembers a level, fades and noise reduction of its own; `setAudio` changes them, and can unmute the clip, which then plays its sound twice.
- Track model: The picture has no sound of its own to set: the call does not apply.
- Visible effect: None in the app, which sets the sound through `AudioUnlinking.setSound`.
- Rule: YouRec/TrackTimeline.swift:743-745 (plan: N9)
- Seen in: `TimelineEditing.setAudio` 105 (0 not expressible; 0 together with another kind)
- Accepted: no

`TimelineEditing.setAudio(clip 2, 6 dB down)` on `unlinked sound`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [muted]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
today
    c1 @0 S1 0–12
    c2 @12 S2 0–8 [-6 dB]
    c3 @20 S3 0–6
    sound of c2 @12 S2 0–8
    (26 s of picture)
through the track model
    does not apply
```

### insert-new-identity

**Inserted footage gets a new id.**

- Today: The clips are put in under the ids they were given.
- Track model: Every clip put in is a copy with an id of its own.
- Visible effect: None on screen. A caller that looks for the clip it inserted by its id does not find it (`UnusedTakes.add` does).
- Rule: YouRec/TimelineEditing.swift:92-101
- Seen in: `TimelineEditing.insert` 2122 (0 not expressible; 987 together with another kind)
- Accepted: no

`TimelineEditing.insert(a clip of 2.5 s at 0.988)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    c1 @0 S1 0–0.988
    c2 @0.988 S1 0–2.5
    c3 @3.488 S1 0.988–12
    (14.5 s of picture)
through the track model
    c1 @0 S1 0–0.988
    c4 @0.988 S1 0–2.5
    c5 @3.488 S1 0.988–12
    (14.5 s of picture)
```

### later-piece-new-identity

**What is left after the beginning of an item is cut away has a new id.**

- Today: A clip or a sound item whose beginning is trimmed or cut away keeps its id.
- Track model: A cut gives the later piece a new id and the earlier piece keeps the old one, so when the earlier piece is then deleted, what stays has the new id.
- Visible effect: None on screen. A selection, or anything else that remembers the item by id, no longer finds it.
- Rule: YouRec/TrackTimeline.swift:1015-1017, :1036-1039
- Seen in: `TimelineEditing.trim, as a handle` 511, `RangeEditing.delete` 82, `RangeEditing.cut` 82, `TimelineEditing.trimAudio` 214, `RangeEditing.paste` 7, `RangeEditing.copy` 22, `TimelineEditing.delete` 3 (0 not expressible; 166 together with another kind)
- Accepted: no

`TimelineEditing.trim, as a handle(clip 1, the start in by a third)` on `one take`:

```
before
    c1 @0 S1 0–12
    (12 s of picture)
today
    c1 @0 S1 4–12
    (8 s of picture)
through the track model
    c2 @0 S1 4–12
    (8 s of picture)
```

### captions-fingerprint

**Which edits switch regrouping of captions off is different.**

- Today: Regrouping is allowed while the clips are what they were at transcription: their places, source bounds, speed and mute. Sound items do not count.
- Track model: The fingerprint is taken of every sound and every picture: place, timing, mute, source. Adding, moving, trimming or muting a sound item makes it stale; unlinking a clip's sound, which changes none of those, does not.
- Visible effect: The words-per-caption control is switched off after an edit of the music, where today it stays on; it stays on after Unlink, where today it goes off.
- Rule: YouRec/Subtitles.swift:183-190
- Seen in: `TimelineEditing.addAudio` 54, `AudioUnlinking.unlink` 22, `TimelineEditing.moveAudio` 51, `TimelineEditing.trimAudio` 34, `TimelineEditing.setAudioItemSound` 17, `TimelineEditing.removeAudio` 17, `RangeEditing.scoped` 28, `RangeEditing.delete` 22, `RangeEditing.cut` 22, `RangeEditing.paste` 62 (0 not expressible; 49 together with another kind)
- Accepted: no

`TimelineEditing.addAudio(30 s of sound at 0)` on `captions`:

```
before
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–8 5 words
    c3 @20 S3 0–6 3 words
    (26 s of picture, with the look of its captions)
today
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–8 5 words
    c3 @20 S3 0–6 3 words
    a1 @0 M1 0–30
    (26 s of picture, with the look of its captions)
through the track model
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–8 5 words
    c3 @20 S3 0–6 3 words
    a2 @0 M1 0–30
    (26 s of picture, with the look of its captions)
```

### duration-with-sound

**The movie lasts as long as its longest item.**

- Today: The movie ends with its last clip; sound after it is not played.
- Track model: The timeline ends where its last picture or sound ends.
- Visible effect: A project whose music runs past the picture is longer, with black picture under the music.
- Rule: YouRec/TrackTimeline.swift:503 (plan: N6)
- Seen in: `EditState.duration` 133 (0 not expressible; 0 together with another kind)
- Accepted: no

`EditState.duration(how long the movie is)` on `music and a voice`:

```
before
    c1 @0 S1 0–12
    c2 @12 S2 0–8
    c3 @20 S3 0–6
    a1 @0 M1 0–30 [-12 dB, fades 1/2]
    a2 @3 M2 0–5
    (26 s of picture)
today
    seconds: 26
through the track model
    seconds: 30
```

## Not explained

Every difference found is of one of the kinds above.

## Fades

A fade longer than half its item, which the sequence form used to play for half the item only, was set in 1597 calls: 1542 identical, 55 not, of which 32 differ in a fade. Those are fades asked longer than the whole item (fade-no-longer-than-its-item).
The sequence form now holds a fade with its length and how far into it the item begins, so a fade capped at half its item is no kind of difference here.

## Ids across the whole edit

Today the id of a bookmark and of a transition is unique within its clip; the track model needs each unique across the timeline (`TrackInvariants`, `DuplicateRelation`). Measured on every step of every golden project, the long one included, on the edits the seeded sequences made, and on every edit a function of today answered in the suite, each converted to a timeline:

| Edits | Counted | With a bookmark id used twice | With a transition id used twice | Converted timelines the invariants refuse |
|---|---:|---:|---:|---|
| Golden projects, every step | 450 | 0 | 0 | none |
| Seeded sequences | 300 | 0 | 0 | none |
| Answers of today's functions in the suite | 74567 | – | – | none |

## Timelines the track model answered

Of the 76288 timelines the operations of the track model answered in the suite, the invariants (`TrackInvariants.violation`) refuse 0.

## The sample

What the tests check when nothing is set in the environment: the calls on 68 of the edits (the 18 small ones, every 8th step of the golden projects, every 9th seeded edit), the same calls the whole suite makes on them.
15642 calls: 12651 identical, 1877 different, 1114 not expressible, 0 with something not explained.

| Kind | Calls in the sample |
|---|---:|
| unlinked-picture-does-not-ripple | 581 |
| trim-removes-an-opening-of-a-middle-clip | 1 |
| range-inside-a-transition | 350 |
| pauses-leave-a-transition-without-footage | 48 |
| handle-trim-outwards-overlaps | 34 |
| handle-trim-inwards-is-a-deletion | 52 |
| insert-inside-a-transition | 47 |
| words-of-unlinked-sound | 148 |
| transcription-hears-sound-items | 16 |
| caption-look-travels-with-any-words | 2 |
| copy-keeps-time-that-sound-fills | 2 |
| footage-put-after-the-picture | 4 |
| paste-opens-the-length-of-the-fragment | 26 |
| playhead-in-time-a-transition-holds | 2 |
| removed-transition-leaves-its-time | 47 |
| bookmark-also-marks-the-unlinked-sound | 22 |
| third-sound | 55 |
| split-refused-near-an-edge | 240 |
| split-on-the-grid | 123 |
| range-end-within-a-frame-of-a-cut | 427 |
| split-cuts-sound-items | 51 |
| scope-cuts-sound-items | 50 |
| insert-moves-sound-items | 153 |
| bookmark-at-the-last-second | 111 |
| bookmark-outlives-its-footage | 6 |
| unlinked-clip-remembers-no-sound | 22 |
| fade-no-longer-than-its-item | 5 |
| fades-through-retimes-and-cuts | 24 |
| unlink-does-not-bake-the-transition | 20 |
| unlink-is-never-refused | 7 |
| noise-reduction-stays-on-its-footage | 3 |
| sound-settings-of-an-unlinked-clip | 25 |
| insert-new-identity | 345 |
| later-piece-new-identity | 153 |
| captions-fingerprint | 37 |
| duration-with-sound | 16 |

## Not compared

- `TimelineEditing.setText`, `removeText`, `placeTitle`, `setColor`, `setCrop` are compared through the view and its merge (`TrackSequenceView.editingClips`): they stay as they are and edit a clip's recipe.
- `UnusedTakes.add` is `TimelineEditing.insert` with whole takes, then a search for the first inserted clip by its id (see insert-new-identity).
- `TimelineEditing.layout`, `fitted`, `join`, `transitions`, `entering`, `leaving`, `locate`, `chains`, `overlap`, `joinMaximum`, `joinSettings`, `transition`, `titlesAt`, `titleRange`, `clipRange`, `newTitleRange`, `soundLevel`, `audioAt`, `nextBookmark`, `previousBookmark`, `bookmarkAt`; `RangeEditing.quantize`, `snap`, `markTime`, `interval`; `SubtitleEditing.active`, `fingerprint`; `AudioUnlinking.detachedSound`, `isDetached`, `clipIndex`, `refusal`, `matches`, `valid`: questions about a sequence that are asked of the sequence form, or whose answer is pinned by the render plan of the golden projects.
- What is drawn and heard is not compared here: that is the render plan (`RenderPlan`), compared when the renderer reads the timeline.
