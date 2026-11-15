package app.takes.core

import app.takes.core.RenderPlan.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * What the export took from the clips before it took it from a [RenderPlan]
 * (docs/TRACKS-PLAN.md, S3a), kept here as it was written in the app, and
 * the plan held to it: the decks of every section, the sequences of sound
 * and the levels, for every step of every golden project and the fixtures of
 * the track timeline.
 */
class RenderPlanExportTest {
    private val edits: List<Pair<String, EditState>> by lazy {
        TrackFixtures.all.map { (name, state) -> "fixture $name" to state } +
            GoldenProjects.all.filter { !it.large }.flatMap { golden -> golden.project.history.mapIndexed { index, state -> "golden ${golden.name}, step $index" to state } }
    }

    // MARK: The export as it decided by itself

    private val margin = 0.5
    private val shortest = 0.000002

    private fun cutMicros(seconds: Double): Long = floor(seconds * 1e6).toLong()

    private fun freezes(clips: List<TimelineClip>): List<Pair<Double, Double>> = TimelineEditing.layout(clips).let { layout ->
        layout.all.filter { it.transition != null && it.outgoing != null && TransitionLayout.longEnough(it.timing) }.mapNotNull { join ->
            val placement = layout.placements[join.outgoing!!]
            TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, TransitionScene.FRAMES_PER_SECOND)?.let { it.shown to it.until }
        }
    }

    private fun lead(clips: List<TimelineClip>, first: Int, rate: Int): Int {
        val begins = first.toDouble() / rate
        val held = freezes(clips).filter { (shown, until) -> shown - margin < begins && begins < until + margin }.minOfOrNull { it.first }
        val from = minOf(begins, held ?: begins) - margin
        return maxOf(0, minOf(first, floor(from * rate).toInt()))
    }

    /** One item of a deck: a black still of some microseconds, or a clip cut in its footage. */
    private data class Item(val black: Long?, val clip: Int?, val startsUs: Long, val endsUs: Long, val begins: Double)

    private class Drawn(val opens: Int, val closes: Int, val from: Double, val decks: List<List<Item>>)

    private fun picture(clips: List<TimelineClip>, section: ExportSections.Section, rate: Int): Drawn {
        val chains = TimelineEditing.chains(clips)
        val decks = minOf(chains.size, 2)
        val total = TimelineEditing.duration(clips)
        val frames = ExportSections.pictures(total, rate)
        val first = (section.startTicks / ExportSections.frameTicks(rate)).toInt()
        val count = ExportSections.pictures(section.lengthTicks, rate)
        val opens = lead(clips, first, rate)
        val closes = minOf(frames, first + count + ceil(margin * rate).toInt())
        val from = opens.toDouble() / rate
        val to = if (closes == frames) total else closes.toDouble() / rate
        val sequences = List(decks) { mutableListOf<Item>() }
        val filled = DoubleArray(decks) { from }
        fun gap(deck: Int, until: Double) {
            val micros = ((until - filled[deck]) * 1e6).roundToLong()
            if (micros > 0) sequences[deck] += Item(micros, null, 0, 0, 0.0)
            filled[deck] = maxOf(filled[deck], until)
        }
        chains.forEachIndexed { number, chain ->
            val deck = number % decks
            for (index in chain.first..chain.last) {
                val begins = TimelineEditing.clipStart(clips, index)
                val ends = begins + clips[index].duration
                val starts = maxOf(begins, from)
                val stops = minOf(ends, to)
                if (stops - starts < shortest) continue
                gap(deck, starts)
                val clip = clips[index]
                val startsUs = if (starts <= begins) cutMicros(clip.start) else cutMicros(clip.start + (starts - begins) * clip.rate)
                val endsUs = if (stops >= begins + clip.duration) cutMicros(clip.end) else cutMicros(clip.start + (stops - begins) * clip.rate)
                sequences[deck] += Item(null, index, startsUs, maxOf(startsUs + 1, endsUs), begins - from)
                filled[deck] = stops
            }
        }
        for (deck in 0 until decks) gap(deck, to)
        return Drawn(opens, closes, from, sequences)
    }

    /** One item of a sequence of sound: silence, a sound of a track, or the sound of a clip, with the envelope it follows and where it begins. */
    private data class Heard(val gap: Long?, val item: AudioItem?, val clip: Int?, val startsUs: Long, val endsUs: Long, val envelope: AudioEnvelopeSegment?, val begins: Double)

    private fun sound(state: EditState, clean: Set<Int>, voiced: (String) -> Boolean): List<List<Heard>> {
        val clips = state.clips
        val sounds = state.audio.orEmpty()
        val total = TimelineEditing.duration(clips)
        val chains = TimelineEditing.chains(clips)
        fun sequence(fill: (MutableList<Heard>, gap: (Double) -> Unit, filled: (Double) -> Unit) -> Unit): List<Heard> {
            val sequence = mutableListOf<Heard>()
            var filled = 0.0
            val gap = { until: Double ->
                val micros = ((until - filled) * 1e6).roundToLong()
                if (micros > 0) sequence += Heard(micros, null, null, 0, 0, null, 0.0)
            }
            fill(sequence, gap) { filled = it }
            gap(total)
            return sequence
        }
        val soundtracks = (0 until AudioItem.TRACKS).mapNotNull { track ->
            val items = sounds.filter { it.track == track && it.start < total - 0.01 }.sortedBy { it.start }
            if (items.isEmpty()) return@mapNotNull null
            sequence { sequence, gap, filled ->
                for (item in items) {
                    gap(item.start)
                    val seconds = minOf(item.end, total) - item.start
                    sequence += Heard(null, item, null, (item.sourceStart * 1e6).roundToLong(), ((item.sourceStart + seconds) * 1e6).roundToLong(), SoundEnvelope.item(sounds, item, total), item.start)
                    filled(item.start + seconds)
                }
            }
        }
        val heard = clips.indices.filter { it in clean || voiced(clips[it].sourceID) }
        val voices = (0..1).mapNotNull { parity ->
            val indices = heard.filter { index -> chains.indexOfFirst { index in it.first..it.last } % 2 == parity }
            if (indices.isEmpty()) return@mapNotNull null
            sequence { sequence, gap, filled ->
                for (index in indices) {
                    val clip = clips[index]
                    val begins = TimelineEditing.clipStart(clips, index)
                    gap(begins)
                    sequence += Heard(null, null, index, cutMicros(clip.start), cutMicros(clip.end), SoundEnvelope.clip(clips, index), begins)
                    filled(begins + clip.duration)
                }
            }
        }
        return soundtracks + voices
    }

    // MARK: The plan, in the same words

    private fun items(plan: RenderPlan, stretch: RenderPlan.Stretch): List<List<Item>> = stretch.decks.map { deck ->
        deck.map { part ->
            when (part) {
                is RenderPlan.Part.Black -> Item(part.micros, null, 0, 0, 0.0)
                is RenderPlan.Part.Footage -> Item(null, part.index, part.sourceStartUs, part.sourceEndUs, plan.pictures[part.index].start - stretch.from)
            }
        }
    }

    private fun heard(plan: RenderPlan, voiced: (String) -> Boolean): List<List<Heard>> = (plan.soundtracks + plan.voices(voiced)).map { sequence ->
        sequence.entries.map { entry ->
            when (entry) {
                is Entry.Gap -> Heard(entry.micros, null, null, 0, 0, null, 0.0)
                is Entry.Sound -> Heard(null, entry.item, null, entry.sourceStartUs, entry.sourceEndUs, plan.soundEnvelope(entry.item), entry.start)
                is Entry.Clip -> plan.pictures[entry.index].let { Heard(null, null, it.index, it.sourceStartUs, it.sourceEndUs, plan.clipEnvelope(it.index), it.start) }
            }
        }
    }

    // MARK: The tests

    @Test fun theDecksOfEverySectionAreThoseTheExportMadeOfTheClips() {
        var sections = 0
        for ((name, state) in edits) {
            if (state.clips.isEmpty()) continue
            val plan = TrackRenderPlan.of(state)
            assertEquals(name, minOf(TimelineEditing.chains(state.clips).size, 2), plan.sequences.size)
            assertEquals(name, TimelineEditing.duration(state.clips), plan.duration, 0.0)
            for (rate in listOf(24, 25, 30, 50, 60)) for (section in ExportSections.sections(plan.duration)) {
                val was = picture(state.clips, section, rate)
                val first = (section.startTicks / ExportSections.frameTicks(rate)).toInt()
                val now = plan.section(first, ExportSections.pictures(section.lengthTicks, rate), rate)
                assertEquals("$name at $rate, section ${section.index}", was.opens to was.closes, now.opens to now.closes)
                assertEquals("$name at $rate, section ${section.index}", was.from, now.stretch.from, 0.0)
                assertEquals("$name at $rate, section ${section.index}", was.decks, items(plan, now.stretch))
                sections++
            }
        }
        println("The export's decks: $sections sections of ${edits.size} edits, each as the export made it of the clips")
        assertTrue(sections > 1000)
    }

    @Test fun aLongMovieIsCutIntoTheSameSectionsWhereverTheyBegin() {
        // Golden movies are short; the same edits played four times over have sections that begin inside clips, transitions and freezes.
        var sections = 0
        for ((name, state) in edits) {
            if (state.clips.size !in 2..40) continue
            val long = EditState((0 until 4).flatMap { round -> state.clips.map { it.copy(id = "${it.id}-$round", transition = it.transition?.let { join -> join.copy(nextClipID = join.nextClipID?.let { next -> "$next-$round" }) }) } })
            val plan = RenderPlan.of(long)
            for (section in ExportSections.sections(plan.duration)) {
                val was = picture(long.clips, section, 30)
                val now = plan.section((section.startTicks / ExportSections.frameTicks(30)).toInt(), ExportSections.pictures(section.lengthTicks, 30), 30)
                assertEquals("$name, section ${section.index}", was.opens to was.closes, now.opens to now.closes)
                assertEquals("$name, section ${section.index}", was.decks, items(plan, now.stretch))
                sections++
            }
        }
        assertTrue(sections > 500)
    }

    @Test fun theSoundIsOnTheSequencesTheExportMadeOfTheClipsWithTheSameLevels() {
        var sequences = 0
        for ((name, state) in edits) {
            val asking = state.clips.indices.filter { (state.clips[it].audio?.denoise ?: 0.0) > 0 }.toSet()
            val silent = state.clips.map { it.sourceID }.distinct().filterIndexed { index, _ -> index % 3 == 1 }.toSet()
            for (clean in listOf(asking, emptySet(), asking.drop(1).toSet())) for (voiced in listOf<(String) -> Boolean>({ true }, { it !in silent }, { false })) {
                val plan = TrackRenderPlan.of(state, clean)
                val was = sound(state, clean, voiced)
                assertEquals("$name, cleaned $clean", was, heard(plan, voiced))
                sequences += was.size
            }
            assertEquals(name, SoundEnvelope.segments(state), TrackRenderPlan.of(state).envelopes())
        }
        println("The export's sound: $sequences sequences, each as the export made it of the clips")
        assertTrue(sequences > 1000)
    }

    @Test fun whatIsWrittenAndKeptIsWhatTheExportTookFromTheClips() {
        for ((name, state) in edits) {
            val clips = state.clips
            val plan = TrackRenderPlan.of(state)
            // The stills: the frame each holds, on its deck, and the end of every chain.
            val layout = TimelineEditing.layout(clips)
            val deckOf = TransitionScene.decks(clips)
            val kept = layout.all.filter { it.transition != null && it.outgoing != null && TransitionLayout.longEnough(it.timing) }.mapNotNull { join ->
                val placement = layout.placements[join.outgoing!!]
                TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, TransitionScene.FRAMES_PER_SECOND)
                    ?.let { listOf(it.from, deckOf[placement.index].toDouble(), it.until, it.shown) }
            }
            assertEquals(name, kept, plan.freezes.map { listOf(it.from, it.deck.toDouble(), it.until, it.shown) })
            val decks = maxOf(1, plan.sequences.size)
            assertEquals(name, TimelineEditing.chains(clips).mapIndexed { index, chain -> chain.end to index % decks }, plan.endings.map { it.at to it.deck % decks })
            // Titles and captions.
            assertEquals(name, clips.any { !it.texts.isNullOrEmpty() }, plan.titled)
            val captions = SubtitleEditing.cues(state)
            assertEquals(name, captions, plan.captions)
            assertEquals(name, state.subtitles?.takeIf { captions.isNotEmpty() }, plan.subtitles)
            val ranges = TitleMotionRanges(clips)
            for (step in 0..40) {
                val time = plan.duration * step / 40
                assertEquals(name, ranges.shown(clips, time) { 50.0 }, plan.titlesShown(time) { 50.0 })
                assertEquals(name, TransitionScene.moment(clips, time, Size(1080.0, 1920.0), deckOf) { 50.0 }, plan.moment(time, Size(1080.0, 1920.0)) { 50.0 })
            }
        }
    }
}
