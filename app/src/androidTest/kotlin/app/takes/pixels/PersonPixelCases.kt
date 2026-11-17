package app.takes.pixels

import android.os.Build
import androidx.annotation.RequiresApi

import app.takes.EffectAssets
import app.takes.EffectShader
import app.takes.PersonFrame
import app.takes.core.AutoBackgroundFormulas
import app.takes.core.DollyZoomFormulas
import app.takes.core.EffectColorMath
import app.takes.core.EffectFrameInputs
import app.takes.core.EffectImage
import app.takes.core.EffectMask
import app.takes.core.EffectPalette
import app.takes.core.EffectPaletteAnalysis
import app.takes.core.EffectUniforms
import app.takes.core.EffectValue
import app.takes.core.EffectVector
import app.takes.core.MaskRefinement
import app.takes.core.MirrorSettings
import app.takes.core.PersonMaskChunks
import app.takes.core.Point
import app.takes.core.Size
import app.takes.core.VideoEffectFormulas
import app.takes.core.VideoEffectFormulasC
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectQuantization
import app.takes.core.VideoEffectSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The pixel tests of the effects that read the person in the frame: Color Isolation's subject, Quantization's face
 * detail, Chroma Key's person hint and Auto Background, drawn by the export's [app.takes.VideoEffectChain] with a person
 * mask from the assets, against the core's reference formulas given the same mask on the frame.
 *
 * The mask is a synthetic one as [PersonMaskChunks] stores it, 256 × 256 in eight bits: a soft-edged ellipse off the
 * centre. The reference lays it on the frame by a bilinear read at each pixel's centre, worked out here from the
 * rotation and the mirror rather than by [app.takes.core.PersonMaskPlacement], so that the placement is checked too.
 */
class PersonPixelCases(private val bench: Bench, private val width: Int = 480, private val height: Int = 270) {
    /** The person, upright on the frame, 0…1 from the top left: an ellipse off the centre, its edge ramped over a fifth of its radius. */
    private fun person(u: Double, v: Double): Double = ((1.1 - ellipse(u, v, .42, .52, .2, .38)) / .2).coerceIn(0.0, 1.0)

    private fun ellipse(u: Double, v: Double, cu: Double, cv: Double, ru: Double, rv: Double): Double {
        val du = (u - cu) / ru
        val dv = (v - cv) / rv
        return sqrt(du * du + dv * dv)
    }

    /** The mask as stored for a recording turned clockwise by [rotation] (0 or 90) to stand upright: in the decoded frame's coordinates. */
    private fun stored(rotation: Int): PersonMaskChunks.Mask {
        val n = 256
        val rows = ByteArray(n * n) { i ->
            val sx = (i % n + .5) / n
            val sy = (i / n + .5) / n
            // A stored point turned a quarter clockwise: its top left goes to the top right.
            val value = if (rotation == 90) person(1 - sy, sx) else person(sx, sy)
            (value * 255).roundToInt().toByte()
        }
        return PersonMaskChunks.Mask(1.0, n, n, rows)
    }

    /**
     * The stored mask laid on a picture of the whole frame of [w] × [h] as the reference reads it: each pixel's centre
     * taken back through the mirror and the turn, read between the mask's pixels.
     */
    private fun placed(mask: PersonMaskChunks.Mask, rotation: Int, mirrored: Boolean, w: Int = width, h: Int = height): EffectMask {
        val stored = EffectMask.of(mask.width, mask.height) { x, y -> mask.alpha(x, y).toDouble() }
        return EffectMask.of(w, h) { x, y ->
            val u = ((x + .5) / w).let { if (mirrored) 1 - it else it }
            val v = (y + .5) / h
            stored.sample(if (rotation == 90) Point(v, 1 - u) else Point(u, v))
        }
    }

    private val skin = rgb(214, 158, 124)

