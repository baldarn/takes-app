package app.takes.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.util.Base64

/**
 * What each thumbnail of the timeline draws, as the original's plan since 57e5d91 (YouRec/Filmstrip.swift:42-275): the
 * thumbnails show the picture as edited, and a rendered thumbnail is kept by what it draws (the source frame, the
 * look, the placement and the captions), so an edit renders again only the thumbnails it changes. A split, a move or
 * a ripple keeps every picture whose frame and look did not change; a grade over part of a clip renders again only
 * the frames it covers. Where the picture depends on more than the saved edit (a transition, an effect made from
 * other pictures, a refined analysis, a moving title, a stabilised stretch), the key is the timeline's generation
 * and the time, so the thumbnail is drawn again whenever the timeline's pictures change ([regenerates]).
 */
class FilmstripPlan(timeline: TrackTimeline, reusing: FilmstripPlan? = null) {
    /**
     * The identity of one thumbnail: [key], what it is cached by; [sourceTime], the source second of its own
     * picture when the key is that picture's; [plain] when that picture is the footage as recorded, which a
     * thumbnail of the footage can stand in for while it is rendered; [captionPlaceholder], with captions, the key
     * of the same footage, look, caption and animation phase whatever the rest of the transcript, whose last
     * picture can stand in while the fit against the other captions is worked out again.
     */
    data class Sample(val key: String, val sourceTime: Double?, val plain: Boolean, val captionPlaceholder: String? = null)

    @Serializable
    private data class Look(
        val color: ColorSettings?,
        val crop: CropSettings?,
        val mirror: MirrorSettings?,
        val images: List<ImageOverlay>,
        val texts: List<TextOverlay>,
        val effects: List<VideoEffectSettings>,
    )

    private class Span(val start: Double, val namespace: String?, val plain: Boolean)

    private class Picture(val item: VideoTimelineItem, val spans: List<Span>, val playback: List<EffectPlaybackSpan>)

    private class Overlap(val interval: EditInterval, val layers: List<String>, val placement: String)

    private val pictures = HashMap<String, Picture>()
    private val composite: List<EditInterval>
    private val overlaps = ArrayList<Overlap>()
    private val captions = Captions(timeline)

    init {
        val complex = timeline.generated.map { it.interval }.toMutableList()
        for (relation in timeline.transitions) {
            val timing = relation.timing
            complex += EditInterval(relation.origin + EditTime.of(timing.lower), relation.origin + EditTime.of(timing.upper))
        }
        // Only the edges of pictures are swept: a transcript or a dense sound edit does not multiply the work of
        // finding where two pictures overlap.
        val ordered = timeline.video.sortedWith(VideoTimelineItem.backToFront)
        val events = java.util.TreeMap<EditTime, MutableList<Pair<Int, Boolean>>>()
        ordered.forEachIndexed { i, item ->
            events.getOrPut(item.start) { ArrayList() } += i to true
            events.getOrPut(item.end) { ArrayList() } += i to false
        }
        val placements = HashMap<Set<String>, Map<String, VideoPlacement>>()
        for (region in timeline.placements) placements.putIfAbsent(region.participants, region.videos)
        var previous = EditTime.ZERO
        val active = java.util.TreeSet<Int>()
        for ((edge, changes) in events) {
            if (active.size > 1) {
                val interval = EditInterval(previous, edge)
                val layers = active.map { ordered[it].id }
                val settings = placements[layers.toSet()] ?: emptyMap()
                val order = VideoPlacement.ordered(layers, settings)
                overlaps += Overlap(interval, order, digest(PLACEMENTS, order.map { settings[it] }))
            }
            for ((i, entering) in changes) if (entering) active += i else active -= i
            previous = edge
        }
        composite = TrackEditing.union(complex)
        for (item in timeline.video) {
            val clip = item.content
            val saved = reusing?.pictures?.get(item.id)
            if (saved != null && saved.item.content == clip) {
                pictures[item.id] = Picture(item, saved.spans, saved.playback)
                continue
            }
            picture(item)?.let { pictures[item.id] = it }
        }
    }

