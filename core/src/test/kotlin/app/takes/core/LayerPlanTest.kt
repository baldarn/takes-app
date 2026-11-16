package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * The plan of picture layers ([LayerPlan]): for a timeline that is a
 * sequence, the movie [RenderPlan] describes (the equality gate); for one
 * whose pictures play over one another, the original's compositor order.
 */
class LayerPlanTest {
    private val tick = TransitionLayout.TICK
    private val full = listOf("TAKES_PARITY_GATE", "TAKES_PARITY_FULL", "TAKES_RECORD_PARITY").any { System.getenv(it) == "1" }

    private val edits: List<Pair<String, EditState>> by lazy {
        TrackFixtures.all.map { (name, state) -> "fixture $name" to state } +
            (TrackParityCases.probes + TrackParityCases.generated).map { it.name to it.state } +
            GoldenProjects.all.filter { !it.large }.flatMap { golden -> golden.project.history.mapIndexed { index, state -> "golden ${golden.name}, step $index" to state } }
    }

    // MARK: The same movie as the plan of the sequence

    private fun near(message: String, expected: Double, actual: Double, within: Double) {
        if (abs(expected - actual) > within) fail("$message: expected $expected, was $actual")
    }

    private fun sameSource(message: String, today: TransitionScene.Source, made: TransitionScene.Source, plan: LayerPlan, render: RenderPlan) {
        when (today) {
            is TransitionScene.Source.Clip -> {
                val other = made as? TransitionScene.Source.Clip ?: return fail("$message: $made in place of $today")
                assertEquals(message, render.pictures[today.index].id, plan.pictures[other.index].id)
                near("$message: offset", today.offset, other.offset, 2 * tick)
            }
            is TransitionScene.Source.Held -> {
                val other = made as? TransitionScene.Source.Held ?: return fail("$message: $made in place of $today")
                assertEquals(message, render.pictures[today.index].id, plan.pictures[other.index].id)
                near("$message: held offset", today.offset, other.offset, 2 * tick)
                near("$message: held from", today.from, other.from, 2 * tick)
            }
            is TransitionScene.Source.Fill -> assertEquals(message, today, made)
        }
    }

    private fun sameFrame(message: String, today: TransitionFrame, made: TransitionFrame) {
        assertEquals(message, today::class, made::class)
        if (today == made) return
        // A frame is the progress at the moment, which a tick moves by a tick over the transition's length.
        val numbers = { frame: TransitionFrame -> frame.toString().split(Regex("[^0-9.eE-]+")).mapNotNull { it.toDoubleOrNull() } }
        val a = numbers(today)
        val b = numbers(made)
        assertEquals(message, a.size, b.size)
        a.indices.forEach { near("$message: $today against $made", a[it], b[it], 1e-3 * maxOf(1.0, abs(a[it]))) }
    }

