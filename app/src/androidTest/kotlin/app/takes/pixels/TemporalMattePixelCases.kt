package app.takes.pixels

import app.takes.EffectShader
import app.takes.core.AutoBackgroundFormulas
import app.takes.core.EffectColorMath
import app.takes.core.EffectImage
import app.takes.core.EffectRGB
import app.takes.core.EffectUniforms
import app.takes.core.EffectValue
import app.takes.core.EffectVector
import app.takes.core.MaskPlane
import app.takes.core.MaskStorage
import app.takes.core.VideoEffectFormulas
import app.takes.core.VideoEffectFormulasC
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectMatteKind
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTemporalMatte
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The pixel tests of the temporal matte, algorithm 2 of Color Isolation and Chroma Key: a run of frames drawn one
 * after another by one export chain, which keeps the frames before in its window ([app.takes.EffectMatteWindow]),
 * against the core given the same frames: each frame's raw matte, its guide, the history in eight bits and the
 * window ([VideoEffectTemporalMatte.temporalMatte]), then the pass with the window's matte.
 *
 * The frames move: a disc crosses the picture a few pixels a frame, so the window's matte trails it, and one frame
 * is a new scene, which the window must not blend with the frames before. Behind the key, the screen changes its
 * colour every other frame but hardly its luma.
 */
class TemporalMattePixelCases(private val bench: Bench, private val width: Int = 320, private val height: Int = 180) {
    private val red = rgb(192, 57, 43)
    private val screen = rgb(40, 200, 60)

    /** Frame [i] of the run: a disc of [disc] moving right over [ground], and from frame [cut] on another scene, darker and with the disc lower. */
    private fun frame(i: Int, ground: (Int, Int, Int) -> Int, disc: Int, cut: Int): Picture = Picture(width, height) { x, y ->
        val later = i >= cut
        val cx = 60 + 9 * i
        val cy = if (later) 120 else 80
        val inside = (x - cx) * (x - cx) + (y - cy) * (y - cy) < 34 * 34
        val color = if (inside) disc else ground(i, x, y)
        if (later && !inside) rgb(((color shr 16) and 0xff) / 3, ((color shr 8) and 0xff) / 3, (color and 0xff) / 3) else color
    }

    private fun linear(picture: Picture): EffectImage = EffectImage.of(width, height) { x, y ->
        EffectVector(EffectColorMath.linear(picture.channel(x, y, 0) / 255.0), EffectColorMath.linear(picture.channel(x, y, 1) / 255.0), EffectColorMath.linear(picture.channel(x, y, 2) / 255.0))
    }

    private fun encoded(image: EffectImage): Picture = Picture(width, height) { x, y ->
        val c = image[x, y]
        fun level(v: Double) = (EffectColorMath.encoded(v).coerceIn(0.0, 1.0) * 255).roundToInt()
        rgb(level(c.x), level(c.y), level(c.z))
    }

    private fun distance(a: Int, b: Int): Int = (0..2).maxOf { kotlin.math.abs(((a shr (16 - 8 * it)) and 0xff) - ((b shr (16 - 8 * it)) and 0xff)) }

    /** The raw matte of a frame as the chain makes it, in half floats: the weight by hue, or the key refined. */
    private fun raw(u: EffectUniforms, image: EffectImage): MaskPlane = when (u.effect) {
        VideoEffectID.ColorIsolation -> MaskPlane.of(width, height) { x, y ->
            MaskStorage.half(VideoEffectFormulas.colorIsolationWeight(image[x, y], u.p[1].xyz, u.p[2].x, u.p[3].x).toFloat())
        }
        else -> {
            val matte = VideoEffectFormulasC.matte(image, { u.vector("screenColor").xyz }, u)
            MaskPlane.of(width, height) { x, y -> MaskStorage.half(matte[x, y].x.toFloat()) }
        }
    }

    private fun drawn(u: EffectUniforms, image: EffectImage, window: MaskPlane): EffectImage = when (u.effect) {
        VideoEffectID.ColorIsolation ->
            EffectImage.of(VideoEffectFormulas.pass(u, image.toPicture(), mask = { uv -> window.sample(uv.x.toFloat(), uv.y.toFloat()).toDouble() }))
        else -> VideoEffectFormulasC.chromaKeyPass(u, image, AutoBackgroundFormulas.background(u, image), temporalMatte = window)
    }

