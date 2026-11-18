package app.takes.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * Effects that apply to part of a clip, or across several pieces as one
 * application, as the original keeps them since 57e5d91: grades over parts of
 * a clip, the windows of a recipe, framing variants, the active range of a
 * crop, a flip or a stabilisation, and the clock of a piece of a title.
 * Every time here is in source seconds, so the windows travel with their
 * footage through cuts and moves (YouRec/Project.swift:61-63).
 */

/** Whether [source] is in the half-open interval (YouRec/VideoTools.swift:123). */
operator fun SourceInterval.contains(source: Double): Boolean = start <= source && source < end

/**
 * A grade over part of a clip: source seconds of moving footage, or the
 * advancing motion clock of a held frame. Half-open, so two adjacent grades
 * never compete for a frame (YouRec/VideoTools.swift:163-173).
 */
@Serializable
data class ColorRegion(val start: Double, val end: Double, val grade: ColorSettings) {
    val valid: Boolean
        get() = start.isFinite() && end.isFinite() && start >= 0 && end > start && grade.valid &&
            (grade.motion?.valid(grade.pose) { grade.applying(it).valid } ?: true)
}

/**
 * The clock of a title's motion, kept by a piece of a title edited on part of
 * its range so the fade under way goes on: [duration] seconds of motion, and
 * how much of it has run at each source second (YouRec/VideoTools.swift:351-359).
 */
@Serializable
data class TitlePlaybackClock(val duration: Double, val points: List<StrobeTimePoint>) {
    fun elapsed(source: Double): Double = StrobeClock.elapsed(source, points)

    val valid: Boolean
        get() = duration.isFinite() && duration > 0 && points.isNotEmpty() && points.all { it.source.isFinite() && it.elapsed.isFinite() } &&
            points.zipWithNext().all { (a, b) -> a.source <= b.source && a.elapsed <= b.elapsed }
}

/** Seconds elapsed at a source second along a clock of points, as the original's `StrobeTimePoint.elapsed(at:in:)`. */
internal object StrobeClock {
    fun elapsed(source: Double, points: List<StrobeTimePoint>): Double {
        val first = points.firstOrNull() ?: return source
        val last = points.last()
        if (source <= first.source) return first.elapsed
        if (source >= last.source) return last.elapsed
        var lower = 0
        var upper = points.size - 1
        while (upper - lower > 1) {
            val mid = (upper + lower) / 2
            if (points[mid].source < source) lower = mid else upper = mid
        }
        val l = points[lower]
        val r = points[upper]
        return if (r.source == l.source) r.elapsed else l.elapsed + (r.elapsed - l.elapsed) * (source - l.source) / (r.source - l.source)
    }
}

/**
 * A crop, a flip or a stabilisation that shares the clip's one slot for it at
 * another time, without cutting the footage (YouRec/ScopedEffect.swift:3-29).
 * Saved as Swift saves an enum with values: `{"crop":{"_0":…}}`,
 * `{"mirror":{"_0":…}}`, `{"stabilization":{"_0":"id","_1":…}}`.
 */
@Serializable(with = FramingVariantSerializer::class)
sealed interface FramingVariant {
    val id: String
    val tool: EditorTool
    val activeRange: SourceInterval?

    /** The variant under another id. */
    fun identified(id: String): FramingVariant

    data class Crop(val value: CropSettings) : FramingVariant {
        override val id: String get() = value.id
        override val tool: EditorTool get() = EditorTool.Crop
        override val activeRange: SourceInterval? get() = value.activeRange
        override fun identified(id: String) = Crop(value.copy(id = id))
    }

    data class Mirror(val value: MirrorSettings) : FramingVariant {
        override val id: String get() = value.id
        override val tool: EditorTool get() = EditorTool.Mirror
        override val activeRange: SourceInterval? get() = value.activeRange
        override fun identified(id: String) = Mirror(value.copy(id = id))
    }

    /** A stabilisation is named twice, as the original keeps it: by [id], and by its settings' own id. */
    data class Stabilization(override val id: String, val value: StabilizationSettings) : FramingVariant {
        override val tool: EditorTool get() = EditorTool.Stabilize
        override val activeRange: SourceInterval? get() = value.activeRange
        val hasID: Boolean get() = value.id == id
        override fun identified(id: String) = Stabilization(id, value.copy(id = id))
    }
}

internal object FramingVariantSerializer : KSerializer<FramingVariant> {
    @Serializable private class One<T>(@SerialName("_0") val value: T)
    @Serializable private class Two(@SerialName("_0") val id: String, @SerialName("_1") val value: StabilizationSettings)
    @Serializable private class Cases(
        val crop: One<CropSettings>? = null,
        val mirror: One<MirrorSettings>? = null,
        val stabilization: Two? = null,
    )