    /** The picture of [state] as a plan of layers is the picture of today's plan of it. */
    private fun assertSameMovie(name: String, state: EditState, moments: Boolean = true, sections: Boolean = true) {
        val render = TrackRenderPlan.of(state)
        val timeline = state.toTimeline()
        val plan = LayerPlan.planned(timeline, RenderPlan.DECKS)
            ?: return fail("$name: refused on two lanes, ${LayerPlan.of(timeline, RenderPlan.DECKS)}; ${timeline.video.map { listOf(it.id, it.start.ticks, it.end.ticks) }}; today ${render.pictures.map { listOf(it.id, it.deck, it.start, it.end) }}")

        // The same pictures at the same times, with the same footage.
        assertEquals(name, render.pictures.map { it.id }, plan.pictures.map { it.id })
        render.pictures.forEachIndexed { index, today ->
            val made = plan.pictures[index]
            near("$name: picture $index begins", today.start, made.start, tick / 2 + 1e-9)
            near("$name: picture $index ends", today.end, made.end, 2 * tick)
            assertEquals(name, listOf(today.sourceStartUs, today.sourceEndUs), listOf(made.sourceStartUs, made.sourceEndUs))
            assertEquals(name, today.rate, made.rate, 0.0)
            assertEquals(name, today.color, made.color)
            assertEquals(name, today.crop, made.crop)
        }
        near("$name: duration", render.duration, plan.duration, 2 * tick)
        // Lanes: no more than today's decks, and never two pictures on one at once.
        assertTrue("$name: ${plan.lanes} lanes for ${render.sequences.size} sequences", plan.lanes <= maxOf(1, render.sequences.size))
        for (lane in 0 until plan.lanes) {
            val own = plan.pictures.filter { it.lane == lane }.sortedBy { it.interval.start }
            own.zipWithNext().forEach { (a, b) -> assertTrue("$name: lane $lane holds ${a.id} and ${b.id} at once", a.occupied.ticks <= b.interval.start.ticks + LayerPlan.MEETING_TICKS) }
        }
        assertEquals(name, plan.lanes, plan.sequences.size)

        // The same transitions, each between the same two pictures at the same factor.
        fun ids(index: Int?) = index?.let { render.pictures[it].id }
        assertEquals(
            name,
            render.transitions.map { listOf(it.transition!!.id, it.factor, ids(it.outgoing), ids(it.incoming)) }.sortedBy { it[0] as String },
            plan.transitions.map { listOf(it.id, it.factor, it.outgoing, it.incoming) }.sortedBy { it[0] as String },
        )
        // The same freezes.
        val frozen = render.freezes.sortedBy { it.from }
        assertEquals(name, frozen.map { render.pictures[it.clip].id }, plan.freezes.map { plan.pictures[it.picture].id })
        frozen.zip(plan.freezes).forEach { (today, made) ->
            near("$name: freeze from", today.from, made.from, 2 * tick)
            near("$name: freeze until", today.until, made.until, 2 * tick)
            near("$name: freeze shown", today.shown, made.shown, 2 * tick)
            near("$name: freeze offset", today.offset, made.offset, 2 * tick)
            near("$name: freeze source", today.sourceTime, made.sourceTime, 2 * tick * 4)
            assertEquals(name, today.sounding, made.sounding)
        }
        // The same endings: every chain ends where a lane's run does. A lane's run may also end where the next picture begins on another lane.
        val lanes = "today ${render.endings}, lanes ${plan.endings}, pictures ${plan.pictures.map { listOf(it.lane, it.start, it.end, it.occupied.seconds) }}, decks ${render.pictures.map { listOf(it.deck, it.start, it.end) }}"
        // A chain may end where the next on its deck begins straight on, which on a lane is one run.
        for (ending in render.endings) {
            assertTrue(
                "$name: ending at ${ending.at}; $lanes",
                plan.endings.any { abs(it.at - ending.at) <= 2 * tick } || plan.pictures.any { abs(it.start - ending.at) <= 2 * tick },
            )
        }
        for (ending in plan.endings) {
            assertTrue(
                "$name: lane ${ending.deck} ends at ${ending.at}; today ${render.endings}, lanes ${plan.endings}, pictures ${plan.pictures.map { listOf(it.lane, it.start, it.end, it.occupied.seconds) }}",
                render.endings.any { abs(it.at - ending.at) <= 2 * tick } || plan.pictures.any { it.lane != ending.deck && abs(it.start - ending.at) <= 2 * tick },
            )
        }
        // The same titles and captions.
        assertEquals(name, render.titles.map { render.pictures[it.clip].id to it.title }, plan.titles.map { plan.pictures[it.clip].id to it.title })
        render.titles.zip(plan.titles).forEach { (today, made) ->
            near("$name: title begins", today.start, made.start, 2 * tick)
            near("$name: title ends", today.end, made.end, 2 * tick)
        }
        // Today's plan captions only the sound that follows a picture; a sound unlinked from its picture keeps its words for S8.
        val followed = timeline.audio.filter { it.followsVideo != null }.map { it.id }.toSet()
        val spoken = plan.captions.filter { it.audioID in followed }
        assertEquals(name, render.subtitles == null, spoken.isEmpty())
        if (render.subtitles != null) assertEquals(name, render.subtitles?.copy(timingFingerprint = ""), plan.subtitles?.copy(timingFingerprint = ""))
        // Cues that begin within a tick of each other may come in either order.
        val byStart = compareBy<SubtitleCue>({ Math.round(it.start * 1e5) }, { it.text })
        assertEquals(name, render.captions.sortedWith(byStart).map { it.text }, spoken.sortedWith(byStart).map { it.text })
        render.captions.sortedWith(byStart).zip(spoken.sortedWith(byStart)).forEach { (today, made) ->
            near("$name: caption begins", today.start, made.start, 2 * tick)
            near("$name: caption ends", today.end, made.end, 2 * tick)
        }

        if (moments) for (stretch in plan.stretches) {
            // The middle of a stretch is away from every edge by more than the ticks the two clocks differ by.
            if (stretch.interval.duration.ticks < 12) continue
            val time = (stretch.from + stretch.to) / 2
            val today = render.moment(time)
            val made = plan.moment(time)
            val at = "$name at $time"
            val showing = made.layers.flatMap { layer ->
                when (layer) {
                    is LayerPlan.Layer.Whole -> listOf(layer.source)
                    is LayerPlan.Layer.Mixed -> layer.sources
                }
            }.filter { it !is TransitionScene.Source.Fill && plan.pictures[index(it)].let { p -> time >= p.start && time < p.end || it is TransitionScene.Source.Held } }
            assertEquals("$at: a lane each", showing.size, showing.map { lane(it) }.toSet().size)
            val join = today.join
            if (join == null) {
                if (today.first is TransitionScene.Source.Fill) {
                    assertEquals("$at: black", emptyList<LayerPlan.Layer>(), made.layers)
                } else {
                    val layer = made.layers.singleOrNull() as? LayerPlan.Layer.Whole ?: return fail("$at: ${made.layers} in place of one picture")
                    sameSource(at, today.first, layer.source, plan, render)
                    assertNull(at, layer.window)
                }
            } else {
                val layer = made.layers.singleOrNull() as? LayerPlan.Layer.Mixed ?: return fail("$at: ${made.layers} in place of a transition")
                assertEquals(at, join.transition!!.id, layer.transition.id)
                assertEquals(at, join.factor, layer.transition.factor, 0.0)
                sameSource("$at: outgoing", today.first, layer.first, plan, render)
                sameSource("$at: incoming", today.second, layer.second, plan, render)
                sameFrame(at, today.frame, layer.frame)
                near("$at: first share", today.firstShare, layer.firstShare, 1e-3)
                near("$at: second share", today.secondShare, layer.secondShare, 1e-3)
            }
            // Likewise a title, which begins and ends at a second of its footage.
            if (render.titles.none { abs(it.start - time) < 1e-5 || abs(it.end - time) < 1e-5 }) {
                val titles = render.titlesAt(time)
                assertEquals("$at: titles", titles.map { it.first }, made.titles.map { it.first })
                titles.zip(made.titles).forEach { (a, b) -> near("$at: title share", a.second, b.second, 1e-3) }
            }
            // A caption begins and ends to within a tick of today's, so not at a moment that close to its edge.
            if (render.captions.none { abs(it.start - time) < 1e-5 || abs(it.end - time) < 1e-5 }) assertEquals("$at: captions", render.captionsAt(time).map { it.text }.sorted(), SubtitleEditing.active(spoken, time).map { it.text }.sorted())
        }

        // An export's sections hold the same footage on the lanes as on the decks.
        if (sections && render.duration > 0) {
            val rate = 30
            val frames = ExportSections.pictures(render.duration, rate)
            for (first in listOf(0, frames / 3, frames / 2, maxOf(0, frames - 40)).distinct()) {
                val today = render.section(first, 360, rate)
                val made = plan.section(first, 360, rate)
                assertEquals("$name: section $first", listOf(today.first, today.count, today.opens, today.closes), listOf(made.first, made.count, made.opens, made.closes))
                fun footage(stretch: RenderPlan.Stretch, id: (Int) -> String) =
                    stretch.decks.flatten().filterIsInstance<RenderPlan.Part.Footage>().map { id(it.index) to it }.sortedWith(compareBy({ it.first }, { it.second.start }))
                val a = footage(today.stretch) { render.pictures[it].id }
                val b = footage(made.stretch) { plan.pictures[it].id }
                assertEquals("$name: section $first footage", a.map { it.first }, b.map { it.first })
                a.zip(b).forEach { (x, y) ->
                    near("$name: section $first part begins", x.second.start, y.second.start, 2 * tick)
                    near("$name: section $first part ends", x.second.end, y.second.end, 2 * tick)
                    val within = 2 * tick * 1e6 * render.pictures[x.second.index].rate + 2
                    near("$name: section $first cut in", x.second.sourceStartUs.toDouble(), y.second.sourceStartUs.toDouble(), within)
                    near("$name: section $first cut out", x.second.sourceEndUs.toDouble(), y.second.sourceEndUs.toDouble(), within)
                }
                for (lane in made.stretch.decks) near("$name: section $first lane filled", today.stretch.to - today.stretch.from, lane.sumOf { part ->
                    when (part) {
                        is RenderPlan.Part.Black -> part.micros / 1e6
                        is RenderPlan.Part.Footage -> part.end - part.start
                    }
                }, 2e-6 + 2 * tick)
            }
        }
    }

