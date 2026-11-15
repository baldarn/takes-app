# Track timeline: three kinds checked against the original's editor

`docs/TRACKS-PARITY-DECISIONS.md` ends by asking for a check of three kinds of difference
against the original's editor, not only its model: `insert-inside-a-transition`,
`range-inside-a-transition` and `pauses-leave-a-transition-without-footage`. In each the
track model allows two pictures to overlap in time with no transition between them. This
file says, for each, whether a person using the original can reach that state, by which
gestures, what the editor does before it calls the model, and what is then shown.

**How this was done.** Everything here was read in the source of the original
(`YouRec/`, `Tests/`, `SPEC.md`, `docs/EDITOR-TRACKS-DESIGN.md`). Nothing was built or run,
on either side. Where a result is given in seconds it was worked out by hand from the code
and is marked "read, not run". Paths are relative to the original's repository unless they
begin with `docs/TRACKS-`, which are this repository's.

The worked examples use the timeline the parity file calls "a dissolve": c1 at 0 showing
0–12 of its source, c2 at 11, c3 at 19, a dissolve of 1 s from c1 into c2. In the original's
terms c1 occupies 0–12 and c2 11–19; the transition's range is 11–12 and its **join** is at
11.5.

## What is common to the three cases

**The join is the middle of the overlap, not an edge of a clip.** For a transition between
two pictures the join's time is the transition's origin plus half its picture time
(`YouRec/Transitions.swift:291`, `:155`; for a still it is the end of the hold). The range
of a join is the union of its picture and sound windows (`YouRec/Transitions.swift:293`).
`SPEC.md:958` says it in words: "Timeline joins represent overlap midpoints." So for every
style that really overlaps two pictures (dissolve, wipe, split screen, Hitchcock, whip pan,
flash cut, defocus) a time "on the join" is strictly inside both pictures. Fade through
colour between two clips and the still have no overlap: there the join is where one picture
ends and the next begins (`YouRec/Transitions.swift:125-126`, `:145-150`; worked out by
hand, read, not run).

**The playhead can rest anywhere inside a transition.** Scrubbing seeks to the raw time
(`YouRec/EditorTimeline.swift:513`); when the finger lifts, the magnet, if on, pulls to an
edge of an item, a bookmark or zero within 8 points (`YouRec/EditorTimeline.swift:519`,
`:558`, `:711`; `YouRec/TrackTimeline.swift:1470-1490`). Those edges include the two ends of
the overlap (where c2 starts and where c1 ends) but not the join. Playback can be paused at
any time. A tap on a transition's badge, and Previous/Next change, put the playhead exactly
on the join (`YouRec/EditorTimeline.swift:561-562`; `YouRec/EditorView.swift:596`;
`Tests/TrackTimelineTests.swift:800`). Nothing moves the playhead out of a transition.

**What is checked at the playhead inside a transition, and what is not.**

| Control | Inside a transition | Where |
|---|---|---|
| Split | Refused, with "Remove the transition before splitting inside its overlap" | `YouRec/EditorView.swift:1095-1097`; `YouRec/TimelineEditing.swift:48`; `Tests/TransitionTests.swift:20` |
| Mark start / Mark end / Move end | The mark is moved to the join, with "Selection aligned to the transition join" | `YouRec/EditorView.swift:1115` |
| Paste of footage with picture | Allowed; the button reads "Replaces transition" | `YouRec/EditorView.swift:775-797` |
| Tools → Add video or photo | Allowed, no check, no message | `YouRec/EditorView.swift:325`; `YouRec/EditorTools.swift:125` |
| Trim handle | Not offered on the edge a transition between two clips is on; offered on the other edge, with no limit before the last frame | `YouRec/TrackTimeline.swift:1689-1690`, `:1696-1706`; `YouRec/EditorTimeline.swift:597-598`, `:932`; `YouRec/EditorView.swift:1250` |

