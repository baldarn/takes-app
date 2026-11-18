package app.takes.core.yourec

import app.takes.core.FontLibrary
import app.takes.core.Project
import app.takes.core.VideoEffectSettings

/**
 * The files the original says a project owns, `Project.mediaFiles`
 * (YouRec/Project.swift:286-302): the sources in their order, then, sorted,
 * the pictures, the effects' files and LUTs and the imported fonts of every
 * step of the history and of the takes. A ZIP of YouRec holds these and
 * nothing else (YouRec/ProjectArchive.swift:334-341).
 *
 * It is worked out on the project as Takes reads it, with the effects the
 * takes were recorded with ([sourceEffects]), which Takes keeps beside each
 * movie rather than in the project. The pictures on every step are its
 * timeline's when it has one; the takes not yet on a step are [Project.captureLedger].
 */
object YouRecMediaFiles {
    fun of(project: Project, sourceEffects: Collection<List<VideoEffectSettings>>): List<String> {
        val clips = project.snapshots.flatMap { step -> step.timeline?.video?.map { it.content } ?: step.state.clips } + project.captureLedger
        val images = clips.flatMap { clip -> clip.images.orEmpty().map { it.filename } }
        val effects = (sourceEffects.flatten() + clips.flatMap { it.videoEffects.orEmpty() }).flatMap { it.mediaFiles }
        val luts = clips.flatMap { clip -> clip.color?.lutAssets.orEmpty().map { it.filename } }
        val fonts = project.snapshots.flatMap { step ->
            step.timeline?.let { FontLibrary.files(it.video.map { item -> item.content }, it.subtitles) } ?: FontLibrary.files(step.state)
        } + FontLibrary.files(project.captureLedger, null)
        return project.sources.map { it.filename } + (images + effects + luts + fonts).toSortedSet()
    }

    /** The name of the OpenTimelineIO file the original writes beside [mediaFiles] (YouRec/EditorialTimeline.swift:16-21). */
    fun timelineName(mediaFiles: List<String>): String {
        val taken = mediaFiles.map { it.lowercase() }.toSet()
        var name = "Timeline.otio"
        var index = 1
        while (name.lowercase() in taken) { index += 1; name = "Timeline-$index.otio" }
        return name
    }
}
