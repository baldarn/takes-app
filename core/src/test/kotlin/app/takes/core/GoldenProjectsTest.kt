package app.takes.core

import app.takes.core.RenderPlan.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** A golden project as text: what is saved of it, where its clips play, and what is drawn and played. */
object GoldenRecord {
    private fun n(value: Double): String = String.format(Locale.ROOT, "%.9f", value)
    private fun n(range: ClosedFloatingPointRange<Double>): String = "${n(range.start)}..${n(range.endInclusive)}"

    /** The files a save writes: the manifest, and each step of the history under the name of its hash. */
    fun saved(project: Project): Pair<ByteArray, Map<String, ByteArray>> {
        val steps = linkedMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> steps[name] = data }
        return manifest to steps
    }

    private fun join(join: TransitionLayout.Join): String = with(join) {
        "join $index at ${n(time)} over ${n(range)} origin ${n(origin)} factor ${n(factor)} edge $edge from $outgoing to $incoming maximum ${n(maximum)}" +
            (transition?.let {
                " | ${it.style.name} ${it.id} motion ${n(timing.motion)} hold ${n(timing.hold)} picture ${n(timing.videoDuration)}" +
                    " in ${n(timing.audioIn)} out ${n(timing.audioOut)} leftEnd ${n(timing.leftEnd)} rightStart ${n(timing.rightStart)}" +
                    " lower ${n(timing.lower)} upper ${n(timing.upper)} cut ${n(timing.cut)} costs ${n(timing.leftCost)} ${n(timing.rightCost)}"
            } ?: " | cut")
    }

    fun layout(clips: List<TimelineClip>): String = buildString {
        val layout = TimelineEditing.layout(clips)
        appendLine("duration ${n(layout.duration)}")
        for (placement in layout.placements) with(placement) {
            appendLine("clip $index ${clips[index].id} from ${n(start)} for ${n(duration)} overlaps ${n(incoming)} ${n(outgoing)} owns ${n(lower)}..${n(upper)}")
        }
        for (join in layout.joins) appendLine(join(join))
        for (fade in layout.fades) appendLine("fade " + join(fade))
    }

    private fun entry(entry: RenderPlan.Entry): String = when (entry) {
        is RenderPlan.Entry.Gap -> "gap@${n(entry.start)}:${entry.micros}us"
        is RenderPlan.Entry.Clip -> "clip ${entry.index}"
        is RenderPlan.Entry.Sound -> "sound ${entry.item.id}@${n(entry.start)} for ${n(entry.seconds)} file ${entry.sourceStartUs}..${entry.sourceEndUs}us"
    }

    private fun source(source: TransitionScene.Source): String = when (source) {
        is TransitionScene.Source.Clip -> "clip ${source.index} on deck ${source.deck} at ${n(source.offset)}"
        is TransitionScene.Source.Held -> "clip ${source.index} on deck ${source.deck} held at ${n(source.offset)} since ${n(source.from)}"
        is TransitionScene.Source.Fill -> "fill ${source.color.red} ${source.color.green} ${source.color.blue}"
    }

    private fun rect(rect: Rect): String = "${n(rect.x)},${n(rect.y)} ${n(rect.width)}x${n(rect.height)}"

    private fun frame(frame: TransitionFrame): String = when (frame) {
        TransitionFrame.First -> "the first picture"
        TransitionFrame.Second -> "the second picture"
        is TransitionFrame.Dissolve -> "dissolve, second ${n(frame.second)}"
        is TransitionFrame.FadeColor -> "fade through ${frame.color.red} ${frame.color.green} ${frame.color.blue}: first ${n(frame.first)} color ${n(frame.solid)} second ${n(frame.second)}"
        is TransitionFrame.Wipe -> with(frame.edge) { "wipe, edge at ${n(center.x)},${n(center.y)} going ${n(dx)},${n(dy)} feather ${n(feather)}" }
        is TransitionFrame.Split -> "split, first in ${rect(frame.first)} second in ${rect(frame.second)} line ${rect(frame.line)} mix ${n(frame.mix.first)} ${n(frame.mix.pair)} ${n(frame.mix.second)}"
        is TransitionFrame.Hitchcock -> "Hitchcock, key ${n(frame.key.scale)} ${n(frame.key.bias)}"
        is TransitionFrame.Whip -> "whip, ${if (frame.vertical) "down" else "across"} by ${n(frame.firstOffset)} and ${n(frame.secondOffset)} radius ${n(frame.blurRadius)} mix ${n(frame.mix)}"
        is TransitionFrame.Defocus -> "defocus, radius ${n(frame.blurRadius)} mix ${n(frame.mix)}"
    }

    fun plan(plan: RenderPlan): String = buildString {
        appendLine("duration ${n(plan.duration)} stacked ${plan.stacked} transitions drawn ${plan.drawsTransitions}")
        plan.chains.forEachIndexed { index, chain -> appendLine("chain $index clips ${chain.first}..${chain.last} over ${n(chain.start)}..${n(chain.end)}") }
        for (picture in plan.pictures) with(picture) {
            appendLine(
                "picture $index $id of $sourceID at ${n(start)} for ${n(duration)} footage ${n(sourceStart)}..${n(sourceEnd)} cut ${sourceStartUs}..${sourceEndUs}us rate ${n(rate)}" +
                    " chain $chain deck $deck color ${color?.id} crop ${crop?.id} shaped $shaped sound removed $soundRemoved",
            )
            // How loud it is at its start, its quarters and its end.
            appendLine("  level " + listOf(0.0, 0.25, 0.5, 0.75, 1.0).joinToString(" ") { n(plan.clipLevel(index, it * duration)) })
        }
        for (deck in plan.decks) appendLine("deck ${deck.number} clips ${deck.clips} chains ${deck.chains}")
        for (sequence in plan.sequences + plan.soundtracks + plan.cleaned) {
            appendLine("sequence ${sequence.role} ${sequence.number}: " + sequence.entries.joinToString(", ", transform = ::entry))
        }
        for (sound in plan.sounds) appendLine("sound ${sound.id} level " + listOf(0.0, 0.25, 0.5, 0.75, 1.0).joinToString(" ") { n(plan.soundLevel(sound, it * sound.duration)) })
        for (join in plan.transitions) appendLine("transition ${join.index} ${join.transition!!.style.name} picture ${n(join.videoRange)} sound in ${n(join.audioIn)} out ${n(join.audioOut)}")
        for (freeze in plan.freezes) with(freeze) {
            appendLine("freeze of join $join: clip $clip on deck $deck from ${n(from)} to ${n(until)} shows ${n(shown)}, ${n(offset)} in, footage ${n(sourceTime)}, sounding $sounding")
        }
        for (ending in plan.endings) appendLine("ending on deck ${ending.deck} at ${n(ending.at)}")
        for (title in plan.titles) appendLine("title ${title.title.id} of clip ${title.clip} over ${n(title.start)}..${n(title.end)}")
        plan.subtitles?.let { appendLine("captions ${it.style} ${it.color} at most ${it.maxWords} words") }
        for (cue in plan.captions) appendLine("caption ${cue.id} over ${n(cue.start)}..${n(cue.end)}: ${cue.text}")
        // What the movie shows at seventeen moments from its start to its end.
        for (step in 0..16) {
            val time = plan.duration * step / 16
            val moment = plan.moment(time)
            appendLine("moment ${n(time)}: ${frame(moment.frame)} | first ${source(moment.first)} | second ${source(moment.second)} | titles ${n(moment.firstShare)} ${n(moment.secondShare)}")
        }
    }

    /** Everything pinned of [golden]. A large one is pinned by the hash of the same text. */
    fun text(golden: Golden): String {
        val project = golden.project
        val (manifest, steps) = saved(project)
        val whole = buildString {
            appendLine("== project.json")
            appendLine(manifest.decodeToString())
            appendLine("== history")
            for (name in steps.keys) appendLine(name)
            appendLine("== step ${project.historyCursor} of ${project.snapshots.size}")
            appendLine(project.snapshots[project.historyCursor].data().decodeToString())
            appendLine("== layout")
            append(layout(project.edit.clips))
            appendLine("== plan")
            append(plan(RenderPlan.of(project.edit)))
        }
        if (!golden.large) return whole
        return "sha256 ${sha256(whole.toByteArray())}\n${project.edit.clips.size} clips, ${project.snapshots.size} steps, ${n(project.edit.duration)} s\n"
    }
}

