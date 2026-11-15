package app.takes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The model of the video effects. Unless a test says otherwise it is
 * translated from Tests/VideoEffectModelTests.swift, whose line it names.
 */
class VideoEffectModelTest {
    private val json = documentJson

    private fun number(value: Double) = EffectValue.Number(value)
    private fun toggle(value: Boolean) = EffectValue.Toggle(value)
    private fun choice(value: String) = EffectValue.Choice(value)

    private fun saved(effect: VideoEffectSettings): VideoEffectSettings =
        json.decodeFromString(VideoEffectSettings.serializer(), json.encodeToString(VideoEffectSettings.serializer(), effect))

    private fun tree(effect: VideoEffectSettings): JsonObject = json.encodeToJsonElement(VideoEffectSettings.serializer(), effect).jsonObject

    private fun without(effect: VideoEffectSettings, vararg ids: String) = effect.copy(parameters = effect.parameters.filterNot { it.id in ids })

    // :7-14.
    @Test fun everyEffectHasValidDefaultsThatSurviveSaving() {
        assertEquals(24, VideoEffectID.entries.size)
        for (id in VideoEffectID.entries) {
            val effect = VideoEffectSettings(id)
            assertTrue("Valid defaults: $id", effect.valid)
            val copy = saved(effect)
            assertEquals("Typed parameter round trip: $id", effect, copy)
            assertTrue(copy.valid)
        }
        // Unique Metal effect IDs without the removed rotation shader.
        assertEquals((0..24).toSet() - 10, VideoEffectID.entries.map { it.shaderIndex }.toSet())
    }