    override val descriptor: SerialDescriptor = Cases.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FramingVariant) = Cases.serializer().serialize(
        encoder,
        when (value) {
            is FramingVariant.Crop -> Cases(crop = One(value.value))
            is FramingVariant.Mirror -> Cases(mirror = One(value.value))
            is FramingVariant.Stabilization -> Cases(stabilization = Two(value.id, value.value))
        },
    )

    override fun deserialize(decoder: Decoder): FramingVariant {
        val cases = Cases.serializer().deserialize(decoder)
        if (listOfNotNull(cases.crop, cases.mirror, cases.stabilization).size != 1) throw SerializationException("A framing variant has exactly one case")
        cases.crop?.let { return FramingVariant.Crop(it.value) }
        cases.mirror?.let { return FramingVariant.Mirror(it.value) }
        return cases.stabilization!!.let { FramingVariant.Stabilization(it.id, it.value) }
    }
}

/** The windows of a clip's recipes, written as an object with its keys in order. */
internal object EffectWindowsSerializer : KSerializer<Map<String, List<SourceInterval>>> {
    private val map = MapSerializer(String.serializer(), ListSerializer(SourceInterval.serializer()))
    override val descriptor: SerialDescriptor = map.descriptor
    override fun serialize(encoder: Encoder, value: Map<String, List<SourceInterval>>) = map.serialize(encoder, value.toSortedMap())
    override fun deserialize(decoder: Decoder): Map<String, List<SourceInterval>> = map.deserialize(decoder)
}

/** Whether the clip holds anything of [ScopedEffects], which only readers of [TimelineClip.SCOPED_EFFECTS_SCHEMA] understand. */
val TimelineClip.scoped: Boolean
    get() = colorRegions != null || effectWindows != null || framingVariants != null ||
        crop?.activeRange != null || mirror?.activeRange != null || stabilization?.activeRange != null ||
        texts.orEmpty().any { it.playbackClock != null }

// MARK: Grades over parts of a clip (YouRec/VideoTools.swift:175-248)

val TimelineClip.hasGrade: Boolean get() = color != null || !colorRegions.isNullOrEmpty()

/** Every LUT the clip's grades need. */
val TimelineClip.gradeAssets: List<ImportedLUT> get() = color?.lutAssets.orEmpty() + colorRegions.orEmpty().flatMap { it.grade.lutAssets }

/** The source second (or a held frame's motion clock) [local] timeline seconds into the clip. */
fun TimelineClip.gradeTime(local: Double): Double {
    frozenFrame?.let { return it.sourceTime + it.offset + local }
    return sourceTime(local)
}

/** How far into the clip, in timeline seconds, [source] is ([gradeTime] the other way). */
fun TimelineClip.gradeOffset(source: Double): Double {
    frozenFrame?.let { return minOf(it.duration, maxOf(0.0, source - it.sourceTime - it.offset)) }
    return offset(source)
}

/** The grade in force at [source]. */
fun TimelineClip.grade(source: Double): ColorSettings? = color ?: colorRegions?.firstOrNull { it.start <= source && source < it.end }?.grade

/** The grades the clip shows, each cut to the footage it plays. */
val TimelineClip.visibleGrades: List<ColorRegion>
    get() {
        if (!hasGrade) return emptyList()
        val first = gradeTime(0.0)
        val last = gradeTime(duration)
        color?.let { return listOf(ColorRegion(first, last, it)) }
        return colorRegions.orEmpty().mapNotNull { region ->
            val a = maxOf(first, region.start)
            val b = minOf(last, region.end)
            if (b > a) ColorRegion(a, b, region.grade) else null
        }
    }

/**
 * The clip with [grade] over the source stretch [range], and the grades there
 * before cut around it; with [removingOnly], only that grade is cut. One grade
 * over the whole clip is its [TimelineClip.color] (YouRec/VideoTools.swift:200-228).
 */
fun TimelineClip.replacingGrade(range: ClosedFloatingPointRange<Double>, grade: ColorSettings?, removingOnly: String? = null): TimelineClip {
    val lower = range.start
    val upper = range.endInclusive
    if (!(upper > lower)) return this
    val result = ArrayList<ColorRegion>()
    for (region in visibleGrades) {
        if (!(region.start < upper && region.end > lower && (removingOnly == null || region.grade.id == removingOnly))) {
            result += region
            continue
        }
        if (region.start < lower) result += ColorRegion(region.start, lower, region.grade)
        if (region.end > upper) {
            // Separate handles for the two remnants.
            val remainder = if (region.start < lower) region.grade.copy(id = newID()) else region.grade
            result += ColorRegion(upper, region.end, remainder)
        }
    }
    if (grade != null) {
        // Re-editing just the middle of a look leaves independent editable remnants.
        for (i in result.indices) if (result[i].grade.id == grade.id) result[i] = result[i].copy(grade = result[i].grade.copy(id = newID()))
        result += ColorRegion(lower, upper, grade)
    }
    return if (result.size == 1 && result[0].start <= gradeTime(0.0) && result[0].end >= gradeTime(duration)) {
        copy(color = result[0].grade, colorRegions = null)
    } else {
        copy(color = null, colorRegions = result.sortedBy { it.start }.ifEmpty { null })
    }
}

