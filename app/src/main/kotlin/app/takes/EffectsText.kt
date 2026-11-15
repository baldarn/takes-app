package app.takes

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.takes.core.EffectParameter
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectLibrarySection
import app.takes.core.historicalHelp
import app.takes.core.summary
import app.takes.core.usageHelp
import java.text.NumberFormat
import kotlin.math.roundToInt

/*
 * The words of the Video effects tool: the catalogue's names, lines and help
 * (app.takes.core.VideoEffectCatalog), translated. An effect this release
 * does not offer, met in a project made elsewhere, keeps the catalogue's English.
 */

/** The resources of one effect: name, line in the library, help, and its film history where it has one. */
private class EffectWords(@StringRes val name: Int, @StringRes val summary: Int, @StringRes val help: Int, @StringRes val history: Int?)

private val WORDS: Map<VideoEffectID, EffectWords> = mapOf(
    VideoEffectID.ColorCorrection to EffectWords(R.string.effect_color_correction, R.string.effect_color_correction_summary, R.string.effect_color_correction_help, null),
    VideoEffectID.SilentTint to EffectWords(R.string.effect_silent_tint, R.string.effect_silent_tint_summary, R.string.effect_silent_tint_help, R.string.effect_silent_tint_history),
    VideoEffectID.ThreeStrip to EffectWords(R.string.effect_three_strip, R.string.effect_three_strip_summary, R.string.effect_three_strip_help, R.string.effect_three_strip_history),
    VideoEffectID.BleachBypass to EffectWords(R.string.effect_bleach_bypass, R.string.effect_bleach_bypass_summary, R.string.effect_bleach_bypass_help, R.string.effect_bleach_bypass_history),
    VideoEffectID.DayForNight to EffectWords(R.string.effect_day_for_night, R.string.effect_day_for_night_summary, R.string.effect_day_for_night_help, R.string.effect_day_for_night_history),
    VideoEffectID.Noir to EffectWords(R.string.effect_noir, R.string.effect_noir_summary, R.string.effect_noir_help, R.string.effect_noir_history),
    VideoEffectID.Expressionism to EffectWords(R.string.effect_expressionism, R.string.effect_expressionism_summary, R.string.effect_expressionism_help, R.string.effect_expressionism_history),
    VideoEffectID.ColorIsolation to EffectWords(R.string.effect_color_isolation, R.string.effect_color_isolation_summary, R.string.effect_color_isolation_help, R.string.effect_color_isolation_history),
    VideoEffectID.RgbOffset to EffectWords(R.string.effect_rgb_offset, R.string.effect_rgb_offset_summary, R.string.effect_rgb_offset_help, R.string.effect_rgb_offset_history),
    VideoEffectID.NegativeStrobe to EffectWords(R.string.effect_negative_strobe, R.string.effect_negative_strobe_summary, R.string.effect_negative_strobe_help, R.string.effect_negative_strobe_history),
    VideoEffectID.Kaleidoscope to EffectWords(R.string.effect_kaleidoscope, R.string.effect_kaleidoscope_summary, R.string.effect_kaleidoscope_help, R.string.effect_kaleidoscope_history),
)

@Composable
internal fun effectName(kind: VideoEffectID): String = WORDS[kind]?.let { stringResource(it.name) } ?: kind.descriptor.displayName

@Composable
internal fun effectSummary(kind: VideoEffectID): String = WORDS[kind]?.let { stringResource(it.summary) } ?: kind.summary

@Composable
internal fun effectHelp(kind: VideoEffectID): String = WORDS[kind]?.let { stringResource(it.help) } ?: kind.usageHelp

@Composable
internal fun effectHistory(kind: VideoEffectID): String? {
    val words = WORDS[kind] ?: return kind.historicalHelp
    return words.history?.let { stringResource(it) }
}

@StringRes
private fun controlTitle(kind: VideoEffectID, id: String): Int? = when (id) {
    "intensity" -> R.string.effect_control_intensity
    "exposure" -> R.string.effect_control_exposure
    "contrast" -> R.string.effect_control_contrast
    "shadows" -> R.string.effect_control_shadows
    "highlights" -> R.string.effect_control_highlights
    "blacks" -> R.string.effect_control_blacks
    "whites" -> R.string.effect_control_whites
    "pivot" -> R.string.effect_control_pivot
    "temperature" -> R.string.effect_control_temperature
    "tint" -> if (kind == VideoEffectID.ColorCorrection) R.string.effect_control_tint else R.string.effect_control_film_tint
    "saturation" -> R.string.effect_control_saturation
    "vibrance" -> R.string.effect_control_vibrance
    "hue", "color" -> R.string.effect_control_hue
    "mode" -> if (kind == VideoEffectID.SilentTint) R.string.effect_control_process else if (kind == VideoEffectID.ColorIsolation) R.string.effect_control_keep else null
    "redDepth" -> R.string.effect_control_red_depth
    "warmth" -> R.string.effect_control_warmth
    "strength" -> R.string.effect_control_strength
    "darkness" -> R.string.effect_control_darkness
    "blue" -> R.string.effect_control_blue
    "moonlight" -> R.string.effect_control_moonlight
    "range" -> R.string.effect_control_hue_range
    "softness" -> R.string.effect_control_softness
    "crush" -> R.string.effect_control_black_crush
    "grain" -> R.string.effect_control_grain
    "shadowColor" -> R.string.effect_control_blue_shadows
    "vignette" -> R.string.effect_control_vignette
    "iris" -> R.string.effect_control_iris
    "redX" -> R.string.effect_control_red_x
    "redY" -> R.string.effect_control_red_y
    "greenX" -> R.string.effect_control_green_x
    "greenY" -> R.string.effect_control_green_y
    "blueX" -> R.string.effect_control_blue_x
    "blueY" -> R.string.effect_control_blue_y
    "frequency" -> R.string.effect_control_frequency
    "timedBurst" -> R.string.effect_control_timed_burst
    "burstLength" -> R.string.effect_control_burst_length
    "divisions" -> R.string.effect_control_divisions
    "centerX" -> R.string.effect_control_center_x
    "centerY" -> R.string.effect_control_center_y
    "splitAngle" -> R.string.effect_control_split_angle
    "sourcePanX" -> R.string.effect_control_source_pan_x
    "sourcePanY" -> R.string.effect_control_source_pan_y
    "sourceAngle" -> R.string.effect_control_source_angle
    else -> null
}