    // :16-34.
    @Test fun theStrobeKeepsItsPhase() {
        var strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe, start = 3.0, end = 9.0).with("frequency", number(15.0))
        // Alternating frames stay in phase after backward seeks.
        for (frame in listOf(0, 1, 2, 25, 15, 4, 3)) {
            assertEquals("frame $frame", frame % 2 != 0, strobe.strobeShowsNegative(3 + frame / 30.0, 30.0))
        }
        strobe = strobe.with("timedBurst", toggle(true)).with("burstLength", number(0.1))
        assertFalse("Timed burst ends exactly at its boundary", strobe.strobeShowsNegative(3.1, 30.0))
        strobe = strobe.copy(phaseOffset = 0.1)
        assertFalse("Later clips do not restart a timed burst", strobe.strobeShowsNegative(3.04, 30.0))
        strobe = strobe.copy(phaseOffset = null).with("timedBurst", toggle(false))
        strobe = strobe.copy(strobeTimeMap = listOf(StrobeTimePoint(3.0, 0.0), StrobeTimePoint(5.0, 1.0)))
        val fast = saved(strobe)
        assertTrue(fast.valid)
        assertTrue("Two-times footage uses playback seconds after saving", fast.strobeShowsNegative(3 + 2.0 / 30, 30.0))
        strobe = strobe.copy(strobeTimeMap = null).withKeyframe("frequency", 3.0, number(4.0)).withKeyframe("frequency", 3.2, number(8.0))
        // Rate changes preserve accumulated phase.
        assertTrue(strobe.strobeShowsNegative(3.21, 30.0))
        assertFalse(strobe.strobeShowsNegative(3.24, 30.0))
    }

    /** Not in the original's tests: the rule of VideoEffectModel.swift:303-309 on a held frame and above half the frame rate. */
    @Test fun theStrobeOnAHeldFrameAndAtTheCeiling() {
        val strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe, start = 3.0, end = 9.0).with("frequency", number(15.0))
        // A held frame's clock is how long it has been held, whatever its source second.
        assertFalse(strobe.strobeShowsNegative(7.0, 30.0, holdTime = 0.0))
        assertTrue(strobe.strobeShowsNegative(7.0, 30.0, holdTime = 1 / 30.0))
        // At 24 frames a second the rate is held to 12 cycles: one change of polarity per frame.
        for (frame in 0..11) assertEquals(frame % 2 != 0, strobe.strobeShowsNegative(3 + frame / 24.0, 24.0))
        // Before its start the clock stands at zero: positive.
        assertFalse(strobe.strobeShowsNegative(2.0, 30.0))
    }

    // :35-62.
    @Test fun theKaleidoscopeAnimatesItsAnglesAlongTheShortArcAndReopensOlderSaves() {
        var split = VideoEffectSettings(VideoEffectID.Kaleidoscope)
            .withKeyframe("splitAngle", 0.0, number(170.0)).withKeyframe("splitAngle", 1.0, number(-170.0))
        assertEquals("Animating across the angle boundary takes the short arc", -180.0, split.number("splitAngle", 0.5), 0.0)
        split = split.withKeyframe("divisions", 0.0, number(2.0)).withKeyframe("divisions", 1.0, number(9.0))
        // Animated divisions change in whole wedges.
        assertEquals(2.0, split.number("divisions", 0.99), 0.0)
        assertEquals(9.0, split.number("divisions", 1.0), 0.0)
        val upgraded = saved(without(split, "sourcePanX", "sourcePanY", "sourceAngle"))
        assertEquals("Existing Kaleidoscope edits reopen with neutral source pan and rotation", split, upgraded)
        assertTrue(upgraded.valid)
        split = split.withKeyframe("sourcePanX", 0.0, number(-0.4)).withKeyframe("sourcePanX", 1.0, number(0.8))
        assertEquals("An existing panned take keeps its framing with zero source rotation", split, saved(without(split, "sourceAngle")))
        split = split.withKeyframe("sourceAngle", 0.0, number(-160.0)).withKeyframe("sourceAngle", 1.0, number(140.0))
        val savedSplit = saved(split)
        // Independent source and split rotations animate along their own short arcs.
        assertEquals(170.0, savedSplit.number("sourceAngle", 0.5), 0.0)
        assertEquals(-180.0, savedSplit.number("splitAngle", 0.5), 0.0)
        // Source pan animates independently of the split after reopening.
        assertEquals(0.2, savedSplit.number("sourcePanX", 0.5), 1e-9)
        assertEquals(0.5, savedSplit.number("centerX", 0.5), 0.0)
    }

    // :80-89.
    @Test fun theCorrectionComesBeforeTheLooksAndItsAnimationSurvivesSaving() {
        val correction = VideoEffectSettings(VideoEffectID.ColorCorrection)
        assertTrue("Every correction control supports keyframes", correction.parameters.all { it.animatable })
        val order = VideoEffectSettings.ordered(
            listOf(VideoEffectSettings(VideoEffectID.SilentTint), correction, VideoEffectSettings(VideoEffectID.Noir), VideoEffectSettings(VideoEffectID.ChromaKey)),
        )
        assertEquals(
            "Correction precedes looks without changing their relative order",
            listOf(VideoEffectID.ChromaKey, VideoEffectID.ColorCorrection, VideoEffectID.SilentTint, VideoEffectID.Noir),
            order.map { it.kind },
        )
        val animated = correction.withKeyframe("exposure", 0.0, number(-1.0)).withKeyframe("exposure", 2.0, number(1.0))
        val copy = saved(animated)
        assertEquals(0.0, copy.number("exposure", 1.0), 0.0)
        assertEquals("Correction animation survives save/reopen with source-time interpolation", animated, copy)
    }

    @Serializable
    private class SavedEffects(
        @Serializable(with = SupportedVideoEffects::class) val videoEffects: List<VideoEffectSettings>? = null,
    )

    // :90-99; docs/VIDEO-EFFECTS.md, "Crop rotation": loading skips unknown video-effect IDs.
    @Test fun unknownEffectsAreSkippedWithoutDiscardingTheirNeighbours() {
        val known = VideoEffectSettings(VideoEffectID.SilentTint)
        val mixed = JsonArray(
            listOf(
                buildJsonObject { put("kind", JsonPrimitive("dutchAngle")) },
                tree(known),
                buildJsonObject { put("kind", JsonPrimitive("futureEffect")); put("parameters", JsonPrimitive("unrecognized layout")) },
            ),
        )
        assertEquals(listOf(known), json.decodeFromJsonElement(SupportedVideoEffects, mixed))
        // Synthesized project-field decoding skips unknown effects.
        val nested = json.decodeFromJsonElement(SavedEffects.serializer(), buildJsonObject { put("videoEffects", mixed) })
        assertEquals(listOf(known), nested.videoEffects)
        // An entirely unknown stack becomes empty.
        val empty = json.decodeFromString(SavedEffects.serializer(), """{"videoEffects":[{"kind":"futureEffect"}]}""")
        assertEquals(emptyList<VideoEffectSettings>(), empty.videoEffects)
        assertNull(json.decodeFromString(SavedEffects.serializer(), "{}").videoEffects)
        assertNull(json.decodeFromString(SavedEffects.serializer(), """{"videoEffects":null}""").videoEffects)
    }

    /** VideoEffectModel.swift:363-364: corrupt recognized settings are not hidden; alone, an unknown effect does not read. */
    @Test fun aKnownEffectThatIsCorruptStillFailsToRead() {
        val broken = JsonArray(listOf(buildJsonObject { put("kind", JsonPrimitive("noir")); put("parameters", JsonPrimitive("unrecognized layout")) }))
        assertThrows(SerializationException::class.java) { json.decodeFromJsonElement(SupportedVideoEffects, broken) }
        assertThrows(SerializationException::class.java) { json.decodeFromJsonElement(SupportedVideoEffects, JsonArray(listOf(buildJsonObject { }))) }
        assertThrows(SerializationException::class.java) {
            json.decodeFromJsonElement(VideoEffectSettings.serializer(), tree(VideoEffectSettings(VideoEffectID.Noir)).with("kind", JsonPrimitive("dutchAngle")))
        }
    }

    /** The same tree with every number as a double, so that `1` and `1.0` compare equal. */
    private fun numbers(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { numbers(it.value) })
        is JsonArray -> JsonArray(element.map(::numbers))
        is JsonPrimitive -> if (!element.isString && element.content.toDoubleOrNull() != null) JsonPrimitive(element.content.toDouble()) else element
    }

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))
    private fun JsonObject.without(key: String) = JsonObject(this - key)

    // :100-108, :327-341.
    @Test fun theAlgorithmVersionIsKeptAsSaved() {
        for (kind in VideoEffectID.entries) {
            val candidate = VideoEffectSettings(kind).copy(algorithm = 2)
            val supported = kind == VideoEffectID.ColorIsolation || kind == VideoEffectID.ChromaKey
            // Temporal matte version is accepted only by the techniques that implement it.
            assertEquals(supported, candidate.valid)
            assertEquals(supported, candidate.usesTemporalMatte)
            if (supported) {
                val copy = saved(candidate)
                assertTrue(copy.algorithm == 2 && copy.valid && copy.usesModernPersonMask && !copy.usesAutoMaskStream)
            }
        }
        for (kind in listOf(VideoEffectID.ColorIsolation, VideoEffectID.ChromaKey)) {
            val current = VideoEffectSettings(kind)
            // New key and isolation recipes enable validated temporal processing.
            assertTrue(current.algorithm == 2 && current.usesTemporalMatte)
            for (version in listOf<Int?>(null, 1)) {
                val value = if (version == null) tree(current).without("algorithm") else tree(current).with("algorithm", JsonPrimitive(version))
                val restored = json.decodeFromJsonElement(VideoEffectSettings.serializer(), value)
                // Existing recipes keep their original spatial processing after reopening.
                assertTrue(restored.algorithm == version && !restored.usesTemporalMatte && restored.valid)
            }
        }
        val modern = VideoEffectSettings(VideoEffectID.AutoBackground)
        val legacy = json.decodeFromJsonElement(VideoEffectSettings.serializer(), tree(modern).without("algorithm"))
        // Absent algorithm version preserves legacy recipes instead of silently enabling new processing.
        assertTrue(modern.algorithm == 1 && legacy.algorithm == null && legacy.valid)
    }

    // :133-138. Previously applied Silent Era looks were stored as four ordinary effects.
    @Test fun aSavedStackKeepsItsOrderIdentitiesAndSettings() {
        val stack = listOf(
            VideoEffectSettings(VideoEffectID.Expressionism),
            VideoEffectSettings(VideoEffectID.SilentTint).with("tint", choice("Night Blue")),
            VideoEffectSettings(VideoEffectID.SixteenMM),
            VideoEffectSettings(VideoEffectID.Undercranking),
        )
        assertEquals(stack, json.decodeFromString(SupportedVideoEffects, json.encodeToString(SupportedVideoEffects, stack)))
    }

    // :178-184.
    @Test fun continuousKeyframesInterpolateAndCategoricalOnesHold() {
        var parameter = EffectParameter("value", "Value", EffectParameterKind.Slider(0.0, 1.0, 0.0, 0.01))
        parameter = parameter.withKeyframe(1.0, number(0.0)).withKeyframe(3.0, number(1.0))
        assertEquals(number(0.5), parameter.value(2.0))
        assertTrue(parameter.valid)
        parameter = parameter.withKeyframe(2.0, number(99.0))
        assertEquals("Reject out-of-range keyframes", 2, parameter.keyframes.size)
        var categorical = EffectParameter("mode", "Mode", EffectParameterKind.Picker(listOf("A", "B"), "A"))
        categorical = categorical.withKeyframe(0.0, choice("A")).withKeyframe(2.0, choice("B"))
        // Categorical keyframes hold until their time.
        assertEquals(choice("A"), categorical.value(1.99))
        assertEquals(choice("B"), categorical.value(2.0))
    }

    /** VideoEffectModel.swift:64-74, :122-140, beyond what the original's tests ask. */
    @Test fun keyframesOfEveryKind() {
        // Before the first key and after the last, the nearest key's value; the plain value is not used once keys exist.
        var slider = EffectParameter("value", "Value", EffectParameterKind.Slider(0.0, 1.0, 0.7, 0.01))
        assertEquals(number(0.7), slider.value(5.0))
        slider = slider.withKeyframe(1.0, number(0.2)).withKeyframe(3.0, number(0.4))
        assertEquals(number(0.2), slider.value(0.0))
        assertEquals(number(0.2), slider.value(1.0))
        assertEquals(0.25, (slider.value(1.5) as EffectValue.Number).value, 1e-12)
        assertEquals(number(0.4), slider.value(3.0))
        assertEquals(number(0.4), slider.value(99.0))
        // A key within a sixty-thousandth of a second replaces the one there; keys stay sorted.
        slider = slider.withKeyframe(1.0 + 1.0 / 70000, number(0.9)).withKeyframe(2.0, number(0.5))
        assertEquals(listOf(0.9, 0.5, 0.4), slider.keyframes.map { (it.value as EffectValue.Number).value })
        assertEquals(slider, slider.withKeyframe(-1.0, number(0.5)))
        assertEquals(slider, slider.withKeyframe(Double.NaN, number(0.5)))
        assertEquals(slider, slider.withKeyframe(1.0, toggle(true)))
        // Colours interpolate channel by channel.
        var color = EffectParameter("color", "Color", EffectParameterKind.Color(EffectRGB(0.0, 0.0, 0.0)))
        color = color.withKeyframe(0.0, EffectValue.Color(EffectRGB(0.0, 0.2, 1.0))).withKeyframe(2.0, EffectValue.Color(EffectRGB(1.0, 0.4, 0.0)))
        assertEquals(EffectValue.Color(EffectRGB(0.25, 0.25, 0.75)), color.value(0.5))
        // Toggles, steppers and files hold the earlier key.
        var flag = EffectParameter("flag", "Flag", EffectParameterKind.Toggle(false))
        flag = flag.withKeyframe(0.0, toggle(true)).withKeyframe(1.0, toggle(false))
        assertEquals(toggle(true), flag.value(0.999))
        assertEquals(toggle(false), flag.value(1.0))
        var steps = EffectParameter("steps", "Steps", EffectParameterKind.Stepper(listOf(2.0, 4.0), 2.0))
        steps = steps.withKeyframe(0.0, number(2.0)).withKeyframe(1.0, number(4.0)).withKeyframe(0.5, number(3.0))
        assertEquals(number(2.0), steps.value(0.9))
        assertEquals(2, steps.keyframes.size)
        var file = EffectParameter("media", "Media", EffectParameterKind.Media(image = true, video = false))
        file = file.withKeyframe(0.0, EffectValue.Media("a.png")).withKeyframe(1.0, EffectValue.Media(null)).withKeyframe(2.0, EffectValue.Media("b.mov"))
        assertEquals(EffectValue.Media("a.png"), file.value(0.5))
        assertEquals(EffectValue.Media(null), file.value(5.0))
        // A control that cannot be animated takes no key and is not valid with one.
        val fixed = EffectParameter("fixed", "Fixed", EffectParameterKind.Toggle(false), animatable = false)
        assertEquals(fixed, fixed.withKeyframe(0.0, toggle(true)))
        assertFalse(fixed.copy(keyframes = listOf(EffectKeyframe(time = 0.0, value = toggle(true)))).valid)
        // The interpolation itself.
        assertEquals(number(3.0), number(2.0).interpolated(number(4.0), 0.5))
        assertEquals(number(4.0), number(2.0).interpolated(number(4.0), 7.0))
        assertEquals(choice("A"), choice("A").interpolated(choice("B"), 0.999))
        assertEquals(choice("B"), choice("A").interpolated(choice("B"), 1.0))
        assertEquals(number(1.0), number(1.0).interpolated(toggle(true), 0.5))
        assertEquals(-180.0, EffectParameter.wrappedDegrees(180.0), 0.0)
        assertEquals(170.0, EffectParameter.wrappedDegrees(-190.0), 0.0)
    }

    /** VideoEffectModel.swift:90-102. */
    @Test fun aControlAcceptsOnlyItsOwnValues() {
        val slider = EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)
        assertTrue(slider.accepts(number(0.0)) && slider.accepts(number(1.0)))
        assertFalse(slider.accepts(number(1.0001)) || slider.accepts(number(Double.NaN)) || slider.accepts(toggle(true)))
        val stepper = EffectParameterKind.Stepper(listOf(12.0, 16.0, 18.0), 16.0)
        assertTrue(stepper.accepts(number(18.0)))
        assertFalse(stepper.accepts(number(17.0)))
        assertTrue(EffectParameterKind.Color(EffectRGB(0.0, 0.0, 0.0)).accepts(EffectValue.Color(EffectRGB(1.0, 0.5, 0.0))))
        assertFalse(EffectParameterKind.Color(EffectRGB(0.0, 0.0, 0.0)).accepts(EffectValue.Color(EffectRGB(1.5, 0.5, 0.0))))
        val picker = EffectParameterKind.Picker(listOf("Tint", "Tone"), "Tint")
        assertTrue(picker.accepts(choice("Tone")))
        assertFalse(picker.accepts(choice("tone")))
        val image = EffectParameterKind.Media(image = true, video = false)
        val both = EffectParameterKind.Media(image = true, video = true)
        assertTrue(image.accepts(EffectValue.Media(null)) && image.accepts(EffectValue.Media("plate.PNG")) && both.accepts(EffectValue.Media("clip.mp4")))
        for (name in listOf("clip.mp4", "", "folder/plate.png", ".plate.png", "a\\b.png", "plate.gif", "plate")) {
            assertFalse(name, image.accepts(EffectValue.Media(name)))
        }
        assertEquals(number(0.5), slider.default)
        assertEquals(EffectValue.Media(null), image.default)
        assertTrue(slider.continuous && EffectParameterKind.Color(EffectRGB(0.0, 0.0, 0.0)).continuous)
        assertFalse(stepper.continuous || picker.continuous || image.continuous || EffectParameterKind.Toggle(true).continuous)
    }

    // :185-194.
    @Test fun thePipelineOrderIsFixedAndTheCatalogueOwnsTheBounds() {
        val chain = VideoEffectSettings.ordered(
            listOf(VideoEffectID.Widescreen, VideoEffectID.SixteenMM, VideoEffectID.Noir, VideoEffectID.ChromaKey).map { VideoEffectSettings(it) },
        )
        assertEquals(listOf(VideoEffectID.ChromaKey, VideoEffectID.Noir, VideoEffectID.SixteenMM, VideoEffectID.Widescreen), chain.map { it.kind })
        val noir = VideoEffectSettings(VideoEffectID.Noir)
        val invalid = noir.copy(parameters = noir.parameters.toMutableList().also { it[0] = it[0].copy(kind = EffectParameterKind.Slider(-100.0, 100.0, 0.0, 1.0)) })
        assertFalse("Documents cannot redefine parameter bounds", invalid.valid)
        val canonical = saved(invalid)
        // Decoded parameter metadata comes from the installed catalogue.
        assertTrue(canonical.valid)
        assertEquals(noir.parameters[0].kind, canonical.parameters[0].kind)
        val under = VideoEffectSettings(VideoEffectID.Undercranking)
        val old = under.copy(parameters = under.parameters.map { if (it.id == "speed") it.copy(animatable = false) else it })
        val migrated = saved(old)
        // Catalogue animation upgrades preserve old projects.
        assertTrue(migrated.valid && migrated.parameters.first { it.id == "speed" }.animatable)
    }

    /** VideoEffectModel.swift:350-360 and docs/VIDEO-EFFECTS.md:77-78, beyond the original's two cases. */
    @Test fun theOrderOfTheFamiliesIsKeyCameraColorQuantizationTextureFrame() {
        val added = listOf(
            VideoEffectID.Widescreen, VideoEffectID.Halation, VideoEffectID.Quantization, VideoEffectID.Kaleidoscope, VideoEffectID.Noir,
            VideoEffectID.Undercranking, VideoEffectID.CrashZoom, VideoEffectID.ColorCorrection, VideoEffectID.AutoBackground,
            VideoEffectID.SilentTint, VideoEffectID.NightFix, VideoEffectID.SixteenMM, VideoEffectID.ChromaKey, VideoEffectID.DollyZoom,
        ).map { VideoEffectSettings(it) }
        assertEquals(
            listOf(
                // Night fix restores the footage before everything.
                VideoEffectID.NightFix,
                // Key, in the order added.
                VideoEffectID.AutoBackground, VideoEffectID.ChromaKey,
                // Camera, the Kaleidoscope last.
                VideoEffectID.CrashZoom, VideoEffectID.DollyZoom, VideoEffectID.Kaleidoscope,
                // Color, the correction first.
                VideoEffectID.ColorCorrection, VideoEffectID.Noir, VideoEffectID.SilentTint,
                VideoEffectID.Quantization,
                VideoEffectID.Halation, VideoEffectID.SixteenMM,
                VideoEffectID.Widescreen,
                // Time is the last family of the applied list.
                VideoEffectID.Undercranking,
            ),
            VideoEffectSettings.ordered(added).map { it.kind },
        )
        // Stable within a family: two of a kind keep the order they were added in.
        val first = VideoEffectSettings(VideoEffectID.Noir)
        val second = VideoEffectSettings(VideoEffectID.Noir)
        assertEquals(listOf(second.id, first.id), VideoEffectSettings.ordered(listOf(second, VideoEffectSettings(VideoEffectID.Widescreen), first)).take(2).map { it.id })
        assertEquals(
            listOf(EffectFamily.Key, EffectFamily.Camera, EffectFamily.Color, EffectFamily.Quantization, EffectFamily.Texture, EffectFamily.Frame, EffectFamily.Time),
            EffectFamily.entries.sortedBy { it.order },
        )
    }

    // :261-282.
    @Test fun aCapturedCrashZoomIsCutToItsTakeAndRetimed() {
        val late = VideoEffectSettings(VideoEffectID.CrashZoom, start = 3.0, end = 8.0).copy(releaseTime = 6.0)
        val trimmed = VideoEffectSettings.capturedRecipe(listOf(late), 4.0)
        // Stopping mid-move retains a valid recipe.
        assertTrue(trimmed.size == 1 && trimmed[0].valid && trimmed[0].end == 4.0 && trimmed[0].releaseTime == null)
        assertTrue("A trigger beyond the saved take never invalidates the recording", VideoEffectSettings.capturedRecipe(listOf(late), 2.0).isEmpty())
        assertTrue("A carried manual return can begin before the take", late.copy(start = 0.0, phaseOffset = 2.0, releaseTime = -0.1).valid)
        var editable = VideoEffectSettings(VideoEffectID.CrashZoom, start = 1.0, end = 2.0).copy(phaseOffset = 0.0)
            .with("return", toggle(true)).with("speed", number(0.5)).with("hold", number(3.0))
        editable = editable.refreshCapturedCrashEnd(8.0)
        // Extending captured crash timing does not clip its return.
        assertEquals(5.08, editable.end, 0.000001)
        assertTrue(editable.valid)
        editable = editable.with("return", toggle(false)).refreshCapturedCrashEnd(7.0)
        assertEquals("Turning off automatic return holds until the next trigger or clip end", 7.0, editable.end, 0.0)
        editable = editable.copy(releaseTime = 3.0).refreshCapturedCrashEnd(7.0)
        assertEquals("A manual return follows the edited push duration", 3.5, editable.end, 0.0)
        val capturedMove = editable
        editable = editable.retimeCapturedCrash(capturedMove, 2.0)
        assertEquals("Opening a split captured move preserves its saved timing and downstream analysis", capturedMove, editable)
        // The original sets "amount", a control the Crash Zoom does not have: nothing changes.
        editable = editable.with("amount", number(3.0)).retimeCapturedCrash(capturedMove, 2.0)
        assertEquals("Changing framing does not implicitly shorten a captured move", capturedMove.end, editable.end, 0.0)
        val beforeTiming = editable
        editable = editable.with("speed", number(0.3)).retimeCapturedCrash(beforeTiming, 7.0)
        assertEquals("An explicit timing edit refreshes a captured return", 3.3, editable.end, 0.0)
    }

    /** VideoEffectModel.swift:194-196, :233-244. */
    @Test fun anEffectIsActiveInsideItsRangeWhileOnAndAboveZeroIntensity() {
        val noir = VideoEffectSettings(VideoEffectID.Noir, start = 2.0, end = 5.0)
        assertFalse(noir.isActive(1.999))
        assertTrue(noir.isActive(2.0))
        assertTrue(noir.isActive(4.999))
        assertFalse(noir.isActive(5.0))
        assertFalse(noir.copy(enabled = false).isActive(3.0))
        assertFalse(noir.with("intensity", number(0.0)).isActive(3.0))
        // Intensity is a control like the others: keyed to zero, the effect is off from that second.
        val fading = noir.withKeyframe("intensity", 3.0, number(1.0)).withKeyframe("intensity", 4.0, number(0.0))
        assertTrue(fading.isActive(3.99))
        assertFalse(fading.isActive(4.0))
        // Camera and time effects have no intensity and are not switched off by one.
        val zoom = VideoEffectSettings(VideoEffectID.CrashZoom)
        assertNull(zoom.value("intensity"))
        assertTrue(zoom.isActive(0.0))
        assertEquals("intensity", noir.parameters[0].id)
        assertEquals(listOf("contrast", "crush", "grain", "shadowColor"), noir.parameters.drop(1).map { it.id })
        // Reading a control of the wrong type, or one that is not there, gives the type's zero.
        assertEquals(0.0, noir.number("grain"), 0.0)
        assertFalse(noir.toggle("contrast"))
        assertEquals("", noir.choice("nothing"))
        assertEquals(EffectRGB(0.0, 0.0, 0.0), noir.color("nothing"))
        // Setting a control it does not have, or a value out of bounds, changes nothing.
        assertEquals(noir, noir.with("nothing", number(1.0)))
        assertEquals(noir, noir.with("contrast", number(5.0)))
        assertEquals(1.7, noir.with("contrast", number(1.7)).number("contrast"), 0.0)
    }

    /** VideoEffectModel.swift:178, :233-236, :269-275; the motion is the one of EffectAnimation.swift. */
    @Test fun theMotionOfAColorCorrectionDecidesTheControlsItNames() {
        val correction = VideoEffectSettings(VideoEffectID.ColorCorrection, start = 0.0, end = 4.0)
        val a = correction.correctionPose(0.0)
        assertEquals(correction.parameters.map { it.id }.toSet(), a.values.keys)
        val b = correction.with("exposure", number(1.0)).with("hue", number(170.0)).with("saturation", number(2.0)).correctionPose(0.0)
        val moving = correction.copy(motion = EffectAnimation(a, 4.0).copy(b = b).anchored(listOf(EffectPlaybackSpan(0.0, 4.0, 1.0)), 0.0))
        assertTrue(moving.valid)
        assertEquals(moving, saved(moving))
        val last = 4.0 - PauseDetector.FRAME
        assertEquals(0.0, moving.number("exposure", 0.0), 0.0)
        assertEquals(0.5, moving.number("exposure", last / 2), 1e-12)
        assertEquals(1.0, moving.number("exposure", 4.0), 0.0)
        assertEquals(1.5, moving.number("saturation", last / 2), 1e-12)
        assertEquals(85.0, moving.number("hue", last / 2), 1e-9)
        // The motion wins over the control's own value and keys.
        assertEquals(0.5, moving.with("exposure", number(-3.0)).withKeyframe("exposure", 1.0, number(4.0)).number("exposure", last / 2), 1e-12)
        // An end outside a control's bounds, a motion on another effect, or one that lacks a control: not valid.
        assertFalse(correction.copy(motion = moving.motion!!.copy(b = EffectPose(b.values + ("exposure" to 9.0)))).valid)
        assertFalse(VideoEffectSettings(VideoEffectID.Noir).let { it.copy(motion = EffectAnimation(it.correctionPose(0.0), 4.0)) }.valid)
        assertFalse(correction.copy(motion = moving.motion.copy(b = EffectPose(b.values - "hue"))).valid)
    }

    /** VideoEffectModel.swift:269-289. */
    @Test fun whatASavedProjectMayNotHold() {
        val noir = VideoEffectSettings(VideoEffectID.Noir)
        assertFalse(noir.copy(version = 2).valid)
        assertFalse(noir.copy(start = 3.0, end = 3.0).valid)
        assertFalse(noir.copy(start = -1.0).valid)
        assertFalse(noir.copy(end = VideoEffectSettings.MAXIMUM_TIME + 1).valid)
        assertFalse(noir.copy(end = Double.NaN).valid)
        assertFalse(noir.copy(algorithm = 3).valid)
        assertFalse(noir.copy(parameters = noir.parameters.drop(1)).valid)
        assertFalse(noir.copy(parameters = noir.parameters + noir.parameters[1]).valid)
        assertFalse(noir.copy(strobeTimeMap = listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(1.0, 1.0))).valid)
        assertFalse(noir.copy(phaseOffset = 1.0).valid)
        assertFalse(noir.copy(releaseTime = 1.0).valid)
        assertFalse(noir.copy(palette = listOf(EffectRGB(0.0, 0.0, 0.0))).valid)
        assertTrue(noir.copy(palette = listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0))).valid)
        assertFalse(noir.copy(lockedPalette = EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.5, 0.6))).valid)
        assertFalse(noir.copy(analysisFile = "../outside.bin").valid)
        val strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe)
        assertTrue(strobe.copy(strobeTimeMap = listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(1.0, 1.0)), phaseOffset = 2.0).valid)
        assertFalse(strobe.copy(strobeTimeMap = listOf(StrobeTimePoint(0.0, 0.0))).valid)
        assertFalse(strobe.copy(strobeTimeMap = listOf(StrobeTimePoint(1.0, 0.0), StrobeTimePoint(1.0, 1.0))).valid)
        val keyed = noir.withKeyframe("contrast", 1.0, number(1.2))
        val twice = keyed.copy(parameters = keyed.parameters.map { p -> if (p.id == "contrast") p.copy(keyframes = p.keyframes + p.keyframes) else p })
        assertTrue(keyed.valid)
        assertFalse(twice.valid)
        val key = VideoEffectSettings(VideoEffectID.ChromaKey).with("cleanPlate", EffectValue.Media("plate.png")).with("media", EffectValue.Media("back.mov"))
        assertEquals(listOf("analysis.bin", "back.mov", "plate.png"), key.copy(analysisFile = "analysis.bin").mediaFiles)
        assertEquals(choice("Result"), key.with("view", choice("Matte")).withKeyframe("view", 1.0, choice("Original")).recordingResult.parameters.first { it.id == "view" }.value)
    }

    /** VideoEffectModel.swift:181-193. */
    @Test fun whichEffectsNeedTheFootageAnalysedFirst() {
        fun needs(kind: VideoEffectID) = VideoEffectSettings(kind).requiresOfflineAnalysis
        assertEquals(
            setOf(VideoEffectID.Quantization, VideoEffectID.AutoBackground, VideoEffectID.DollyZoom, VideoEffectID.CrashZoom, VideoEffectID.ColorIsolation, VideoEffectID.ChromaKey),
            VideoEffectID.entries.filter(::needs).toSet(),
        )
        assertFalse(VideoEffectSettings(VideoEffectID.CrashZoom).with("trackFace", toggle(false)).requiresOfflineAnalysis)
        assertFalse(VideoEffectSettings(VideoEffectID.ColorIsolation).copy(algorithm = null).requiresOfflineAnalysis)
        assertFalse(VideoEffectSettings(VideoEffectID.Quantization).copy(enabled = false).requiresOfflineAnalysis)
        val night = VideoEffectSettings(VideoEffectID.NightFix)
        assertEquals(NightFixMode.Fast, night.nightFixMode)
        assertTrue(night.with("mode", choice("Precise")).requiresOfflineAnalysis)
        assertEquals(4, night.with("mode", choice("Precise")).nightFixMode.radius)
        assertEquals(256.0, NightFixMode.Medium.guideLimit, 0.0)
    }

    /** YouRec/VideoEffects/VideoEffectTiming.swift:17-28. */
    @Test fun theEffectsThatChangeTheSpeedAndSilenceTheSound() {
        val under = VideoEffectSettings(VideoEffectID.Undercranking, start = 1.0, end = 3.0)
        val step = VideoEffectSettings(VideoEffectID.StepPrinting, start = 2.0, end = 4.0).with("speed", number(0.5))
        val effects = listOf(under, step, VideoEffectSettings(VideoEffectID.Noir))
        assertEquals(1.0, VideoEffectSettings.sequenceRate(effects, 0.5), 0.0)
        assertEquals(1.25, VideoEffectSettings.sequenceRate(effects, 1.0), 0.0)
        assertEquals(0.625, VideoEffectSettings.sequenceRate(effects, 2.5), 0.0)
        assertEquals(0.5, VideoEffectSettings.sequenceRate(effects, 3.0), 0.0)
        assertEquals(1.0, VideoEffectSettings.sequenceRate(effects, 4.0), 0.0)
        assertEquals(0.5, VideoEffectSettings.sequenceRate(listOf(under.copy(enabled = false), step), 2.5), 0.0)
        assertFalse(VideoEffectSettings.mutesSourceAudio(effects, 2.0))
        val muted = listOf(under.with("mute", toggle(true)))
        assertTrue(VideoEffectSettings.mutesSourceAudio(muted, 2.0))
        assertFalse(VideoEffectSettings.mutesSourceAudio(muted, 3.0))
        val hold = listOf(VideoEffectSettings(VideoEffectID.FreezeFrame))
        assertTrue(VideoEffectSettings.mutesSourceAudio(hold, 0.0, frozen = true))
        assertFalse(VideoEffectSettings.mutesSourceAudio(hold, 0.0, frozen = false))
        assertFalse(VideoEffectSettings.mutesSourceAudio(listOf(hold[0].with("audio", choice("Continue"))), 0.0, frozen = true))
    }

    /**
     * The saved shape, written out by hand as Swift's synthesized coding gives
     * it for VideoEffectModel.swift:64-108, :162-206: an enum with values is
     * an object with the case's name as its one key, an unlabelled value under
     * `_0`, labelled ones under their labels; an absent optional is left out.
     */
    @Test fun anEffectIsSavedInTheShapeTheOriginalWrites() {
        val text = """
            {"id":"6F0E0A3C-0C1B-4B7E-9E57-0E8D8E3F2A11","kind":"silentTint","enabled":true,"version":1,"start":1.5,"end":604800,
             "algorithm":1,
             "parameters":[
              {"id":"intensity","title":"Intensity","kind":{"slider":{"min":0,"max":1,"defaultValue":1,"step":0.01}},"value":{"number":{"_0":0.8}},
               "keyframes":[{"id":"0B9B5C0E-6B0C-4D55-8F5C-2B5C3F1D7A10","time":2,"value":{"number":{"_0":0.25}}}],"animatable":true},
              {"id":"tint","title":"Tint","kind":{"picker":{"options":["Night Blue","Amber","Fire Red","Nature Green","Sepia"],"defaultValue":"Amber"}},
               "value":{"choice":{"_0":"Sepia"}},"keyframes":[],"animatable":true},
              {"id":"mode","title":"Process","kind":{"picker":{"options":["Tint","Tone"],"defaultValue":"Tint"}},"value":{"choice":{"_0":"Tone"}},"keyframes":[],"animatable":true},
              {"id":"contrast","title":"Contrast","kind":{"slider":{"min":0.8,"max":1.4,"defaultValue":1.15,"step":0.01}},"value":{"number":{"_0":1}},"keyframes":[],"animatable":true}
             ]}
        """.trimIndent()
        val effect = json.decodeFromString(VideoEffectSettings.serializer(), text)
        val expected = VideoEffectSettings(VideoEffectID.SilentTint, start = 1.5).copy(id = "6F0E0A3C-0C1B-4B7E-9E57-0E8D8E3F2A11")
            .with("intensity", number(0.8)).with("tint", choice("Sepia")).with("mode", choice("Tone")).with("contrast", number(1.0))
            .let { e -> e.copy(parameters = e.parameters.map { if (it.id == "intensity") it.copy(keyframes = listOf(EffectKeyframe("0B9B5C0E-6B0C-4D55-8F5C-2B5C3F1D7A10", 2.0, number(0.25)))) else it }) }
        assertEquals(expected, effect)
        assertTrue(effect.valid)
        // Written back, it is the same tree: the same keys, nothing added, no nulls.
        assertEquals(numbers(Json.parseToJsonElement(text)), numbers(tree(effect)))
        // The other cases of a value and of a control.
        fun value(text: String) = json.decodeFromString(EffectValue.serializer(), text)
        assertEquals(toggle(true), value("""{"toggle":{"_0":true}}"""))
        assertEquals(EffectValue.Color(EffectRGB(1.0, 0.5, 0.0)), value("""{"color":{"_0":{"red":1,"green":0.5,"blue":0}}}"""))
        assertEquals(EffectValue.Media("a.png"), value("""{"media":{"_0":"a.png"}}"""))
        assertEquals(EffectValue.Media(null), value("""{"media":{}}"""))
        assertEquals("""{"media":{}}""", json.encodeToString(EffectValue.serializer(), EffectValue.Media(null)))
        assertEquals("""{"toggle":{"_0":false}}""", json.encodeToString(EffectValue.serializer(), toggle(false)))
        assertThrows(SerializationException::class.java) { value("""{"number":{"_0":1},"toggle":{"_0":true}}""") }
        assertThrows(SerializationException::class.java) { value("{}") }
        fun kind(text: String) = json.decodeFromString(EffectParameterKind.serializer(), text)
        assertEquals(EffectParameterKind.Toggle(true), kind("""{"toggle":{"defaultValue":true}}"""))
        assertEquals(EffectParameterKind.Stepper(listOf(18.0, 24.0), 18.0), kind("""{"stepper":{"values":[18,24],"defaultValue":18}}"""))
        assertEquals(EffectParameterKind.Color(EffectRGB(0.0, 1.0, 0.0)), kind("""{"color":{"defaultValue":{"red":0,"green":1,"blue":0}}}"""))
        assertEquals(EffectParameterKind.Media(image = true, video = false), kind("""{"media":{"image":true,"video":false}}"""))
        assertEquals("""{"media":{"image":true,"video":false}}""", json.encodeToString(EffectParameterKind.serializer(), EffectParameterKind.Media(image = true, video = false)))
        // The optional fields, under the original's names.
        val strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe).copy(strobeTimeMap = listOf(StrobeTimePoint(0.0, 0.0), StrobeTimePoint(2.0, 1.0)), phaseOffset = 0.5)
        assertEquals(setOf("id", "kind", "enabled", "version", "start", "end", "parameters", "algorithm", "strobeTimeMap", "phaseOffset"), tree(strobe).keys)
        val all = VideoEffectSettings(VideoEffectID.Quantization).copy(
            analysisFile = "a.bin", analysisDataFile = "b.bin", capturedEffectID = "C", palette = listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)),
            lockedPalette = EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.5)),
        )
        assertTrue(tree(all).keys.containsAll(setOf("analysisFile", "analysisDataFile", "capturedEffectID", "palette", "lockedPalette")))
        assertEquals(all, saved(all))
        assertEquals("C", all.analysisID)
        assertEquals(all.copy(id = "C", capturedEffectID = null), all.captureIdentity)
        assertEquals(JsonPrimitive("rgbOffset"), tree(VideoEffectSettings(VideoEffectID.RgbOffset))["kind"])
        assertEquals(VideoEffectID.entries.map { it.rawValue }, VideoEffectID.entries.map { json.encodeToString(VideoEffectID.serializer(), it).trim('"') })
        assertEquals(VideoEffectID.SixteenMM, VideoEffectID.of("sixteenMM"))
        assertNull(VideoEffectID.of("dutchAngle"))
        assertTrue(abs(effect.number("intensity", 1.75) - 0.25) < 1e-12)
    }
}