/**
 * The golden projects as baselines. Their pinned text is in
 * `src/test/resources/golden`, one file a project; run the tests with
 * `TAKES_RECORD_GOLDEN=1` in the environment to write it anew, and read the
 * difference before keeping it: nothing in it should change by accident.
 *
 * The numbers are written as Java 17 prints them, which is the version the
 * build is set to.
 */
class GoldenProjectsTest {
    private val folder = File("src/test/resources/golden")
    private val recording = System.getenv("TAKES_RECORD_GOLDEN") == "1"

    @Test fun theSavedBytesTheLayoutAndThePlanOfEveryGoldenProjectArePinned() {
        if (recording) folder.mkdirs()
        val changed = mutableListOf<String>()
        for (golden in GoldenProjects.all) {
            val file = File(folder, golden.name + ".txt")
            val text = GoldenRecord.text(golden)
            if (recording) file.writeText(text) else if (!file.isFile || file.readText() != text) changed += golden.name
        }
        assertEquals("These golden projects are no longer what is pinned", emptyList<String>(), changed)
        if (!recording) {
            val pinned = folder.listFiles().orEmpty().map { it.name.removeSuffix(".txt") }.toSet()
            assertEquals("A pinned file has no golden project", GoldenProjects.all.map { it.name }.toSet(), pinned)
        }
    }