**An overlap with no transition is a legal, ordinary state in the original, and it is
drawn.** The design decides it: "one video covers the other", "An overlap does not
automatically create a transition", "All overlapping audio is mixed"
(`docs/EDITOR-TRACKS-DESIGN.md:23-30`), "At overlapping time ranges, the timeline shows the
videos in separate vertical lanes" and "a Placement bubble appears"
(`docs/EDITOR-TRACKS-DESIGN.md:145-151`), "The default composite puts the foreground video
over the background" (`docs/EDITOR-TRACKS-DESIGN.md:168`). The code does that:

- Timeline: only a transition's two pictures are folded into one row at the join
  (`YouRec/TrackTimeline.swift:1692-1718`); any other two pictures that overlap get separate
  rows, the front one in the upper row (`YouRec/TrackTimeline.swift:1744-1761`;
  `Tests/TrackTimelineTests.swift:546`, `:555`). A test states the very case: after a
  transition is taken away, "Removing the transition reveals the actual overlap instead of
  concealing a layer" (`Tests/TrackTimelineTests.swift:725-727`).
- Preview and export: the pictures of an interval are drawn back to front, each over the
  one before (`YouRec/TrackRenderer.swift:152-160`; `YouRec/VideoToolCompositor.swift:303-311`).
  The later clip of a plain sequence has the higher layer, so it is the one in front (the
  parity file's own listings show c1 on layer 0 and c2 on layer 1).
- Status bar: while the playhead is in the overlap a chip "Placement · default" is offered
  (`YouRec/VideoTools.swift:612-621`; `YouRec/EditorView.swift:627-629`, `:673-675`).

So the original has no notion of "an overlap that should not be there". What the three
cases have in common is that the overlap is a by-product: a short piece of one clip is left
under or over its neighbour, and nothing tells the person that the transition went. The
design asks for a message, "Transition removed · Undo"
(`docs/EDITOR-TRACKS-DESIGN.md:201-204`); no such text exists in `YouRec/` (searched). The
messages actually shown are "Deleted · Undo available", "Trimmed · Undo available" and
"Clip inserted · Undo available" (`YouRec/EditorView.swift:1134`, `:1257`, `:890`).

## 1. insert-inside-a-transition

### Routes

| Route | Time passed to the model | Model call | Leaves an overlap? |
|---|---|---|---|
| Tools → Add video or photo → From Photos | the playhead, as it is (`YouRec/EditorView.swift:325`) | `TimelineEditing.inserting` with no site (`YouRec/ProjectStore.swift:401-431`; `YouRec/VideoBatchImport.swift:100-105`) | **Yes** |
| Tools → Add video or photo → Unused takes | the same | `Project.insertVideos` → `inserting` (`YouRec/AddVideoPanel.swift:85`) | **Yes** |
| Tools → Add video or photo → Record here | the same (`YouRec/EditorView.swift:315`, `:885-887`; `YouRec/ContentView.swift:45-49`) | `beginRecordingInsertion`, then `inserting` for each take (`YouRec/Project.swift:275-279`, `:342-365`) | **Yes** |
| The "+" markers on the timeline (start, a gap, end) | a place computed from the picture coverage, never the playhead (`YouRec/EditorView.swift:597-601`; `YouRec/TimelineEditing.swift:16-32`) | `inserting` with a site | No: the coverage counts a transition's time as covered (`YouRec/TimelineEditing.swift:36-38`), so a site is never inside one |
| Paste (status bar, or "Paste here" in the panel) | the playhead, as it is (`YouRec/EditorView.swift:861-868`) | `paste` → `preparePaste` → `insertPictureTime` (`YouRec/TimelinePaste.swift:136-194`) | No: see below |
| A take recorded later, not "here" | the end of the timeline (`YouRec/Project.swift:255-262`) | — | No |

### What the editor does first

For the three Add-video routes: nothing. The Tools button pauses and re-seeks to the same
playhead (`YouRec/EditorView.swift:914-916`); the panel is titled "Insert at" that time and
says "The first starts at the playhead" (`YouRec/AddVideoPanel.swift:26`, `:109`). No
snapping, no alignment to the join, no refusal, nothing disabled.

For Paste: the editor shows "Replaces transition" on the button when the clipboard has
picture that opens time and the playhead is strictly inside a transition's range or on a
join that has one (`YouRec/EditorView.swift:776-783`, `:796`). The model then restores the
straight cut of every transition the place lies strictly inside, and of the target join's
(`YouRec/TimelinePaste.swift:176-184`; `YouRec/Transitions.swift:387-396`), and only then
cuts the picture and opens time (`YouRec/TimelinePaste.swift:185-193`).

### Can a person reach the state?

**Yes, read in the code, not run.** The shortest way: tap the badge of a dissolve (the
playhead goes to its join), Tools, Add video or photo, From Photos, choose one item.
Scrubbing or pausing anywhere in the dissolve does the same.

What the model does (`YouRec/TimelineEditing.swift:115-125`;
`YouRec/TrackTimeline.swift:1145-1161`): every item crossing the place is split there; the
later pieces and everything after move later by the length inserted. The split hands the
transition to the later piece of the outgoing picture (`YouRec/TrackTimeline.swift:1059-1061`),
which is now shorter than the transition needs, so the prune removes it
(`YouRec/TrackTimeline.swift:877-878`). By hand for an insertion of 2.5 s at 11.314: c1
0–11.314, c2's first piece 11–11.314, the new clip 11.314–13.814, then the rest of c1
(0.686 s) and the rest of c2 both starting at 13.814. This is the parity file's listing.

### What they then see

Read, not run; the layers are as in the parity file's listing.

- Preview: c1 until 11; a hard cut to c2 for 0.314 s (c2 is in front and covers c1); the
  inserted footage; then c2 from where it left off. The last 0.686 s of c1 is never seen: it
  lies under c2 after the insertion. Whether a covered picture shows at the sides depends on
  the two pictures' framing, which the code does not let me state.
- Sound: both sounds play wherever both pictures do, mixed
  (`docs/EDITOR-TRACKS-DESIGN.md:29-30`): c1 under c2 for 0.314 s before the insertion and
  for 0.686 s after it.
- Timeline: the dissolve's badge is gone. On each side of the new clip there are two rows
  for the length of the overlap, with a short card in the second row. With the playhead
  there, "Placement · default" is offered.
- Message: "Clip inserted · Undo available". Nothing says that the transition was removed.

### Intended or not?

- The design describes this operation as it is coded: "Insert time is a distinct command:
  split crossing items, preserve the pieces, and shift the right-hand pieces together.
  Record here uses that explicit insertion behavior" (`docs/EDITOR-TRACKS-DESIGN.md:139-141`).
  It does not speak of a transition at the place.
- `SPEC.md:961-962`: "Moving, deleting or inserting at a join removes its transition when
  its exact neighbor identities no longer match." The transition is removed, as written;
  the footage it held is not spoken of.
- No test inserts inside a transition through `inserting` (searched `Tests/` for
  `inserting(`, `insertVideos(`, `insertTime(`: none of the calls has a transition at the
  place).
- For Paste the opposite result is deliberate, and tested twice in the model and once in
  the interface: "Pasting picture into a dissolve recovers both source handles" and "A
  mid-transition scene paste retains all source handles"
  (`Tests/TimelineClipboardChecks.swift:87-92`, `:110-113`); `testClipboardPasteAtDissolve`
  checks the words "Replaces transition" and that the badge is gone
  (`Tests/EditorUITests.swift:3691-3707`). The comment on the function says why: "If the
  insertion separates a dissolve, use its full handles" (`YouRec/TimelinePaste.swift:174-175`).

So the original answers the same situation in two ways. Paste, the newer and tested path,
gives each clip its whole length back and cuts the clip then showing. Add video and Record
here go through the older `inserting` and leave the pieces overlapping. I read the second
as an omission, not a choice, but that is an inference: nothing in the original says so.

### Certainty

Routes, the absence of any check, and the model's result: read in the code. The picture
and the sound described: read from the compositor and the design, not run. That the overlap
is unintended: inferred.

### Conclusion for the port

**Keep the port's present behaviour**, for every way of inserting footage, and treat it as
the original's Paste rule, not as a departure from the original. Concretely:

- The wrapper for `TimelineEditing.insert`, and `RangeEditing.paste`, do what
  `insertPictureTime` does: restore the straight cut of each transition the place lies
  strictly inside (and of the join's own transition when the place is a join), then cut and
  open time. This is what the port does today according to `docs/TRACKS-PARITY.md`, and its
  example result is the one the original's Paste gives.
- To match the original's editor, the port's editor shows "Replaces transition" on the
  control that will insert, under the condition of `YouRec/EditorView.swift:776-780`. The
  original shows it only on Paste; showing it on Add video and Record here too is the port
  being consistent where the original is not.
- Adopting the model's `inserting` as it is would be literal parity with Add video and
  Record here only, would make Paste and Insert disagree in the port as they do in the
  original, and needs picture layers, which the port does not have before S9.

## 2. range-inside-a-transition

### Routes

**A. Marks.** Mark start, then Mark end (or Move end), then the trash, Copy, Cut, Move or a
tool (`YouRec/EditorView.swift:912`, `:1110-1125`, `:1127-1135`, `:841-859`, `:1011-1020`).
The accessibility action "Mark cut boundary" calls the same function
(`YouRec/EditorView.swift:621`). There is no other way to make a time range: a tap on a
card selects whole items (`YouRec/EditorView.swift:607-613`), and range handles are not
dragged on the timeline (the two dashed lines are only drawn,
`YouRec/EditorTimeline.swift:985-989`).

**B. A trim handle dragged inwards from the far edge.** A trim inwards is a deletion of a
range of that one item and its linked sound (`YouRec/EditorView.swift:1247-1257`). The
range runs from the item's edge to where the finger stopped, and that point is limited only
to one frame before the item's other edge (`YouRec/EditorView.swift:1250`). So the start
handle of c1 can be dragged right into the dissolve at c1's end, and the end handle of c2
can be dragged left into the dissolve at c2's start. Nothing aligns or refuses this.

**C. A range kept across an edit.** After a commit the marked range is re-derived from the
footage it covered (`YouRec/EditorView.swift:1073-1079`; `YouRec/TrackTimeline.swift:1097-1144`)
and is not aligned again. A range marked first, with a transition added afterwards at one
of its ends, was not followed to the end: unknown.

### What the editor does first

For A, in `mark` (`YouRec/EditorView.swift:1110-1125`):

1. With the magnet on, the playhead within 8 points of a join is taken as that join
   (`:1112`; `YouRec/TimelineEditing.swift:128-133`).
2. Otherwise the time is moved to a quiet instant within 20 ms if the waveform has one, and
   put on the 1/30 s grid (`:1114`; `YouRec/EditorController.swift:415-425`).
3. **If the time lies in the range of a join that has a transition, first instant included
   and last excluded, it becomes that join's time**, and the message "Selection aligned to
   the transition join" is shown (`:1115`; `YouRec/Transitions.swift:259`). `SPEC.md:959-960`:
   "Marking within a transition aligns the mark to its join."
4. The playhead is put on the mark (`:1116`).

Both ends of a marked range pass through this. Then Delete, Cut, Copy and Move call the
model's `scoped` directly, with no further check (`YouRec/EditorView.swift:1129`, `:846`,
`:851`, `:1164`; `YouRec/TimelinePaste.swift:123-128`; `YouRec/TrackTimeline.swift:1529-1532`).
A tool opened on a marked range does the same (`YouRec/EditorView.swift:1013-1019`;
`YouRec/EditorTools.swift:430`; `YouRec/VideoTools.swift:651-656`). The refusal the port
copied as N12, "A selection inside overlap cannot silently modify excess footage"
(`YouRec/VideoTools.swift:299-305`; `Tests/TransitionTests.swift:55-56`), is on the other
branch, taken only when a tool is opened without targets, and even there an end exactly on
a join is let through (`YouRec/VideoTools.swift:300`).

For B: nothing, except that the handle is not offered on the edge the transition is on.

### Can a person reach the state?

**Yes, by A, with an end on the join; read in the code, not run.** The alignment does not
avoid the state, because the join is the middle of the overlap. `scoped` cuts every
targeted item crossing each end (`YouRec/TrackTimeline.swift:1080-1094`); with nothing
selected when the first mark was made, the targets are everything
(`YouRec/EditorView.swift:518-522`, `:1123`). At 11.5 both c1 and c2 are cut. The transition
now runs from the last 0.5 s of c1 into the first 0.5 s of c2; each is shorter than the 1 s
it needs, and the prune removes it (`YouRec/TrackTimeline.swift:877-878`).

By hand:

- Shortest way: tap the dissolve's badge, Mark start, scrub or play to somewhere in c2 (say
  14), Mark end, trash. Left: c1 0–11.5 and the first 0.5 s of c2 at 11–11.5 over it, then
  the rest of c2 at 11.5.
