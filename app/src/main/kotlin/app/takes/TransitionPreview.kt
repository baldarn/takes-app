@file:OptIn(UnstableApi::class)

package app.takes

import android.graphics.Bitmap
import android.os.Build
import android.view.LayoutInflater
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.takes.core.PictureFrame
import app.takes.core.Rect
import app.takes.core.Size as FrameSize
import app.takes.core.SplitViews
import app.takes.core.TransitionFrame
import app.takes.core.TransitionScene
import app.takes.core.TransitionStyle

/** Android 13 brought the shaders the preview draws transitions with; before it, the decks are cross-faded. */
internal val shadedPreview = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The styles only the shader can draw: without it the preview shows them as a
 * dissolve, and the export is unaffected. A split screen is drawn with views.
 */
internal val simplifiedWithoutShader = setOf(TransitionStyle.Wipe, TransitionStyle.Hitchcock, TransitionStyle.Whip, TransitionStyle.Defocus)

/**
 * The frames the decks hold while their clips play on. A still whose sound
 * has a timing of its own freezes the picture of its outgoing clip while the
 * clip's sound runs on under it, and a player cannot stop its picture and go
 * on with its sound. So the frame is drawn over the deck's view until the
 * freeze ends: the frame itself, read from the recording, or until that is
 * ready the picture the view showed when the freeze began.
 */
internal class HeldPictures {
    private val views = arrayOfNulls<TextureView>(2)
    private val kept = arrayOfNulls<Bitmap>(2)
    private val freezes = arrayOfNulls<Any>(2)

    /** What is drawn over each deck now. */
    var shown by mutableStateOf(listOf<Bitmap?>(null, null)); private set

    fun attach(deck: Int, view: TextureView?) { views[deck] = view }

    /** Takes what [deck] shows at this moment as the frame of [freeze]; it is not drawn until [show]. False when the deck has no picture yet. */
    fun keep(deck: Int, freeze: Any): Boolean {
        val picture = views[deck]?.takeIf { it.isAvailable }?.bitmap ?: return false
        kept[deck] = picture
        freezes[deck] = freeze
        return true
    }

    /** Gives [deck] the frame of [freeze] as it was read from the recording. */
    fun put(deck: Int, freeze: Any, picture: Bitmap) {
        kept[deck] = picture
        freezes[deck] = freeze
    }

    /** The freeze whose frame [deck] has. */
    fun freeze(deck: Int): Any? = freezes[deck]

    fun show(deck: Int) {
        if (shown[deck] !== kept[deck]) shown = shown.toMutableList().also { it[deck] = kept[deck] }
    }

    /** Lets go of the frame of [deck]; with [freeze], only when it is that freeze's. */
    fun drop(deck: Int, freeze: Any? = null) {
        if (freeze != null && freezes[deck] !== freeze) return
        kept[deck] = null
        freezes[deck] = null
        if (shown[deck] != null) shown = shown.toMutableList().also { it[deck] = null }
    }
}

/**
 * The picture of the preview: the two decks and what the moment makes of
 * them. [effects] is each deck's color and [frames] its framing at this
 * moment for a frame of the size drawn, null for a clip that is not cropped;
 * [moment] gives the moment for a frame of the size drawn,
 * and [content] where a clip's own picture lies in that frame. [held] has the
 * frame a deck holds while its clip plays on, drawn in place of its picture.
 *
 * On Android 13 and later the decks lie one above the other in a layer twice
 * the height of the frame, and the transition shader draws the frame from the
 * two, with the numbers and the shader text the export uses. Only the upper
 * half of the layer is in view.
 *
 * Before Android 13 the decks are stacked and cross-faded, with the fill
 * color over them: exact for a cut, a flash cut and a freeze; a dissolve and a
 * fade are mixed on encoded pixels, so their middle is darker than the
 * export's. A split screen is drawn with the decks' views scaled, moved and
 * clipped into their halves ([SplitViews]): the pair is exact, its brief entry
 * and exit are not dissolves. Every other style is shown as a dissolve.
 */
