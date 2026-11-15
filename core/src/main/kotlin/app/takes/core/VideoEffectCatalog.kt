package app.takes.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/*
 * The catalogue of the original's video effects: every effect with its
 * controls, ranges and defaults, the picker's sections and the rules for which
 * controls show. Ported from YouRec/VideoEffects/VideoEffectCatalog.swift
 * (cited below as `Catalog`). The catalogue is data: it lists every effect,
 * whether or not this app draws it yet.
 */

/**
 * Original effect definitions. Parameter IDs and units are part of the project
 * format. The order of an effect's parameters is also the order in which the
 * shader receives them (see [EffectFrame]). Catalog:188-391.
 */
object VideoEffectCatalog {
    private fun slider(id: String, title: String, min: Double, max: Double, defaultValue: Double, step: Double, animatable: Boolean = true) =
        EffectParameter(id, title, EffectParameterKind.Slider(min, max, defaultValue, step), animatable)

    private fun toggle(id: String, title: String, defaultValue: Boolean, animatable: Boolean = true) =
        EffectParameter(id, title, EffectParameterKind.Toggle(defaultValue), animatable)

    private fun stepper(id: String, title: String, values: List<Double>, defaultValue: Double) =
        EffectParameter(id, title, EffectParameterKind.Stepper(values, defaultValue), true)

    private fun color(id: String, title: String, hex: String) =
        EffectParameter(id, title, EffectParameterKind.Color(EffectRGB.hex(hex)), true)

    private fun picker(id: String, title: String, options: List<String>, defaultValue: String, animatable: Boolean = true) =
        EffectParameter(id, title, EffectParameterKind.Picker(options, defaultValue), animatable)

    private fun media(id: String, title: String, image: Boolean, video: Boolean) =
        EffectParameter(id, title, EffectParameterKind.Media(image, video), true)

    /** The controls a replaced background has, the same for Chroma Key and Auto Background. Catalog:358-369, :377-388. */
    private fun background(defaultValue: String) = listOf(
        picker("background", "Background", listOf("Solid", "Image", "Video", "Blur original", "Studio", "Window"), defaultValue),
        color("backgroundColor", "Background color", "#20252D"),
        media("media", "Background media", image = true, video = true),
        picker("fit", "Framing", listOf("Fill", "Fit"), "Fill"),
        slider("backgroundX", "Background X", 0.0, 1.0, 0.5, 0.01),
        slider("backgroundY", "Background Y", 0.0, 1.0, 0.5, 0.01),
        slider("backgroundZoom", "Background zoom", 1.0, 4.0, 1.0, 0.05),
        slider("backgroundBlur", "Background blur", 0.0, 20.0, 0.0, 1.0),
        slider("backgroundBrightness", "Background brightness", -1.0, 1.0, 0.0, 0.01),
        toggle("backgroundAudio", "Background sound", false),
        toggle("matchColor", "Match color", false),
        slider("matchStrength", "Match strength", 0.0, 1.0, 0.3, 0.01),
    )

