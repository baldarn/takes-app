package app.takes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.Affine
import app.takes.core.KaleidoscopeGuide
import app.takes.core.KaleidoscopePose
import app.takes.core.KaleidoscopeTarget
import app.takes.core.Point
import app.takes.core.Size
import app.takes.core.VideoEffectID
import app.takes.core.ViewLayer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Kaleidoscope's guide on the editor's preview while its controls are open, the effect on and on the picture
 * and the picture not compared with the original: the boundaries of the split, the cyan arc of the source wedge,
 * the centre and the handle that turns the split, a drag on the picture to pan the source and a twist of two
 * fingers to turn it. [framing] gives, for the view's size in pixels, how the deck lays the clip (null when it
 * shows it whole). VideoEffectsEditor.swift:972-988, KaleidoscopeGuide.swift:41-300.
 */
@Composable
internal fun KaleidoscopeOverlay(work: EffectsWork, playhead: () -> Double, framing: (Float, Float) -> ViewLayer?, change: (EffectsWork) -> Unit) {
    if (work.page != EffectsPage.Inspector || work.bypass) return
    val time = playhead()
    val effect = work.current(time)?.takeIf { e -> e.kind == VideoEffectID.Kaleidoscope && e.enabled && work.active(time).any { it.id == e.id } } ?: return
    val at = work.sourceTime(time)
    val latest by rememberUpdatedState(work)
    val clock by rememberUpdatedState(playhead)
    val send by rememberUpdatedState(change)
    val projection = { width: Float, height: Float, density: Float ->
        val layer = framing(width * density, height * density)?.let { ViewLayer(it.scaleX, it.scaleY, it.rotation, it.translationX / density, it.translationY / density) }
        KaleidoscopeGuide.viewTransform(width.toDouble(), height.toDouble(), layer)
    }
    KaleidoscopeGuideView(KaleidoscopePose.of(effect, at), effect.number("divisions", at).toInt(), projection) { pose, target ->
        send(latest.setting(pose.parameters(target), clock()))
    }
}

/**
 * The guide itself for [pose] with [divisions], over a view whose picture [projection] places: the unit square to
 * the view, in dp, given the view's width and height in dp and the density; [change]
 * takes the new pose and what it moved. Worked out in dp, the original's points. KaleidoscopeGuide.swift:41-210.
 */