    /** The spans of [item]'s own picture, each with what it draws; null where its frames are not its own source's. */
    private fun picture(item: VideoTimelineItem): Picture? {
        val clip = item.content
        val prefix = clip.sourceID
        val effects = clip.videoEffects.orEmpty().filter { it.enabled }
        // A freeze advances a second clock; a temporal matte reads other frames of the source.
        if (clip.frozenFrame != null || effects.any { it.usesTemporalMatte }) return null
        val stabilization = clip.stabilization?.takeIf { it.level != StabilizationLevel.Off }
        val texts = clip.texts.orEmpty()
        val images = clip.images.orEmpty()
        val grades = clip.colorRegions.orEmpty()
        val changes = java.util.TreeMap<Double, MutableList<Pair<Part, Boolean>>>()
        changes[clip.start] = ArrayList()
        fun span(part: Part, start: Double, end: Double) {
            if (!(end > clip.start && start < clip.end && end > start)) return
            changes.getOrPut(maxOf(clip.start, start)) { ArrayList() } += part to true
            if (end < clip.end) changes.getOrPut(end) { ArrayList() } += part to false
        }
        val windows = clip.effectWindows.orEmpty().values.flatten()
        val framing = listOfNotNull(clip.crop?.activeRange, clip.mirror?.activeRange, stabilization?.activeRange)
        for (value in windows + framing + clip.framingVariants.orEmpty().mapNotNull { it.activeRange }) {
            for (edge in listOf(value.start, value.end)) if (clip.start < edge && edge < clip.end) changes.getOrPut(edge) { ArrayList() }
        }
        grades.forEachIndexed { i, grade -> span(Part(Part.GRADE, i), grade.start, grade.end) }
        images.forEachIndexed { i, image -> span(Part(Part.IMAGE, i), image.start, image.end) }
        texts.forEachIndexed { i, title -> span(Part(Part.TEXT, i), title.start, title.end) }
        effects.forEachIndexed { i, effect -> span(Part(Part.EFFECT, i), effect.start, effect.end) }
        val active = Array(4) { java.util.TreeSet<Int>() }
        val spans = ArrayList<Span>()
        for ((t, parts) in changes) {
            for ((part, enabled) in parts) if (enabled) active[part.kind] += part.index else active[part.kind] -= part.index
            val resolved = clip.resolvingEffects(t)
            fun visible(id: String): Boolean = clip.effectWindows?.get(id)?.any { t in it } ?: true
            val titles = active[Part.TEXT].map { texts[it] }.filter { visible(it.id) }
            val look = Look(
                color = clip.color ?: active[Part.GRADE].firstOrNull()?.let { grades[it].grade },
                crop = resolved.crop?.takeIf { it.activeRange?.let { range -> t in range } != false },
                mirror = resolved.mirror?.takeIf { it.activeRange?.let { range -> t in range } != false },
                images = active[Part.IMAGE].map { images[it] }.filter { visible(it.id) },
                texts = titles,
                effects = active[Part.EFFECT].map { effects[it] }.filter { visible(it.id) },
            )
            val plain = look.color == null && look.crop == null && look.mirror == null && look.images.isEmpty() && titles.isEmpty() && look.effects.isEmpty()
            // Refinement can replace these pixels without the saved edit changing.
            val refined = resolved.stabilization?.let { it.level != StabilizationLevel.Off && it.activeRange?.let { range -> t in range } != false } == true ||
                look.effects.any { it.requiresOfflineAnalysis || it.kind in UNSTABLE } ||
                titles.any { it.motion?.active == true }
            val namespace = when {
                refined -> null
                plain -> prefix
                else -> prefix + ":" + digest(Look.serializer(), look)
            }
            spans += Span(t, namespace, plain)
        }
        return Picture(item, spans, clip.playbackSpans)
    }

    private class Part(val kind: Int, val index: Int) {
        companion object {
            const val TEXT = 0
            const val IMAGE = 1
            const val GRADE = 2
            const val EFFECT = 3
        }
    }

    private fun layer(id: String, time: EditTime): Sample? {
        val picture = pictures[id] ?: return null
        if (time !in picture.item.interval) return null
        val clip = picture.item.content
        val source = SpeedMapping.sourceTime((time - picture.item.start).seconds, picture.playback, clip.end)
        val span = picture.spans.lastStartingBy { it.start <= source } ?: return null
        val namespace = span.namespace ?: return null
        return Sample(namespace + ":" + EditTime.of(source).ticks, source, span.plain)
    }

    /**
     * The thumbnail of picture [lane] at timeline time [time]. [generation] names the timeline's pictures as they
     * are; it keys what depends on more than the saved edit.
     */
    fun sample(lane: String, time: EditTime, generation: String): Sample {
        val transient = Sample(generation + ":" + time.ticks, null, false)
        if (composite.lastStartingBy { it.start <= time }?.let { time in it } == true) return transient
        val caption = captions.suffix(time) ?: return transient
        val overlap = overlaps.lastStartingBy { it.interval.start <= time }?.takeIf { time in it.interval }
        val picture = if (overlap != null) {
            val key = StringBuilder("overlap:").append(overlap.placement)
            for (id in overlap.layers) key.append('|').append((layer(id, time) ?: return transient).key)
            Sample(key.toString(), null, false)
        } else layer(lane, time) ?: return transient
        val envelope = captions.envelope
        if (caption.isEmpty() || envelope == null) return picture
        val local = picture.key + caption
        return Sample("$local:$envelope", picture.sourceTime, false, captionPlaceholder = local)
    }

