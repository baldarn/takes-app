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
    VideoEffectID.Halation to EffectWords(R.string.effect_halation, R.string.effect_halation_summary, R.string.effect_halation_help, R.string.effect_halation_history),
    VideoEffectID.SixteenMM to EffectWords(R.string.effect_sixteen_mm, R.string.effect_sixteen_mm_summary, R.string.effect_sixteen_mm_help, R.string.effect_sixteen_mm_history),
    VideoEffectID.SuperEight to EffectWords(R.string.effect_super_eight, R.string.effect_super_eight_summary, R.string.effect_super_eight_help, R.string.effect_super_eight_history),
    VideoEffectID.Widescreen to EffectWords(R.string.effect_widescreen, R.string.effect_widescreen_summary, R.string.effect_widescreen_help, R.string.effect_widescreen_history),
    VideoEffectID.CrashZoom to EffectWords(R.string.effect_crash_zoom, R.string.effect_crash_zoom_summary, R.string.effect_crash_zoom_help, R.string.effect_crash_zoom_history),
    VideoEffectID.Quantization to EffectWords(R.string.effect_quantization, R.string.effect_quantization_summary, R.string.effect_quantization_help, R.string.effect_quantization_history),
    VideoEffectID.ChromaKey to EffectWords(R.string.effect_chroma_key, R.string.effect_chroma_key_summary, R.string.effect_chroma_key_help, R.string.effect_chroma_key_history),
    VideoEffectID.AutoBackground to EffectWords(R.string.effect_auto_background, R.string.effect_auto_background_summary, R.string.effect_auto_background_help, null),
    VideoEffectID.NightFix to EffectWords(R.string.effect_night_fix, R.string.effect_night_fix_summary, R.string.effect_night_fix_help, null),
    VideoEffectID.FreezeFrame to EffectWords(R.string.effect_freeze_frame, R.string.effect_freeze_frame_summary, R.string.effect_freeze_frame_help, R.string.effect_freeze_frame_history),
    VideoEffectID.StepPrinting to EffectWords(R.string.effect_step_printing, R.string.effect_step_printing_summary, R.string.effect_step_printing_help, R.string.effect_step_printing_history),
    VideoEffectID.Undercranking to EffectWords(R.string.effect_undercranking, R.string.effect_undercranking_summary, R.string.effect_undercranking_help, R.string.effect_undercranking_history),
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
    "hue" -> R.string.effect_control_hue
    "color" -> if (kind == VideoEffectID.SixteenMM) R.string.effect_control_film_color else R.string.effect_control_hue
    "mode" -> when (kind) {
        VideoEffectID.SilentTint -> R.string.effect_control_process
        VideoEffectID.ColorIsolation -> R.string.effect_control_keep
        VideoEffectID.NightFix -> R.string.effect_control_night_mode
        else -> null
    }
    "redDepth" -> R.string.effect_control_red_depth
    "warmth" -> R.string.effect_control_warmth
    "strength" -> if (kind == VideoEffectID.ChromaKey) R.string.effect_control_key_strength else R.string.effect_control_strength
    "darkness" -> R.string.effect_control_darkness
    "blue" -> R.string.effect_control_blue
    "moonlight" -> R.string.effect_control_moonlight
    "range" -> R.string.effect_control_hue_range
    "softness" -> if (kind == VideoEffectID.ChromaKey || kind == VideoEffectID.AutoBackground) R.string.effect_control_edge_softness else R.string.effect_control_softness
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
    // Halation.
    "glow" -> R.string.effect_control_glow
    "red" -> R.string.effect_control_halation_red
    "flash" -> R.string.effect_control_shadow_lift
    "flashColor" -> R.string.effect_control_flash_color
    // 16mm and Super 8.
    "grainSize" -> R.string.effect_control_grain_size
    "weave" -> if (kind == VideoEffectID.SuperEight) R.string.effect_control_weave else R.string.effect_control_gate_weave
    "frameEdge" -> R.string.effect_control_frame_edge
    "fade" -> R.string.effect_control_fade
    "fps" -> R.string.effect_control_frame_rate
    "leaks" -> R.string.effect_control_light_leaks
    "border" -> R.string.effect_control_frame_border
    // Widescreen 1953.
    "ratio" -> R.string.effect_control_ratio
    "bars" -> R.string.effect_control_bars
    "flares" -> R.string.effect_control_flares
    "flareColor" -> R.string.effect_control_flare_color
    "ovalBokeh" -> R.string.effect_control_oval_bokeh
    // Crash Zoom.
    "zoom" -> R.string.effect_control_zoom_amount
    "speed" -> when (kind) {
        VideoEffectID.CrashZoom -> R.string.effect_control_push_duration
        VideoEffectID.Undercranking -> R.string.effect_control_speed_up
        VideoEffectID.StepPrinting -> R.string.effect_control_speed
        else -> null
    }
    "hold" -> when (kind) {
        VideoEffectID.CrashZoom -> R.string.effect_control_hold
        VideoEffectID.FreezeFrame -> R.string.effect_control_hold_duration
        else -> null
    }
    // The time effects.
    "push" -> R.string.effect_control_push_in
    "style" -> R.string.effect_control_style
    "audio" -> R.string.effect_control_audio
    "step" -> R.string.effect_control_step_frames
    "smear" -> R.string.effect_control_smear
    "flicker" -> R.string.effect_control_flicker
    "mute" -> R.string.effect_control_mute_audio
    "return" -> R.string.effect_control_return
    "motionBlur" -> R.string.effect_control_motion_blur
    "targetX" -> if (kind == VideoEffectID.CrashZoom) R.string.effect_control_target_x else null
    "targetY" -> if (kind == VideoEffectID.CrashZoom) R.string.effect_control_target_y else null
    "trackFace" -> R.string.effect_control_follow_face
    // Quantization.
    "colors" -> R.string.effect_control_colors
    "monochrome" -> R.string.effect_control_monochrome
    "palette" -> R.string.effect_control_palette
    "smoothing" -> R.string.effect_control_smoothing
    "dithering" -> R.string.effect_control_dithering
    "faceDetail" -> R.string.effect_control_face_detail
    "lockPalette" -> R.string.effect_control_lock_palette
    // Chroma Key and its background.
    "screenColor" -> R.string.effect_control_screen_color
    "screenType" -> R.string.effect_control_screen_type
    "autoScreen" -> R.string.effect_control_auto_screen
    "balance" -> R.string.effect_control_balance
    "blackClip" -> R.string.effect_control_black_clip
    "whiteClip" -> R.string.effect_control_white_clip
    "grow" -> R.string.effect_control_grow
    "despill" -> R.string.effect_control_despill
    "wrap" -> R.string.effect_control_light_wrap
    "personHint" -> R.string.effect_control_person_hint
    "portraitBlur" -> R.string.effect_control_portrait_blur
    "cleanPlate" -> R.string.effect_control_clean_plate
    "view" -> R.string.effect_control_view
    "background" -> R.string.effect_control_background
    "backgroundColor" -> R.string.effect_control_background_color
    "media" -> R.string.effect_control_background_media
    "fit" -> R.string.effect_control_framing
    "backgroundX" -> R.string.effect_control_background_x
    "backgroundY" -> R.string.effect_control_background_y
    "backgroundZoom" -> R.string.effect_control_background_zoom
    "backgroundBlur" -> R.string.effect_control_background_blur
    "backgroundBrightness" -> R.string.effect_control_background_brightness
    "backgroundAudio" -> R.string.effect_control_background_audio
    "matchColor" -> R.string.effect_control_match_color
    "matchStrength" -> R.string.effect_control_match_strength
    // Night fix.
    "brightness" -> R.string.effect_control_shadow_brightness
    "reduction" -> R.string.effect_control_noise_reduction
    "detail" -> R.string.effect_control_preserve_detail
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
    "Neutral" to R.string.effect_choice_neutral, "Warm" to R.string.effect_choice_warm, "Cool" to R.string.effect_choice_cool, "Fine" to R.string.effect_choice_fine,
    "Medium" to R.string.effect_choice_medium, "Coarse" to R.string.effect_choice_coarse, "Color" to R.string.effect_choice_color, "B&W" to R.string.effect_choice_bw,
    "Black" to R.string.effect_choice_black, "Blur fill" to R.string.effect_choice_blur_fill, "Blue" to R.string.effect_choice_blue, "Off" to R.string.effect_choice_off,
    "Ordered 4x4" to R.string.effect_choice_ordered_4, "Ordered 8x8" to R.string.effect_choice_ordered_8, "From scene" to R.string.effect_choice_from_scene, "Pop 1" to R.string.effect_choice_pop_1,
    "Pop 2" to R.string.effect_choice_pop_2, "Pop 3" to R.string.effect_choice_pop_3, "Saved" to R.string.effect_choice_saved, "Auto" to R.string.effect_choice_auto,
    "Green" to R.string.effect_choice_green, "Result" to R.string.effect_choice_result, "Matte" to R.string.effect_choice_matte, "Original" to R.string.effect_choice_original,
    "Solid" to R.string.effect_choice_solid, "Image" to R.string.effect_choice_image, "Blur original" to R.string.effect_choice_blur_original,
    "Studio" to R.string.effect_choice_studio, "Window" to R.string.effect_choice_window, "Fill" to R.string.effect_choice_fill,
    "Fit" to R.string.effect_choice_fit, "Fast" to R.string.effect_choice_fast,
    "Clean" to R.string.effect_choice_clean, "Print" to R.string.effect_choice_print, "Cut" to R.string.effect_choice_cut,
    "Continue" to R.string.effect_choice_continue, "Fade out over hold" to R.string.effect_choice_fade_out_over_hold,
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
