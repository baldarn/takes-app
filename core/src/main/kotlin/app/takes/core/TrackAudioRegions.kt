package app.takes.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlin.math.abs

/** The two tools that put something on a stretch of a sound: a chain of effects, or a censor. */
enum class AudioToolKind { Enhance, Censor }

/**
 * One badge of a sound tool on the timeline: the region [effectID] of the
 * sound [itemID], at [interval] on the timeline's clock. The original's
 * `ToolRegion` for `.enhanceAudio` and `.censorAudio` (YouRec/VideoTools.swift:443-450, :622-629).
 */
data class AudioToolRegion(val kind: AudioToolKind, val effectID: String, val itemID: String, val interval: EditInterval)

/**
 * What the Enhance audio panel opens with: the chain of the first region in
 * scope, and whether the sounds in scope have different chains, or some have
 * one and some none. YouRec/TrackEditors.swift:212-219.
 */
data class AudioChainScope(val effects: List<AudioEffect>, val mixed: Boolean)

/**
 * One stretch of a sound as the mixer plays it: [local] in the item's own
 * ticks, with the timing [mapping] of that stretch, and the chain [effects]
 * that processes it (its stages that are on, empty for none). [key] names the
 * processed sound to play ([TrackAudioRegions.cacheKey]); null plays the
 * recording itself.
 */
data class AudioRenderPiece(
    val itemID: String,
    val sourceID: String,
    val local: EditInterval,
    val mapping: SourcePlaybackMap,
    val effects: List<AudioEffect>,
    val key: String?,
)

/** A chain to process over the whole of one source, once for every place it is used with that chain. */
data class AudioChainRequest(val sourceID: String, val effects: List<AudioEffect>)

/** A chain to process over only [spans] of one source, in source seconds, for listening to a stretch. */
data class AudioWindowRequest(val sourceID: String, val effects: List<AudioEffect>, val spans: List<SourceInterval>)

/**
 * Enhance audio and Censor as regions of a sound on the track timeline: the
 * badges, what a badge's panel acts on and removes, and what the renderer
 * processes and plays. The edits themselves are [TrackAudio.enhancing] and
 * [TrackAudio.censoring]; what cutting, moving and retiming a sound does to
 * its regions is in [AudioTimelineItem.sliced], [TrackMove] and
 * [TrackEditing.synchronizingFollowers].
 *
 * A processed sound is made once per source and chain, whatever plays it:
 * two pieces of one recording with the same chain share it, two with
 * different chains have one each (YouRec/TrackRenderer.swift:27-30,
 * YouRec/SourceMediaCache.swift:240-247).
 */
object TrackAudioRegions {
    // MARK: Badges

    /** Every censor and chain on the timeline, sound by sound, the censors of each first. YouRec/VideoTools.swift:622-629. */
    fun badges(timeline: TrackTimeline): List<AudioToolRegion> = timeline.audio.flatMap { item ->
        fun at(interval: EditInterval) = EditInterval(item.start + interval.start, item.start + interval.end)
        item.censors.map { AudioToolRegion(AudioToolKind.Censor, it.id, item.id, at(it.interval)) } +
            item.enhancements.map { AudioToolRegion(AudioToolKind.Enhance, it.id, item.id, at(it.interval)) }
    }

    /**
     * What the panel opened from [region] acts on: sounds and a range. With
     * [selection] holding sounds the badge's effect is on (within its range,
     * when it has one), those pieces; otherwise the badge's own piece, and for
     * a censor every piece of the same censor that overlaps it, and what
     * overlaps those, so one action over several sounds is reopened together
     * and a sound running under a split one joins both halves. A piece of the
     * same effect that only touches is never taken in. The range is that of
     * the pieces, within the selection's range when it limits them.
     * YouRec/VideoTools.swift:457-489.
     */
    fun targets(timeline: TrackTimeline, region: AudioToolRegion, selection: TimelineSelection = TimelineSelection()): TimelineSelection {
        val matching = badges(timeline).filter { it.kind == region.kind && it.effectID == region.effectID }
        val scoped = matching.filter { it.itemID in selection.audio && (selection.range == null || selection.range.intersection(it.interval) != null) }
        val event = mutableListOf(region)
        if (region.kind == AudioToolKind.Censor) {
            while (true) {
                val additions = matching.filter { candidate ->
                    event.none { it.itemID == candidate.itemID && it.interval == candidate.interval } &&
                        event.any { it.interval.intersection(candidate.interval) != null }
                }
                if (additions.isEmpty()) break
                event += additions
            }
        }
        val regions = scoped.ifEmpty { event }
        val bounds = EditInterval(regions.minOf { it.interval.start }, regions.maxOf { it.interval.end })
        val range = if (scoped.isEmpty()) bounds else selection.range?.intersection(bounds) ?: bounds
        return TimelineSelection(audio = regions.mapTo(LinkedHashSet()) { it.itemID }, range = range)
    }