- The other way round, a range 10–11.5 deleted: c1 0–10, the last 0.5 s of c1 at 10–10.5,
  and the rest of c2 from 10, over it.
- Copy of 10–11.5 puts on the clipboard 1.5 s of c1 with 0.5 s of c2 over its end. This is
  exactly the example printed under this kind in `docs/TRACKS-PARITY.md`: the suite's
  example range ends on the join, the one place inside a transition where a mark can be.
- A tool on 10–11.5 (Colour, Speed, Pauses, …): the same two cuts are made when the tool's
  panel opens, the transition is gone from the draft, and nothing is moved, so the dissolve
  becomes a hard cut at 11 with the last second of c1 under c2. The tool applies to the
  piece of c1 and to the first 0.5 s of c2. Whether Cancel leaves the project untouched was
  not followed: unknown.

An end that is inside a transition and **not** on its join cannot be made with marks. The
part of the kind that the suite found at such ends is reachable only by B:

- Select c2, hold its end handle and drag left until inside the dissolve, to 11.4 say:
  c2 keeps 0.4 s at 11–11.4, the transition is pruned, c3 closes up to 12 (the closing rule,
  `YouRec/TrackTimeline.swift:785-799`). Left: c1 0–12 with c2 over it at 11–11.4, then c3.
- Select c1, hold its start handle and drag right to 11.3: c1 keeps its last 0.7 s, c2 moves
  to 0, and the piece of c1 lies under c2 at 0.3–1.

