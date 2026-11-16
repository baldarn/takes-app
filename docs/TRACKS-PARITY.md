# Parity of the editing functions on the track model with what they did before

Written by `TrackParityTest` (`core/src/test/kotlin/app/takes/core/TrackParity*.kt`); do not edit by hand.

## Running it

The suite is one test of the core tests: `./gradlew :core:test`, or by itself with `--tests app.takes.core.TrackParityTest`. What it does is chosen in the environment:

- **Nothing set.** It makes the calls of a sample of the edits, about a seventh of them and the same every run, and compares what it finds with the section [The sample](#the-sample) of this file. It fails when that section is not what it finds now, or when a function answers an edit whose timeline the invariants refuse. It does not fail on a difference that is written down here.
- **`TAKES_PARITY_FULL=1`.** It makes every call and compares the whole of this file, examples included, in the same way. Run it before and after a change to the editing functions or to the track model.
- **`TAKES_RECORD_PARITY=1`.** It makes every call and writes this file anew instead of comparing. Read what changed (`git diff docs/TRACKS-PARITY.md`) and commit the file with the change that caused it.
- **`TAKES_PARITY_GATE=1`.** As `TAKES_PARITY_FULL=1`, and it also fails on every call that shows a kind of difference not listed in `TrackParityAccepted.kt`, or a difference no kind explains: the failure names each such kind with the number of calls that show it, then what is not explained. The list holds the kinds that `docs/TRACKS-PARITY-DECISIONS.md` decides as Unreachable or Invisible; every other kind is one a function keeps today's result for, and must not appear.

## What is compared

Since stage S2-B of `docs/TRACKS-PLAN.md` the functions of `TimelineEditing` and `SubtitleEditing` that change an edit run on the track model: the edit is converted to a timeline, an operation of the track model is run, and what it changed is written back into the sequence (`TrackWrapping`). `RangeEditing` cuts, removes and joins clips through them. What each did before is kept in the tests, unchanged, as it was in release 0.8 (`LegacyTimelineEditing`, `LegacyRangeEditing`, `LegacySubtitleEditing`, `LegacyAudioUnlinking`).
Every such function, and the questions among them, is called twice on the same edit with the same arguments: as it was ("today" below, the name the kinds were written with), and as it is now ("through the track model").
Both answers are read as the conversion reads them (fits saved, settings that do not play dropped, sound tracks repacked), so only what plays differently counts.
Ids made during the call, the track of a sound item, the order of a clip's bookmarks, and times less than three ticks (1/80000 s) apart are not differences. Where a sound is muted on both sides, its level, fades and noise reduction are compared as one thing: what it would sound like unmuted. The section [Saved bytes](#saved-bytes) counts the calls without any of these allowances.

- **identical**: the same edit and the same answer, or neither applies.
- **different**: both give a sequence, and the two differ.
- **not expressible**: the result is a valid timeline that no sequence of clips with two sound tracks plays. No function answers one while a project saves a sequence.

The edits: 18 small ones made for one situation each, 128 steps of the histories of the golden projects (all but the long one), and 300 made by 30 seeded sequences of the functions as they were. 91309 calls.

## By function

| Function | Calls | Identical | Different | Not expressible | Kinds |
|---|---:|---:|---:|---:|---|
| `TimelineEditing.split` | 1931 | 1899 | 32 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound) |
| `TimelineEditing.delete` | 621 | 620 | 1 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.move` | 1396 | 1377 | 19 | 0 | [move-finds-no-cut-among-unlinked-pictures](#move-finds-no-cut-among-unlinked-pictures), [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.insert` | 2822 | 2793 | 29 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound), [unlinked-clip-remembers-no-sound](#unlinked-clip-remembers-no-sound) |
| `TimelineEditing.trim` | 3080 | 3062 | 18 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.removeIntervals` | 2464 | 2464 | 0 | 0 |  |
| `TimelineEditing.setSpeed` | 2464 | 2444 | 20 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.toggleBookmark` | 1250 | 1250 | 0 | 0 |  |
| `TimelineEditing.setJoin` | 15917 | 15879 | 38 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.setTransition` | 5253 | 5233 | 20 | 0 | [unlinked-picture-does-not-ripple](#unlinked-picture-does-not-ripple) |
| `TimelineEditing.setText` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.setColor` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.setCrop` | 616 | 616 | 0 | 0 |  |
| `TimelineEditing.placeTitle` | 610 | 610 | 0 | 0 |  |
| `TimelineEditing.setAudio` | 3708 | 3708 | 0 | 0 |  |
| `AudioUnlinking.setSound` | 3708 | 3708 | 0 | 0 |  |
| `TimelineEditing.addAudio` | 1062 | 1062 | 0 | 0 |  |
| `RangeEditing.delete` | 4519 | 4519 | 0 | 0 |  |
| `RangeEditing.copy` | 4519 | 4519 | 0 | 0 |  |
| `RangeEditing.cut` | 4519 | 4519 | 0 | 0 |  |
| `RangeEditing.paste` | 13557 | 13557 | 0 | 0 |  |
| `RangeEditing.scoped` | 4519 | 4519 | 0 | 0 |  |
| `SubtitleEditing.install` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.cues` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.transcript` | 446 | 446 | 0 | 0 |  |
| `SubtitleEditing.groupingAvailable` | 446 | 446 | 0 | 0 |  |
| `AudioUnlinking.unlink` | 612 | 612 | 0 | 0 |  |
| `AudioUnlinking.relink` | 612 | 612 | 0 | 0 |  |
| `AudioUnlinking.restoreSync` | 612 | 612 | 0 | 0 |  |
| `AudioUnlinking.syncOffset` | 612 | 612 | 0 | 0 |  |
| `TimelineEditing.duration` | 446 | 446 | 0 | 0 |  |
| `TimelineEditing.clipStart` | 446 | 446 | 0 | 0 |  |
| `RangeEditing.joinTimes` | 446 | 446 | 0 | 0 |  |
| `TimelineEditing.bookmarkTimes` | 446 | 446 | 0 | 0 |  |
| `RangeEditing.transitionAt` | 1931 | 1931 | 0 | 0 |  |
| `TimelineEditing.moveAudio` | 897 | 897 | 0 | 0 |  |
| `TimelineEditing.trimAudio` | 897 | 893 | 4 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `TimelineEditing.setAudioItemSound` | 897 | 897 | 0 | 0 |  |
| `TimelineEditing.removeAudio` | 299 | 283 | 16 | 0 | [words-of-unlinked-sound](#words-of-unlinked-sound) |
| `SubtitleEditing.remove` | 81 | 81 | 0 | 0 |  |
| `SubtitleEditing.restyle` | 81 | 81 | 0 | 0 |  |
| `SubtitleEditing.regroup` | 243 | 243 | 0 | 0 |  |
| `SubtitleEditing.correct` | 93 | 93 | 0 | 0 |  |
| `SubtitleEditing.delete` | 93 | 93 | 0 | 0 |  |
| `TimelineEditing.removeText` | 14 | 14 | 0 | 0 |  |
| **all** | 91309 | 91112 | 197 | 0 | |

## The kinds of difference

Only the kinds that are found, in the order a user would notice them; the kinds that are kept as today are absent, and described in `TrackParityKinds.kt` and `docs/TRACKS-PARITY-DECISIONS.md`. A call can show several kinds; it is counted under each. "Plan" is the row of `docs/TRACKS-PLAN.md`, 2.4. The rule is where the original does what the track model does, as a path in its repository.

### unlinked-picture-does-not-ripple

**A clip whose sound is unlinked stays where it is, and nothing closes up behind it.**

- Today: A clip is always part of the sequence: when it or an earlier clip is deleted, trimmed, retimed, moved, has pauses taken out or gets a transition, every later clip moves to close the picture, whether or not its sound was unlinked.
- Track model: Only items in a link of picture and sound ripple. A picture whose sound was unlinked is linked to nothing: deleting it or cutting a range out of it leaves a gap, and when an earlier clip changes length it stays behind, so the timeline gets a gap before it or an overlap with it.
- Visible effect: After Unlink, an ordinary delete, trim, speed change or transition leaves black time or two pictures on top of each other, where today the clips stay one after another. The result cannot be saved as a sequence.
- Rule: YouRec/TrackTimeline.swift:774-799 (only complete links close their time, only links of picture and sound move); YouRec/Transitions.swift:400-410; YouRec/TrackTimeline.swift:1361 (plan: N9)
- Seen in: `TimelineEditing.delete` 1, `TimelineEditing.move` 8, `TimelineEditing.trim` 18, `TimelineEditing.setSpeed` 20, `TimelineEditing.setJoin` 38, `TimelineEditing.setTransition` 20 (0 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.delete(clip 1)` on `golden unlinked-sound, step 5`:

```
before
    c1 @0 S1 1.5–6
    c2 @4.5 S2 0–6 (into next: dissolve 1) [muted, -3 dB, fades 0/0.5]
    c3 @9.5 S3 0–6 [noise reduction 0.8]
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (15.5 s of picture)
today
    c2 @0 S2 0–6 (into next: dissolve 1) [muted]
    c3 @5 S3 0–6 [noise reduction 0.8]
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (11 s of picture)
through the track model
    c2 @0 S2 0–6 [muted]
    c3 @6 S3 0–6 [noise reduction 0.8]
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (12 s of picture)
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

### words-of-unlinked-sound

**The words of a clip whose sound is unlinked are on the sound, and show.**

- Today: Words belong to clips. A clip whose sound is unlinked is muted, and a muted clip shows no captions, gets no words from a transcription, and is not listened to; its unlinked sound, an item on a sound track, has no words.
- Track model: Words belong to sounds. The unlinked sound of a clip is an ordinary sound that is not muted: it keeps the words, shows them where it now plays, is transcribed, and loses them when it is deleted. Cut in pieces, each piece keeps them. A copy of the picture alone has no words, and so does not carry the look of the captions.
- Visible effect: After Unlink the captions of that clip stay on screen and follow the sound when it is moved, where today they disappear. Deleting the unlinked sound deletes its captions.
- Rule: YouRec/TrackTimeline.swift:184; YouRec/Subtitles.swift:155-180, :205-246 (plan: N10)
- Seen in: `TimelineEditing.split` 20, `TimelineEditing.insert` 22, `TimelineEditing.removeAudio` 16, `TimelineEditing.trimAudio` 4 (0 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.split(at 15.547)` on `captions, then the sound of a clip unlinked`:

```
before
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–8 [muted] 5 words
    c3 @20 S3 0–6 3 words
    sound of c2 @12 S2 0–8
    (26 s of picture, with the look of its captions)
today
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–3.547 [muted] 5 words
    c4 @15.547 S2 3.547–8 [muted] 5 words
    c3 @20 S3 0–6 3 words
    sound of c2 @12 S2 0–8
    (26 s of picture, with the look of its captions)
through the track model
    c1 @0 S1 0–12 10 words
    c2 @12 S2 0–3.547 [muted] 5 words
    c5 @15.547 S2 3.547–8 [muted]
    c3 @20 S3 0–6 3 words
    sound of c2 @12 S2 0–8
    (26 s of picture, with the look of its captions)
```

### unlinked-clip-remembers-no-sound

**A clip whose sound is unlinked remembers no sound of its own.**

- Today: Unlinking mutes the clip and leaves on it the level and the fades it had, for linking again. A piece cut from that clip, and the clip itself once its unlinked sound is deleted, is an ordinary muted clip with those settings: unmuting it plays the sound of its footage again.
- Track model: A picture whose sound is unlinked has no sound. Its pieces are pictures without sound, and deleting the unlinked sound leaves a picture without sound; in the sequence form each is a muted clip that remembers nothing.
- Visible effect: None while the clip is muted. Today Unmute on such a piece brings the recorded sound back at the level it had (a second time, beside the unlinked sound, while that is still there); through the track model there is no sound to unmute.
- Rule: YouRec/TrackTimeline.swift:575-579 (unlinking removes the link; level, mute and fades are the sound's, :174-177), :771-776 (a sound deleted by itself is gone) (plan: N9)
- Seen in: `TimelineEditing.split` 12, `TimelineEditing.insert` 7 (0 not expressible; 0 together with another kind)
- Accepted: yes

`TimelineEditing.split(at 9.465)` on `golden unlinked-sound, step 2`:

```
before
    c1 @0 S1 0–6
    c2 @6 S2 0–6 [muted, -3 dB, fades 0/0.5]
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
today
    c1 @0 S1 0–6
    c2 @6 S2 0–3.465 [muted]
    c4 @9.465 S2 3.465–6 [muted, -3 dB, fades 0/0.5]
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
through the track model
    c1 @0 S1 0–6
    c2 @6 S2 0–3.465 [muted]
    c5 @9.465 S2 3.465–6 [muted]
    c3 @12 S3 0–6
    sound of c2 @6 S2 0–6 [-3 dB, fades 0/0.5]
    (18 s of picture)
```

## Not explained

Every difference found is of one of the kinds above.

## Fades

A fade longer than half its item, which the sequence form used to play for half the item only, was set in 1597 calls: 1597 identical, 0 not, of which 0 differ in a fade. Those are fades asked longer than the whole item (fade-no-longer-than-its-item).
The sequence form now holds a fade with its length and how far into it the item begins, so a fade capped at half its item is no kind of difference here.

## Ids across the whole edit

Today the id of a bookmark and of a transition is unique within its clip; the track model needs each unique across the timeline (`TrackInvariants`, `DuplicateRelation`). Measured on every step of every golden project, the long one included, on the edits the seeded sequences made, and on every edit a function as it was answered in the suite, each converted to a timeline:

| Edits | Counted | With a bookmark id used twice | With a transition id used twice | Converted timelines the invariants refuse |
|---|---:|---:|---:|---|
| Golden projects, every step | 450 | 0 | 0 | none |
| Seeded sequences | 300 | 0 | 0 | none |
| Answers of the functions as they were, in the suite | 72286 | – | – | none |

## Timelines of the answers

Of the 72275 edits the functions answered in the suite, each converted to a timeline, the invariants (`TrackInvariants.violation`) refuse 0.

## Saved bytes

In 72279 calls both sides answer an edit. In 72092 of them the two edits are the same JSON, byte for byte, once the ids made during the call are renamed in the order they appear: nothing is read through the conversion and no time is rounded. The others, by function:

| Function | Both answer | Not the same bytes | Of which identical as compared above |
|---|---:|---:|---:|
| `TimelineEditing.split` | 1120 | 33 | 1 |
| `TimelineEditing.delete` | 621 | 1 | 0 |
| `TimelineEditing.move` | 1244 | 8 | 0 |
| `TimelineEditing.insert` | 2822 | 29 | 0 |
| `TimelineEditing.trim` | 2119 | 18 | 0 |
| `TimelineEditing.setSpeed` | 1868 | 20 | 0 |
| `TimelineEditing.setJoin` | 10812 | 38 | 0 |
| `TimelineEditing.setTransition` | 3676 | 20 | 0 |
| `TimelineEditing.trimAudio` | 577 | 4 | 0 |
| `TimelineEditing.removeAudio` | 299 | 16 | 0 |

## The sample

What the tests check when nothing is set in the environment: the calls on 68 of the edits (the 18 small ones, every 8th step of the golden projects, every 9th seeded edit), the same calls the whole suite makes on them.
14967 calls: 14939 identical, 28 different, 0 not expressible, 0 with something not explained.

| Kind | Calls in the sample |
|---|---:|
| unlinked-picture-does-not-ripple | 14 |
| words-of-unlinked-sound | 11 |
| unlinked-clip-remembers-no-sound | 3 |

## Not compared

- `TimelineEditing.removeIntervals`, the functions of `RangeEditing` and those of `AudioUnlinking` keep the bodies they had (`RangeEditing` over the functions that changed); they are compared all the same.
- `UnusedTakes.add` is `TimelineEditing.insert` with whole takes, then a search for the first inserted clip by its id; `insert` keeps the ids it is given.
- A trim by a handle as the original makes it (a deletion inwards, a reveal outwards) is no function of the app until S4, and is no longer compared.
- `TimelineEditing.layout`, `fitted`, `join`, `transitions`, `entering`, `leaving`, `locate`, `chains`, `overlap`, `joinMaximum`, `joinSettings`, `transition`, `titlesAt`, `titleRange`, `clipRange`, `newTitleRange`, `soundLevel`, `audioAt`, `nextBookmark`, `previousBookmark`, `bookmarkAt`, `trimmed`; `RangeEditing.quantize`, `snap`, `markTime`, `interval`; `SubtitleEditing.active`, `fingerprint`; `AudioUnlinking.detachedSound`, `isDetached`, `clipIndex`, `refusal`, `matches`, `valid`: questions about a sequence that are asked of the sequence form, or whose answer is pinned by the render plan of the golden projects.
- What is drawn and heard is not compared here: that is the render plan (`RenderPlan`), compared when the renderer reads the timeline.
