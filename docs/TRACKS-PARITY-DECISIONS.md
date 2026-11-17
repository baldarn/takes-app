# Track timeline: what to do with each kind of difference

`docs/TRACKS-PARITY.md` lists 41 kinds of difference between the editing functions the app
uses today and the same edits made through the track model. This file decides each one for
stage S2-B of `docs/TRACKS-PLAN.md`, where the present functions become wrappers over the
track model while the saved format stays as it is.

The aim of S2-B is a release that behaves exactly as the one before it. So a kind is
accepted only when no project made with the app today can show it, or when it cannot be
seen or heard. Every other kind is **kept**: the wrapper does what the app does today, and
the original's behaviour arrives in the later stage named, where it is announced.

- **Unreachable** — needs a clip whose sound is unlinked, which the editor cannot make yet.
  Accepted. To be read again in S6, when Unlink reaches the editor.
- **Invisible** — the result differs in a way nobody sees or hears. Accepted; callers that
  depend on the detail are adapted in S2-B.
- **Kept until** — the wrapper keeps today's result; the stage named adopts the original's.
- **Not adopted** — a defect, or a consequence of one. The port keeps its own behaviour and
  the difference from the original is recorded as wanted.

| Kind | Decision | Notes |
|---|---|---|
| unlinked-picture-does-not-ripple | Unreachable | The largest kind. Decide in S6 how an ordinary edit after Unlink should look to the person; black time or two pictures on top of each other must be shown as such on the timeline. |
| move-finds-no-cut-among-unlinked-pictures | Unreachable | S6. |
| trim-removes-an-opening-of-a-middle-clip | Not adopted | The original sends the clip to time zero over the earlier clips (`TrackTimeline.swift:1387-1391`): a defect. Keep the picture where it was when the relation removed is an opening. |
| range-inside-a-transition | Kept until S9 | The original's editor also moves a mark inside a transition onto its join (`YouRec/EditorView.swift:1115`); the difference is what an end on the join then does: there both pictures are cut, the transition is dropped and half the overlap stays, covered. That needs overlapping pictures to be drawn, so it waits for picture layers. See `docs/TRACKS-EDITOR-CHECK.md`. |
| pauses-leave-a-transition-without-footage | Not adopted | Not reachable in the original's editor: there pauses are a setting of the clip and both transitions are scaled to fit it, never removed. Since D6 (schema 22) it is not reachable in the port's editor either: the Pauses tool writes the setting on the clip (`TimelineEditing.setPauses`) and the transitions are scaled to the whole clip, as in the original. `TimelineEditing.removeIntervals`, which made pieces, is no longer called by the app and is kept for the parity suite and the golden projects. |
| handle-trim-outwards-overlaps | Kept until S4 | Changes what a daily gesture does: a handle dragged outwards covers the next clip instead of pushing it. Announce it. |
| handle-trim-inwards-is-a-deletion | Kept until S4 | With the one above. The original's editor offers no handle on the edge a transition between two clips is on, as the port does since 0.6, so trimming "the edge a dissolve is on" arises only for an opening or a close. |
| insert-inside-a-transition | Not adopted | In the original, Paste restores the straight cut first and says "Replaces transition", while Add video inserts at the raw playhead and leaves the two clips overlapping with their sound doubled, a route no test of the original covers. The port keeps the Paste rule for every insertion; that insert and paste agree is the port's own rule. See `docs/TRACKS-EDITOR-CHECK.md`. |
| words-of-unlinked-sound | Unreachable | S6 and S8. Also fixes the latent defect that Unlink hides a clip's captions. |
| transcription-hears-sound-items | Kept until S8 | Wanted, and already asked for by the review of the subtitles; it needs words on sound items, which the present format cannot hold. |
| caption-look-travels-with-any-words | Invisible | |
| copy-keeps-time-that-sound-fills | Kept until S5 | Audible in a paste; adopt with the sound lanes. |
| regroup-at-the-count-it-has | Invisible | A choice that did nothing now regroups; harmless and the original's. Adopt in S2-B. |
| footage-put-after-the-picture | Kept until S5 | Needs a movie longer than its picture. |
| paste-opens-the-length-of-the-fragment | Kept until S5 | Same. |
| playhead-in-time-a-transition-holds | Invisible | The playhead stays where it is; adopt in S2-B. |
| removed-transition-leaves-its-time | Kept until S4 | Black time where a still was is visible; adopt with gaps drawn on the timeline. |
| close-that-holds-pushes-nothing | Kept until S4 | One call; with the one above. |
| bookmark-also-marks-the-unlinked-sound | Unreachable | S6. |
| third-sound | Kept until S5 | The present format has two sound tracks. |
| sound-tracks-are-laid-anew | Kept until S5 | |
| split-refused-near-an-edge | Kept until S4 | Pieces a few frames long are legal in the original; the preview and the export handle one-frame pieces since 0.7, but the timeline cannot show or grab them well yet. |
| split-on-the-grid | Kept until S3b | Moving a cut by up to half a frame changes existing habits and saved numbers; adopt when the format changes, not before. |
| range-end-within-a-frame-of-a-cut | Kept until S3b | With the grid. |
| split-cuts-sound-items | Kept until S5 | Wanted; music in two pieces after a split is visible and must be drawn as such. Adopted: see "At S5". |
| scope-cuts-sound-items | Kept until S5 | |
| insert-moves-sound-items | Kept until S5 | The largest audible change for existing projects: music follows the picture instead of the clock. Announce it. |
| bookmark-at-the-last-second | Invisible | Adopt in S2-B. |
| bookmark-outlives-its-footage | Invisible | Adopt in S2-B. |
| unlinked-clip-remembers-no-sound | Unreachable | S6. |
| fade-no-longer-than-its-item | Invisible | Nothing is heard; the number shown is the item's length. Adopt in S2-B. |
| fades-through-retimes-and-cuts | Invisible | Already the port's behaviour since 0.8 in most cases; where a trim falls inside a fade, the level at the new edge follows the original. Adopt in S2-B and say so in the notes of that release. |
| unlink-does-not-bake-the-transition | Unreachable | S6. |
| unlink-is-never-refused | Unreachable | S6. |
| sync-is-measured-on-footage-that-shows | Unreachable | S6. |
| noise-reduction-stays-on-its-footage | Kept until S7 | Needs a chain over part of a sound. |
| sound-settings-of-an-unlinked-clip | Unreachable | S6. |
| insert-new-identity | Invisible | Adapt `UnusedTakes.add` and any caller that looks for the clip it inserted. |
| later-piece-new-identity | Invisible | Adapt the selection in the editor so it follows the piece. |
| captions-fingerprint | Kept until S8 | Which edits switch regrouping off is visible in the Subtitles tool. |
| duration-with-sound | Kept until S5 | Plan decision already taken: existing projects keep their length until the new one has been shown once. |