Each needs a long drag across almost the whole clip; a person trying to keep "just the end"
or "just the start" of a clip would do it.

One consequence of the alignment that helps: a mark outside a transition's range always
leaves the transition all the footage it needs on that side, because the range begins and
ends exactly where that footage does (`YouRec/Transitions.swift:293`, `:141-153`). So a
range edit with both ends outside every transition keeps the transition and never leaves an
overlap; only an end on a join, or route B, does. (Reasoned from the code, not run.)

### What they then see

Read, not run.

- Range ending on the join, deleted: the dissolve's badge is gone. The preview shows a cut.
  Half a second of one clip lies under the other: unseen, but heard, mixed with the other
  clip's sound. On the timeline a second row appears for that half second with a short
  card; "Placement · default" is offered there. The message is "Deleted · Undo available".
- End handle of c2 dragged into the dissolve: c2 appears over c1 for 0.4 s and then c1 comes
  back until its end. This one is seen in the picture, as a flash of the wrong clip.
- For fade through colour between two clips and for the still, an end on the join cuts
  nothing in two, because the join is where the pictures meet; no overlap results
  (worked out by hand from `YouRec/Transitions.swift:125-126`, `:145-155`; read, not run).

### Intended or not?

- The alignment is intended and specified (`SPEC.md:959-960`). It was written when a join
  was the border between two clips of a sequence; `Tests/LegacyTimelineOracle.swift:1-2`
  keeps that older layout "used only by migration tests". In the track model the same time
  is in the middle of two pictures.
