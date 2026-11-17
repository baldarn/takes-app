package app.takes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.PreviewInspection
import app.takes.core.Size

/**
 * The inspection zoom of a preview, as the original's player view has it (YouRec/EditorView.swift:1380-1601):
 * how the picture is magnified ([PreviewInspection]), the size of the view it is in and the shape of the movie.
 * It changes what the preview shows only, never the export.
 */
@Stable
internal class InspectionZoom {
    var view by mutableStateOf(PreviewInspection())
    var bounds = Size(0.0, 0.0)
        private set

    /** The movie's frame, width over height; null while it is not known. */
    var canvas: Size? = null
        set(value) {
            if (field != value) { field = value; reset() }
        }

    val fit: Size get() = PreviewInspection.fitted(canvas, bounds)

    fun reset() { view = PreviewInspection() }

    /** The view is laid out at a new size: the picture goes back to 1× (EditorView.swift:1523). */
    fun sized(width: Int, height: Int) {
        val next = Size(width.toDouble(), height.toDouble())
        if (next != bounds) { bounds = next; reset() }
    }
}

/**
 * The view a preview is in, taking the gestures of the inspection zoom while [enabled]: a pinch magnifies the
 * picture about the fingers, a drag moves it once it is magnified (EditorView.swift:1586-1599, 1621-1628), and the
 * screen reader's actions zoom in, zoom out and reset (EditorView.swift:1471-1475). Taps go on to what is inside.
 */
internal fun Modifier.inspecting(zoom: InspectionZoom, enabled: Boolean, labels: InspectionLabels): Modifier {
    val sized = onSizeChanged { zoom.sized(it.width, it.height) }
    if (!enabled) return this.then(sized)
    return this.then(sized).pointerInput(zoom) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var panning = false
            var travelled = Offset.Zero
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2) {
                    val factor = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centre = event.calculateCentroid(useCurrent = true)
                    zoom.view = zoom.view.pinched(factor.toDouble(), centre.x.toDouble(), centre.y.toDouble(), pan.x.toDouble(), pan.y.toDouble(), zoom.bounds, zoom.fit)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    panning = true
                } else if (fingers == 1 && zoom.view.zoomed) {
                    val pan = event.calculatePan()
                    if (!panning) {
                        travelled += pan
                        panning = travelled.getDistance() > slop
                    }
                    if (panning) {
                        zoom.view = zoom.view.panned(pan.x.toDouble(), pan.y.toDouble(), zoom.bounds, zoom.fit)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }.semantics {
        customActions = listOf(
            CustomAccessibilityAction(labels.zoomIn) { zoom.view = zoom.view.stepped(PreviewInspection.STEP, zoom.bounds, zoom.fit); true },
            CustomAccessibilityAction(labels.zoomOut) { zoom.view = zoom.view.stepped(1 / PreviewInspection.STEP, zoom.bounds, zoom.fit); true },
            CustomAccessibilityAction(labels.reset) { zoom.reset(); true },
        )
    }
}

/** The names of the screen reader's actions of the inspection zoom. */
internal data class InspectionLabels(val zoomIn: String, val zoomOut: String, val reset: String)

@Composable
internal fun inspectionLabels() = InspectionLabels(
    stringResource(R.string.preview_zoom_in), stringResource(R.string.preview_zoom_out), stringResource(R.string.preview_zoom_reset),
)

/** The picture magnified and moved as [zoom] has it, about the middle of the view it is centred in. */
internal fun Modifier.inspected(zoom: InspectionZoom): Modifier = graphicsLayer {
    val view = zoom.view
    scaleX = view.zoom.toFloat()
    scaleY = view.zoom.toFloat()
    translationX = view.x.toFloat()
    translationY = view.y.toFloat()
}

/**
 * Reset preview zoom, in a corner of the view while the picture is magnified (EditorView.swift:1430-1434, 1526-1543);
 * with [named], as in the original's tool previews, it says how much: "2.0× · Preview".
 */
@Composable
internal fun InspectionReset(zoom: InspectionZoom, named: Boolean, modifier: Modifier = Modifier) {
    val view = zoom.view
    if (!view.zoomed) return
    TextButton(
        onClick = { zoom.reset() },
        modifier = modifier.padding(8.dp).heightIn(min = 48.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloseFullscreen, stringResource(R.string.preview_zoom_reset), Modifier.size(20.dp), tint = Color.White)
            if (named) Text(
                stringResource(R.string.preview_zoom_named, "%.1f".format(java.util.Locale.ROOT, view.zoom)),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
