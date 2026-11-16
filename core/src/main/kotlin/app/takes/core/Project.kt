package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun newID(): String = UUID.randomUUID().toString().uppercase()

internal val documentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class ProjectException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    enum class Reason(val message: String) {
        InvalidDocument("This project has unsupported or invalid editing data. Its files have been preserved."),
        MissingMedia("A source file is missing. The project has been preserved."),
        InvalidMedia("The recording could not be opened as a valid video. Its file has been preserved."),

        /** Not damage: the project uses something this version does not know. It must never be replaced by an older revision. */
        NewerVersion("This project was saved by a newer version of Takes. Update the app to open it. Its files have been preserved."),
    }
}

private fun invalid(): Nothing = throw ProjectException(ProjectException.Reason.InvalidDocument)

@Serializable
data class CaptureEvent(
    val id: String = newID(),
    val time: Double,
    val kind: String,
    val detail: String,
) {
    companion object {
        fun recovered(events: List<CaptureEvent>, duration: Double): List<CaptureEvent> =
            events.filter { it.time.isFinite() && it.time >= 0 && it.time < duration }
    }
}

/** Immutable recorded or imported media. An edit never rewrites it. */
@Serializable
data class SourceMedia(
    val id: String = newID(),
    val filename: String,
    val duration: Double,
    val recordedAt: Long = System.currentTimeMillis(),
    val events: List<CaptureEvent>? = null,
    val sha256: String? = null,
    val audioOnly: Boolean? = null,
    val originalName: String? = null,
)

/** The name the saved transition had while it knew two styles. */
typealias ClipTransition = TransitionSettings

/** A title over the picture. Times are source seconds, so it follows its footage through trims and moves. */
@Serializable
data class TextOverlay(
    val id: String = newID(),
    val text: String = "Your text",
    val start: Double = 0.0,
    val end: Double = 3.0,
    val x: Double = 0.5,
    val y: Double = 0.5,
    // Font size relative to the shorter canvas dimension; consistent on rotation.
    val size: Double = 0.065,
    /**
     * Null draws the title as before designs existed: bold white letters with a dark outline.
     * Only titles of older projects have none; a new one is made by [fresh] with the default design.
     */
    val appearance: TitleDesign? = null,
    /** How it arrives and leaves. Null stays still. */
    val motion: TitleMotion? = null,
) {
    val valid: Boolean
        get() = start.isFinite() && end.isFinite() && start >= 0 && end > start && text.length <= 500 &&
            x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && size.isFinite() && size in 0.02..0.3 &&
            appearance?.valid != false && motion?.valid != false

    /** True when the title uses a design or a motion, which readers before schema 12 would draw wrongly. */
    val styled: Boolean get() = appearance != null || motion != null

    /** What the design tools start from: the title's own design, or the look of a title without one. */
    val design: TitleDesign get() = appearance ?: TitleDesign.plain

    companion object {
        /**
         * A title as the tools make one: nothing written yet, in the original's
         * default design. The design is saved with it, which is what tells it
         * from a title of an older project: that one has none, keeps its
         * outlined look and is written back exactly as it was read.
         */
        fun fresh(): TextOverlay = TextOverlay(text = "", appearance = TitleDesign())
    }
}

/** A mark on the footage, at a source second. It follows its clip through trims, splits and moves. */
@Serializable
data class Bookmark(val id: String = newID(), val time: Double, val label: String? = null) {
    val valid: Boolean get() = time.isFinite() && time >= 0 && (label?.length ?: 0) <= 100
}

/**
 * How a clip's own sound is played: level, mute, and a fade at each end. Named as in the original app.
 *
 * The level is chosen on the original's grid ([AudioGainGrid]: half decibels
 * from −40 to +12) and, as there, any level from −60 dB is a valid saved one:
 * a project made before the grid keeps the level it has until the level is
 * chosen again.
 *
 * A fade is the original's [AudioEdgeFade]: [fadeIn] and [fadeOut] are its
 * whole length in seconds of the movie, from silence to the full level, and
 * may be as long as the item; [fadeInElapsed] and [fadeOutElapsed] are how
 * much of it lies behind the item's edge after a cut inside it, so the piece
 * goes on from the level and with the slope the fade had there. They are
 * saved only when a fade has been cut, which keeps every older project's
 * file as it was.
 */
