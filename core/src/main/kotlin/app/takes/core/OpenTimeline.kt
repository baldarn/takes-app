package app.takes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor

/**
 * The current edit as an OpenTimelineIO document, so the cuts can be continued
 * in another editor such as DaVinci Resolve. Media URLs are relative to the
 * document: the unzipped folder can move between machines.
 *
 * Times are frames at the project's export frame rate. Timeline edges are
 * rounded to whole frames, so every item is a whole number of frames long and
 * the tracks stay aligned; a clip's source start is kept exact.
 *
 * The stack holds, bottom to top: "Audio 1" with the clips' own sound, one
 * audio track for each sound track in use, then "Video 1".
 *
 * What another editor cannot take as an edit is left as a marker on the stack,
 * at timeline time: bookmarks (yellow), fades through black (purple, spanning
 * the fade) and titles (cyan, spanning the title, with its text and placement
 * in the metadata). Levels and fades of the sound travel as metadata too.
 */
object OpenTimeline {
    /** Key of this app's own values inside an OTIO metadata dictionary. */
    const val METADATA = "takes"

    private val format = Json { prettyPrint = true }

    /** The document's name in a folder that also holds the project's media. */
    fun filename(project: Project): String {
        val taken = project.mediaFiles.map { it.lowercase() }.toSet()
        var name = "Timeline.otio"
        var index = 1
        while (name.lowercase() in taken) { index += 1; name = "Timeline-$index.otio" }
        return name
    }

    fun text(project: Project): String = format.encodeToString(JsonObject.serializer(), json(project))

    fun json(project: Project): JsonObject = Conform(project).timeline()

    /** A picture clip placed on whole frames. [lead] is how many frames of its start an incoming dissolve gave away. */
    private class Placed(val clip: TimelineClip, val source: SourceMedia, val start: Double, val end: Double, val lead: Double)

    private class Conform(private val project: Project) {
        private val rate = project.exportSettings.frameRate.toDouble()
        private val clips = project.edit.clips
        private val sources = project.sources.associateBy { it.id }
        private val total = edge(TimelineEditing.duration(clips))

        /** Frames, without the noise of binary fractions: 0.1 s at 30 fps is 3 frames, not 3.0000000000000004. */
        private fun frames(seconds: Double) = Math.round(seconds * rate * 1e6) / 1e6

        /** A timeline position on its nearest whole frame. */
        private fun edge(seconds: Double) = Math.round(seconds * rate).toDouble()

        private fun time(frames: Double) = buildJsonObject {
            put("OTIO_SCHEMA", "RationalTime.1"); put("rate", rate); put("value", frames)
        }

        private fun range(start: Double, length: Double) = buildJsonObject {
            put("OTIO_SCHEMA", "TimeRange.1"); put("start_time", time(start)); put("duration", time(length))
        }

        private fun metadata(values: (JsonObjectBuilder.() -> Unit)?) = buildJsonObject {
            if (values != null) put(METADATA, buildJsonObject(values))
        }

        /** The fields every composable OTIO object has, in one order, so documents compare line by line. */
        private fun item(
            schema: String, name: String, sourceRange: JsonObject? = null, enabled: Boolean = true,
            markers: List<JsonObject> = emptyList(), values: (JsonObjectBuilder.() -> Unit)? = null,
            fields: JsonObjectBuilder.() -> Unit = {},
        ) = buildJsonObject {
            put("OTIO_SCHEMA", schema)
            put("name", name)
            put("enabled", enabled)
            put("source_range", sourceRange ?: JsonNull)
            fields()
            put("effects", JsonArray(emptyList()))
            put("markers", JsonArray(markers))
            put("metadata", metadata(values))
        }

        private fun gap(length: Double) = item("Gap.1", "", range(0.0, length))

        private fun source(id: String) = sources[id] ?: throw ProjectException(ProjectException.Reason.MissingMedia)

        private fun media(source: SourceMedia, start: Double, length: Double, enabled: Boolean, sound: ClipAudio?) =
            item("Clip.2", source.originalName ?: source.filename, range(start, length), enabled, values = sound?.let { audio ->
                {
                    put("volumeDB", audio.volumeDB); put("muted", audio.muted)
                    put("fadeIn", audio.fadeIn); put("fadeOut", audio.fadeOut); put("denoise", audio.denoise)
                }
            }) {
                put("active_media_reference_key", "DEFAULT_MEDIA")
                put("media_references", buildJsonObject {
                    put("DEFAULT_MEDIA", buildJsonObject {
                        put("OTIO_SCHEMA", "ExternalReference.1")
                        put("name", source.originalName ?: source.filename)
                        put("target_url", url(source.filename))
                        put("available_range", range(0.0, frames(source.duration)))
                        put("metadata", metadata(null))
                    })
                })
            }

        private fun track(kind: String, number: Int, children: List<JsonObject>, length: Double): JsonObject {
            // Every track is as long as the picture.
            val padded = if (total > length) children + gap(total - length) else children
            return item("Track.1", "$kind $number") { put("kind", kind); put("children", JsonArray(padded)) }
        }

        /**
         * OTIO puts a dissolve on a cut, with spare media on both sides, while
         * here the two clips overlap. So the cut goes to the middle of the
         * overlap: the outgoing clip is shortened to it and the incoming one
         * starts later in its source. Each then has, past the cut, exactly the
         * media the dissolve needs, and the timeline keeps its length.
         */
        private val placed: List<Placed> = run {
            var start = 0.0
            var lead = 0.0
            clips.mapIndexed { index, clip ->
                val begins = TimelineEditing.clipStart(clips, index)
                val nextBegins = TimelineEditing.clipStart(clips, index + 1)
                val first = edge(nextBegins)
                val last = edge(begins + clip.duration)
                val cut = if (index == clips.lastIndex) total else floor((first + last) / 2)
                Placed(clip, source(clip.sourceID), start, cut, lead).also {
                    start = cut
                    // Zero for a cut or a fade; the exact distance from where the next clip really begins otherwise.
                    lead = if (last > first) frames(cut / rate - nextBegins) else 0.0
                }
            }
        }

