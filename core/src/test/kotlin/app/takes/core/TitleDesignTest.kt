package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleDesignTest {
    private val frame = PauseDetector.FRAME

    // Translated from TitleDesignTests.swift.

    @Test fun shortTitleKeepsAReadableHold() {
        val motion = TitleMotion(entrance = TitleMove.Fade, exit = TitleMove.Wipe, inSeconds = 0.75, outSeconds = 0.75)
        for (t in listOf(0.13, 0.15, 0.17)) {
            val pose = motion.pose(t, 0.30)
            assertTrue(pose.opacity == 1.0 && pose.left == 0.0)
        }
    }

    @Test fun everyMoveAnimatesBothEndsAndHoldsInBetween() {
        for (mode in TitleMove.entries) {
            if (mode == TitleMove.None) continue
            val m = TitleMotion(entrance = mode, exit = mode)
            val early = m.pose(0.15, 2.0)
            val late = m.pose(2 - 0.15, 2.0)
            val mid = m.pose(1.0, 2.0)
            assertTrue(mid.opacity == 1.0 && mid.scale == 1.0 && mid.left == 0.0 && mid.right == 1.0)
            if (mode == TitleMove.Wipe) assertTrue(early.right < 1 && late.left > 0)
            else assertTrue(early.opacity < 1 && late.opacity < 1)
        }
    }

    @Test fun wipeRevealsFromItsDirection() {
        for (d in TitleDirection.entries) {
            val before = TitleMotion(entrance = TitleMove.Wipe, inDirection = d).pose(0.1, 2.0)
            when (d) {
                TitleDirection.Up -> assertTrue(before.top > 0 && before.bottom == 1.0)
                TitleDirection.Down -> assertTrue(before.top == 0.0 && before.bottom < 1)
                TitleDirection.Left -> assertTrue(before.left > 0 && before.right == 1.0)
                TitleDirection.Right -> assertTrue(before.left == 0.0 && before.right < 1)
            }
        }
    }

    @Test fun savedDesignAndMotionRoundTrip() {
        val design = TitleDesign(face = "fraunces", gradient = TitleGradient.gallery[0].second, outlined = true, plate = TitlePlate.Lines)
        val motion = TitleMotion(entrance = TitleMove.Fade, exit = TitleMove.Wipe, inSeconds = 0.75, outSeconds = 0.75, outDirection = TitleDirection.Left)
        assertEquals(design, documentJson.decodeFromString(TitleDesign.serializer(), documentJson.encodeToString(TitleDesign.serializer(), design)))
        assertEquals(motion, documentJson.decodeFromString(TitleMotion.serializer(), documentJson.encodeToString(TitleMotion.serializer(), motion)))
    }

    @Test fun durationPresetsNeverMoveStartOrRoundTheLength() {
        var timing = OverlayTiming(3.125..6.125, 1.25..70.38)
        for (seconds in EditorDurationPresets.wholeSeconds) {
            assertTrue(EditorDurationPresets.fits(seconds, timing.available))
            timing = timing.setting(OverlayTiming.Edge.End, timing.range.start + seconds)
            assertEquals(3.125..(3.125 + seconds), timing.range)
        }
        timing = timing.setting(OverlayTiming.Edge.Start, 62.0)
        // Overlong presets must not change Start.
        assertTrue(!EditorDurationPresets.fits(9.0, timing.available) && EditorDurationPresets.fits(8.0, timing.available))
        timing = timing.setting(OverlayTiming.Edge.End, timing.time(1.0, OverlayTiming.Edge.End))
        // A fractional final boundary must be reachable.
        assertEquals(timing.bounds.endInclusive, timing.range.endInclusive, 0.0)
        timing = timing.setting(OverlayTiming.Edge.End, timing.time(0.0, OverlayTiming.Edge.End))
        // Dragging End past Start must preserve a frame.
        assertEquals(frame, timing.duration, 0.000001)
    }

    @Test fun tinyTrimRangeStaysInsideItsBounds() {
        for (length in listOf(0.0, frame / 2, frame, frame * 1.4)) {
            val bounds = 8.125..(8.125 + length)
            var short = OverlayTiming(bounds, bounds)
            for (edge in OverlayTiming.Edge.entries) for (fraction in listOf(0.0, 0.1, 0.95, 1.0)) {
                short = short.setting(edge, short.time(fraction, edge))
                assertTrue(short.range.start in bounds && short.range.endInclusive in bounds && short.duration + 0.000001 >= minOf(length, frame))
            }
        }
    }

    // Own tests.

    @Test fun defaultsAreValidAndRangesAreEnforced() {
        val d = TitleDesign()
        assertTrue(d.valid)
        for ((_, look) in TitleDesign.looks) assertTrue(look.valid)
        assertFalse(d.copy(face = "").valid)
        assertFalse(d.copy(face = "é".repeat(128)).valid) // 256 bytes
        assertTrue(d.copy(face = "é".repeat(127)).valid)
        assertFalse(d.copy(weight = 99).valid); assertFalse(d.copy(weight = 1001).valid)
        assertFalse(d.copy(tracking = -0.06).valid); assertFalse(d.copy(tracking = 0.26).valid)
        assertFalse(d.copy(lineSpacing = 0.81).valid)
        assertFalse(d.copy(borderWidth = 0.21).valid)
        assertFalse(d.copy(shadowBlur = 0.61).valid)
        assertFalse(d.copy(shadowOffset = 0.51).valid); assertTrue(d.copy(shadowOffset = -0.5).valid)
        assertFalse(d.copy(padding = 0.81).valid)
        assertFalse(d.copy(radius = -0.01).valid)
        assertFalse(d.copy(gradient = TitleGradient(0xffffff, 0x000000, 361.0)).valid)
        assertFalse(d.copy(tracking = Double.NaN).valid)
        assertFalse(TitleMotion(inSeconds = 0.04).valid); assertFalse(TitleMotion(outSeconds = 5.1).valid)
        assertTrue(TitleMotion().valid && !TitleMotion().active)
    }

    @Test fun legacyStylesMapToDesigns() {
        val clean = TitleDesign.legacy(TextStyle.Clean, TextColor.White)
        assertEquals(TitleDesign(weight = 400), clean)
        // The default of an older title, bold white, is the default design.
        assertEquals(TitleDesign(), TitleDesign.legacy(TextStyle.Bold, TextColor.White))
        val box = TitleDesign.legacy(TextStyle.Box, TextColor.Yellow)
        assertEquals(TitlePlate.Block, box.plate)
        assertFalse(box.shadow)
        assertEquals(TitleInk.of(TextColor.Yellow), box.fill)
        assertEquals(TitleInk(0x050505, alpha = 209), box.backing)
        // A highlight is black words on the chosen color.
        val highlight = TitleDesign.legacy(TextStyle.Highlight, TextColor.Coral)
        assertEquals(TitleInk.BLACK, highlight.fill)
        assertEquals(TitleInk.of(TextColor.Coral), highlight.backing)
        assertEquals(700, highlight.weight)
        // Preset caption fonts keep their original names.
        assertEquals("Georgia-Bold", TitleDesign.legacy(TextStyle.Bold, TextColor.White, SubtitleFont.Classic).face)
        assertEquals("Georgia", TitleDesign.legacy(TextStyle.Clean, TextColor.White, SubtitleFont.Classic).face)
        assertEquals("system", TitleDesign.legacy(TextStyle.Bold, TextColor.White, SubtitleFont.System).face)
        for (style in TextStyle.entries) for (color in TextColor.entries) assertTrue(TitleDesign.legacy(style, color).valid)
    }

    @Test fun inkPacksRedGreenBlueAlpha() {
        assertEquals(0xffffffffL, TitleInk.WHITE.rgba)
        assertEquals(0x000000ffL, TitleInk.BLACK.rgba)
        val ink = TitleInk(0x112233, alpha = 0x80)
        assertEquals(0x11223380L, ink.rgba)
        assertEquals(0x112233L, ink.rgb)
        assertEquals(0x11 / 255.0, ink.red, 0.0); assertEquals(0x22 / 255.0, ink.green, 0.0); assertEquals(0x33 / 255.0, ink.blue, 0.0)
        assertEquals(128 / 255.0, ink.alpha, 0.0)
        // 0.03, 0.03, 0.04 → 8, 8, 10; 1, 0.85, 0.16 → 255, 217, 41.
        assertEquals(0x08080aL, TitleInk.of(TextColor.Black).rgb)
        assertEquals(0xffd929L, TitleInk.of(TextColor.Yellow).rgb)
        assertEquals("""{"rgba":4294967295}""", documentJson.encodeToString(TitleInk.serializer(), TitleInk.WHITE))
        assertEquals(TitleInk.WHITE, documentJson.decodeFromString(TitleInk.serializer(), """{"rgba":4294967295}"""))
    }

    @Test fun designWritesTheOriginalFields() {
        assertEquals(
            """{"face":"system","weight":700,"italic":false,"alignment":"center","tracking":0.0,"lineSpacing":0.0,"fill":{"rgba":4294967295},""" +
                """"outlined":false,"border":{"rgba":255},"borderWidth":0.045,"shadow":true,"shade":{"rgba":210},"shadowBlur":0.12,""" +
                """"shadowOffset":0.05,"plate":"off","backing":{"rgba":286398669},"padding":0.28,"radius":0.14}""",
            documentJson.encodeToString(TitleDesign.serializer(), TitleDesign()),
        )
        assertEquals(
            """{"entrance":"drift","exit":"none","inSeconds":0.35,"outSeconds":0.35,"inDirection":"up"}""",
            documentJson.encodeToString(TitleMotion.serializer(), TitleMotion(entrance = TitleMove.Drift, inDirection = TitleDirection.Up)),
        )
        assertEquals(
            """{"first":{"rgba":4293966591},"last":{"rgba":4118971391},"angle":105.0}""",
            documentJson.encodeToString(TitleGradient.serializer(), TitleGradient.gallery[0].second),
        )
    }

    @Test fun gradientAxisCoversTheBox() {
        val size = Size(200.0, 100.0)
        assertEquals(Point(0.0, 50.0) to Point(200.0, 50.0), TitleGradient(0, 0, 0.0).axis(size))
        val (top, bottom) = TitleGradient(0, 0, 90.0).axis(size)
        assertEquals(100.0, top.x, 1e-9); assertEquals(0.0, top.y, 1e-9)
        assertEquals(100.0, bottom.x, 1e-9); assertEquals(100.0, bottom.y, 1e-9)
        // On a diagonal the axis is long enough that both corners get an end color.
        val (a, b) = TitleGradient(0, 0, 45.0).axis(size)
        val length = Math.hypot(b.x - a.x, b.y - a.y)
        assertEquals((200 + 100) * Math.sqrt(0.5), length, 1e-9)
    }

    @Test fun lookKeepsTheOutlineAndLayoutKeyIgnoresColor() {
        val outlined = TitleDesign(outlined = true, border = TitleInk.opaque(0xff0000), borderWidth = 0.1)
        val next = outlined.applyingLook(TitleDesign.looks[2].second)
        assertEquals("barlowcondensed", next.face)
        assertTrue(next.outlined && next.border == outlined.border && next.borderWidth == 0.1)
        assertEquals(TitleDesign().layoutKey, TitleDesign(fill = TitleInk.opaque(0x00ff00), gradient = TitleGradient.gallery[3].second).layoutKey)
        assertFalse(TitleDesign().layoutKey == TitleDesign(weight = 400).layoutKey)
    }

    @Test fun bundledFacesResolveToResourceNames() {
        assertNull(TitleTypeface.resourceName(TitleDesign()))
        assertEquals("Fraunces-9ptBlackItalic", TitleTypeface.resourceName(TitleDesign(face = "fraunces", italic = true)))
        // No slanted file: the upright one is used.
        assertEquals("Caveat-Regular", TitleTypeface.resourceName(TitleDesign(face = "caveat", italic = true)))
        assertEquals("BarlowCondensed-Regular", TitleTypeface.resourceName(TitleDesign(face = "barlowcondensed", weight = 400)))
        assertEquals("BarlowCondensed-Bold", TitleTypeface.resourceName(TitleDesign(face = "barlowcondensed", weight = 700)))
        assertEquals("BarlowCondensed-BoldItalic", TitleTypeface.resourceName(TitleDesign(face = "barlowcondensed", weight = 700, italic = true)))
        assertEquals("BarlowCondensed-Italic", TitleTypeface.resourceName(TitleDesign(face = "barlowcondensed", weight = 400, italic = true)))
        assertEquals("IBMPlexMono-Medium", TitleTypeface.resourceName(TitleDesign(face = "ibmplexmono", weight = 500)))
        assertEquals("IBMPlexMono-MediumItalic", TitleTypeface.resourceName(TitleDesign(face = "ibmplexmono", weight = 500, italic = true)))
        assertEquals("Georgia-Bold", TitleTypeface.resourceName(TitleDesign(face = "Georgia-Bold")))
        for ((_, look) in TitleDesign.looks) assertTrue(TitleTypeface.family(look.face)!!.weights.contains(look.weight) || look.face == "archivoblack")
    }

    @Test fun importedFaceReferencesAreParsed() {
        val digest = "0123456789abcdef".repeat(4)
        val ref = ImportedFontReference.parse("import:$digest:1:My Font: Bold")!!
        assertEquals(ImportedFontReference(digest, 1, "My Font: Bold"), ref)
        assertEquals("font-$digest.sfnt", ref.filename)
        assertNull(TitleTypeface.resourceName(TitleDesign(face = "import:$digest:1:X")))
        assertNull(ImportedFontReference.parse("system"))
        assertNull(ImportedFontReference.parse("import:${digest.uppercase()}:0:X"))
        assertNull(ImportedFontReference.parse("import:${digest.dropLast(1)}:0:X"))
        assertNull(ImportedFontReference.parse("import:$digest:-1:X"))
        assertNull(ImportedFontReference.parse("import:$digest:0"))
    }

    @Test fun motionEndsShrinkToFitAShortTitle() {
        val motion = TitleMotion(entrance = TitleMove.Fade, exit = TitleMove.Fade, inSeconds = 1.0, outSeconds = 1.0)
        // 1 s on screen: 0.1 s stays readable and each end gets 0.45 s.
        assertEquals(1.0, motion.pose(0.45, 1.0).opacity, 1e-9)
        assertEquals(1.0, motion.pose(0.55, 1.0).opacity, 1e-9)
        assertTrue(motion.pose(0.44, 1.0).opacity < 1 && motion.pose(0.56, 1.0).opacity < 1)
        assertEquals(0.0, motion.pose(0.0, 1.0).opacity, 0.0)
        assertEquals(0.0, motion.pose(1.0, 1.0).opacity, 1e-9)
        assertEquals(0.0..0.8, motion.previewWindow(exit = false, duration = 1.0))
        val leaving = motion.previewWindow(exit = true, duration = 1.0)
        assertEquals(0.2, leaving.start, 1e-9); assertEquals(1.0, leaving.endInclusive, 0.0)
        assertEquals(TitleMotion.Pose(), TitleMotion().pose(0.0, 1.0))
    }

    @Test fun driftAndScaleSettleOnTheStillTitle() {
        val up = TitleMotion(entrance = TitleMove.Drift).pose(0.0, 2.0)
        assertEquals(0.32, up.rise, 1e-12) // Arrives from below, moving up.
        assertEquals(-0.32, TitleMotion(entrance = TitleMove.Drift, inDirection = TitleDirection.Down).pose(0.0, 2.0).rise, 1e-12)
        assertEquals(0.32, TitleMotion(entrance = TitleMove.Drift, inDirection = TitleDirection.Left).pose(0.0, 2.0).shift, 1e-12)
        assertEquals(0.32, TitleMotion(exit = TitleMove.Drift, outDirection = TitleDirection.Right).pose(2.0, 2.0).shift, 1e-12)
        assertEquals(0.88, TitleMotion(entrance = TitleMove.Scale).pose(0.0, 2.0).scale, 1e-12)
        assertEquals(1.08, TitleMotion(exit = TitleMove.Scale).pose(2.0, 2.0).scale, 1e-12)
    }

    @Test fun poseRectangleAndBitmapCrop() {
        val still = Rect(100.0, 50.0, 200.0, 40.0)
        assertEquals(still, TitleMotion.Pose().rect(still))
        assertEquals(Rect(100.0, 50.0, 100.0, 40.0), TitleMotion.Pose(right = 0.5).rect(still))
        assertEquals(Rect(200.0, 50.0, 100.0, 40.0), TitleMotion.Pose(left = 0.5).rect(still))
        assertEquals(Rect(150.0, 60.0, 100.0, 20.0), TitleMotion.Pose(scale = 0.5).rect(still))
        assertEquals(Rect(104.0, 58.0, 200.0, 40.0), TitleMotion.Pose(rise = 0.2, shift = 0.1).rect(still))
        assertEquals(Rect(0.0, 0.0, 300.0, 90.0), TitleMotion.Pose().crop(300, 90))
        // Whole pixels, never losing a partly visible one.
        assertEquals(Rect(0.0, 0.0, 101.0, 90.0), TitleMotion.Pose(right = 0.335).crop(300, 90))
        assertEquals(Rect(0.0, 44.0, 300.0, 46.0), TitleMotion.Pose(top = 0.495).crop(300, 90))
        assertNull(TitleMotion.Pose(right = 0.0).crop(300, 90))
    }

    @Test fun enumsKeepTheOriginalRawValues() {
        fun <T> text(serializer: kotlinx.serialization.KSerializer<T>, values: List<T>) = values.map { documentJson.encodeToString(serializer, it).trim('"') }
        assertEquals(listOf("clean", "bold", "box", "highlight"), text(TextStyle.serializer(), TextStyle.entries))
        assertEquals(listOf("white", "black", "yellow", "coral", "mint", "blue"), text(TextColor.serializer(), TextColor.entries))
        assertEquals(listOf("left", "center", "right"), text(TitleAlignment.serializer(), TitleAlignment.entries))
        assertEquals(listOf("off", "block", "lines"), text(TitlePlate.serializer(), TitlePlate.entries))
        assertEquals(listOf("none", "fade", "drift", "scale", "wipe"), text(TitleMove.serializer(), TitleMove.entries))
        assertEquals(listOf("up", "down", "left", "right"), text(TitleDirection.serializer(), TitleDirection.entries))
        assertEquals(listOf("system", "modern", "classic", "mono", "condensed"), text(SubtitleFont.serializer(), SubtitleFont.entries))
    }
}

