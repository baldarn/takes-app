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
