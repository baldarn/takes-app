package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Titles of a label and a headline on a card (Tests/TitleDesignTests.swift:180-251, 497-502; YouRec/TitleDesign.swift:187-290). */
class LabelHeadlineTest {
    // Tests/TitleDesignTests.swift:182-205
    @Test fun aLookChangesTheAppearanceOnly() {
        val chapter = LabelHeadline()
        val custom = chapter.copy(
            label = "Location 04",
            labelStyle = chapter.labelStyle.copy(outlined = true, border = TitleInk.opaque(0xFF4040)),
            headlineStyle = chapter.headlineStyle.copy(borderWidth = 0.09),
            card = chapter.card.copy(labelOffset = TitleCard.Offset(0.5, -0.2), headlineOffset = TitleCard.Offset(-0.4, 0.2)),
        )
        assertEquals(listOf("Graphite", "Editorial", "Broadcast", "Notebook", "Terminal", "Poster"), LabelHeadline.looks.map { it.first })
        for ((name, look) in LabelHeadline.looks) {
            val applied = custom.applyingLook(look)
            assertTrue("$name changes appearance only", applied.valid && applied.look == look && applied.label == custom.label)
            assertTrue("$name preserves custom positions and text borders",
                applied.card.labelOffset == custom.card.labelOffset && applied.card.headlineOffset == custom.card.headlineOffset &&
                    applied.labelStyle.outlined && applied.labelStyle.border == custom.labelStyle.border &&
                    applied.headlineStyle.borderWidth == custom.headlineStyle.borderWidth)
            for (line in listOf(applied.labelStyle, applied.headlineStyle)) {
                assertTrue("$name uses bundled font weights", TitleTypeface.families.any { it.id == line.face && line.weight in it.weights })
            }
            val restored = documentJson.decodeFromString(LabelHeadline.serializer(), documentJson.encodeToString(LabelHeadline.serializer(), applied))
            assertEquals("$name survives reopening the title", applied, restored)
        }
        assertEquals("Each look is its own", 6, LabelHeadline.looks.map { it.second }.toSet().size)
    }

    // YouRec/TitleDesign.swift:191-228: the defaults, which are the Graphite look.
    @Test fun theDefaultsAreTheOriginals() {
        val card = LabelHeadline()
        assertEquals("First text", card.label)
        assertEquals("Second text", LabelHeadline.DEFAULT_HEADLINE)
        assertEquals(0.36, card.labelScale, 0.0)
        assertEquals(TitleInk(0x08090B, alpha = 139), card.card.fill)
        assertEquals(TitleInk(0xFFFFFF, alpha = 72), card.card.border)
        assertEquals(listOf(0.017, 0.42, 0.29, 0.08), with(card.card) { listOf(borderWidth, padding, radius, spacing) })
        assertEquals(TitleDesign(face = "system", weight = 700, alignment = TitleAlignment.Left, tracking = 0.09, fill = TitleInk.BLACK, shadow = false,
            plate = TitlePlate.Block, backing = TitleInk.WHITE, padding = 0.32, radius = 0.27), card.labelStyle)
        assertTrue(card.valid)
        assertFalse(card.copy(labelScale = 0.19).valid)
        assertFalse(card.copy(headlineScale = 1.81).valid)
        assertFalse(card.copy(card = card.card.copy(borderWidth = 0.11)).valid)
        assertFalse(card.copy(card = card.card.copy(labelOffset = TitleCard.Offset(1.01, 0.0))).valid)
        assertFalse(card.copy(label = "x".repeat(501)).valid)
    }

    // Tests/TitleDesignTests.swift:217-219: a card saved before the texts could be moved opens with them where they were.
    @Test fun anOlderCardOpensAtItsOriginalPositions() {
        val saved = documentJson.encodeToString(LabelHeadline.serializer(), LabelHeadline())
        assertFalse(saved.contains("labelOffset") || saved.contains("headlineOffset"))
        val read = documentJson.decodeFromString(LabelHeadline.serializer(), saved)
        assertNull(read.card.labelOffset)
        assertNull(read.card.headlineOffset)
    }

    private val label = Size(180.0, 40.0)
    private val headline = Size(420.0, 96.0)

    private fun arranged(card: TitleCard = TitleCard(), labelAlignment: TitleAlignment = TitleAlignment.Left, headlineAlignment: TitleAlignment = TitleAlignment.Left) =
        LabelHeadlineArrangement(label, labelAlignment, headline, headlineAlignment, card, 88.0)