    /**
     * Takes the effect [effectID] of [kind] off what [targets] holds: the
     * selected sounds, inside its range (each whole sound when it has none).
     * Pieces of the effect outside are kept; a chain cut in two keeps its id
     * on the left and takes a new one on the right. Every other region stays.
     * YouRec/VideoTools.swift:490-511.
     */
    fun removing(timeline: TrackTimeline, kind: AudioToolKind, effectID: String, targets: TimelineSelection): TrackTimeline = when (kind) {
        AudioToolKind.Censor -> TrackAudio.censoring(timeline, null, targets, removingOnly = effectID)
        AudioToolKind.Enhance -> timeline.copy(audio = timeline.audio.map { item ->
            if (item.id !in targets.audio) return@map item
            val scope = targets.range ?: item.interval
            item.copy(enhancements = item.enhancements.flatMap { region ->
                val whole = EditInterval(item.start + region.interval.start, item.start + region.interval.end)
                val part = whole.intersection(scope)
                if (region.id != effectID || part == null) return@flatMap listOf(region)
                val kept = ArrayList<AudioEnhancementRegion>()
                if (whole.start < part.start) kept += region.copy(interval = EditInterval(region.interval.start, part.start - item.start))
                if (whole.end > part.end) {
                    kept += region.copy(id = if (kept.isEmpty()) region.id else newID(), interval = EditInterval(part.end - item.start, region.interval.end))
                }
                kept
            })
        })
    }

    /** What the Enhance audio panel opens with for [targets] ([AudioChainScope]). YouRec/TrackEditors.swift:212-219. */
    fun chainScope(timeline: TrackTimeline, targets: TimelineSelection): AudioChainScope {
        val items = timeline.audio.filter { it.id in targets.audio }
        val chains = items.flatMap { item ->
            item.enhancements.filter { region ->
                targets.range?.intersection(EditInterval(item.start + region.interval.start, item.start + region.interval.end)) != null || targets.range == null
            }.map { it.effects }
        }
        val mixed = chains.drop(1).any { it != chains.first() } || (items.any { it.enhancements.isEmpty() } && chains.isNotEmpty())
        return AudioChainScope(chains.firstOrNull().orEmpty(), mixed)
    }

    // MARK: Rendering

