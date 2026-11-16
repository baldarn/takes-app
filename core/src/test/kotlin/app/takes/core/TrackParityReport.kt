package app.takes.core

import app.takes.core.TrackParity.Case
import app.takes.core.TrackParity.Verdict

/** The parity suite run on every subject, and its findings as text. */
internal object TrackParityReport {
    /** A group of edits: how many of each kind of argument its calls are tried with, the seed they are chosen by, and which of its edits the sample takes. */
    private class Group(val subjects: List<TrackParity.Subject>, val samples: Int, val seed: Long, val every: Int)

    private val groups: List<Group> by lazy {
        listOf(
            Group(TrackParityCases.probes, 3, 11, 1),
            Group(TrackParityCases.golden, 2, 23, GOLDEN_EVERY),
            Group(TrackParityCases.generated, 1, 37, GENERATED_EVERY),
        )
    }

    /** The sample takes every small edit, one step in this many of the golden projects' histories, and one in this many of the seeded edits (not a divisor of their steps, so every depth of a sequence is taken). */
    const val GOLDEN_EVERY = 8
    const val GENERATED_EVERY = 9

    /** The calls made on [subject], the [index]-th of [group]: the same whether or not the other edits are run. */
    private fun judged(group: Group, index: Int, subject: TrackParity.Subject): List<Case> {
        val rng = TrackFixtures.Random(group.seed + index * 104729L)
        return TrackParityCases.runs(subject, rng, group.samples).map { TrackParity.judge(subject, it) }
    }

    /** Every call on every edit. Of each kind the shortest case keeps its answers, to be shown; the others keep what is counted. */
    val cases: List<Case> by lazy {
        val result = ArrayList<Case>()
        val shown = HashMap<String, Case>()
        fun keep(key: String, case: Case): Boolean {
            val best = shown[key]
            if (best != null && best.size <= case.size) return false
            shown[key] = case
            return true
        }
        for (group in groups) group.subjects.forEachIndexed { index, subject ->
            for (case in judged(group, index, subject)) {
                result += case
                if (case.verdict == Verdict.Identical) { case.drop(); continue }
                // The shortest call that shows a kind by itself is its example; so is the shortest of each unexplained signature.
                val keys = (if (case.kinds.size == 1 && case.unexplained.isEmpty()) case.kinds else emptyList()) + case.kinds.map { "$it, with others" } + listOfNotNull(leftover(case))
                if (keys.map { keep(it, case) }.none { it }) case.drop()
            }
        }
        val kept = shown.values.toSet()
        for (case in result) if (case !in kept) case.drop()
        result
    }

    /** The edits of the sample: a part of the edits that is the same every run, with the calls the whole suite makes on them. */
    private val sampled: Set<TrackParity.Subject> by lazy {
        groups.flatMapTo(HashSet()) { group -> group.subjects.filterIndexed { index, _ -> index % group.every == 0 } }
    }

    /** The calls on the edits of the sample alone, with what is counted of each. They are among [cases], call for call. */
    val sampleCases: List<Case> by lazy {
        groups.flatMap { group ->
            group.subjects.withIndex().filter { it.value in sampled }.flatMap { (index, subject) -> judged(group, index, subject).onEach { it.drop() } }
        }
    }

    /**
     * What the sample finds, as the section of the report the ordinary run
     * of the tests checks: the calls on its edits by verdict, and how many
     * show each kind. [cases] are the sample's own, or all, of which the
     * sample's are taken.
     */
    fun sample(cases: List<Case>): String = buildString {
        val found = cases.filter { it.subject in sampled }
        val subjects = found.map { it.subject }.distinct()
        appendLine("## The sample")
        appendLine()
        appendLine("What the tests check when nothing is set in the environment: the calls on ${subjects.size} of the edits (the ${subjects.count { it.small }} small ones, every ${ordinal(GOLDEN_EVERY)} step of the golden projects, every ${ordinal(GENERATED_EVERY)} seeded edit), the same calls the whole suite makes on them.")
        appendLine("${found.size} calls: ${found.count { it.verdict == Verdict.Identical }} identical, ${found.count { it.verdict == Verdict.Different }} different, ${found.count { it.verdict == Verdict.NotExpressible }} not expressible, ${found.count { it.unexplained.isNotEmpty() }} with something not explained.")
        appendLine()
        appendLine("| Kind | Calls in the sample |")
        appendLine("|---|---:|")
        for (kind in TrackParityKinds.all) {
            val count = found.count { kind.id in it.kinds }
            if (count > 0) appendLine("| ${kind.id} | $count |")
        }
    }

