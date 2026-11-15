package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The catalogue of the video effects against the original's
 * (YouRec/VideoEffects/VideoEffectCatalog.swift:188-391), and the rules of the
 * picker and the inspectors, translated from Tests/VideoEffectModelTests.swift
 * where a test names its line.
 */
class VideoEffectCatalogTest {
    private fun number(value: Double) = EffectValue.Number(value)
    private fun toggle(value: Boolean) = EffectValue.Toggle(value)
    private fun choice(value: String) = EffectValue.Choice(value)

    private fun hex(c: EffectRGB) = "#%02X%02X%02X".format((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())

    private fun written(p: EffectParameter): String {
        val kind = when (val k = p.kind) {
            is EffectParameterKind.Slider -> "slider(${k.min},${k.max},${k.defaultValue},${k.step})"
            is EffectParameterKind.Toggle -> "toggle(${k.defaultValue})"
            is EffectParameterKind.Stepper -> "stepper(${k.values.joinToString(",")}|${k.defaultValue})"
            is EffectParameterKind.Color -> "color(${hex(k.defaultValue)})"
            is EffectParameterKind.Picker -> "picker(${k.options.joinToString(",")}|${k.defaultValue})"
            is EffectParameterKind.Media -> "media(${k.image},${k.video})"
        }
        return "${p.id}=${p.title}:$kind${if (p.animatable) "" else "!"}"
    }

    /**
     * Every effect in the original's order: saved name, shown name, family and
     * where it can be used; then each control as `id=Title:kind(…)`, with the
     * ranges, steps, choices and defaults, and `!` after one that cannot be
     * animated. Written out from VideoEffectCatalog.swift:193-389.
     */
    private val original: List<Pair<String, List<String>>> = listOf(
        "rgbOffset|RGB Offset|color|both" to listOf(
            "redX=Red X:slider(-1.0,1.0,0.0,0.001)",
            "redY=Red Y:slider(-1.0,1.0,0.0,0.001)",
            "greenX=Green X:slider(-1.0,1.0,0.0,0.001)",
            "greenY=Green Y:slider(-1.0,1.0,0.0,0.001)",
            "blueX=Blue X:slider(-1.0,1.0,0.0,0.001)",
            "blueY=Blue Y:slider(-1.0,1.0,0.0,0.001)",
        ),
        "negativeStrobe|Negative Strobe|color|both" to listOf(
            "frequency=Cycles per second:stepper(1.0,2.0,4.0,6.0,8.0,10.0,12.0,15.0|8.0)",
            "timedBurst=Limit duration:toggle(false)!",
            "burstLength=Burst length (s):slider(0.1,10.0,1.0,0.05)!",
        ),
        "nightFix|Night fix|color|edit" to listOf(
            "mode=Mode:picker(Fast,Medium,Precise|Fast)!",
            "brightness=Shadow brightness:slider(0.0,8.0,2.5,0.1)",
            "reduction=Noise reduction:slider(0.0,3.0,1.0,0.05)",
            "detail=Preserve detail:slider(0.0,1.0,0.5,0.05)",
        ),
        "colorCorrection|Color Correction|color|both" to listOf(
            "exposure=Exposure:slider(-4.0,4.0,0.0,0.05)",
            "contrast=Contrast:slider(0.25,2.0,1.0,0.01)",
            "shadows=Shadows:slider(-1.0,1.0,0.0,0.01)",
            "highlights=Highlights:slider(-1.0,1.0,0.0,0.01)",
            "blacks=Blacks:slider(-1.0,1.0,0.0,0.01)",
            "whites=Whites:slider(-1.0,1.0,0.0,0.01)",
            "pivot=Contrast pivot:slider(0.1,0.9,0.5,0.01)",
            "temperature=Temperature:slider(-1.0,1.0,0.0,0.01)",
            "tint=Tint:slider(-1.0,1.0,0.0,0.01)",
            "saturation=Saturation:slider(0.0,2.0,1.0,0.01)",
            "vibrance=Vibrance:slider(-1.0,1.0,0.0,0.01)",
            "hue=Hue:slider(-180.0,180.0,0.0,1.0)",
        ),
        "silentTint|Silent Tint|color|both" to listOf(
            "tint=Tint:picker(Night Blue,Amber,Fire Red,Nature Green,Sepia|Amber)",
            "mode=Process:picker(Tint,Tone|Tint)",
            "contrast=Contrast:slider(0.8,1.4,1.15,0.01)",
        ),
        "threeStrip|Three-Strip|color|both" to listOf(
            "saturation=Saturation:slider(1.0,1.8,1.4,0.01)",
            "redDepth=Red depth:slider(0.0,1.0,0.6,0.01)",
            "warmth=Warmth:slider(-1.0,1.0,0.2,0.01)",
        ),
        "bleachBypass|Bleach Bypass|color|both" to listOf(
            "strength=Strength:slider(0.0,1.0,0.7,0.01)",
        ),
        "dayForNight|Day for Night|color|both" to listOf(
            "darkness=Darkness:slider(0.0,1.0,0.6,0.01)",
            "blue=Blue:slider(0.0,1.0,0.7,0.01)",
            "moonlight=Moonlight highlights:toggle(true)",
        ),
        "colorIsolation|Color Isolation|color|both" to listOf(
            "mode=Keep:picker(A color,The subject|A color)",
            "color=Hue:color(#C0392B)",
            "range=Hue range:slider(5.0,60.0,22.0,1.0)",
            "softness=Softness:slider(0.0,1.0,0.4,0.01)",
        ),
        "noir|Noir|color|both" to listOf(
            "contrast=Contrast:slider(1.0,2.0,1.5,0.01)",
            "crush=Black crush:slider(0.0,0.3,0.12,0.01)",
            "grain=Grain:toggle(true)",
            "shadowColor=Blue shadows:toggle(false)",
        ),
        "expressionism|Expressionism|color|both" to listOf(
            "contrast=Contrast:slider(1.5,3.0,2.2,0.01)",
            "vignette=Vignette:slider(0.0,1.0,0.7,0.01)",
            "iris=Iris:picker(Oval,Circle|Oval)",
            "tint=Tint:toggle(false)",
        ),
        "quantization|Quantization|quantization|both" to listOf(
            "colors=Colors:stepper(2.0,3.0,4.0,5.0,6.0,8.0,12.0,16.0|4.0)",
            "monochrome=B&W:toggle(false)",
            "palette=Palette:picker(From scene,Pop 1,Pop 2,Pop 3,Saved|From scene)",
            "smoothing=Smoothing:slider(0.0,1.0,0.5,0.01)",
            "dithering=Dithering:picker(Off,Ordered 4x4,Ordered 8x8|Off)",
            "faceDetail=Face detail:toggle(true)",
            "lockPalette=Lock palette:toggle(false)",
        ),
        "dollyZoom|Dolly Zoom|camera|both" to listOf(
            "direction=Direction:picker(Stretch,Compress|Stretch)",
            "strength=Strength:slider(0.1,0.5,0.3,0.01)",
            "duration=Duration:slider(1.0,5.0,2.5,0.1)!",
            "easing=Easing:picker(In-out cubic,Linear|In-out cubic)",
            "targetX=Subject X:slider(0.0,1.0,0.5,0.01)",
            "targetY=Subject Y:slider(0.0,1.0,0.5,0.01)",
        ),
        "crashZoom|Crash Zoom|camera|both" to listOf(
            "zoom=Zoom amount:slider(1.5,4.0,2.5,0.1)",
            "speed=Push duration:slider(0.1,0.5,0.18,0.01)!",
            "hold=Hold:slider(0.0,3.0,1.0,0.1)!",
            "return=Return:toggle(false)",
            "motionBlur=Motion blur:slider(0.0,1.0,0.6,0.01)",
            "targetX=Target X:slider(0.0,1.0,0.5,0.01)",
            "targetY=Target Y:slider(0.0,1.0,0.5,0.01)",
            "trackFace=Follow face:toggle(true)",
        ),
        "kaleidoscope|Kaleidoscope|camera|both" to listOf(
            "divisions=Divisions:stepper(2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0|2.0)",
            "centerX=Split center X:slider(0.0,1.0,0.5,0.001)",
            "centerY=Split center Y:slider(0.0,1.0,0.5,0.001)",
            "splitAngle=Split rotation:slider(-180.0,180.0,0.0,1.0)",
            "sourcePanX=Source pan X:slider(-1.0,1.0,0.0,0.001)",
            "sourcePanY=Source pan Y:slider(-1.0,1.0,0.0,0.001)",
            "sourceAngle=Source rotation:slider(-180.0,180.0,0.0,1.0)",
        ),
        "widescreen|Widescreen 1953|frame|both" to listOf(
            "ratio=Ratio:picker(2.39:1,2.20:1,1.85:1,1.33:1|2.39:1)",
            "bars=Bars:picker(Black,Blur fill|Black)",
            "flares=Flares:slider(0.0,1.0,0.4,0.01)",
            "flareColor=Flare color:picker(Blue,Amber,Neutral|Blue)",
            "ovalBokeh=Oval bokeh:toggle(false)",
        ),
        "freezeFrame|Freeze Frame|time|edit" to listOf(
            "hold=Hold duration:slider(0.5,10.0,3.0,0.1)!",
            "push=Push-in:slider(0.0,0.15,0.05,0.01)",
            "style=Style:picker(Clean,Print|Clean)",
            "audio=Audio:picker(Cut,Continue,Fade out over hold|Cut)",
        ),
        "stepPrinting|Step Printing|time|edit" to listOf(
            "step=Step frames:stepper(2.0,3.0,4.0,5.0,6.0|3.0)",
            "smear=Smear:slider(0.0,1.0,0.7,0.01)",
            "speed=Speed:stepper(0.5,1.0|1.0)",
        ),
        "undercranking|Undercranking|time|both" to listOf(
            "fps=Frame rate:stepper(12.0,16.0,18.0|16.0)",
            "speed=Speed-up:slider(1.0,1.5,1.25,0.05)",
            "flicker=Flicker:slider(0.0,1.0,0.5,0.01)",
            "weave=Gate weave:slider(0.0,1.0,0.4,0.01)",
            "mute=Mute audio:toggle(false)",
        ),
        "halation|Halation|texture|both" to listOf(
            "glow=Glow:slider(0.0,1.0,0.4,0.01)",
            "red=Halation red:slider(0.0,1.0,0.5,0.01)",
            "flash=Shadow lift:slider(0.0,1.0,0.2,0.01)",
            "flashColor=Flash color:picker(Neutral,Warm,Cool|Warm)",
        ),
        "sixteenMM|16mm|texture|both" to listOf(
            "grain=Grain:slider(0.0,1.0,0.5,0.01)",
            "grainSize=Grain size:picker(Fine,Medium,Coarse|Medium)",
            "softness=Softness:slider(0.0,1.0,0.3,0.01)",
            "weave=Gate weave:slider(0.0,1.0,0.2,0.01)",
            "frameEdge=Frame edge:toggle(false)",
            "color=Color:picker(Color,B&W|Color)",
        ),
        "superEight|Super 8|texture|both" to listOf(
            "fade=Fade:slider(0.0,1.0,0.5,0.01)",
            "grain=Grain:slider(0.0,1.0,0.7,0.01)",
            "weave=Weave:slider(0.0,1.0,0.5,0.01)",
            "fps=Frame rate:stepper(18.0,24.0|18.0)",
            "leaks=Light leaks:slider(0.0,1.0,0.2,0.01)",
            "border=Frame border:toggle(true)",
        ),
        "chromaKey|Chroma Key|key|both" to listOf(
            "screenColor=Manual screen color:color(#00FF00)",
            "screenType=Screen type:picker(Auto,Green,Blue|Auto)",
            "autoScreen=Detect green / blue screen:toggle(true)",
            "strength=Strength:slider(0.0,2.0,1.0,0.01)",
            "balance=Balance:slider(0.0,1.0,0.5,0.01)",
            "blackClip=Black clip:slider(0.0,0.5,0.05,0.01)",
            "whiteClip=White clip:slider(0.5,1.0,0.9,0.01)",
            "softness=Edge softness:slider(0.0,1.0,0.3,0.01)",
            "grow=Shrink / grow:slider(-5.0,5.0,0.0,0.1)",
            "despill=Despill:slider(0.0,1.0,0.8,0.01)",
            "wrap=Light wrap:slider(0.0,1.0,0.25,0.01)",
            "personHint=Use person hint:toggle(false)",
            "cleanPlate=Clean plate:media(true,false)",
            "view=View:picker(Result,Matte,Original|Result)",
            "background=Background:picker(Solid,Image,Video,Blur original,Studio,Window|Solid)",
            "backgroundColor=Background color:color(#20252D)",
            "media=Background media:media(true,true)",
            "fit=Framing:picker(Fill,Fit|Fill)",
            "backgroundX=Background X:slider(0.0,1.0,0.5,0.01)",
            "backgroundY=Background Y:slider(0.0,1.0,0.5,0.01)",
            "backgroundZoom=Background zoom:slider(1.0,4.0,1.0,0.05)",
            "backgroundBlur=Background blur:slider(0.0,20.0,0.0,1.0)",
            "backgroundBrightness=Background brightness:slider(-1.0,1.0,0.0,0.01)",
            "backgroundAudio=Background sound:toggle(false)",
            "matchColor=Match color:toggle(false)",
            "matchStrength=Match strength:slider(0.0,1.0,0.3,0.01)",
        ),
        "autoBackground|Auto Background|key|both" to listOf(
            "softness=Edge softness:slider(0.0,1.0,0.3,0.01)",
            "grow=Shrink / grow:slider(-5.0,5.0,0.0,0.1)",
            "wrap=Light wrap:slider(0.0,1.0,0.25,0.01)",
            "portraitBlur=Portrait blur:slider(0.0,40.0,12.0,1.0)",
            "view=View:picker(Result,Matte,Original|Result)",
            "background=Background:picker(Solid,Image,Video,Blur original,Studio,Window|Blur original)",
            "backgroundColor=Background color:color(#20252D)",
            "media=Background media:media(true,true)",
            "fit=Framing:picker(Fill,Fit|Fill)",
            "backgroundX=Background X:slider(0.0,1.0,0.5,0.01)",
            "backgroundY=Background Y:slider(0.0,1.0,0.5,0.01)",
            "backgroundZoom=Background zoom:slider(1.0,4.0,1.0,0.05)",
            "backgroundBlur=Background blur:slider(0.0,20.0,0.0,1.0)",
            "backgroundBrightness=Background brightness:slider(-1.0,1.0,0.0,0.01)",
            "backgroundAudio=Background sound:toggle(false)",
            "matchColor=Match color:toggle(false)",
            "matchStrength=Match strength:slider(0.0,1.0,0.3,0.01)",
        ),
    )

    @Test fun theCatalogueIsTheOriginalsEffectForEffectAndControlForControl() {
        assertEquals(24, VideoEffectCatalog.all.size)
        assertEquals(151, VideoEffectCatalog.all.sumOf { it.parameters.size })
        // :7. Every effect has a catalog entry, and its own.
        assertEquals(VideoEffectID.entries.toSet(), VideoEffectCatalog.all.map { it.id }.toSet())
        for (id in VideoEffectID.entries) assertEquals(id, id.descriptor.id)
        assertEquals(
            original.map { it.first },
            VideoEffectCatalog.all.map { "${it.id.rawValue}|${it.displayName}|${it.family.name.lowercase()}|${it.mode.name.lowercase()}" },
        )
        for ((expected, descriptor) in original.zip(VideoEffectCatalog.all)) {
            assertEquals(expected.first, expected.second, descriptor.parameters.map(::written))
            // A catalogue entry is at its default, with no keys, and holds what it accepts.
            for (p in descriptor.parameters) {
                assertEquals(p.kind.default, p.value)
                assertTrue(p.keyframes.isEmpty() && p.valid)
            }
            assertEquals(descriptor.parameters.map { it.id }.toSet().size, descriptor.parameters.size)
            assertTrue(descriptor.historyNote.endsWith(".") && descriptor.parameters.size <= 38)
        }
    }

    /** VideoEffectModel.swift:5-12, :34-54, :155; docs/VIDEO-EFFECTS.md:33-56. */
    @Test fun familiesModesAndTheShaderNumbers() {
        fun of(family: EffectFamily) = VideoEffectID.entries.filter { it.descriptor.family == family }.toSet()
        assertEquals(setOf(VideoEffectID.ChromaKey, VideoEffectID.AutoBackground), of(EffectFamily.Key))
        assertEquals(setOf(VideoEffectID.DollyZoom, VideoEffectID.CrashZoom, VideoEffectID.Kaleidoscope), of(EffectFamily.Camera))
        assertEquals(setOf(VideoEffectID.Quantization), of(EffectFamily.Quantization))
        assertEquals(setOf(VideoEffectID.Halation, VideoEffectID.SixteenMM, VideoEffectID.SuperEight), of(EffectFamily.Texture))
        assertEquals(setOf(VideoEffectID.Widescreen), of(EffectFamily.Frame))
        assertEquals(setOf(VideoEffectID.FreezeFrame, VideoEffectID.StepPrinting, VideoEffectID.Undercranking), of(EffectFamily.Time))
        assertEquals(11, of(EffectFamily.Color).size)
        // Editor only: Night fix, Freeze Frame and Step Printing. Nothing is for the recorder alone.
        assertEquals(
            setOf(VideoEffectID.NightFix, VideoEffectID.FreezeFrame, VideoEffectID.StepPrinting),
            VideoEffectID.entries.filter { it.descriptor.mode == EffectMode.Edit }.toSet(),
        )
        assertTrue(VideoEffectID.entries.none { it.descriptor.mode == EffectMode.Live })
        // Intensity is for image effects: not the camera moves, not the structural time effects.
        assertEquals(of(EffectFamily.Camera) + of(EffectFamily.Time), VideoEffectID.entries.filterNot { it.descriptor.supportsIntensity }.toSet())
        for (id in VideoEffectID.entries) {
            val parameters = VideoEffectSettings(id).parameters
            assertEquals(id.descriptor.supportsIntensity, parameters.first().id == "intensity")
            assertTrue(parameters.count { it.id == "intensity" } <= 1)
            if (id.descriptor.supportsIntensity) assertEquals(EffectParameterKind.Slider(0.0, 1.0, 1.0, 0.01), parameters.first().kind)
        }
        assertEquals(
            mapOf(
                "rgbOffset" to 23, "negativeStrobe" to 24, "nightFix" to 21, "colorCorrection" to 20, "silentTint" to 0, "threeStrip" to 1,
                "bleachBypass" to 2, "dayForNight" to 3, "colorIsolation" to 4, "noir" to 5, "expressionism" to 6, "quantization" to 7,
                "dollyZoom" to 8, "crashZoom" to 9, "kaleidoscope" to 22, "widescreen" to 11, "freezeFrame" to 12, "stepPrinting" to 13,
                "undercranking" to 14, "halation" to 15, "sixteenMM" to 16, "superEight" to 17, "chromaKey" to 18, "autoBackground" to 19,
            ),
            VideoEffectID.entries.associate { it.rawValue to it.shaderIndex },
        )
        assertEquals(listOf("Background", "Camera", "Color & tone", "Quantization", "Film texture", "Frame", "Time"), EffectFamily.entries.map { it.title })
        assertEquals(listOf("redX", "redY", "Green"), listOf(RGBChannel.Red.x, RGBChannel.Red.y, RGBChannel.Green.channelName))
    }

    /** The controls that cannot be animated, as section 2.1 of docs/PIP-AND-EFFECTS-PLAN.md lists them from VideoEffectCatalog.swift. */
    @Test fun theControlsThatCannotBeAnimated() {
        assertEquals(
            setOf(
                "negativeStrobe.timedBurst", "negativeStrobe.burstLength", "nightFix.mode", "dollyZoom.duration",
                "crashZoom.speed", "crashZoom.hold", "freezeFrame.hold",
            ),
            VideoEffectCatalog.all.flatMap { d -> d.parameters.filterNot { it.animatable }.map { "${d.id.rawValue}.${it.id}" } }.toSet(),
        )
    }

    // :126-132; VideoEffectCatalog.swift:54-59.
    @Test fun theLibraryOpensOnTheFourSections() {
        val sections = VideoEffectLibrarySection.entries
        assertEquals(listOf("colorStyle", "cameraFrame", "time", "background"), sections.map { it.rawValue })
        assertEquals(listOf("Color & style", "Camera & frame", "Time", "Background"), sections.map { it.title })
        for (live in listOf(false, true)) {
            val available = sections.flatMap { s -> s.effects(live).map { it.id } }
            val expected = VideoEffectID.entries.filter { !live || it.descriptor.mode != EffectMode.Edit }
            // Every available individual effect appears exactly once.
            assertEquals(expected.size, available.size)
            assertEquals(expected.toSet(), available.toSet())
        }
        assertEquals(
            listOf(
                "nightFix", "colorCorrection", "rgbOffset", "silentTint", "threeStrip", "bleachBypass", "dayForNight", "colorIsolation",
                "noir", "expressionism", "quantization", "halation", "sixteenMM", "superEight",
            ),
            VideoEffectLibrarySection.ColorStyle.kinds.map { it.rawValue },
        )
        assertEquals(listOf("dollyZoom", "crashZoom", "kaleidoscope", "widescreen"), VideoEffectLibrarySection.CameraFrame.kinds.map { it.rawValue })
        assertEquals(listOf("negativeStrobe", "freezeFrame", "stepPrinting", "undercranking"), VideoEffectLibrarySection.Time.kinds.map { it.rawValue })
        assertEquals(listOf("autoBackground", "chromaKey"), VideoEffectLibrarySection.Background.kinds.map { it.rawValue })
        assertEquals(listOf(VideoEffectID.NegativeStrobe, VideoEffectID.Undercranking), VideoEffectLibrarySection.Time.effects(live = true).map { it.id })
        // The picker's section is not the pipeline's family: the strobe is a colour effect found under Time.
        assertEquals(VideoEffectLibrarySection.Time, VideoEffectID.NegativeStrobe.librarySection)
        assertEquals(EffectFamily.Color, VideoEffectID.NegativeStrobe.descriptor.family)
        for (id in VideoEffectID.entries) {
            assertTrue(id.summary.endsWith(".") && id.usageHelp.endsWith("."))
            assertEquals(id in listOf(VideoEffectID.ColorCorrection, VideoEffectID.NightFix, VideoEffectID.AutoBackground, VideoEffectID.FreezeFrame), id.historicalHelp == null)
        }
        assertEquals("Tint the picture with a single film color.", VideoEffectID.SilentTint.summary)
    }

    // :139-170.
    @Test fun theInspectorsShowOnlyTheControlsThatDoSomething() {
        for (live in listOf(false, true)) {
            fun visible(id: String, e: VideoEffectSettings) = VideoEffectParameterVisibility.visible(e.parameters.first { it.id == id }, e, live = live)
            fun shown(e: VideoEffectSettings) = e.parameters.filter { VideoEffectParameterVisibility.visible(it, e, live = live) }.map { it.id }
            var key = VideoEffectSettings(VideoEffectID.ChromaKey)
            // Solid backgrounds hide irrelevant framing and color-match controls in both inspectors.
            assertTrue(shown(key).none { it in listOf("fit", "backgroundX", "backgroundY", "backgroundZoom", "media", "matchStrength") })
            val isolation = VideoEffectSettings(VideoEffectID.ColorIsolation).with("mode", choice("The subject"))
            assertTrue("Subject isolation hides color selection in both inspectors", shown(isolation).none { it in listOf("color", "range") })
            // New keys do not imply automatic person cutout or show an ignored manual color.
            assertFalse(key.toggle("personHint"))
            assertFalse(visible("screenColor", key))
            key = key.with("autoScreen", toggle(false))
            // Manual key infers screen type from a supported green/blue sample.
            assertTrue(visible("screenColor", key))
            assertFalse(visible("screenType", key))
            key = key.with("cleanPlate", EffectValue.Media("plate.png"))
            // A clean plate replaces the color reference.
            assertFalse(visible("screenColor", key))
            assertFalse(visible("autoScreen", key))
            var bg = VideoEffectSettings(VideoEffectID.AutoBackground)
            // Original blur has one meaningful radius control.
            assertFalse(visible("backgroundBlur", bg))
            assertTrue(visible("portraitBlur", bg))
            bg = bg.with("background", choice("Image"))
            // Media controls wait for a real image.
            assertTrue(visible("media", bg))
            assertFalse(visible("fit", bg))
            assertFalse(visible("backgroundBrightness", bg))
            bg = bg.with("media", EffectValue.Media("picture.png"))
            // Imported backgrounds expose their controls.
            assertTrue(visible("fit", bg))
            assertTrue(visible("backgroundBrightness", bg))
            var crash = VideoEffectSettings(VideoEffectID.CrashZoom)
            // A non-returning tracked zoom hides ineffective hold and manual target controls.
            assertFalse(visible("hold", crash))
            assertFalse(visible("targetX", crash))
            crash = crash.with("return", toggle(true)).with("trackFace", toggle(false))
            // Returning/manual zoom exposes its active controls.
            assertTrue(visible("hold", crash))
            assertEquals(!live, visible("targetX", crash))
            crash = crash.copy(releaseTime = 2.0)
            // Recorded manual return owns its timing; ignored automatic controls stay hidden.
            assertFalse(visible("return", crash))
            assertFalse(visible("hold", crash))
            var quant = VideoEffectSettings(VideoEffectID.Quantization)
            val palette = quant.parameters.first { it.id == "palette" }
            assertFalse("Saved cannot be selected before a palette exists", "Saved" in VideoEffectParameterVisibility.choices(palette, quant))
            quant = quant.copy(palette = listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)))
            assertTrue("Saved palettes remain selectable", "Saved" in VideoEffectParameterVisibility.choices(palette, quant))
        }
    }

    /** VideoEffectCatalog.swift:94-133, the rules the original's tests do not reach. */
    @Test fun theOtherRulesOfWhatShows() {
        fun visible(id: String, e: VideoEffectSettings, time: Double = 0.0, live: Boolean = false, hasDepth: Boolean = false) =
            VideoEffectParameterVisibility.visible(e.parameters.first { it.id == id }, e, time, live, hasDepth)
        val strobe = VideoEffectSettings(VideoEffectID.NegativeStrobe)
        assertFalse(visible("burstLength", strobe))
        assertTrue(visible("burstLength", strobe.with("timedBurst", toggle(true))))
        val wide = VideoEffectSettings(VideoEffectID.Widescreen)
        // Oval bokeh needs depth, which only the editor can have.
        assertFalse(visible("ovalBokeh", wide))
        assertTrue(visible("ovalBokeh", wide, hasDepth = true))
        assertFalse(visible("ovalBokeh", wide, live = true, hasDepth = true))
        assertTrue(visible("flareColor", wide))
        assertFalse(visible("flareColor", wide.with("flares", number(0.0))))
        // A rule looks at the values of the moment it is asked about.
        val fading = wide.withKeyframe("flares", 0.0, number(0.4)).withKeyframe("flares", 2.0, number(0.0))
        assertTrue(visible("flareColor", fading, time = 1.0))
        assertFalse(visible("flareColor", fading, time = 2.0))
        assertFalse(visible("flashColor", VideoEffectSettings(VideoEffectID.Halation).with("flash", number(0.0))))
        assertFalse(visible("grainSize", VideoEffectSettings(VideoEffectID.SixteenMM).with("grain", number(0.0))))
        assertFalse(visible("iris", VideoEffectSettings(VideoEffectID.Expressionism).with("vignette", number(0.0))))
        assertTrue(visible("iris", VideoEffectSettings(VideoEffectID.Expressionism)))
        val quant = VideoEffectSettings(VideoEffectID.Quantization)
        assertTrue(visible("palette", quant) && visible("faceDetail", quant))
        assertFalse(visible("palette", quant.with("monochrome", toggle(true))))
        assertFalse(visible("faceDetail", quant.with("colors", number(5.0))))
        val key = VideoEffectSettings(VideoEffectID.ChromaKey)
        assertTrue(visible("backgroundColor", key) && visible("screenType", key))
        assertFalse(visible("backgroundBlur", key))
        assertTrue(visible("backgroundBlur", key.with("background", choice("Blur original"))))
        assertFalse(visible("backgroundColor", key.with("background", choice("Studio"))))
        assertTrue(visible("backgroundBlur", key.with("background", choice("Studio"))))
        assertFalse(visible("matchColor", key.with("background", choice("Blur original"))))
        assertFalse(visible("matchStrength", key))
        assertTrue(visible("matchStrength", key.with("matchColor", toggle(true))))
        val video = key.with("background", choice("Video")).with("media", EffectValue.Media("back.mov"))
        assertTrue(visible("backgroundAudio", video))
        assertFalse(visible("backgroundAudio", video.with("background", choice("Image"))))
        assertFalse(visible("portraitBlur", VideoEffectSettings(VideoEffectID.AutoBackground).with("background", choice("Solid"))))
        val dolly = VideoEffectSettings(VideoEffectID.DollyZoom)
        assertTrue(visible("strength", dolly))
        assertFalse(visible("strength", dolly, live = true))
        assertTrue(visible("direction", dolly, live = true))
    }

    /** VideoEffectCatalog.swift:171-186. */
    @Test fun theHeadingsOfAnInspector() {
        assertEquals(listOf(VideoEffectParameterSection.General), VideoEffectParameterSection.sections(VideoEffectSettings(VideoEffectID.Noir)))
        val key = VideoEffectSettings(VideoEffectID.ChromaKey)
        assertEquals(VideoEffectParameterSection.entries, VideoEffectParameterSection.sections(key))
        fun section(id: String, e: VideoEffectSettings) = VideoEffectParameterSection.section(e.parameters.first { it.id == id }, e)
        assertEquals(VideoEffectParameterSection.General, section("intensity", key))
        assertEquals(VideoEffectParameterSection.Screen, section("strength", key))
        assertEquals(VideoEffectParameterSection.Edges, section("despill", key))
        assertEquals(VideoEffectParameterSection.CleanPlate, section("cleanPlate", key))
        assertEquals(VideoEffectParameterSection.Background, section("matchColor", key))
        val auto = VideoEffectSettings(VideoEffectID.AutoBackground)
        assertEquals(VideoEffectParameterSection.General, section("view", auto))
        assertEquals(VideoEffectParameterSection.Background, section("portraitBlur", auto))
        assertEquals(
            listOf(VideoEffectParameterSection.General, VideoEffectParameterSection.Edges, VideoEffectParameterSection.Background),
            VideoEffectParameterSection.sections(auto),
        )
        assertEquals(listOf("Effect", "Screen", "Edges", "Clean plate", "Background"), VideoEffectParameterSection.entries.map { it.title })
    }

    // :171-177.
    @Test fun screenAndHueSamples() {
        // Green and blue screen samples are supported.
        assertTrue(ChromaScreen.accepts(EffectRGB(0.0, 1.0, 0.0)) && ChromaScreen.accepts(EffectRGB(0.0, 0.0, 1.0)))
        // Beige, red and neutral screen samples cannot silently select an unsupported key.
        assertFalse(ChromaScreen.accepts(EffectRGB(0.8, 0.75, 0.65)) || ChromaScreen.accepts(EffectRGB(1.0, 0.0, 0.0)) || ChromaScreen.accepts(EffectRGB(0.5, 0.5, 0.5)))
        for (hue in listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0, 359.0)) {
            val color = IsolationHue.color(hue)
            // Hue controls offer only meaningful chromatic targets.
            assertTrue(IsolationHue.accepts(color))
            assertTrue(abs(IsolationHue.degrees(color) - hue) < 0.0001)
        }
        // Neutral samples must not silently isolate red.
        assertFalse(IsolationHue.accepts(EffectRGB(0.0, 0.0, 0.0)) || IsolationHue.accepts(EffectRGB(1.0, 1.0, 1.0)) || IsolationHue.accepts(EffectRGB(0.5, 0.5, 0.5)))
        assertEquals(0.0, IsolationHue.degrees(EffectRGB(0.5, 0.5, 0.5)), 0.0)
    }

    // :15, :84; VideoEffectCatalog.swift:395-407.
    @Test fun theCorrectionsControlsAndHowTheyRead() {
        // Interpolated near-neutral values never display negative zero.
        assertEquals("0", ColorCorrectionControls.display("tint", -0.004))
        assertEquals("0°", ColorCorrectionControls.display("hue", -0.2))
        val correction = VideoEffectSettings(VideoEffectID.ColorCorrection)
        // Every correction knob appears exactly once in its inspector.
        val knobs = ColorCorrectionControls.light + ColorCorrectionControls.advanced + ColorCorrectionControls.color
        assertEquals(correction.kind.descriptor.parameters.map { it.id }.toSet(), knobs.toSet())
        assertEquals(12, knobs.size)
        assertEquals("+1.50 EV", ColorCorrectionControls.display("exposure", 1.5))
        assertEquals("-0.25 EV", ColorCorrectionControls.display("exposure", -0.25))
        assertEquals("+0.00 EV", ColorCorrectionControls.display("exposure", 0.000001))
        assertEquals("130%", ColorCorrectionControls.display("contrast", 1.3))
        assertEquals("50%", ColorCorrectionControls.display("pivot", 0.5))
        assertEquals("+12°", ColorCorrectionControls.display("hue", 12.0))
        assertEquals("-75°", ColorCorrectionControls.display("hue", -75.0))
        assertEquals("+30", ColorCorrectionControls.display("shadows", 0.3))
        assertEquals("-40", ColorCorrectionControls.display("highlights", -0.4))
        assertTrue(ColorCorrectionControls.help("pivot").startsWith("The tone that stays fixed"))
        assertTrue(ColorCorrectionControls.help("intensity").startsWith("Blends this correction"))
        assertNull(VideoEffectID.ColorCorrection.historicalHelp)
    }
}