@Serializable
data class ClipAudio(
    val volumeDB: Double = 0.0,
    val muted: Boolean = false,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    /** Noise reduction: how much of the cleaned sound replaces the recorded one, 0–1. Clips only. */
    val denoise: Double = 0.0,
    /** Seconds of [fadeIn] already over where the item begins. Null for a fade that starts from silence. */
    val fadeInElapsed: Double? = null,
    /** Seconds of [fadeOut] that lie past where the item ends. Null for a fade that reaches silence. */
    val fadeOutElapsed: Double? = null,
) {
    val gain: Double get() = if (muted) 0.0 else Math.pow(10.0, volumeDB / 20)
    val valid: Boolean
        get() = volumeDB.isFinite() && volumeDB in MINIMUM_DB..MAXIMUM_DB && denoise.isFinite() && denoise in 0.0..1.0 &&
            fadeIn.isFinite() && fadeOut.isFinite() && fadeIn >= 0 && fadeOut >= 0 && fadeIn <= 604800 && fadeOut <= 604800 &&
            (fadeInElapsed == null || fadeInElapsed >= 0 && fadeInElapsed < fadeIn) &&
            (fadeOutElapsed == null || fadeOutElapsed >= 0 && fadeOutElapsed < fadeOut)

    /** The two fades as the original keeps them. */
    val fades: AudioEdgeFades
        get() = AudioEdgeFades(
            fadeIn.takeIf { it > 0 }?.let { AudioEdgeFade(it, fadeInElapsed ?: 0.0) },
            fadeOut.takeIf { it > 0 }?.let { AudioEdgeFade(it, fadeOutElapsed ?: 0.0) },
        )

    /** The same sound with [fades] at its ends. A fade that was never cut is saved as its length alone. */
    fun withFades(fades: AudioEdgeFades): ClipAudio = copy(
        fadeIn = fades.fadeIn?.seconds ?: 0.0, fadeInElapsed = fades.fadeIn?.elapsed?.takeIf { it > 0 },
        fadeOut = fades.fadeOut?.seconds ?: 0.0, fadeOutElapsed = fades.fadeOut?.elapsed?.takeIf { it > 0 },
    )

    /**
     * The sound of the piece from [from] to [to] seconds of an item [length]
     * seconds long: a cut inside a fade leaves the piece the rest of it, at
     * the level and with the slope it had at the cut, and a cut past a fade
     * leaves the piece without it (TrackTimeline.swift:196-202).
     */
    fun sliced(from: Double, to: Double, length: Double): ClipAudio =
        if (fadeIn <= 0 && fadeOut <= 0) this else withFades(fades.sliced(EditInterval.of(from, to), EditTime.of(length)))

    /**
     * The sound after its item was made [amount] seconds longer at its start
     * ([leading]) or its end: a fade cut there gets that much of itself back
     * (TrackTimeline.swift:1650-1655).
     */
    fun extended(amount: Double, leading: Boolean): ClipAudio {
        val elapsed = (if (leading) fadeInElapsed else fadeOutElapsed) ?: return this
        val left = maxOf(0.0, elapsed - amount).takeIf { it > 0 }
        return if (leading) copy(fadeInElapsed = left) else copy(fadeOutElapsed = left)
    }

    /** The sound when its item goes from [oldLength] to [newLength] seconds without being cut: a fade is squeezed into a shorter item, never stretched (TrackTimeline.swift:1282-1283). */
    fun fitted(oldLength: Double, newLength: Double): ClipAudio =
        if (fadeIn <= 0 && fadeOut <= 0) this else withFades(fades.fitted(EditTime.of(oldLength), EditTime.of(newLength)))

    /**
     * True when an item [length] seconds long plays these fades in a way
     * readers before schema 15 would not: they knew nothing of a fade cut by
     * a split, and held each fade to half its item.
     */
    fun reshaped(length: Double): Boolean =
        fadeInElapsed != null || fadeOutElapsed != null || fadeIn > length / 2 || fadeOut > length / 2

    companion object {
        const val MINIMUM_DB = -60.0
        const val MAXIMUM_DB = 12.0
    }
}

/**
 * How a transition was fitted to the footage around it, saved beside it as
 * the original saves it: [factor] scales its picture and sound together,
 * 1 when it plays as entered, less when its clips had less footage than it
 * asks for. A saved fit is kept by every edit except those that name it
 * ([TimelineEditing]); it is never worked out again from the clips. Where the
 * transition starts is not saved: clips follow one another here, so it
 * follows from their order and the factors.
 */
@Serializable
data class TransitionFit(val factor: Double = 1.0) {
    val valid: Boolean get() = factor.isFinite() && factor in 0.0..1.0
}

