# Transitions: storing how a transition was fitted

The port saves a transition's settings and nothing about how it was fitted to the footage: `TransitionLayout.sequence` works the factor out again from the clips every time it is asked (`docs/TRANSITIONS-MIGRATION.md`, section 4.1). The original stores the fit. This is the specification for storing it here too, written before schema 13 ships in any release.

Paths starting with `YouRec/` and `Tests/` are in the original iOS app's repository (read at its commit `faf1815`); the others are in this one, with the line numbers of main at `2e652d4`. The original's numbers were worked out by reading its source, since its tests cannot be run here; the Android numbers come from running the core with throwaway probes.

What the missing fit does today, measured:

| Probe | Today | The original |
|---|---|---|
| Three 4 s clips saved with two 3 s dissolves (factors 0.667 / 0.667, duration 8), split at 1.5 s | factors 0.606 / 0.727, second clip moves from 2.0 to 2.18 | factors unchanged, nothing moves |
| Two 4 s clips, 1 s dissolve; the first trimmed to 0.5 s, then extended back to 4 s | shortened to 0.5 s (duration 4.0), then 1 s again (duration 7.0) | removed (duration 4.5), and still removed (duration 8.0) |
| Two 4 s clips, 1 s dissolve running 3.0…4.0; a 2 s clip inserted at 3.4 s | `[A, X, B]`, X begins at 4.0 | `[A 0…3.4, X, A 3.4…4, B]`, X begins at 3.4 |

## 1. What the original stores

### 1.1 The relation

A transition is one entry of `TrackTimeline.transitions` (`YouRec/TrackTimeline.swift:500`), of type `TimelineTransition` (`:462-470`):