    /**
     * [effect] drawn over [count] frames of a run, a frame every 1/30 s from second 1, against the core frame by
     * frame. At most [PersonPixelCases.OUTLIERS] pixels of a frame may be more than [allowed] levels away. The
     * window must change the picture against the frame's own matte by more than [allowed] on [sensitive] of a frame
     * after the first at least, and must not reach across the cut: the frame of the cut is drawn as if alone.
     */
    private fun case(name: String, effect: VideoEffectSettings, ground: (Int, Int, Int) -> Int, disc: Int, allowed: Int = PersonPixelCases.ALLOWED, count: Int = 8, cut: Int = 5, sensitive: Double = .005) = Case(name) {
        val effects = listOf(effect)
        val kind = if (effect.kind == VideoEffectID.ChromaKey) VideoEffectMatteKind.KEY else VideoEffectMatteKind.ISOLATION
        val stage = EffectStage(bench, effects, width, height)
        try {
            val history = ArrayList<Pair<MaskPlane, MaskPlane>>()
            var trailing = 0
            for (i in 0 until count) {
                val time = 1.0 + i / EffectStage.RATE
                val source = frame(i, ground, disc, cut)
                val got = stage.frame(source, (time * 1e6).roundToLong())
                val image = linear(source)
                val u = EffectShader.passes(effects, time, EffectStage.RATE).single()
                check(u.flags and EffectUniforms.FLAG_TEMPORAL_MATTE != 0) { "The recipe is not drawn with the temporal matte" }
                val current = raw(u, image)
                val guide = VideoEffectTemporalMatte.guide(image)
                val result = VideoEffectTemporalMatte.temporalMatte(current, guide, history.reversed(), kind)
                val want = encoded(drawn(u, image, result.matte))
                val alone = encoded(drawn(u, image, current))
                if (i == cut) check(result.cuts[0]) { "The core sees no cut at frame $i" }
                if (i > 0 && i != cut) check(!result.cuts[0]) { "The core sees a cut at frame $i" }
                val moved = want.pixels.indices.count { distance(want.pixels[it], alone.pixels[it]) > allowed }
                if (i > 0 && i != cut) trailing = maxOf(trailing, moved)
                var worst = 0
                var worstAt = 0
                var over = 0
                for (p in want.pixels.indices) {
                    val d = distance(got.pixels[p], want.pixels[p])
                    if (d > worst) { worst = d; worstAt = p }
                    if (d > allowed) over++
                }
                note("frame $i: the window changes $moved pixels; worst $worst levels at (${worstAt % width}, ${worstAt / width}): drawn #%06x, formula #%06x, window %.3f, own %.3f; %d over %d"
                    .format(got.pixels[worstAt], want.pixels[worstAt], result.matte.data[worstAt], current.data[worstAt], over, allowed))
                check(over <= PersonPixelCases.OUTLIERS && worst <= maxOf(allowed, PersonPixelCases.OUTLIER_LEVELS)) {
                    "Frame $i: $over pixels more than $allowed levels from the core's formula, the worst $worst"
                }
                if (i == cut) {
                    val across = want.pixels.indices.count { distance(got.pixels[it], alone.pixels[it]) > allowed }
                    check(across <= PersonPixelCases.OUTLIERS) { "Frame $i, a new scene: $across pixels blend the frames before it" }
                }
                history += MaskStorage.r8(current) to guide
            }
            check(trailing >= sensitive * width * height) { "The window changes too little of a frame: the case tests nothing" }
        } finally {
            stage.release()
        }
    }

    private fun settings(kind: VideoEffectID, values: Map<String, Any>): VideoEffectSettings {
        var effect = VideoEffectSettings(kind).copy(algorithm = 2)
        for ((id, value) in values) effect = effect.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            is EffectRGB -> EffectValue.Color(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        return effect
    }

    private val grey: (Int, Int, Int) -> Int = { _, x, y -> rgb(70 + x * 120 / width, 90 + y * 60 / height, 110) }

    /**
     * The screen, every other frame paler with about the same luma: the key leaves part of it there, while the guide
     * moves too little for the key's window to let go of the frames before (Metal:631-633). So the window blends.
     */
    private val green: (Int, Int, Int) -> Int = { i, x, _ -> if (x % 40 < 3) rgb(36, 186, 58) else if (i % 2 == 1) rgb(100, 192, 100) else screen }
    private val keyed = mapOf("autoScreen" to false, "screenColor" to EffectRGB.hex("#28C83C"))

    fun all(): List<Case> = listOf(
        case("Temporal matte: Color Isolation of a red disc", settings(VideoEffectID.ColorIsolation, emptyMap()), grey, red),
        case("Temporal matte: Chroma Key, the matte", settings(VideoEffectID.ChromaKey, keyed + mapOf("view" to "Matte", "softness" to 0.0)), green, rgb(214, 158, 124)),
        case("Temporal matte: Chroma Key, refined by softness 1", settings(VideoEffectID.ChromaKey, keyed + mapOf("softness" to 1.0, "background" to "Solid")), green, rgb(214, 158, 124)),
    )
}