@Serializable
data class TimelineClip(
    val id: String = newID(),
    val sourceID: String,
    val start: Double,
    val end: Double,
    /** The join to the clip after this one. On the last clip, with no next clip named, the close of the timeline. */
    val transition: TransitionSettings? = null,
    /** The transition into this clip from nothing. It counts on the first clip only. */
    val openingTransition: TransitionSettings? = null,
    val texts: List<TextOverlay>? = null,
    val bookmarks: List<Bookmark>? = null,
    /** Null plays the sound as recorded. */
    val audio: ClipAudio? = null,
    /** Null shows the picture as recorded. */
    val color: ColorSettings? = null,
    /** Null fits the whole picture inside the movie's frame; a crop fills the frame with part of it. */
    val crop: CropSettings? = null,
    /** Null plays at the recorded speed. */
    val speed: SpeedSettings? = null,
    /** What is said in this clip's sound, in source seconds, so it follows the footage. Null when not transcribed. */
    val subtitleWords: List<SubtitleWord>? = null,
    /**
     * The fit of [transition]. Null while it has not been fitted: a project saved before fits were, whose
     * transitions are laid out by [TransitionLayout.sequence]'s own rule until an edit that moves footage saves them.
     */
    val transitionFit: TransitionFit? = null,
    /** The fit of [openingTransition]. */
    val openingTransitionFit: TransitionFit? = null,
    /**
     * The clip's video effects in the order they were applied, as the original saves them
     * (YouRec/Project.swift:58). Ranges and keyframes are in source seconds, so both pieces of a split
     * keep the list. Null when the clip has none; effects of a later version are left out when read.
     */
    @Serializable(with = SupportedVideoEffects::class)
    val videoEffects: List<VideoEffectSettings>? = null,
    /**
     * A held frame: the clip shows one frame of its source for the hold's
     * duration, as the original's Freeze Frame inserts it
     * (YouRec/Project.swift:59). Null for footage that plays.
     */
    val frozenFrame: FrozenFrameSegment? = null,
) {
    /** How fast the clip plays by its own speed setting: 2 is twice as fast and takes half the time. */
    val rate: Double get() = SpeedMapping.rate(speed)

    /**
     * Whether the clip's playback is more than one stretch at [rate]: a held
     * frame, or an Undercranking or Step Printing that changes its speed.
     */
    val timed: Boolean get() = frozenFrame != null || VideoEffectTiming.retimes(videoEffects, start, end)

    /**
     * What the clip plays, as source stretches each at one rate: the clip's
     * speed times that of its Undercranking and Step Printing
     * ([VideoEffectTiming.playbackSpans]). A held frame is one stretch from
     * [start] to [end] taking the hold's duration.
     */
    val playbackSpans: List<EffectPlaybackSpan>
        get() = frozenFrame?.let { listOf(EffectPlaybackSpan(start, end, (end - start) / it.duration)) }
            ?: if (!timed) listOf(EffectPlaybackSpan(start, end, rate))
            else VideoEffectTiming.playbackSpans(videoEffects.orEmpty(), start, end, speed, listOf(SourceInterval(start, end)))

    /** Seconds the clip takes on the timeline. [start] and [end] are source seconds. */
    val duration: Double get() = frozenFrame?.duration ?: if (!timed) (end - start) / rate else SpeedMapping.duration(playbackSpans)

    /** The source second showing [offset] timeline seconds into the clip: the held one on a held frame. */
    fun sourceTime(offset: Double): Double = frozenFrame?.sourceTime
        ?: if (!timed) start + offset * rate else SpeedMapping.sourceTime(offset, playbackSpans, end)

    /** How far into the clip, in timeline seconds, a source second shows. */
    fun offset(sourceTime: Double): Double = frozenFrame?.let { SpeedMapping.timelineTime(sourceTime, it) }
        ?: if (!timed) (sourceTime - start) / rate else SpeedMapping.timelineTime(sourceTime, playbackSpans)

    /** The oldest schema whose readers understand everything this clip uses. */
    val schema: Int get() = when {
        // Readers before 19 knew no held frame and played the time effects at the clip's own speed.
        frozenFrame != null || videoEffects.orEmpty().any { it.kind in VideoEffectTiming.TIME_EFFECTS } -> TIME_EFFECTS_SCHEMA
        !videoEffects.isNullOrEmpty() -> 16
        audio?.reshaped(duration) == true -> 15
        crop?.reframed == true -> 14
        openingTransition != null || transition?.legacy == false -> 13
        texts.orEmpty().any { it.styled } -> 12
        !subtitleWords.isNullOrEmpty() -> 11
        speed != null -> 9
        crop != null -> 8
        color != null -> 7
        (audio?.denoise ?: 0.0) > 0 -> 6
        audio != null -> 4
        !bookmarks.isNullOrEmpty() -> 3
        transition != null || !texts.isNullOrEmpty() -> 2
        else -> 1
    }

    companion object {
        /** The schema of a held frame and of the time effects that change how a clip plays. */
        const val TIME_EFFECTS_SCHEMA = 19
    }
}

/**
 * One edit of the timeline. [captureBase] is runtime provenance: how many
 * captured takes the state had seen when it was read from the project.
 */
@Serializable
data class EditState(
    val clips: List<TimelineClip> = emptyList(),
    val captureBase: Int? = null,
    /** Sound placed on its own tracks, such as music or a voice-over. Null when there is none. */
    val audio: List<AudioItem>? = null,
    /** How the captions look and what was transcribed. Null when the project has no subtitles; the words are on the clips. */
    val subtitles: SubtitleSettings? = null,
    /**
     * The edit itself when it is a track timeline that no sequence of clips
     * can be: pictures over one another, time with nothing in it
     * ([TrackEditState]). Then [clips], [audio] and [subtitles] are only a
     * view of it, and the editing functions run on it directly or say they
     * are not available. Null for every edit that is a sequence, which is
     * then written as it always was.
     */
    @SerialName("timeline")
    val layered: TrackTimeline? = null,
) {
    /** True when the edit is a sequence of clips: every editing function is available, as it always was. */
    val isSequence: Boolean get() = layered == null

    /** The movie is as long as its picture; sound past the last clip is not played. A layered edit is as long as its timeline. */
    val duration: Double get() = layered?.duration?.seconds ?: TimelineEditing.duration(clips)
}