| Field | Type | JSON | Notes |
|---|---|---|---|
| `outgoing` | `UUID?` | `"outgoing":"…"`, left out when nil | The picture the transition leaves. Nil for an opening. |
| `incoming` | `UUID?` | `"incoming":"…"`, left out when nil | The picture it enters. Nil for a close. |
| `settings` | `ClipTransition` | `"settings":{…}` | What the person entered (`YouRec/Transitions.swift:39-64`); never altered by fitting. |
| `origin` | `EditTime` | `"origin":{"ticks":n}` | Timeline position where the picture effect starts. Ticks of 1/240 000 s (`TrackTimeline.swift:5-15`). "Persist the resolved picture/sound placement. Rendering never ripples." (`:467`) |
| `factor` | `Double` | `"factor":1` | 0…1, the scale the transition was fitted with. Declared with a default of 1 but always written, and required when reading (the type's coding is synthesized). |

`id` is not stored: it is `settings.id` (`:463`). Both sides are named by the id of the picture (`VideoTimelineItem.id` is its clip's id, `:112`). A relation between two pictures names both; an opening names only `incoming`; a close names only `outgoing`. There is no separate storage for the two ends of the timeline: they are relations with one side nil, and the edge (−1 opening, +1 close, 0 between) is worked out from which side is nil wherever a timing is needed (`Transitions.swift:288`, `:352`; `TrackTimeline.swift:861`, `:890`).

The pictures themselves are stored at explicit positions: `VideoTimelineItem.start` in ticks, with the played length in `mapping` (`TrackTimeline.swift:111-125`). The original's timeline does not ripple: "There is no inferred magnetic track" (`:485-486`). So the saved truth is *positions, settings, factor*; `origin` is kept consistent with them.

### 1.2 Stored, and derived

| | Stored | Derived |
|---|---|---|
| `settings`, `factor`, `origin`, `outgoing`, `incoming` | yes | `origin` is written again from the pictures on every prune (`TrackTimeline.swift:879`, `:883-884`); `factor` is found again only when the pictures' overlap no longer matches it (`:863-876`) |
| The timing (`motion`, `hold`, `leftEnd`, `rightStart`, `lower`, `upper`, `leftCost`, `rightCost`, the sound ramps) | no | `TransitionTiming(settings, factor:, edge:)` whenever needed (`Transitions.swift:97-156`) |
| `generated` regions (the stretch each transition occupies, and the frozen picture of a still) | yes | rebuilt from the relations on every prune (`TrackTimeline.swift:889-895`) |
| `clip.transition`, `clip.openingTransition`, `settings.nextClipID` | no: the saved clip content has both cleared (`:513`, `:1354`) | projected from the relations when the clips are read (`:1259-1266`) |
| Joins, their `time`, `range`, `videoRange`, the placements | no | `TimelineLayout(timeline:)` (`Transitions.swift:271-332`) |

Nothing is fitted when a document is read or laid out. Of the relations, reading only keeps one per slot (`retainUniqueTransitionSlots`, `YouRec/Project.swift:179`); `validate` then refuses a relation whose factor is not a finite number in 0…1, whose settings are invalid, that names no picture or a picture that is not there, and two relations in one slot (`TrackTimeline.swift:1212-1218`). Every commit prunes first (`Project.swift:312-315`, `YouRec/VideoTools.swift:551-566`).

### 1.3 A document without the stored fit

The original has this path. A step saved before the timeline existed holds `clips` and no `timeline`; reading it converts once, "Explicit conversion of existing development projects" (`Project.swift:175-185`), through `TrackTimeline(clips:)` (`TrackTimeline.swift:510-534`): the clips are laid out by the sequence rule `TimelineLayout(clips:)` (`Transitions.swift:199-252`), each picture is given the start that layout found, and each join with a transition becomes a relation with that layout's `origin` and `factor` (`TrackTimeline.swift:528-533`). From then on the step is saved with its timeline (`Project.swift:186-191`).

The sequence rule is the one `TransitionLayout.sequence` ports: every factor starts at 1; then, clip by clip from the first, when the transition into a clip and the one out of it together cost more than the clip (beyond 10⁻⁸ s), both are multiplied by the largest scale that fits, found by 48 halvings (`Transitions.swift:213-222`). It is a property of the whole list, and independent of the order in which the transitions were made.

## 2. When the fit is computed, kept, found again, or the transition removed

"Fit" below is the factor. Outcomes: **unchanged**, **refitted**, **removed**, **inactive** (kept, not playing).

### 2.1 Setting or changing a transition

`EditState.settingTransition(_:at:)`, `Transitions.swift:335-386`.

1. The relation between exactly these two pictures is removed, and the relations pruned (`:340-341`). With nil settings that is all: **removed, and every picture stays where it is** (`:342`). `Tests/TransitionTests.swift:23-24`: "Removing a transition retains explicit clip positions". Closing the timeline again is a separate step, `restoringStraightCut` (`:387-396`), which moves the incoming picture and what follows it by `left.end − right.start`: `TransitionTests.swift:25`, "Explicit straight-cut restoration recovers sequence duration"; `:37`, "Removing a long overlap restores all footage" (duration 32); `Tests/VideoToolsTests.swift:783`, "Deleting a transition restores a straight cut with all source footage". The editor's Remove uses `restoringStraightCut` (`YouRec/TransitionEditor.swift:76-78`, `VideoTools.swift:522-525`).
2. **Slot rule.** "Each picture has one incoming and one outgoing transition slot. A new cut transition replaces edge fades on those same sides." Every relation leaving the outgoing picture or entering the incoming one is removed (`:343-347`). `Tests/TrackTimelineTests.swift:953`: "A cut transition replaces the old outgoing and incoming edge fades".
3. **Free footage.** The transitions on the far sides keep theirs: `leftFree = max(0, outgoing length − rightCost of the relation entering the outgoing picture)`, `rightFree = max(0, incoming length − leftCost of the relation leaving the incoming picture)`, each with that relation's stored factor; a missing side counts 604 800 s (`:349-357`).
4. **Bisection.** Factor 1 when `leftCost ≤ leftFree + 10⁻⁸` and `rightCost ≤ rightFree + 10⁻⁸`. Otherwise 48 halvings of 0…1 with the same test, keeping the low end (`:359-368`). The entered settings are never looked at through an earlier factor: every setting starts again from 1.
5. **One frame.** When the fitted picture is shorter than `1/30 − 10⁻⁶` s nothing is added, and the edit keeps the removals of steps 1 and 2 (`:369-370`). The editor does not offer Apply in that case (`TransitionEditor.swift:41`).
6. **Place.** `origin = outgoing end − leftEnd`; for an opening, `max(0, incoming start − rightStart)`, so sound leading the picture is reserved at zero instead of moving the first picture negative (`:371-374`). The incoming picture and everything linked that starts at or after it move to `origin + rightStart`, with the relations wholly among them (`:375-380`, `:400-417`). The outgoing picture does not move.
7. The relation is appended with that origin and factor, and the relations pruned (`:381-383`).

What the neighbours keep: their factor, their footage, and their place relative to their own pictures. They are never shortened to make room; the new transition gets what is left. So the result depends on the order of setting. `TransitionTests.swift:49-54`: three 4 s clips, 3 s at join 1 then at join 2. Join 1 fits whole (cost 3 of each clip); join 2 finds 4 − 3 = 1 s free in the middle clip and gets factor 1/3 (1 s). "Adjacent transitions never require three simultaneous clips" (`incoming + outgoing ≤ 4.000001`), "Transition windows never cross". The sequence rule on the same clips gives 2/3 and 2/3. Both give a duration of 8.

Other pinned numbers: `TransitionTests.swift:14-16`, two 4 s clips and a 1 s dissolve, "Overlap shifts the second clip and join by the correct amounts" (duration 7, second clip at 3, join at 3.5); `:17`, "Adding an overlap never changes source trims"; `:43-45`, "Fade through black retains timeline length" (duration 8, range 3.5…4.5); `:46-48`, "Black opening/closing retain the whole project" (duration 8, ranges 0…1 and 7…8); `:110-112`, "Opening color holds cannot collide with the next transition"; `:273-277`, "Re-editing an opening J-cut stays anchored without gaps or detached audio drift"; `:371-373`, 90 random runs, "Long holds and custom sound fit available footage" (`rightCost + leftCost ≤ clip length + 1 tick`).

### 2.2 Changing only its style or parameters

There is no edit in place. The editor starts from the join's settings, so the id is kept (`TransitionEditor.swift:64`), and Apply calls `settingTransition` with the changed settings (`:76-78`): **refitted from factor 1** against the free footage, by every rule of 2.1. A transition that had been shortened, and whose clips have grown since, gets its full length back at this moment and at no other.

### 2.3 Split

`TimelineEditing.split`, `YouRec/TimelineEditing.swift:45-62`, and `TrackTimeline.split`, `TrackTimeline.swift:1007-1079`.

| Where | Outcome | Rule |
|---|---|---|
| Strictly inside a transition's `range` (more than 10⁻⁵ s from both ends) | refused, nothing changes | `TimelineEditing.swift:48`. `TransitionTests.swift:20`: "Splitting inside an overlap is refused without modifying footage" (at 3.25 in 3…4). The editor refuses on the whole half-open range (`YouRec/EditorView.swift:1097`). |
| The outgoing clip, outside the range | **unchanged**; the relation now leaves the second piece | The first piece keeps the clip's id, the second gets a new one, and `outgoing` follows it (`TrackTimeline.swift:1015-1016`, `:1059-1061`). The pictures have not moved, so the prune finds the overlap the factor gives and leaves it (`:863-866`). |
| The incoming clip, outside the range | **unchanged**; the relation still enters the first piece | The first piece keeps the id. |
| At an end of the range | as outside | The test at `:48` is strict. |

`TransitionTests.swift:21-22`: split at 1 of the pair above, "Splits preserve the outgoing join and duration" (3 clips, duration 7, `clips[1].transition == dissolve`, `clips[0].transition == nil`). The transitions of other clips are not looked at. Outside the range the piece that carries a transition always has the footage it costs, since that footage lies inside the range; the prune's cost check (`:878`) is what would remove it otherwise.

### 2.4 Trim

The original has two paths.

**Changing a clip's source bounds** (`EditState.clips` set, `TrackTimeline.replaceVideoContents`, `TrackTimeline.swift:1339-1437`). This is the path the transition tests pin, and it ripples as the port's trim does (`:1387-1415`).

- The cost is taken on the clip that changed: `rightCost` of the relation entering it plus `leftCost` of the one leaving it, at their stored factors (`:1362-1369`).
- Cost greater than the new length (no tolerance) and `start` or `end` changed: **both relations of the clip are removed**, "Trimming away the footage required by a transition removes that relation, as an ordinary trim promises" (`:1370-1375`). Which end was trimmed does not matter, and neither does which of the two transitions was short of footage.
- Otherwise: **unchanged**. The clip keeps its place after the transition into it, and the next picture is moved to keep the transition out of it as it was (`:1387-1397`). The handle is simply other footage now.
- Extending afterwards: the removed relation is gone, so **nothing comes back**; a kept one stays at its factor, also when that factor is below 1 and there is now room for more.

`TransitionTests.swift:40-42`: the 1 s dissolve between two 4 s clips, first clip's `end` set to 0.5, "A trim that removes required transition handles removes the invalid relation"; then `end` set back to 4, "Extending does not resurrect removed transitions".

**The editor's trim handles** (`EditorView.swift:1241-1258`).

- An edge folded into an overlapping transition has no handle at all: a card trims on a side only where its visible edge is the edge of its footage (`TrackTimeline.swift:1689-1690`, `:1692-1719`; `YouRec/EditorTimeline.swift:597-598`). `TrackTimelineTests.swift:709-711`: "Internal transition handles cannot masquerade as ordinary trim edges". A transition that overlaps nothing (a fade through color between clips, a still) leaves the handle.
- Inwards is a range delete of the trimmed part (`:1249-1256`): the clip is split there and the piece removed. A relation that named the removed piece is **removed** (`TrackTimeline.swift:860`); one on the kept piece is **unchanged** while the piece still has the footage it costs, else **removed** (`:878`).
- Outwards is `extend` (`TrackTimeline.swift:1618-1675`): "Other items keep their positions: revealing into an occupied interval creates an editable overlap". Where that changes how far two joined pictures overlap, the prune finds the factor for the new overlap (**refitted**, the only place a transition grows by itself) or, when no factor gives it, **removes** the relation and leaves a plain overlap (`:863-876`).

### 2.5 Speed, and pauses

Same function as a trim, the other branch: the played length changed and `start` and `end` did not. "Speed changes preserve the source footage. Fit both handles together before moving the next picture": when the cost at the stored factors is greater than the new length, the largest scale with `cost(scale) ≤ length` is found by 48 halvings (no slack) and **both factors are multiplied by it** (`TrackTimeline.swift:1376-1385`). Otherwise **unchanged**; a clip made slower does not give a shortened transition its length back.

`TrackTimelineTests.swift:832-837`: three 4 s clips, a 4 s dissolve at join 1, the first clip at 4×: "Speed refits a full-length transition without negative time or dropping it" (one relation, second picture still at 0), "Shrinking footage already inside an overlap does not move the next cut" (duration 8). `TransitionTests.swift:30-33`: both clips at 0.25× and a 12 s dissolve, "Transitions extend beyond ten seconds up to available footage" (`maximum == 16`, duration 20).

Pauses in the original are a setting of the clip (`clip.pauses`), which changes its played length and not its bounds, so removing pauses takes this branch too: **refitted together when they no longer fit, otherwise unchanged**. No refit removes a relation, even one left shorter than a frame: its timing then has no picture (`Transitions.swift:122`) and the relation stays.

### 2.6 Move and reorder

The order of the stored array means nothing: `TransitionTests.swift:38-39`, "Reordering payload storage never reassigns a transition or changes participant identity". A move changes positions (`TrackTimeline.move`, `TrackTimeline.swift:703-725`):

- Both pictures of a relation move together: its origin moves with them, **unchanged** (`:712-716`).
- One moves without the other: the prune looks for the factor that gives the new overlap; when none does, or a picture lacks the footage, **removed** (`:863-880`). A picture moved away from its partner therefore loses the transition, and a transition is never handed to the new neighbour.
- An opening or a close has one picture and goes with it wherever it is moved (`:883-884`). `TrackTimelineTests.swift:936-938`: "Moved fades are anchored to their own picture", "A moved opening fade leaves the adjacent cut available".

### 2.7 Deleting a clip or a range

`TrackTimeline.remove`, `TrackTimeline.swift:771-827`; a range is first cut at its two ends by `scoped` (`:1080-1094`).

- A relation that names a removed picture is **removed** (`:860`).
- Every other relation is **unchanged**; its origin follows its picture when the gap closes (`:800-805`).
- Only the vacated time closes, "including the handles of removed transitions" (`:783-790`). `TrackTimelineTests.swift:142`: three clips with 0.5 s dissolves at both joins, the middle one removed, "Deleting a linked clip between two transitions closes only the newly vacated gap" (the two that are left meet at a plain cut).
- A range end inside a transition: the editor puts a mark on the transition's join instead (`EditorView.swift:1115`, "Selection aligned to the transition join"), and a tool's scope refuses it (`VideoTools.swift:299-303`; `TransitionTests.swift:55-56`, "A selection inside overlap cannot silently modify excess footage").

### 2.8 Insert and paste at a point

`TrackTimeline.paste` and `preparePaste`, `YouRec/TimelinePaste.swift:136-194`.

| Where | Outcome |
|---|---|
| Outside every transition | The picture showing there is cut at the point and the tail moves (`:185-189`). Every relation **unchanged**: the one leaving the cut clip goes with its second piece (2.3). |
| On the join of a transition (the playhead within 24 ticks, 0.0001 s, of `join.time`, `:16-22`, and that target chosen) | The transition is **removed** with `restoringStraightCut`, and the insertion goes between its two clips, at the start of the incoming one (`:161-163`, `:178-185`). `Tests/TimelineClipboardChecks.swift:92`: "Pasting picture into a dissolve recovers both source handles" (no relations, duration 12). |
| Strictly inside a transition's stretch (`generated.interval.start < point < end`) | Every transition crossing the point is **removed** with `restoringStraightCut`, so both clips have their whole length again and the tail has moved; then the picture showing at the same timeline second is cut there (`:176-186`). `TimelineClipboardChecks.swift:112`: "A mid-transition scene paste retains all source handles" (pasted 0.1 s before the join; no relations, duration 12). |

Both clips are whole again before the cut, so for a dissolve the point always falls in the outgoing clip.

### 2.9 Copy and paste of clips, duplicating a project

- A copied fragment keeps a relation only when both its pictures are in it; an opening or a close goes with its one picture (`TimelinePaste.swift:70-75`, the prune at `TrackTimeline.swift:860`). Its factor travels, and the paste gives the relation, its settings and its pictures new ids (`TrackTimeline.swift:1568`): **unchanged**. `TimelineClipboardChecks.swift:97`: "An internal transition travels with both selected partners and keeps the real overlap".
- Clips handed over as clips (a recorded or imported take: `TimelineEditing.inserting`) carry no transition: "The clipboard contains clips, not the neighboring join identity" (`TimelineEditing.swift:98-99`).
- Duplicating a project copies the document; nothing is fitted (`YouRec/ProjectArchive.swift:178`).

### 2.10 Undo and redo

A history step is a whole `EditState`, relations included. `TransitionTests.swift:26-28`: "Transition parameters and undo/redo survive reopening" (`reopened.edit == edit`, which compares origin and factor); `:34-36`; `:375-376`, "All new transition settings survive saved Undo/Redo". Nothing is fitted again on undo, redo or reading (1.2).

### 2.11 The opening and the close when the first or last clip changes

They belong to their picture, not to the ends of the timeline.

- The first or last clip is removed: **removed** with it.
- It is moved, or a clip is put before the first or after the last: **unchanged**, and it goes on playing where its picture now is; the layout lists it as a join of its own (`Transitions.swift:299-322`).
- A take recorded later is appended at the timeline's duration, which includes the stretch of a close (`Project.swift:255-263`, `TrackTimeline.swift:503`), so it begins after the close has finished.
- A transition set between the first clip and one put before it takes the slot and **removes** the opening (2.1, step 2); likewise the close.
- Trim and speed of that clip: as 2.4 and 2.5; the opening counts as the transition into the clip and the close as the one out of it (`TrackTimeline.swift:1362-1369`).

## 3. Validation and pruning

`TrackTimeline.pruneRelations`, `TrackTimeline.swift:847-899`, runs after every edit of the timeline (`:637`, `:724`, `:806`, `:1077`, `:1160`, `:1436`, `:1582`, `:1603`, `:1614`, `:1674`; `Transitions.swift:341`, `:383`, `:394`; `TimelinePaste.swift:75`, `:103`; `VideoTools.swift:565`). In this order:

1. Links lose members that are gone; a link of fewer than two is dropped (`:849`).
2. Placements of pictures that no longer overlap are dropped (`:850-855`).
3. Each relation, in stored order and independently of the others (`:856-887`):
   - **Dropped** when a picture it names is gone (`:860`).
   - **Between two pictures** (timing with edge 0):
     - `required = outgoing end − incoming start`. When the overlap its factor gives differs from that by more than 2 ticks (8.3 µs), the factor is found again: the overlaps at factor 0 and at factor 1 bound what can be had; **dropped** when `required` lies more than 10⁻⁵ s outside them; else 48 halvings, and the factor is the middle of the last interval (`:863-876`). The overlap need not grow with the factor: the test follows whichever way it runs (`:873`). A still without custom sound overlaps by a fixed negative amount whatever the factor, so its factor is never found again, only kept or dropped.
     - **Dropped** when `leftCost > outgoing length + 10⁻⁵` or `rightCost > incoming length + 10⁻⁵` (`:877-878`). Each relation is checked against its own two pictures; the sum of the two transitions on one clip is not checked here.
     - `origin = outgoing end − leftEnd`, rounded to a tick (`:879`).
     - **Dropped** when `origin + rightStart` is more than 2 ticks from the incoming start (`:880`).
   - **An opening or a close**: `origin = picture end − leftEnd` for a close, `picture start − rightStart` for an opening (`:881-885`). Never dropped for want of footage, and its factor never changes here.
4. One relation per slot: going from the last to the first, a relation is kept unless a later one already entered the same picture or left the same picture (`:836-846`, `:888`). `TrackTimelineTests.swift:961`: "Pruning deterministically retains the latest transition in each slot"; `:959`, "Validation rejects competing transition slots".
5. The generated regions are rebuilt, `max(0, origin + lower) … max(0, origin + upper)` (`:889-895`).

The other tolerances: the fit's 10⁻⁸ s slack (`Transitions.swift:361`); the one-frame minimum `videoDuration ≥ 1/30 − 10⁻⁶` when a transition is set (`:370`); a timing whose picture is shorter than that has neither motion nor hold, except a still (`:122`); the split's 10⁻⁵ s margin (`TimelineEditing.swift:48`).

## 4. Layout from the stored form

`TimelineLayout(timeline:)`, `Transitions.swift:271-332`. Nothing is fitted; each relation gives `timing = TransitionTiming(settings, factor, edge)`.

| | From the stored form | Line |
|---|---|---|
| Picture positions | stored (`item.start`) | `:327` |
| `origin` | stored; without a relation, the outgoing end, else the incoming start, else 0 | `:290` |
| Join `time` | between two pictures `origin + timing.cut`; an opening, the start of its picture; a close, the end of its picture; without a relation, `origin` | `:291` |
| `range` | `max(0, origin + lower) … max(0, origin + upper)` | `:293` |
| `videoRange` | `origin … origin + videoDuration` | `:189` |
| `audioIn`, `audioOut` | the timing's ramps moved by `origin` | `:190-191` |
| `maximum` | the shorter of the two pictures' whole lengths (604 800 for a missing side) | `:294` |
| Placement | `incoming` and `outgoing` are `max(0, timing.overlap)`; `lower` and `upper` are the times of the joins into and out of the picture, or its own start and end | `:324-330` |
| Duration | the latest end among pictures, sound items and generated regions | `TrackTimeline.swift:503` |

On a timeline whose clips simply follow one another, the positions the stored form holds are those the sequence layout computes from the same factors (`Transitions.swift:223-234`): with `t(i)` the timing of join i,

```
origin(0) = −min(0, t(0).rightStart)            start(0) = origin(0) + t(0).rightStart
origin(i) = start(i−1) + length(i−1) − t(i).leftEnd    start(i) = origin(i) + t(i).rightStart
origin(n) = start(n−1) + length(n−1) − t(n).leftEnd
duration  = max(start(n−1) + length(n−1), origin(n) + t(n).upper)
```

So on Android `origin` follows from the order of the clips and the factors, as the prune recomputes it from the pictures (section 3). The factor is the only part of the fit that cannot be had from anything else.

### The Kotlin port

`TransitionLayout.kt:196-362` ports the stored form as `Relation`, `Arrangement`, `setting`, `restoringStraightCut` and `refitted`.

- `setting` (`:271-290`) against `settingTransition`: complete. Same slot rule, free footage, fit, one-frame rule, origin and shift. It finds the join by position in playing order where the original asks its layout, and moves every picture starting at or after the incoming one where the original moves the linked ones (`Transitions.swift:400-409`); the same on a sequence.
- `restoringStraightCut` (`:293-300`): complete.
- `refitted` (`:321-362`) against `pruneRelations`, step 3 and 4: complete, with the same tolerances (2 ticks, 10⁻⁵ s). It does not round the origin to a tick.
- `fit` (`:65-78`): complete, slack included.
- `Arrangement.joins` (`:228-247`) against `TimelineLayout(timeline:)`: **not complete**. It pairs chronological neighbours, where the original takes the relation entering each picture and reads its outgoing picture from the relation (`Transitions.swift:280-282`); it has no `selectable`, no outgoing and incoming ids, no placements, no joins for relations away from a boundary (`:299-310`) and no cut beside a moved opening (`:311-322`). All of that matters only once pictures can be placed freely.

Neither half is what a sequence with stored factors needs: `sequence` derives the factors, and `Arrangement` wants positions the port does not store. What is needed is `sequence` reading the factors (sections 5.3 and 6.2).

Two things `sequence` takes from `TimelineLayout(clips:)` differ from the stored-form layout the original's editor actually reads, whether or not the fit is stored (open point 5): the time of the opening join is 0 and that of the close is the duration (`Transitions.swift:244`), where the stored form gives the start of the first picture and the end of the last (`:291`), which differ when a close adds time or an opening holds the first clip back; and `maximum` is the footage the neighbouring transitions leave, halved for a fade (`:238-243`), where the stored form gives the shorter clip (`:294`).

## 5. Proposed Android format

### 5.1 Fields

On `TimelineClip` (`Project.kt:111-133`), declared after `subtitleWords` so no positional caller is disturbed:

| JSON name | Type | Default | Meaning |
|---|---|---|---|
| `transitionFit` | `TransitionFit?` | absent | The fit of `transition`: of the join to the next clip, or of the close on the last clip. |
| `openingTransitionFit` | `TransitionFit?` | absent | The fit of `openingTransition`. |

```kotlin
@Serializable data class TransitionFit(val factor: Double = 1.0)
```

```json
{"id":"C1","sourceID":"S1","start":0.0,"end":4.0,
 "transition":{"id":"J1","nextClipID":"C2","style":"dissolve","duration":3.0},
 "transitionFit":{"factor":0.6666666666666643}}
```

- `factor` is the original's name and meaning (`TrackTimeline.swift:469`): a finite number in 0…1 (`:1216-1217`). `encodeDefaults` is on, so it is written also when it is 1: a fit that is present is complete.
- **`origin` is not stored.** The original needs it because its pictures are placed freely; here it follows from the clip order and the factors (section 4), it would have to be rewritten on every later clip for each edit, and two sources for one number can disagree. The object leaves room for it (`"origin":{"ticks":n}`) on the day clips are placed freely.
- **Not inside `TransitionSettings`.** The settings are what the person entered, the port of `ClipTransition`, which has no factor (`Transitions.swift:39-64`); and their equality is how the port tells whether a join changed (`TimelineEditing.kt:222`, `EditorScreen.kt:886`, `:1612`).
- Which side it names: as today, the transition lives on the outgoing clip under `transition` (its `nextClipID` is the original's `incoming`) and the opening on the incoming clip under `openingTransition`; a fit sits beside the transition it belongs to.
- A fit without its transition means nothing: ignored when read, cleared by whatever clears the transition.
- `Project.validate` (`Project.kt:440`) refuses a factor that is not finite or outside 0…1.

### 5.2 A project without the fields

Saved by 0.5, or by main before this step. The options:

| | How | Consequence |
|---|---|---|
| **A. Absent means "not fitted yet"** (recommended) | The layout derives the factor of a transition without a fit by the sequence rule, as today. The first edit that depends on the layout stores the fit of every transition in play (5.3) before applying its own rule. | Old steps keep their bytes and file names, and a project that is only opened, or edited in ways that move nothing (titles, color, crop, sound), is written as it was. The same conversion as the original's, made later. |
| B. Convert when read, as the original does (1.3) | Every step read gets its fits at once. | Every history step holding a transition is written again with new bytes and a new file name at the next save (`docs/TRANSITIONS-MIGRATION.md`, section 1), and `ProjectFormatTest.aProjectWithTheTwoOldTransitionsIsReadAndWrittenBackByteForByte` no longer holds. |
| C. Absent means factor 1 | No conversion. | Wrong for every transition the sequence rule shortened: it would ask for footage that is not there. |

With A, both conversions use the same rule, so a project lays out after the conversion exactly as before it.

### 5.3 The rule for a list of clips, some fitted and some not

`TimelineEditing.fitted(clips)`: the clips with a fit on every transition in play (valid settings, and for a join its `nextClipID` naming the next clip; the opening on the first clip; the close on the last).

- Seed each join's factor with its stored value, or 1 where there is none.
- When every transition in play has a fit, that is the answer and the list is returned as it is, the same instance.
- Otherwise run the original's loop over the seed (`Transitions.swift:213-222`) and store the result on every transition in play. For a list with no fit at all this is exactly the sequence rule.
- A transition not in play keeps whatever fit it has.

`TimelineEditing.layout(clips)` lays out `fitted(clips)` and never shortens a stored factor.

### 5.4 Schema

Schema 13 is the first to hold `TransitionSettings` in full, and it has not shipped: the released 0.5 reads up to 11 (`LATEST_READABLE_SCHEMA = 11` at tag `v0.5`), and main, still versioned 0.5, is the only writer of 13. Adding two optional fields now needs no further number.

A fit does not by itself raise a clip's schema (`TimelineClip.schema`, `Project.kt:147-158`): a project of old-style transitions stays at 2 after a split, as section 5 of the migration plan promises, and 0.5 still opens it. 0.5 ignores unknown keys, lays the transitions out by its own rule and writes the step back without the fits; the next version then derives them again. That is the same latitude Option A of the migration plan already took. The alternative, a fit counting as schema 13 so that 0.5 refuses such a project, is open point 10.

## 6. Impact

### 6.1 The rules as they land here

A clip here is the original's linked picture and sound and clips always follow one another, so "the incoming picture moves" is simply the layout. Every function below starts from `fitted(state.clips)`.

| Operation | Rule | From |
|---|---|---|
| Set a join | Remove what is on the join; fit the new settings from factor 1 into `free(left length, rightCost of the transition into the left clip)` and `free(right length, leftCost of the transition out of the right clip)`, both at their stored factors; store the factor. Neighbours unchanged. | 2.1 |
| Remove a join | Clear the transition and its fit. | 2.1 (`restoringStraightCut`) |
| Split | Refused inside a range, as today. The first piece keeps the opening and its fit, the second the transition and its fit. No factor changes. | 2.3 |
| Trim | When `rightCost` of the transition into the clip plus `leftCost` of the one out of it is greater than the new length: remove both. Otherwise no factor changes. | 2.4, first path |
| Speed | When that cost is greater than the new length: multiply both factors by the largest scale that fits (48 halvings, `cost ≤ length`). Otherwise no factor changes. | 2.5 |
| Remove pauses | The transition into the clip stays on the first piece and the one out of it on the last. Each is scaled as for speed until its cost fits its piece (one piece left: both together). | 2.5; open point 3 |
| Delete, move | As today (`withoutBrokenJoins`), clearing the fit with the transition. Every other fit unchanged. | 2.6, 2.7 |
| Insert, paste | On a join (within 0.0001 s of its time): between the two clips. Strictly inside a range: remove every transition running there, then cut the clip showing at that second. Elsewhere: cut. Every other fit unchanged. | 2.8 |
| Copy | A transition travels with its fit when both its clips do. | 2.9 |
| After every edit | A transition between two clips whose `leftCost` exceeds the outgoing clip by more than 10⁻⁵ s, or whose `rightCost` exceeds the incoming one by as much, is removed. With the rules above this never fires; it is the guard the original runs. | 3 |

Kept on the clip but **inactive**, as today: a transition whose `nextClipID` is not the next clip or whose settings are invalid; a close on a clip that a later recorded take now follows (`docs/TRANSITIONS-MIGRATION.md`, section 10); and one left with less than a frame by a speed refit (`TimelineEditing.join` hides it, `TimelineEditing.kt:28-29`).

### 6.2 Core functions

`TimelineEditing.kt`

| Function | Lines | Change |
|---|---|---|
| `layout` | 18-25 | Passes the stored factors (opening first, then each clip's) to `TransitionLayout.sequence`; lays out `fitted(clips)`. The cache by list identity stays. |
| `fitted` (new) | | 5.3. |
| `pruned` (new, private) | | The guard of 6.1, last row. |
| `join`, `clipStart`, `duration`, `locate`, `chains`, `overlap`, `soundLevel`, `titlesAt`, `bookmarkTimes` | 28-74, 238, 257, 312, 355 | None; they read `layout`. |
| `split` | 76-95 | Fits first. The first piece clears `transitionFit` with `transition`, the second `openingTransitionFit` with `openingTransition`. |
| `delete`, `move` | 98-108 | Fit first; `withoutBrokenJoins` clears the fit too. |
| `insert` | 119-132 | The paste rule (6.1) instead of going to the nearer edge of the clip when the point is inside a transition. Its comment (110-118) changes. |
| `trim` | 149-154 | The removal rule. `trimmed` (138-146) is unchanged. |
| `removeIntervals` | 162-181 | The pieces carry the fits with the transitions; then the refit of 6.1. |
| `withoutBrokenJoins` | 191-195 | Clears the fit with the transition it clears. |
| `transition`, `joinSettings` | 198-207 | None. |
| `setJoin` | 217-227 | Fits by 2.1 and stores the factor. Null when what fits is under a frame, as today. Null for unchanged settings only when the factor found is also the stored one, so applying the same settings again lets a shortened transition grow (2.2). No longer asks `join(next, …)` whether the layout kept it. |
| `setTransition` | 230-235 | None beyond `setJoin`. |
| `setSpeed` | 455-460 | The joint refit. |

`TransitionLayout.kt`

| Function | Lines | Change |
|---|---|---|
| `sequence` | 142-194 | A fourth argument, the stored factors, one per join. The loop at 151-162 is seeded with them and skipped when every transition in play has one. |
| `fit` | 65-78 | Recommended: the halvings test against the free footage without the 10⁻⁸ slack (line 72); the slack stays for "fits whole". See open point 4. |
| `Relation`, `Arrangement`, `setting`, `restoringStraightCut`, `refitted` | 199-362 | None; still unused by the sequence. |

`RangeEditing.kt`

| Function | Lines | Change |
|---|---|---|
| Header comment | 168-178 | "Where it sits is read from the layout, fitted to the footage" becomes "as it was fitted". |
| `delete`, `copy`, `paste` | 418-446, 489-533, 624-674 | Start from the fitted state. |
| `scoped` | 714-727 | Fits before cutting, and still returns the state it was given, the same instance, when it cuts nothing (four tests of `RangeEditingTest` assert it). |
| `divided` | 303-314 | None: it goes through `split`, and a transition lent between two empty clips is not in play, so its fit is kept as it is. |
| `joined`, `inner` | 330-338 | Clear the fit with the transition (through `withoutBrokenJoins`; `inner` directly). |
| `copy` | 520-528 | `transitionFit` travels exactly when `transition` does, `openingTransitionFit` when `openingTransition` does. |
| `paste` | 640-649 | Clears the fits with the crossed transitions. One pass is enough now: a neighbour can no longer take the footage back. |
| `fresh` | 677-695 | The fit is copied with the transition it keeps and dropped with the one it drops. |
| `transitions`, `transitionAt`, `cut`, `resolve`, `carried`, `placed`, `markTime`, `interval` | | None; they read the layout. |

Other core files

| File | Change |
|---|---|
| `Project.kt` | The two fields and `TransitionFit` (111-133); `validate` (440). `schema` (147-158), `commit` and the appending of recorded takes: none. `addRecording` (369) follows `insert`. |
| `UnusedTakes.kt` | `add` (41-52): none in code; a take added inside a transition now removes it and cuts the clip. Comment at 30-36. |
| `InsertionSites.kt` | None; the comment of `time` (86-94) names `insert`. |
| `TimelineNavigation.kt` | None (108-118, 200-220 read `join` and `clipStart`); the stops follow the stored fit. |
| `ProjectDuplicate.kt` | None: the copy is the same model, fits included. |
| `TransitionScene.kt` (85-88), `AudioUnlinking.kt` (60-61, 105, 143, 183), `OpenTimeline.kt` (129-155, 216-225), `SubtitleEditing.kt`, `TitleMotion.kt`, `CoverFramePool.kt`, `TimelineSnapping.kt` | None; they read the layout. |
| `TransitionSettings.kt` | None. |

### 6.3 Call sites in the app (read only)

Lines of `app/src/main/kotlin/app/takes/`. Two need a change; the rest read the layout and follow it.

| File:line | Reads | Change |
|---|---|---|
| `EditorScreen.kt:571-578` | The trim draft: the clip list with the dragged clip replaced by `TimelineEditing.trimmed`, laid out for the strip | **Yes.** With stored fits that list can hold a transition its clip no longer has footage for (the second clip would begin before the first). The draft must be the clips `TimelineEditing.trim` would give. |
| `EditorScreen.kt:1611-1612` | `drafted`: `setJoin`, and "same settings means no change" | **Yes.** `setJoin` can now return a state for unchanged settings (the fit grew); the fallback must not read a null as "remove". |
| `EditorScreen.kt:588-589` | `trim` | None. |
| `EditorScreen.kt:886-887` | `join`, `layout.joins[…].origin`, `timing.videoDuration` | None. |
| `EditorScreen.kt:898`, `1600-1603` | `layout.joins[…].maximum`, `joinSettings` | None (open point 5). |
| `EditorScreen.kt:899`, `921-924` | `setJoin(…, null)`, `layout.joins[…].time` | None. |
| `EditorScreen.kt:944-945`, `1050-1051`, `1068-1069`, `1077`, `1119` | `split`, `setSpeed`, `move`, `delete`, `removeIntervals` | None. |
| `EditorScreen.kt:289-290`, `315`, `339`, `389`, `565`, `582`, `623`, `632`, `850`, `854`, `969`, `1165`, `1209`, `1213`, `1219-1221` | `chains`, `duration`, `clipStart`, `locate`, `soundLevel`, `layout.joins`, `join`, `overlap` | None. |
| `TransitionRendering.kt:304-316` | `layout`, its joins and placements, `chains` | None. |
| `Exporter.kt:184`, `195`, `218`, `232`, `263` | `join`, `duration`, `chains`, `clipStart`, `layout.joins` | None. |
| `TransitionPanel.kt:172-176`, `481` | A `TransitionLayout.Join`, `TransitionLayout.edge` | None. |
| `ProjectStore.kt:316` | `UnusedTakes.add` | None. |

### 6.4 Existing tests whose expected values change

Found by running the core suite (934 tests) against a throwaway implementation of 6.1 and 6.2, which was not kept. Five tests change: three in what they expect to happen, two only in carrying the fit.

| Test | Line | Today | By the original's rule | Why |
|---|---|---|---|---|
| `RangeEditingTest.aShortenedTransitionIsMeasuredAsItWasFitted` | `RangeEditingTest.kt:961` | `join(pasted, 4).factor == 1.0` | `2/3` (0.6667) | The paste removes the first dissolve; the second keeps the factor it had (`TrackTimeline.swift:863-866`). The comments at 946-948 and 955-957 say so already. |
| same | `:962` | `pasted.duration == 11.0` | `12.0` | 3.5 + 2 + 0.5 + 4 + 4 − 2. |
| `TransitionSceneTest.aShortClipBetweenTwoLongTransitionsSharesItsFootage` | `TransitionSceneTest.kt:60-67` | Both `setJoin` succeed; each dissolve 0.5 s; duration 8.0 | The first `setJoin` gets the whole 1 s clip (factor 1/3, 1.0 s); the second returns null, nothing being left (`Transitions.swift:356-357`, `:370`); duration 8.0 | Fitting is in the order of setting (2.1). The sharing the test describes still holds for clips saved without a fit. |
| `TransitionSceneTest.aClipInsertedOnAJoinRemovesItsTransition` | `TransitionSceneTest.kt:249-250` | Inserted at 3.6 in a wipe running 3…4: `[A, C, B]` | `[A, C, the second piece of A, B]`, A cut at 3.6; no transitions | `TimelinePaste.swift:176-186`. On the join itself (3.5) it stays `[A, C, B]`. |
| `RangeEditingTest.copyThenPasteReproducesTheContent` | `RangeEditingTest.kt:417`, `:419` | A fragment and its paste equal clips built without a fit | The same clips with `transitionFit` (factor 1.0) | The copy starts from the fitted state. No timing changes. |
| `RangeEditingTest.everyStyleFollowsTheSameRangeRules` | `RangeEditingTest.kt:755` | As above | As above | As above. |

Five more change by 10⁻⁸ s only if `fit` keeps the original's slack inside its halvings (open point 4), because a transition set on clips too short for it is then fitted by `fit` and no longer by the sequence loop: `DissolveTest.overlapShortensTheTimeline`, `DissolveTest.theMiddleOfADissolveIsTheBoundary`, `DissolveTest.changingStyleKeepsTheJoin`, `TransitionAndTitleTest.fadesMeetAtBlackAndNeverOverlap` (all in `TimelineEditingTest.kt`) and `TimelineNavigationTest.aDissolveShortenedToFitHasTheStopsOfWhatIsLeft`; for example 1.0 becomes 1.00000001 and 9.0 becomes 8.99999999.

Unchanged, and worth keeping an eye on: `ProjectFormatTest` (all seven, since fits are stored by edits and not by `commit`), `TransitionLayoutTest` and `TransitionOriginalParityTest` (they test `sequence` without stored factors, `setting`, `refitted`), `ProjectDuplicateTest`, `ProjectArchiveTest`, `ProjectRestoreTest`.

### 6.5 Tests to add

Clips are 4 s unless said; "saved without a fit" means clips built with their transitions and no `transitionFit`, as current main writes them. Factors to 10⁻⁹.

| # | Case | Expected | Today |
|---|---|---|---|
| 1 | Three clips saved without a fit, 3 s dissolves at both joins | factors 2/3, 2/3; starts 0, 2, 4; duration 8 | same |
| 1a | Probe 1: that state, `split` at 1.5 | 4 clips (1.5 s, 2.5 s, 4 s, 4 s); the dissolve on the second piece; **factors 2/3, 2/3**, both stored; starts 0, 1.5, 2, 4; duration 8 (`TransitionTests.swift:21-22`) | factors 0.6061, 0.7273; starts 0, 1.5, 2.1818, 4 |
| 1b | That state, `delete` of the third clip | factor 2/3; duration 6 | factor 1; duration 5 |
| 1c | Three clips, `setJoin` 3 s at join 1, then at join 2 | factors 1 and 1/3; starts 0, 1, 4; ranges 1…4 and 4…5; duration 8 (`TransitionTests.swift:49-54`) | 2/3, 2/3; starts 0, 2, 4 |
| 1d | State 1c, `split` at 0.5; at 1.5 | at 0.5: factors 1 and 1/3 unchanged; at 1.5: null (inside 1…4) | |
| 2 | Probe 2: two clips, `setJoin` 1 s; `trim` the first to end 0.5 | **no transition** on either clip; second clip at 0.5; duration 4.5 (`TransitionTests.swift:40-41`) | factor 0.5; duration 4.0 |
| 2a | Then `trim` it back to end 4 | **still no transition**; duration 8 (`:42`) | factor 1; duration 7 |
| 2b | State 2 before the trim, `trim` the first to end 2 | factor 1; second clip at 1; duration 5 | same |
| 2c | State 2 before the trim, `trim` the second to start 3.5 (0.5 s left) | no transition; duration 4.5 | factor 0.5; duration 4.0 |
| 2d | Three clips, 1 s dissolves at both joins, the middle one trimmed to 1.5 s | both removed (cost 2 > 1.5, `TrackTimeline.swift:1370-1375`); duration 9.5 | both at factor 0.75; duration 8 |
| 3 | Probe 3: two clips, 1 s dissolve (range 3…4); `insert` a 2 s clip at 3.4 | **4 clips: A source 0…3.4, the new clip, A source 3.4…4, B**; no transitions; the new clip begins at 3.4; duration 10 (`TimelineClipboardChecks.swift:112`) | 3 clips `[A, new, B]`; begins at 4.0 |
| 3a | `insert` at 3.5, the join | `[A, new, B]`; no transitions; begins at 4.0; duration 10 (`TimelineClipboardChecks.swift:92`) | same |
| 3b | `insert` at 2.0 | `[A 0…2, new, A 2…4, B]`; the dissolve on the second piece at factor 1; duration 9 | same |
| 4 | Three clips, `setJoin` 4 s at join 1; `setSpeed` of the first to 4× | factor 0.25; starts 0, 0, 4; duration 8 (`TrackTimelineTests.swift:832-837`) | same |
| 4a | Then `setSpeed` back to 1× | factor stays 0.25; starts 0, 3, 7; duration 11 | factor 1; duration 8 |
| 5 | A 1 s clip and a 4 s clip, `setJoin` 3 s | factor 1/3; duration 4 | same |
| 5a | Then `trim` the first to 4 s | factor stays 1/3; second clip at 3; duration 7 | factor 1; duration 5 |
| 5b | Then `setJoin` with the same settings | not null; factor 1; duration 5 (2.2) | null |
| 6 | A step saved without fits, read and written back; then a title added and committed | byte for byte as read; the new step has no fit either | |
| 7 | State 1a through `Project.manifest` and `readManifest`, and through undo and redo | equal edits, factors included (`TransitionTests.swift:26-28`) | |
| 8 | A fit with factor 1.5, NaN or −0.1 | `validate` refuses (`TrackTimeline.swift:1216-1217`) | |
| 9 | `RangeEditing.copy` of both clips of state 1 and `paste` into an empty edit | the pasted dissolve has factor 2/3 (`TimelineClipboardChecks.swift:97`) | |

## 7. Open points

1. **Which trim.** The port's `trim` changes source bounds and ripples, so 6.1 gives it the rule of the original's function that does the same, the one its tests pin. The original's editor never gets there for an overlapping transition: that edge has no handle. Its own inward trim removes a transition on the trimmed side whatever is left, and its outward trim can make a shortened transition longer or remove one already at full length (2.4). Whether the Android strip should also hide the handle under a transition is for the app.
2. **Inserting a take inside a transition.** The original's paste is specified (2.8). A recorded or imported take goes another way there, `TimelineEditing.inserting` and `insertTime` (`TimelineEditing.swift:86-127`, `TrackTimeline.swift:1145-1161`): both clips are cut at the point, the relation loses its footage and is dropped, and the first piece of the incoming clip is left playing over the end of the outgoing one. A sequence cannot hold that; 6.1 uses the paste rule for both.
3. **Removing pauses.** In the original a pause is cut inside the clip, a retime; here it splits the clip. The rule in 6.1 (refit each transition to the piece it is left on) is this document's reading, not something the source states for separate clips.
4. **The slack inside the halvings.** The original fits with `cost ≤ free + 10⁻⁸` throughout (`Transitions.swift:359-367`), so a shortened transition overdraws its clip by up to 10⁻⁸ s, which its 1/240 000 s grid then absorbs. The port has no grid: with the slack, a 3 s dissolve out of a 1 s clip puts the next clip at −10⁻⁸ s. Recommended: no slack in the halvings (the sequence loop has none either, `:218`); the factor then differs from the original's by at most 10⁻⁸ divided by the transition's length.
5. **The opening's and the close's join time, and `maximum`.** The layout the original's editor reads gives the start of the first clip, the end of the last, and the shorter clip's length (section 4); `sequence` gives 0, the duration, and the free footage. Independent of the fit, and changing it moves marks, snapping and navigation stops, so it is left out of this step.
6. **The opening and the close belong to their clip in the original** and play on in the middle of the timeline when the clip is no longer first or last (2.11). The port removes or ignores them (`docs/TRANSITIONS-MIGRATION.md`, section 10). Unchanged by this step.
7. **A refit below one frame.** The original keeps such a relation, with no picture (2.5). Here it stays on the clip and `join` hides it; whether it should be removed instead was not decided by the source.
8. **Setting a transition that does not fit a frame.** The original removes what was on the join and adds nothing (`Transitions.swift:369-370`), and its editor never asks. `setJoin` returns null and changes nothing; kept.
9. **A list with some fits and some not** arises only when a transition comes back into play (a later take removed after a close was fitted around). 5.3 lets the loop shorten stored factors too in that case; restricting it to the missing ones is the alternative.
10. **Schema.** Recommended: a fit never raises the schema (5.4). If a project touched by this version must not be opened by 0.5 with different timing, add `transitionFit != null || openingTransitionFit != null -> 13` to `TimelineClip.schema`; every project with a transition then moves to 13 at its first split, trim or paste.
11. **The split point.** The original tests the unrounded time against the range and cuts at the time rounded to 1/30 s (`TimelineEditing.swift:47-48`), so a split just outside a range can land just inside it and the prune then drops the transition. Whether that is intended could not be determined; the port cuts where it is asked.
12. **An opening or a close is never checked for footage by the prune** (section 3, step 3), only by the trim and speed rules. The editor's inward trim of the far end of the first clip can therefore leave an opening longer than its clip in the original. With 6.1 that cannot happen here.
13. **Removing a transition keeps positions in the original** (2.1, step 1) and `restoringStraightCut` closes them; here the clips always close. Unchanged.
14. **Not determined:** the original's tests were read, not run; and no test of the original pins what a trim does to a clip with a transition on each side when only one of them lacks footage (the source removes both, `TrackTimeline.swift:1374-1375`).