/** A control's name; only the offered effects' controls are translated. */
@Composable
internal fun controlName(kind: VideoEffectID, parameter: EffectParameter): String =
    controlTitle(kind, parameter.id)?.takeIf { kind in AndroidVideoEffects.offered }?.let { stringResource(it) } ?: parameter.title

private val CHOICES: Map<String, Int> = mapOf(
    "Night Blue" to R.string.effect_choice_night_blue, "Amber" to R.string.effect_choice_amber, "Fire Red" to R.string.effect_choice_fire_red,
    "Nature Green" to R.string.effect_choice_nature_green, "Sepia" to R.string.effect_choice_sepia,
    "Tint" to R.string.effect_choice_tint, "Tone" to R.string.effect_choice_tone,
    "Oval" to R.string.effect_choice_oval, "Circle" to R.string.effect_choice_circle,
    "A color" to R.string.effect_choice_a_color, "The subject" to R.string.effect_choice_the_subject,
)

/** A choice of a picker as it is shown; the saved value stays the original's English. */
@Composable
internal fun choiceName(kind: VideoEffectID, choice: String): String =
    CHOICES[choice]?.takeIf { kind in AndroidVideoEffects.offered }?.let { stringResource(it) } ?: choice

/** What a Color Correction control does, shown when its name is held. */
@StringRes
internal fun correctionHelp(id: String): Int = when (id) {
    "exposure" -> R.string.correction_help_exposure
    "contrast" -> R.string.correction_help_contrast
    "shadows" -> R.string.correction_help_shadows
    "highlights" -> R.string.correction_help_highlights
    "blacks" -> R.string.correction_help_blacks
    "whites" -> R.string.correction_help_whites
    "pivot" -> R.string.correction_help_pivot
    "temperature" -> R.string.correction_help_temperature
    "tint" -> R.string.correction_help_tint
    "saturation" -> R.string.correction_help_saturation
    "vibrance" -> R.string.correction_help_vibrance
    "hue" -> R.string.correction_help_hue
    else -> R.string.correction_help_intensity
}

/** How the library shows a section: its name and line, symbol and colour. VideoEffectsEditor.swift:41-43. */
internal class SectionLook(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector, val tint: Color)

internal val VideoEffectLibrarySection.look: SectionLook
    get() = when (this) {
        VideoEffectLibrarySection.ColorStyle -> SectionLook(R.string.effects_section_color_style, R.string.effects_section_color_style_summary, Icons.Outlined.Palette, Color(0xFFFF9F0A))
        VideoEffectLibrarySection.CameraFrame -> SectionLook(R.string.effects_section_camera_frame, R.string.effects_section_camera_frame_summary, Icons.Outlined.CameraAlt, Color(0xFF64D2FF))
        VideoEffectLibrarySection.Time -> SectionLook(R.string.effects_section_time, R.string.effects_section_time_summary, Icons.Outlined.Timer, Color(0xFFBF5AF2))
        VideoEffectLibrarySection.Background -> SectionLook(R.string.effects_section_background, R.string.effects_section_background_summary, Icons.Outlined.Wallpaper, Color(0xFF6AC4DC))
    }

/** Up to two decimals, in the reader's way of writing numbers. */
internal fun effectNumber(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(if (kotlin.math.abs(value) < 0.000005) 0.0 else value)

/** A number control's value as its row shows it. VideoEffectsEditor.swift:1218-1228, KaleidoscopeGuide.swift:20-22. */
internal fun effectValueLabel(kind: VideoEffectID, id: String, value: Double): String = when {
    kind == VideoEffectID.Kaleidoscope && id != "divisions" ->
        if (id == "splitAngle" || id == "sourceAngle") "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%"
    id == "intensity" -> "${(value * 100).toInt()}%"
    id == "range" -> effectNumber(value) + "°"
    else -> effectNumber(value)
}