- That the transition goes is intended (`SPEC.md:961-962`;
  `docs/EDITOR-TRACKS-DESIGN.md:201-204`).
- Nothing says what should become of the footage the transition held. No test makes a
  range edit with an end on a join that has a transition (searched `Tests/TransitionTests.swift`,
  `Tests/TrackTimelineTests.swift`, `Tests/TimelineClipboardChecks.swift`,
  `Tests/VideoToolsTests.swift`). The range tests use timelines without transitions
  (`Tests/TrackTimelineTests.swift:83-88`; `Tests/TimelineClipboardChecks.swift:61-65`).
- The editor contradicts itself here: Split at the join of a dissolve is refused because it
  is "inside its overlap" (`YouRec/EditorView.swift:1097`), while a range end at the same
  instant makes the same cut.
- The left-over overlap is, by the design, a normal state and is drawn as one.

### Certainty

The alignment, the routes, the absence of a check before `scoped`, and that both pictures
are cut at the join: read in the code. The resulting positions: worked out by hand, and
for Copy confirmed by the parity file's own example. What is seen and heard: read from the
compositor and the lanes, not run. That the left-over overlap is an oversight and not a
choice: inferred from the missing tests and the Split refusal.

### Conclusion for the port

**Adopt the model's behaviour only together with two things the editor does, and not
before the port can show an overlap.**

