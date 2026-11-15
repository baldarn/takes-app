package app.takes.core

import app.takes.core.TrackParity.Case
import app.takes.core.TrackParity.Verdict

/** The parity suite run on every subject, and its findings as text. */
internal object TrackParityReport {
    val cases: List<Case> by lazy {
        val result = ArrayList<Case>()
        // Of each kind the shortest case keeps its answers, to be shown; the others keep what is counted.
        val shown = HashMap<String, Case>()
        fun keep(key: String, case: Case): Boolean {
            val best = shown[key]
            if (best != null && best.size <= case.size) return false
            shown[key] = case
            return true
        }
        fun run(subjects: List<TrackParity.Subject>, samples: Int, seed: Long) = subjects.forEachIndexed { index, subject ->
            val rng = TrackFixtures.Random(seed + index * 104729L)
            for (run in TrackParityCases.runs(subject, rng, samples)) {
                val case = TrackParity.judge(subject, run)
                result += case
                if (case.verdict == Verdict.Identical) { case.drop(); continue }
                // The shortest call that shows a kind by itself is its example; so is the shortest of each unexplained signature.
                val keys = (if (case.kinds.size == 1 && case.unexplained.isEmpty()) case.kinds else emptyList()) + case.kinds.map { "$it, with others" } + listOfNotNull(leftover(case))
                if (keys.map { keep(it, case) }.none { it }) case.drop()
            }
        }
        run(TrackParityCases.probes, 3, 11)
        run(TrackParityCases.golden, 2, 23)
        run(TrackParityCases.generated, 1, 37)
        val kept = shown.values.toSet()
        for (case in result) if (case !in kept) case.drop()
        result
    }

    private fun leftover(case: Case): String? = if (case.unexplained.isEmpty()) null else "${case.function}: ${case.unexplained.sorted().joinToString(", ")}"

    /** The calls whose answer through the track model is a timeline the invariants refuse. */
    fun refused(cases: List<Case>): List<String> = cases.mapNotNull { case -> case.refused?.let { "${case.function}(${case.label}) on ${case.subject.name}: $it" } }

    fun summary(cases: List<Case>): String = buildString {
        appendLine("Parity: ${cases.size} calls, ${cases.count { it.verdict == Verdict.Identical }} identical, ${cases.count { it.verdict == Verdict.Different }} different, ${cases.count { it.verdict == Verdict.NotExpressible }} not expressible")
        for ((kind, found) in cases.filter { it.verdict != Verdict.Identical }.groupBy { it.kind }.entries.sortedByDescending { it.value.size }) appendLine("%6d  %s".format(found.size, kind))
    }

    private fun example(found: List<Case>): Case? = found.filter { it.today != null || it.track != null }.minByOrNull { it.size }

    /** How unique the ids of bookmarks and of transitions are across a whole edit, where today they need only be so within a clip. */
    private fun identities(states: List<Pair<EditState, List<SourceMedia>>>): String {
        var bookmarks = 0
        var transitions = 0
        val refused = LinkedHashMap<TrackInvariants.Violation, Int>()
        for ((state, sources) in states) {
            val marks = state.clips.flatMap { it.bookmarks.orEmpty() }.map { it.id }
            if (marks.toSet().size != marks.size) bookmarks++
            val joins = TimelineEditing.transitions(state.clips).mapNotNull { it.transition?.id }
            if (joins.toSet().size != joins.size) transitions++
            TrackInvariants.violation(state.toTimeline(), sources)?.let { refused[it] = (refused[it] ?: 0) + 1 }
        }
        val rules = if (refused.isEmpty()) "none" else refused.entries.joinToString(", ") { "${it.value} for `${it.key}`" }
        return "${states.size} | $bookmarks | $transitions | $rules"
    }