        /** The dissolve on the cut where [index] begins, when both of its clips are on the track. */
        private fun dissolve(index: Int): JsonObject? {
            val incoming = placed[index]
            val outgoing = placed.getOrNull(index - 1) ?: return null
            if (outgoing.end <= outgoing.start || incoming.end <= incoming.start) return null
            val first = edge(TimelineEditing.clipStart(clips, index))
            val last = edge(TimelineEditing.clipStart(clips, index - 1) + outgoing.clip.duration)
            if (last <= first) return null
            return buildJsonObject {
                put("OTIO_SCHEMA", "Transition.1")
                put("name", "Dissolve")
                put("transition_type", "SMPTE_Dissolve")
                // How far it reaches back into the outgoing clip, and forward into the incoming one.
                put("in_offset", time(incoming.start - first))
                put("out_offset", time(last - incoming.start))
                put("metadata", metadata(null))
            }
        }

        /** The clips in order: the picture, or their own sound cut the same way and cross-faded where the picture dissolves. */
        private fun clipTrack(kind: String): JsonObject {
            val children = mutableListOf<JsonObject>()
            placed.forEachIndexed { index, piece ->
                // A clip shorter than a frame cannot be placed; its neighbors meet instead.
                if (piece.end <= piece.start) return@forEachIndexed
                dissolve(index)?.let { children += it }
                val sound = piece.clip.audio.takeIf { kind == "Audio" }
                children += media(piece.source, frames(piece.clip.start) + piece.lead, piece.end - piece.start, sound?.muted != true, sound)
            }
            return track(kind, 1, children, total)
        }

        /** Sound on its own tracks, with gaps where a track is silent. Sound past the last clip is not played. */
        private fun soundTracks(): List<JsonObject> {
            val items = project.edit.audio.orEmpty()
            return items.map { it.track }.distinct().sorted().mapIndexed { number, lane ->
                val children = mutableListOf<JsonObject>()
                var cursor = 0.0
                for (sound in items.filter { it.track == lane }.sortedBy { it.start }) {
                    val start = edge(sound.start)
                    val end = minOf(edge(sound.end), total)
                    if (end <= start) continue
                    if (start > cursor) children += gap(start - cursor)
                    children += media(source(sound.sourceID), frames(sound.sourceStart), end - start, sound.audio?.muted != true, sound.audio)
                    cursor = end
                }
                track("Audio", number + 2, children, cursor)
            }
        }

        private fun marker(name: String, start: Double, length: Double, color: String, values: (JsonObjectBuilder.() -> Unit)? = null) =
            buildJsonObject {
                put("OTIO_SCHEMA", "Marker.2")
                put("name", name)
                put("color", color)
                put("marked_range", range(start, length))
                put("comment", "")
                put("metadata", metadata(values))
            }

        private fun markers(): List<JsonObject> {
            val bookmarks = mutableListOf<Pair<Double, JsonObject>>()
            val fades = mutableListOf<JsonObject>()
            val titles = mutableListOf<JsonObject>()
            clips.forEachIndexed { index, clip ->
                val begins = TimelineEditing.clipStart(clips, index)
                // Only what is inside the visible part of the clip is on the timeline.
                for (mark in clip.bookmarks.orEmpty().filter { it.time >= clip.start && it.time < clip.end }) {
                    val at = edge(begins + clip.offset(mark.time))
                    bookmarks += at to marker(mark.label?.ifEmpty { null } ?: "Bookmark", at, 0.0, "YELLOW")
                }
                val fade = TimelineEditing.fadeLength(clips, index)
                if (fade > 0) {
                    val cut = begins + clip.duration
                    val start = edge(cut - fade)
                    fades += marker("Fade through black", start, edge(cut + fade) - start, "PURPLE")
                }
                for (title in clip.texts.orEmpty()) {
                    val start = edge(begins + clip.offset(maxOf(title.start, clip.start)))
                    val end = edge(begins + clip.offset(minOf(title.end, clip.end)))
                    if (end <= start) continue
                    titles += marker(title.text.lines().joinToString(" ") { it.trim() }.trim().ifEmpty { "Title" }, start, end - start, "CYAN") {
                        put("kind", "title"); put("text", title.text)
                        put("x", title.x); put("y", title.y); put("size", title.size)
                    }
                }
            }
            return bookmarks.sortedBy { it.first }.map { it.second } + fades + titles
        }

        fun timeline() = buildJsonObject {
            put("OTIO_SCHEMA", "Timeline.1")
            put("name", project.title)
            put("global_start_time", time(0.0))
            put("tracks", item("Stack.1", "tracks", markers = markers()) {
                put("children", JsonArray(listOf(clipTrack("Audio")) + soundTracks() + clipTrack("Video")))
            })
            put("metadata", metadata(null))
        }
    }

    /** A file name as a relative URL: everything but unreserved ASCII is percent-encoded. */
    internal fun url(filename: String): String = buildString {
        for (byte in filename.toByteArray()) {
            val character = byte.toInt().toChar()
            if (byte >= 0 && (character.isLetterOrDigit() || character in "-._~")) append(character)
            else append("%%%02X".format(byte.toInt() and 0xFF))
        }
    }
}