@Composable
internal fun DeckPictures(
    decks: List<Player>, effects: List<RenderEffect?>, frames: List<((FrameSize) -> PictureFrame)?>,
    moment: (FrameSize) -> TransitionScene.Moment, content: (Int, FrameSize) -> Rect, held: HeldPictures,
) {
    val deck: @Composable (Int, Modifier) -> Unit = { index, modifier ->
        val placed = modifier.fillMaxSize().graphicsLayer {
            renderEffect = effects[index]
            // The player fits the whole picture in this view. Enlarge, turn and shift the
            // view so the picture lies where the framing puts it, as the export draws it.
            frames[index]?.let { framing ->
                val layer = framing(FrameSize(size.width.toDouble(), size.height.toDouble())).layer
                scaleX = layer.scaleX.toFloat()
                scaleY = layer.scaleY.toFloat()
                rotationZ = layer.rotation.toFloat()
                translationX = layer.translationX.toFloat()
                translationY = layer.translationY.toFloat()
            }
        }
        AndroidView(
            { (LayoutInflater.from(it).inflate(R.layout.deck, null) as PlayerView).apply { player = decks[index]; held.attach(index, videoSurfaceView as? TextureView) } },
            placed,
        )
        // The frame a freeze holds, where the player fits its picture, with the deck's color and crop.
        held.shown[index]?.let { Image(it.asImageBitmap(), null, placed, contentScale = ContentScale.Fit) }
    }
    if (shadedPreview) {
        val shader = remember { TransitionPreviewShader() }
        Column(
            Modifier.layout { measurable, constraints ->
                val both = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight * 2))
                layout(constraints.maxWidth, constraints.maxHeight) { both.place(0, 0) }
            }.graphicsLayer {
                val width = size.width
                val height = size.height / 2
                val frame = FrameSize(width.toDouble(), height.toDouble())
                val drawing = SceneDrawing.of(moment(frame), width, height, { source ->
                    when (source) {
                        is TransitionScene.Source.Clip -> source.deck.toFloat()
                        is TransitionScene.Source.Held -> source.deck.toFloat() // the deck has stopped on that frame
                        is TransitionScene.Source.Fill -> SceneDrawing.FILL
                    }
                }) { content(it, frame) }
                renderEffect = shader.effect(drawing, width, height).asComposeRenderEffect()
            },
        ) {
            for (index in 0..1) Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { deck(index, Modifier) }
        }
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvas = FrameSize(constraints.maxWidth.toDouble(), constraints.maxHeight.toDouble())
        val now = moment(canvas)
        val split = (now.frame as? TransitionFrame.Split)?.takeIf { splitDecks(now) }
        // A split screen: each deck scaled, moved and clipped into its half, as SplitViews places it.
        val drawing = split?.let {
            val whole = Rect(0.0, 0.0, canvas.width, canvas.height)
            fun lies(source: TransitionScene.Source) = when (source) {
                is TransitionScene.Source.Clip -> content(source.index, canvas)
                is TransitionScene.Source.Held -> content(source.index, canvas)
                is TransitionScene.Source.Fill -> whole
            }
            SplitViews.of(it, canvas, lies(now.first), lies(now.second))
        }
        val sides = drawing?.let { listOf(now.first to it.first, now.second to it.second) }.orEmpty()
        // Otherwise how much of the frame each deck and the fill color give.
        val (first, second) = when (val frame = now.frame) {
            TransitionFrame.First -> 1.0 to 0.0
            TransitionFrame.Second -> 0.0 to 1.0
            is TransitionFrame.FadeColor -> frame.first to frame.second
            is TransitionFrame.Dissolve -> frame.first to frame.second
            else -> (now.firstShare + now.secondShare).let { if (it > 0) now.firstShare / it to now.secondShare / it else 1.0 to 0.0 }
        }
        val shares = DoubleArray(2)
        var fill = (1 - first - second).coerceIn(0.0, 1.0)
        var color = (now.frame as? TransitionFrame.FadeColor)?.color
        for ((source, share) in listOf(now.first to first, now.second to second)) when (source) {
            is TransitionScene.Source.Clip -> shares[source.deck] += share
            is TransitionScene.Source.Held -> shares[source.deck] += share
            is TransitionScene.Source.Fill -> { fill += share; color = source.color }
        }
        // The decks are always drawn here, in this order, so that their views are never made again.
        fun placed(index: Int, shared: Modifier): Modifier {
            if (drawing == null) return shared
            val side = sides.firstOrNull { (source, _) -> deckOf(source) == index }?.second ?: return Modifier.graphicsLayer { alpha = 0f }
            return Modifier.splitSide(side)
        }
        // Deck 1 below, opaque whenever it contributes; deck 0 above, as transparent as its share of the two.
        deck(1, placed(1, Modifier.graphicsLayer { alpha = if (shares[1] > 0) 1f else 0f }))
        deck(0, placed(0, Modifier.graphicsLayer { alpha = if (shares[0] > 0) (shares[0] / (shares[0] + shares[1])).toFloat() else 0f }))
        if (drawing != null) {
            for ((source, side) in sides) if (source is TransitionScene.Source.Fill) {
                val solid = source.color
                Box(Modifier.splitSide(side).fillMaxSize().background(Color(solid.red.toFloat(), solid.green.toFloat(), solid.blue.toFloat())))
            }
            val line = split.lineColor
            Box(
                Modifier.zIndex(LINE_RANK).fillMaxSize().graphicsLayer { alpha = drawing.lineAlpha.toFloat() }.drawBehind {
                    drawRect(
                        Color(line.red.toFloat(), line.green.toFloat(), line.blue.toFloat()),
                        Offset(drawing.line.x.toFloat(), drawing.line.y.toFloat()), Size(drawing.line.width.toFloat(), drawing.line.height.toFloat()),
                    )
                },
            )
        } else {
            val solid = color
            if (fill > 0 && solid != null) {
                Box(Modifier.fillMaxSize().background(Color(solid.red.toFloat(), solid.green.toFloat(), solid.blue.toFloat(), fill.coerceIn(0.0, 1.0).toFloat())))
            }
        }
    }
}

