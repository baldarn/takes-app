package app.takes.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored types of the track timeline: what they hold, what they answer, and the JSON they are written as. */
class TrackTimelineTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)
    private val clip = TimelineClip("C", "S", 2.0, 6.0)
    private val picture = VideoTimelineItem.of("C", ticks(10.0), clip, layer = 3)
    private val sound = AudioTimelineItem(
        id = "A", occurrenceID = "C", sourceID = "S", start = ticks(10.0), mapping = picture.mapping, followsVideo = "C",
    )

    // MARK: Playback map

    @Test
    fun aClipsOwnMapIsOneRunOfItsSourceAtItsSpeed() {
        assertEquals(SourcePlaybackMap(listOf(PlaybackRun(ticks(4.0), 2.0, 6.0))), SourcePlaybackMap.of(clip))
        val fast = SourcePlaybackMap.of(clip.copy(speed = SpeedSettings(rate = 2.0)))
        assertEquals(ticks(2.0), fast.duration)
        assertEquals(4.0, fast.source(ticks(1.0))!!, 1e-9)
    }

    @Test
    fun aMapAnswersSourceSecondsAndBack() {
        val map = SourcePlaybackMap(listOf(PlaybackRun(ticks(2.0), 0.0, 4.0), PlaybackRun(ticks(1.0), 4.0, 4.0, silent = true), PlaybackRun(ticks(3.0), 4.0, 7.0)))
        assertEquals(ticks(6.0), map.duration)
        assertEquals(2.0, map.source(ticks(1.0))!!, 1e-9)
        // A hold shows one second for its whole length and plays none: the second after it belongs to the next run.
        assertEquals(4.0, map.source(ticks(2.5))!!, 1e-9)
        assertEquals(5.0, map.source(ticks(4.0))!!, 1e-9)
        assertEquals(7.0, map.source(ticks(6.0))!!, 1e-9)
        assertNull(map.source(ticks(6.5)))
        assertEquals(ticks(0.5), map.local(1.0))
        assertEquals(ticks(3.0), map.local(4.0))
        assertEquals(ticks(5.0), map.local(6.0))
        assertNull(map.local(7.0))
        assertNull(map.local(-1.0))
    }

    @Test
    fun aSliceOfAMapKeepsTheSourceOfEachPart() {
        val map = SourcePlaybackMap(listOf(PlaybackRun(ticks(2.0), 0.0, 4.0), PlaybackRun(ticks(3.0), 4.0, 7.0, silent = true)))
        val part = map.sliced(EditInterval.of(1.0, 3.5))
        assertEquals(listOf(PlaybackRun(ticks(1.0), 2.0, 4.0), PlaybackRun(ticks(1.5), 4.0, 5.5, silent = true)), part.runs)
        assertEquals(map, map.sliced(EditInterval(EditTime.ZERO, map.duration)))
        assertTrue(map.sliced(EditInterval.of(9.0, 10.0)).runs.isEmpty())
    }

    // MARK: Items

    @Test
    fun aPictureIsNamedByItsRecipeAndEndsAfterItsMap() {
        assertEquals("C", picture.id)
        assertEquals(ticks(14.0), picture.end)
        assertEquals(EditInterval(ticks(10.0), ticks(14.0)), picture.interval)
        assertEquals(EditTime.ZERO, picture.localOrigin)
    }

    @Test
    fun picturesAreDrawnByLayerThenById() {
        val items = listOf(picture.copy(content = clip.copy(id = "B")), picture.copy(layer = 1), picture.copy(content = clip.copy(id = "A")))
        assertEquals(listOf("C", "A", "B"), items.sortedWith(VideoTimelineItem.backToFront).map { it.id })
    }

    @Test
    fun aPieceOfASoundKeepsItsFadesChainsAndCensors() {
        val whole = sound.copy(
            fadeIn = AudioEdgeFade(2.0), fadeOut = AudioEdgeFade(1.0),
            enhancements = listOf(AudioEnhancementRegion("E", EditInterval.of(0.5, 3.0), listOf(AudioEffect.make(AudioEffectKind.Gain, "G")))),
            censorRegions = listOf(AudioCensorRegion("X", EditInterval.of(3.0, 3.5), ticks(3.0), AudioCensorSettings())),
        )
        val later = whole.sliced(EditInterval.of(1.0, 4.0), "B")
        assertEquals("B", later.id)
        assertEquals(ticks(11.0), later.start)
        assertEquals(ticks(1.0), later.localOrigin)
        assertEquals(listOf(PlaybackRun(ticks(3.0), 3.0, 6.0)), later.mapping.runs)
        // The cut is half way up the fade in: the piece starts from there, on the same slope.
        assertEquals(AudioEdgeFade(2.0, 1.0), later.fadeIn)
        assertEquals(1.0, later.fadeSeconds(AudioFadeEdge.FadeIn), 1e-9)
        assertEquals(AudioEdgeFade(1.0), later.fadeOut)
        assertEquals(listOf(EditInterval.of(0.0, 2.0)), later.enhancements.map { it.interval })
        assertEquals(listOf(EditInterval.of(2.0, 2.5)), later.censors.map { it.interval })
        val earlier = whole.sliced(EditInterval.of(0.0, 2.5), "D")
        // The earlier piece begins where the item did, so its fade in is whole; all of the fade out is past its end.
        assertEquals(AudioEdgeFade(2.0), earlier.fadeIn)
        assertNull(earlier.fadeOut)
        assertNull(earlier.censorRegions)
        assertEquals(listOf(EditInterval.of(0.5, 2.5)), earlier.enhancements.map { it.interval })
    }

    @Test
    fun aSoundsGainIsItsLevelOrNothingWhenMuted() {
        assertEquals(1.0, sound.gain, 1e-12)
        assertEquals(0.5011872336, sound.copy(volumeDB = -6.0).gain, 1e-9)
        assertEquals(0.0, sound.copy(volumeDB = 6.0, muted = true).gain, 0.0)
        assertNull(sound.withCensors(emptyList()).censorRegions)
    }

    // MARK: Relations

    @Test
    fun aTransitionsEdgeFollowsThePicturesItNames() {
        val settings = TransitionSettings(id = "T", duration = 1.0)
        assertEquals(0, TimelineTransition("A", "B", settings, ticks(3.0)).edge)
        assertEquals(-1, TimelineTransition(null, "B", settings, ticks(3.0)).edge)
        assertEquals(1, TimelineTransition("A", null, settings, ticks(3.0)).edge)
        assertEquals("T", TimelineTransition("A", "B", settings, ticks(3.0)).id)
    }

    @Test
    fun aTransitionsStretchIsHeldAtZeroAndAStillNamesThePictureItFreezes() {
        val dissolve = TimelineTransition("A", "B", TransitionSettings(id = TrackFixtures.uuid(1), duration = 1.0), ticks(3.0))
        val timing = dissolve.timing
        assertEquals(
            TimelineGeneratedRegion(TrackTimeline.counterpart(dissolve.id), dissolve.id, EditInterval(ticks(3.0) + ticks(timing.lower), ticks(3.0) + ticks(timing.upper))),
            dissolve.generated,
        )
        val early = dissolve.copy(origin = ticks(-5.0)).generated
        assertEquals(EditInterval(EditTime.ZERO, EditTime.ZERO), early.interval)
        val still = TimelineTransition("A", "B", TransitionSettings(style = TransitionStyle.Still, duration = 1.0), ticks(3.0))
        assertEquals("A", still.generated.freezeVideo)
        assertNull(dissolve.generated.freezeVideo)
    }

    // MARK: Derived ids

    @Test
    fun aCounterpartFlipsTheHighestBitOfTheLastByteAsTheOriginalDoes() {
        // YouRec/TrackTimeline.swift:535-539: bytes.15 ^= 0x80.
        assertEquals("00000000-0000-4000-8000-00000000008F", TrackTimeline.counterpart("00000000-0000-4000-8000-00000000000F"))
        assertEquals("ABCDEF01-2345-4789-8ABC-DEF0123456F9", TrackTimeline.counterpart("ABCDEF01-2345-4789-8ABC-DEF012345679"))
        assertEquals("abcdef01-2345-4789-8abc-def012345619", TrackTimeline.counterpart("abcdef01-2345-4789-8abc-def012345699"))
        val id = newID()
        assertEquals(id, TrackTimeline.counterpart(TrackTimeline.counterpart(id)))
    }

    @Test
    fun derivedIdsAreDefinedForAnyStringAndNeverCollide() {
        assertEquals("clip~sound", TrackTimeline.counterpart("clip"))
        assertEquals("clip~item", TrackTimeline.alias("clip"))
        assertEquals("~sound", TrackTimeline.counterpart(""))
        val id = newID()
        assertEquals(3, setOf(id, TrackTimeline.counterpart(id), TrackTimeline.alias(id)).size)
        assertEquals(36, TrackTimeline.alias(id).length)
        // The same on every call.
        assertEquals(TrackTimeline.alias(id), TrackTimeline.alias(id))
    }

    // MARK: The timeline

    @Test
    fun theTimelineIsAsLongAsItsLastItemOrStretch() {
        assertEquals(EditTime.ZERO, TrackTimeline().duration)
        val timeline = TrackTimeline(video = listOf(picture), audio = listOf(sound.copy(start = ticks(12.0), followsVideo = null)))
        assertEquals(ticks(16.0), timeline.duration)
        val closing = timeline.copy(generated = listOf(TimelineGeneratedRegion("G", "T", EditInterval.of(15.0, 18.5))))
        assertEquals(ticks(18.5), closing.duration)
        assertEquals(setOf("C", "A"), timeline.allIDs)
    }

    @Test
    fun picturesInTheOrderTheyStartKeepTheirOrderWhereTheyStartTogether() {
        val items = listOf(picture.copy(content = clip.copy(id = "late")), picture.copy(start = ticks(1.0), content = clip.copy(id = "early")), picture.copy(content = clip.copy(id = "later")))
        assertEquals(listOf("early", "late", "later"), TrackTimeline(video = items).chronologicalVideo.map { it.id })
    }

    @Test
    fun aBookmarkIsWhereItsAnchorIsNow() {
        val held = picture.copy(content = clip.copy(id = "H"), occurrenceID = "H", start = ticks(20.0), localOrigin = ticks(1.0))
        val timeline = TrackTimeline(
            video = listOf(picture, held),
            audio = listOf(sound.copy(id = "M", occurrenceID = "M", sourceID = "music", start = ticks(30.0), followsVideo = null)),
            bookmarks = listOf(
                TimelineBookmark("project", anchor = BookmarkAnchor.Project(ticks(40.0))),
                TimelineBookmark("footage", anchor = BookmarkAnchor.Source("C", "S", 3.0)),
                TimelineBookmark("sound", anchor = BookmarkAnchor.Source("M", "music", 5.0)),
                TimelineBookmark("hold", anchor = BookmarkAnchor.Local("H", ticks(2.5))),
                TimelineBookmark("trimmed away", anchor = BookmarkAnchor.Source("C", "S", 1.0)),
                TimelineBookmark("gone", anchor = BookmarkAnchor.Local("nothing", ticks(1.0))),
            ),
        )
        assertEquals(
            listOf("footage" to ticks(11.0), "hold" to ticks(21.5), "sound" to ticks(33.0), "project" to ticks(40.0)),
            timeline.resolvedBookmarks.map { it.id to it.time },
        )
    }

    // MARK: JSON

    private val json = Json

    private fun keys(value: Any?) = (value as JsonObject).keys.toList()

    @Test
    fun theTimelineIsWrittenUnderTheOriginalsNames() {
        val timeline = TrackTimeline(
            video = listOf(picture),
            audio = listOf(sound),
            links = listOf(TimelineLink("L", setOf("C", "A"))),
            bookmarks = listOf(TimelineBookmark("B", "Mark", BookmarkAnchor.Source("C", "S", 3.0))),
            transitions = listOf(TimelineTransition(null, "C", TransitionSettings(id = "T", duration = 1.0), ticks(10.0), 0.5)),
            generated = listOf(TimelineGeneratedRegion("G", "T", EditInterval.of(10.0, 11.0))),
        )
        val root = json.parseToJsonElement(timeline.encoded()).jsonObject
        // YouRec/TrackTimeline.swift:490: the coding keys. `subtitles` is left out when there are none.
        assertEquals(listOf("video", "audio", "links", "bookmarks", "placements", "transitions", "generated"), root.keys.toList())
        assertEquals(listOf("occurrenceID", "start", "content", "layer", "localOrigin", "mapping"), keys(root["video"]!!.jsonArray[0]))
        // Optional keys of a sound are absent when unset; `enhancements` is always there.
        assertEquals(
            listOf("id", "occurrenceID", "sourceID", "start", "mapping", "localOrigin", "followsVideo", "volumeDB", "muted", "enhancements"),
            keys(root["audio"]!!.jsonArray[0]),
        )
        assertEquals("""{"runs":[{"duration":{"ticks":960000},"sourceStart":2.0,"sourceEnd":6.0,"silent":false}]}""", root["video"]!!.jsonArray[0].jsonObject["mapping"].toString())
        assertEquals("""{"ticks":2400000}""", root["video"]!!.jsonArray[0].jsonObject["start"].toString())
        assertEquals("""{"id":"L","members":["A","C"]}""", root["links"]!!.jsonArray[0].toString())
        assertEquals("""{"id":"B","label":"Mark","anchor":{"source":{"occurrence":"C","source":"S","time":3.0}}}""", root["bookmarks"]!!.jsonArray[0].toString())
        assertEquals(listOf("incoming", "settings", "origin", "factor"), keys(root["transitions"]!!.jsonArray[0]))
        assertEquals("""{"id":"G","owner":"T","interval":{"start":{"ticks":2400000},"end":{"ticks":2640000}}}""", root["generated"]!!.jsonArray[0].toString())
        assertEquals(timeline, TrackTimeline.decoded(timeline.encoded()))
    }

    @Test
    fun everyOptionalPartOfASoundIsWrittenAndReadBack() {
        val full = sound.copy(
            fadeIn = AudioEdgeFade(2.0, 0.5), fadeOut = AudioEdgeFade(1.0),
            enhancements = listOf(AudioEnhancementRegion("E", EditInterval.of(0.5, 3.0), listOf(AudioEffect.make(AudioEffectKind.Gain, "G")))),
            censorRegions = listOf(AudioCensorRegion("X", EditInterval.of(3.0, 3.5), ticks(3.0), AudioCensorSettings())),
            subtitleWords = listOf(SubtitleWord("W", "P", "word", 2.5, 3.0)),
        )
        val timeline = TrackTimeline(video = listOf(picture), audio = listOf(full), subtitles = SubtitleSettings(id = "U"))
        val root = json.parseToJsonElement(timeline.encoded()).jsonObject
        val item = root["audio"]!!.jsonArray[0].jsonObject
        assertTrue(item.keys.containsAll(listOf("fadeIn", "fadeOut", "censorRegions", "subtitleWords")))
        assertEquals("""{"seconds":2.0,"elapsed":0.5}""", item["fadeIn"].toString())
        assertEquals(listOf("id", "interval", "effects"), keys(item["enhancements"]!!.jsonArray[0]))
        assertTrue("subtitles" in root)
        assertEquals(timeline, TrackTimeline.decoded(timeline.encoded()))
    }

    @Test
    fun anAnchorIsWrittenAsSwiftWritesAnEnumWithValues() {
        fun written(anchor: BookmarkAnchor) = documentJson.encodeToString(BookmarkAnchor.serializer(), anchor)
        assertEquals("""{"source":{"occurrence":"O","source":"S","time":1.5}}""", written(BookmarkAnchor.Source("O", "S", 1.5)))
        assertEquals("""{"local":{"occurrence":"O","offset":{"ticks":240000}}}""", written(BookmarkAnchor.Local("O", ticks(1.0))))
        assertEquals("""{"project":{"_0":{"ticks":480000}}}""", written(BookmarkAnchor.Project(ticks(2.0))))
        for (anchor in listOf(BookmarkAnchor.Source("O", "S", 1.5), BookmarkAnchor.Local("O", ticks(1.0)), BookmarkAnchor.Project(ticks(2.0)))) {
            assertEquals(anchor, documentJson.decodeFromString(BookmarkAnchor.serializer(), written(anchor)))
        }
        assertThrows(SerializationException::class.java) { documentJson.decodeFromString(BookmarkAnchor.serializer(), "{}") }
        assertThrows(SerializationException::class.java) {
            documentJson.decodeFromString(BookmarkAnchor.serializer(), """{"project":{"_0":{"ticks":1}},"local":{"occurrence":"O","offset":{"ticks":1}}}""")
        }
    }

    @Test
    fun aPlacementRegionIsWrittenWithSortedIdsAndReadBack() {
        val region = VideoPlacementRegion("R", setOf("b", "a"), mapOf("b" to VideoPlacement(order = 1), "a" to VideoPlacement(x = 0.25, width = 0.5)))
        val written = documentJson.encodeToString(VideoPlacementRegion.serializer(), region)
        val root = json.parseToJsonElement(written).jsonObject
        assertEquals(JsonArray(listOf(json.parseToJsonElement("\"a\""), json.parseToJsonElement("\"b\""))), root["participants"])
        assertEquals(listOf("a", "b"), keys(root["videos"]))
        assertEquals(region, documentJson.decodeFromString(VideoPlacementRegion.serializer(), written))
        // The order a set was built in does not change what is written.
        assertEquals(written, documentJson.encodeToString(VideoPlacementRegion.serializer(), region.copy(participants = setOf("a", "b"))))
    }

    @Test
    fun aPictureWithoutItsMapIsNotReadAndAMissingListIsEmpty() {
        assertThrows(SerializationException::class.java) { TrackTimeline.decoded("""{"video":[{"occurrenceID":"C","start":{"ticks":0},"content":{"id":"C","sourceID":"S","start":0.0,"end":1.0}}]}""") }
        // A timeline with nothing in it reads from nothing: the arrays have defaults here.
        assertEquals(TrackTimeline(), TrackTimeline.decoded("{}"))
        assertFalse(TrackTimeline().encoded().contains("subtitles"))
    }
}
