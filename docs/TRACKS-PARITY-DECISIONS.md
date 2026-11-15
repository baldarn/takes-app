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
| range-inside-a-transition | Kept until S4 | Today a range end inside a transition snaps to its join. The original cuts both pictures there and leaves an overlap; adopt it together with the trim model, with the overlap drawn. |
| pauses-leave-a-transition-without-footage | Not adopted | An overlap with no transition is not something the original's editor produces: there a clip with pauses stays one clip and the transition is refitted. Keep the refit per piece until pauses become a setting of the clip (plan decision on pauses, after S4). |
| handle-trim-outwards-overlaps | Kept until S4 | Changes what a daily gesture does: a handle dragged outwards covers the next clip instead of pushing it. Announce it. |
| handle-trim-inwards-is-a-deletion | Kept until S4 | With the one above. |
| insert-inside-a-transition | Kept until S4 | Today's result (transition removed, clip cut) follows the original's paste; the track model's "time opened on every lane" leaves an overlap. Check against the original's editor before adopting: `RangeEditing.paste` and `insert` must keep agreeing. |
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

Three kinds need a check against the original's editor, not only its model, before their
stage adopts them: `insert-inside-a-transition`, `range-inside-a-transition` and
`pauses-leave-a-transition-without-footage`. In each the model allows a state (two pictures
overlapping with no transition between them) that the editor may never produce.