1. *The alignment.* Before any range reaches the model, each end that lies in the range of
   a join with a transition (the union of its picture and sound windows, first instant
   included, last excluded) is put on the join's time, and the person is told "Selection
   aligned to the transition join". `docs/TRACKS-PARITY.md` says the port does this today;
   it must stay in the port's editor, or in the wrapper, when S4 removes N12. With it, every
   end strictly inside a transition and off its join disappears from what a person can do
   with marks, and so does most of this kind.
2. *The drawing of an overlap.* What remains (an end on the join of a real-overlap
   transition) leaves two pictures at once in the original. To match, the port's editor
   needs what the original has: two rows where two pictures overlap, the front one above,
   and the Placement control. `docs/TRACKS-PARITY-DECISIONS.md` already says "with the
   overlap drawn". In the port's plan that is picture layers, S9, not S4.

Until then, **keep the port's present result for an end on a join**: the clip on each side
keeps or loses its whole handle, and no piece is left under another. It is the reading of
"the join is the border between the two clips" that the original's own specification was
written for, and it is what a person sees in the original's picture in most cases anyway (a
cut; the left-over piece is covered). What the port loses by keeping it is the half second
of doubled sound and the extra card.

For the trim handle (the port's kinds `handle-trim-inwards-is-a-deletion` and
`handle-trim-outwards-overlaps`, S4): to match the original's editor, offer no handle on an
edge that a transition between two clips is on. If the far handle may be dragged into the
transition as in the original, the result is route B; the port may instead stop the drag at
the transition's range, which the original does not do.

## 3. pauses-leave-a-transition-without-footage

### Routes

**The Pauses tool.** In the original, removing pauses does not delete anything from the
timeline. The tool writes a pause setting on each clip in scope
(`YouRec/EditorTools.swift:618-641`) and assigns the clips back; that assignment goes
through `replaceVideoContents` (`YouRec/Project.swift:159-162`). The clip stays one clip
with a shorter playback map. `SPEC.md:776-778`: "Pause removal is an editable source-domain
exclusion, not a destructive ripple deletion; Remove restores those pauses".