    private fun ordinal(number: Int) = "$number" + when { number % 100 in 11..13 -> "th"; number % 10 == 1 -> "st"; number % 10 == 2 -> "nd"; number % 10 == 3 -> "rd"; else -> "th" }

    private fun leftover(case: Case): String? = if (case.unexplained.isEmpty()) null else "${case.function}: ${case.unexplained.sorted().joinToString(", ")}"

    /** The calls whose answer through the track model is a timeline the invariants refuse. */
    fun refused(cases: List<Case>): List<String> = cases.mapNotNull { case -> case.refused?.let { "${case.function}(${case.label}) on ${case.subject.name}: $it" } }

    /**
     * What the gate does not let through: every kind that is not accepted with
     * the calls that show it, most first, then every leftover no kind explains.
     * Empty when every difference is of an accepted kind.
     */
    fun open(cases: List<Case>, accepted: Set<String>): List<String> {
        val different = cases.filter { it.verdict != Verdict.Identical }
        val kinds = different.flatMap { it.kinds }.filter { it !in accepted }.groupingBy { it }.eachCount()
        val leftovers = different.mapNotNull(::leftover).groupingBy { it }.eachCount()
        return kinds.entries.sortedByDescending { it.value }.map { "${it.value} × ${it.key}" } +
            leftovers.entries.sortedByDescending { it.value }.map { "${it.value} × not explained: ${it.key}" }
    }

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
        appendLine("# Parity of the editing functions on the track model with what they did before")
        appendLine()
        appendLine("Written by `TrackParityTest` (`core/src/test/kotlin/app/takes/core/TrackParity*.kt`); do not edit by hand.")
        appendLine()
        appendLine("## Running it")
        appendLine()
        appendLine("The suite is one test of the core tests: `./gradlew :core:test`, or by itself with `--tests app.takes.core.TrackParityTest`. What it does is chosen in the environment:")
        appendLine()
        appendLine("- **Nothing set.** It makes the calls of a sample of the edits, about a seventh of them and the same every run, and compares what it finds with the section [The sample](#the-sample) of this file. It fails when that section is not what it finds now, or when a function answers an edit whose timeline the invariants refuse. It does not fail on a difference that is written down here.")
        appendLine("- **`TAKES_PARITY_FULL=1`.** It makes every call and compares the whole of this file, examples included, in the same way. Run it before and after a change to the editing functions or to the track model.")
        appendLine("- **`TAKES_RECORD_PARITY=1`.** It makes every call and writes this file anew instead of comparing. Read what changed (`git diff docs/TRACKS-PARITY.md`) and commit the file with the change that caused it.")
        appendLine("- **`TAKES_PARITY_GATE=1`.** As `TAKES_PARITY_FULL=1`, and it also fails on every call that shows a kind of difference not listed in `TrackParityAccepted.kt`, or a difference no kind explains: the failure names each such kind with the number of calls that show it, then what is not explained. The list holds the kinds that `docs/TRACKS-PARITY-DECISIONS.md` decides as Unreachable or Invisible, and those adopted since (\"At S4\"); every other kind is one a function keeps today's result for, and must not appear.")
        appendLine()
        appendLine("## What is compared")
        appendLine()
        appendLine("Since stage S2-B of `docs/TRACKS-PLAN.md` the functions of `TimelineEditing` and `SubtitleEditing` that change an edit run on the track model: the edit is converted to a timeline, an operation of the track model is run, and what it changed is written back into the sequence (`TrackWrapping`). `RangeEditing` cuts, removes and joins clips through them. What each did before is kept in the tests, unchanged, as it was in release 0.8 (`LegacyTimelineEditing`, `LegacyRangeEditing`, `LegacySubtitleEditing`, `LegacyAudioUnlinking`).")
        appendLine("Every such function, and the questions among them, is called twice on the same edit with the same arguments: as it was (\"today\" below, the name the kinds were written with), and as it is now (\"through the track model\").")
        appendLine("Both answers are read as the conversion reads them (fits saved, settings that do not play dropped, sound tracks repacked), so only what plays differently counts.")
        appendLine("Ids made during the call, the track of a sound item, the order of a clip's bookmarks, and times less than three ticks (1/80000 s) apart are not differences. Where a sound is muted on both sides, its level, fades and noise reduction are compared as one thing: what it would sound like unmuted. The section [Saved bytes](#saved-bytes) counts the calls without any of these allowances.")
        appendLine()
        appendLine("- **identical**: the same edit and the same answer, or neither applies.")
        appendLine("- **different**: both give a sequence, and the two differ.")
        appendLine("- **not expressible**: the result is a valid timeline that no sequence of clips with two sound tracks plays. No function answers one while a project saves a sequence.")
        appendLine()
        val subjects = cases.map { it.subject }.distinct()
        appendLine("The edits: ${subjects.count { it.small }} small ones made for one situation each, ${subjects.count { it.name.startsWith("golden") }} steps of the histories of the golden projects (all but the long one), and ${subjects.count { it.name.startsWith("generated") }} made by ${TrackParityCases.SEQUENCES} seeded sequences of the functions as they were. ${cases.size} calls.")
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
        appendLine("Only the kinds that are found, in the order a user would notice them; the kinds that are kept as today are absent, and described in `TrackParityKinds.kt` and `docs/TRACKS-PARITY-DECISIONS.md`. A call can show several kinds; it is counted under each. \"Plan\" is the row of `docs/TRACKS-PLAN.md`, 2.4. The rule is where the original does what the track model does, as a path in its repository.")
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
        appendLine("Today the id of a bookmark and of a transition is unique within its clip; the track model needs each unique across the timeline (`TrackInvariants`, `DuplicateRelation`). Measured on every step of every golden project, the long one included, on the edits the seeded sequences made, and on every edit a function as it was answered in the suite, each converted to a timeline:")
        appendLine()
        appendLine("| Edits | Counted | With a bookmark id used twice | With a transition id used twice | Converted timelines the invariants refuse |")
        appendLine("|---|---:|---:|---:|---|")
        val golden = GoldenProjects.all.flatMap { golden -> golden.project.snapshots.map { it.state to golden.project.sources } }
        appendLine("| Golden projects, every step | ${identities(golden)} |")
        appendLine("| Seeded sequences | ${identities(TrackParityCases.generated.map { it.state to it.sources })} |")
        val answered = cases.filter { it.todayAnswered }
        val broken = answered.mapNotNull { it.todayRefused }.groupingBy { it }.eachCount()
        appendLine("| Answers of the functions as they were, in the suite | ${answered.size} | – | – | ${if (broken.isEmpty()) "none" else broken.entries.joinToString(", ") { "${it.value} for `${it.key}`" }} |")
        appendLine()
        appendLine("## Timelines of the answers")
        appendLine()
        appendLine("Of the ${cases.count { it.trackAnswered }} edits the functions answered in the suite, each converted to a timeline, the invariants (`TrackInvariants.violation`) refuse ${cases.count { it.refused != null }}.")
        appendLine()
        appendLine("## Saved bytes")
        appendLine()
        val both = cases.filter { it.sameBytes != null }
        appendLine("In ${both.size} calls both sides answer an edit. In ${both.count { it.sameBytes == true }} of them the two edits are the same JSON, byte for byte, once the ids made during the call are renamed in the order they appear: nothing is read through the conversion and no time is rounded. The others, by function:")
        appendLine()
        appendLine("| Function | Both answer | Not the same bytes | Of which identical as compared above |")
        appendLine("|---|---:|---:|---:|")
        for ((function, found) in both.groupBy { it.function }) {
            val apart = found.filter { it.sameBytes == false }
            if (apart.isNotEmpty()) appendLine("| `$function` | ${found.size} | ${apart.size} | ${apart.count { it.verdict == Verdict.Identical }} |")
        }
        appendLine()
        append(sample(cases))
        appendLine()
        appendLine("## Not compared")
        appendLine()
        appendLine("- `TimelineEditing.removeIntervals`, the functions of `RangeEditing` and those of `AudioUnlinking` keep the bodies they had (`RangeEditing` over the functions that changed); they are compared all the same.")
        appendLine("- `UnusedTakes.add` is `TimelineEditing.insert` with whole takes, then a search for the first inserted clip by its id; `insert` keeps the ids it is given.")
        appendLine("- `TimelineEditing.layout`, `fitted`, `join`, `transitions`, `entering`, `leaving`, `locate`, `chains`, `overlap`, `joinMaximum`, `joinSettings`, `transition`, `titlesAt`, `titleRange`, `clipRange`, `newTitleRange`, `soundLevel`, `audioAt`, `nextBookmark`, `previousBookmark`, `bookmarkAt`, `trimmed`; `RangeEditing.quantize`, `snap`, `markTime`, `interval`; `SubtitleEditing.active`, `fingerprint`; `AudioUnlinking.detachedSound`, `isDetached`, `clipIndex`, `refusal`, `matches`, `valid`: questions about a sequence that are asked of the sequence form, or whose answer is pinned by the render plan of the golden projects.")
        appendLine("- What is drawn and heard is not compared here: that is the render plan (`RenderPlan`), compared when the renderer reads the timeline.")
    }
}