    fun text(cases: List<Case>): String = buildString {
        val different = cases.filter { it.verdict != Verdict.Identical }
        appendLine("# Parity of today's editing functions with the track model")
        appendLine()
        appendLine("Written by `TrackParityTest` (`core/src/test/kotlin/app/takes/core/TrackParity*.kt`); do not edit by hand.")
        appendLine("Run the core tests with `TAKES_RECORD_PARITY=1` in the environment to write it anew, and read what changed.")
        appendLine("The test fails when this file is not what the suite finds; it does not fail on a difference.")
        appendLine("With `TAKES_PARITY_GATE=1` it fails on every difference whose kind is not in `TrackParityAccepted.kt`.")
        appendLine()
        appendLine("## What is compared")
        appendLine()
        appendLine("Every function of `TimelineEditing`, `RangeEditing`, `SubtitleEditing` and `AudioUnlinking` that changes an edit, and the questions among them that the track model also answers, is called twice on the same edit with the same arguments:")
        appendLine("as it is today, and through the track model (`TrackSequenceView`: the edit is converted to a timeline, the operation the function is to become is run, and the result is brought back to a sequence).")
        appendLine("Today's answer is read as the conversion reads it (fits saved, settings that do not play dropped, sound tracks repacked), so only what plays differently counts.")
        appendLine("Ids made during the call, the track of a sound item, the order of a clip's bookmarks, and times less than three ticks (1/80000 s) apart are not differences.")
        appendLine()
        appendLine("- **identical**: the same edit and the same answer, or neither applies.")
        appendLine("- **different**: both give a sequence, and the two differ.")
        appendLine("- **not expressible**: the track model's result is a valid timeline that no sequence of clips with two sound tracks plays.")
        appendLine()
        val subjects = cases.map { it.subject }.distinct()
        appendLine("The edits: ${subjects.count { it.small }} small ones made for one situation each, ${subjects.count { it.name.startsWith("golden") }} steps of the histories of the golden projects (all but the long one), and ${subjects.count { it.name.startsWith("generated") }} made by ${TrackParityCases.SEQUENCES} seeded sequences of today's functions. ${cases.size} calls.")
        appendLine()
        appendLine("## By function")
        appendLine()
        appendLine("| Function | Calls | Identical | Different | Not expressible | Kinds |")
        appendLine("|---|---:|---:|---:|---:|---|")
        for ((function, found) in cases.groupBy { it.function }) {
            val kinds = found.flatMap { it.kinds }.distinct()
            val open = found.count { it.unexplained.isNotEmpty() }
            appendLine("| `$function` | ${found.size} | ${found.count { it.verdict == Verdict.Identical }} | ${found.count { it.verdict == Verdict.Different }} | ${found.count { it.verdict == Verdict.NotExpressible }} | ${(kinds.map { "[$it](#$it)" } + (if (open > 0) listOf("$open unexplained") else emptyList())).joinToString(", ")} |")
        }
        appendLine("| **all** | ${cases.size} | ${cases.count { it.verdict == Verdict.Identical }} | ${cases.count { it.verdict == Verdict.Different }} | ${cases.count { it.verdict == Verdict.NotExpressible }} | |")
        appendLine()
        appendLine("## The kinds of difference")
        appendLine()
        appendLine("In the order a user would notice them. A call can show several kinds; it is counted under each. \"Plan\" is the row of `docs/TRACKS-PLAN.md`, 2.4. The rule is where the original does what the track model does, as a path in its repository.")
        for (kind in TrackParityKinds.all) {
            val found = different.filter { kind.id in it.kinds }
            if (found.isEmpty()) continue
            appendLine()
            appendLine("### ${kind.id}")
            appendLine()
            appendLine("**${kind.title}.**")
            appendLine()
            appendLine("- Today: ${kind.today}")
            appendLine("- Track model: ${kind.track}")
            appendLine("- Visible effect: ${kind.effect}")
            appendLine("- Rule: ${kind.rule}" + (kind.plan?.let { " (plan: $it)" } ?: ""))
            appendLine("- Seen in: " + found.groupingBy { it.function }.eachCount().entries.joinToString(", ") { "`${it.key}` ${it.value}" } +
                " (${found.count { it.verdict == Verdict.NotExpressible }} not expressible; ${found.count { it.kinds.size > 1 || it.unexplained.isNotEmpty() }} together with another kind)")
            appendLine("- Accepted: ${if (kind.id in TrackParityAccepted.kinds) "yes" else "no"}")
            (example(found.filter { it.kinds.size == 1 && it.unexplained.isEmpty() }) ?: example(found))?.let {
                appendLine()
                appendLine(TrackParity.example(it))
            }
        }
        val open = different.filter { it.unexplained.isNotEmpty() }
        appendLine()
        appendLine("## Not explained")
        appendLine()
        if (open.isEmpty()) appendLine("Every difference found is of one of the kinds above.") else {
            appendLine("${open.size} calls differ in something no kind above accounts for. They are listed by function and by what is left over, each with its shortest example; none of them can be accepted in the gate.")
            for ((name, found) in open.groupBy { leftover(it)!! }.entries.sortedByDescending { it.value.size }) {
                appendLine()
                appendLine("- **$name**: ${found.size} calls" + (found.flatMap { it.kinds }.distinct().takeIf { it.isNotEmpty() }?.let { " (also showing ${it.joinToString(", ")})" } ?: ""))
                example(found)?.let { appendLine(); appendLine(TrackParity.example(it).prependIndent("  ")) }
            }
        }
        appendLine()
        appendLine("## Fades")
        appendLine()
        val long = cases.filter { Tag.LONG_FADE in it.tags }
        appendLine("A fade longer than half its item, which the sequence form used to play for half the item only, was set in ${long.size} calls: ${long.count { it.verdict == Verdict.Identical }} identical, ${long.count { it.verdict != Verdict.Identical }} not, of which ${long.count { it.verdict != Verdict.Identical && it.signature.any { name -> "fades" in name } }} differ in a fade. Those are fades asked longer than the whole item (fade-no-longer-than-its-item).")
        appendLine("The sequence form now holds a fade with its length and how far into it the item begins, so a fade capped at half its item is no kind of difference here.")
        appendLine()
        appendLine("## Ids across the whole edit")
        appendLine()
        appendLine("Today the id of a bookmark and of a transition is unique within its clip; the track model needs each unique across the timeline (`TrackInvariants`, `DuplicateRelation`). Measured on every step of every golden project, the long one included, on the edits the seeded sequences made, and on every edit a function of today answered in the suite, each converted to a timeline:")
        appendLine()
        appendLine("| Edits | Counted | With a bookmark id used twice | With a transition id used twice | Converted timelines the invariants refuse |")
        appendLine("|---|---:|---:|---:|---|")
        val golden = GoldenProjects.all.flatMap { golden -> golden.project.snapshots.map { it.state to golden.project.sources } }
        appendLine("| Golden projects, every step | ${identities(golden)} |")
        appendLine("| Seeded sequences | ${identities(TrackParityCases.generated.map { it.state to it.sources })} |")
        val answered = cases.filter { it.todayAnswered }
        val broken = answered.mapNotNull { it.todayRefused }.groupingBy { it }.eachCount()
        appendLine("| Answers of today's functions in the suite | ${answered.size} | – | – | ${if (broken.isEmpty()) "none" else broken.entries.joinToString(", ") { "${it.value} for `${it.key}`" }} |")
        appendLine()
        appendLine("## Timelines the track model answered")
        appendLine()
        appendLine("Of the ${cases.count { it.trackAnswered }} timelines the operations of the track model answered in the suite, the invariants (`TrackInvariants.violation`) refuse ${cases.count { it.refused != null }}.")
        appendLine()
        appendLine("## Not compared")
        appendLine()
        appendLine("- `TimelineEditing.setText`, `removeText`, `placeTitle`, `setColor`, `setCrop` are compared through the view and its merge (`TrackSequenceView.editingClips`): they stay as they are and edit a clip's recipe.")
        appendLine("- `UnusedTakes.add` is `TimelineEditing.insert` with whole takes, then a search for the first inserted clip by its id (see insert-new-identity).")
        appendLine("- `TimelineEditing.layout`, `fitted`, `join`, `transitions`, `entering`, `leaving`, `locate`, `chains`, `overlap`, `joinMaximum`, `joinSettings`, `transition`, `titlesAt`, `titleRange`, `clipRange`, `newTitleRange`, `soundLevel`, `audioAt`, `nextBookmark`, `previousBookmark`, `bookmarkAt`; `RangeEditing.quantize`, `snap`, `markTime`, `interval`; `SubtitleEditing.active`, `fingerprint`; `AudioUnlinking.detachedSound`, `isDetached`, `clipIndex`, `refusal`, `matches`, `valid`: questions about a sequence that are asked of the sequence form, or whose answer is pinned by the render plan of the golden projects.")
        appendLine("- What is drawn and heard is not compared here: that is the render plan (`RenderPlan`), compared when the renderer reads the timeline.")
    }
}
