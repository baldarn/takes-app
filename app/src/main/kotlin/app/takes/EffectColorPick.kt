package app.takes

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.EffectRGB
import app.takes.core.KaleidoscopeGuide
import app.takes.core.LiveVideoEffects
import app.takes.core.Point
import app.takes.core.ViewLayer

/**
 * A colour of an effect picked from the editor's preview while the Video effects tool picks one: a tap on the
 * picture takes the median of the 9 × 9 pixels around it. [sample] gives the colour under a point of the preview of
 * [width] × [height], or null outside the picture. What to tap, and Cancel, are in [EffectPickBar].
 * VideoEffectsEditor.swift:988-1002, 865-880.
 */
@Composable
internal fun EffectColorPick(work: EffectsWork, playhead: () -> Double, sample: (Offset, Float, Float) -> EffectRGB?, change: (EffectsWork) -> Unit) {
    if (work.picking == null) return
    val latest by rememberUpdatedState(work)
    val send by rememberUpdatedState(change)
    val take by rememberUpdatedState(sample)
    val clock by rememberUpdatedState(playhead)
    val hint = pickHint(work)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { at -> send(latest.picked(take(at, size.width.toFloat(), size.height.toFloat()), clock())) }
        }.semantics { contentDescription = hint },
    )
}

/**
 * The bar at the foot of the preview while a colour is picked: what to tap, or why the last tap was refused, the
 * effect it is for, and Cancel. Across the whole preview rather than the picture, so that on a narrow portrait picture
 * it neither hides the picture nor loses Cancel. VideoEffectsEditor.swift:865-880.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EffectPickBar(work: EffectsWork, playhead: () -> Double, change: (EffectsWork) -> Unit, modifier: Modifier = Modifier) {
    if (work.picking == null) return
    val hint = pickHint(work)
    val input = work.current(playhead())?.let { stringResource(R.string.effects_pick_input, effectName(it.kind)) }.orEmpty()
    // Cancel under the words where the two do not fit on a line.
    FlowRow(
        modifier.fillMaxWidth().padding(8.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(vertical = 6.dp).widthIn(min = 120.dp)) {
            Text(hint, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (input.isNotEmpty()) Text(input, color = Color(0xFFA0A4AB), fontSize = 11.sp)
        }
        TextButton(onClick = { change(work.cancellingPick()) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_cancel)) }
    }
}

/** What the person is asked to tap, or why the last tap was refused. */
@Composable
private fun pickHint(work: EffectsWork): String = stringResource(
    when (work.pickRefusal) {
        EffectsWork.PickRefusal.Outside -> R.string.effects_tap_inside
        EffectsWork.PickRefusal.NotScreen -> R.string.live_effects_screen_invalid
        EffectsWork.PickRefusal.NoHue -> R.string.isolation_invalid_hue
        null -> if (work.picking == "screenColor") R.string.effects_tap_screen else R.string.effects_tap_color
    },
)

/** Reads a colour from what a deck of the editor shows. */
internal object EffectColorSampling {
    /**
     * The colour under [at] of a preview of [width] × [height]: the median of each channel over the 9 × 9 pixels
     * around it of the frame [player] shows (LiveVideoEffects.medianColor, VideoEffectAssets.swift:121-132), the
     * point taken back through the deck's framing [layer]; through the clip's [color] grade when it has one that is not a
     * table nor moves. Null outside the picture, or where the deck does not draw in a texture.
     */
    fun sample(player: Player?, layer: ViewLayer?, at: Offset, width: Float, height: Float, color: ColorSettings?): EffectRGB? {
        val (view, texture) = player?.let(PreviewViews::of) ?: return null
        if (texture.width <= 0 || texture.height <= 0) return null
        val unit = Point(at.x.toDouble(), at.y.toDouble()).applying(KaleidoscopeGuide.viewTransform(width.toDouble(), height.toDouble(), layer).inverted())
        // Where the texture lies in the deck's view: the player fits the picture in it.
        var left = 0f
        var top = 0f
        var node: View? = texture
        while (node != null && node !== view) {
            left += node.left
            top += node.top
            node = node.parent as? View
        }
        val x = unit.x * view.width - left
        val y = unit.y * view.height - top
        if (x < 0 || y < 0 || x >= texture.width || y >= texture.height) return null
        val bitmap = texture.getBitmap(texture.width, texture.height) ?: return null
        try {
            val patch = LiveVideoEffects.patch(x / (texture.width - 1).coerceAtLeast(1), y / (texture.height - 1).coerceAtLeast(1), bitmap.width, bitmap.height)
            val w = patch[2] - patch[0]
            val h = patch[3] - patch[1]
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, patch[0], patch[1], w, h)
            val rgba = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                rgba[i * 4] = (pixels[i] shr 16 and 0xff).toByte()
                rgba[i * 4 + 1] = (pixels[i] shr 8 and 0xff).toByte()
                rgba[i * 4 + 2] = (pixels[i] and 0xff).toByte()
                rgba[i * 4 + 3] = -1
            }
            val raw = LiveVideoEffects.medianColor(rgba, w, h)
            val grade = color?.takeIf { it.imported == null && it.motion == null } ?: return raw
            val graded = ColorGrade.apply(grade, null, raw.red, raw.green, raw.blue)
            return EffectRGB(graded[0], graded[1], graded[2])
        } finally {
            bitmap.recycle()
        }
    }
}