## What this means for the gate

`TrackParityAccepted.kt` lists the kinds marked Unreachable and Invisible. With
`TAKES_PARITY_GATE=1` the suite must pass with that list and the wrappers in place: every
kind marked "Kept until" and "Not adopted" has to be absent, because the wrapper reproduces
today's result. A kind that appears and is in neither group stops S2-B.

The three kinds that needed a check against the original's editor, not only its model, have
had it: `docs/TRACKS-EDITOR-CHECK.md`. Two pictures overlapping with no transition between
them can be reached in the original for an insertion and for a range end on a join, and
cannot for pauses. The rows above say what follows for each.

## After S2-B

The wrappers adopted none of the nine kinds marked Invisible. Four do not arise in what was
wrapped (`caption-look-travels-with-any-words`, `playhead-in-time-a-transition-holds`,
`bookmark-outlives-its-footage`, `later-piece-new-identity` outside `trimAudio`). Five turned
out not to be invisible while a project still saves a sequence, and the wrappers keep
today's result for them:

| Kind | Why it is kept | Until |
|---|---|---|
| insert-new-identity | A project inserts the takes of its capture ledger again on every read, so inserted clips must keep the ids they are given; new ids also move the golden bytes. | S3b |
| later-piece-new-identity (on `trimAudio`) | Moves the golden bytes of the sound projects. | S3b |
| fade-no-longer-than-its-item | Audible: a ten-second fade on a shorter clip plays with the slope of ten seconds, and a cap changes it. | S4 |
| fades-through-retimes-and-cuts (on trim) | Audible: the level at the new edge differs. | S4 |
| regroup-at-the-count-it-has | Would add an Undo step that shows nothing. | S8 |
| bookmark-at-the-last-second | At the end of the movie the track model puts the mark on the timeline itself, which a sequence cannot save. | S3b |

