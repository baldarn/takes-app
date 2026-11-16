@file:OptIn(UnstableApi::class)

package app.takes

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.takes.core.LayerPlan
import app.takes.core.PictureFrame
import app.takes.core.Rect
import app.takes.core.Size as FrameSize
import app.takes.core.SplitViews
import app.takes.core.TitleMotionRanges
import app.takes.core.TransitionScene

/**
 * The picture of the preview of an edit drawn by picture layers: one view a
 * lane, each showing its player's picture with that picture's color, video
 * effects and crop ([effects], [frames]) and its titles drawn on it
 * ([titles]), and what the moment makes of them ([moment], for a frame of the
 * size drawn; [content] is where a picture's footage lies in that frame).
 *
 * On Android 13 and later the lanes lie one above the other in a layer as
 * many times as tall as the frame, and the layer shader draws the frame from
 * them with the numbers and the shader text the export uses
 * ([LayerDrawing]): back to front over black, a pair in transition mixed, each
 * other picture moved into its window. Only the top of the layer is in view.
 *
 * Before Android 13 each lane's view is moved into its window and clipped to
 * it, back to front: exact for pictures over one another and for windows; a
 * pair in a split screen is drawn as a sequence's decks are there, each lane
 * scaled, moved and clipped into its half ([SplitViews.layered]), with the
 * divider and the titles of both over it; any other pair in transition is
 * cross-faded, as the decks of a sequence are there.
 */
@Composable
internal fun LayerPictures(
    lanes: List<Player>, count: Int, effects: List<RenderEffect?>, frames: List<((FrameSize) -> PictureFrame)?>,
    titles: List<List<TitleMotionRanges.Shown>>, painter: TitlePainter,
    moment: (FrameSize) -> LayerPlan.Moment, content: (Int, FrameSize) -> Rect,
) {
    // The pictures of a split screen are drawn without their titles, and those of both go once over the pair, as the
    // original draws them (YouRec/VideoToolCompositor.swift:282, :298-301): with the shader, or with the views below it.
    val split = moment(FrameSize(1920.0, 1080.0)).let { now ->
        val drawn = shadedPreview || SplitViews.layered(now, FrameSize(1920.0, 1080.0), count) { Rect(0.0, 0.0, 1920.0, 1080.0) } != null
        if (drawn) splitTitles(now, titles) else null
    }
    val pairTitles = remember { PairTitles(painter) }
    val lane: @Composable (Int, Modifier) -> Unit = { index, modifier ->
        Box(modifier.fillMaxSize().clipToBounds()) {
            val placed = Modifier.fillMaxSize().graphicsLayer {
                renderEffect = effects.getOrNull(index)
                // As a deck of a sequence: the view enlarged, turned and shifted so the footage lies where its crop puts it.
                frames.getOrNull(index)?.let { framing ->
                    val layer = framing(FrameSize(size.width.toDouble(), size.height.toDouble())).layer
                    scaleX = layer.scaleX.toFloat()
                    scaleY = layer.scaleY.toFloat()
                    rotationZ = layer.rotation.toFloat()
                    translationX = layer.translationX.toFloat()
                    translationY = layer.translationY.toFloat()
                }
            }
            AndroidView({ (LayoutInflater.from(it).inflate(R.layout.deck, null) as PlayerView).apply { player = lanes[index] } }, placed)
            // The titles of the picture belong to its layer: they are moved into its window with it.
            val shown = if (split != null && index in split.lanes) emptyList() else titles.getOrNull(index).orEmpty()
            if (shown.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, shown) } }
        }
    }
    if (shadedPreview) {
        val shader = remember { LayerPreviewShader() }
        Column(
            Modifier.layout { measurable, constraints ->
                val all = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight * count))
                layout(constraints.maxWidth, constraints.maxHeight) { all.place(0, 0) }
            }.graphicsLayer {
                val width = size.width
                val height = size.height / count
                val frame = FrameSize(width.toDouble(), height.toDouble())
                val drawing = LayerDrawing.of(moment(frame), width, height, { source ->
                    when (source) {
                        is TransitionScene.Source.Clip -> source.deck.toFloat()
                        is TransitionScene.Source.Held -> source.deck.toFloat() // the lane has stopped on that frame
                        is TransitionScene.Source.Fill -> LayerDrawing.FILL
                    }
                }) { content(it, frame) }
                val over = split?.titles?.takeIf { it.isNotEmpty() }?.let { pairTitles.shader(it, width.toInt(), height.toInt()) }
                renderEffect = shader.effect(drawing, width, height, over).asComposeRenderEffect()
            },
        ) {
            for (index in 0 until count) Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { lane(index, Modifier) }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val frame = FrameSize(constraints.maxWidth.toDouble(), constraints.maxHeight.toDouble())
            val now = moment(frame)
            // A split screen: each lane of its pair scaled, moved and clipped into its half, as a sequence's decks are.
            val pair = SplitViews.layered(now, frame, count) { content(it, frame) }
            val places = places(now, count, pair)
            for (index in 0 until count) key(index) {
                val place = places[index]
                val side = place?.side
                lane(
                    index,
                    if (side != null) Modifier.splitSide(side, place.rank) else Modifier.zIndex(place?.rank ?: -1f).graphicsLayer {
                        alpha = place?.alpha ?: 0f
                        val window = place?.window ?: return@graphicsLayer
                        // The lane's picture, the size of the frame, scaled about its top left corner into the window and clipped to what it shows there.
                        val scale = window.scale.toFloat()
                        val dx = (window.window.midX - window.source.midX * window.scale).toFloat()
                        val dy = (window.window.midY - window.source.midY * window.scale).toFloat()
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = dx
                        translationY = dy
                        val shown = LayerDrawing.shown(window)
                        clip = true
                        shape = Window(
                            androidx.compose.ui.geometry.Rect(
                                ((shown.minX - dx) / scale).toFloat(), ((shown.minY - dy) / scale).toFloat(),
                                ((shown.maxX - dx) / scale).toFloat(), ((shown.maxY - dy) / scale).toFloat(),
                            ),
                        )
                    },
                )
            }
            if (pair != null) {
                // The fill on a side without a picture, the divider over both, and the titles of both once over the pair, under what is in front of it.
                val base = pair.layer * 2f
                val pairMoment = now.layers[pair.layer] as LayerPlan.Layer.Mixed
                for ((source, side) in listOf(pairMoment.first to pair.drawing.first, pairMoment.second to pair.drawing.second)) {
                    if (source is TransitionScene.Source.Fill) SplitFill(source.color, side, base + side.rank * SIDE_STEP)
                }
                SplitLine(pair.frame, pair.drawing, base + LINE_STEP)
                val over = split?.titles.orEmpty()
                if (over.isNotEmpty()) Canvas(Modifier.zIndex(base + TITLES_STEP).fillMaxSize()) { drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, over) } }
            }
        }
    }
}