@Composable
internal fun KaleidoscopeGuideView(pose: KaleidoscopePose, divisions: Int, projection: (Float, Float, Float) -> Affine, change: (KaleidoscopePose, KaleidoscopeTarget) -> Unit) {
    val density = LocalDensity.current.density
    var turningSource by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val transform = projection(widthPx / density, heightPx / density, density)
        val layout = KaleidoscopeGuide.layout(pose, divisions, transform, Size(widthPx / density.toDouble(), heightPx / density.toDouble())) ?: return@BoxWithConstraints
        val currentPose by rememberUpdatedState(pose)
        val currentLayout by rememberUpdatedState(layout)
        val currentTransform by rememberUpdatedState(transform)
        val send by rememberUpdatedState(change)
        val count by rememberUpdatedState(divisions)
        fun dp(p: Point) = Offset((p.x * density).toFloat(), (p.y * density).toFloat())
        Canvas(Modifier.fillMaxSize()) {
            val v = layout.visible
            clipRect(dp(Point(v.minX, v.minY)).x, dp(Point(v.minX, v.minY)).y, dp(Point(v.maxX, v.maxY)).x, dp(Point(v.maxX, v.maxY)).y) {
                val pivot = dp(layout.pivot)
                val dash = PathEffect.dashPathEffect(floatArrayOf(5 * density, 4 * density))
                for (ray in layout.directions) {
                    val end = Offset(pivot.x + (layout.reach * ray.x * density).toFloat(), pivot.y + (layout.reach * ray.y * density).toFloat())
                    drawLine(Color.Black.copy(alpha = 0.55f), pivot, end, 2.5f * density)
                    drawLine(Color.White.copy(alpha = 0.75f), pivot, end, 1f * density, pathEffect = dash)
                }
                // A short arc identifies the source wedge as the split turns. KaleidoscopeGuide.swift:104-112.
                layout.arc.zipWithNext { a, b -> drawLine(Color.Cyan, dp(a), dp(b), 2f * density) }
            }
        }
        // The gestures: one finger moves what it lands on, two turn the source; a second finger takes over and
        // lifting it does not start a drag. KaleidoscopeGuide.swift:156-256.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                val visible = currentLayout.visible
                fun point(o: Offset) = Point(o.x / density.toDouble(), o.y / density.toDouble())
                val p = point(first.position)
                if (!(p.x >= visible.minX && p.x < visible.maxX && p.y >= visible.minY && p.y < visible.maxY)) return@awaitEachGesture
                first.consume()
                val fingers = mutableListOf<PointerId>(first.id)
                val positions = HashMap<PointerId, Offset>().apply { put(first.id, first.position) }
                var drag: ((Point) -> KaleidoscopePose?)? = null
                var dragTarget = KaleidoscopeTarget.Source
                var twist: ((Double) -> KaleidoscopePose)? = null
                var startAngle = 0.0
                var lastTurn = 0.0
                var turning = false
                var lastPoint = first.position
                fun end() {
                    if (turning) turningSource = false
                    drag = null; twist = null; turning = false
                }
                fun begin() {
                    end()
                    if (fingers.size == 1) {
                        lastPoint = positions.getValue(fingers[0])
                        val (target, moving) = KaleidoscopeGuide.drag(currentPose, count, currentTransform, currentLayout, point(lastPoint))
                        dragTarget = target
                        drag = moving
                    } else if (fingers.size == 2) {
                        val a = positions.getValue(fingers[0])
                        val b = positions.getValue(fingers[1])
                        startAngle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                        lastTurn = 0.0
                        twist = KaleidoscopeGuide.twist(currentPose, count, currentTransform, currentLayout, point(Offset((a.x + b.x) / 2, (a.y + b.y) / 2)))
                    }
                }
                begin()
                while (fingers.isNotEmpty()) {
                    val event = awaitPointerEvent()
                    var added = false
                    var lifted = false
                    for (c in event.changes) {
                        positions[c.id] = c.position
                        if (c.pressed && c.id !in fingers) { fingers += c.id; added = true }
                        if (!c.pressed && c.id in fingers) { fingers -= c.id; lifted = true }
                        c.consume()
                    }
                    if (added) { begin(); continue }
                    drag?.let { moving ->
                        val at = positions.getValue(fingers.firstOrNull() ?: return@let)
                        if (at != lastPoint) { lastPoint = at; moving(point(at))?.let { send(it, dragTarget) } }
                    }
                    twist?.let { turn ->
                        if (fingers.size < 2) return@let
                        val a = positions.getValue(fingers[0])
                        val b = positions.getValue(fingers[1])
                        val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) - startAngle
                        val turned = atan2(sin(angle), cos(angle))
                        if (KaleidoscopeGuide.twistCounts(turned, lastTurn, turning, hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) / density)) {
                            turning = true; turningSource = true; lastTurn = turned
                            send(turn(turned), KaleidoscopeTarget.SourceRotation)
                        }
                    }
                    if (lifted) end()
                }
                end()
            }
        })
        GuideControls(pose, layout, turningSource, density) { next, target -> send(next, target) }
    }
}

