package app.takes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import app.takes.core.DeviceTurn

/**
 * How the recorder's controls stand: the quarter the phone is held in, as the orientation sensor names it,
 * and the angle the controls are drawn at on their way there.
 */
internal class Turn(val quarter: Int, val angle: () -> Float) {
    /** The phone is on one of its long sides. */
    val sideways: Boolean get() = DeviceTurn.sideways(quarter)
}

internal val LocalTurn = compositionLocalOf { Turn(0) { 0f } }

/** The turn for the phone held in [quarter]. The controls go there the short way round, in a quarter of a second. */
@Composable
internal fun rememberTurn(quarter: Int): Turn {
    val reached = remember { floatArrayOf(DeviceTurn.controls(quarter).toFloat()) }
    val target = remember(quarter) { DeviceTurn.shortest(reached[0], DeviceTurn.controls(quarter)).also { reached[0] = it } }
    val angle by animateFloatAsState(target, tween(250), label = "turn")
    return remember(quarter) { Turn(quarter) { angle } }
}

/**
 * Turns a control with the phone, in its place: the screen stays portrait, as the activity is, and each
 * control stands upright for the person. For a control about as wide as it is tall. A touch is turned
 * with it, so that "up" on a control that is dragged stays up for the person.
 */
@Composable
internal fun Modifier.turned(): Modifier {
    val turn = LocalTurn.current
    return graphicsLayer { rotationZ = turn.angle() }
}

/**
 * Turns a control with the phone and gives it the room it takes when it stands across the screen: its
 * width becomes its height. For a control that is wider than tall, like the clock.
 */
@Composable
internal fun Modifier.turnedBounds(): Modifier {
    val turn = LocalTurn.current
    return layout { measurable, constraints ->
        val across = turn.sideways
        val placeable = measurable.measure(
            if (across) Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth) else constraints,
        )
        val width = if (across) placeable.height else placeable.width
        val height = if (across) placeable.width else placeable.height
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) { rotationZ = turn.angle() }
        }
    }
}
