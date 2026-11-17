package app.takes.pixels

import android.graphics.Bitmap
import android.graphics.Canvas
import app.takes.OverlayPictures
import app.takes.TitlePainter
import app.takes.core.EditState
import app.takes.core.EditTime
import app.takes.core.ImageOverlay
import app.takes.core.LayerPlan
import app.takes.core.PlacementLayout
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TitleMotionRanges
import app.takes.core.TrackRenderPlan
import app.takes.core.TrackTimeline
import app.takes.core.TransitionSettings
import app.takes.core.TransitionStyle
import app.takes.core.VideoPlacement
import app.takes.core.VideoPlacementRegion
import app.takes.core.VideoTimelineItem
import app.takes.core.compile
import app.takes.core.toTimeline
import java.io.File
import kotlin.math.abs

/**
 * Pictures laid over clips, in both exports, against the preview. The preview draws a clip's pictures with
 * [TitlePainter] on the view of its picture; the exports draw them with the same painter into the bitmap of a deck or a
 * lane, which the shader lays over its picture before the transition combines the two sides, as the original draws each
 * clip with its overlays (`YouRec/VideoToolCompositor.swift:282`, `:396-420`). A picture shows over its footage only,
 * and a wipe uncovers one with its clip.
 */
class ImagePixelCases(private val bench: Bench) {
    private val colorA = rgb(40, 90, 160)
    private val colorB = rgb(160, 90, 40)
    private val colorO = rgb(30, 170, 60)
    private val folder: File = File(bench.context.cacheDir, "image-pixels").apply { mkdirs() }
    private val name = "image-pixels.png"

    init {
        // 100 by 50: a magenta block with a transparent border and a half-transparent green band, as Tests/VideoToolsTests.swift:715-717 draws one.
        val picture = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        picture.eraseColor(android.graphics.Color.TRANSPARENT)
        Canvas(picture).apply {
            drawRect(10f, 10f, 90f, 40f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(255, 0, 255) })
            drawRect(10f, 40f, 90f, 50f, android.graphics.Paint().apply { color = android.graphics.Color.argb(128, 0, 255, 0) })
        }
        File(folder, name).outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun clip(id: String, seconds: Double = SECONDS) = TimelineClip(id = id, sourceID = "S", start = 0.0, end = seconds)

    private val overlay = ImageOverlay(id = "I", filename = name, start = 1.0, end = 2.0, x = 0.7, y = 0.3, width = 0.5, opacity = 0.8)

    /** How far apart two colors are: the largest difference of a channel, in steps. */
    private fun apart(one: Int, other: Int): Int = (0..2).maxOf { abs(((one shr (8 * it)) and 0xff) - ((other shr (8 * it)) and 0xff)) }

    /** What the preview shows: the flat picture of the clip with the painter's drawing of [shown] over it, at the movie's size. */
    private fun preview(color: Int, shown: List<TitleMotionRanges.Shown>): Picture {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        frame.eraseColor(color or (0xff shl 24))
        TitlePainter(bench.context.assets, OverlayPictures(folder)).draw(Canvas(frame), WIDTH.toFloat(), HEIGHT.toFloat(), shown)
        val pixels = IntArray(WIDTH * HEIGHT)
        frame.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        frame.recycle()
        return Picture(WIDTH, HEIGHT, IntArray(pixels.size) { pixels[it] and 0xffffff })
    }

    /** The export's frame against the preview's: the most any pixel is apart, and how many are more than [loose] apart. */
    private fun Checks.agree(what: String, export: Picture, preview: Picture, loose: Int = 12) {
        val apart = IntArray(export.pixels.size) { apart(export.pixels[it], preview.pixels[it]) }
        val far = apart.count { it > loose }
        val drawn = preview.pixels.count { apart(it, colorA) > 8 && apart(it, colorB) > 8 }
        note("$what: the export and the preview differ by up to ${apart.max()}, $far pixels by more than $loose; the picture covers $drawn")
        check(drawn > 1000) { "$what: the picture is not drawn in the preview ($drawn pixels)" }
        // Only the edges of the picture may differ, where the two filter it differently.
        check(far <= drawn / 20) { "$what: $far pixels of the export are more than $loose apart from the preview" }
    }

    /** The picture by itself on one clip, exported as a sequence: where the preview draws it, as it draws it, and only over its footage. */
    private fun Checks.alone() {
        val clips = listOf(clip("A").copy(images = listOf(overlay)))
        val stage = Stage(bench, clips, WIDTH, HEIGHT, titled = true, folder = folder) { _, _ -> Picture.flat(WIDTH, HEIGHT, colorA) }
        try {
            val plan = TrackRenderPlan.of(EditState(clips))
            agree("at 1.5 s", stage.frame(1.5), preview(colorA, plan.titlesShown(1.5)))
            for (time in listOf(0.5, 2.5)) {
                val off = stage.frame(time).pixels.count { apart(it, colorA) > 2 }
                check(off == 0) { "The picture shows at $time s, outside its footage: $off pixels" }
            }
            // The magenta block, at 80% over blue: where the core puts it, a quarter from the right edge of the band.
            val rect = overlay.frame(app.takes.core.Size(100.0, 50.0), app.takes.core.Size(WIDTH.toDouble(), HEIGHT.toDouble()))
            val middle = stage.frame(1.5).at((rect.x + rect.width / 2).toInt(), (rect.y + rect.height * 0.5).toInt())
            val expected = rgb((0.8 * 255 + 0.2 * 40).toInt(), (0.2 * 90).toInt(), (0.8 * 255 + 0.2 * 160).toInt())
            note("the middle of the block is ${Integer.toHexString(middle)}, at 80% over the clip ${Integer.toHexString(expected)}")
            check(apart(middle, expected) <= 3) { "The block is ${Integer.toHexString(middle)}, not ${Integer.toHexString(expected)}" }
        } finally {
            stage.release()
        }
    }