/** Titles with a design or a motion in a project: the saved form, the schema and the motion's clock. */
class TitleProjectTest {
    private val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 10.0, recordedAt = 5)
    private val fade = TitleMotion(entrance = TitleMove.Fade, exit = TitleMove.Fade, inSeconds = 0.5, outSeconds = 0.5)

    private fun project(vararg texts: TextOverlay): Project {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val clip = base.edit.clips[0].copy(id = "C1", texts = texts.toList())
        return base.commit(EditState(listOf(clip), base.edit.captureBase), now = 3)
    }

    @Test fun aTitleWithoutDesignIsWrittenAsBeforeAndKeepsItsSchema() {
        val project = project(TextOverlay(id = "T", text = "Hello", start = 1.0, end = 3.0, y = 0.15, size = 0.1))
        assertEquals(2, project.schemaVersion)
        assertEquals(
            """{"state":{"clips":[{"id":"C1","sourceID":"S1","start":0.0,"end":10.0,"texts":[{"id":"T","text":"Hello","start":1.0,"end":3.0,"x":0.5,"y":0.15,"size":0.1}]}]},"captureCursor":1}""",
            project.snapshots[1].data().decodeToString(),
        )
        // And what an older version wrote reads back as the same title, with no design.
        val read = documentJson.decodeFromString(TextOverlay.serializer(), """{"id":"T","text":"Hello","start":1.0,"end":3.0,"x":0.5,"y":0.15,"size":0.1}""")
        assertEquals(TextOverlay(id = "T", text = "Hello", start = 1.0, end = 3.0, y = 0.15, size = 0.1), read)
        assertFalse(read.styled)
        assertEquals(TitleDesign.plain, read.design)
    }

    @Test fun aDesignOrAMotionRaisesTheSchemaSoOlderReadersRefuse() {
        val plain = TextOverlay(id = "T", text = "Hello", start = 1.0, end = 3.0)
        for (title in listOf(plain.copy(appearance = TitleDesign()), plain.copy(motion = fade), plain.copy(appearance = TitleDesign.looks[2].second, motion = fade))) {
            val project = project(title)
            assertEquals(12, project.schemaVersion)
            assertEquals(13, Project.LATEST_READABLE_SCHEMA) // the nine transitions came after
            project.validate()
            val files = mutableMapOf<String, ByteArray>()
            val manifest = project.manifest { name, data -> files[name] = data }
            val read = Project.readManifest(manifest) { files.getValue(it) }
            assertEquals(project, read)
            assertEquals(title, read.edit.clips[0].texts!![0])
            // A reader that knows schemas up to 11 checks the number first and stops.
            assertTrue(manifest.decodeToString().startsWith("""{"schemaVersion":12,"""))
        }
        // The other titles of a styled project are still written the old way.
        val mixed = project(plain, plain.copy(id = "U", motion = fade))
        assertTrue(mixed.snapshots[1].data().decodeToString().contains(""""texts":[{"id":"T","text":"Hello","start":1.0,"end":3.0,"x":0.5,"y":0.5,"size":0.065},{"id":"U","""))
        // Undoing the design does not lower the schema: the history still holds it.
        assertEquals(12, project(plain.copy(motion = fade)).undo().schemaVersion)
    }

    @Test fun aMotionIsWrittenWithTheOriginalFieldNames() {
        val title = TextOverlay(id = "T", text = "Hi", start = 0.0, end = 2.0, motion = TitleMotion(entrance = TitleMove.Wipe, inDirection = TitleDirection.Left))
        assertEquals(
            """{"id":"T","text":"Hi","start":0.0,"end":2.0,"x":0.5,"y":0.5,"size":0.065,"motion":{"entrance":"wipe","exit":"none","inSeconds":0.35,"outSeconds":0.35,"inDirection":"left"}}""",
            documentJson.encodeToString(TextOverlay.serializer(), title),
        )
        val designed = documentJson.encodeToString(TextOverlay.serializer(), title.copy(motion = null, appearance = TitleDesign()))
        assertTrue(designed.contains(""""appearance":{"face":"system","weight":700,"italic":false,"alignment":"center""""))
    }

    @Test fun invalidDesignsAndMotionsAreRefused() {
        val title = TextOverlay(text = "Hi", start = 0.0, end = 2.0)
        assertTrue(title.copy(appearance = TitleDesign(), motion = fade).valid)
        assertFalse(title.copy(appearance = TitleDesign(borderWidth = 0.5)).valid)
        assertFalse(title.copy(appearance = TitleDesign(tracking = Double.NaN)).valid)
        assertFalse(title.copy(appearance = TitleDesign(fill = TitleInk(-1))).valid)
        assertFalse(title.copy(appearance = TitleDesign(gradient = TitleGradient(TitleInk.WHITE, TitleInk(0x1ffffffffL), 10.0))).valid)
        assertFalse(title.copy(motion = fade.copy(inSeconds = 9.0)).valid)
        assertFalse(title.copy(motion = fade.copy(outSeconds = Double.NaN)).valid)
        val state = project(title).edit
        assertNull(TimelineEditing.setText(state, 0, title.copy(appearance = TitleDesign(weight = 5))))
    }

    @Test fun aNewTitleCarriesTheDefaultDesignWhichTellsItFromAnOlderOne() {
        val fresh = TextOverlay.fresh()
        // The original's default: bold white, a soft shadow, no outline.
        assertEquals(TitleDesign(), fresh.appearance)
        assertEquals(TitleDesign(), fresh.design)
        assertTrue(fresh.design.shadow && !fresh.design.outlined)
        assertEquals(0.045, fresh.design.borderWidth, 0.0)
        assertEquals("", fresh.text)
        assertTrue(fresh.valid && fresh.styled)
        // Saved, the design is written out, so the project is one older versions refuse instead of drawing an outline.
        val project = project(fresh.copy(id = "N", text = "New", start = 1.0, end = 4.0))
        assertEquals(12, project.schemaVersion)
        assertTrue(project.snapshots[1].data().decodeToString().contains(""""id":"N","text":"New","start":1.0,"end":4.0,"x":0.5,"y":0.5,"size":0.065,"appearance":{"face":"system","weight":700,"""))
        // A title of an older project has no design and still starts the tools from its outlined look.
        val older = TextOverlay(id = "T", text = "Hello", start = 1.0, end = 3.0)
        assertNull(older.appearance)
        assertEquals(TitleDesign.plain, older.design)
        assertFalse(older.styled)
    }

    @Test fun plainLookIsWhatAnUndesignedTitleShows() {
        val plain = TitleDesign.plain
        assertTrue(plain.valid && plain.outlined && !plain.shadow)
        assertEquals("system", plain.face)
        assertEquals(700, plain.weight)
        assertEquals(TitleInk.WHITE, plain.fill)
        assertEquals(TitleInk.BLACK, plain.border)
    }

    private fun clip(id: String, start: Double, end: Double, title: TextOverlay?, rate: Double = 1.0, join: TransitionSettings? = null) = TimelineClip(
        id = id, sourceID = "S1", start = start, end = end, texts = title?.let { listOf(it) }, transition = join,
        speed = if (rate == 1.0) null else SpeedSettings(rate = rate),
    )

    @Test fun motionRunsOnTheMovieClockAtAnySpeed() {
        val title = TextOverlay(id = "T", text = "Hi", start = 2.0, end = 6.0, motion = fade)
        val normal = TitleMotionRanges(listOf(clip("A", 0.0, 10.0, title)))
        assertEquals(2.0..6.0, normal.range(title, 3.0))
        assertNull(normal.range(title, 6.5))
        assertEquals(0.5, normal.pose(title, 2.25).opacity, 1e-9)
        assertEquals(1.0, normal.pose(title, 4.0).opacity, 0.0)
        assertEquals(0.5, normal.pose(title, 5.75).opacity, 1e-9)
        // Twice as fast: on screen from 1 s to 3 s, and the fade still takes half a second.
        val fast = listOf(clip("A", 0.0, 10.0, title, rate = 2.0))
        val quick = TitleMotionRanges(fast)
        assertEquals(1.0..3.0, quick.range(title, 2.0))
        assertEquals(0.5, quick.pose(title, 1.25).opacity, 1e-9)
        assertEquals(1.0, quick.pose(title, 1.5).opacity, 0.0)
        assertEquals(listOf(title), quick.shown(fast, 1.25).map { it.title })
        assertTrue(quick.shown(fast, 3.1).isEmpty())
        // A clip that starts inside the title begins the run where the title is first seen.
        val late = TitleMotionRanges(listOf(clip("A", 3.0, 10.0, title)))
        assertEquals(0.0..3.0, late.range(title, 1.0))
        assertEquals(0.5, late.pose(title, 0.25).opacity, 1e-9)
    }

    @Test fun aStillTitleHasNoRunAndIsDrawnWhole() {
        val title = TextOverlay(id = "T", text = "Hi", start = 2.0, end = 6.0)
        val clips = listOf(clip("A", 0.0, 10.0, title))
        val ranges = TitleMotionRanges(clips)
        assertTrue(ranges.isEmpty)
        assertNull(ranges.range(title, 3.0))
        assertEquals(listOf(TitleMotionRanges.Shown(title, 1.0, TitleMotion.Pose())), ranges.shown(clips, 2.0))
        assertTrue(TitleMotionRanges(listOf(clip("A", 0.0, 10.0, title.copy(motion = TitleMotion())))).isEmpty)
    }

    @Test fun aSplitTitleArrivesOnceAndLeavesOnce() {
        val title = TextOverlay(id = "T", text = "Hi", start = 2.0, end = 6.0, motion = fade)
        val whole = EditState(listOf(clip("A", 0.0, 10.0, title)))
        val split = TimelineEditing.split(whole, 4.0)!!
        assertEquals(listOf(listOf(title), listOf(title)), split.clips.map { it.texts })
        val ranges = TitleMotionRanges(split.clips)
        assertEquals(2.0..6.0, ranges.range(title, 3.9))
        assertEquals(2.0..6.0, ranges.range(title, 4.1))
        assertEquals(1.0, ranges.pose(title, 4.0).opacity, 0.0) // no second entrance at the cut
        // Moved apart, each piece is a run of its own.
        val apart = split.clips + clip("B", 0.0, 1.0, null)
        val moved = TimelineEditing.move(EditState(apart), 2, 1)!!.clips
        val separate = TitleMotionRanges(moved)
        assertEquals(2.0..4.0, separate.range(title, 3.0))
        assertEquals(5.0..7.0, separate.range(title, 6.0))
        assertNull(separate.range(title, 4.5))
        // A motion changed on one piece only also starts a new run.
        val other = title.copy(motion = fade.copy(inSeconds = 1.0))
        val changed = TitleMotionRanges(listOf(split.clips[0], split.clips[1].copy(texts = listOf(other))))
        assertEquals(2.0..4.0, changed.range(title, 3.0))
        assertEquals(4.0..6.0, changed.range(other, 5.0))
    }

    @Test fun aChangeStaysOnThePieceThatWasOpened() {
        // The original: "Effect identity preserves animation across cuts. It is not edit scope".
        val title = TextOverlay(id = "T", text = "Hi", start = 2.0, end = 6.0, motion = fade)
        val other = clip("B", 0.0, 5.0, title.copy(text = "Elsewhere")).copy(sourceID = "S2")
        val split = TimelineEditing.split(EditState(listOf(clip("A", 0.0, 10.0, title), other)), 4.0)!!
        assertEquals(listOf(listOf(title), listOf(title)), split.clips.take(2).map { it.texts })
        val edited = title.copy(text = "Edited section", appearance = TitleDesign(face = "caveat"))
        val next = TimelineEditing.setText(split, 0, edited)!!
        // Editing one separated fragment does not change another.
        assertEquals(listOf(edited), next.clips[0].texts)
        assertEquals(split.clips[1], next.clips[1])
        assertEquals(other, next.clips[2])
        assertNull(TimelineEditing.setText(next, 0, edited))
        // The motion is the same on both pieces, so the title still arrives once and leaves once.
        assertEquals(2.0..6.0, TitleMotionRanges(next.clips).range(edited, 4.0))
        // Removing one separated fragment does not remove another.
        val removed = TimelineEditing.removeText(split, 0, "T")!!
        assertEquals(listOf(null, listOf(title), other.texts), removed.clips.map { it.texts })
        assertEquals(split.clips[1], removed.clips[1])
        assertEquals(listOf(listOf(title), null, other.texts), TimelineEditing.removeText(split, 1, "T")!!.clips.map { it.texts })
        assertNull(TimelineEditing.removeText(split, 0, "missing"))
        // What is left is a run of its own, from the cut.
        assertEquals(4.0..6.0, TitleMotionRanges(removed.clips).range(title, 5.0))
    }

    @Test fun aTitleOnBothSidesOfADissolveIsDrawnOnce() {
        val title = TextOverlay(id = "T", text = "Hi", start = 0.0, end = 10.0, motion = fade)
        val first = clip("A", 0.0, 4.0, title, join = TransitionSettings(nextClipID = "B", style = TransitionStyle.Dissolve, duration = 1.0))
        val clips = listOf(first, clip("B", 4.0, 10.0, title))
        val ranges = TitleMotionRanges(clips)
        // 4 s + 6 s with one second shared: a single run of nine seconds.
        assertEquals(0.0..9.0, ranges.range(title, 3.5))
        val during = ranges.shown(clips, 3.5)
        assertEquals(1, during.size)
        assertEquals(1.0, during[0].share, 1e-9)
        assertEquals(1.0, during[0].pose.opacity, 0.0)
        // Different titles on the two clips keep their own shares.
        val next = TextOverlay(id = "U", text = "There", start = 4.0, end = 10.0)
        val mixed = listOf(first, clip("B", 4.0, 10.0, next))
        val shares = TitleMotionRanges(mixed).shown(mixed, 3.25).associate { it.title.id to it.share }
        // A quarter of the way through, eased.
        assertEquals(0.84375, shares.getValue("T"), 1e-9)
        assertEquals(0.15625, shares.getValue("U"), 1e-9)
    }
}

class TitleLibraryTest {
    @Test fun aStyleIsSavedFirstUnderAUniqueName() {
        val lower = TitleDesign.looks[2].second
        var saved = TitleLibrary.saving(null, "  My channel ", lower)
        saved = TitleLibrary.saving(saved, "Second", TitleDesign(face = "caveat"))
        val looks = TitleLibrary.looks(saved)
        assertEquals(listOf("Second", "My channel"), looks.map { it.name })
        assertEquals(lower, looks[1].design)
        assertFalse(TitleLibrary.canSave(looks, "my CHANNEL"))
        assertFalse(TitleLibrary.canSave(looks, "   "))
        assertFalse(TitleLibrary.canSave(looks, "x".repeat(61)))
        assertTrue(TitleLibrary.canSave(looks, "x".repeat(60)))
        assertEquals(looks, TitleLibrary.looks(TitleLibrary.saving(saved, "second", TitleDesign()))) // refused, nothing lost
        assertEquals(listOf("My channel"), TitleLibrary.looks(TitleLibrary.deleting(saved, looks[0].id)).map { it.name })
    }

    @Test fun unreadableOrInvalidSavedTextCountsAsNothing() {
        assertTrue(TitleLibrary.looks(null).isEmpty())
        assertTrue(TitleLibrary.looks("not json").isEmpty())
        assertTrue(TitleLibrary.favorites("{").isEmpty())
        assertTrue(TitleLibrary.recentColors("[1,2").isEmpty())
        val bad = """[{"id":"A","name":"Bad","design":{"weight":5}},{"id":"B","name":"Good","design":{}}]"""
        assertEquals(listOf("Good"), TitleLibrary.looks(bad).map { it.name })
    }

    @Test fun favoritesToggleAndRecentColorsKeepTheLastEight() {
        var stars = TitleLibrary.togglingFavorite(null, "caveat")
        stars = TitleLibrary.togglingFavorite(stars, "fraunces")
        assertEquals(listOf("caveat", "fraunces"), TitleLibrary.favorites(stars))
        assertEquals(listOf("fraunces"), TitleLibrary.favorites(TitleLibrary.togglingFavorite(stars, "caveat")))
        var colors: String? = null
        for (value in 1L..10L) colors = TitleLibrary.remembering(colors, TitleInk.opaque(value))
        colors = TitleLibrary.remembering(colors, TitleInk.opaque(7))
        assertEquals(listOf(7L, 10L, 9L, 8L, 6L, 5L, 4L, 3L), TitleLibrary.recentColors(colors).map { it.rgb })
    }
}