    // YouRec/StyledTextRenderer.swift:158-189; Tests/TitleDesignTests.swift:208-216
    @Test fun theBadgeSitsAboveTheLeftAlignedHeadline() {
        val layout = arranged()
        val pad = kotlin.math.ceil(0.42 * 88) // 37
        val gap = kotlin.math.ceil(0.08 * 88) // 8
        assertEquals(Size(420 + 2 * pad, pad + 40 + gap + 96 + pad), layout.size)
        val a = layout.label!!
        val b = layout.headline!!
        assertTrue(a.y + a.height < b.y && a.x == b.x)
        assertEquals(Rect(pad, pad, 180.0, 40.0), a)
        assertEquals(Rect(pad, pad + 40 + gap, 420.0, 96.0), b)
        for ((frame, part) in listOf(a to LabelHeadline.Part.Label, b to LabelHeadline.Part.Headline)) {
            assertEquals("Preview hit testing agrees with the renderer", part, layout.part(frame.midX / layout.size.width, frame.midY / layout.size.height))
        }
        assertEquals(LabelHeadline.Part.Card, layout.part(0.99, 0.01))
        // Each text is aligned within the wider of the two.
        assertEquals(pad + (420 - 180) / 2.0, arranged(labelAlignment = TitleAlignment.Center).label!!.x, 0.0)
        assertEquals(pad + 420 - 180, arranged(labelAlignment = TitleAlignment.Right).label!!.x, 0.0)
    }

    // Tests/TitleDesignTests.swift:220-238
    @Test fun eachTextMovesOnItsOwnToTheEdgesOfTheCard() {
        val original = arranged()
        val labelMoved = arranged(TitleCard(labelOffset = TitleCard.Offset(1.0, 1.0)))
        assertEquals(original.size, labelMoved.size)
        assertEquals(original.headline, labelMoved.headline)
        assertEquals("Only the label moves to the bottom right edge of the card", labelMoved.size.width, labelMoved.label!!.x + labelMoved.label!!.width, 1e-9)
        assertEquals(labelMoved.size.height, labelMoved.label!!.y + labelMoved.label!!.height, 1e-9)
        val bothMoved = arranged(TitleCard(labelOffset = TitleCard.Offset(1.0, 1.0), headlineOffset = TitleCard.Offset(-1.0, -1.0)))
        assertEquals(original.size, bothMoved.size)
        assertEquals(labelMoved.label, bothMoved.label)
        assertEquals("Headline offsets are independent on both axes", 0.0, bothMoved.headline!!.x, 0.0)
        assertEquals(0.0, bothMoved.headline!!.y, 0.0)
        val head = bothMoved.headline!!
        assertEquals("Direct selection follows moved lettering", LabelHeadline.Part.Headline,
            bothMoved.part(head.width / 2 / bothMoved.size.width, head.height / 2 / bothMoved.size.height))
        // Halfway: half of the travel from where the layout put it.
        val half = arranged(TitleCard(headlineOffset = TitleCard.Offset(0.5, 0.0)))
        val start = original.headline!!.x
        assertEquals(start + 0.5 * (original.size.width - headline.width - start), half.headline!!.x, 1e-9)
        assertEquals("Reset restores the original places", original.label, arranged(TitleCard()).label)
    }

    // YouRec/StyledTextRenderer.swift:163-166: a text with nothing to show takes no room, and no space is left between.
    @Test fun aCardWithOneTextHasNoGap() {
        val alone = LabelHeadlineArrangement(label, TitleAlignment.Left, null, TitleAlignment.Left, TitleCard(), 88.0)
        val pad = kotlin.math.ceil(0.42 * 88)
        assertNull(alone.headline)
        assertEquals(Size(180 + 2 * pad, 40 + 2 * pad), alone.size)
        assertEquals(LabelHeadline.Part.Label, alone.part(0.5, 0.5))
    }