    /**
     * Subtitle layout fits every caption against the largest caption or pair of the transcript: moving a phrase
     * keeps that envelope, editing one can move captions elsewhere in the movie (YouRec/Filmstrip.swift:50-93).
     */
    private class Captions(timeline: TrackTimeline) {
        class Span(val start: Double, val cues: List<SubtitleCue>)

        private val spans = ArrayList<Span>()
        private var style: String? = null
        var envelope: String? = null
            private set
        private var animated = false

        init {
            val settings = timeline.subtitles
            if (settings != null) {
                val cues = TrackSubtitles.cues(timeline)
                // Removals before additions at the same time.
                val events = cues.indices.flatMap { listOf(Triple(cues[it].start, true, it), Triple(cues[it].end, false, it)) }
                    .sortedWith(compareBy<Triple<Double, Boolean, Int>> { it.first }.thenBy { it.second })
                val active = java.util.TreeSet<Int>()
                val stacks = HashSet<List<String>>()
                for ((time, add, i) in events) {
                    if (add) active += i else active -= i
                    val visible = active.take(2).map { cues[it] }
                    stacks += visible.map { it.text }
                    if (spans.lastOrNull()?.start == time) spans.removeAt(spans.lastIndex)
                    spans += Span(time, visible)
                }
                animated = settings.motion?.active == true
                // The timing fingerprint governs transcribing again, never pixels.
                style = digest(SubtitleSettings.serializer(), settings.copy(timingFingerprint = ""))
                envelope = digest(STACKS, stacks.sortedWith(LEXICOGRAPHIC))
            }
        }

        /** What captions add to a thumbnail's key at [time]: "" without one, null when the layout cannot be named. */
        fun suffix(time: EditTime): String? {
            val visible = spans.lastStartingBy { it.start <= time.seconds }
            if (visible == null || visible.cues.isEmpty()) return ""
            val style = style ?: return null
            if (envelope == null) return null
            val key = StringBuilder(":captions:").append(style)
            for (cue in visible.cues) {
                key.append(':').append(cue.text.toByteArray(Charsets.UTF_8).size).append(':').append(cue.text)
                if (animated) key.append(':').append(EditTime.of(time.seconds - cue.start).ticks).append(':').append(EditTime.of(cue.end - cue.start).ticks)
            }
            return key.toString()
        }
    }

    companion object {
        /** Effects whose frames the plan does not name by the edit alone (YouRec/Filmstrip.swift:205). */
        private val UNSTABLE = setOf(VideoEffectID.Undercranking, VideoEffectID.SuperEight, VideoEffectID.StepPrinting)

        private val PLACEMENTS = ListSerializer(VideoPlacement.serializer().nullable)
        private val STACKS = ListSerializer(ListSerializer(String.serializer()))
        private val LEXICOGRAPHIC = Comparator<List<String>> { a, b ->
            for (i in 0 until minOf(a.size, b.size)) {
                val order = a[i].compareTo(b[i])
                if (order != 0) return@Comparator order
            }
            a.size.compareTo(b.size)
        }

        private fun <T> digest(serializer: KSerializer<T>, value: T): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(documentJson.encodeToString(serializer, value).toByteArray()))

        /**
         * Whether the edit from [previous] to [next] gives the timeline's pictures a new generation, so that every
         * thumbnail keyed by it is drawn again: a change of the pictures, the transitions, the generated stretches,
         * the placements or the subtitles, or of the sound under subtitles (YouRec/EditorTimeline.swift:1290-1297).
         */
        fun regenerates(previous: TrackTimeline?, next: TrackTimeline): Boolean =
            previous != null && (previous.video != next.video || previous.transitions != next.transitions || previous.generated != next.generated ||
                previous.placements != next.placements || previous.subtitles != next.subtitles || (next.subtitles != null && previous.audio != next.audio))
    }
}

/** The last element of a list sorted by [matches] (true, then false) that matches, by binary search; null when none does. */
private inline fun <T> List<T>.lastStartingBy(matches: (T) -> Boolean): T? {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (matches(this[mid])) low = mid + 1 else high = mid
    }
    return if (low > 0) this[low - 1] else null
}