/** Within the two places back to front a pair in a split screen has below Android 13: its second side, its divider, its titles. */
private const val SIDE_STEP = 0.5f
private const val LINE_STEP = 1.25f
private const val TITLES_STEP = 1.5f

/** The [lanes] of a split screen in transition, which draw no titles, and the [titles] of both, each once, drawn over the pair. */
private class SplitTitles(val lanes: Set<Int>, val titles: List<TitleMotionRanges.Shown>)

/** The split screen of [moment] with the titles of its pictures out of [titles], those of each lane; null when its pair is not a split screen. */
private fun splitTitles(moment: LayerPlan.Moment, titles: List<List<TitleMotionRanges.Shown>>): SplitTitles? {
    val pair = moment.layers.lastOrNull { it is LayerPlan.Layer.Mixed } as? LayerPlan.Layer.Mixed ?: return null
    if (TransitionScene.titlesInSides(pair.frame)) return null
    val lanes = listOf(pair.first, pair.second).mapNotNull(TransitionScene::deckOf).distinct()
    // A title on both pictures once, as the original's `seen` keeps it once.
    val both = lanes.flatMap { titles.getOrNull(it).orEmpty() }.distinctBy { it.title.copy(start = 0.0, end = 0.0) }
    return SplitTitles(lanes.toSet(), both)
}

/** The titles over a split screen as a picture of the frame's size, for the layer shader; drawn again only when they change. */
private class PairTitles(private val painter: TitlePainter) {
    private var bitmap: android.graphics.Bitmap? = null
    private var shown: List<TitleMotionRanges.Shown> = emptyList()
    private var shader: android.graphics.BitmapShader? = null

    fun shader(titles: List<TitleMotionRanges.Shown>, width: Int, height: Int): android.graphics.Shader {
        val kept = bitmap?.takeIf { it.width == width && it.height == height }
        val picture = kept ?: android.graphics.Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), android.graphics.Bitmap.Config.ARGB_8888).also {
            bitmap = it
            shader = null
        }
        if (kept == null || titles != shown) {
            picture.eraseColor(android.graphics.Color.TRANSPARENT)
            painter.draw(android.graphics.Canvas(picture), width.toFloat(), height.toFloat(), titles)
            shown = titles
            shader = null
        }
        return shader ?: android.graphics.BitmapShader(picture, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).also { shader = it }
    }
}

/**
 * Where a lane's view goes in the preview below Android 13: its place back to front, how opaque it is, and its window;
 * or, for a side of a split screen, where [side] puts it.
 */
private class LanePlace(val rank: Float, val alpha: Float, val window: app.takes.core.PlacedPicture?, val side: SplitViews.Side? = null)

private fun places(moment: LayerPlan.Moment, count: Int, split: SplitViews.Layered?): Array<LanePlace?> {
    val places = arrayOfNulls<LanePlace>(count)
    fun lane(source: TransitionScene.Source): Int? = when (source) {
        is TransitionScene.Source.Clip -> source.deck
        is TransitionScene.Source.Held -> source.deck
        is TransitionScene.Source.Fill -> null
    }?.takeIf { it in 0 until count }
    moment.layers.forEachIndexed { rank, layer ->
        when (layer) {
            is LayerPlan.Layer.Whole -> lane(layer.source)?.let { places[it] = LanePlace(rank * 2f, 1f, layer.window) }
            is LayerPlan.Layer.Mixed -> if (split != null && split.layer == rank) {
                for ((lane, side) in listOf(split.first to split.drawing.first, split.second to split.drawing.second)) {
                    lane?.let { places[it] = LanePlace(rank * 2f + side.rank * SIDE_STEP, side.alpha.toFloat(), null, side) }
                }
            } else {
                // The incoming picture over the outgoing one, as transparent as its share of the two.
                val total = layer.firstShare + layer.secondShare
                lane(layer.first)?.let { places[it] = LanePlace(rank * 2f, 1f, null) }
                lane(layer.second)?.let { places[it] = LanePlace(rank * 2f + 1, if (total > 0) (layer.secondShare / total).toFloat() else 0f, null) }
            }
        }
    }
    return places
}

/** A clip rectangle in the coordinates of the view it clips. */
private class Window(private val rect: androidx.compose.ui.geometry.Rect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rectangle(rect)
}

/** In place of the picture, for an edit with more pictures at once than the device decodes: why nothing is drawn ([EditRendering.tooMany]). */
@Composable
internal fun LayersRefused(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}