/** The centre, the handle, the twist's reading, and the three parts a screen reader offers. KaleidoscopeGuide.swift:113-155. */
@Composable
private fun GuideControls(pose: KaleidoscopePose, layout: KaleidoscopeGuide.Layout, turningSource: Boolean, density: Float, change: (KaleidoscopePose, KaleidoscopeTarget) -> Unit) {
    fun at(p: Point, size: Dp) = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(IntOffset((p.x * density - placeable.width / 2.0).roundToInt(), (p.y * density - placeable.height / 2.0).roundToInt()))
        }
    }.then(if (size == Dp.Unspecified) Modifier else Modifier.size(size))
    val step = KaleidoscopeGuide.NUDGE
    val v = layout.visible
    // The source: the whole visible picture, for a screen reader.
    val sourceLabel = stringResource(R.string.kaleidoscope_source)
    val sourceReading = stringResource(
        R.string.kaleidoscope_source_reading,
        KaleidoscopePose.display("sourcePanX", pose.sourcePan.x), KaleidoscopePose.display("sourcePanY", pose.sourcePan.y), KaleidoscopePose.display("sourceAngle", pose.sourceDegrees),
    )
    val sourceHint = stringResource(R.string.kaleidoscope_source_hint)
    val sourceActions = listOf(
        stringResource(R.string.kaleidoscope_source_x_less) to { pose.offsetSource(-step, 0.0) to KaleidoscopeTarget.Source },
        stringResource(R.string.kaleidoscope_source_x_more) to { pose.offsetSource(step, 0.0) to KaleidoscopeTarget.Source },
        stringResource(R.string.kaleidoscope_source_y_less) to { pose.offsetSource(0.0, -step) to KaleidoscopeTarget.Source },
        stringResource(R.string.kaleidoscope_source_y_more) to { pose.offsetSource(0.0, step) to KaleidoscopeTarget.Source },
        stringResource(R.string.kaleidoscope_source_turn_more) to { pose.turningSource(1.0) to KaleidoscopeTarget.SourceRotation },
        stringResource(R.string.kaleidoscope_source_turn_less) to { pose.turningSource(-1.0) to KaleidoscopeTarget.SourceRotation },
        stringResource(R.string.kaleidoscope_source_turn_reset) to { pose.turningSource(-pose.sourceDegrees) to KaleidoscopeTarget.SourceRotation },
    )
    Box(
        Modifier.offset((v.minX).dp, (v.minY).dp).size(v.width.dp, v.height.dp).clearAndSetSemantics {
            contentDescription = "$sourceLabel. $sourceHint"
            stateDescription = sourceReading
            customActions = sourceActions.map { (name, next) -> CustomAccessibilityAction(name) { next().let { (p, t) -> change(p, t) }; true } }
        },
    )
    val centerLabel = stringResource(R.string.kaleidoscope_split_center)
    val centerReading = "${KaleidoscopePose.display("centerX", pose.center.x)}, ${KaleidoscopePose.display("centerY", pose.center.y)}"
    val centerActions = listOf(
        stringResource(R.string.rgb_pad_left) to Point(-step, 0.0), stringResource(R.string.rgb_pad_right) to Point(step, 0.0),
        stringResource(R.string.rgb_pad_up) to Point(0.0, -step), stringResource(R.string.rgb_pad_down) to Point(0.0, step),
    )
    Box(
        at(layout.pivot, 44.dp).clearAndSetSemantics {
            contentDescription = centerLabel
            stateDescription = centerReading
            customActions = centerActions.map { (name, d) -> CustomAccessibilityAction(name) { change(pose.nudgeCenter(d.x, d.y), KaleidoscopeTarget.Center); true } }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(21.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, Modifier.size(13.dp), tint = Color.White)
        }
    }
    val rotateLabel = stringResource(R.string.kaleidoscope_rotate_split)
    val rotateMore = stringResource(R.string.kaleidoscope_rotate_more)
    val rotateLess = stringResource(R.string.kaleidoscope_rotate_less)
    Box(
        at(layout.handle, 44.dp).clearAndSetSemantics {
            contentDescription = rotateLabel
            stateDescription = KaleidoscopePose.display("splitAngle", pose.degrees)
            customActions = listOf(
                CustomAccessibilityAction(rotateMore) { change(pose.turningSplit(1.0), KaleidoscopeTarget.Rotation); true },
                CustomAccessibilityAction(rotateLess) { change(pose.turningSplit(-1.0), KaleidoscopeTarget.Rotation); true },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).background(Color.Black.copy(alpha = 0.75f), CircleShape).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Autorenew, null, Modifier.size(17.dp), tint = Color.White)
        }
    }
    if (turningSource) {
        Text(
            stringResource(R.string.kaleidoscope_source_turn, KaleidoscopePose.display("sourceAngle", pose.sourceDegrees)),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = at(Point(v.midX, v.maxY - 19), Dp.Unspecified).clearAndSetSemantics { }
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
