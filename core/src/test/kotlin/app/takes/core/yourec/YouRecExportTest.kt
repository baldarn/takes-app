package app.takes.core.yourec

import app.takes.core.CaptureEffectsFile
import app.takes.core.CaptureEvent
import app.takes.core.ClipAudio
import app.takes.core.EffectValue
import app.takes.core.FrozenFrameSegment
import app.takes.core.Project
import app.takes.core.ProjectDisk
import app.takes.core.ProjectRestore
import app.takes.core.SourceMedia
import app.takes.core.SubtitleSettings
import app.takes.core.TextOverlay
import app.takes.core.TitleCard
import app.takes.core.LabelHeadline
import app.takes.core.TimelineEditing
import app.takes.core.TrackTimeline
import app.takes.core.TransitionStyle
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import app.takes.core.documentJson
import app.takes.core.sha256
import app.takes.core.toTimeline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A Takes project zipped for the iPhone (docs/IOS-INTEROP.md, 3.6 and 7.5 #3 to #6). */
class YouRecExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "Projects")
    private fun disk() = ProjectDisk(root)
    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }

    private val take = SourceMedia(filename = "1731400000000-take.mp4", duration = 4.0,
        events = listOf(CaptureEvent(time = 1.5, kind = Project.BOOKMARK_EVENT, detail = "")))
    private val scene = SourceMedia(filename = "1731400004000-scene.mp4", duration = 3.0)
    private val photo = SourceMedia(filename = "photo-1731400008000-x.mp4", duration = 3.0)
    private val music = SourceMedia(filename = "audio-song.m4a", duration = 30.0, audioOnly = true)
    private val fakePng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    /** [project] saved with made-up media, and [extra] files beside it. */
    private fun save(project: Project, extra: Map<String, ByteArray> = emptyMap()): File {
        disk().save(project)
        val folder = disk().folder(project.id)
        project.mediaFiles.forEachIndexed { index, name -> File(folder, name).writeBytes(bytes(2000 + index, index.toLong())) }
        for ((name, data) in extra) File(folder, name).writeBytes(data)
        return folder
    }

    /** Takes as recorded here: a gain, marks, a dissolve, a title of an older project, captions, a sound file, a take inserted at a place, a photo. */
    private fun trip(): Project {
        var project = Project("Trip").addRecording(take, gainDB = -6.0).addRecording(scene).addRecording(photo).addSource(music)
        project = project.commit(TimelineEditing.setTransition(project.edit, 0, 1.0, TransitionStyle.Dissolve)!!)
        project = project.commit(TimelineEditing.addAudio(project.edit, music.id, music.duration, 2.0)!!)
        val edit = project.edit
        project = project.commit(edit.copy(
            clips = edit.clips.mapIndexed { index, clip ->
                if (index != 0) clip else clip.copy(texts = listOf(TextOverlay(text = "Old title"), TextOverlay.fresh().copy(text = "New")),
                    audio = (clip.audio ?: ClipAudio()).copy(denoise = 0.5, fadeIn = 0.5))
            },
            subtitles = SubtitleSettings(timingFingerprint = "x"),
        ))
        val inserted = SourceMedia(filename = "1731400012000-inserted.mp4", duration = 2.0)
        return project.beginRecordingInsertion(1.0).addRecording(inserted)
    }

    private fun zip(plan: YouRecExport.Plan): ByteArray =
        ByteArrayOutputStream().also { YouRecArchive.write(plan, it, 0, png = { fakePng }) }.toByteArray()

    private fun entries(data: ByteArray): LinkedHashMap<String, ByteArray> {
        val file = File(temporary.root, "out-${System.nanoTime()}.zip").apply { writeBytes(data) }
        val result = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { zip -> for (entry in zip.entries()) result[entry.name] = zip.getInputStream(entry).readBytes() }
        return result
    }

    private fun json(data: ByteArray) = documentJson.parseToJsonElement(data.decodeToString()) as JsonObject

    /** 7.5 #3: what the original requires of every exported project. */
    private fun assertShape(plan: YouRecExport.Plan, project: Project) {
        val data = zip(plan)
        val entries = entries(data)
        val manifest = json(entries.getValue("project.json"))
        assertTrue(SwiftRequiredKeys.problems(manifest, plan.stepTrees).isEmpty())
        assertEquals(1, (manifest["automaticCanvasVersion"] as JsonPrimitive).intOrNull)
        assertEquals(1, (manifest["recordingHistoryVersion"] as JsonPrimitive).intOrNull)
        assertFalse("captureInsertions" in manifest || "recordingInsertion" in manifest || "recordingSite" in manifest)
        val schema = (manifest["schemaVersion"] as JsonPrimitive).intOrNull!!
        assertTrue(schema in 6..YouRecCodec.LATEST_READABLE_SCHEMA)
        assertEquals(plan.schema, schema)
        val names = (manifest["snapshotFiles"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(project.snapshots.size, names.size)
        val ledgerSize = (manifest["captureLedger"] as JsonArray).size
        for (name in names) {
            val step = entries.getValue("History/$name")
            assertEquals(sha256(step) + ".json", name)
            val tree = json(step)
            // Every step a timeline, with every take already on it.
            assertTrue((tree["state"] as JsonObject)["timeline"] is JsonObject)
            assertEquals(ledgerSize, (tree["captureCursor"] as JsonPrimitive).intOrNull)
            for (region in ((tree["state"] as JsonObject)["timeline"] as JsonObject)["placements"] as JsonArray) {
                assertEquals(0, ((region as JsonObject)["videos"] as JsonArray).size % 2)
            }
        }
        // The archive holds exactly what the original works out it owns, its timeline file under the name the original
        // gives it (YouRec/EditorialTimeline.swift:16-21), and nothing of Takes' own.
        val media = YouRecMediaFiles.of(manifest, names.distinct().map { json(entries.getValue("History/$it")) })
        val otio = YouRecMediaFiles.timelineName(media)
        assertEquals(media.toSet() + "project.json" + otio + YouRecArchive.INTEGRITY + names.map { "History/$it" }, entries.keys)
        assertTrue(entries.keys.none { it.startsWith("Recovery/") })
        assertTrue(json(entries.getValue(otio))["OTIO_SCHEMA"].toString().contains("Timeline"))
        for (name in media) {
            assertTrue(name, name in (manifest["sources"] as JsonArray).map { ((it as JsonObject)["filename"] as JsonPrimitive).content } ||
                name.startsWith("image-") || name.startsWith("font-") || name.startsWith("effect-"))
        }
        // integrity.json as the original writes it: the media only, version 1.
        val integrity = json(entries.getValue(YouRecArchive.INTEGRITY))
        assertEquals(setOf("sha256", "version"), integrity.keys)
        assertEquals(1, (integrity["version"] as JsonPrimitive).intOrNull)
        assertEquals(media.toSet(), (integrity["sha256"] as JsonObject).keys)
        for ((name, hash) in integrity["sha256"] as JsonObject) assertEquals(sha256(entries.getValue(name)), (hash as JsonPrimitive).content)
        // Every entry stored, its sizes in its local header, no data descriptor.
        val file = File(temporary.root, "stored.zip").apply { writeBytes(data) }
        ZipFile(file).use { zip -> for (entry in zip.entries()) assertEquals(entry.name, ZipEntry.STORED, entry.method) }
        RandomAccessFile(file, "r").use { raw ->
            var offset = 0L
            while (true) {
                raw.seek(offset)
                if (Integer.reverseBytes(raw.readInt()) != 0x04034b50) break
                raw.seek(offset + 6)
                val flags = java.lang.Short.reverseBytes(raw.readShort()).toInt()
                assertEquals(0, flags and 0x08)
                raw.seek(offset + 18)
                val compressed = Integer.reverseBytes(raw.readInt()).toLong()
                raw.seek(offset + 26)
                val nameLength = java.lang.Short.reverseBytes(raw.readShort()).toInt()
                val extraLength = java.lang.Short.reverseBytes(raw.readShort()).toInt()
                offset += 30 + nameLength + extraLength + compressed
            }
        }
    }

    @Test fun aTakesProjectBecomesWhatTheOriginalRequires() {
        val project = trip()
        val folder = save(project)
        val plan = YouRecExport.plan(project, folder)
        assertEquals(emptyList<YouRecExport.Issue>(), plan.blockers)
        assertEquals(setOf(YouRecExport.Issue.Kind.CaptionRegrouping, YouRecExport.Issue.Kind.RecordingRunDropped), plan.losses.map { it.kind }.toSet())
        assertShape(plan, project)
        val manifest = plan.manifestTree(emptyMap())
        // Captions raise the original's schema to 8, a fade to 11.
        assertEquals(11, plan.schema)
        // The ledger: the takes, the inserted one flagged, without what only Takes' clips carry.
        val ledger = (manifest["captureLedger"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf(false, false, false, true), ledger.map { (it["insertion"] as JsonPrimitive).content.toBoolean() })
        assertTrue(ledger.none { "audio" in (it["clip"] as JsonObject) || "bookmarks" in (it["clip"] as JsonObject) })
        val sources = (manifest["sources"] as JsonArray).map { it as JsonObject }
        assertEquals(JsonPrimitive(true), sources[2]["isStillPhoto"])
        assertFalse("isStillPhoto" in sources[0])
        assertEquals(project.createdAt / 1000.0 - 978_307_200.0, (manifest["createdAt"] as JsonPrimitive).doubleOrNull!!, 1e-6)
        val timeline = (plan.stepTrees[project.historyCursor]["state"] as JsonObject)["timeline"] as JsonObject
        // The recording gain and the marks of a take are on the step, not left to the ledger.
        val sounds = (timeline["audio"] as JsonArray).map { it as JsonObject }
        assertTrue(sounds.any { (it["volumeDB"] as JsonPrimitive).doubleOrNull == -6.0 })
        assertEquals(1, (timeline["bookmarks"] as JsonArray).size)
        // T5 and T7.
        val transition = ((timeline["transitions"] as JsonArray).first() as JsonObject)["settings"] as JsonObject
        assertTrue(listOf("axis", "divider", "reverse", "audio", "first", "second").all { it in transition })
        val texts = ((timeline["video"] as JsonArray).map { (it as JsonObject)["content"] as JsonObject }.first { "texts" in it }["texts"] as JsonArray).map { it as JsonObject }
        assertTrue(texts.all { it["style"] == JsonPrimitive("bold") && it["color"] == JsonPrimitive("white") && it["appearance"] is JsonObject })
        assertEquals(JsonPrimitive(true), (texts[0]["appearance"] as JsonObject)["outlined"])
        assertEquals(JsonPrimitive(""), (timeline["subtitles"] as JsonObject)["timingFingerprint"])
    }

    // Tests/TitleDesignTests.swift:54-59, YouRec/Project.swift:333-335 at dadc258: a card goes to the iPhone at schema 14, with every key Swift requires, and comes back.
    @Test fun aCardOfALabelAndAHeadlineGoesAtSchemaFourteen() {
        var project = trip()
        val plain = YouRecExport.plan(project, save(project))
        assertEquals("Without a card the schema stays where it was", 11, plain.schema)
        val group = LabelHeadline().let { it.copy(card = it.card.copy(headlineOffset = TitleCard.Offset(-0.4, -0.2))) }
        val edit = project.edit
        project = project.commit(edit.copy(clips = edit.clips.mapIndexed { index, clip ->
            if (index != 0) clip else clip.copy(texts = clip.texts.orEmpty() + TextOverlay.fresh().copy(text = "Headline", labelHeadline = group))
        }))
        val folder = save(project)
        val plan = YouRecExport.plan(project, folder)
        assertEquals(emptyList<YouRecExport.Issue>(), plan.blockers)
        assertEquals(14, plan.schema)
        assertShape(plan, project)
        val timeline = (plan.stepTrees[project.historyCursor]["state"] as JsonObject)["timeline"] as JsonObject
        val texts = (timeline["video"] as JsonArray).map { (it as JsonObject)["content"] as JsonObject }.flatMap { (it["texts"] as? JsonArray).orEmpty() }.map { it as JsonObject }
        val card = texts.mapNotNull { it["labelHeadline"] as? JsonObject }.single()
        assertTrue(listOf("label", "labelStyle", "headlineStyle", "labelScale", "headlineScale", "card").all { it in card })
        val frame = card["card"] as JsonObject
        assertTrue(listOf("fill", "border", "borderWidth", "padding", "radius", "spacing", "headlineOffset").all { it in frame })
        assertFalse("A nil offset is left out, as Swift leaves it", "labelOffset" in frame)
        val restored = ProjectRestore.restoreArchive(ByteArrayInputStream(zip(plan)), File(temporary.root, "Card"), Long.MAX_VALUE).project
        assertEquals(group, restored.edit.toTimeline().video.flatMap { it.content.texts.orEmpty() }.mapNotNull { it.labelHeadline }.single())
    }

    /** 7.5 #4: Takes → iPhone → Takes gives every step's timeline back. */
    @Test fun aTakesProjectComesBackFromTheIPhoneForm() {
        val project = trip()
        val folder = save(project)
        val restored = ProjectRestore.restoreArchive(ByteArrayInputStream(zip(YouRecExport.plan(project, folder))), File(temporary.root, "Back"), Long.MAX_VALUE).project
        assertEquals(project.snapshots.size, restored.snapshots.size)
        assertEquals(project.historyCursor, restored.historyCursor)
        // The documented changes: no caption fingerprint, a title without a design drawn as Takes draws it, with that design (T7, T9).
        fun normalized(timeline: TrackTimeline) = timeline.copy(
            subtitles = timeline.subtitles?.copy(timingFingerprint = ""),
            video = timeline.video.map { item -> item.copy(content = item.content.copy(texts = item.content.texts?.map { it.copy(appearance = it.design) })) },
        )
        for (index in project.snapshots.indices) {
            assertEquals("step $index", normalized(project.history[index].toTimeline()), normalized(restored.history[index].toTimeline()))
            assertEquals(project.history[index].outputSize, restored.history[index].outputSize)
        }
        assertEquals(project.sources.map { it.copy(sha256 = null, isStillPhoto = null) }, restored.sources.map { it.copy(sha256 = null, isStillPhoto = null) })
        assertEquals(project.title, restored.title)
        assertEquals(project.exportSettings, restored.exportSettings)
    }

    /** 7.5 #5: the original's projects → Takes → the original's form, equal but for what the spec lists. */
    @Test fun theOriginalsProjectsGoBackAsTheyCame() {
        for (case in YouRecFixtures.cases) {
            val fixture = YouRecFixtures.load(case)
            val imported = YouRecCodec.import(fixture.manifest, { fixture.steps.getValue(it) }) { app.takes.core.Size(1080.0, 1920.0) }
            // The fixtures share an id; each is a project of its own here.
            val project = imported.project.copy(id = app.takes.core.newID())
            disk().save(project)
            val folder = disk().folder(project.id)
            imported.mediaFiles.forEachIndexed { index, name -> File(folder, name).writeBytes(bytes(100 + index, index.toLong())) }
            for ((filename, effects) in imported.sourceEffects) CaptureEffectsFile.write(effects, File(folder, filename))
            // Its analysis is the original's, which goes back as it came.
            for (name in imported.mediaFiles.filter { it.startsWith("effect-") && it.endsWith(".json") }) File(folder, name).writeText("""{"version":1,"palettes":[],"masks":[]}""")
            val plan = YouRecExport.plan(project, folder)
            assertEquals(case, emptyList<YouRecExport.Issue>(), plan.blockers)
            assertShape(plan, project)
            val original = json(fixture.manifest)
            val exported = plan.manifestTree(emptyMap())
            for (key in listOf("title", "historyCursor")) assertEquals("$case $key", original[key], exported[key])
            assertEquals(case, normalize(original["exportSettings"]!!), normalize(exported["exportSettings"]!!))
            // The sources, but for their hashes, which the restore takes from integrity.json anyway.
            assertEquals(case, normalize(JsonArray((original["sources"] as JsonArray).map { JsonObject(it as JsonObject - "sha256") })), normalize(exported["sources"]!!))
            val ledger = original["captureLedger"] as JsonArray
            assertEquals(case, normalize(JsonArray(ledger.map { entry ->
                val clip = (entry as JsonObject)["clip"] as JsonObject
                JsonObject(entry + ("clip" to YouRecJson.timelineClipToYouRec(YouRecJson.timelineClipToTakes(clip) {})))
            })), normalize(exported["captureLedger"]!!))
            // Each step that held every take: its timeline, as the original wrote it.
            val names = (original["snapshotFiles"] as JsonArray).map { (it as JsonPrimitive).content }
            names.forEachIndexed { index, name ->
                val step = json(fixture.steps.getValue(name))
                if ((step["captureCursor"] as JsonPrimitive).intOrNull != ledger.size) return@forEachIndexed
                val state = step["state"] as JsonObject
                val expected = timeline(state["timeline"] as JsonObject)
                val actual = timeline((plan.stepTrees[index]["state"] as JsonObject)["timeline"] as JsonObject)
                assertEquals("$case step $index", expected, actual)
                // A frame kept from another take is now a fixed size.
                if ("canvasReference" !in state) assertEquals("$case step $index", state["outputSize"]?.let(::normalize), (plan.stepTrees[index]["state"] as JsonObject)["outputSize"]?.let(::normalize))
            }
        }
    }

    /** A step's timeline with the documented differences taken out (7.5 #5): titles as their designs, captions without a fingerprint. */
    private fun timeline(tree: JsonObject): JsonElement {
        val through = YouRecJson.trackTimelineToYouRec(YouRecJson.trackTimelineToTakes(tree) {})
        return normalize(through)
    }

    /** Numbers as numbers, sets and flat dictionaries in order, keys sorted; a sound item's absent optional lists as empty. */
    private fun normalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.toSortedMap().mapValues { (key, value) ->
            when {
                (key == "members" || key == "participants") && value is JsonArray -> JsonArray(value.map { it.toString() }.sorted().map { documentJson.parseToJsonElement(it) })
                key == "videos" && value is JsonArray -> normalize(YouRecJson.flatDictionaryToObject(value))
                else -> normalize(value)
            }
        })
        is JsonArray -> JsonArray(element.map(::normalize))
        is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null && element.content != "true" && element.content != "false") JsonPrimitive(element.doubleOrNull!!) else element
    }

    @Test fun effectFilesAreNamedAsTheOriginalRequires() {
        var project = Project("Effects").addRecording(take).addRecording(scene)
        val plate = "effect-picture-ab12.webp"
        val movie = "effect-${app.takes.core.newID()}.m4v"
        val quick = "effect-${app.takes.core.newID()}.m4v"
        val takesAnalysis = "effect-takes.json"
        val iphoneAnalysis = "effect-iphone.json"
        val key = VideoEffectSettings(VideoEffectID.ChromaKey).with("cleanPlate", EffectValue.Media(plate))
        val background = VideoEffectSettings(VideoEffectID.AutoBackground).with("media", EffectValue.Media(movie))
        val other = VideoEffectSettings(VideoEffectID.AutoBackground).with("media", EffectValue.Media(quick))
        val crash = VideoEffectSettings(VideoEffectID.CrashZoom).copy(analysisFile = takesAnalysis, analysisDataFile = "effect-takes.bin", capturedEffectID = app.takes.core.newID())
        val kept = VideoEffectSettings(VideoEffectID.CrashZoom).copy(analysisFile = iphoneAnalysis, analysisDataFile = "effect-iphone.bin")
        val edit = project.edit
        project = project.commit(edit.copy(clips = listOf(
            edit.clips[0].copy(videoEffects = listOf(key, crash)),
            edit.clips[1].copy(videoEffects = listOf(background, kept)),
        )))
        project = project.commit(project.edit.copy(clips = listOf(project.edit.clips[0], project.edit.clips[1].copy(videoEffects = listOf(other, kept)))))
        val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray() + bytes(100, 1)
        val mov = byteArrayOf(0, 0, 0, 20) + "ftypqt  ".toByteArray() + bytes(100, 2)
        val folder = save(project, mapOf(
            movie to mp4, quick to mov,
            takesAnalysis to """{"version":1,"palettes":{},"masks":[],"maskGaps":[]}""".toByteArray(),
            iphoneAnalysis to """{"version":1,"palettes":[],"masks":[]}""".toByteArray(),
        ))
        val plan = YouRecExport.plan(project, folder)
        assertEquals(emptyList<YouRecExport.Issue>(), plan.blockers)
        assertEquals(listOf(YouRecExport.Issue.Kind.AnalysisRecomputed), plan.losses.map { it.kind })
        assertShape(plan, project)
        val entries = entries(zip(plan))
        val png = plate.replace(".webp", ".png")
        assertArrayEquals(fakePng, entries.getValue(png))
        assertArrayEquals(mp4, entries.getValue(movie.replace(".m4v", ".mp4")))
        assertArrayEquals(mov, entries.getValue(quick.replace(".m4v", ".mov")))
        assertTrue(iphoneAnalysis in entries && "effect-iphone.bin" in entries)
        assertTrue(entries.keys.none { it.contains("takes") || it.endsWith(".webp") || it.endsWith(".m4v") })
        val steps = entries.filterKeys { it.startsWith("History/") }.values.joinToString { it.decodeToString() }
        assertTrue(png in steps && plate !in steps && takesAnalysis !in steps && iphoneAnalysis in steps)
    }

    @Test fun whatTheIPhoneCannotPlayOrReadStopsTheExport() {
        val webm = SourceMedia(filename = "import-clip.webm", duration = 2.0)
        var project = Project("Blocked").addRecording(take).addRecording(webm).addRecording(scene)
        val edit = project.edit
        project = project.commit(edit.copy(clips = edit.clips.mapIndexed { index, clip ->
            if (index == 2) clip.copy(start = 1.0, end = 1.0 + 1 / 30.0, frozenFrame = FrozenFrameSegment(1.0, 2.0)) else clip
        }))
        val folder = save(project)
        File(folder, take.filename).delete()
        val plan = YouRecExport.plan(project, folder, YouRecExport.Probe { file -> if (file.name == scene.filename) listOf("video/x-vnd.on2.vp9") else listOf("video/avc") })
        assertEquals(
            mapOf(
                YouRecExport.Issue.Kind.FrozenFrame to emptyList(),
                YouRecExport.Issue.Kind.MissingRecording to listOf(take.filename),
                YouRecExport.Issue.Kind.UnsupportedMedia to listOf(webm.filename, scene.filename),
            ),
            plan.blockers.associate { it.kind to it.files },
        )
        assertThrows(YouRecBlockedException::class.java) { zip(plan) }
    }

    @Test fun idsThatAreNotUUIDsBecomeUUIDsEverywhere() {
        val legacy = SourceMedia(id = "take-1", filename = "1731400000000-old.mp4", duration = 4.0)
        val project = Project("Old", id = "old-project").addRecording(legacy)
        val plan = YouRecExport.plan(project, save(project))
        assertTrue(SwiftRequiredKeys.problems(plan.manifestTree(emptyMap()), plan.stepTrees).isEmpty())
        val id = java.util.UUID.nameUUIDFromBytes("take-1".toByteArray()).toString().uppercase()
        val manifest = plan.manifestTree(emptyMap())
        assertEquals(JsonPrimitive(id), ((manifest["sources"] as JsonArray)[0] as JsonObject)["id"])
        assertTrue(plan.stepTrees.all { it.toString().contains("\"sourceID\":\"$id\"") && !it.toString().contains("take-1") })
    }
}
