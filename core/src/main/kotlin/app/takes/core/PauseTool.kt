package app.takes.core

import kotlin.math.abs

/** One pause the Pauses tool found in a clip of its scope, as the detector found it ([raw]), and whether it is cut (YouRec/EditorTools.swift:159-165). */
data class PauseChoice(val clipID: String, val raw: SourceInterval, val enabled: Boolean = true)

/**
 * The arithmetic of the Pauses tool, as the original's tool workspace does
 * it (YouRec/EditorTools.swift:337-357, :411-413, :618-640, :934-1041): what
 * it opens on, what a new scan keeps, the edit it makes and the stretch of
 * the movie a pause plays in. The screen holds the choices and calls these.
 */
object PauseTool {
    /**
     * The settings the tool opens with: those saved on the first clip of
     * [ids] in [state] that has any, else the defaults
     * (YouRec/EditorTools.swift:444-447).
     */
    fun settings(state: EditState, ids: Collection<String>): PauseSettings =
        state.clips.firstOrNull { it.id in ids }?.pauses ?: PauseSettings()

    /** Whether any clip of [ids] in [state] has pauses taken out, so that the tool can remove them. */
    fun saved(state: EditState, ids: Collection<String>): Boolean = state.clips.any { it.id in ids && it.pauses != null }

    /**
     * The pauses saved on the clips [ids] of [state], with their Cut or Keep,
     * to open the tool on them again; with the silence level each clip was
     * scanned at. A clip saved with cuts and no candidates gives each cut
     * back widened by its padding (YouRec/EditorTools.swift:1033-1041).
     */
    fun opened(state: EditState, ids: Collection<String>): Pair<List<PauseChoice>, Map<String, Double>> {
        val choices = ArrayList<PauseChoice>()
        val levels = HashMap<String, Double>()
        for (clip in state.clips) {
            val settings = clip.pauses ?: continue
            if (clip.id !in ids) continue
            levels[clip.id] = settings.thresholdDB
            val candidates = settings.candidates ?: settings.cuts.map {
                DetectedPause(SourceInterval(maxOf(clip.start, it.start - settings.before * clip.rate), minOf(clip.end, it.end + settings.after * clip.rate)))
            }
            choices += candidates.filter { it.raw.start < clip.end && it.raw.end > clip.start }.map { PauseChoice(clip.id, it.raw, it.enabled) }
        }
        return choices to levels
    }

    /** Whether [a] and [b] are mostly the same pause: they share more than half of the shorter (YouRec/EditorTools.swift:965, :1017). */
    fun same(a: SourceInterval, b: SourceInterval): Boolean = minOf(a.end, b.end) - maxOf(a.start, b.start) > minOf(a.duration, b.duration) * 0.5

    /** The pauses found in a clip by a scan, each kept where it is mostly one of [kept], the pauses chosen to keep before (YouRec/EditorTools.swift:963-967). */
    fun found(clipID: String, raws: List<SourceInterval>, kept: List<SourceInterval>): List<PauseChoice> =
        raws.map { raw -> PauseChoice(clipID, raw, enabled = kept.none { same(it, raw) }) }

    /**
     * The pauses to keep of a clip after [raw] was set to Cut ([enabled]) or
     * Keep: kept across scans, even while a setting hides the pause, so that
     * sliding back does not cut it again (YouRec/EditorTools.swift:385-387, :1013-1019).
     */
    fun keeping(kept: List<SourceInterval>, raw: SourceInterval, enabled: Boolean): List<SourceInterval> =
        kept.filter { !same(it, raw) } + listOfNotNull(raw.takeIf { !enabled })

    /** Whether going from [old] to [new] settings asks for a new scan: the level or the shortest pause changed; the padding is applied to what was found (YouRec/EditorTools.swift:980-989). */
    fun rescans(old: PauseSettings, new: PauseSettings): Boolean =
        old.levelMode != new.levelMode || old.thresholdDB != new.thresholdDB || old.minimum != new.minimum

    /**
     * The settings the clip [clip] is given: [settings] under [id], at the
     * level the clip was scanned at in automatic mode, with every pause of
     * [choices] and the cuts the enabled ones make (YouRec/EditorTools.swift:629-638).
     */
    fun settingsFor(clip: TimelineClip, settings: PauseSettings, choices: List<PauseChoice>, level: Double?, id: String): PauseSettings {
        val candidates = choices.map { DetectedPause(it.raw, it.enabled) }
        val base = settings.copy(id = id, thresholdDB = if (settings.levelMode == PauseLevelMode.Automatic) level ?: settings.thresholdDB else settings.thresholdDB)
        return base.copy(candidates = candidates, analyzed = SourceInterval(clip.start, clip.end), cuts = PauseDetector.effectiveCuts(candidates, clip, base, clip.rate))
    }

