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
| pauses-leave-a-transition-without-footage | Not adopted | Not reachable in the original's editor: there pauses are a setting of the clip and both transitions are scaled to fit it, never removed. Keep the refit until pauses become a setting of the clip. One difference to close then: the port refits to the last piece, the original to the whole clip, so the port's dissolve can come out shorter. |
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
| split-cuts-sound-items | Kept until S5 | Wanted; music in two pieces after a split is visible and must be drawn as such. |
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