/**
 * A piece of sound at a fixed place on the timeline, independent of the
 * clips: editing the picture does not move it. Two tracks; items on the same
 * track never overlap.
 */
@Serializable
data class AudioItem(
    val id: String = newID(),
    val sourceID: String,
    /** Timeline second where it begins. */
    val start: Double,
    val sourceStart: Double,
    val sourceEnd: Double,
    val track: Int = 0,
    val audio: ClipAudio? = null,
) {
    val duration: Double get() = sourceEnd - sourceStart
    val end: Double get() = start + duration

    companion object {
        const val TRACKS = 2
    }
}

/**
 * Immutable edit payload. Capture is journaled independently, so a new take
 * never rewrites a historical snapshot or becomes an accidental Undo step.
 *
 * A step is written in the form it has (docs/TRACKS-PLAN.md, 3.1). One read
 * from an older project, or made before the timeline was saved, has no
 * [timeline] and is written as a sequence of clips, `{"state":{"clips":…}}`.
 * One committed since is written as the original's track timeline alone,
 * `{"state":{"timeline":…}}`, and [state] is that timeline read as the
 * sequence the rest of the app edits ([TrackSequenceForm.sequence]), or,
 * when it is not one (pictures over one another, gaps), the timeline itself
 * with its view ([TrackEditState]).
 *
 * A step read from disk keeps the bytes it was read from, and one made here
 * the bytes it was first encoded to: [data] never encodes a step twice, and a
 * step is saved again under the name it was read from, whatever this version
 * would write for it.
 */
@Serializable(with = EditSnapshotSerializer::class)
data class EditSnapshot(val state: EditState, val captureCursor: Int, val timeline: TrackTimeline? = null) {
    @Volatile private var encoded: ByteArray? = null

    /** The sources and the capture ledger this step was last found valid with ([Project.validate]), by identity. */
    @Volatile internal var checked: Pair<List<SourceMedia>, List<TimelineClip>>? = null

    fun data(): ByteArray = encoded ?: documentJson.encodeToString(serializer(), this).toByteArray().also { encoded = it }

    @Volatile private var named: String? = null

    /** The name of the history file that holds [data]: the hash of its bytes. */
    fun filename(): String = named ?: (sha256(data()) + ".json").also { named = it }

    /** The schema number a project needs once it holds this step, when it is a committed one: a timeline's or a layered timeline's, or its clips' when higher. */
    val schema: Int get() = maxOf(
        if (state.layered != null) TrackTimeline.LAYERED_SCHEMA else TrackTimeline.SCHEMA,
        // A held frame or a time effect on a clip ([TimelineClip.TIME_EFFECTS_SCHEMA]).
        (timeline?.video?.map { it.content } ?: state.clips).maxOfOrNull { it.schema } ?: 0,
    )

    companion object {
        /** A step that saves [timeline], with [state] the edit it is ([TrackEditState.edit]): the sequence it reads back as, or the timeline itself. */
        fun of(timeline: TrackTimeline, captureCursor: Int): EditSnapshot = EditSnapshot(TrackEditState.edit(timeline), captureCursor, timeline)

        /**
         * The step [state] is saved as from now on: [timeline], its conversion,
         * with [state] as that timeline shows it. A sequence whose timeline
         * does not read back as a sequence, which no conversion makes, keeps
         * the sequence form rather than being refused. An edit that is a
         * timeline ([EditState.layered]) is saved as its timeline.
         */
        fun committed(state: EditState, captureCursor: Int, timeline: TrackTimeline = saved(state).toTimeline()): EditSnapshot =
            if (!state.isSequence) of(timeline, captureCursor)
            else TrackSequenceForm.sequence(timeline)?.let { EditSnapshot(it, captureCursor, timeline) } ?: EditSnapshot(saved(state), captureCursor)

        /** What of [state] a step saves: not the takes it had seen, and no empty list of sound. */
        internal fun saved(state: EditState): EditState =
            EditState(state.clips, audio = state.audio?.ifEmpty { null }, subtitles = state.subtitles, layered = state.layered)

        /** The step saved as [bytes], which it keeps. */
        internal fun decoded(bytes: ByteArray): EditSnapshot =
            documentJson.decodeFromString(serializer(), bytes.decodeToString()).also { it.encoded = bytes }

        /** The step saved as [bytes] in the history file [name], which [Project.readManifest] has checked is their hash. */
        internal fun decoded(bytes: ByteArray, name: String): EditSnapshot = decoded(bytes).also { it.named = name }
    }
}

