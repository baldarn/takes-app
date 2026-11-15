package app.takes.core

import kotlinx.serialization.builtins.ListSerializer

/** A project kept as a baseline. [large] ones are pinned by their hash alone. */
class Golden(val name: String, val project: Project, val large: Boolean = false)

/**
 * The golden projects: saved projects that stand for what users have, built
 * here from the core's public editing functions. Their saved bytes, their
 * layout and their render plan are pinned ([GoldenProjectsTest]), so a later
 * change of the model can show that it leaves every one of them as it was.
 *
 * Every id is a fixed one: the editing functions name new clips at random,
 * and [stable] renames those in the order they appear.
 */
object GoldenProjects {
    private const val CREATED = 1_700_000_000_000L
    private val random = Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")

    /** [project] with every id made at random replaced by a numbered one, the same wherever it appears. */
    fun stable(project: Project): Project {
        val names = mutableMapOf<String, String>()
        fun fixed(text: String) = random.replace(text) { names.getOrPut(it.value) { "ID-%05d".format(names.size + 1) } }
        val ledger = ListSerializer(TimelineClip.serializer())
        // The captions remember the clips they were timed for, ids included, as a hash: it is taken again of the renamed clips,
        // and the steps after an edit that changed the footage keep the one of the step they were timed in.
        val fingerprints = mutableMapOf<String, String>()
        return project.copy(
            captureLedger = documentJson.decodeFromString(ledger, fixed(documentJson.encodeToString(ledger, project.captureLedger))),
            snapshots = project.snapshots.map { snapshot ->
                val renamed = documentJson.decodeFromString(EditSnapshot.serializer(), fixed(snapshot.data().decodeToString()))
                val settings = renamed.state.subtitles ?: return@map renamed
                if (SubtitleEditing.groupingAvailable(snapshot.state)) fingerprints[settings.timingFingerprint] = SubtitleEditing.fingerprint(renamed.state.clips)
                val fingerprint = checkNotNull(fingerprints[settings.timingFingerprint]) { "Captions timed for clips no step of the history has" }
                renamed.copy(state = renamed.state.copy(subtitles = settings.copy(timingFingerprint = fingerprint)))
            },
        )
    }

    /** Builds a project step by step, as the app does: takes are recorded, sounds imported, and every edit is a step of the history. */
    private class Builder(val name: String) {
        var project = Project(title = "Golden $name", id = "P-$name", createdAt = CREATED, updatedAt = CREATED)
        private var clock = CREATED
        private var numbered = 0

        val edit: EditState get() = project.edit
        val clips: List<TimelineClip> get() = project.edit.clips

        /** Records a take of [seconds]; it joins the end of the timeline, or the place chosen for it. */
        fun take(seconds: Double, events: List<CaptureEvent>? = null): Builder {
            numbered++
            project = project.addRecording(SourceMedia("S$numbered", "take-$numbered.mp4", seconds, CREATED + numbered * 1000L, events), ++clock)
            return this
        }

        /** Imports a sound file of [seconds] and returns its source id. */
        fun sound(seconds: Double): String {
            numbered++
            project = project.addSource(SourceMedia("S$numbered", "sound-$numbered.m4a", seconds, CREATED + numbered * 1000L, audioOnly = true, originalName = "Music $numbered.m4a"), ++clock)
            return "S$numbered"
        }

        /** One step of the history. An edit that does not apply is a mistake in the builder. */
        fun step(what: (EditState) -> EditState?): Builder {
            val next = checkNotNull(what(edit)) { "$name: an edit did not apply at step ${project.snapshots.size}" }
            val before = project
            project = project.commit(next, ++clock)
            check(project !== before) { "$name: an edit changed nothing at step ${before.snapshots.size}" }
            return this
        }

        fun join(index: Int, settings: TransitionSettings): Builder = step { TimelineEditing.setJoin(it, index, settings) }

        fun source(index: Int): Double = project.sources.first { it.id == clips[index].sourceID }.duration