    /**
     * A person before a background: the background a ramp crossed by bars every 48 pixels; the person an ellipse a
     * little inside the mask's half contour, skin with stripes of a red and a blue shirt every 18 pixels.
     */
    private val portrait = Picture(width, height) { x, y ->
        val u = (x + .5) / width
        val v = (y + .5) / height
        if (ellipse(u, v, .425, .52, .195, .37) < 1) {
            when ((y / 18) % 4) {
                1 -> rgb(196, 40, 52)
                3 -> rgb(40, 70, 170)
                else -> skin
            }
        } else {
            val bar = (x / 8) % 6 == 0
            if (bar) rgb(235, 225, 90) else rgb((60 + 120 * u).roundToInt(), (90 + 70 * v).roundToInt(), (150 - 60 * u).roundToInt())
        }
    }

    /**
     * The same person before a green screen, a little darker at the bottom, with a grey card beside them that the key
     * keeps and the grown person does not reach, and a stripe of a green shirt the key half takes away.
     */
    private val screened = Picture(width, height) { x, y ->
        val u = (x + .5) / width
        val v = (y + .5) / height
        val shade = 1 - .25 * v
        when {
            ellipse(u, v, .425, .52, .195, .37) < 1 -> when ((y / 18) % 4) {
                1 -> rgb(196, 40, 52)
                3 -> rgb(80, 150, 90)
                else -> skin
            }
            u in .75..0.9 && v in .15..0.35 -> rgb(128, 128, 128)
            else -> rgb((40 * shade).roundToInt(), (200 * shade).roundToInt(), (60 * shade).roundToInt())
        }
    }

    private fun linear(picture: Picture): EffectImage = EffectImage.of(width, height) { x, y ->
        EffectVector(EffectColorMath.linear(picture.channel(x, y, 0) / 255.0), EffectColorMath.linear(picture.channel(x, y, 1) / 255.0), EffectColorMath.linear(picture.channel(x, y, 2) / 255.0))
    }

    /** What the chain reads: the person mask of the frame, and a Quantization's palette analysed from [source] as [EffectPixelCases] does. */
    private class Assets(private val frame: PersonFrame?, private val source: () -> EffectImage) : EffectAssets {
        override fun person(sourceTime: Double, smoothed: Boolean): PersonFrame? = frame
        override fun analysed(effect: VideoEffectSettings, sourceTime: Double): EffectPalette =
            EffectPaletteAnalysis.analyze(EffectPaletteAnalysis.samples(source()), effect.number("colors", sourceTime).toInt(), seed = 7uL)
    }

    /** The person mask of a frame as the references take it: laid on the frame. Chroma Key's hint grows and shrinks it from there to a quarter of the frame ([VideoEffectFormulasC.personHint]). */
    private class Person(val frame: EffectMask)

    /** One pass by the core, the frame given the person mask [person] (or none). */
    private fun reference(u: EffectUniforms, image: EffectImage, person: Person?): EffectImage = when (u.effect) {
        VideoEffectID.ColorIsolation -> if (person == null) image else EffectImage.of(VideoEffectFormulas.pass(u, image.toPicture(), person = person.frame))
        VideoEffectID.Quantization -> VideoEffectQuantization.pass(u, image, person = person?.frame)
        VideoEffectID.ChromaKey -> VideoEffectFormulasC.chromaKeyPass(u, image, AutoBackgroundFormulas.background(u, image), person = person?.frame)
        // Without a person mask the pass leaves the picture as it is.
        VideoEffectID.AutoBackground -> if (person == null) image else AutoBackgroundFormulas.pass(u, image, person.frame, AutoBackgroundFormulas.background(u, image))
        VideoEffectID.DollyZoom -> DollyZoomFormulas.pass(u, image, person?.frame)
        else -> error("No reference here for ${u.effect}")
    }