    /**
     * The name of the processed sound of [sourceID] through [effects]: the
     * source, and the stages that are on with every setting, so a stage that
     * is off never causes a second processing. As the original's: the id, a
     * bar, and the Base64 of the stages' JSON with sorted keys
     * (YouRec/TrackRenderer.swift:27-30), with one difference: a stage's own
     * id is left out, since it changes nothing that is heard, so chains made
     * apart with the same settings (as the conversion makes one per clip that
     * asks for noise reduction) share one processing, as the app's file of cleaned sound
     * per recording and amount does today. The bytes of that JSON are this
     * app's, not Swift's, so a key is only ever compared with keys made here.
     */
    fun cacheKey(sourceID: String, effects: List<AudioEffect>): String {
        val stages = documentJson.encodeToJsonElement(ListSerializer(AudioEffect.serializer()), effects.filter { it.enabled })
        val json = JsonArray((stages as JsonArray).map { JsonObject((it as JsonObject).filterKeys { key -> key != "id" }) })
        return sourceID + "|" + Base64.getEncoder().encodeToString(sorted(json).toString().toByteArray())
    }

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }

    /**
     * The stretches the mixer plays [item] in, in order: cut where a chain
     * begins or ends, and where the item's map jumps in its source, holds, or
     * goes from heard to silent or back, so a pass that keeps pitch at speed
     * never bridges a removed stretch. With [range] (timeline ticks), only
     * the part of the item inside it, for listening to a stretch; nothing
     * when it misses the item. A muted item plays nothing, and a stretch all
     * silent is left out. YouRec/TrackRenderer.swift:198-221.
     */
    fun pieces(item: AudioTimelineItem, range: EditInterval? = null): List<AudioRenderPiece> {
        val duration = item.mapping.duration
        val boundaries = mutableListOf(EditTime.ZERO, duration)
        for (region in item.enhancements) { boundaries += region.interval.start; boundaries += region.interval.end }
        var cursor = EditTime.ZERO
        for ((run, next) in item.mapping.runs.zipWithNext()) {
            cursor += run.duration
            if (abs(run.sourceEnd - next.sourceStart) > SOURCE_TOLERANCE || run.silent != next.silent || run.isHold || next.isHold) boundaries += cursor
        }
        var points: List<EditTime> = boundaries
        if (range != null) {
            val scope = item.interval.intersection(range) ?: return emptyList()
            val local = EditInterval(scope.start - item.start, scope.end - item.start)
            points = (boundaries + listOf(local.start, local.end)).filter { it >= local.start && it <= local.end }
        }
        if (item.muted) return emptyList()
        return points.distinct().sorted().zipWithNext().mapNotNull { (a, b) ->
            if (b <= a) return@mapNotNull null
            val map = item.mapping.sliced(EditInterval(a, b))
            if (map.runs.none { !it.silent }) return@mapNotNull null
            val region = item.enhancements.firstOrNull { a in it.interval }
            val key = region?.takeIf { it.effects.any { stage -> stage.enabled } }?.let { cacheKey(item.sourceID, it.effects) }
            AudioRenderPiece(item.id, item.sourceID, EditInterval(a, b), map, region?.let { AudioChain.active(it.effects) }.orEmpty(), key)
        }
    }

    /**
     * The processed sounds the timeline needs, by [cacheKey] in sorted order:
     * one for each source and chain with a stage that is on, however many
     * pieces play it. YouRec/SourceMediaCache.swift:240-247.
     */
    fun requests(timeline: TrackTimeline): Map<String, AudioChainRequest> {
        val requests = HashMap<String, AudioChainRequest>()
        for (item in timeline.audio) for (region in item.enhancements) {
            if (region.effects.any { it.enabled }) requests[cacheKey(item.sourceID, region.effects)] = AudioChainRequest(item.sourceID, region.effects)
        }
        return requests.toSortedMap()
    }

    /**
     * What listening to [range] of the timeline needs processed: for each
     * source and chain, only the source seconds the sounds that are not muted
     * play there, merged where they touch or overlap, so a short stretch of a
     * long take never processes the take, and a removed stretch or a distant
     * second use of it is never bridged. YouRec/SourceMediaCache.swift:296-319.
     */
    fun windowRequests(timeline: TrackTimeline, range: EditInterval): Map<String, AudioWindowRequest> {
        val requests = HashMap<String, AudioWindowRequest>()
        for (item in timeline.audio) {
            if (item.muted) continue
            val part = item.interval.intersection(range) ?: continue
            val local = EditInterval(part.start - item.start, part.end - item.start)
            for (region in item.enhancements) {
                if (region.effects.none { it.enabled }) continue
                val scope = region.interval.intersection(local) ?: continue
                val key = cacheKey(item.sourceID, region.effects)
                val spans = item.mapping.sliced(scope).runs.filter { !it.silent && !it.isHold }.map { SourceInterval(it.sourceStart, it.sourceEnd) }
                val known = requests[key] ?: AudioWindowRequest(item.sourceID, region.effects, emptyList())
                requests[key] = known.copy(spans = known.spans + spans)
            }
        }
        return requests.mapValues { (_, request) ->
            val merged = ArrayList<SourceInterval>()
            for (span in request.spans.sortedBy { it.start }) {
                val last = merged.lastOrNull()
                if (last != null && span.start <= last.end + SOURCE_TOLERANCE) merged[merged.lastIndex] = SourceInterval(last.start, maxOf(last.end, span.end))
                else merged += span
            }
            request.copy(spans = merged)
        }.toSortedMap()
    }

    /** Source seconds closer than this are the same place. */
    private const val SOURCE_TOLERANCE = 0.00001
}