There, because the clip's source bounds did not change, the branch taken is the one for
speed (`YouRec/TrackTimeline.swift:1370-1385`): if the transition into the clip and the one
out of it together need more than the clip now lasts, both are scaled down by one factor
until they fit; neither is removed. The clip is then put where its incoming transition
wants it, and the next picture, its sound and everything after follow the clip's new end
(`YouRec/TrackTimeline.swift:1387-1415`). The prune that ends the function finds the
overlap it expects and keeps the transition (`YouRec/TrackTimeline.swift:862-880`; worked
out by hand, read, not run). `SPEC.md:945-946`: "The requested duration is retained when
trimming, speed or Pauses temporarily limits the effective duration." The same refit is
tested for speed: "Speed refits a full-length transition without negative time or dropping
it" (`Tests/TrackTimelineTests.swift:832-837`). No test in `Tests/PausesTests.swift` has a
transition.

**The Pauses tool on a marked range.** The range is cut out first, by `scoped`, and the
setting is written on the pieces inside it. If an end of the range is on the join of a
real-overlap transition, the transition is lost by that cut, as in case 2, before pauses
come into it. That is case 2, not this one.

**Doing by hand what the port's function does**: marking each silence and deleting it. With
both marks outside the transition's range, the piece left at the clip's end always keeps
the footage the transition needs (the last paragraph of "Can a person reach the state?" in
case 2), the transition moves with its picture (`YouRec/TrackTimeline.swift:800-805`) and
stays. With a mark inside the range, the mark goes to the join, and it is case 2 again.

### Can a person reach the state?

**No, not with pauses; read in the code, not run.** The state the parity file describes
(the transition removed by the prune, the last piece and the next clip left overlapping by
what the transition took) comes from expressing the port's `removeIntervals` as a series of
range deletions of the model. The original's editor never does that. Its only ways to a
transition "left with too little footage" are an end on a join and the far trim handle,
both under case 2.

One limit: what happens when pauses leave a clip so short that the scaled transition is
under one frame (`YouRec/Transitions.swift:122`) was not followed. Unknown, and rare
(`SPEC.md:775`: "retaining at least one frame").

### What they then see

A clip with its pauses out, one card, and its transitions still there, shorter when they
had to be (the transition's panel reports the actual timing, `SPEC.md:994-995`). No second
row, no Placement chip.

### Intended or not?

Intended, specified and consistent: `SPEC.md:776-778`, `:945-946`; the comment "Speed
changes preserve the source footage. Fit both handles together before moving the next
picture" (`YouRec/TrackTimeline.swift:1377-1378`).

### Certainty

That the Pauses tool goes through the clip setting and the refit, and never through a range
deletion: read in the code. That the prune then keeps the transition: worked out by hand,
not run, and supported by the test of the same path for speed.

### Conclusion for the port

**Keep the port's present behaviour** ("Not adopted" in `docs/TRACKS-PARITY-DECISIONS.md`
is right, and its reason is confirmed). The wrapper for `removeIntervals` must go on
refitting, and must not be expressed as range deletions of the model.

To match the original exactly the port needs pauses as a setting of the clip (plan decision
D6). Until then there is one visible difference from the original, in the other direction
from the one the suite looked for: the port fits the transition to the **last piece**, the
original to the **whole clip** as it lasts after the pauses. In the parity file's example
the clip is left with 1.2 s and 0.6 s; the port plays the dissolve at ×0.60, while the
original, where 1.8 s is more than the 1 s the dissolve needs, would play it at full length
across the place a pause was taken out (worked out by hand, read, not run). When pauses
become a setting, the refit to copy is `YouRec/TrackTimeline.swift:1370-1385`: one factor
for the incoming and the outgoing transition together, against the clip's new length.

## Where this contradicts the port's documents

1. **`docs/TRACKS-PARITY-DECISIONS.md`, last paragraph**: "a state … that the editor may
   never produce". For two of the three kinds the original's editor does produce it: by Add
   video and Record here inside a transition (case 1), and by a range end on a join or a far
   trim handle (case 2). Only for pauses does it never.