        fun build(large: Boolean = false): Golden = Golden(name, stable(project).also { it.validate() }, large)
    }

    private fun transition(id: String, style: TransitionStyle, duration: Double = 1.0) = TransitionSettings(id = id, style = style, duration = duration)

    /** The settings each style is tried with between two clips: its own controls away from their defaults. */
    private fun dressed(style: TransitionStyle): TransitionSettings = when (style) {
        TransitionStyle.Dissolve -> transition("T-own", style).copy(hold = 0.5)
        TransitionStyle.FadeColor -> transition("T-own", style).copy(fadeColor = TransitionColor(0.9, 0.85, 0.7), hold = 0.4)
        TransitionStyle.Wipe -> transition("T-own", style).copy(wipeAngle = 30.0, hardEdge = true)
        TransitionStyle.Split -> transition("T-own", style, 2.0).copy(axis = TransitionAxis.Vertical, divider = 0.4, audio = TransitionAudio.Second, first = TransitionFraming(0.3, 0.6, 1.5))
        TransitionStyle.Hitchcock -> transition("T-own", style).copy(highlightsFirst = true)
        TransitionStyle.Whip -> transition("T-own", style, TransitionDefaults.WHIP_DURATION).copy(axis = TransitionAxis.Vertical, reverse = true)
        TransitionStyle.FlashCut -> transition("T-own", style, 0.5).copy(flashCount = 4, flashAcceleration = 1.5)
        TransitionStyle.Still -> transition("T-own", style).copy(stillDissolve = 0.3)
        TransitionStyle.Defocus -> transition("T-own", style).copy(blurAmount = 0.4)
    }

    /**
     * One style everywhere it can be: opening the movie, between clips as it
     * comes, between clips with its own controls set, between clips too short
     * for it (so it plays fitted), with a sound timing of its own, and closing the movie.
     */
    private fun styled(style: TransitionStyle): Golden {
        val builder = Builder("transition-${style.name.lowercase()}").take(4.0).take(4.0).take(4.0).take(0.7).take(4.0).take(4.0)
            .join(0, transition("T-opening", style, 0.5))
            .join(1, transition("T-plain", style))
            .join(2, dressed(style))
            // Longer than the short clip after it: fitted to what there is. The short clip then meets the next at a plain cut.
            .join(3, transition("T-fitted", style, 2.0).let { if (style == TransitionStyle.Still) it.copy(stillDissolve = 2.0) else it })
            // The sound leads the picture and changes over more slowly: a J-cut.
            .join(5, transition("T-sound", style).copy(audioOffset = -0.25, audioBlend = 1.5))
            .join(6, transition("T-close", style, 0.5))
        return builder.build()
    }

    private val title = TextOverlay(
        id = "TITLE-1", text = "Chapter one", start = 0.0, end = 3.0, x = 0.5, y = 0.3, size = 0.08,
        appearance = TitleDesign(
            face = "serif", weight = 600, italic = true, alignment = TitleAlignment.Left, tracking = 0.04, lineSpacing = 0.1,
            fill = TitleInk(0xffd429ffL), gradient = TitleGradient(TitleInk(0xff4d5cffL), TitleInk(0x59a8ffffL), 45.0),
            outlined = true, plate = TitlePlate.Block,
        ),
        motion = TitleMotion(TitleMove.Drift, TitleMove.Fade, 0.5, 0.25, TitleDirection.Up, null),
    )

    private fun words(text: String, from: Double, each: Double): List<SubtitleWord> {
        val phrase = "PHRASE-${from}"
        return text.split(" ").mapIndexed { index, word -> SubtitleWord("WORD-$from-$index", phrase, word, from + index * each, from + index * each + each * 0.9) }
    }

    /** Every golden project, built once. */
    val all: List<Golden> by lazy { build() }