    val all: List<VideoEffectDescriptor> = listOf(
        // History: Kodak Essential Reference Guide for Filmmakers, Color Timing/Color Correction.
        // https://www.kodak.com/content/products-brochures/Film/kodak-essential-reference-guide-for-filmmakers.pdf
        VideoEffectDescriptor(
            VideoEffectID.RgbOffset, "RGB Offset",
            "Color dye-transfer printing aligned three separate matrices to register the color image.",
            EffectFamily.Color, EffectMode.Both,
            RGBChannel.entries.flatMap { channel ->
                listOf(
                    slider(channel.x, channel.channelName + " X", -1.0, 1.0, 0.0, 0.001),
                    slider(channel.y, channel.channelName + " Y", -1.0, 1.0, 0.0, 0.001),
                )
            },
        ),
        VideoEffectDescriptor(
            VideoEffectID.NegativeStrobe, "Negative Strobe",
            "Experimental flicker films of the 1960s intercut positive and negative images.",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                stepper("frequency", "Cycles per second", listOf(1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0), 8.0),
                toggle("timedBurst", "Limit duration", false, animatable = false),
                slider("burstLength", "Burst length (s)", 0.1, 10.0, 1.0, 0.05, animatable = false),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.NightFix, "Night fix",
            "Digital film restoration combines motion-aligned frames to separate image detail from noise before correcting exposure.",
            EffectFamily.Color, EffectMode.Edit,
            listOf(
                picker("mode", "Mode", NightFixMode.entries.map { it.rawValue }, "Fast", animatable = false),
                slider("brightness", "Shadow brightness", 0.0, 8.0, 2.5, 0.1),
                slider("reduction", "Noise reduction", 0.0, 3.0, 1.0, 0.05),
                slider("detail", "Preserve detail", 0.0, 1.0, 0.5, 0.05),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.ColorCorrection, "Color Correction",
            "Film laboratories balanced each scene with color timing; digital grading added precise control of contrast and individual tones.",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                slider("exposure", "Exposure", -4.0, 4.0, 0.0, 0.05),
                slider("contrast", "Contrast", 0.25, 2.0, 1.0, 0.01),
                slider("shadows", "Shadows", -1.0, 1.0, 0.0, 0.01),
                slider("highlights", "Highlights", -1.0, 1.0, 0.0, 0.01),
                slider("blacks", "Blacks", -1.0, 1.0, 0.0, 0.01),
                slider("whites", "Whites", -1.0, 1.0, 0.0, 0.01),
                slider("pivot", "Contrast pivot", 0.1, 0.9, 0.5, 0.01),
                slider("temperature", "Temperature", -1.0, 1.0, 0.0, 0.01),
                slider("tint", "Tint", -1.0, 1.0, 0.0, 0.01),
                slider("saturation", "Saturation", 0.0, 2.0, 1.0, 0.01),
                slider("vibrance", "Vibrance", -1.0, 1.0, 0.0, 0.01),
                slider("hue", "Hue", -180.0, 180.0, 0.0, 1.0),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.SilentTint, "Silent Tint",
            "Silent cinema used tinting and toning to color whole shots and suggest mood or time of day.",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                picker("tint", "Tint", listOf("Night Blue", "Amber", "Fire Red", "Nature Green", "Sepia"), "Amber"),
                picker("mode", "Process", listOf("Tint", "Tone"), "Tint"),
                slider("contrast", "Contrast", 0.8, 1.4, 1.15, 0.01),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.ThreeStrip, "Three-Strip",
            "Three-strip dye-transfer printing layered cyan, yellow and magenta dyes to create rich cinema color.",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                slider("saturation", "Saturation", 1.0, 1.8, 1.4, 0.01),
                slider("redDepth", "Red depth", 0.0, 1.0, 0.6, 0.01),
                slider("warmth", "Warmth", -1.0, 1.0, 0.2, 0.01),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.BleachBypass, "Bleach Bypass",
            "Retaining silver during color-film processing produces desaturated color and dense contrast.",
            EffectFamily.Color, EffectMode.Both,
            listOf(slider("strength", "Strength", 0.0, 1.0, 0.7, 0.01)),
        ),
        VideoEffectDescriptor(
            VideoEffectID.DayForNight, "Day for Night",
            "Day-for-night photography uses exposure and color filtration to make daylight scenes read as night.",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                slider("darkness", "Darkness", 0.0, 1.0, 0.6, 0.01),
                slider("blue", "Blue", 0.0, 1.0, 0.7, 0.01),
                toggle("moonlight", "Moonlight highlights", true),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.ColorIsolation, "Color Isolation",
            "Selective color contrasts a colored detail with monochrome surroundings, as in Rumble Fish (1983).",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                picker("mode", "Keep", listOf("A color", "The subject"), "A color"),
                color("color", "Hue", "#C0392B"),
                slider("range", "Hue range", 5.0, 60.0, 22.0, 1.0),
                slider("softness", "Softness", 0.0, 1.0, 0.4, 0.01),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.Noir, "Noir",
            "Film noir uses low-key lighting and strong shadows; colored lens filters also shaped black-and-white tones.",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                slider("contrast", "Contrast", 1.0, 2.0, 1.5, 0.01),
                slider("crush", "Black crush", 0.0, 0.3, 0.12, 0.01),
                toggle("grain", "Grain", true),
                toggle("shadowColor", "Blue shadows", false),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.Expressionism, "Expressionism",
            "German Expressionist cinema used stylized shadows and distorted sets, notably in The Cabinet of Dr. Caligari (1920).",
            EffectFamily.Color, EffectMode.Both,
            listOf(
                slider("contrast", "Contrast", 1.5, 3.0, 2.2, 0.01),
                slider("vignette", "Vignette", 0.0, 1.0, 0.7, 0.01),
                picker("iris", "Iris", listOf("Oval", "Circle"), "Oval"),
                toggle("tint", "Tint", false),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.Quantization, "Quantization",
            "Posterization reduces continuous tones to flat bands, echoing photographic silkscreen prints of the 1960s.",
            EffectFamily.Quantization, EffectMode.Both,
            listOf(
                stepper("colors", "Colors", listOf(2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 12.0, 16.0), 4.0),
                toggle("monochrome", "B&W", false),
                picker("palette", "Palette", listOf("From scene", "Pop 1", "Pop 2", "Pop 3", "Saved"), "From scene"),
                slider("smoothing", "Smoothing", 0.0, 1.0, 0.5, 0.01),
                picker("dithering", "Dithering", listOf("Off", "Ordered 4x4", "Ordered 8x8"), "Off"),
                toggle("faceDetail", "Face detail", true),
                toggle("lockPalette", "Lock palette", false),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.DollyZoom, "Dolly Zoom",
            "Opposing a camera move with zoom changes perspective while keeping the subject the same size, as in Vertigo (1958).",
            EffectFamily.Camera, EffectMode.Both,
            listOf(
                picker("direction", "Direction", listOf("Stretch", "Compress"), "Stretch"),
                slider("strength", "Strength", 0.1, 0.5, 0.3, 0.01),
                slider("duration", "Duration", 1.0, 5.0, 2.5, 0.1, animatable = false),
                picker("easing", "Easing", listOf("In-out cubic", "Linear"), "In-out cubic"),
                slider("targetX", "Subject X", 0.0, 1.0, 0.5, 0.01),
                slider("targetY", "Subject Y", 0.0, 1.0, 0.5, 0.01),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.CrashZoom, "Crash Zoom",
            "Fast lens zooms became an expressive device in 1960s and 1970s westerns and martial-arts cinema.",
            EffectFamily.Camera, EffectMode.Both,
            listOf(
                slider("zoom", "Zoom amount", 1.5, 4.0, 2.5, 0.1),
                slider("speed", "Push duration", 0.1, 0.5, 0.18, 0.01, animatable = false),
                slider("hold", "Hold", 0.0, 3.0, 1.0, 0.1, animatable = false),
                toggle("return", "Return", false),
                slider("motionBlur", "Motion blur", 0.0, 1.0, 0.6, 0.01),
                slider("targetX", "Target X", 0.0, 1.0, 0.5, 0.01),
                slider("targetY", "Target Y", 0.0, 1.0, 0.5, 0.01),
                toggle("trackFace", "Follow face", true),
            ),
        ),
        // https://www.eamesoffice.com/the-work/kaleidoscope-shop/
        VideoEffectDescriptor(
            VideoEffectID.Kaleidoscope, "Kaleidoscope",
            "A camera fitted with mirrors turned ordinary workshop scenes into moving patterns in Kaleidoscope Shop (1959).",
            EffectFamily.Camera, EffectMode.Both,
            listOf(
                stepper("divisions", "Divisions", listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), 2.0),
                slider("centerX", "Split center X", 0.0, 1.0, 0.5, 0.001),
                slider("centerY", "Split center Y", 0.0, 1.0, 0.5, 0.001),
                slider("splitAngle", "Split rotation", -180.0, 180.0, 0.0, 1.0),
                slider("sourcePanX", "Source pan X", -1.0, 1.0, 0.0, 0.001),
                slider("sourcePanY", "Source pan Y", -1.0, 1.0, 0.0, 0.001),
                slider("sourceAngle", "Source rotation", -180.0, 180.0, 0.0, 1.0),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.Widescreen, "Widescreen 1953",
            "The widescreen expansion of the 1950s brought broad compositions, anamorphic flares and distinctive lens rendering.",
            EffectFamily.Frame, EffectMode.Both,
            listOf(
                picker("ratio", "Ratio", listOf("2.39:1", "2.20:1", "1.85:1", "1.33:1"), "2.39:1"),
                picker("bars", "Bars", listOf("Black", "Blur fill"), "Black"),
                slider("flares", "Flares", 0.0, 1.0, 0.4, 0.01),
                picker("flareColor", "Flare color", listOf("Blue", "Amber", "Neutral"), "Blue"),
                toggle("ovalBokeh", "Oval bokeh", false),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.FreezeFrame, "Freeze Frame",
            "A held frame can suspend a film at a decisive moment, as at the end of The 400 Blows (1959).",
            EffectFamily.Time, EffectMode.Edit,
            listOf(
                slider("hold", "Hold duration", 0.5, 10.0, 3.0, 0.1, animatable = false),
                slider("push", "Push-in", 0.0, 0.15, 0.05, 0.01),
                picker("style", "Style", listOf("Clean", "Print"), "Clean"),
                picker("audio", "Audio", listOf("Cut", "Continue", "Fade out over hold"), "Cut"),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.StepPrinting, "Step Printing",
            "Repeating printed frames produces stuttering motion, combined with blur in Chungking Express (1994).",
            EffectFamily.Time, EffectMode.Edit,
            listOf(
                stepper("step", "Step frames", listOf(2.0, 3.0, 4.0, 5.0, 6.0), 3.0),
                slider("smear", "Smear", 0.0, 1.0, 0.7, 0.01),
                stepper("speed", "Speed", listOf(0.5, 1.0), 1.0),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.Undercranking, "Undercranking",
            "Shooting fewer frames than the projection rate gives the quick, uneven movement associated with silent comedy.",
            EffectFamily.Time, EffectMode.Both,
            listOf(
                stepper("fps", "Frame rate", listOf(12.0, 16.0, 18.0), 16.0),
                slider("speed", "Speed-up", 1.0, 1.5, 1.25, 0.05),
                slider("flicker", "Flicker", 0.0, 1.0, 0.5, 0.01),
                slider("weave", "Gate weave", 0.0, 1.0, 0.4, 0.01),
                toggle("mute", "Mute audio", false),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.Halation, "Halation",
            "Film halation spreads warm light around bright highlights; diffusion and pre-exposure can soften contrast.",
            EffectFamily.Texture, EffectMode.Both,
            listOf(
                slider("glow", "Glow", 0.0, 1.0, 0.4, 0.01),
                slider("red", "Halation red", 0.0, 1.0, 0.5, 0.01),
                slider("flash", "Shadow lift", 0.0, 1.0, 0.2, 0.01),
                picker("flashColor", "Flash color", listOf("Neutral", "Warm", "Cool"), "Warm"),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.SixteenMM, "16mm",
            "Lightweight 16mm cameras helped filmmakers work outside studios and became central to documentary production.",
            EffectFamily.Texture, EffectMode.Both,
            listOf(
                slider("grain", "Grain", 0.0, 1.0, 0.5, 0.01),
                picker("grainSize", "Grain size", listOf("Fine", "Medium", "Coarse"), "Medium"),
                slider("softness", "Softness", 0.0, 1.0, 0.3, 0.01),
                slider("weave", "Gate weave", 0.0, 1.0, 0.2, 0.01),
                toggle("frameEdge", "Frame edge", false),
                picker("color", "Color", listOf("Color", "B&W"), "Color"),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.SuperEight, "Super 8",
            "Introduced in 1965, the small-gauge cartridge format made home-movie filmmaking more accessible.",
            EffectFamily.Texture, EffectMode.Both,
            listOf(
                slider("fade", "Fade", 0.0, 1.0, 0.5, 0.01),
                slider("grain", "Grain", 0.0, 1.0, 0.7, 0.01),
                slider("weave", "Weave", 0.0, 1.0, 0.5, 0.01),
                stepper("fps", "Frame rate", listOf(18.0, 24.0), 18.0),
                slider("leaks", "Light leaks", 0.0, 1.0, 0.2, 0.01),
                toggle("border", "Frame border", true),
            ),
        ),
        VideoEffectDescriptor(
            VideoEffectID.ChromaKey, "Chroma Key",
            "Color-difference travelling mattes separate a subject photographed against a blue or green backing.",
            EffectFamily.Key, EffectMode.Both,
            listOf(
                color("screenColor", "Manual screen color", "#00FF00"),
                picker("screenType", "Screen type", listOf("Auto", "Green", "Blue"), "Auto"),
                toggle("autoScreen", "Detect green / blue screen", true),
                slider("strength", "Strength", 0.0, 2.0, 1.0, 0.01),
                slider("balance", "Balance", 0.0, 1.0, 0.5, 0.01),
                slider("blackClip", "Black clip", 0.0, 0.5, 0.05, 0.01),
                slider("whiteClip", "White clip", 0.5, 1.0, 0.9, 0.01),
                slider("softness", "Edge softness", 0.0, 1.0, 0.3, 0.01),
                slider("grow", "Shrink / grow", -5.0, 5.0, 0.0, 0.1),
                slider("despill", "Despill", 0.0, 1.0, 0.8, 0.01),
                slider("wrap", "Light wrap", 0.0, 1.0, 0.25, 0.01),
                toggle("personHint", "Use person hint", false),
                media("cleanPlate", "Clean plate", image = true, video = false),
                picker("view", "View", listOf("Result", "Matte", "Original"), "Result"),
            ) + background("Solid"),
        ),
        VideoEffectDescriptor(
            VideoEffectID.AutoBackground, "Auto Background",
            "The travelling-matte tradition continues with a subject mask made by the camera instead of a colored screen.",
            EffectFamily.Key, EffectMode.Both,
            listOf(
                slider("softness", "Edge softness", 0.0, 1.0, 0.3, 0.01),
                slider("grow", "Shrink / grow", -5.0, 5.0, 0.0, 0.1),
                slider("wrap", "Light wrap", 0.0, 1.0, 0.25, 0.01),
                slider("portraitBlur", "Portrait blur", 0.0, 40.0, 12.0, 1.0),
                picker("view", "View", listOf("Result", "Matte", "Original"), "Result"),
            ) + background("Blur original"),
        ),
    )

    private val byID: Map<VideoEffectID, VideoEffectDescriptor> = all.associateBy { it.id }

    fun descriptor(id: VideoEffectID): VideoEffectDescriptor = byID.getValue(id)
}

/**
 * Browsing follows creative intent, independently of the fixed render
 * pipeline. These groups never change [EffectFamily], shader order or saved
 * effect IDs. Catalog:35-61.
 */
enum class VideoEffectLibrarySection(val rawValue: String, val title: String, val summary: String, val kinds: List<VideoEffectID>) {
    ColorStyle(
        "colorStyle", "Color & style", "Color, contrast, grain and glow",
        listOf(
            VideoEffectID.NightFix, VideoEffectID.ColorCorrection, VideoEffectID.RgbOffset, VideoEffectID.SilentTint,
            VideoEffectID.ThreeStrip, VideoEffectID.BleachBypass, VideoEffectID.DayForNight, VideoEffectID.ColorIsolation,
            VideoEffectID.Noir, VideoEffectID.Expressionism, VideoEffectID.Quantization, VideoEffectID.Halation,
            VideoEffectID.SixteenMM, VideoEffectID.SuperEight,
        ),
    ),
    CameraFrame(
        "cameraFrame", "Camera & frame", "Zoom, mirror patterns and widescreen framing",
        listOf(VideoEffectID.DollyZoom, VideoEffectID.CrashZoom, VideoEffectID.Kaleidoscope, VideoEffectID.Widescreen),
    ),
    Time(
        "time", "Time", "Freeze, repeat or speed up motion",
        listOf(VideoEffectID.NegativeStrobe, VideoEffectID.FreezeFrame, VideoEffectID.StepPrinting, VideoEffectID.Undercranking),
    ),
    Background(
        "background", "Background", "Replace or blur what is behind you",
        listOf(VideoEffectID.AutoBackground, VideoEffectID.ChromaKey),
    );

    /** The section's effects in the order the picker lists them; while recording ([live]) without the editor-only ones. */
    fun effects(live: Boolean): List<VideoEffectDescriptor> = kinds.map { it.descriptor }.filter { !live || it.mode != EffectMode.Edit }
}

/** The section of the picker an effect is found under. Catalog:63. */
val VideoEffectID.librarySection: VideoEffectLibrarySection
    get() = VideoEffectLibrarySection.entries.first { this in it.kinds }

/** The film history behind an effect, for the effects that have one to tell. Catalog:4-6. */
val VideoEffectID.historicalHelp: String?
    get() = when (this) {
        VideoEffectID.ColorCorrection, VideoEffectID.NightFix, VideoEffectID.AutoBackground, VideoEffectID.FreezeFrame -> null
        else -> descriptor.historyNote
    }

/** One line under an effect's name in the picker. Catalog:64-89. */
val VideoEffectID.summary: String
    get() = when (this) {
        VideoEffectID.RgbOffset -> "Slide the red, green and blue images out of alignment."
        VideoEffectID.NegativeStrobe -> "Flash between the original picture and its color negative."
        VideoEffectID.NightFix -> "Lift dark footage and clean noise. Choose fast cleanup or motion-aligned restoration. Editor only."
        VideoEffectID.ColorCorrection -> "Balance exposure, contrast, white balance and color before your look."
        VideoEffectID.SilentTint -> "Tint the picture with a single film color."
        VideoEffectID.ThreeStrip -> "Rich colors, deeper reds and dense blacks."
        VideoEffectID.BleachBypass -> "Muted color with strong, metallic contrast."
        VideoEffectID.DayForNight -> "Turn daylight into a cool, dark night look."
        VideoEffectID.ColorIsolation -> "Keep one color or the person in color; make the rest monochrome."
        VideoEffectID.Noir -> "Black and white with deep shadows and contrast."
        VideoEffectID.Expressionism -> "Bold monochrome contrast with a dark vignette."
        VideoEffectID.Quantization -> "Reduce the picture to a few colors or shades of gray."
        VideoEffectID.DollyZoom -> "Keep the subject steady while the background stretches."
        VideoEffectID.CrashZoom -> "A quick push toward a face or detail."
        VideoEffectID.Kaleidoscope -> "Mirror the picture or repeat a wedge around a movable center."
        VideoEffectID.Widescreen -> "Change the frame ratio and add horizontal light flares."
        VideoEffectID.FreezeFrame -> "Insert a held frame, with optional slow push-in."
        VideoEffectID.StepPrinting -> "Repeat and blend frames for stuttering, smeared motion."
        VideoEffectID.Undercranking -> "Quick, uneven motion with a silent-film cadence."
        VideoEffectID.Halation -> "Soften bright lights with a warm glow."
        VideoEffectID.SixteenMM -> "Film grain, softness and subtle frame movement."
        VideoEffectID.SuperEight -> "Warm, faded home-movie color and coarse grain."
        VideoEffectID.ChromaKey -> "Remove a green or blue screen and choose a new background."
        VideoEffectID.AutoBackground -> "Keep people and blur or replace the background."
    }

/** How to use an effect, as its help says it. Catalog:7-32. */
val VideoEffectID.usageHelp: String
    get() = when (this) {
        VideoEffectID.ColorCorrection -> "Start with Exposure and White balance, then adjust Contrast and Saturation. Use the tonal controls to refine shadows, midtones and highlights. Compare with Original while adjusting; clipped detail that was never recorded cannot be recovered."
        VideoEffectID.NightFix -> "Brighten dark footage and reduce noise after recording. Fast gives a quick result; higher quality modes use more processing time. Increase recovery gradually: strong settings can soften detail or expose color blotches."
        VideoEffectID.SilentTint -> "Choose a color for the whole image. Tint colors the highlights; Tone colors the shadows while keeping whites neutral. Contrast controls the separation of light and dark."
        VideoEffectID.ThreeStrip -> "Use Saturation for rich color, Red depth for denser reds, and Warmth to balance the result. Lower Intensity for a gentler look and check skin tones in the preview."
        VideoEffectID.BleachBypass -> "Strength increases contrast and removes color for a dense, metallic look. Lower it if faces or shadows lose too much detail."
        VideoEffectID.DayForNight -> "Darkness and Blue make daylight footage read as night. Moonlight highlights preserve bright accents. It works best with controlled light; a bright daytime sky can still reveal the illusion."
        VideoEffectID.ColorIsolation -> "Keep one color while the rest becomes black and white, or keep the person in color. Hue range widens the colors retained; Softness blends their edges. Subject mode requires a visible person."
        VideoEffectID.Noir -> "Create a monochrome image with deep shadows. Adjust Contrast and Black crush while watching darker details; add Grain or a slight shadow tint for character."
        VideoEffectID.Expressionism -> "Combine hard monochrome contrast with a dark vignette. Choose the iris shape and reduce Vignette if it hides important content near the edges."
        VideoEffectID.Quantization -> "Reduce the image to a few colors or gray levels. From scene samples the picture; Pop palettes map brightness to fixed colors. Smoothing reduces speckles. Lock palette keeps colors stable, and Re-sample captures a new palette from the current frame."
        VideoEffectID.DollyZoom -> "During recording, tap the subject and move slowly toward or away from it; zoom tries to keep the subject the same size. Stop before reaching the lens limit. In the editor, the effect separates subject and background using imported depth or a person mask."
        VideoEffectID.CrashZoom -> "A quick push toward a face or detail. Adjust Zoom amount, Speed and Hold, then choose whether to return. During recording, tap the subject and trigger the move with the on-screen control or a volume key."
        VideoEffectID.RgbOffset -> "Drag each cross to shift its color layer. The center is the original registration. The outer ring moves a channel by 12% of the picture’s shorter side. Double-tap a pad to center that channel."
        VideoEffectID.NegativeStrobe -> "Alternate the picture with its color negative. One cycle includes a positive and a negative image. Use the whole range or set a shorter burst. Camera preview starts when you tap Try strobe; recording starts a fresh cycle."
        VideoEffectID.Kaleidoscope -> "Two divisions mirror one half of the picture. Higher counts repeat a wedge around the center. Even counts alternate reflections. Odd counts repeat by rotation. Drag the center to move the split or the round handle to rotate it. Drag a detail to pan the source. Twist two fingers to rotate the source around the split center. The angle settles at 0° near neutral. The cyan arc marks the source wedge. The picture reflects at its edges."
        VideoEffectID.Widescreen -> "Choose the frame ratio and bar style, then add horizontal highlight flares if desired. The bars remain clean above other effects. Oval bokeh needs compatible imported depth and is available only in the editor."
        VideoEffectID.FreezeFrame -> "Insert a hold at the playhead. Hold duration adds time to the project; Push-in moves slowly into the still. Silence pauses dialogue until footage resumes. Continue can repeat dialogue when the original video resumes."
        VideoEffectID.StepPrinting -> "Repeat groups of frames for stuttering motion. Step controls the repetition and Smear blends motion within each group. Slow also reduces playback speed."
        VideoEffectID.Undercranking -> "Choose a low frame cadence for uneven motion, then adjust Speed-up, Flicker and Gate weave. Recording previews the cadence; the editor applies the final speed and audio choice."
        VideoEffectID.Halation -> "Glow softens bright areas, Halation red adds warm fringes around highlights, and Flash lifts shadows. Use small amounts to retain contrast."
        VideoEffectID.SixteenMM -> "Choose the grain size and amount, then add softness or gate movement. Grain animates with each frame. The frame edge option adds a rounded film gate."
        VideoEffectID.SuperEight -> "Combine faded warm color, coarse grain and an unsteady frame. Adjust Fade, Grain and Weave independently; Light leaks adds occasional warm light at the edge."
        VideoEffectID.ChromaKey -> "Use a green or blue screen behind the subject. Screen color identifies the color to remove; Background chooses its replacement. Matte view shows what is kept. Despill removes colored fringes. Person hint can remove objects outside a person's silhouette, so turn it off for other subjects."
        VideoEffectID.AutoBackground -> "Keep people and replace or blur what is behind them. With no person visible, the whole frame becomes the background. Edge softness and Shrink/Grow refine the outline; Light wrap blends it with the new background. Hair and fast motion can need careful adjustment."
    }

/** One set of capability/dependency rules for the live and editor inspectors. Catalog:92-139. */
object VideoEffectParameterVisibility {
    private fun hasFile(effect: VideoEffectSettings, id: String, time: Double): Boolean =
        (effect.value(id, time) as? EffectValue.Media)?.file != null

    /**
     * Whether the inspector shows a control of [effect] at a source second.
     * [live] is the recorder's panel; [hasDepth] says the footage carries a
     * depth track, which no Android footage does.
     */
    fun visible(parameter: EffectParameter, effect: VideoEffectSettings, time: Double = 0.0, live: Boolean = false, hasDepth: Boolean = false): Boolean {
        if (effect.kind == VideoEffectID.NegativeStrobe && parameter.id == "burstLength") return effect.toggle("timedBurst", time)
        val id = parameter.id
        if (id == "ovalBokeh") return !live && hasDepth
        if (effect.kind == VideoEffectID.Quantization) {
            if (id == "palette" && effect.toggle("monochrome", time)) return false
            if (id == "faceDetail" && effect.number("colors", time) > 4) return false
        }
        if (effect.kind == VideoEffectID.ChromaKey) {
            val hasPlate = hasFile(effect, "cleanPlate", time)
            if (id in listOf("screenColor", "autoScreen") && hasPlate) return false
            if (id == "screenColor") return !effect.toggle("autoScreen", time)
            if (id == "screenType") return hasPlate || effect.toggle("autoScreen", time)
        }
        if (effect.kind == VideoEffectID.Widescreen && id == "flareColor") return effect.number("flares", time) > 0
        if (effect.kind == VideoEffectID.Halation && id == "flashColor") return effect.number("flash", time) > 0
        if (effect.kind == VideoEffectID.SixteenMM && id == "grainSize") return effect.number("grain", time) > 0
        if (effect.kind == VideoEffectID.Expressionism && id == "iris") return effect.number("vignette", time) > 0
        if (effect.kind == VideoEffectID.ColorIsolation && effect.choice("mode", time) == "The subject" && id in listOf("color", "range")) return false
        val background = effect.choice("background", time)
        if (id == "backgroundColor") return background == "Solid"
        if (id in listOf("media", "fit", "backgroundX", "backgroundY", "backgroundZoom", "backgroundAudio")) {
            if (id == "media") return background in listOf("Image", "Video")
            if (!hasFile(effect, "media", time)) return false
            return if (id == "backgroundAudio") background == "Video" else background in listOf("Image", "Video")
        }
        if (id == "backgroundBlur" && background in listOf("Solid", "Blur original")) {
            return effect.kind == VideoEffectID.ChromaKey && background == "Blur original"
        }
        if (id in listOf("backgroundBlur", "backgroundBrightness", "matchColor", "matchStrength") && background in listOf("Image", "Video")) {
            if (!hasFile(effect, "media", time)) return false
        }
        if (id in listOf("matchColor", "matchStrength") && background == "Blur original") return false
        if (id == "portraitBlur") return background == "Blur original"
        if (id == "matchStrength") return effect.toggle("matchColor", time)
        if (effect.kind == VideoEffectID.CrashZoom) {
            if (id in listOf("targetX", "targetY") && (live || effect.toggle("trackFace", time))) return false
            if (id in listOf("hold", "return") && effect.releaseTime != null) return false
            if (id == "hold" && !effect.toggle("return", time)) return false
        }
        if (live && effect.kind == VideoEffectID.DollyZoom && id in listOf("strength", "targetX", "targetY", "easing")) return false
        return true
    }

    /** The choices a picker offers: "Saved" only once the Quantization has a saved palette. */
    fun choices(parameter: EffectParameter, effect: VideoEffectSettings): List<String> {
        val picker = parameter.kind as? EffectParameterKind.Picker ?: return emptyList()
        return picker.options.filterNot { effect.kind == VideoEffectID.Quantization && parameter.id == "palette" && it == "Saved" && effect.palette == null }
    }
}

/** The hue a Color Isolation keeps, as the person picks it. Catalog:141-157. */
object IsolationHue {
    /** Black, white and gray have no hue to isolate. */
    fun accepts(color: EffectRGB): Boolean {
        val high = maxOf(color.red, color.green, color.blue)
        val low = minOf(color.red, color.green, color.blue)
        return high > 0.001 && (high - low) / high > 0.08
    }

    fun degrees(c: EffectRGB): Double {
        val high = maxOf(c.red, c.green, c.blue)
        val low = minOf(c.red, c.green, c.blue)
        val delta = high - low
        if (!(delta > 0)) return 0.0
        val phase = if (high == c.red) (c.green - c.blue) / delta else if (high == c.green) 2 + (c.blue - c.red) / delta else 4 + (c.red - c.green) / delta
        return (phase * 60 + 360).rem(360.0)
    }

    /** The fully saturated colour of a hue. */
    fun color(degrees: Double): EffectRGB {
        val h = degrees.rem(360.0) / 60
        val x = 1 - abs(h.rem(2.0) - 1)
        return when (h.toInt()) {
            0 -> EffectRGB(1.0, x, 0.0)
            1 -> EffectRGB(x, 1.0, 0.0)
            2 -> EffectRGB(0.0, 1.0, x)
            3 -> EffectRGB(0.0, x, 1.0)
            4 -> EffectRGB(x, 0.0, 1.0)
            else -> EffectRGB(1.0, 0.0, x)
        }
    }

    const val INVALID_SAMPLE = "Choose a colored area. Black, white and gray have no hue to isolate."
}

/** Which screen colours the Chroma Key can remove. Catalog:159-169. */
object ChromaScreen {
    /**
     * Match the keyer's linear-light channel-difference test. A generic RGB
     * picker would also offer neutral/red screens this renderer cannot remove.
     */
    fun accepts(color: EffectRGB): Boolean {
        fun linear(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        return g - maxOf(r, b) > 0.02 || b - maxOf(r, g) > 0.02
    }

    const val EXPLANATION = "Requires a green or blue screen. For an ordinary room or wall, use Auto Background."
    const val INVALID_SAMPLE = "Choose a green or blue area of the screen. For an ordinary background, use Auto Background."
}

/** The headings an inspector groups its controls under; only the two background effects use more than the first. Catalog:171-186. */
enum class VideoEffectParameterSection(val title: String) {
    General("Effect"),
    Screen("Screen"),
    Edges("Edges"),
    CleanPlate("Clean plate"),
    Background("Background");

    companion object {
        /** The headings that have something to show, in order. */
        fun sections(effect: VideoEffectSettings, time: Double = 0.0, live: Boolean = false): List<VideoEffectParameterSection> =
            entries.filter { section ->
                effect.parameters.any { VideoEffectParameterVisibility.visible(it, effect, time, live) && section(it, effect) == section }
            }

        fun section(parameter: EffectParameter, effect: VideoEffectSettings): VideoEffectParameterSection {
            if (effect.kind != VideoEffectID.ChromaKey && effect.kind != VideoEffectID.AutoBackground) return General
            val id = parameter.id
            if (id == "cleanPlate") return CleanPlate
            if (id in listOf("balance", "blackClip", "whiteClip", "softness", "grow", "despill", "wrap")) return Edges
            if (id.startsWith("background") || id in listOf("media", "fit", "portraitBlur", "matchColor", "matchStrength")) return Background
            if (id == "intensity") return General
            return if (effect.kind == VideoEffectID.AutoBackground) General else Screen
        }
    }
}

/**
 * Shared display semantics for editor and recording controls. Temperature and
 * tint are relative corrections, never a claim about the camera's Kelvin
 * value. Catalog:393-423.
 */
object ColorCorrectionControls {
    val light = listOf("exposure", "contrast", "shadows", "highlights")
    val advanced = listOf("blacks", "whites", "pivot")
    val color = listOf("saturation", "vibrance", "temperature", "tint", "hue")

    /** A control's value as its slider shows it. Interpolated near-neutral values never display negative zero. */
    fun display(id: String, value: Double): String {
        val v = if (abs(value) < 0.00001) 0.0 else value
        fun format(pattern: String, x: Double): String = String.format(Locale.ROOT, pattern, x)
        return when (id) {
            "exposure" -> format("%+.2f EV", v)
            "contrast", "saturation", "intensity", "pivot" -> format("%.0f%%", v * 100)
            "hue" -> if (rounded(v) == 0.0) "0°" else format("%+.0f°", v)
            else -> if (rounded(v * 100) == 0.0) "0" else format("%+.0f", v * 100)
        }
    }

    /** Swift's `rounded()`: to the nearest whole number, halves away from zero. */
    private fun rounded(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

    /** What a control does, as its help says it. */
    fun help(id: String): String = when (id) {
        "exposure" -> "Brightens or darkens the whole picture in stops. +1 EV doubles the light. This grades the image; it does not change the camera exposure. Highlights roll off gently. Like the other video effects, this grades HDR after the app’s SDR tone map; it cannot recover HDR headroom discarded by that map."
        "contrast" -> "Separates light and dark tones around the contrast pivot. 100% is unchanged. Color proportions are preserved."
        "shadows" -> "Raises or lowers darker tones, while keeping the black endpoint. Use Blacks to lift or deepen the very darkest areas."
        "highlights" -> "Raises or lowers brighter tones, while keeping the white endpoint. Use Whites to change the brightest areas."
        "blacks" -> "Lifts or deepens the darkest endpoint. Positive values soften black; negative values deepen black with a gentle toe."
        "whites" -> "Raises or lowers the brightest endpoint. Positive values brighten whites with a gentle roll-off."
        "pivot" -> "The tone that stays fixed as contrast changes. 50% is a middle display tone. Has no effect at 100% contrast."
        "temperature" -> "Relative white balance: negative is cooler (blue), positive is warmer (amber). Zero preserves the recorded balance; very bright whites stay white. This is not a Kelvin setting."
        "tint" -> "Relative white balance: negative adds green, positive adds magenta. Useful for correcting a color cast."
        "saturation" -> "Controls all color intensity. 100% is unchanged; 0% is black and white."
        "vibrance" -> "Adjusts muted colors more than already vivid ones. Positive adjustments are gentler on common skin hues."
        "hue" -> "Rotates all colors around the color wheel, preserving luminance. Zero preserves the original hues."
        else -> "Blends this correction with its input. 100% applies the full correction; 0% bypasses it."
    }
}
