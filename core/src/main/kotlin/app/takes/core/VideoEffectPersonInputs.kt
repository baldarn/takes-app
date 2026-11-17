package app.takes.core

/*
 * Which effects of a clip read a person mask on a frame, so that the mask is
 * segmented only when one of them is drawn. Ported from
 * YouRec/VideoEffects/VideoEffectAssets.swift:346-353 (cited as `Assets`) and
 * YouRec/VideoEffects/MetalVideoEffects.swift:634 (`Renderer`).
 */
object VideoEffectPersonInputs {
    /**
     * Whether [effect] reads the person mask at source second [time]: it is
     * on and the second is inside its range, and it is an Auto Background, a
     * Dolly Zoom, a Color Isolation keeping "The subject", a Quantization
     * with face detail at most four colours, or a Chroma Key with the person
     * hint. The intensity is not looked at. Assets:347-352.
     */
    fun needsPerson(effect: VideoEffectSettings, time: Double): Boolean {
        if (!(effect.enabled && time >= effect.start && time < effect.end)) return false
        return when (effect.kind) {
            VideoEffectID.AutoBackground, VideoEffectID.DollyZoom -> true
            VideoEffectID.ColorIsolation -> effect.choice("mode", time) == "The subject"
            VideoEffectID.Quantization -> effect.toggle("faceDetail", time) && effect.number("colors", time) <= 4
            VideoEffectID.ChromaKey -> effect.toggle("personHint", time)
            else -> false
        }
    }

    /** Whether any of [effects] reads the person mask at [time]: `needsPerson` of the original. Assets:346-353. */
    fun needsPerson(effects: List<VideoEffectSettings>, time: Double): Boolean = effects.any { needsPerson(it, time) }

    /**
     * The source seconds of [clip] on which some of its effects read the person mask, from the first such effect's
     * start to the last one's end inside the clip; null when none does. The choices that decide it hold between
     * keyframes, so an effect is looked at where it begins in the clip and at each of its keyframes there. The export
     * has these masks made before it draws, as the original's renderer prepares them (MediaRenderer.swift:361-404).
     */
    fun personSpan(clip: TimelineClip): ClosedFloatingPointRange<Double>? {
        var from = Double.POSITIVE_INFINITY
        var to = Double.NEGATIVE_INFINITY
        for (effect in clip.videoEffects.orEmpty()) {
            val start = maxOf(clip.start, effect.start)
            val end = minOf(clip.end, effect.end)
            if (end <= start) continue
            val times = listOf(start) + effect.parameters.flatMap { p -> p.keyframes.map { it.time } }.filter { it > start && it < end }
            if (times.none { needsPerson(effect, it) }) continue
            from = minOf(from, start)
            to = maxOf(to, end)
        }
        return if (from < to) from..to else null
    }

    /**
     * Whether [effect] cannot be drawn without the mask: an Auto Background,
     * a Dolly Zoom without a depth map ([hasDepth]), a Color Isolation
     * keeping "The subject". The original stops the frame with
     * `unavailableMask` then; the others draw without it. Renderer:634.
     */
    fun requiresPerson(effect: VideoEffectSettings, time: Double, hasDepth: Boolean = false): Boolean = when (effect.kind) {
        VideoEffectID.AutoBackground -> true
        VideoEffectID.DollyZoom -> !hasDepth
        VideoEffectID.ColorIsolation -> effect.choice("mode", time) == "The subject"
        else -> false
    }
}