One more thing S3b has to decide: the track model's fit of a transition differs from
`TransitionLayout.fit` in the last digits (1e-8 to 1e-6). The wrappers save today's number
when the two are within 1e-4.

The remaining differences in `docs/TRACKS-PARITY.md` (197 of 91,755 calls) are all on edits
that contain an unlinked clip and show four kinds marked Unreachable.

## At S3b

S3b saves every committed edit as the original's track timeline (schema 17). The editing
functions still work on the sequence the timeline reads as, so each kind deferred to S3b
was read again against the original, with what the new format itself does or does not
change about it.

| Kind | Decision | Why |
|---|---|---|
| split-on-the-grid | Not adopted | The original puts a cut on its 1/30 s clock because its projects are cut on that clock (`TimelineEditing.swift:42-47`). Android's cuts are on no grid (N8), its movies are made at 24 to 60 frames a second, and a picture's place in the saved timeline is already a tick: the cut is kept at the second asked for, as the clip's source bounds, and nothing in the format needs it on a frame. Adopting it would move every new cut by up to half a frame with no gain. |
| range-end-within-a-frame-of-a-cut | Kept until S4 | Same family as `split-refused-near-an-edge`: the original's editor does snap a range end to a cut (`EditorView.swift:1125-1130`), and slivers a frame long cannot yet be shown or grabbed on this timeline. The format does not change that. |
| insert-new-identity | Kept until S12 | The original copies every clip it inserts under a new id (`TimelineEditing.swift:92-101`), and finds a take it inserted by its source. Here a take recorded at a place keeps the id of its entry in the capture ledger, and `UnusedTakes.add`, `Project.addRecording` and the editor's selection find the clip they inserted by that id. The saved timeline holds either equally, so nothing forces it now; it moves with the paste targets of S12, where insertion is reworked, together with those callers. |
| later-piece-new-identity (on `trimAudio`) | Kept until S5 | Invisible, and the editor's selection of a sound item follows its id. It moves with the sound lanes, where sound items are selected as the original selects them. |
| bookmark-at-the-last-second | Kept until S12 | The saved timeline could hold a mark on the timeline itself, but the editing functions still see the sequence it reads as ([TrackSequenceForm]), which has no place for one, and a step that does not read as a sequence is not committed as a timeline. Bookmarks become the timeline's own at S12. |
| The fit's last digits | Today's number | A relation's `factor` is the one `TransitionLayout` finds (or the one saved, when the two are within 1e-4), not the track model's, which differs from it by 1e-8 to 1e-6. The render plan and every pinned golden plan are worked out from that number; the original's own conversion of an old project likewise takes the layout's factor (`TrackTimeline.swift:510-534`). S4, where the renderer takes transitions by identity and in ticks, is where the model's own fit can take over. |

What the format does change, and is accepted with it (plan, 3.3 and D4):

- **Sound items are laid on the two tracks by first fit**, in the order they begin: the
  timeline stores no track. A project whose items were put the other way round shows them
  on swapped rows after its first edit; the mix is the same. Of the golden projects, one
  step (`sound-tracks`, step 4) shows it.
- **A sound item begins on a tick** (at most 1/480,000 s away), or exactly on the edge of
  a clip or the end of the item before when that is the same tick.
- **Settings that do not play are dropped and every transition in play has its fit
  saved**: a relation always has a factor. Nothing is seen or heard of either.
- **Two edits are the same edit when they are the same timeline**: committing an edit
  that differs only in one of the above makes no Undo step.

## At S4r

S4r is the part of S4 that picture layers need (`docs/PIP-AND-EFFECTS-PLAN.md`, 1.2): the
plan takes a transition by the pictures it relates, and the wrappers lose N1 and the close
half of N13 where the model's own placement gives the original's result.