    private fun index(source: TransitionScene.Source): Int = when (source) {
        is TransitionScene.Source.Clip -> source.index
        is TransitionScene.Source.Held -> source.index
        is TransitionScene.Source.Fill -> -1
    }

    private fun lane(source: TransitionScene.Source): Int = when (source) {
        is TransitionScene.Source.Clip -> source.deck
        is TransitionScene.Source.Held -> source.deck
        is TransitionScene.Source.Fill -> -1
    }

    @Test fun aSequenceAsLayersIsTheMovieOfItsRenderPlanForEveryEdit() {
        for ((name, state) in edits) assertSameMovie(name, state)
        println("Layers against the render plan: ${edits.size} edits, the same movie")
        assertTrue(edits.size > 400)
    }

    @Test fun aSequenceAsLayersIsTheMovieOfItsRenderPlanForEveryStepOfTheLongProject() {
        val long = GoldenProjects.all.single { it.large }.project
        long.history.forEachIndexed { index, state ->
            val asked = index % 10 == 0 || index == long.history.lastIndex
            assertSameMovie("long, step $index", state, moments = asked, sections = asked)
        }
    }

    @Test fun aSequenceAsLayersIsTheMovieOfItsRenderPlanForWhatEveryEditingFunctionAnswers() {
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
                if (!seen.add(state)) continue
                assertSameMovie("${subject.name}: ${run.function} ${run.label}", state, sections = false)
            }
        }
        println("Layers against the render plan: ${seen.size} edits the editing functions answer, the same movie")
        assertTrue(seen.size > 1000)
    }

    // MARK: Layers

    private val canvas = Size(1920.0, 1080.0)

    /** [timeline] with a copy of its first picture as [id], from [start] for [seconds], on [layer]. */
    private fun overlaid(timeline: TrackTimeline, id: String, start: Double, seconds: Double, layer: Int = 10, color: ColorSettings? = null): TrackTimeline {
        val base = timeline.video.first()
        val content = base.content.copy(id = id, start = 1.0, end = 1.0 + seconds, color = color)
        return timeline.copy(video = timeline.video + VideoTimelineItem.of("occurrence-$id", EditTime.of(start), content, layer))
    }

    private fun planned(timeline: TrackTimeline, limit: Int = 3): LayerPlan = LayerPlan.planned(timeline, limit) ?: error("Refused")

    private fun ids(plan: LayerPlan, moment: LayerPlan.Moment): List<String> = moment.layers.map { layer ->
        when (layer) {
            is LayerPlan.Layer.Whole -> plan.pictures[layer.picture].id
            is LayerPlan.Layer.Mixed -> "${layer.transition.outgoing}>${layer.transition.incoming}"
        }
    }

    @Test fun anOverlayCoversItsBaseOnALaneOfItsOwn() {
        val base = TrackFixtures.plain().toTimeline()
        val plan = planned(overlaid(base, "O", 2.0, 3.0), limit = 2)
        assertEquals(2, plan.lanes)
        val overlay = plan.pictures.single { it.id == "O" }
        assertEquals(1, overlay.lane)
        assertTrue(plan.pictures.filter { it.id != "O" }.all { it.lane == 0 })
        val first = base.video.first().id
        assertEquals(listOf(first), ids(plan, plan.moment(1.0)))
        val covered = plan.moment(3.0)
        assertEquals(listOf(first, "O"), ids(plan, covered))
        val top = covered.layers.last() as LayerPlan.Layer.Whole
        assertNull(top.window)
        assertEquals(TransitionScene.Source.Clip(overlay.index, 1, 1.0), top.source)
        assertEquals(listOf(first), ids(plan, plan.moment(5.5)))
        // The overlay's lane plays it and is black around it.
        val lanes = plan.stretch(0.0, 8.0).decks
        assertEquals(listOf(RenderPlan.Part.Black::class, RenderPlan.Part.Footage::class, RenderPlan.Part.Black::class), lanes[1].map { it::class })
        val footage = lanes[1][1] as RenderPlan.Part.Footage
        assertEquals(overlay.index, footage.index)
        assertEquals(2.0, footage.start, 1e-9)
        assertEquals(listOf(1_000_000L, 4_000_000L), listOf(footage.sourceStartUs, footage.sourceEndUs))
        assertTrue(plan.composites)
        assertTrue(plan.fits(1920, 1080, 4096))
        assertTrue(!plan.fits(3840, 2160, 4096))
    }

    @Test fun anOverlayOverADissolveTakesThreeLanesAndMixesThePairByItsRelation() {
        val sequence = TrackFixtures.transitions(TransitionStyle.Dissolve).toTimeline()
        val relation = sequence.transitions.first { it.outgoing != null && it.incoming != null }
        val range = relation.generated.interval
        val during = (relation.origin.seconds + relation.origin.seconds + relation.timing.videoDuration) / 2
        val timeline = overlaid(sequence, "O", range.start.seconds - 1, range.duration.seconds + 2)
        val plan = planned(timeline, limit = 3)
        assertEquals(3, plan.lanes)
        val moment = plan.moment(during)
        assertEquals(listOf("${relation.outgoing}>${relation.incoming}", "O"), ids(plan, moment))
        val mixed = moment.layers[0] as LayerPlan.Layer.Mixed
        assertEquals(relation, mixed.transition)
        val outgoing = plan.pictures.single { it.id == relation.outgoing }
        val incoming = plan.pictures.single { it.id == relation.incoming }
        assertEquals(outgoing.index, index(mixed.first))
        assertEquals(incoming.index, index(mixed.second))
        assertTrue(mixed.frame is TransitionFrame.Dissolve)
        assertEquals(setOf(0, 1, 2), setOf(outgoing.lane, incoming.lane, plan.pictures.single { it.id == "O" }.lane))
        // The pair is named by its pictures, not by where they are in the list: the overlay first changes nothing.
        val reordered = planned(timeline.copy(video = listOf(timeline.video.last()) + timeline.video.dropLast(1)), limit = 3)
        val again = reordered.moment(during)
        assertEquals(ids(plan, moment), ids(reordered, again))
        val second = again.layers[0] as LayerPlan.Layer.Mixed
        assertEquals(relation.outgoing, reordered.pictures[index(second.first)].id)
        assertEquals(relation.incoming, reordered.pictures[index(second.second)].id)
        // Two lanes are not enough.
        val refused = LayerPlan.of(timeline, 2) as LayerPlan.Outcome.TooManyLayers
        assertEquals(setOf(relation.outgoing, relation.incoming, "O"), refused.pictures.toSet())
        assertTrue(refused.at.start <= EditTime.of(during) && EditTime.of(during) < refused.at.end || refused.at.start >= range.start)
        assertEquals(2, refused.limit)
    }

    /** YouRec Tests/TrackMediaTests.swift:381-400 and the placement order and edge crop after it. */
    @Test fun theForegroundCoversTheBackgroundByDefaultAndAPlacementReordersAndCrops() {
        val baseline = EditState(listOf(TrackFixtures.clip(1, TrackFixtures.take, 0.0, 4.0))).toTimeline()
        val overlap = overlaid(baseline, "other", 1.0, 4.0, layer = 10, color = ColorSettings(look = ColorLook.Mono))
        val base = baseline.video[0].id
        var plan = planned(overlap, limit = 2)
        var moment = plan.moment(2.0)
        assertEquals(listOf(base, "other"), ids(plan, moment))
        val top = moment.layers.last() as LayerPlan.Layer.Whole
        assertNull("Foreground video covers background by default", top.window)
        assertEquals(ColorLook.Mono, plan.pictures[top.picture].color?.look)

        val ids = setOf(base, "other")
        val back = VideoPlacement(order = 10)
        val front = VideoPlacement(order = 0)
        plan = planned(overlap.copy(placements = listOf(VideoPlacementRegion(participants = ids, videos = mapOf(base to back, "other" to front)))), limit = 2)
        moment = plan.moment(2.0)
        assertEquals("Placement order changes which video covers the other", listOf("other", base), ids(plan, moment))

        val cropped = front.copy(order = 10, edgeCrop = PlacementEdgeCrop(left = 0.2, right = 0.15, top = 0.2, bottom = 0.1))
        plan = planned(overlap.copy(placements = listOf(VideoPlacementRegion(participants = ids, videos = mapOf(base to back.copy(order = 0), "other" to cropped)))), limit = 2)
        moment = plan.moment(2.0)
        assertEquals(listOf(base, "other"), ids(plan, moment))
        val window = (moment.layers.last() as LayerPlan.Layer.Whole).window!!
        listOf(384.0, 216.0, 1248.0, 756.0).zip(listOf(window.clip.x, window.clip.y, window.clip.width, window.clip.height)).forEach { (a, b) -> assertEquals(a, b, 1e-9) }
        assertEquals(1.0, window.scale, 1e-12)
        assertEquals("The lower video fills the canvas", PlacementRendering.placed(back.copy(order = 0), canvas), (moment.layers.first() as LayerPlan.Layer.Whole).window)
    }

    /** The arrangements of YouRec Tests/TrackTimelineTests.swift:532-555, with an overlay and a stack of three. */
    private fun arrangements(): List<TrackTimeline> {
        val plain = TrackFixtures.plain().toTimeline()
        val dissolve = TrackFixtures.transitions(TransitionStyle.Dissolve).toTimeline()
        return listOf(
            plain,
            overlaid(plain, "O", 2.0, 3.0),
            overlaid(plain, "under", 2.0, 3.0, layer = -1),
            overlaid(overlaid(plain, "O", 2.0, 6.0), "P", 3.0, 2.0, layer = 20),
            overlaid(dissolve, "O", 10.0, 5.0),
            dissolve,
        )
    }

    @Test fun theLanesOfTheDrawnTimelineAgreeWithTheCompositor() {
        for ((number, timeline) in arrangements().withIndex()) {
            val plan = planned(timeline, limit = 4)
            val rows = timeline.lanes().filter { !it.audio }.associate { it.id to it.row }
            assertEquals(timeline.video.map { it.id }.toSet(), rows.keys)
            val compiled = timeline.compile()
            for (part in compiled.filter { it.interval.start.seconds < plan.duration }) {
                val actual = timeline.video.filter { part.interval.start in it.interval }.map { it.id }.toSet()
                assertEquals("$number: the compositor retains every overlapping source", actual, part.video.toSet())
                val stretch = plan.at(part.interval.start.seconds + tick / 2)!!
                assertEquals(number.toString(), part.interval.start, stretch.interval.start)
                val drawn = stretch.order.map { plan.pictures[it].id }.filter { it in actual }
                assertEquals("$number: upper and lower agree with the compositor", drawn.sortedByDescending { rows.getValue(it) }, drawn)
                assertEquals("$number: a lane each", actual.size, actual.map { id -> plan.pictures.single { it.id == id }.lane }.toSet().size)
            }
        }
    }

    @Test fun morePicturesAtOnceThanTheLimitAreRefused() {
        val plain = TrackFixtures.plain().toTimeline()
        val three = overlaid(overlaid(plain, "O", 2.0, 6.0), "P", 3.0, 2.0, layer = 20)
        val refused = LayerPlan.of(three, 2) as LayerPlan.Outcome.TooManyLayers
        assertEquals(EditInterval(EditTime.of(3.0), EditTime.of(5.0)), refused.at)
        assertEquals(listOf(plain.video[0].id, "O", "P"), refused.pictures)
        assertNull(LayerPlan.planned(three, 2))
        assertEquals(3, planned(three, limit = 3).lanes)
        // One after another, two overlays take one lane: lanes are what plays at once, not the clips.
        val apart = overlaid(overlaid(plain, "O", 2.0, 2.0), "P", 4.0, 2.0)
        val plan = planned(apart, limit = 2)
        assertEquals(2, plan.lanes)
        assertEquals(1, plan.pictures.single { it.id == "O" }.lane)
        assertEquals(1, plan.pictures.single { it.id == "P" }.lane)
        assertNotNull(LayerPlan.planned(plain, 1))
    }

    @Test fun eachStretchTakesTheWindowsOfExactlyThePicturesItShows() {
        val plain = EditState(listOf(TrackFixtures.clip(1, TrackFixtures.take, 0.0, 10.0))).toTimeline()
        val timeline = overlaid(overlaid(plain, "O", 2.0, 4.0), "P", 4.0, 4.0, layer = 20)
        val base = plain.video[0].id
        val pip = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2)
        val moving = pip.copy(motion = EffectAnimation(pip.pose, 4.0).copy(b = pip.copy(x = 0.3).pose))
        val sides = listOf(0, 1, 2).map { VideoPlacement.layout(PlacementLayout.SideBySide, it, 3) }
        val two = VideoPlacementRegion(id = "two", participants = setOf(base, "O"), videos = mapOf(base to VideoPlacement(order = 0), "O" to moving))
        val three = VideoPlacementRegion(id = "three", participants = setOf(base, "O", "P"), videos = mapOf(base to sides[0], "O" to sides[1], "P" to sides[2]))
        val plan = planned(timeline.copy(placements = listOf(two, three)))

        // 2…4: the base and O, O in its moving window.
        assertEquals("two", plan.at(3.0)!!.placement?.id)
        val early = plan.moment(2.5)
        val later = plan.moment(3.5)
        assertEquals(listOf(base, "O"), ids(plan, early))
        val o = plan.pictures.single { it.id == "O" }
        fun window(moment: LayerPlan.Moment) = (moment.layers.last() as LayerPlan.Layer.Whole).window!!
        // The motion is taken at the source second O shows.
        assertEquals(PlacementRendering.placed(moving.at(o.content.sourceTime(0.5)), canvas), window(early))
        assertEquals(PlacementRendering.placed(moving.at(o.content.sourceTime(1.5)), canvas), window(later))
        assertTrue(window(early) != window(later))
        // The base has a window of its own in the region: the whole canvas.
        assertEquals(PlacementRendering.placed(VideoPlacement(order = 0), canvas), (early.layers.first() as LayerPlan.Layer.Whole).window)
        // 4…6: all three side by side.
        val all = plan.moment(5.0)
        assertEquals("three", all.stretch?.placement?.id)
        assertEquals(listOf(base, "O", "P"), ids(plan, all))
        all.layers.forEachIndexed { rank, layer -> assertEquals(PlacementRendering.placed(sides[rank], canvas), (layer as LayerPlan.Layer.Whole).window) }
        // 6…8: the base and P have no region: P covers the base whole.
        val uncovered = plan.moment(7.0)
        assertNull(uncovered.stretch?.placement)
        assertEquals(listOf(base, "P"), ids(plan, uncovered))
        assertTrue(uncovered.layers.all { (it as LayerPlan.Layer.Whole).window == null })
        // 8…10: the base alone.
        assertEquals(listOf(base), ids(plan, plan.moment(9.0)))
        // Past the picture, and where nothing plays, black.
        assertEquals(10.0, plan.duration, 1e-9)
        assertEquals(emptyList<String>(), ids(plan, plan.moment(-1.0)))
    }

    @Test fun whereNoPicturePlaysTheFrameIsBlack() {
        val plain = TrackFixtures.plain().toTimeline()
        val second = plain.video[1]
        val apart = plain.copy(video = plain.video.mapIndexed { index, item -> if (index > 0) item.copy(start = item.start + EditTime.of(2.0)) else item })
        val plan = planned(apart, limit = 1)
        assertEquals(1, plan.lanes)
        val gap = (plain.video[0].end.seconds + plain.video[0].end.seconds + 2) / 2
        assertEquals(emptyList<LayerPlan.Layer>(), plan.moment(gap).layers)
        assertEquals(listOf(second.id), ids(plan, plan.moment(second.start.seconds + 2.5)))
    }
}