/** The two forms of a saved step, and the reading of either. */
internal object EditSnapshotSerializer : kotlinx.serialization.KSerializer<EditSnapshot> {
    @Serializable private class SequenceStep(val state: EditState, val captureCursor: Int)
    @Serializable private class TimelineState(val timeline: TrackTimeline)
    @Serializable private class TimelineStep(val state: TimelineState, val captureCursor: Int)

    /** What either form holds. A reader before the timeline sees a timeline step as an empty edit, which is why its schema is refused there. */
    @Serializable private class ReadState(
        val clips: List<TimelineClip> = emptyList(),
        val captureBase: Int? = null,
        val audio: List<AudioItem>? = null,
        val subtitles: SubtitleSettings? = null,
        val timeline: TrackTimeline? = null,
    )
    @Serializable private class ReadStep(val state: ReadState, val captureCursor: Int)

    override val descriptor = ReadStep.serializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: EditSnapshot) {
        val timeline = value.timeline
        if (timeline == null) SequenceStep.serializer().serialize(encoder, SequenceStep(value.state, value.captureCursor))
        else TimelineStep.serializer().serialize(encoder, TimelineStep(TimelineState(timeline), value.captureCursor))
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): EditSnapshot {
        val step = ReadStep.serializer().deserialize(decoder)
        val state = step.state
        val timeline = state.timeline
            ?: return EditSnapshot(EditState(state.clips, state.captureBase, state.audio, state.subtitles), step.captureCursor)
        // A timeline step holds the timeline alone.
        if (state.clips.isNotEmpty() || state.audio != null || state.subtitles != null) throw kotlinx.serialization.SerializationException("A step in two forms")
        return EditSnapshot.of(timeline, step.captureCursor)
    }
}

/** How a project is rendered to a movie. Saved with the project, outside undo. */
@Serializable
data class ExportSettings(
    val codec: Codec = Codec.H264,
    val resolution: Resolution = Resolution.Project,
    val frameRate: Int = 30,
) {
    @Serializable
    enum class Codec { @SerialName("H.264") H264, @SerialName("H.265") HEVC }

    @Serializable
    enum class Resolution(val long: Int, val short: Int) {
        @SerialName("Project size") Project(0, 0),
        @SerialName("720p") HD(1280, 720),
        @SerialName("1080p") FullHD(1920, 1080),
        @SerialName("4K") UltraHD(3840, 2160);

        /** The canvas scaled to fit this format in either orientation, with even dimensions. */
        fun size(width: Int, height: Int): Pair<Int, Int> {
            if (this == Project || width <= 0 || height <= 0) return width to height
            val scale = minOf(long.toDouble() / maxOf(width, height), short.toDouble() / minOf(width, height))
            fun even(dimension: Int) = maxOf(2, Math.round(dimension * scale / 2).toInt() * 2)
            return even(width) to even(height)
        }
    }

    val valid: Boolean get() = frameRate in FRAME_RATES

    companion object {
        val FRAME_RATES = listOf(24, 25, 30, 50, 60)
    }
}

@Serializable
private data class Manifest(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sources: List<SourceMedia>,
    val historyCursor: Int,
    val captureLedger: List<TimelineClip> = emptyList(),
    val snapshotFiles: List<String>,
    val exportSettings: ExportSettings = ExportSettings(),
    /** Indices into [captureLedger] of the takes that were inserted rather than appended. Absent when there are none. */
    val captureInsertions: List<Int>? = null,
    val recordingInsertion: Double? = null,
    val recordingInsertionStep: Int? = null,
)