    /** A wipe: the picture of B is uncovered with B's picture, behind the edge, and there is none of it ahead of the edge. */
    private fun Checks.wiped() {
        val settings = TransitionSettings(id = "T", style = TransitionStyle.Wipe, duration = 1.0)
        fun joined(with: Boolean) = TimelineEditing.setJoin(EditState(listOf(clip("A"), clip("B"))), 1, settings)!!.clips
            .mapIndexed { index, clip -> if (index == 1 && with) clip.copy(images = listOf(overlay.copy(start = 0.0, end = SECONDS, x = 0.5, y = 0.5, width = 1.0))) else clip }
        val pictures = { index: Int, _: Double -> Picture.flat(WIDTH, HEIGHT, if (index == 0) colorA else colorB) }
        val titled = Stage(bench, joined(true), WIDTH, HEIGHT, titled = true, folder = folder, picture = pictures)
        val plain = Stage(bench, joined(false), WIDTH, HEIGHT, picture = pictures)
        try {
            val join = TimelineEditing.join(titled.clips, 1)!!
            val half = join.origin + join.timing.motion * 0.5
            val alone = join.origin + join.timing.motion + 0.5
            val withPicture = titled.frame(alone)
            val without = plain.frame(alone)
            val mask = withPicture.pixels.indices.filter { apart(withPicture.pixels[it], without.pixels[it]) > 40 }
            val now = titled.frame(half)
            val under = plain.frame(half)
            val ahead = mask.filter { apart(under.pixels[it], colorA) <= 2 }
            val behind = mask.filter { apart(under.pixels[it], colorB) <= 2 }
            note("picture pixels ahead of the edge ${ahead.size}, behind it ${behind.size}")
            check(ahead.size > 50 && behind.size > 50) { "The edge does not cross the picture: ${ahead.size} ahead, ${behind.size} behind" }
            val leaked = ahead.maxOfOrNull { apart(now.pixels[it], under.pixels[it]) } ?: 0
            check(leaked <= 3) { "The picture of B shows ahead of the edge, over A, by up to $leaked steps" }
            val shown = behind.count { apart(now.pixels[it], under.pixels[it]) > 40 }
            check(shown >= behind.size * 9 / 10) { "Behind the edge only $shown of ${behind.size} pixels of the picture show" }
        } finally {
            titled.release()
            plain.release()
        }
    }

    /** Picture layers: the pair under a picture in the corner, the picture of A drawn in A's lane where the preview draws it on A's view. */
    private fun Checks.layered() {
        val timeline = EditState(listOf(clip("A").copy(images = listOf(overlay)))).toTimeline()
        val over = timeline.copy(video = timeline.video + VideoTimelineItem.of("occurrence-O", EditTime.of(0.0), clip("O"), 10))
        val corner = VideoPlacement.layout(PlacementLayout.PictureInPicture, 1, 2).copy(order = 10)
        val together = over.compile().map { it.video.toSet() }.filter { "O" in it && it.size > 1 }.distinct()
        val placed: TrackTimeline = over.copy(placements = together.mapIndexed { number, ids -> VideoPlacementRegion(id = "R$number", participants = ids, videos = ids.associateWith { if (it == "O") corner else VideoPlacement() }) })
        val plan = LayerPlan.planned(placed, 3)!!
        val pictures = mapOf("A" to Picture.flat(WIDTH, HEIGHT, colorA), "O" to Picture.flat(WIDTH, HEIGHT, colorO))
        val stage = LayerStage(bench, plan, WIDTH, HEIGHT, titled = true, folder = folder) { index -> pictures[plan.pictures[index].id] }
        try {
            val frame = stage.frame(1.5)
            val alone = listOf(clip("A").copy(images = listOf(overlay)))
            val expected = preview(colorA, TitleMotionRanges(alone).shown(alone, 1.5))
            // Away from the picture in the corner, the lane of A with its picture is what the preview shows.
            val window = plan.moment(1.5, app.takes.core.Size(WIDTH.toDouble(), HEIGHT.toDouble())).layers
                .filterIsInstance<LayerPlan.Layer.Whole>().filter { plan.pictures[it.picture].id == "O" }.mapNotNull { it.window?.window }
            fun covered(index: Int) = window.any { (index % WIDTH).toDouble() in it.minX - 2..it.maxX + 2 && (index / WIDTH).toDouble() in it.minY - 2..it.maxY + 2 }
            val masked = { picture: Picture -> Picture(WIDTH, HEIGHT, IntArray(picture.pixels.size) { if (covered(it)) colorA else picture.pixels[it] }) }
            agree("among layers at 1.5 s", masked(frame), masked(expected))
        } finally {
            stage.release()
        }
    }

    fun all(): List<Case> = listOf(
        Case("Images (sequence): the export draws a picture as the preview does, over its footage only") { alone() },
        Case("Images (sequence): a wipe uncovers the picture of the clip coming with its picture") { wiped() },
        Case("Images (layers): the export draws a picture in the lane of its clip as the preview does") { layered() },
    )

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val SECONDS = 4.5
    }
}
