package app.takes.core

/** Putting video effects on clips, for the editor's Video effects tool. */
object VideoEffectEditing {
    /**
     * [state] with each clip named in [effects] carrying that list, in the
     * order given; an empty list takes the clip's effects off. Other clips are
     * left as they are. Null when no clip changes.
     *
     * A held frame follows its Freeze Frame: the hold lasts the effect's
     * Hold duration, and a held frame whose Freeze Frame is taken off goes
     * with it (YouRec/VideoEffects/VideoEffectsEditor.swift:498, :576).
     */
    fun setEffects(state: EditState, effects: Map<String, List<VideoEffectSettings>>): EditState? {
        var changed = false
        val clips = state.clips.mapNotNull { clip ->
            val list = effects[clip.id] ?: return@mapNotNull clip
            val next = list.takeIf { it.isNotEmpty() }
            val hold = clip.frozenFrame?.let { frozen ->
                val freeze = next.orEmpty().firstOrNull { it.kind == VideoEffectID.FreezeFrame }
                if (freeze == null) {
                    changed = true
                    return@mapNotNull null
                }
                frozen.copy(duration = freeze.number("hold", clip.start).coerceIn(MINIMUM_HOLD, MAXIMUM_HOLD))
            }
            if (next == clip.videoEffects && hold == clip.frozenFrame) clip else clip.copy(videoEffects = next, frozenFrame = hold).also { changed = true }
        }
        // The clips are replaced as they are: the timeline's merge of a tool's clips (TrackWrapping.merged) does not
        // carry a held frame, and the conversion makes each clip's timing from its recipe (SourcePlaybackMap.of).
        return if (changed && clips.isNotEmpty()) state.copy(clips = clips) else null
    }

    /** The Hold duration's range (VideoEffectCatalog), which a saved hold must keep ([FrozenFrameSegment.valid]). */
    private const val MINIMUM_HOLD = 0.5
    private const val MAXIMUM_HOLD = 10.0
}