// MARK: Framing variants and effect windows (YouRec/ScopedEffect.swift:31-133)

/** The clip's own crop, flip or stabilisation, as a variant. A stabilisation without an id is named by the clip's. */
fun TimelineClip.framing(tool: EditorTool): FramingVariant? = when (tool) {
    EditorTool.Crop -> crop?.let { FramingVariant.Crop(it) }
    EditorTool.Mirror -> mirror?.let { FramingVariant.Mirror(it) }
    EditorTool.Stabilize -> stabilization?.let { val id = it.id ?: this.id; FramingVariant.Stabilization(id, it.copy(id = id)) }
    else -> null
}

fun TimelineClip.settingFraming(value: FramingVariant): TimelineClip = when (value) {
    is FramingVariant.Crop -> copy(crop = value.value)
    is FramingVariant.Mirror -> copy(mirror = value.value)
    is FramingVariant.Stabilization -> copy(stabilization = value.value)
}

/** The clip with [variant] in its slot, and what was there among its variants. */
fun TimelineClip.promoting(variant: FramingVariant): TimelineClip {
    val others = framingVariants.orEmpty().filter { it.id != variant.id }.toMutableList()
    framing(variant.tool)?.let { if (it.id != variant.id) others += it }
    return copy(framingVariants = others.ifEmpty { null }).settingFraming(variant)
}

/** The ids of the recipes a window may name. */
val TimelineClip.windowRecipeIDs: Set<String>
    get() {
        val ids = HashSet<String>()
        texts.orEmpty().mapTo(ids) { it.id }
        images.orEmpty().mapTo(ids) { it.id }
        videoEffects.orEmpty().mapTo(ids) { it.id }
        listOfNotNull(crop?.id, mirror?.id, stabilization?.let { it.id ?: id }).forEach { ids += it }
        framingVariants.orEmpty().mapTo(ids) { it.id }
        return ids
    }

/**
 * The clip with its variants and windows tidied: a fresh framing applied over
 * the whole clip replaces its variants of that tool, a variant moves into an
 * empty slot, and windows of recipes that are gone go with them.
 */
fun TimelineClip.normalizingEffectWindows(): TimelineClip {
    if (effectWindows == null && framingVariants == null) return this
    var clip = this
    val alternatives = framingVariants.orEmpty().toMutableList()
    for (tool in listOf(EditorTool.Crop, EditorTool.Mirror, EditorTool.Stabilize)) {
        val main = clip.framing(tool)
        if (main != null) {
            // Applying a fresh, unscoped framing via Tools replaces the previous framing on this item, including its local variants.
            if (clip.effectWindows?.get(main.id) == null && main.activeRange == null) alternatives.removeAll { it.tool == tool }
        } else {
            val index = alternatives.indexOfFirst { it.tool == tool }
            if (index >= 0) clip = clip.settingFraming(alternatives.removeAt(index))
        }
    }
    clip = clip.copy(framingVariants = alternatives.ifEmpty { null })
    val ids = clip.windowRecipeIDs
    val kept = clip.effectWindows?.filterKeys { it in ids }
    return clip.copy(effectWindows = kept?.takeIf { it.isNotEmpty() })
}

/** Whether the variants and windows are as a project may hold them (YouRec/ScopedEffect.swift:84-94). */
val TimelineClip.validEffectWindows: Boolean
    get() {
        val variants = framingVariants.orEmpty()
        if (!variants.all { it !is FramingVariant.Stabilization || it.hasID } || variants.map { it.id }.toSet().size != variants.size ||
            !variants.all { effectWindows?.get(it.id)?.isNotEmpty() == true }
        ) return false
        val windows = effectWindows ?: return variants.isEmpty()
        if (!windowRecipeIDs.containsAll(windows.keys)) return false
        return windows.values.all { spans ->
            spans.isNotEmpty() && spans.all { it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start && it.end <= 604800 } &&
                spans.zipWithNext().all { (a, b) -> a.end < b.start }
        }
    }

/** The recipe of the clip at source second [time]: each recipe in its windows, the variants whose window holds it in their slots. */
fun TimelineClip.resolvingEffects(time: Double): TimelineClip {
    val windows = effectWindows ?: return this
    fun includes(id: String): Boolean = windows[id]?.any { time in it } ?: true
    var copy = copy(
        effectWindows = null, framingVariants = null,
        texts = texts?.filter { includes(it.id) },
        images = images?.filter { includes(it.id) },
        videoEffects = videoEffects?.filter { includes(it.id) },
        crop = crop?.takeIf { includes(it.id) },
        mirror = mirror?.takeIf { includes(it.id) },
        stabilization = stabilization?.takeIf { includes(it.id ?: id) },
    )
    for (variant in framingVariants.orEmpty()) if (includes(variant.id)) copy = copy.settingFraming(variant)
    return copy
}