    @Test fun everyGoldenProjectIsValidAndReadsBackAsItWasSaved() {
        for (golden in GoldenProjects.all) {
            golden.project.validate()
            val (manifest, steps) = GoldenRecord.saved(golden.project)
            val read = Project.readManifest(manifest) { steps.getValue(it) }
            assertEquals(golden.name, golden.project, read)
            val (again, _) = GoldenRecord.saved(read)
            assertTrue(golden.name, manifest.contentEquals(again))
        }
    }

    @Test fun theGoldenProjectsAreBuiltTheSameEveryTime() {
        val first = GoldenProjects.build(withLong = false).map(GoldenRecord::text)
        val second = GoldenProjects.build(withLong = false).map(GoldenRecord::text)
        assertEquals(first, second)
        assertEquals(first, GoldenProjects.all.filter { !it.large }.map(GoldenRecord::text))
    }

    @Test fun theGoldenProjectsCoverWhatAProjectCanHold() {
        val edits = GoldenProjects.all.filter { !it.large }.map { it.project.edit }
        val clips = edits.flatMap { it.clips }
        val layouts = edits.map { TimelineEditing.layout(it.clips) }
        val joins = layouts.flatMap { it.all }.filter { it.transition != null }
        // Every style between two clips, opening the movie and closing it; some fitted to less footage than they ask for.
        for (style in TransitionStyle.entries) for (edge in listOf(-1, 0, 1)) {
            assertTrue("${style.name} at edge $edge", joins.any { it.transition!!.style == style && it.edge == edge })
        }
        for (style in TransitionStyle.entries) assertTrue("${style.name} fitted", joins.any { it.transition!!.style == style && it.factor < 0.999 })
        assertTrue("a sound timing of its own", joins.any { it.transition!!.customAudio })
        assertTrue("a hold", joins.any { it.timing.hold > 0 && it.transition!!.style != TransitionStyle.Still })
        // Cuts between frames.
        assertTrue(clips.any { (it.start * 30) % 1.0 != 0.0 })
        // Titles with a design and a motion, and one as older projects have them.
        val titles = clips.flatMap { it.texts.orEmpty() }
        assertTrue(titles.any { it.appearance != null && it.motion?.active == true })
        assertTrue(titles.any { !it.styled })
        assertTrue("a title on two pieces", titles.groupBy { it.id }.any { it.value.size > 1 })
        assertTrue(clips.any { !it.bookmarks.isNullOrEmpty() })
        // Captions, speed, crop, color and a LUT by name.
        assertTrue(edits.any { it.subtitles != null && SubtitleEditing.cues(it).isNotEmpty() })
        assertTrue(clips.any { it.rate > 1 } && clips.any { it.rate < 1 })
        assertTrue(clips.any { it.crop != null })
        assertTrue(clips.any { it.color?.look != null && it.color?.imported == null } && clips.any { it.color?.imported != null } && clips.any { it.color?.hasBalance == true })
        // Sound: both tracks, fades, past the end of the picture, unlinked, cleaned.
        val sounds = edits.flatMap { it.audio.orEmpty() }
        assertTrue(sounds.any { it.track == 0 } && sounds.any { it.track == 1 })
        assertTrue(sounds.any { (it.audio?.fadeIn ?: 0.0) > 0 && (it.audio?.fadeOut ?: 0.0) > 0 })
        assertTrue(edits.any { edit -> edit.audio.orEmpty().any { it.end > edit.duration } })
        assertTrue(edits.any { edit -> edit.clips.indices.any { AudioUnlinking.isDetached(edit, it) && (AudioUnlinking.syncOffset(edit, it) ?: 0.0) != 0.0 } })
        assertTrue(clips.any { (it.audio?.denoise ?: 0.0) > 0 })
        // Pieces one frame long.
        assertTrue(clips.any { kotlin.math.abs(it.duration - RangeEditing.FRAME) < 1e-9 })
        // A history with takes that the ledger does not replay, and a project never edited.
        assertTrue(GoldenProjects.all.any { it.project.captureInsertions.isNotEmpty() })
        assertTrue(GoldenProjects.all.any { it.project.snapshots.size == 1 && it.project.captureLedger.isNotEmpty() })
        val long = GoldenProjects.all.single { it.large }.project
        assertEquals(300, long.snapshots.size)
        assertEquals(2000, long.edit.clips.size)
    }