There was nothing to drop. An opening and a close have stayed with their clip since before
the switch to the track model: moved, or with a clip put before the first or after the last,
they play on with their picture, as the model keeps them (`TrackTimeline.swift:881-885`), and
a take recorded later lands after a close that goes on playing with its clip. No kind of
`docs/TRACKS-PARITY.md` stands for either, so the accepted list is unchanged, and so is the
report. The one kind written against N1, `trim-removes-an-opening-of-a-middle-clip`, stays
Not adopted: it is the original's defect, not the rule.

The kinds kept until S4 stay kept until the visible S4: `handle-trim-outwards-overlaps`,
`handle-trim-inwards-is-a-deletion`, `split-refused-near-an-edge`,
`range-end-within-a-frame-of-a-cut`, `removed-transition-leaves-its-time` and
`close-that-holds-pushes-nothing` (the last two need gaps drawn), and the fit's last digits.

## At S4

The visible part of S4 adopts the original's result for the kinds left to it. Each was
read again against the original (`TrackTimeline.swift`, `TimelineEditing.swift`,
`Transitions.swift`, `EditorView.swift`), the wrapper rule that kept today's result was
dropped, and the kind moved to the adopted list of `TrackParityAccepted.kt`. An edit whose
pictures the result leaves over one another or apart is now that timeline
(`EditState.layered`), which the editor draws on rows and edits since the picture-in-picture
work: `TrackWrapping.edit` with `layers` gives the timeline itself when reading it as a
sequence would move a picture.

| Kind | Decision | Why |
|---|---|---|
| handle-trim-outwards-overlaps | Adopted | `TimelineEditing.trim` is the original's handle trim (`EditorView.swift:1241-1258`, `TrackEditing.trimming`): outwards a reveal that moves nothing else, so the clip grows over its neighbour and in front of it, and the edit becomes one of rows. As in the original, the head of a clip can only reveal into the time before it: none for a clip at the start of the timeline (`TrackTimeline.swift:1636`). |
| handle-trim-inwards-is-a-deletion | Adopted | Inwards the trimmed part of the picture and its sound is cut off and deleted, so the time closes as a deletion of linked picture and sound closes it, and the transition on the trimmed side goes with it. What is left after a head trim keeps the clip's id (`later-piece-new-identity` stays Invisible and not taken up): the editor, the selection and the capture ledger know the clip by it. |
| fades-through-retimes-and-cuts (on trim) | Adopted | The wrapper no longer puts back the fades `trimmed` computes: the track model's cut and reveal give the fade (`TrackTimeline.swift:133-151`, `:1650-1655`). |
| fade-no-longer-than-its-item | Adopted | `setAudio`, `setSound` and `setAudioItemSound` set a changed fade as the original does, no longer than the item and from silence (`TrackTimeline.swift:191-195`). A ten-second fade on a shorter clip is saved as the clip's length; what is heard is the same slope over the clip. |
| range-inside-a-transition | Adopted for Delete and a tool on a range | A range with an end inside a transition, its join included, is cut exactly there on the timeline, as the original's editor cuts a range for Delete and for a tool (`EditorView.swift:1125-1130`, `TrackTimeline.swift:1080-1094`): both pictures playing there are cut, the transition goes when one of them loses its edge, and what is left of the overlap stays, covered. The editor still puts a mark made inside a transition on its join (`EditorView.swift:1115`). Copy and Cut keep today's rule: a clipboard of the sequence form holds a row of clips, and the original's own clipboard cut aligns to the cuts (`TimelineEditing.swift:66-80`); they move with the paste targets of S12. |
| range-end-within-a-frame-of-a-cut | Adopted for Delete and a tool on a range | The same path: an end closer than a frame to an edge is cut there, a sliver left. The editor's mark is put on a cut when one is near at the zoom shown, as the original's (`EditorView.swift:1111-1113`), so a sliver is made only when asked for. Copy and Cut keep the clipboard's rule, as above. |
| split-refused-near-an-edge | Adopted | A split, a trim of a sound item and an insertion leave a piece of any length; the track model refuses one shorter than a tick (`TrackTimeline.swift:1011-1014`). One-frame pieces play since 0.7 and are drawn and grabbed on the timeline. Removing pauses made no pieces once pauses became a setting of the clip (N3, D6, schema 22); `TimelineEditing.removeIntervals`, which the app no longer calls, still drops such a sliver. |
| removed-transition-leaves-its-time | Adopted | Delete, Move and setting or removing a transition keep the places the track model gives the pictures: the time a still or a hold held stays black, a gap the timeline now draws, and Close gap removes it. An insertion still makes room by the sequence rule (`insert-inside-a-transition`, not adopted). |
| close-that-holds-pushes-nothing | Adopted | With the one above: a close that holds, set on a clip that is not the last, lies over the start of the next clip. |
| The fit's last digits | Not adopted | The track model fits by halving with the original's slack of a hundred-millionth of a second (`Transitions.swift:359-366`), which the original's ticks absorb. A sequence is laid out in seconds: the model's factor would start the clip after a transition that takes all of a short clip a hundred-millionth before zero (`TransitionFitTest.settingTheSameSettingsAgainFitsThemAgain`), and it would move every golden plan in its last digits. A sequence keeps the layout's fit; an edit of rows keeps the model's. |

