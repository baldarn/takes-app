package app.takes.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * The gate of the mix plan (docs/TRACKS-PLAN.md, S5): for every timeline that
 * is a sequence, the plan plays the sounds today's export plays, cut where it
 * cuts them, at the level it gives every sample ([RenderPlan.voices],
 * [RenderPlan.soundtracks], [RenderPlan.clipEnvelope],
 * [RenderPlan.soundEnvelope], [RenderPlan.envelopes]).
 *
 * The timeline keeps its places in ticks, which are the layout's seconds
 * rounded, so times agree to within a tick and levels to what a tick moves
 * them by. One difference is the stage's purpose and is allowed: a sound
 * that reaches past the last picture plays on to its end (N6), where today
 * it is cut off with the picture, so it is compared only up to there.
 * Another is a fix: sound unlinked from a clip carries on into the sound of
 * the clip after it without a dip, when it picks it up where it left off,
 * and so does a sound item into the piece a split left of it.
 */
class TrackMixGateTest {
    private val full = listOf("TAKES_PARITY_GATE", "TAKES_PARITY_FULL", "TAKES_RECORD_PARITY").any { System.getenv(it) == "1" }
    private val tick = 1.0 / EditTime.SCALE
    private val time = 2.5 * tick
    private val level = 2e-3

    private val edits: List<Pair<String, EditState>> by lazy {
        TrackFixtures.all.map { (name, state) -> "fixture $name" to state } +
            (TrackParityCases.probes + TrackParityCases.generated).map { it.name to it.state } +
            GoldenProjects.all.flatMap { golden -> golden.project.history.mapIndexed { index, state -> "golden ${golden.name}, step $index" to state } }
    }

    /** What today's export plays of one sound: its run of sound, its footage, and where the movie cuts it off. */
    private class Heard(var segment: AudioEnvelopeSegment, val clip: Boolean, val sourceStart: Double, val sourceEnd: Double, val rate: Double, val cutAt: Double?)

    private fun near(a: Double?, b: Double?, within: Double) = if (a == null || b == null) a == b else abs(a - b) <= within
    private fun near(a: ClosedFloatingPointRange<Double>?, b: ClosedFloatingPointRange<Double>?) =
        if (a == null || b == null) a == b else near(a.start, b.start, time) && near(a.endInclusive, b.endInclusive, time)