    /** What the core's formulas make of [source] under [effects] at second [time] with the frame's [person] mask, to eight bits. */
    private fun expected(effects: List<VideoEffectSettings>, time: Double, source: Picture, assets: EffectAssets, person: Person?): Picture {
        var image = linear(source)
        val inputs = EffectAssets.inputs(assets, effects, time).toMutableMap()
        for (effect in effects) inputs[effect.id] = (inputs[effect.id] ?: EffectFrameInputs()).copy(personMask = person != null)
        for (pass in EffectShader.passes(effects, time, EffectStage.RATE, inputs)) image = reference(pass, image, person)
        return Picture(width, height) { x, y ->
            val c = image[x, y]
            fun level(v: Double) = (EffectColorMath.encoded(v).coerceIn(0.0, 1.0) * 255).roundToInt()
            rgb(level(c.x), level(c.y), level(c.z))
        }
    }

    private fun person(mask: PersonMaskChunks.Mask, rotation: Int, mirrored: Boolean) =
        Person(placed(mask, rotation, mirrored))

    /**
     * The pixels of the frame where Chroma Key's person hint is at one of its thresholds (Metal:474-479): its grown
     * mask within [HINT_BAND] of 0.005, or its shrunk mask within it of 0.995, under the pixel or a neighbour (the
     * matte's softness spreads a pixel a little). There single precision, GL's filtering and a half-float picture
     * may fall on the other side of the threshold than double precision does, and the matte jumps by up to the key's
     * whole alpha.
     */
    private fun atHintThreshold(person: EffectMask): BooleanArray {
        val hint = VideoEffectFormulasC.personHint(person, width, height)
        val near = BooleanArray(width * height) { i ->
            val uv = Point((i % width + .5) / width, (i / width + .5) / height)
            kotlin.math.abs(hint.dilated.sample(uv) - .005) < HINT_BAND || kotlin.math.abs(hint.eroded.sample(uv) - .995) < HINT_BAND
        }
        return BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (-1..1).any { dy -> (-1..1).any { dx -> near[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)] } }
        }
    }

    private fun distance(a: Int, b: Int): Int = (0..2).maxOf { kotlin.math.abs(((a shr (16 - 8 * it)) and 0xff) - ((b shr (16 - 8 * it)) and 0xff)) }

    /**
     * [effects] drawn at second [time] on [source] with the person mask stored for a recording turned by [rotation]
     * and framed with a horizontal mirror when [mirrored], against the core given the same mask on the frame. At most
     * [OUTLIERS] pixels may be more than [allowed] levels away, by up to [OUTLIER_LEVELS]; with Chroma Key's hint, the
     * pixels at its thresholds besides, up to [HINT_FLIPS] of the picture. The reference without the person (or with
     * it laid upright and unmirrored, when the case turns or mirrors it) must differ from it on [sensitive] of the
     * picture at least, so that the case tests what it says.
     */
    private fun case(
        name: String, effects: List<VideoEffectSettings>, allowed: Int, source: Picture = portrait, rotation: Int = 0, mirrored: Boolean = false,
        time: Double = 1.0, sensitive: Double = .01,
    ) = Case(name) {
        val mask = stored(rotation)
        val linear by lazy { linear(source) }
        val assets = Assets(PersonFrame(mask, rotation, Size(width.toDouble(), height.toDouble())), { linear })
        val stage = EffectStage(bench, effects, width, height, assets, mirror = if (mirrored) MirrorSettings(horizontal = true) else null)
        try {
            val drawn = stage.frame(source, (time * 1e6).roundToLong())
            val person = person(mask, rotation, mirrored)
            val want = expected(effects, time, source, assets, person)
            val turned = rotation != 0 || mirrored
            val control = expected(effects, time, source, assets, if (turned) person(stored(0), 0, false) else null)
            val changed = BooleanArray(want.pixels.size) { distance(want.pixels[it], control.pixels[it]) > allowed }
            val reach = changed.count { it }
            note("the person changes ${"%.1f".format(100.0 * reach / want.pixels.size)} % of the picture by more than $allowed levels" +
                if (turned) " against the mask laid upright and unmirrored" else "")
            check(reach >= sensitive * want.pixels.size) { "The person changes too little of the picture: the case tests nothing" }
            val keyed = effects.any { it.kind == VideoEffectID.ChromaKey }
            val threshold = if (keyed) atHintThreshold(person.frame) else BooleanArray(want.pixels.size)
            var worst = 0
            var worstAt = 0
            var over = 0
            var flips = 0
            var flipWorst = 0
            val histogram = IntArray(5)
            for (i in want.pixels.indices) {
                val d = distance(drawn.pixels[i], want.pixels[i])
                histogram[when { d <= 1 -> 0; d <= 2 -> 1; d <= 4 -> 2; d <= 8 -> 3; else -> 4 }]++
                if (d > allowed && threshold[i]) {
                    flips++
                    flipWorst = maxOf(flipWorst, d)
                    continue
                }
                if (d > worst) { worst = d; worstAt = i }
                if (d > allowed) over++
            }
            note("${want.pixels.size} pixels, worst $worst levels at (${worstAt % width}, ${worstAt / width}): drawn #%06x, formula #%06x, from #%06x, mask %.3f; %d over %d"
                .format(drawn.pixels[worstAt], want.pixels[worstAt], source.pixels[worstAt], person.frame.data[worstAt], over, allowed))
            note("levels off: ≤1 ${histogram[0]}, 2 ${histogram[1]}, 3–4 ${histogram[2]}, 5–8 ${histogram[3]}, >8 ${histogram[4]}")
            if (keyed) {
                note("at the hint's thresholds, ${threshold.count { it }} pixels: $flips over $allowed, by up to $flipWorst")
                check(flips <= HINT_FLIPS * want.pixels.size) { "$flips pixels at the hint's thresholds more than $allowed levels from the core's formula" }
            }
            if (effects.any { it.kind == VideoEffectID.Quantization }) {
                // As EffectPixelCases: a pixel on a boundary between two bins goes to the other side in single precision,
                // a whole palette step away; up to 1 % of the picture, each a colour the formula gives elsewhere.
                val palette = want.pixels.toSet()
                val stray = want.pixels.indices.count { i -> distance(drawn.pixels[i], want.pixels[i]) > allowed && palette.none { distance(it, drawn.pixels[i]) <= allowed } }
                // Face detail changes few pixels, fewer than that 1 %: those it changes are held on their own.
                val missed = want.pixels.indices.count { changed[it] && distance(drawn.pixels[it], want.pixels[it]) > allowed }
                note("quantizer: $over pixels on another side of a boundary, $stray of them no colour of the formula's; of the $reach the face detail changes, $missed")
                check(over <= want.pixels.size / 100 && stray == 0) { "$over pixels more than $allowed levels from the core's formula, $stray of them no colour it gives" }
                check(missed <= reach / 20) { "$missed of the $reach pixels the face detail changes are more than $allowed levels from the core's formula" }
                return@Case
            }
            check(over <= OUTLIERS && worst <= maxOf(allowed, OUTLIER_LEVELS)) {
                "$over pixels more than $allowed levels from the core's formula (at most $OUTLIERS may be, by up to $OUTLIER_LEVELS), the worst $worst"
            }
        } finally {
            stage.release()
        }
    }

    private fun settings(kind: VideoEffectID, values: Map<String, Any>): VideoEffectSettings {
        var effect = VideoEffectSettings(kind)
        // Color Isolation and Chroma Key with the single-frame matte, algorithm 1, as older recipes keep it; TemporalMattePixelCases draws algorithm 2.
        if (kind == VideoEffectID.ColorIsolation || kind == VideoEffectID.ChromaKey) effect = effect.copy(algorithm = 1)
        for ((id, value) in values) effect = effect.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            is app.takes.core.EffectRGB -> EffectValue.Color(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        return effect
    }

    private fun auto(values: Map<String, Any>) = listOf(settings(VideoEffectID.AutoBackground, values))

    fun all(): List<Case> = listOf(
        case("Person: Color Isolation, the subject", listOf(settings(VideoEffectID.ColorIsolation, mapOf("mode" to "The subject"))), ALLOWED),
        case("Person: Color Isolation, the subject, softness 1", listOf(settings(VideoEffectID.ColorIsolation, mapOf("mode" to "The subject", "softness" to 1.0))), ALLOWED),
        case("Person: Quantization, four colours of Pop 1, face detail", listOf(settings(VideoEffectID.Quantization, mapOf("colors" to 4.0, "palette" to "Pop 1", "smoothing" to 0.0))), ALLOWED,
            sensitive = .001),
        case("Person: Chroma Key with the person hint", listOf(settings(VideoEffectID.ChromaKey, mapOf("personHint" to true))), ALLOWED, source = screened),
        // The screen set by hand, as EffectPixelCases' matte: the screen found on the border leaves a little alpha on the
        // darker screen below, which the matte shows sRGB-encoded, where a thousandth of alpha is a level or more.
        case("Person: Chroma Key with the person hint, the matte", listOf(settings(VideoEffectID.ChromaKey, mapOf(
            "personHint" to true, "view" to "Matte", "softness" to 0.0, "autoScreen" to false, "screenColor" to app.takes.core.EffectRGB.hex("#28C83C"),
        ))), ALLOWED, source = screened),
        case("Person: Auto Background, solid", auto(mapOf("background" to "Solid")), ALLOWED),
        case("Person: Auto Background, blur original 12", auto(emptyMap()), ALLOWED),
        case("Person: Auto Background, blur original 0", auto(mapOf("portraitBlur" to 0.0)), ALLOWED),
        case("Person: Auto Background, Studio, blurred, brighter", auto(mapOf("background" to "Studio", "backgroundBlur" to 4.0, "backgroundBrightness" to 0.3)), ALLOWED),
        case("Person: Auto Background, Window, grown, soft", auto(mapOf("background" to "Window", "grow" to 3.0, "softness" to 0.8, "wrap" to 1.0)), ALLOWED),
        case("Person: Auto Background, the matte", auto(mapOf("view" to "Matte")), ALLOWED),
        // Match colour, as a Chroma Key's: the subject brought toward the new background's light. Metal:409-413, Renderer:536-541.
        case("Person: Auto Background, match colour to a warm solid", auto(mapOf(
            "background" to "Solid", "backgroundColor" to app.takes.core.EffectRGB.hex("#D2783C"), "matchColor" to true, "matchStrength" to 0.8,
        )), ALLOWED),
        case("Person: Auto Background, match colour to Studio, darker", auto(mapOf("background" to "Studio", "backgroundBrightness" to -0.4, "matchColor" to true)), ALLOWED),
        case("Person: Auto Background, the matte, turned 90° and mirrored", auto(mapOf("view" to "Matte")), ALLOWED, rotation = 90, mirrored = true),
        // Dolly Zoom: the person kept, the background with its fill magnified about the subject (Metal:366-369).
        case("Person: Dolly Zoom, Stretch half way", listOf(settings(VideoEffectID.DollyZoom, emptyMap())), ALLOWED, time = 1.25),
        case("Person: Dolly Zoom, Stretch at the end, strength 0.5", listOf(settings(VideoEffectID.DollyZoom, mapOf("strength" to .5))), ALLOWED, time = 3.0),
        case("Person: Dolly Zoom, Compress, linear, about the subject", listOf(settings(VideoEffectID.DollyZoom, mapOf(
            "direction" to "Compress", "easing" to "Linear", "duration" to 4.0, "targetX" to .42, "targetY" to .4,
        ))), ALLOWED, time = 1.0),
        case("Person: Dolly Zoom, turned 90° and mirrored", listOf(settings(VideoEffectID.DollyZoom, mapOf("strength" to .4))), ALLOWED, rotation = 90, mirrored = true, time = 2.0),
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) previews() else emptyList()

    /**
     * The preview's side (Android 13 and later), simplified as the editor says: the person's mask as it is, without
     * the guided filter. Auto Background's matte must be that mask laid on the deck, and each effect must draw the
     * person apart from the rest: its shaders compile and read the mask where it lies.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun previews(): List<Case> {
        fun drawn(effects: List<VideoEffectSettings>, source: Picture = portrait, rotation: Int = 0, time: Double = 1.0): Pair<Picture, EffectMask> {
            val mask = stored(rotation)
            val linear by lazy { linear(source) }
            val assets = Assets(PersonFrame(mask, rotation, Size(width.toDouble(), height.toDouble())), { linear })
            val inputs = EffectAssets.inputs(assets, effects, time)
            val passes = EffectShader.passes(effects, time, EffectStage.RATE, inputs)
            return PreviewStage.frame(source, passes, person = assets.person(time, true)) to person(mask, rotation, false).frame
        }
        fun inside(person: EffectMask, i: Int) = person.data[i] > .99
        fun outside(person: EffectMask, i: Int) = person.data[i] < .01
        return listOf(
            // The matte against the core's refinedMask of the same mask on the same picture (Renderer:382-402), compared
            // as alpha, which is what the composite takes (the matte shows it sRGB-encoded, where a hundredth of alpha
            // near none is twenty levels): the preview works the guided filter out at the pixel from nine reads without
            // blurring its coefficients, so a little apart on the edge, and far nearer it than the mask as it is.
            Case("Person preview: Auto Background, the matte is the refined mask") {
                val settings = listOf(
                    0 to mapOf<String, Any>(), 90 to mapOf<String, Any>(), 0 to mapOf<String, Any>("softness" to 0.9),
                    0 to mapOf<String, Any>("grow" to 3.0), 0 to mapOf<String, Any>("softness" to 0.0, "grow" to -3.0),
                )
                for ((rotation, values) in settings) {
                    val (picture, person) = drawn(auto(mapOf<String, Any>("view" to "Matte") + values), rotation = rotation)
                    val refined = MaskRefinement.refinedMask(person, linear(portrait), values["softness"] as Double? ?: 0.3, values["grow"] as Double? ?: 0.0, person = true)
                    var worst = 0.0
                    var over = 0
                    var nearer = 0.0
                    var raw = 0.0
                    for (i in picture.pixels.indices) {
                        val alpha = EffectColorMath.linear((picture.pixels[i] and 0xff) / 255.0)
                        val d = kotlin.math.abs(alpha - refined.data[i])
                        worst = maxOf(worst, d)
                        if (d > PREVIEW_MATTE) over++
                        nearer += d
                        raw += kotlin.math.abs(alpha - person.data[i])
                    }
                    note("turned $rotation, $values: worst %.4f of alpha, %d pixels over %.2f; summed distance %.1f to the refined mask, %.1f to the mask as it is"
                        .format(worst, over, PREVIEW_MATTE, nearer, raw))
                    // A grow of under a pixel on an edge the guided filter has made sharp: the preview reads the refined
                    // mask at the pixel the grow reaches, where the export reads between two.
                    val grown = values.containsKey("grow")
                    check(over <= picture.pixels.size / (if (grown) 25 else 200) && worst <= (if (grown) 8 else 3) * PREVIEW_MATTE) {
                        "The preview's matte is not the refined mask: $over pixels over, the worst $worst"
                    }
                    check(nearer * 5 < raw) { "The preview's matte is not nearer the refined mask than the mask as it is" }
                }
            },
            // Chroma Key's person hint in the preview: the key cleared away from the person, as the export's matte is.
            Case("Person preview: Chroma Key's person hint clears the key away from the person") {
                fun matte(hint: Boolean) = drawn(listOf(settings(VideoEffectID.ChromaKey, mapOf(
                    "personHint" to hint, "view" to "Matte", "softness" to 0.0, "autoScreen" to false, "screenColor" to app.takes.core.EffectRGB.hex("#28C83C"),
                ))), source = screened)
                val (on, person) = matte(true)
                val (off, _) = matte(false)
                val hint = VideoEffectFormulasC.personHint(person, width, height)
                var card = 0
                var cleared = 0
                var kept = 0
                var inside = 0
                for (i in on.pixels.indices) {
                    val uv = Point((i % width + .5) / width, (i / width + .5) / height)
                    val offLevel = off.pixels[i] and 0xff
                    // Away from the grown person, where the key alone keeps the grey card.
                    if (hint.dilated.sample(uv) < .001 && offLevel > 200) {
                        card++
                        if ((on.pixels[i] and 0xff) < 8) cleared++
                    }
                    if (hint.eroded.sample(uv) > .999) {
                        inside++
                        if ((on.pixels[i] and 0xff) >= (off.pixels[i] and 0xff) - ALLOWED) kept++
                    }
                }
                note("$card pixels the key keeps away from the person, $cleared cleared; $inside inside the shrunk person, $kept kept at least as the key keeps them")
                check(card > 100 && cleared >= card * 98 / 100) { "The preview's person hint leaves the key away from the person" }
                check(kept >= inside * 98 / 100) { "The preview's person hint takes from the person" }
            },
            Case("Person preview: each effect draws the person apart") {
                val looks = listOf(
                    "Color Isolation, the subject" to listOf(settings(VideoEffectID.ColorIsolation, mapOf("mode" to "The subject"))),
                    "Quantization, face detail" to listOf(settings(VideoEffectID.Quantization, mapOf("colors" to 4.0, "palette" to "Pop 1", "smoothing" to 0.0))),
                    "Auto Background, solid" to auto(mapOf("background" to "Solid")),
                    "Auto Background, blur original" to auto(emptyMap()),
                    "Auto Background, Studio" to auto(mapOf("background" to "Studio")),
                    "Auto Background, Window" to auto(mapOf("background" to "Window")),
                )
                for ((name, effects) in looks) {
                    val (picture, person) = drawn(effects)
                    val kept = picture.pixels.indices.filter { inside(person, it) }
                    val changedInside = kept.count { distance(picture.pixels[it], portrait.pixels[it]) > ALLOWED }
                    val away = picture.pixels.indices.filter { outside(person, it) }
                    val changedOutside = away.count { distance(picture.pixels[it], portrait.pixels[it]) > ALLOWED }
                    note("$name: ${kept.size} pixels of the person, $changedInside changed; ${away.size} of the rest, $changedOutside changed")
                    when {
                        // The person keeps its colour; the rest goes grey, or behind the new background.
                        name.startsWith("Color Isolation") || name.startsWith("Auto Background") -> {
                            check(changedInside <= kept.size / 50) { "$name changes the person" }
                            // Blurring this picture's smooth background changes it only near its bars and the card.
                            val least = if (name.endsWith("blur original")) away.size / 5 else away.size / 2
                            check(changedOutside >= least) { "$name leaves the rest as it was" }
                        }
                        // Face detail sharpens the person only; the palette changes the rest alike, so compare with face detail off.
                        else -> {
                            val off = drawn(listOf(settings(VideoEffectID.Quantization, mapOf("colors" to 4.0, "palette" to "Pop 1", "smoothing" to 0.0, "faceDetail" to false)))).first
                            val detailInside = kept.count { distance(picture.pixels[it], off.pixels[it]) > ALLOWED }
                            val detailOutside = away.count { distance(picture.pixels[it], off.pixels[it]) > ALLOWED }
                            note("$name against face detail off: $detailInside of the person changed, $detailOutside of the rest")
                            // The export's own difference on the same picture, for scale.
                            val mask = stored(0)
                            val assets = Assets(PersonFrame(mask, 0, Size(width.toDouble(), height.toDouble())), { linear(portrait) })
                            fun export(detail: Boolean): Picture {
                                val stage = EffectStage(bench, listOf(settings(VideoEffectID.Quantization, mapOf("colors" to 4.0, "palette" to "Pop 1", "smoothing" to 0.0, "faceDetail" to detail))), width, height, assets)
                                try { return stage.frame(portrait, 1_000_000) } finally { stage.release() }
                            }
                            val on = export(true)
                            val offExport = export(false)
                            val exported = kept.count { distance(on.pixels[it], offExport.pixels[it]) > ALLOWED }
                            note("the export against face detail off: $exported of the person changed")
                            // On this picture the sharpening crosses few palette boundaries; the preview, simplified, as many as the export or so.
                            check(kotlin.math.abs(detailInside - exported) <= maxOf(50, exported / 2)) { "$name sharpens the person unlike the export: $detailInside against $exported" }
                            check(detailOutside == 0) { "$name changes the rest" }
                        }
                    }
                }
            },
            // The preview's Dolly Zoom, simplified: the person as they are, and where the point the background is read at
            // is clear of the person, the picture there, magnified about the subject as the core's formula reads it.
            Case("Person preview: Dolly Zoom keeps the person and magnifies the rest") {
                val effects = listOf(settings(VideoEffectID.DollyZoom, mapOf("strength" to .5, "easing" to "Linear", "targetX" to .42, "targetY" to .52)))
                val (start, person) = drawn(effects, time = 0.0)
                // Before the move, the background where the person's mask is under a half is read where it is.
                val moved = start.pixels.indices.count { person.data[it] < .5 && distance(start.pixels[it], portrait.pixels[it]) > ALLOWED }
                note("at the start, $moved pixels of the background changed")
                check(moved <= OUTLIERS) { "At the start the preview's Dolly Zoom changes the background" }
                val (picture, _) = drawn(effects, time = 2.0)
                val scale = 1.4
                val target = Point(.42, .52)
                val source = linear(portrait)
                val kept = picture.pixels.indices.filter { inside(person, it) }
                val changedInside = kept.count { distance(picture.pixels[it], portrait.pixels[it]) > ALLOWED }
                var compared = 0
                var over = 0
                var worst = 0
                for (i in picture.pixels.indices) {
                    if (person.data[i] > .001) continue
                    val at = DollyZoomFormulas.backgroundPoint(source.uv(i % width, i / width), target, scale)
                    if (person.sample(at) > .001) continue
                    val c = source.sample(at)
                    fun level(v: Double) = (EffectColorMath.encoded(v).coerceIn(0.0, 1.0) * 255).roundToInt()
                    val d = distance(picture.pixels[i], rgb(level(c.x), level(c.y), level(c.z)))
                    compared++
                    worst = maxOf(worst, d)
                    if (d > ALLOWED) over++
                }
                val changedOutside = picture.pixels.indices.count { outside(person, it) && distance(picture.pixels[it], portrait.pixels[it]) > ALLOWED }
                note("${kept.size} pixels of the person, $changedInside changed; of $compared read clear of the person, $over over $ALLOWED, the worst $worst; $changedOutside of the rest changed")
                check(changedInside <= kept.size / 50) { "The preview's Dolly Zoom changes the person" }
                check(compared >= picture.pixels.size / 3 && over <= OUTLIERS && worst <= OUTLIER_LEVELS) { "The preview's Dolly Zoom does not read the background magnified about the subject" }
                check(changedOutside >= compared / 5) { "The preview's Dolly Zoom leaves the rest as it was" }
            },
        )
    }

    companion object {
        /** Eight bits in, a single-precision shader, eight bits out: a level of rounding each way. */
        const val ALLOWED = 2

        /** As EffectPixelCases: a few pixels on a hard edge may come out further, by up to [OUTLIER_LEVELS]. */
        const val OUTLIERS = 64
        const val OUTLIER_LEVELS = 8

        /** How far the preview's matte may be from the core's refined mask, as alpha. */
        const val PREVIEW_MATTE = .02

        /** How near a threshold of the hint a value is taken to be at it: a few steps of a half float at 0.995 (2⁻¹¹). */
        const val HINT_BAND = .002

        /** The part of the picture that may be off at the hint's thresholds. */
        const val HINT_FLIPS = .002
    }
}