    // Tests/TitleDesignTests.swift:250-251, YouRec/VideoTools.swift:377-383
    @Test fun aLabelOnlyCardRemainsATitle() {
        val chapter = LabelHeadline()
        val labelOnly = TextOverlay(text = "", labelHeadline = chapter)
        assertTrue(labelOnly.hasText)
        assertEquals(chapter.label, labelOnly.displayText)
        assertFalse(TextOverlay(text = "  ").hasText)
        assertEquals("Words", TextOverlay(text = "Words", labelHeadline = chapter).displayText)
        assertEquals(listOf("archivoblack"), TextOverlay(text = "x", appearance = TitleDesign(face = "archivoblack")).fontFaces)
        assertEquals(listOf("system", "system", "archivoblack"), TextOverlay(text = "x", appearance = TitleDesign(), labelHeadline = chapter).fontFaces)
    }

    // Tests/TitleDesignTests.swift:497-502: a card owns the fonts of both its texts.
    @Test fun aCardKeepsTheFontsOfBothTexts() {
        val first = "import:" + "a".repeat(64) + ":0:One-Regular"
        val second = "import:" + "b".repeat(64) + ":1:Two-Bold"
        val card = LabelHeadline().let { it.copy(labelStyle = it.labelStyle.copy(face = first), headlineStyle = it.headlineStyle.copy(face = second)) }
        val clip = TimelineClip("C", "S", 0.0, 3.0, texts = listOf(TextOverlay(id = "T", text = "ABC", labelHeadline = card)))
        assertEquals(setOf("font-" + "a".repeat(64) + ".sfnt", "font-" + "b".repeat(64) + ".sfnt"), FontLibrary.files(EditState(listOf(clip))))
    }

    // Tests/TitleDesignTests.swift:54-59: a card raises the schema, reopens as it was, and older readers refuse it.
    @Test fun aCardRaisesTheSchemaAndReopensWithBothFacesAndOneRange() {
        val take = SourceMedia(id = "S1", filename = "take.mp4", duration = 10.0, recordedAt = 5)
        val base = Project("Cards", id = "P", createdAt = 1).addRecording(take, now = 2)
        val group = LabelHeadline().let {
            it.copy(card = it.card.copy(fill = TitleInk(0x916235, alpha = 218), labelOffset = TitleCard.Offset(0.6, 0.15), headlineOffset = TitleCard.Offset(-0.4, -0.2)),
                headlineStyle = it.headlineStyle.copy(fill = TitleInk.opaque(0x4383ca)))
        }
        val title = TextOverlay(id = "T", text = "Hello", start = 1.0, end = 3.0, size = 0.15, labelHeadline = group)
        val clip = base.edit.clips[0].copy(id = "C1", texts = listOf(title))
        val project = LegacyProject.commit(base, EditState(listOf(clip), base.edit.captureBase), now = 3)
        assertEquals(TimelineClip.LABEL_HEADLINE_SCHEMA, project.schemaVersion)
        assertEquals(25, Project.LATEST_READABLE_SCHEMA)
        project.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = project.manifest { name, data -> files[name] = data }
        val read = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(listOf(title), read.edit.clips[0].texts)
        // An invalid card is refused like any title out of range.
        assertFalse(title.copy(labelHeadline = group.copy(labelScale = 3.0)).valid)
    }

    // YouRec/TitleInspector.swift:447-523: card looks saved under their own key, with names of their own.
    @Test fun cardLooksAreSavedUnderNamesOfTheirOwn() {
        val look = LabelHeadline.looks[2].second
        var saved = TitleLibrary.savingCard(null, "  Channel ", look)
        assertEquals(listOf("Channel"), TitleLibrary.cardLooks(saved).map { it.name })
        assertEquals(look, TitleLibrary.cardLooks(saved)[0].look)
        val looks = TitleLibrary.cardLooks(saved)
        for (taken in listOf("channel", "GRAPHITE", "custom", " ", "x".repeat(61))) assertFalse(taken, TitleLibrary.canSaveCard(looks, taken))
        assertTrue(TitleLibrary.canSaveCard(looks, "Second"))
        saved = TitleLibrary.savingCard(saved, "Second", LabelHeadline().look)
        assertEquals(listOf("Second", "Channel"), TitleLibrary.cardLooks(saved).map { it.name })
        assertEquals(listOf("Channel"), TitleLibrary.cardLooks(TitleLibrary.deletingCard(saved, TitleLibrary.cardLooks(saved)[0].id)).map { it.name })
        assertTrue(TitleLibrary.cardLooks("[{").isEmpty())
        assertEquals("title.groupLooks.v1", TitleLibrary.CARD_LOOKS_KEY)
    }
}
