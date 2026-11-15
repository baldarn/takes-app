package app.takes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTimelineTest {
    private val first = SourceMedia(filename = "source.mp4", duration = 4.0)
    private val other = SourceMedia(filename = "next-scene.mp4", duration = 4.0)
    private val music = SourceMedia(filename = "music.m4a", duration = 60.0, audioOnly = true, originalName = "Song.m4a")

    // A project as 0.9.1 saved it, which keeps the track of each sound item; one saved since lays them by first fit.
    private fun project(clips: List<TimelineClip>, audio: List<AudioItem>? = null, frameRate: Int = 30) =
        LegacyProject.commit(Project("Interchange", sources = listOf(first, other, music), exportSettings = ExportSettings(frameRate = frameRate)), EditState(clips, audio = audio))
            .also { it.validate() }

    private fun joined(style: TransitionStyle, duration: Double): List<TimelineClip> {
        val right = TimelineClip(sourceID = other.id, start = 0.0, end = 3.0)
        val left = TimelineClip(sourceID = first.id, start = 0.0, end = 3.0, transition = TransitionSettings(nextClipID = right.id, style = style, duration = duration))
        return listOf(left, right)
    }

    private fun JsonObject.schema() = getValue("OTIO_SCHEMA").jsonPrimitive.content
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.children() = getValue("children").jsonArray.map { it.jsonObject }
    private fun JsonObject.frames(key: String) = getValue(key).jsonObject.getValue("value").jsonPrimitive.double
    private fun JsonObject.sourceStart() = getValue("source_range").jsonObject.frames("start_time")
    private fun JsonObject.length() = getValue("source_range").jsonObject.frames("duration")
    private fun JsonObject.reference() = getValue("media_references").jsonObject.getValue("DEFAULT_MEDIA").jsonObject
    private fun JsonObject.own() = getValue("metadata").jsonObject[OpenTimeline.METADATA]?.jsonObject

    private fun tracks(project: Project) = OpenTimeline.json(project).getValue("tracks").jsonObject.children()
    private fun markers(project: Project) = OpenTimeline.json(project).getValue("tracks").jsonObject.getValue("markers").jsonArray.map { it.jsonObject }
    private fun JsonObject.span() = getValue("marked_range").jsonObject.let { it.frames("start_time") to it.frames("duration") }

    /** Frames a track occupies: transitions sit on a cut and take no room. */
    private fun JsonObject.extent() = children().filter { it.schema() != "Transition.1" }.sumOf { it.length() }

    @Test fun cutsBecomeOneVideoTrackAndItsSound() {
        val project = project(listOf(TimelineClip(sourceID = first.id, start = 1.0, end = 3.0), TimelineClip(sourceID = other.id, start = 0.5, end = 2.0)))
        val document = OpenTimeline.json(project)
        assertEquals("Timeline.1", document.schema())
        assertEquals("Interchange", document.text("name"))
        assertEquals(0.0, document.frames("global_start_time"), 0.0)
        val stack = document.getValue("tracks").jsonObject
        assertEquals("Stack.1", stack.schema())

        val (sound, picture) = stack.children()
        assertEquals(listOf("Audio 1" to "Audio", "Video 1" to "Video"), listOf(sound, picture).map { it.text("name") to it.text("kind") })
        for (track in listOf(sound, picture)) {
            assertEquals("Track.1", track.schema())
            assertEquals(JsonNull, track.getValue("source_range"))
            val clips = track.children()
            assertEquals(listOf("Clip.2", "Clip.2"), clips.map { it.schema() })
            assertEquals(listOf(30.0, 15.0), clips.map { it.sourceStart() })
            assertEquals(listOf(60.0, 45.0), clips.map { it.length() })
            assertEquals(listOf("source.mp4", "next-scene.mp4"), clips.map { it.reference().text("target_url") })
            assertTrue(clips.all { it.getValue("enabled").jsonPrimitive.boolean })
            val reference = clips[0].reference()
            assertEquals("ExternalReference.1", reference.schema())
            assertEquals("DEFAULT_MEDIA", clips[0].text("active_media_reference_key"))
            assertEquals(0.0, reference.getValue("available_range").jsonObject.frames("start_time"), 0.0)
            assertEquals(120.0, reference.getValue("available_range").jsonObject.frames("duration"), 0.0)
        }
    }

    @Test fun timesUseTheExportFrameRate() {
        val clips = listOf(TimelineClip(sourceID = first.id, start = 1.0, end = 3.0))
        for (rate in listOf(24, 25, 60)) {
            val clip = tracks(project(clips, frameRate = rate)).last().children().single()
            val start = clip.getValue("source_range").jsonObject.getValue("start_time").jsonObject
            assertEquals("RationalTime.1", start.schema())
            assertEquals(rate.toDouble(), start.getValue("rate").jsonPrimitive.double, 0.0)
            assertEquals(rate * 1.0, clip.sourceStart(), 0.0)
            assertEquals(rate * 2.0, clip.length(), 0.0)
        }
    }

    // From the original: an isolated cross dissolve is editable on a single track.
    @Test fun dissolveIsATransitionOnTheCutWithMediaOnBothSides() {
        val project = project(joined(TransitionStyle.Dissolve, 1.0))
        assertTrue(OpenTimeline.text(project).contains("SMPTE_Dissolve"))
        // The clips overlap for a second: the timeline is five seconds, not six.
        for (track in tracks(project)) {
            val (outgoing, transition, incoming) = track.children()
            assertEquals("Transition.1", transition.schema())
            assertEquals("SMPTE_Dissolve", transition.text("transition_type"))
            assertEquals(15.0, transition.frames("in_offset"), 0.0)
            assertEquals(15.0, transition.frames("out_offset"), 0.0)
            // The cut is in the middle of the overlap.
            assertEquals(0.0 to 75.0, outgoing.sourceStart() to outgoing.length())
            assertEquals(15.0 to 75.0, incoming.sourceStart() to incoming.length())
            assertEquals(150.0, track.extent(), 0.0)
            // Each clip has the media the dissolve uses beyond the cut.
            assertTrue(outgoing.sourceStart() + outgoing.length() + transition.frames("out_offset") <= 90.0)
            assertTrue(incoming.sourceStart() - transition.frames("in_offset") >= 0.0)
        }
        assertTrue(markers(project).isEmpty())
    }

    @Test fun oddOverlapSplitsIntoWholeFrames() {
        // 0.5 s at 30 fps is 15 frames: 7 before the cut and 8 after.
        val project = project(joined(TransitionStyle.Dissolve, 0.5))
        val (outgoing, transition, incoming) = tracks(project).last().children()
        assertEquals(7.0 to 8.0, transition.frames("in_offset") to transition.frames("out_offset"))
        assertEquals(82.0, outgoing.length(), 0.0)
        assertEquals(7.0 to 83.0, incoming.sourceStart() to incoming.length())
    }

    @Test fun dissolveIsLimitedLikeTheEditor() {
        // Asked for 10 s, but a dissolve cannot take more footage than its clips have: here all 3 s of both.
        val clips = joined(TransitionStyle.Dissolve, 10.0)
        assertEquals(3.0, TimelineEditing.overlap(clips, 0), 1e-9)
        val (outgoing, transition, incoming) = tracks(project(clips)).last().children()
        assertEquals(90.0, transition.frames("in_offset") + transition.frames("out_offset"), 0.0)
        assertEquals(90.0, outgoing.length() + incoming.length(), 0.0)
    }

    @Test fun fadeThroughBlackStaysACutWithAMarker() {
        val project = project(joined(TransitionStyle.FadeColor, 1.0))
        for (track in tracks(project)) {
            assertEquals(listOf("Clip.2", "Clip.2"), track.children().map { it.schema() })
            assertEquals(listOf(90.0, 90.0), track.children().map { it.length() })
        }
        val marker = markers(project).single()
        assertEquals("Marker.2", marker.schema())
        assertEquals("Fade through black", marker.text("name"))
        assertEquals("PURPLE", marker.text("color"))
        // Half a second on each side of the cut at 3 s.
        assertEquals(75.0 to 30.0, marker.span())
    }

    @Test fun staleTransitionIsIgnored() {
        val clips = joined(TransitionStyle.Dissolve, 1.0)
        val project = project(listOf(clips[0], TimelineClip(sourceID = other.id, start = 0.0, end = 1.0), clips[1]))
        assertEquals(List(3) { "Clip.2" }, tracks(project).last().children().map { it.schema() })
    }

    @Test fun soundItemsGetTheirOwnTracksWithGaps() {
        val clips = listOf(TimelineClip(sourceID = first.id, start = 0.0, end = 4.0), TimelineClip(sourceID = other.id, start = 0.0, end = 4.0))
        val audio = listOf(
            AudioItem(sourceID = music.id, start = 1.0, sourceStart = 10.0, sourceEnd = 12.0, track = 0),
            AudioItem(sourceID = music.id, start = 5.0, sourceStart = 0.0, sourceEnd = 1.5, track = 0, audio = ClipAudio(volumeDB = -6.0, fadeOut = 0.5)),
            // Runs past the picture: only what plays is on the track.
            AudioItem(sourceID = music.id, start = 7.0, sourceStart = 20.0, sourceEnd = 30.0, track = 1),
        )
        val (clipSound, lower, upper, picture) = tracks(project(clips, audio))
        assertEquals(listOf("Audio 1", "Audio 2", "Audio 3", "Video 1"), listOf(clipSound, lower, upper, picture).map { it.text("name") })
        assertEquals(listOf("Audio", "Audio", "Audio", "Video"), listOf(clipSound, lower, upper, picture).map { it.text("kind") })

        assertEquals(listOf("Gap.1", "Clip.2", "Gap.1", "Clip.2", "Gap.1"), lower.children().map { it.schema() })
        assertEquals(listOf(30.0, 60.0, 60.0, 45.0, 45.0), lower.children().map { it.length() })
        assertEquals(listOf(300.0, 0.0), lower.children().filter { it.schema() == "Clip.2" }.map { it.sourceStart() })
        val item = lower.children()[3]
        assertEquals("Song.m4a", item.text("name"))
        assertEquals("music.m4a", item.reference().text("target_url"))
        assertEquals(1800.0, item.reference().getValue("available_range").jsonObject.frames("duration"), 0.0)
        assertEquals(-6.0, item.own()!!.getValue("volumeDB").jsonPrimitive.double, 0.0)
        assertEquals(0.5, item.own()!!.getValue("fadeOut").jsonPrimitive.double, 0.0)

        assertEquals(listOf("Gap.1", "Clip.2"), upper.children().map { it.schema() })
        assertEquals(listOf(210.0, 30.0), upper.children().map { it.length() })
        assertEquals(600.0, upper.children()[1].sourceStart(), 0.0)
        // Every track is as long as the picture.
        for (track in listOf(clipSound, lower, upper, picture)) assertEquals(240.0, track.extent(), 0.0)
    }

    @Test fun onlyUsedSoundTracksAreWritten() {
        val clips = listOf(TimelineClip(sourceID = first.id, start = 0.0, end = 4.0))
        val audio = listOf(AudioItem(sourceID = music.id, start = 0.0, sourceStart = 0.0, sourceEnd = 4.0, track = 1))
        val written = tracks(project(clips, audio))
        assertEquals(listOf("Audio 1", "Audio 2", "Video 1"), written.map { it.text("name") })
        assertEquals(listOf("Clip.2"), written[1].children().map { it.schema() })
        // Sound that begins after the picture ends is not played at all.
        val late = listOf(AudioItem(sourceID = music.id, start = 4.0, sourceStart = 0.0, sourceEnd = 4.0))
        assertEquals(listOf("Gap.1"), tracks(project(clips, late))[1].children().map { it.schema() })
    }

    @Test fun mutedClipIsDisabledOnTheSoundTrackOnly() {
        val clip = TimelineClip(sourceID = first.id, start = 0.0, end = 2.0, audio = ClipAudio(muted = true, volumeDB = 3.0))
        val (sound, picture) = tracks(project(listOf(clip)))
        assertFalse(sound.children().single().getValue("enabled").jsonPrimitive.boolean)
        assertEquals(3.0, sound.children().single().own()!!.getValue("volumeDB").jsonPrimitive.double, 0.0)
        assertTrue(picture.children().single().getValue("enabled").jsonPrimitive.boolean)
        assertEquals(null, picture.children().single().own())
    }

    @Test fun bookmarksAndTitlesAreMarkersAtTimelineTime() {
        val clips = listOf(
            TimelineClip(sourceID = first.id, start = 1.0, end = 3.0),
            TimelineClip(
                sourceID = other.id, start = 1.0, end = 3.0,
                bookmarks = listOf(Bookmark(time = 2.5, label = "Laugh"), Bookmark(time = 1.5), Bookmark(time = 0.5)),
                // Trimmed at its start; the second is outside the clip.
                texts = listOf(TextOverlay(text = "Hello\nworld", start = 0.0, end = 2.0, x = 0.25, y = 0.75), TextOverlay(start = 3.0, end = 4.0)),
            ),
        )
        val (unnamed, named, title) = markers(project(clips))
        assertEquals(listOf("Bookmark", "YELLOW"), listOf(unnamed.text("name"), unnamed.text("color")))
        assertEquals(75.0 to 0.0, unnamed.span())
        assertEquals("Laugh", named.text("name"))
        assertEquals(105.0 to 0.0, named.span())
        assertEquals(listOf("Hello world", "CYAN"), listOf(title.text("name"), title.text("color")))
        assertEquals(60.0 to 30.0, title.span())
        assertEquals("Hello\nworld", title.own()!!.text("text"))
        assertEquals(0.25 to 0.75, title.own()!!.getValue("x").jsonPrimitive.double to title.own()!!.getValue("y").jsonPrimitive.double)
        assertEquals(3, markers(project(clips)).size)
    }

    @Test fun mediaPathsAreRelativeURLs() {
        assertEquals("source.mp4", OpenTimeline.url("source.mp4"))
        assertEquals("My%20take%20%231.mp4", OpenTimeline.url("My take #1.mp4"))
        assertEquals("caff%C3%A8.m4a", OpenTimeline.url("caffè.m4a"))
    }

    @Test fun documentNameAvoidsMediaFiles() {
        assertEquals("Timeline.otio", OpenTimeline.filename(Project("Empty")))
        val taken = Project("Taken", sources = listOf(SourceMedia(filename = "timeline.OTIO", duration = 1.0), SourceMedia(filename = "Timeline-2.otio", duration = 1.0)))
        assertEquals("Timeline-3.otio", OpenTimeline.filename(taken))
    }

    @Test fun outputIsStableAndReadable() {
        val project = project(joined(TransitionStyle.Dissolve, 1.0))
        val text = OpenTimeline.text(project)
        assertEquals(text, OpenTimeline.text(project))
        assertEquals(OpenTimeline.json(project), Json.parseToJsonElement(text))
        assertTrue(text.startsWith("{\n    \"OTIO_SCHEMA\": \"Timeline.1\",\n    \"name\": \"Interchange\","))
        // No binary-fraction noise: a tenth of a second is three frames.
        val tenth = project(listOf(TimelineClip(sourceID = first.id, start = 0.1, end = 1.1)))
        assertEquals(3.0, tracks(tenth).last().children().single().sourceStart(), 0.0)
    }

    @Test fun emptyProjectIsAnEmptyTimeline() {
        val written = tracks(Project("Empty"))
        assertEquals(listOf("Audio 1", "Video 1"), written.map { it.text("name") })
        assertTrue(written.all { it.children().isEmpty() })
    }
}