    // MARK: The render plan of every golden project

    private val plans by lazy { GoldenProjects.all.map { it to RenderPlan.of(it.project.edit) } }

    @Test fun everySequenceRunsTheLengthOfTheMovie() {
        for ((golden, plan) in plans) for (sequence in plan.sequences + plan.soundtracks + plan.cleaned) {
            var at = 0.0
            for (entry in sequence.entries) {
                when (entry) {
                    is Entry.Gap -> {
                        // A gap begins where the sequence has got to.
                        assertEquals("${golden.name} ${sequence.role} ${sequence.number}", at, entry.start, 1e-5)
                        at += entry.micros / 1e6
                    }
                    is Entry.Clip -> {
                        assertEquals("${golden.name} ${sequence.role} ${sequence.number} clip ${entry.index}", plan.pictures[entry.index].start, at, 1e-5)
                        at += plan.pictures[entry.index].duration
                    }
                    is Entry.Sound -> {
                        assertEquals("${golden.name} ${sequence.role} ${sequence.number} sound ${entry.item.id}", entry.start, at, 1e-5)
                        assertEquals(entry.seconds, (entry.sourceEndUs - entry.sourceStartUs) / 1e6, 1e-6)
                        at += entry.seconds
                    }
                }
            }
            assertEquals("${golden.name} ${sequence.role} ${sequence.number}", plan.duration, at, 1e-5 + RenderPlan.GAP_MARGIN)
        }
    }

    @Test fun everyClipIsOnOneDeckAndInOneSequence() {
        for ((golden, plan) in plans) {
            val clips = plan.pictures.indices.toList()
            assertEquals(golden.name, clips, plan.decks.flatMap { it.clips }.sorted())
            assertEquals(golden.name, clips, plan.sequences.flatMap { it.entries }.filterIsInstance<Entry.Clip>().map { it.index }.sorted())
            for (deck in plan.decks) {
                assertTrue(golden.name, deck.clips.all { plan.pictures[it].deck == deck.number })
                // What a deck plays never overlaps: one player shows it.
                for ((one, next) in deck.clips.zipWithNext()) assertTrue(golden.name, plan.pictures[next].start >= plan.pictures[one].end - 1e-5)
            }
            if (plan.sequences.size == 2) for (sequence in plan.sequences) {
                assertEquals(golden.name, plan.decks[sequence.number].clips, sequence.entries.filterIsInstance<Entry.Clip>().map { it.index })
            }
            // Cleaned sound is carried for exactly the clips whose recorded sound is left out.
            assertEquals(golden.name, plan.pictures.filter { it.soundRemoved }.map { it.index }, plan.cleaned.flatMap { it.entries }.filterIsInstance<Entry.Clip>().map { it.index }.sorted())
            assertEquals(golden.name, TimelineEditing.duration(golden.project.edit.clips), plan.duration, 0.0)
        }
    }

    @Test fun aPlanIsMadeForEveryStepOfEveryHistoryAndEqualEditsGiveEqualPlans() {
        for (golden in GoldenProjects.all.filter { !it.large }) for (state in golden.project.history) {
            val plan = RenderPlan.of(state)
            assertEquals(golden.name, plan, RenderPlan.of(state.copy()))
            assertEquals(golden.name, state.clips.size, plan.pictures.size)
        }
    }
}