    /**
     * The edit with the tool's choices: each clip of [ids] in [base] given
     * its settings ([settingsFor]), or none when it has no pause or when
     * [removing], which puts back every pause the clips had taken out
     * (YouRec/EditorTools.swift:618-640). [levels] is the silence level each
     * clip was scanned at.
     */
    fun state(base: EditState, ids: Collection<String>, settings: PauseSettings, choices: List<PauseChoice>, levels: Map<String, Double>, id: String, removing: Boolean = false): EditState {
        val by = choices.groupBy { it.clipID }
        var state = base
        for (clipID in ids) {
            val index = state.clips.indexOfFirst { it.id == clipID }
            if (index < 0) continue
            val clip = state.clips[index]
            val mine = by[clipID]
            val next = if (removing || mine.isNullOrEmpty()) null else settingsFor(clip, settings, mine, levels[clipID], id)
            TimelineEditing.setPauses(state, index, next)?.let { state = it }
        }
        return state
    }

    /** Seconds of the clip the pause [raw] takes out with [settings] when cut (YouRec/EditorTools.swift:990-995). */
    fun cutSeconds(clip: TimelineClip, raw: SourceInterval, settings: PauseSettings): Double {
        val whole = clip.copy(pauses = null)
        return PauseDetector.effectiveCuts(listOf(DetectedPause(raw)), clip, settings, clip.rate).sumOf { whole.offset(it.end) - whole.offset(it.start) }
    }

    /** How many cuts the clips [ids] of [state] have, and how many seconds shorter they are for them (YouRec/EditorTools.swift:410-413). */
    fun summary(state: EditState, ids: Collection<String>): Pair<Int, Double> {
        val clips = state.clips.filter { it.id in ids }
        return clips.sumOf { it.pauses?.cuts?.size ?: 0 } to clips.sumOf { it.copy(pauses = null).duration - it.duration }
    }

    /**
     * Where in the movie [shown] the pause [choice] plays, in seconds: the
     * whole pause when the movie is shown without the cuts ([original]), is
     * kept, or would cut less than a frame; else what is left of it around
     * the cut (YouRec/EditorTools.swift:996-1007). The original's preview
     * plays the clips of the scope alone; here the whole movie is shown, so
     * the times are the movie's.
     */
    fun range(shown: EditState, choice: PauseChoice, settings: PauseSettings, original: Boolean): ClosedFloatingPointRange<Double> {
        val index = shown.clips.indexOfFirst { it.id == choice.clipID }
        if (index < 0) return 0.0..0.0
        val clip = shown.clips[index]
        val start = shown.layered?.video?.firstOrNull { it.id == clip.id }?.start?.seconds ?: TimelineEditing.clipStart(shown.clips, index)
        val interval = if (original || !choice.enabled || cutSeconds(clip, choice.raw, settings) < PauseDetector.FRAME) choice.raw
        else PauseDetector.padded(choice.raw, clip.copy(pauses = null), settings, clip.rate)
        val from = start + clip.offset(interval.start)
        val to = start + clip.offset(interval.end)
        return from..maxOf(from, to)
    }

    /**
     * The stretch a press of Listen plays: three quarters of a second on
     * each side of [range], inside a movie [duration] long
     * (YouRec/EditorTools.swift:1020-1026).
     */
    fun listening(range: ClosedFloatingPointRange<Double>, duration: Double): ClosedFloatingPointRange<Double> =
        maxOf(0.0, range.start - LISTEN_MARGIN)..minOf(duration, range.endInclusive + LISTEN_MARGIN)

    /** The pause of [choices] whose clip is [anchor]'s and whose start is nearest to its own, to stay on after a scan (YouRec/EditorTools.swift:974). */
    fun nearest(choices: List<PauseChoice>, anchor: PauseChoice?): Int? {
        anchor ?: return null
        return choices.indices.filter { choices[it].clipID == anchor.clipID }.minByOrNull { abs(choices[it].raw.start - anchor.raw.start) }
    }

    /** Seconds played before and after a pause by Listen (YouRec/EditorTools.swift:1025). */
    const val LISTEN_MARGIN = 0.75

    /** The ranges of the tool's sliders (YouRec/EditorTools.swift:1458-1465). */
    val THRESHOLD = -65.0..-15.0
    const val THRESHOLD_STEP = 1.0
    val MINIMUM = 0.1..3.0
    const val MINIMUM_STEP = 0.1
    val PADDING = 0.0..1.0
    const val PADDING_STEP = 0.02
}