    /** The differences between today's export of [state] and the mix plan of its timeline; empty when they agree. */
    private fun differences(state: EditState): List<String> {
        val today = RenderPlan.of(state)
        val timeline = TrackWrapping.timeline(state)
        val plan = TrackMix.plan(timeline)
        val problems = ArrayList<String>()

        // Today's owners as the timeline names them (TrackConversion): a clip's sound is its counterpart; a sound item
        // keeps its id, or takes an alias when a clip has it, or is the counterpart of the muted clip it was unlinked from.
        val clipIDs = state.clips.associateBy { it.id }
        fun clipOwner(id: String) = TrackTimeline.counterpart(id)
        fun itemOwner(item: AudioItem): String {
            val clip = clipIDs[item.id] ?: return item.id
            return if (clip.audio?.muted == true && clip.sourceID == item.sourceID) TrackTimeline.counterpart(item.id) else TrackTimeline.alias(item.id)
        }
        val itemsByID = state.audio.orEmpty().associateBy { it.id }
        val denoised = state.clips.filter { it.audio?.muted != true && (it.audio?.denoise ?: 0.0) > 0 }.map { clipOwner(it.id) }.toSet()

        val expected = LinkedHashMap<String, Heard>()
        state.clips.indices.forEach { index ->
            val segment = today.clipEnvelope(index) ?: return@forEach
            val picture = today.pictures[index]
            val mapped = segment.copy(owner = clipOwner(segment.owner!!), initialOwner = segment.initialOwner?.let(::clipOwner))
            expected[mapped.owner!!] = Heard(mapped, true, picture.sourceStart, picture.sourceEnd, picture.rate, null)
        }
        for (item in state.audio.orEmpty()) {
            if (item.start >= today.duration - RenderPlan.SOUND_MARGIN) continue
            val segment = today.soundEnvelope(item) ?: continue
            val mapped = segment.copy(owner = itemOwner(item), initialOwner = segment.initialOwner?.let { itemsByID[it]?.let(::itemOwner) })
            expected[mapped.owner!!] = Heard(mapped, false, item.sourceStart, item.sourceEnd, 1.0, today.duration.takeIf { item.end > it + time })
        }
        // What the movie plays past its picture today is not played at all: those sounds start too late.
        val late = state.audio.orEmpty().filter { it.start >= today.duration - RenderPlan.SOUND_MARGIN }.map(::itemOwner).toSet()

        for (lane in plan.lanes) for ((a, b) in lane.pieces.zipWithNext()) if (a.end > b.start) problems += "lane ${lane.index} overlaps"
        val pieces = plan.pieces.groupBy { it.owner }
        for (owner in pieces.keys - expected.keys - late) problems += "plays $owner, which today's export does not"
        for (owner in expected.keys - pieces.keys) problems += "does not play $owner"

        val longest = maxOf(today.duration, state.audio.orEmpty().maxOfOrNull { it.end } ?: 0.0)
        if (!near(plan.duration.seconds, longest, time) && plan.duration.seconds < longest) problems += "lasts ${plan.duration.seconds}, not $longest"

        // Sound unlinked from a clip and the sound of the clip after it, when one carries the other on, are a sound item
        // and a clip's sound today, which never continue each other; on lanes they continue, as in the original. So do
        // the two pieces a split leaves of a sound item (since S5, split-cuts-sound-items): "Splitting continuous
        // independent audio introduces no fade or volume dip" (Tests/TrackMediaTests.swift:362).
        for (piece in plan.pieces) {
            if (!piece.continues) continue
            val prior = plan.lanes[piece.lane].pieces.let { it[it.indexOf(piece) - 1] }
            val before = expected[prior.owner] ?: continue
            val after = expected[piece.owner] ?: continue
            if (before.clip && after.clip) continue
            before.segment = before.segment.copy(fadeOut = 0.0)
            after.segment = after.segment.copy(fadeIn = 0.0, initialOwner = prior.owner, initialVolume = before.segment.volume)
        }

        for ((owner, heard) in expected) {
            val mine = pieces[owner] ?: continue
            val piece = mine.singleOrNull() ?: run { problems += "$owner in ${mine.size} pieces"; return@run null } ?: continue
            val a = piece.envelope
            val b = heard.segment
            fun check(what: String, same: Boolean) { if (!same) problems += "$owner: $what" }
            check("start ${a.start} for ${b.start}", near(a.start, b.start, time))
            check("end ${a.end} for ${b.end}", near(a.end, b.end, time))
            check("fade in ${a.fadeIn} for ${b.fadeIn}", a.fadeIn == b.fadeIn)
            check("fade out ${a.fadeOut} for ${b.fadeOut}", a.fadeOut == b.fadeOut)
            check("continues ${a.initialOwner} for ${b.initialOwner}", a.initialOwner == b.initialOwner)
            check("volume", near(a.volume, b.volume, 1e-12) && near(a.initialVolume, b.initialVolume, 1e-12))
            check("fades ${a.userFadeIn} ${a.userFadeOut} for ${b.userFadeIn} ${b.userFadeOut}", a.userFadeIn == b.userFadeIn && a.userFadeOut == b.userFadeOut)
            check("ramps ${a.inRamp} ${a.outRamp} for ${b.inRamp} ${b.outRamp}", near(a.inRamp, b.inRamp) && near(a.outRamp, b.outRamp))
            check("item ${a.clipStart}…${a.clipEnd} for ${b.clipStart}…${b.clipEnd}", near(a.clipStart, b.clipStart, time) && near(a.clipEnd, b.clipEnd, time))
            check("source ${piece.runs.first().sourceStart}…${piece.runs.last().sourceEnd} for ${heard.sourceStart}…${heard.sourceEnd}",
                near(piece.runs.first().sourceStart, heard.sourceStart, 1e-9) && near(piece.runs.last().sourceEnd, heard.sourceEnd, 1e-9))
            check("rate ${piece.runs.single().rate} for ${heard.rate}", abs(piece.runs.single().rate - heard.rate) * (b.end - b.start) <= 2 * time * maxOf(1.0, heard.rate))
            check("chain", piece.chain.isNotEmpty() == owner in denoised)

            // The level, sample by sample, away from the ends where a tick decides which side a sample falls on.
            val until = (heard.cutAt?.let { it - AudioLevels.CUT_FADE - time } ?: b.end) - 2 * time
            val from = b.start + 2 * time
            if (until <= from) continue
            val times = (0..64).map { from + (until - from) * it / 64 } +
                (0..40).flatMap { listOf(from + it * 0.0005, until - it * 0.0005) }.filter { it in from..until }
            val worst = times.maxOf { abs(a.gain(it) - b.gain(it)) }
            check("level off by $worst", worst <= level)
        }
        return problems
    }

    private fun assertAlike(name: String, state: EditState) {
        val problems = differences(state)
        if (problems.isNotEmpty()) fail("$name:\n  " + problems.take(12).joinToString("\n  "))
    }

    @Test fun theMixPlanOfEverySequencePlaysWhatTodaysExportPlays() {
        var compared = 0
        for ((name, state) in edits) { assertAlike(name, state); compared++ }
        println("The mix plan: $compared edits, each mixed both ways and alike")
        assertTrue(compared > 100)
    }

    /** The edits a person reaches in one more step from every edit of the parity suite; a sample of them unless the gate runs. */
    @Test fun theMixPlanOfWhatEveryEditingFunctionAnswersPlaysWhatTodaysExportPlays() {
        class Group(val subjects: List<TrackParity.Subject>, val samples: Int, val seed: Long, val every: Int)
        val groups = listOf(
            Group(TrackParityCases.probes, 3, 11, 1),
            Group(TrackParityCases.golden, 2, 23, TrackParityReport.GOLDEN_EVERY),
            Group(TrackParityCases.generated, 1, 37, TrackParityReport.GENERATED_EVERY),
        )
        val seen = HashSet<EditState>()
        for (group in groups) group.subjects.forEachIndexed { index, subject ->
            if (!full && index % group.every != 0) return@forEachIndexed
            val rng = TrackFixtures.Random(group.seed + index * 104729L)
            for (run in TrackParityCases.runs(subject, rng, group.samples)) {
                // An edit of pictures over one another or apart (since S4: a handle trim outwards, a range in a transition) has no sequence to compare with.
                val state = run.track()?.state?.takeIf { it.isSequence } ?: continue
                if (seen.add(state)) assertAlike("${subject.name}: ${run.function} ${run.label}", state)
            }
        }
        println("The mix plan: ${seen.size} edits the editing functions answer, each mixed both ways and alike")
        assertTrue(seen.size > 100)
    }
}
