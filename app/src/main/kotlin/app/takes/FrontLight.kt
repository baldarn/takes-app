package app.takes

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.takes.core.FrontLight
import kotlin.math.roundToInt

private fun FrontLight.Rgb.color() = Color(red.toFloat(), green.toFloat(), blue.toFloat())

@Composable
private fun name(width: FrontLight.Width) = stringResource(when (width) {
    FrontLight.Width.Thin -> R.string.recorder_light_thin
    FrontLight.Width.Medium -> R.string.recorder_light_medium
    FrontLight.Width.Wide -> R.string.recorder_light_wide
})

@Composable
private fun name(tone: FrontLight.Tone) = stringResource(when (tone) {
    FrontLight.Tone.White -> R.string.recorder_light_white
    FrontLight.Tone.Warm -> R.string.recorder_light_warm
    FrontLight.Tone.Cool -> R.string.recorder_light_cool
    FrontLight.Tone.Custom -> R.string.recorder_light_custom
})

/**
 * The front light, as in the original: a ring of light at the edge of the recorder that lights the face in
 * front of the display. It belongs to the interface and is never recorded. While it is on the screen is at
 * full brightness; the bulb in the corner opens its width, colour and opacity, also during a take.
 */
@Composable
internal fun FrontLightRing(settings: RecorderSettings, cornerRadius: Dp, modifier: Modifier = Modifier, turnOff: () -> Unit) {
    val width = settings.frontLightWidth
    val tone = settings.frontLightTone
    val opacity = settings.frontLightOpacity
    var open by remember { mutableStateOf(false) }
    // Only this window, and only while the light shows: leaving the recorder returns the brightness to the system.
    val window = LocalActivity.current?.window
    val bright = opacity > 0
    DisposableEffect(window, bright) {
        fun brightness(value: Float) { window?.let { it.attributes = it.attributes.apply { screenBrightness = value } } }
        if (bright) brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        onDispose { brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val thickness = FrontLight.thickness(maxWidth.value.toDouble(), maxHeight.value.toDouble(), width).dp
        val opening = FrontLight.openingRadius(cornerRadius.value.toDouble(), thickness.value.toDouble()).dp
        val light = FrontLight.color(tone, settings.frontLightHue).color().copy(alpha = opacity.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            val inset = thickness.toPx()
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset)), CornerRadius(opening.toPx())))
            }
            drawPath(path, light)
        }
        // Against the corner at the end of the bottom row. The recorder ends above the tabs, where the corner is square.
        val inset = FrontLight.bulbInset(0.0).dp
        val label = stringResource(R.string.recorder_light_adjust)
        val value = stringResource(R.string.recorder_light_value, name(width), name(tone), (opacity * 100).roundToInt())
        Box(
            Modifier.align(Alignment.BottomEnd).offset(-(inset - FrontLight.BULB_RADIUS.dp), -(inset - FrontLight.BULB_RADIUS.dp))
                .size((FrontLight.BULB_RADIUS * 2).dp).clip(CircleShape).clickable(role = Role.Button) { open = true }
                .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = value },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(30.dp).background(Color(0xFF212121), CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lightbulb, null, Modifier.size(17.dp), tint = Color.White)
            }
            if (open) {
                Popup(
                    alignment = Alignment.BottomEnd, offset = IntOffset(0, -(FrontLight.BULB_RADIUS * 2 * LocalDensity.current.density).roundToInt()),
                    onDismissRequest = { open = false }, properties = PopupProperties(focusable = true),
                ) {
                    FrontLightControls(settings, close = { open = false }, turnOff = { open = false; turnOff() })
                }
            }
        }
    }
}

@Composable
private fun FrontLightControls(settings: RecorderSettings, close: () -> Unit, turnOff: () -> Unit) {
    val tone = settings.frontLightTone
    Column(
        Modifier.width(264.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1A1A)).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recorder_light), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            Box(Modifier.offset(x = 12.dp).size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = close), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, stringResource(R.string.recorder_light_close), Modifier.size(20.dp), tint = Color.White)
            }
        }
        Label(stringResource(R.string.recorder_light_width))
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(9.dp)).padding(2.dp), Arrangement.spacedBy(2.dp)) {
            FrontLight.Width.entries.forEach { width ->
                val chosen = width == settings.frontLightWidth
                Box(
                    Modifier.weight(1f).heightIn(min = 32.dp).clip(RoundedCornerShape(7.dp)).background(if (chosen) Color(0xFF636366) else Color.Transparent)
                        .clickable(role = Role.RadioButton) { settings.frontLightWidth = width }.semantics { selected = chosen },
                    contentAlignment = Alignment.Center,
                ) { Text(name(width), color = Color.White, fontSize = 13.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        Label(stringResource(R.string.recorder_light_color))
        // Each colour is as wide as its name, so a long one ("Personale") is never cut.
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            FrontLight.Tone.entries.forEach { option ->
                val chosen = option == tone
                Column(
                    Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.RadioButton) { settings.frontLightTone = option }.semantics(mergeDescendants = true) { selected = chosen },
                    Arrangement.spacedBy(3.dp, Alignment.CenterVertically), Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(28.dp).background(FrontLight.color(option, settings.frontLightHue).color(), CircleShape), contentAlignment = Alignment.Center) {
                        if (option == FrontLight.Tone.Custom) {
                            Box(Modifier.fillMaxSize().border(3.dp, Brush.sweepGradient(RAINBOW), CircleShape))
                        }
                        if (chosen) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Color.Black)
                    }
                    Text(name(option), color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        if (tone == FrontLight.Tone.Custom) {
            // Android has no system colour picker: the custom colour is a hue, pale enough to light a face.
            val hue = stringResource(R.string.recorder_light_hue)
            Slider(
                settings.frontLightHue.toFloat(), { settings.frontLightHue = FrontLight.hue(it.toDouble().coerceIn(0.0, 359.0)) },
                Modifier.semantics { contentDescription = hue }, valueRange = 0f..359f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = FrontLight.color(tone, settings.frontLightHue).color()),
            )
        }
        Row {
            Label(stringResource(R.string.recorder_light_opacity), Modifier.weight(1f))
            Text("${(settings.frontLightOpacity * 100).roundToInt()}%", color = Color(0xFF98989F), fontSize = 14.sp)
        }
        val opacity = stringResource(R.string.recorder_light_opacity_label)
        Slider(
            settings.frontLightOpacity.toFloat(), { settings.frontLightOpacity = FrontLight.opacity(it.toDouble()) },
            Modifier.semantics { contentDescription = opacity }, steps = (1 / FrontLight.OPACITY_STEP).roundToInt() - 1,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SettingsCyan, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.09f)).clickable(role = Role.Button, onClick = turnOff),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.recorder_light_off), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color(0xFFAF52DE), Color.Red)
