package app.takes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.Point
import app.takes.core.RGBChannel
import app.takes.core.RGBOffsetPad
import app.takes.core.VideoEffectSettings

private val Soft = Color(0xFFA0A4AB)

/**
 * RGB Offset's three pads, one for each channel: a cross on a circle whose edge is the largest offset, dragged to
 * move the channel and double-tapped to centre it, in place of the channel's X and Y sliders.
 * [at] is the second the offsets are read at; [mirrored] when the preview shows the picture mirrored (the
 * recorder's selfie preview); [change] sets a channel's two numbers at once. RGBOffsetPads.swift:3-69.
 */
@Composable
internal fun RgbOffsetPads(effect: VideoEffectSettings, at: Double, mirrored: Boolean, enabled: Boolean = true, change: (Map<String, Double>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.Top) {
            for (channel in RGBChannel.entries) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Pad(effect, channel, at, mirrored, enabled, change)
                }
            }
        }
        Text(stringResource(R.string.rgb_pads_hint), color = Soft, fontSize = 12.sp)
    }
}

@Composable
private fun Pad(effect: VideoEffectSettings, channel: RGBChannel, at: Double, mirrored: Boolean, enabled: Boolean, change: (Map<String, Double>) -> Unit) {
    val tint = when (channel) { RGBChannel.Red -> Color.Red; RGBChannel.Green -> Color.Green; RGBChannel.Blue -> Color(0xFF0A84FF) }
    val name = stringResource(when (channel) { RGBChannel.Red -> R.string.effect_channel_red; RGBChannel.Green -> R.string.effect_channel_green; RGBChannel.Blue -> R.string.effect_channel_blue })
    val sx = effect.number(channel.x, at)
    val sy = effect.number(channel.y, at)
    val shown = RGBOffsetPad.displayed(sx, sy, mirrored)
    val (px, py) = RGBOffsetPad.reading(sx, sy, mirrored)
    val current by rememberUpdatedState(shown)
    val send by rememberUpdatedState(change)
    fun place(point: Point) {
        if (!enabled) return
        val offset = RGBOffsetPad.place(point, mirrored)
        send(mapOf(channel.x to offset.x, channel.y to offset.y))
    }
    val label = stringResource(R.string.rgb_pad_label, name)
    val reading = stringResource(R.string.rgb_pad_reading, px, py)
    val moves = listOf(
        stringResource(R.string.rgb_pad_left) to Point(-RGBOffsetPad.STEP, 0.0), stringResource(R.string.rgb_pad_right) to Point(RGBOffsetPad.STEP, 0.0),
        stringResource(R.string.rgb_pad_up) to Point(0.0, -RGBOffsetPad.STEP), stringResource(R.string.rgb_pad_down) to Point(0.0, RGBOffsetPad.STEP),
    )
    val center = stringResource(R.string.rgb_pad_center)
    Canvas(
        Modifier.widthIn(max = 108.dp).fillMaxWidth().aspectRatio(1f)
            .pointerInput(enabled) {
                // The pad's reach: its radius less the cross's half width. RGBOffsetPads.swift:46-47.
                fun point(at: Offset): Point {
                    val reach = maxOf(1f, minOf(size.width, size.height) / 2f - 10.dp.toPx())
                    return Point(((at.x - size.width / 2f) / reach).toDouble(), ((at.y - size.height / 2f) / reach).toDouble())
                }
                detectDragGestures(onDragStart = { place(point(it)) }) { event, _ -> place(point(event.position)) }
            }
            .pointerInput(enabled) { detectTapGestures(onDoubleTap = { place(Point(0.0, 0.0)) }) }
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = reading
                customActions = moves.map { (word, step) ->
                    CustomAccessibilityAction(word) { place(Point(current.x + step.x, current.y + step.y)); true }
                } + CustomAccessibilityAction(center) { place(Point(0.0, 0.0)); true }
            },
    ) {
        val middle = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f
        val reach = maxOf(1f, radius - 10.dp.toPx())
        drawCircle(tint.copy(alpha = 0.12f), radius, middle)
        drawCircle(tint.copy(alpha = 0.8f), radius - 0.5.dp.toPx(), middle, style = Stroke(1.dp.toPx()))
        drawCircle(tint.copy(alpha = 0.22f), radius - reach * 0.48f - 0.5.dp.toPx(), middle, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.4f), 1.5.dp.toPx(), middle)
        val cross = Offset(middle.x + current.x.toFloat() * reach, middle.y + current.y.toFloat() * reach)
        val arm = 7.dp.toPx()
        val thick = 2.5.dp.toPx()
        for ((colour, width) in listOf(Color.Black.copy(alpha = 0.6f) to thick + 2.dp.toPx(), Color.White to thick)) {
            drawLine(colour, Offset(cross.x - arm, cross.y), Offset(cross.x + arm, cross.y), width)
            drawLine(colour, Offset(cross.x, cross.y - arm), Offset(cross.x, cross.y + arm), width)
        }
    }
    Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.clearAndSetSemantics { })
}