    /** Builds them anew; without the [long] one, which takes seconds. */
    fun build(withLong: Boolean = true): List<Golden> {
        return listOf(
            // Takes as they were recorded, never edited: the history has its first, empty step alone.
            Builder("plain-takes").take(5.0).take(3.25, listOf(CaptureEvent("MARK-1", 1.5, Project.BOOKMARK_EVENT, ""))).take(8.0).build(),

            // Cuts that fall between frames, trims, a move and a deletion.
            Builder("cuts").take(10.0).take(6.0).take(7.5)
                .step { TimelineEditing.split(it, 3.123456) }
                .step { TimelineEditing.split(it, 11.987) }
                .step { state -> TimelineEditing.trim(state, 1, 3.5, 9.4321, 10.0) }
                .step { TimelineEditing.move(it, 3, 0) }
                .step { TimelineEditing.delete(it, 2) }
                .step { state -> TimelineEditing.toggleBookmark(state, 0, state.clips[0].start + 0.4) }
                .build(),
        ) + TransitionStyle.entries.map(::styled) + listOf(
            // Sound timings a transition can have: a J-cut, an L-cut, a hard cut of the sound, one side's sound only, holds.
            Builder("transition-sound").take(6.0).take(6.0).take(6.0).take(6.0).take(6.0).take(6.0)
                .join(1, transition("T-j", TransitionStyle.Dissolve).copy(audioOffset = -0.5, audioBlend = 0.75))
                .join(2, transition("T-l", TransitionStyle.Wipe).copy(audioOffset = 1.25, audioBlend = 0.5))
                .join(3, transition("T-cut", TransitionStyle.Dissolve).copy(audioOffset = 0.5, audioBlend = 0.0))
                .join(4, transition("T-first", TransitionStyle.Split, 2.0).copy(audio = TransitionAudio.First))
                .join(5, transition("T-hold", TransitionStyle.FadeColor).copy(hold = 1.5))
                .build(),

            // Titles: one with a design and a motion that runs across a cut, one as older projects have them, one placed over two clips.
            Builder("titles").take(8.0).take(5.0)
                .step { TimelineEditing.setText(it, 0, title) }
                .step { TimelineEditing.setText(it, 0, TextOverlay(id = "TITLE-2", text = "Plain", start = 4.0, end = 6.5, x = 0.2, y = 0.8)) }
                .step { TimelineEditing.split(it, 1.5) }
                .step { TimelineEditing.placeTitle(it, TextOverlay(id = "TITLE-3", text = "Across", appearance = TitleDesign(), motion = TitleMotion(TitleMove.Scale, TitleMove.Wipe, outDirection = TitleDirection.Right)), 7.0..9.5) }
                .join(2, transition("T-1", TransitionStyle.Dissolve))
                .build(),

            // Captions: words on the clips, a look, a split through a phrase, a muted clip that shows none.
            Builder("subtitles").take(6.0).take(4.0)
                .step { SubtitleEditing.install(it, mapOf("S1" to words("the quick brown fox jumps over the lazy dog again", 0.5, 0.5), "S2" to words("and then some more words", 0.25, 0.6)), "en") }
                .step { SubtitleEditing.restyle(it, it.subtitles!!.copy(id = "SUBTITLES", maxWords = 4, style = SubtitleSettings.Style.Highlight, color = SubtitleSettings.Color.Yellow, size = 0.06, y = 0.8, font = SubtitleFont.Mono)) }
                .step { TimelineEditing.split(it, 2.1) }
                .step { TimelineEditing.setAudio(it, 2, ClipAudio(muted = true)) }
                .build(),

            // Speed, and pauses taken out of a clip that has transitions at both ends.
            Builder("speed-and-pauses").take(12.0).take(8.0).take(6.0)
                .join(1, transition("T-1", TransitionStyle.Dissolve))
                .join(2, transition("T-2", TransitionStyle.Wipe))
                .step { TimelineEditing.setSpeed(it, 0, 2.0) }
                .step { TimelineEditing.setSpeed(it, 2, 0.5) }
                .step { TimelineEditing.removeIntervals(it, 1, listOf(SourceInterval(2.0, 2.8), SourceInterval(4.1, 5.35))) }
                .build(),

            // Framing and color: a crop with a zoom and a turn, a look, a balance, an imported LUT by name.
            Builder("crop-and-color").take(5.0).take(5.0).take(5.0)
                .step { TimelineEditing.setCrop(it, 0, CropSettings(id = "CROP-1", x = 0.4, y = 0.55, zoom = 1.6, rotation = 3.0)) }
                .step { TimelineEditing.setColor(it, 0, ColorSettings(id = "COLOR-1", look = ColorLook.Cinema, amount = 0.8)) }
                .step { TimelineEditing.setColor(it, 1, ColorSettings(id = "COLOR-2", exposure = 0.5, highlights = -0.3, warmth = 0.2, tint = -0.1)) }
                .step { TimelineEditing.setColor(it, 2, ColorSettings(id = "COLOR-3", imported = ImportedLUT("effect-lut-0123456789abcdef.cube", "Teal and orange", LUTInput.Rec709), amount = 0.65)) }
                .join(1, transition("T-1", TransitionStyle.Split, 2.0))
                .build(),

            // Sound on both tracks: with fades and a level, moved, trimmed at both ends, one running past the end of the picture.
            Builder("sound-tracks").take(6.0).take(6.0).let { builder ->
                val music = builder.sound(30.0)
                val voice = builder.sound(4.0)
                builder
                    .step { TimelineEditing.addAudio(it, music, 30.0, 0.0) }
                    .step { TimelineEditing.addAudio(it, voice, 4.0, 1.0) }
                    .step { state -> TimelineEditing.setAudioItemSound(state, state.audio!!.first { it.sourceID == music }.id, ClipAudio(volumeDB = -12.0, fadeIn = 1.0, fadeOut = 2.0)) }
                    .step { state -> TimelineEditing.trimAudio(state, state.audio!!.first { it.sourceID == music }.id, 2.5, leading = true) }
                    .step { state -> TimelineEditing.moveAudio(state, state.audio!!.first { it.sourceID == voice }.id, 6.5) }
                    .step { state -> TimelineEditing.trimAudio(state, state.audio!!.first { it.sourceID == voice }.id, 9.75, leading = false) }
                    .step { TimelineEditing.addAudio(it, voice, 4.0, 11.0) }
                    .step { TimelineEditing.setAudio(it, 0, ClipAudio(volumeDB = 6.0, fadeIn = 0.5, fadeOut = 0.75)) }
            }.build(),

            // A clip's sound unlinked from its picture and left behind by a trim; another clip with noise reduction.
            Builder("unlinked-sound").take(6.0).take(6.0).take(6.0)
                .step { TimelineEditing.setAudio(it, 1, ClipAudio(volumeDB = -3.0, fadeOut = 0.5)) }
                .step { AudioUnlinking.unlink(it, 1) }
                .step { TimelineEditing.trim(it, 0, 1.5, 6.0, 6.0) }
                .step { TimelineEditing.setAudio(it, 2, ClipAudio(denoise = 0.8)) }
                .join(2, transition("T-1", TransitionStyle.Dissolve))
                .build(),

            // Ranges taken out so that pieces one frame long are left, with a sound cut by the same ranges.
            Builder("range-one-frame").take(10.0).take(10.0).let { builder ->
                val music = builder.sound(20.0)
                builder
                    .step { TimelineEditing.addAudio(it, music, 20.0, 0.0) }
                    .step { RangeEditing.delete(it, EditScope.Range(TimeRange(2.0, 3.0)))?.state }
                    // What is left between the two ranges is one frame of the first take.
                    .step { RangeEditing.delete(it, EditScope.Range(TimeRange(2.0 + RangeEditing.FRAME, 4.0)))?.state }
                    .step { RangeEditing.delete(it, EditScope.Range(TimeRange(1.0, 2.0)))?.state }
                    // And one frame at the very start of the second take.
                    .step { state -> RangeEditing.delete(state, EditScope.Range(TimeRange(TimelineEditing.clipStart(state.clips, state.clips.size - 1) + RangeEditing.FRAME, state.duration - 1.0)))?.state }
            }.build(),

            // Takes recorded at a chosen place: they are a step of the history, and the ledger does not replay them.
            Builder("inserted-takes").take(6.0).take(6.0)
                .step { TimelineEditing.split(it, 2.0) }
                .also { it.project = it.project.beginRecordingInsertion(2.0) }
                .take(1.5).take(2.5)
                .also { it.project = it.project.endRecordingInsertion() }
                .take(3.0)
                .step { TimelineEditing.delete(it, 0) }
                .build(),

            // Everything at once.
            Builder("everything").take(9.0).take(7.0).take(8.0).take(5.0).let { builder ->
                val music = builder.sound(40.0)
                builder
                    .join(0, transition("T-opening", TransitionStyle.FadeColor, 0.5))
                    .join(1, transition("T-1", TransitionStyle.Still).copy(stillDissolve = 0.4))
                    .join(2, transition("T-2", TransitionStyle.Whip, TransitionDefaults.WHIP_DURATION))
                    .join(3, transition("T-3", TransitionStyle.Dissolve).copy(audioOffset = -0.3, audioBlend = 1.2))
                    .join(4, transition("T-close", TransitionStyle.Dissolve, 0.75))
                    .step { TimelineEditing.setSpeed(it, 1, 1.5) }
                    .step { TimelineEditing.setCrop(it, 2, CropSettings(id = "CROP-1", zoom = 1.3)) }
                    .step { TimelineEditing.setColor(it, 3, ColorSettings(id = "COLOR-1", look = ColorLook.Warm)) }
                    .step { TimelineEditing.setText(it, 0, title.copy(start = 1.0, end = 4.0)) }
                    .step { TimelineEditing.addAudio(it, music, 40.0, 0.5) }
                    .step { TimelineEditing.setAudio(it, 2, ClipAudio(denoise = 0.5, volumeDB = 3.0)) }
                    .step { SubtitleEditing.install(it, mapOf("S1" to words("one two three four five six", 1.0, 0.7), "S4" to words("the end", 2.0, 0.8)), "en") }
                    .step { TimelineEditing.split(it, 3.0) }
            }.build(),
        ) + if (withLong) listOf(long()) else emptyList()
    }