Two limits on what is adopted, both deliberate:

- **Unlinked pictures.** Where a clip's sound is unlinked in the sequence form (a sound item
  with the clip's id), an edit of the pictures still closes up as before:
  `unlinked-picture-does-not-ripple` stays as decided above, and the 0.8 workflows built on
  it (J and L cuts by trimming an unlinked picture) keep working.
- **Golden projects.** They are built with the editing functions, so the one golden step that
  trims (`cuts`, step 4 and the one after it) saves other bytes: an inward trim is now a cut
  and a deletion. The edit it ends in, its layout and its plan are unchanged; no other golden
  project moved.

## At S12

S12 gives the editor the original's own paste targets, add buttons in gaps and bookmarks
anywhere as edits of a project (`TrackEditorEdits`, on the timeline through the track
model). Two kinds kept until S12 are decided with it. Both stay in the accepted list as
Invisible: the sequence wrappers that the parity suite compares still keep today's result,
and the new calls are the original's behaviour by construction.

| Kind | Decision | Why |
|---|---|---|
| bookmark-at-the-last-second | Adopted | "Add bookmark" is `TrackEditorEdits.addingBookmark`, the original's `addBookmark` (`TrackTimeline.swift:1162-1174`): at the playhead it marks the picture in front, a sound when one is chosen or no picture plays, and the timeline itself in empty time and at the very end. Such a mark makes an edit that is no sequence, which a project now saves. `TimelineEditing.toggleBookmark`, the wrapper of the clip's bookmark button, still marks the footage it was made on; it ends when the editor calls the new function instead. |
| insert-new-identity | Adopted for footage, not for takes | Footage added from the library or with an add button is `TrackEditorEdits.insertingFootage`, the original's `inserting` (`TimelineEditing.swift:86-127`): every clip is a copy with an id of its own, and the call answers the inserted items as its selection, so the editor never looks for a clip by the id it gave. A take of the capture ledger keeps its entry's id (`TimelineEditing.insert`, `Project.materialized`, `UnusedTakes.add`): the ledger's takes are put in again every time a project is read and must be the same clips each time. That is this port's own rule and is not adopted. |

`bookmark-outlives-its-footage` is not decided here: a mark whose footage is cut away is
kept by the track model (`TrackBookmarks.hidden`) and dropped by `RangeEditing` and
`removeIntervals`, which S12 does not change.

## At S5

| Kind | Decision | Why |
|---|---|---|
| split-cuts-sound-items | Adopted | `TimelineEditing.split` cuts everything under the playhead, the sound items too, as the original's split does with nothing selected (`TimelineEditing.swift:45-62`, `TrackEditing.splitAtPlayhead`). Each piece of a sound stays on its track, cut at the second given as the clip is (until `split-on-the-grid`); the first keeps the item's id, and a sound unlinked from the clip gives each piece of the picture its own piece, by its id. The two pieces play on without a break: the mix plan carries the sound of one into the other, and a piece that starts at the same moment leaves that lane to it (`TrackMix.plan`), which the mix gate checks ("Splitting continuous independent audio introduces no fade or volume dip", `Tests/TrackMediaTests.swift:362`). An insertion still cuts the picture alone: `insert-moves-sound-items` stays kept until S5's insertion. The timeline draws each piece as its own sound. |