/**
 * The clip cut where any window begins or ends, each piece with the recipe in
 * force there: what the preview, the transitions and the export all draw.
 * Source times, the order of effects and their clocks survive; a held frame is
 * cut along its hold (YouRec/ScopedEffect.swift:109-132).
 */
val TimelineClip.resolvedEffectPieces: List<TimelineClip>
    get() {
        val windows = effectWindows ?: return listOf(this)
        val lower = gradeTime(0.0)
        val upper = gradeTime(duration)
        val tick = 1.0 / EditTime.SCALE
        val edges = windows.values.flatMap { spans -> spans.flatMap { listOf(it.start, it.end) } }
            .filter { it - lower > tick && upper - it > tick }.toSortedSet().toList()
        val bounds = listOf(lower) + edges + listOf(upper)
        return bounds.zipWithNext().map { (a, b) ->
            var piece = resolvingEffects((a + b) / 2)
            piece.stabilization?.let { piece = piece.copy(stabilization = it.copy(referenceStart = it.referenceStart ?: start, referenceEnd = it.referenceEnd ?: end)) }
            val hold = frozenFrame
            if (hold != null) piece.copy(frozenFrame = hold.copy(offset = hold.offset + a - lower, duration = b - a))
            else piece.copy(start = a, end = b)
        }
    }

/** The recipe of the clip [offset] timeline seconds into it, with the active ranges of its framing honoured: what one frame draws. */
fun TimelineClip.recipe(offset: Double): TimelineClip {
    val time = gradeTime(offset)
    val resolved = resolvingEffects(time)
    return resolved.copy(
        color = resolved.grade(time),
        colorRegions = null,
        crop = resolved.crop?.takeIf { it.activeRange?.let { range -> time in range } ?: true },
        mirror = resolved.mirror?.takeIf { it.activeRange?.let { range -> time in range } ?: true },
        stabilization = resolved.stabilization?.takeIf { it.activeRange?.let { range -> time in range } ?: true },
    )
}

/** Whether the clip's crop, flip or stabilisation applies to part of it only, by an active range, a window or a variant. */
val TimelineClip.scopedFraming: Boolean
    get() = effectWindows != null || framingVariants != null ||
        crop?.activeRange != null || mirror?.activeRange != null || stabilization?.activeRange != null

/**
 * The crop, flip and stabilisation in force at [time], a source second (or a
 * held frame's motion clock): those of the recipe in force there, each only
 * inside its active range (YouRec/VideoToolCompositor.swift:204-222).
 */
fun TimelineClip.framingAt(time: Double): TimelineClip {
    if (!scopedFraming) return this
    val resolved = resolvingEffects(time)
    return resolved.copy(
        crop = resolved.crop?.takeIf { it.activeRange?.let { range -> time in range } ?: true },
        mirror = resolved.mirror?.takeIf { it.activeRange?.let { range -> time in range } ?: true },
        stabilization = resolved.stabilization?.takeIf { it.activeRange?.let { range -> time in range } ?: true },
    )
}

/** What a project may hold of a clip's scoped effects, from a recording of [sourceDuration] seconds (YouRec/Project.swift:504-548). */
object ScopedEffects {
    fun valid(clip: TimelineClip, sourceDuration: Double): Boolean {
        if (!clip.validEffectWindows) return false
        // Alternate framing is checked with exactly the rules of the primary recipe.
        for (variant in clip.framingVariants.orEmpty()) {
            val promoted = clip.promoting(variant)
            if (promoted.crop?.valid == false || promoted.stabilization?.valid(sourceDuration) == false) return false
            if (!activeRangesValid(promoted)) return false
        }
        clip.colorRegions?.let { regions ->
            if (clip.color != null || !regions.all { it.valid } || !regions.zipWithNext().all { (a, b) -> a.end <= b.start }) return false
        }
        return activeRangesValid(clip)
    }

    private fun activeRangesValid(clip: TimelineClip): Boolean =
        listOfNotNull(clip.crop?.activeRange, clip.mirror?.activeRange, clip.stabilization?.activeRange).all {
            it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start
        }

    /** Whether a timeline keeps effects made across pieces, which readers before [TimelineClip.SCOPED_EFFECTS_SCHEMA] would lose. */
    fun spans(timeline: TrackTimeline): Boolean =
        timeline.video.any { it.spanningEffects != null } || timeline.audio.any { it.spanningEffects != null }
}