2. **`docs/TRACKS-PARITY-DECISIONS.md`, row `range-inside-a-transition`**: "Today a range
   end inside a transition snaps to its join. The original cuts both pictures there". The
   original's editor also puts the mark on the join (`YouRec/EditorView.swift:1115`). The
   two editors agree on where the end goes; they differ in what an end **on the join** then
   does. The title of the kind in `docs/TRACKS-PARITY.md`, "A range that begins or ends
   inside a transition cuts both clips there", is true of the model; in the original's
   editor the only such place a mark can be is the join, and the calls of the sample with an
   end elsewhere inside a transition are reachable only through a trim handle.

3. **`docs/TRACKS-PARITY-DECISIONS.md`, row `insert-inside-a-transition`**: "Today's result
   … follows the original's paste" is confirmed (`YouRec/TimelinePaste.swift:176-194`).
   But "`RangeEditing.paste` and `insert` must keep agreeing" is a rule of the port only: in
   the original they do not agree. Paste restores the straight cut; Add video and Record
   here leave the overlap.

4. **`docs/TRACKS-PLAN.md` 2.4 against `docs/TRACKS-PARITY-DECISIONS.md`**: N11 (insert
   inside a transition) ends at "S9 at the earliest" in the plan; the decisions file says
   "Kept until S4". Since the original's result needs two pictures at once, S9 is the
   earlier date that can hold. The same applies to the part of
   `range-inside-a-transition` that leaves an overlap (an end on a join): the decisions file
   says S4 "with the overlap drawn", and the drawing is S9.

5. **`docs/TRACKS-PARITY.md`, `handle-trim-inwards-is-a-deletion`**: "A trim of the edge a
   dissolve is on removes the dissolve even when the clip stays long enough for it". True
   of the model call. In the original's editor that edge has no handle: the card is folded
   at the join, and a handle is offered only where the card's edge is the item's own edge
   (`YouRec/TrackTimeline.swift:1689-1690`, `:1696-1706`; `YouRec/EditorTimeline.swift:597-598`,
   `:932`). The statement holds for an opening or a close, which are not folded
   (`YouRec/TrackTimeline.swift:1703`). Not one of the three kinds, but S4 adopts it.

6. **`docs/TRACKS-PARITY.md`, `pauses-leave-a-transition-without-footage`**: no
   contradiction; its "Rule" line already says that the original keeps one clip and refits.
   The addition is the difference noted under case 3: the port's refit per piece can be
   shorter than the original's refit per clip.

## In one table

| Kind | Reachable in the original's editor? | What the editor does first | What is seen | Certainty | For the port |
|---|---|---|---|---|---|
| insert-inside-a-transition | Yes, by Tools → Add video or photo (Photos, Unused takes, Record here). Not by Paste, not by the "+" markers. | Nothing for Add video. For Paste: "Replaces transition", and the straight cut is restored. | Transition gone; a short piece of each clip beside the new one, one covering the other; doubled sound; a second row. | Routes and result: read. Picture: read, not run. Unintended: inferred. | Keep the present behaviour (the original's Paste rule) for every insertion; show "Replaces transition". |
| range-inside-a-transition | Yes, with an end on the join (marks are aligned there, and the join is mid-overlap). Off the join only by a far trim handle dragged into the transition. | Aligns each mark to the join, with a message. No check for the trim. | Transition gone; a cut; half the overlap left as a covered piece with its sound; a second row. By the trim: a flash of the wrong clip. | Read; positions by hand, Copy confirmed by the parity example. Not run. | Keep the alignment. Keep the present result for an end on a join until overlaps can be drawn (S9); then adopt the model with the two rows and Placement. No handle on a transition's edge. |
| pauses-leave-a-transition-without-footage | No. | Pauses are a setting of the clip; the transitions are refitted to the clip, never removed. | One card, transitions kept, shorter if need be. | Read; the refit by hand, not run. | Keep ("Not adopted"). Until D6 the port's per-piece refit can be shorter than the original's. |