    /** Two thousand clips and three hundred steps: what a long project costs to lay out, plan, save and read. */
    private fun long(): Golden {
        val builder = Builder("long")
        // Twenty takes of fifty seconds, each cut into a hundred pieces of half a second.
        repeat(20) { builder.take(50.0) }
        builder.step { state ->
            state.copy(clips = state.clips.flatMapIndexed { take, clip -> List(100) { piece -> clip.copy(id = "L-$take-$piece", start = piece * 0.5, end = piece * 0.5 + 0.5) } })
        }
        var seed = 12345L
        fun next(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
        var applied = 0
        var tried = 0
        while (applied < 298 && tried < 5000) {
            tried++
            val state = builder.edit
            val index = next(state.clips.size - 1)
            val edited = when (next(6)) {
                0 -> TimelineEditing.setJoin(state, index + 1, transition("T-$tried", TransitionStyle.entries[next(9)], 0.2))
                1 -> TimelineEditing.setText(state, index, TextOverlay(id = "TITLE-$tried", text = "Title $tried", start = state.clips[index].start, end = state.clips[index].end))
                2 -> TimelineEditing.setSpeed(state, index, listOf(0.5, 1.5, 2.0)[next(3)])
                3 -> TimelineEditing.move(state, index, next(state.clips.size))
                4 -> TimelineEditing.setAudio(state, index, ClipAudio(volumeDB = -6.0, fadeIn = 0.1))
                else -> TimelineEditing.toggleBookmark(state, index, state.clips[index].start + 0.2)
            } ?: continue
            if (edited.clips == state.clips) continue
            builder.step { edited }
            applied++
        }
        check(applied == 298) { "long: only $applied edits applied" }
        return builder.build(large = true)
    }
}
