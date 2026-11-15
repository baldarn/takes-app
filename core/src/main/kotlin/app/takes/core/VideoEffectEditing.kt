package app.takes.core

/** Putting video effects on clips, for the editor's Video effects tool. */
object VideoEffectEditing {
    /**
     * [state] with each clip named in [effects] carrying that list, in the
     * order given; an empty list takes the clip's effects off. Other clips are
     * left as they are. Null when no clip changes.
     */
    fun setEffects(state: EditState, effects: Map<String, List<VideoEffectSettings>>): EditState? {
        var changed = false
        val clips = state.clips.map { clip ->
            val list = effects[clip.id] ?: return@map clip
            val next = list.takeIf { it.isNotEmpty() }
            if (next == clip.videoEffects) clip else clip.copy(videoEffects = next).also { changed = true }
        }
        // Effects change no clip's place, length or sound, so the clips are replaced as they are:
        // the timeline's merge of a tool's clips (TrackWrapping.merged) does not carry this field.
        return if (changed) state.copy(clips = clips) else null
    }
}