data class Project(
    val title: String,
    val schemaVersion: Int = 1,
    val id: String = newID(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val sources: List<SourceMedia> = emptyList(),
    val snapshots: List<EditSnapshot> = listOf(EditSnapshot(EditState(), 0)),
    val captureLedger: List<TimelineClip> = emptyList(),
    val historyCursor: Int = 0,
    val exportSettings: ExportSettings = ExportSettings(),
    /**
     * Indices into [captureLedger] of the takes recorded at a chosen place.
     * Such a take lives in the edit step that inserted it, so replaying the
     * ledger skips it: Undo takes it off the timeline, and no other history
     * step gains it at the end.
     */
    val captureInsertions: Set<Int> = emptySet(),
    /** Timeline second where the next take goes; null appends it at the end. See [beginRecordingInsertion]. */
    val recordingInsertion: Double? = null,
    /** The history step holding the takes inserted so far in this run; the next take joins it while it is still the newest step. */
    val recordingInsertionStep: Int? = null,
) {
    private fun materialized(snapshot: EditSnapshot): EditState {
        val later = captureLedger.drop(snapshot.captureCursor)
        val appended = if (captureInsertions.isEmpty()) later else later.filterIndexed { index, _ -> snapshot.captureCursor + index !in captureInsertions }
        // A step that is a timeline gets the later takes where its picture ends, in front, without moving anything.
        if (!snapshot.state.isSequence) return TrackEditState.appending(snapshot.state, appended, captureLedger.size)
        return EditState(snapshot.state.clips + appended, captureLedger.size, snapshot.state.audio, snapshot.state.subtitles)
    }

    val edit: EditState get() = materialized(snapshots[historyCursor])
    val history: List<EditState> get() = snapshots.map(::materialized)
    /** Files an edit refers to that are not recordings, such as imported LUTs, from any step of the history. */
    val effectFiles: List<String>
        get() = snapshots.flatMap { it.state.clips }.plus(captureLedger).mapNotNull { it.color?.imported?.filename }.distinct().sorted()

    /** Every file the project owns and needs: its recordings and sounds, then what its edits refer to. */
    val mediaFiles: List<String> get() = sources.map { it.filename } + effectFiles
    /** The footage, without imported sound files. */
    val recordings: List<SourceMedia> get() = sources.filter { it.audioOnly != true }
    val capturedDuration: Double get() = recordings.sumOf { it.duration }
    val canUndo: Boolean get() = historyCursor > 0
    val canRedo: Boolean get() = historyCursor + 1 < snapshots.size

    fun renamed(title: String, now: Long = System.currentTimeMillis()): Project = copy(title = title, updatedAt = now)

    /**
     * False for a state read before a take was inserted: an inserted take is
     * not replayed from the ledger, so committing that state would silently
     * take it off the timeline. [commit] leaves the project unchanged then.
     */
    fun canCommit(state: EditState): Boolean {
        val base = state.captureBase ?: return true
        return captureInsertions.none { it >= base }
    }

    /**
     * A state built before a take arrived keeps its capture cursor, so the
     * newer takes are appended again rather than silently discarded.
     */
    fun commit(state: EditState, now: Long = System.currentTimeMillis()): Project {
        if (!canCommit(state)) return this
        val cursor = (state.captureBase ?: captureLedger.size).coerceIn(0, captureLedger.size)
        // Two edits are the same edit when they are the same timeline (docs/TRACKS-PLAN.md, 3.5).
        val timeline = EditSnapshot.saved(state).toTimeline()
        if (timeline == edit.toTimeline()) return this
        val step = EditSnapshot.committed(state, cursor, timeline)
        return copy(
            // Older readers must not silently drop what they do not know: from the first timeline step on, none of them reads the
            // project, from the first step that is no sequence, none that reads only sequences, and from the first held
            // frame or time effect, none before those.
            schemaVersion = maxOf(schemaVersion, step.schema),
            snapshots = snapshots.take(historyCursor + 1) + step,
            historyCursor = historyCursor + 1,
            updatedAt = now,
        )
    }

    /**
     * [timeline], made from the current edit's timeline (`edit.toTimeline()`),
     * as one edit: the sequence it is, or the timeline itself when it is no
     * sequence ([TrackEditState.edit]). Unchanged when it is the current edit.
     */
    fun commit(timeline: TrackTimeline, now: Long = System.currentTimeMillis()): Project =
        commit(timeline.toEdit(edit.captureBase), now)

    /** The end of a Move ([TrackMoveSession.done]): one Undo step for [TrackMoveSession.Outcome.Moved], none otherwise. */
    fun commit(outcome: TrackMoveSession.Outcome, now: Long = System.currentTimeMillis()): Project =
        if (outcome is TrackMoveSession.Outcome.Moved) commit(outcome.timeline, now) else this

    /**
     * Adds media that is not a take, such as an imported sound file. It joins
     * the project's files without appearing on the timeline by itself.
     */
    fun addSource(source: SourceMedia, now: Long = System.currentTimeMillis()): Project =
        copy(sources = sources + source, updatedAt = now)

    /**
     * The takes recorded from now on go into the timeline at [at] instead of
     * at the end, one after another, as one Undo step. Saved with the project,
     * so a run of takes continues after the app restarts. A time that is not a
     * number changes nothing.
     */
    fun beginRecordingInsertion(at: Double): Project =
        if (!at.isFinite()) this else copy(recordingInsertion = at.coerceIn(0.0, MAX_DURATION), recordingInsertionStep = null)

    /** Later takes are appended at the end again. */
    fun endRecordingInsertion(): Project = copy(recordingInsertion = null, recordingInsertionStep = null)

    /**
     * Recording additions are outside editor Undo: every history step gains
     * the take. A take recorded at a chosen place ([beginRecordingInsertion])
     * is an edit step instead, and the footage stays in [sources] when it is undone.
     */
    fun addRecording(source: SourceMedia, now: Long = System.currentTimeMillis(), gainDB: Double = 0.0): Project {
        // Marks made while recording arrive on the take's clip.
        val marks = source.events.orEmpty().filter { it.kind == BOOKMARK_EVENT }.map { Bookmark(id = it.id, time = it.time) }.ifEmpty { null }
        // The recording gain the take was made with is its clip's level: the file itself is never changed.
        val level = RecordingGain.normalized(gainDB)
        val clip = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration, bookmarks = marks, audio = if (level != 0.0) ClipAudio(volumeDB = level) else null)
        if (recordingInsertion != null) {
            // The takes of one run share a step while it is still the newest one. After an
            // Undo or another edit, the next take begins a step of its own on what is there now.
            val continuing = recordingInsertionStep == historyCursor && historyCursor > 0 && historyCursor == snapshots.lastIndex
            val current = edit
            val next = TimelineEditing.insert(current, listOf(clip), recordingInsertion)
                ?: if (current.isSequence) current.copy(clips = current.clips + clip) else TrackEditState.appending(current, listOf(clip), current.captureBase)
            // Its cursor is past the take: the ledger never replays what the step already holds.
            val snapshot = EditSnapshot.committed(next, captureLedger.size + 1)
            val step = if (continuing) historyCursor else historyCursor + 1
            return copy(
                // An edit step, saved as a timeline like every other.
                schemaVersion = maxOf(schemaVersion, snapshot.schema),
                sources = sources + source,
                captureLedger = captureLedger + clip,
                captureInsertions = captureInsertions + captureLedger.size,
                snapshots = snapshots.take(step) + snapshot,
                historyCursor = step,
                // The next take follows this one.
                recordingInsertion = snapshot.state.layered?.let { timeline -> timeline.video.firstOrNull { it.id == clip.id }?.end?.seconds ?: snapshot.state.duration }
                    ?: snapshot.state.clips.let { TimelineEditing.clipStart(it, it.indexOfFirst { item -> item.id == clip.id } + 1) },
                recordingInsertionStep = step,
                updatedAt = now,
            )
        }
        return copy(
            schemaVersion = maxOf(schemaVersion, clip.schema),
            sources = sources + source,
            captureLedger = captureLedger + clip,
            updatedAt = now,
        )
    }

    fun undo(now: Long = System.currentTimeMillis()): Project =
        if (canUndo) copy(historyCursor = historyCursor - 1, updatedAt = now) else this

    fun redo(now: Long = System.currentTimeMillis()): Project =
        if (canRedo) copy(historyCursor = historyCursor + 1, updatedAt = now) else this

    fun validate() {
        if (schemaVersion !in 1..LATEST_READABLE_SCHEMA) invalid()
        if (snapshots.isEmpty() || historyCursor !in snapshots.indices) invalid()
        if (!exportSettings.valid) invalid()
        if (snapshots.any { it.captureCursor !in 0..captureLedger.size }) invalid()
        val byID = sources.associateBy { it.id }
        if (byID.size != sources.size) invalid()
        if (sources.map { it.filename.lowercase() }.toSet().size != sources.size) invalid()
        if (captureLedger.any { it.sourceID !in byID }) invalid()
        if (captureInsertions.any { it !in captureLedger.indices }) invalid()
        if (recordingInsertion != null && (!recordingInsertion.isFinite() || recordingInsertion < 0 || recordingInsertion > MAX_DURATION)) invalid()
        if (recordingInsertionStep != null && recordingInsertionStep !in snapshots.indices) invalid()
        for (source in sources) {
            val name = source.filename
            if (!source.duration.isFinite() || source.duration <= 0 || source.duration > MAX_DURATION) invalid()
            if ((source.originalName?.length ?: 0) > 255) invalid()
            if (name.isEmpty() || name != File(name).name || name.startsWith(".") || name.contains("\\")) invalid()
            if (name.lowercase() in RESERVED_NAMES) invalid()
            source.sha256?.let { hash -> if (hash.length != 64 || hash.any { it !in HEX }) invalid() }
            if (source.events.orEmpty().any { !it.time.isFinite() || it.time < 0 || it.time > source.duration + 1 }) invalid()
        }
        // A step found valid with these very sources and takes is not checked again: a commit checks the step it adds.
        for (snapshot in snapshots) {
            val with = snapshot.checked
            if (with != null && with.first === sources && with.second === captureLedger) continue
            val state = materialized(snapshot)
            if (!state.duration.isFinite() || state.duration > MAX_DURATION) invalid()
            // A step that is a timeline keeps the original's rules; its clips and sound items, a view of it, are checked as any.
            state.layered?.let { if (TrackInvariants.violation(it, sources) != null) invalid() }
            if (state.clips.map { it.id }.toSet().size != state.clips.size) invalid()
            if (state.subtitles?.valid == false) invalid()
            val sound = state.audio.orEmpty()
            if (sound.map { it.id }.toSet().size != sound.size) invalid()
            for (item in sound) {
                val source = byID[item.sourceID] ?: invalid()
                if (!item.start.isFinite() || item.start < 0 || item.start > MAX_DURATION || item.track !in 0 until AudioItem.TRACKS) invalid()
                if (!item.sourceStart.isFinite() || !item.sourceEnd.isFinite() || item.sourceStart < 0 ||
                    item.sourceEnd <= item.sourceStart || item.sourceEnd > source.duration + 0.001) invalid()
                if (item.audio?.valid == false) invalid()
                if (sound.any { it !== item && it.track == item.track && it.start < item.end && item.start < it.end }) invalid()
            }
            for (clip in state.clips) {
                val source = byID[clip.sourceID] ?: invalid()
                if (!clip.start.isFinite() || !clip.end.isFinite() || clip.start < 0 ||
                    clip.end <= clip.start || clip.end > source.duration + 0.001) invalid()
                if (clip.transition?.valid == false || clip.openingTransition?.valid == false) invalid()
                if (clip.transitionFit?.valid == false || clip.openingTransitionFit?.valid == false) invalid()
                val texts = clip.texts.orEmpty()
                if (texts.map { it.id }.toSet().size != texts.size) invalid()
                if (texts.any { !it.valid || it.end > source.duration + 0.001 }) invalid()
                if (clip.audio?.valid == false || clip.color?.valid == false || clip.crop?.valid == false || clip.speed?.valid == false || clip.frozenFrame?.valid == false) invalid()
                val marks = clip.bookmarks.orEmpty()
                if (marks.map { it.id }.toSet().size != marks.size) invalid()
                if (marks.any { !it.valid || it.time > source.duration + 0.001 }) invalid()
                val words = clip.subtitleWords.orEmpty()
                if (words.map { it.id }.toSet().size != words.size) invalid()
                if (words.any { !it.valid || it.end > source.duration + 0.001 }) invalid()
            }
            snapshot.checked = sources to captureLedger
        }
    }

    /** Content-addressed history: unchanged snapshots are never rewritten. */
    fun manifest(write: (name: String, data: ByteArray) -> Unit): ByteArray {
        val names = snapshots.map { snapshot -> snapshot.filename().also { write(it, snapshot.data()) } }
        val manifest = Manifest(schemaVersion, id, title, createdAt, updatedAt, sources, historyCursor, captureLedger, names, exportSettings,
            captureInsertions.sorted().ifEmpty { null }, recordingInsertion, recordingInsertionStep)
        return documentJson.encodeToString(Manifest.serializer(), manifest).toByteArray()
    }

    companion object {
        const val LATEST_READABLE_SCHEMA = TimelineClip.TIME_EFFECTS_SCHEMA
        const val BOOKMARK_EVENT = "bookmark"
        private const val MAX_DURATION = 604800.0
        private const val HEX = "0123456789abcdef"
        private val RESERVED_NAMES = setOf("project.json", "history", "recovery", "cache", "exports")

        fun isSnapshotName(name: String): Boolean =
            name.length == 69 && name.endsWith(".json") && name.take(64).all { it in HEX }

        /**
         * Whether [data] is a manifest written by a later version: a document
         * that says so itself, with a schema number above the ones read here.
         * Anything that cannot be read as that is not newer, it is damaged.
         */
        fun isNewer(data: ByteArray): Boolean = (schemaOf(data) ?: 0) > LATEST_READABLE_SCHEMA

        /** The schema number a manifest says it has, or null when [data] is not one that says. */
        fun schemaOf(data: ByteArray): Int? = try {
            val schema = (documentJson.parseToJsonElement(data.decodeToString()) as? kotlinx.serialization.json.JsonObject)
                ?.get("schemaVersion") as? kotlinx.serialization.json.JsonPrimitive
            schema?.content?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

        fun readManifest(data: ByteArray, load: (name: String) -> ByteArray): Project {
            if (isNewer(data)) throw ProjectException(ProjectException.Reason.NewerVersion)
            val manifest = try {
                documentJson.decodeFromString(Manifest.serializer(), data.decodeToString())
            } catch (error: Exception) {
                throw ProjectException(ProjectException.Reason.InvalidDocument, error)
            }
            if (manifest.snapshotFiles.any { !isSnapshotName(it) }) invalid()
            val snapshots = manifest.snapshotFiles.map { name ->
                val payload = try { load(name) } catch (error: Exception) {
                    throw ProjectException(ProjectException.Reason.InvalidDocument, error)
                }
                if (sha256(payload) + ".json" != name) invalid()
                try {
                    EditSnapshot.decoded(payload, name)
                } catch (error: Exception) {
                    throw ProjectException(ProjectException.Reason.InvalidDocument, error)
                }
            }
            return Project(
                title = manifest.title, schemaVersion = manifest.schemaVersion, id = manifest.id,
                createdAt = manifest.createdAt, updatedAt = manifest.updatedAt, sources = manifest.sources,
                snapshots = snapshots, captureLedger = manifest.captureLedger, historyCursor = manifest.historyCursor,
                exportSettings = manifest.exportSettings,
                captureInsertions = manifest.captureInsertions.orEmpty().toSet(),
                recordingInsertion = manifest.recordingInsertion, recordingInsertionStep = manifest.recordingInsertionStep,
            ).also { it.validate() }
        }
    }
}