/** The deck a side is shown by; null for the fill color. */
private fun deckOf(source: TransitionScene.Source): Int? = when (source) {
    is TransitionScene.Source.Clip -> source.deck
    is TransitionScene.Source.Held -> source.deck
    is TransitionScene.Source.Fill -> null
}

/** Whether the views can draw the split screen of [moment]: each side on a deck of its own, or the fill color. */
private fun splitDecks(moment: TransitionScene.Moment): Boolean {
    val first = deckOf(moment.first)
    val second = deckOf(moment.second)
    return listOfNotNull(first, second).all { it in 0..1 } && (first == null || first != second)
}

/** Above both sides of a split screen. */
private const val LINE_RANK = 2f

/** A side of a split screen: its picture, a view the size of the frame, scaled about its top left corner into its half and clipped to it, or whole. */
private fun Modifier.splitSide(side: SplitViews.Side): Modifier = zIndex(side.rank.toFloat()).graphicsLayer {
    alpha = side.alpha.toFloat()
    val placed = side.placed ?: return@graphicsLayer
    val scale = placed.scale.toFloat()
    val dx = placed.translationX.toFloat()
    val dy = placed.translationY.toFloat()
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = scale
    scaleY = scale
    translationX = dx
    translationY = dy
    // The clip is in the view's own coordinates, before it is scaled and moved.
    clip = true
    shape = Clipped(
        androidx.compose.ui.geometry.Rect(
            ((placed.shown.minX - dx) / scale).toFloat(), ((placed.shown.minY - dy) / scale).toFloat(),
            ((placed.shown.maxX - dx) / scale).toFloat(), ((placed.shown.maxY - dy) / scale).toFloat(),
        ),
    )
}

/** A clip rectangle in the coordinates of the view it clips. */
private class Clipped(private val rect: androidx.compose.ui.geometry.Rect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rectangle(rect)
    override fun equals(other: Any?): Boolean = other is Clipped && other.rect == rect
    override fun hashCode(): Int = rect.hashCode()
}
