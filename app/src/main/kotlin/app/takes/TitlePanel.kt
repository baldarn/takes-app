package app.takes

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takes.core.EditState
import app.takes.core.EditorDurationPresets
import app.takes.core.ImportedFontFile
import app.takes.core.OverlayOpening
import app.takes.core.OverlayTiming
import app.takes.core.Size
import app.takes.core.TextColor
import app.takes.core.TextOverlay
import app.takes.core.TitleAlignment
import app.takes.core.TitleDesign
import app.takes.core.TitleDirection
import app.takes.core.TitleGradient
import app.takes.core.TitleInk
import app.takes.core.TitleLibrary
import app.takes.core.TitleLookCollection
import app.takes.core.TitleMotion
import app.takes.core.TitleMove
import app.takes.core.TitlePlate
import app.takes.core.TimelineEditing
import app.takes.core.TitleTypeface
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)
private val Accent = Color(0xFF4DD0E1)
private val Chosen = Accent.copy(alpha = 0.22f)
private val Plain = Color.White.copy(alpha = 0.07f)
private val Rounded = RoundedCornerShape(8.dp)

private enum class TitleTab(val label: Int, val icon: ImageVector) {
    Looks(R.string.title_tab_looks, Icons.Outlined.GridView),
    Font(R.string.title_tab_font, Icons.Outlined.TextFormat),
    Fill(R.string.title_tab_fill, Icons.Outlined.Palette),
    Outline(R.string.title_tab_outline, Icons.Outlined.Circle),
    Shadow(R.string.title_tab_shadow, Icons.Outlined.Contrast),
    Background(R.string.title_tab_background, Icons.Filled.Rectangle),
    Motion(R.string.title_tab_motion, Icons.AutoMirrored.Filled.KeyboardTab),
}

/** The built-in looks and gradients are named in the model in English; these are their names on screen. */
private fun lookLabel(name: String): Int? = when (name) {
    "Clean" -> R.string.title_look_clean
    "Field report" -> R.string.title_look_field_report
    "Dateline" -> R.string.title_look_dateline
    "Caption box" -> R.string.title_look_caption_box
    "Pull quote" -> R.string.title_look_pull_quote
    "Interview" -> R.string.title_look_interview
    "Film title" -> R.string.title_look_film
    "Opening credit" -> R.string.title_look_opening_credit
    "Intertitle" -> R.string.title_look_intertitle
    "Daylight" -> R.string.title_look_daylight
    "Night drive" -> R.string.title_look_night_drive
    "Matinée" -> R.string.title_look_matinee
    "Headline" -> R.string.title_look_headline
    "Magazine italic" -> R.string.title_look_magazine_italic
    "Source note" -> R.string.title_look_source_note
    "Masthead" -> R.string.title_look_masthead
    "Clothbound" -> R.string.title_look_clothbound
    "Field note" -> R.string.title_look_field_note
    "Gaffer tape" -> R.string.title_look_gaffer_tape
    "Highlighter" -> R.string.title_look_highlighter
    "Afterglow" -> R.string.title_look_afterglow
    "Wide type" -> R.string.title_look_wide_type
    "Flyer" -> R.string.title_look_flyer
    "Lilac gel" -> R.string.title_look_lilac_gel
    else -> null
}

private fun collectionLabel(collection: TitleLookCollection): Int = when (collection) {
    TitleLookCollection.Editorial -> R.string.title_collection_editorial
    TitleLookCollection.Cinema -> R.string.title_collection_cinema
    TitleLookCollection.Print -> R.string.title_collection_print
    TitleLookCollection.Display -> R.string.title_collection_display
}

private fun gradientLabel(name: String): Int? = when (name) {
    "Sunset" -> R.string.title_gradient_sunset
    "Golden hour" -> R.string.title_gradient_golden_hour
    "Ember" -> R.string.title_gradient_ember
    "Rose" -> R.string.title_gradient_rose
    "Lavender" -> R.string.title_gradient_lavender
    "Midnight" -> R.string.title_gradient_midnight
    "Glacier" -> R.string.title_gradient_glacier
    "Lagoon" -> R.string.title_gradient_lagoon
    "Sea glass" -> R.string.title_gradient_sea_glass
    "Olive" -> R.string.title_gradient_olive
    "Silver" -> R.string.title_gradient_silver
    "Copper" -> R.string.title_gradient_copper
    else -> null
}

private fun weightLabel(weight: Int): Int? = when (weight) {
    400 -> R.string.title_weight_regular
    500 -> R.string.title_weight_medium
    600 -> R.string.title_weight_semibold
    700 -> R.string.title_weight_bold
    800 -> R.string.title_weight_extra_bold
    else -> null
}

private fun seconds(value: Double): String {
    val text = String.format(Locale.getDefault(), "%.2f", value)
    return if (text.contains('.') || text.contains(',')) text.trimEnd('0').trimEnd('.', ',') else text
}

/** What the title tools keep between projects: saved styles, starred fonts and the colors used last. */
private class TitleMemory(context: Context) {
    private val prefs = context.getSharedPreferences("titles", Context.MODE_PRIVATE)
    var looks by mutableStateOf(prefs.getString(TitleLibrary.LOOKS_KEY, null)); private set
    var favorites by mutableStateOf(prefs.getString(TitleLibrary.FAVORITES_KEY, null)); private set
    var colors by mutableStateOf(prefs.getString(TitleLibrary.RECENT_COLORS_KEY, null)); private set

    fun saveLooks(text: String) { looks = text; prefs.edit().putString(TitleLibrary.LOOKS_KEY, text).apply() }
    fun saveFavorites(text: String) { favorites = text; prefs.edit().putString(TitleLibrary.FAVORITES_KEY, text).apply() }
    fun remember(ink: TitleInk) {
        val text = TitleLibrary.remembering(colors, ink)
        if (text != colors) { colors = text; prefs.edit().putString(TitleLibrary.RECENT_COLORS_KEY, text).apply() }
    }
}

/**
 * The Title tool, in place of the editing tools so the picture stays in view:
 * the words, how long they stay, and the design and motion of the original's
 * title inspector. Everything happens on [draft], which the preview shows;
 * [done] makes it one undo step and [cancel] drops it.
 *
 * @param existing the title as saved, null for a new one.
 * @param range the seconds of the movie the title shows over.
 * @param bounds the seconds of the movie it may cover: all of it for a new title, the clip of the piece that was opened for an existing one.
 * @param replay plays one end of the title's motion; the title is passed as it is at that moment.
 * @param sample a picture of the frame under the playhead to take a color from, or null when it cannot be read.
 */
@Composable
fun TitlePanel(
    draft: TextOverlay, existing: TextOverlay?, change: (TextOverlay) -> Unit,
    range: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>, setRange: (ClosedFloatingPointRange<Double>) -> Unit,
    replay: (exit: Boolean, title: TextOverlay) -> Unit, sample: suspend () -> Bitmap?,
    done: () -> Unit, remove: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier,
) {
    var showPosition by remember { mutableStateOf(false) }
    // A new title holds the starter words, shown as the field's prompt so typing replaces them in one step (YouRec/EditorTools.swift:1541-1548).
    var example by remember { mutableStateOf(if (existing == null) draft.text else null) }

    Column(modifier.fillMaxWidth().background(Color(0xFF1B1D21))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
            Text(stringResource(if (existing == null) R.string.title_add else R.string.title_edit), color = Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (existing != null) TextButton(onClick = remove) { Text(stringResource(R.string.common_remove), color = RecordRed) }
            TextButton(onClick = done, enabled = draft.text.isNotBlank()) { Text(stringResource(R.string.common_done)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val write = stringResource(R.string.title_write)
            val starter = example != null && example == draft.text
            OutlinedTextField(
                if (starter) "" else draft.text, { example = null; change(draft.copy(text = it.take(500))) }, Modifier.fillMaxWidth().semantics { contentDescription = write },
                placeholder = { if (starter) Text(draft.text, color = Color.White) else Text(write) }, maxLines = 4,
            )
            DurationPicker(range, bounds, setRange)
            TitleInspector(
                appearance = draft.appearance, setDesign = { change(draft.copy(appearance = it)) },
                motion = draft.motion, setMotion = { change(draft.copy(motion = it)) },
                words = draft.text, replay = { exit, motion -> replay(exit, draft.copy(motion = motion)) }, sample = sample,
            )
            Text(stringResource(R.string.title_drag_hint), color = Soft, fontSize = 12.sp)
            Amount(stringResource(R.string.title_size), draft.size, 0.02..0.3, 0.001, label = "") { change(draft.copy(size = it)) }
            Disclosure(stringResource(R.string.title_position), showPosition) { showPosition = !showPosition }
            if (showPosition) {
                Amount(stringResource(R.string.title_across), draft.x, 0.0..1.0, 0.001, label = "") { change(draft.copy(x = it)) }
                Amount(stringResource(R.string.title_down), draft.y, 0.0..1.0, 0.001, label = "") { change(draft.copy(y = it)) }
                TextButton(onClick = { change(draft.copy(x = 0.5, y = 0.5)) }) { Text(stringResource(R.string.title_centre)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The movie second the Title tool opens [title] on, a paused frame over the
 * footage it covers, where it is readable (YouRec/EditorTools.swift:308-320):
 * every clip for a new title, the clip [clipID] for one opened from its bar.
 * [edit] is the edit before the title is placed over [range].
 */
internal fun titleOpening(edit: EditState, title: TextOverlay, range: ClosedFloatingPointRange<Double>, clipID: String?): Double {
    val clips = edit.clips
    val placements = TimelineEditing.layout(clips).placements
    val footage = clips.indices.filter { clipID == null || clips[it].id == clipID }.mapNotNull { index -> placements.getOrNull(index)?.let { it.start..it.end } }
    val placed = TimelineEditing.placeTitle(edit, title, range, clipID?.let { setOf(it) })?.clips ?: clips
    return OverlayOpening.title(placed, title, range, footage)
}

/**
 * The original's title inspector (YouRec/TitleInspector.swift:4-121): looks,
 * font, fill, outline, shadow, background and motion, on a design and a
 * motion. The Title tool works with it on a title, the Subtitles tool on the
 * caption series (YouRec/SubtitleEditor.swift:279-294).
 *
 * @param appearance the design as saved: null for one nobody has styled yet, which the tools start from as [plain].
 * @param words the text the looks and fonts are shown with.
 * @param replay plays one end of the motion, as it is at that moment.
 */
@Composable
fun TitleInspector(
    appearance: TitleDesign?, setDesign: (TitleDesign) -> Unit, motion: TitleMotion?, setMotion: (TitleMotion) -> Unit,
    words: String, replay: (exit: Boolean, motion: TitleMotion) -> Unit, sample: suspend () -> Bitmap?,
    plain: TitleDesign = TitleDesign.plain,
) {
    val context = LocalContext.current
    val memory = remember { TitleMemory(context) }
    var tab by remember { mutableStateOf(TitleTab.Looks) }
    val design = appearance ?: plain
    // Two columns where a name does not fit a quarter of the width, as the original's grids at large sizes.
    FitGrid(4) {
        for (value in TitleTab.entries) {
            Cell(tab == value, { tab = value }, minHeight = 46) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(value.icon, null, Modifier.size(20.dp))
                    Text(stringResource(value.label), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    HorizontalDivider(color = Plain)
    when (tab) {
        TitleTab.Looks -> Looks(appearance, design, words, memory, setDesign)
        TitleTab.Font -> Fonts(words, design, memory, setDesign)
        TitleTab.Fill -> Fill(design, memory, sample, setDesign)
        TitleTab.Outline -> {
            // The text border, in three thicknesses or a width of its own (YouRec/TitleInspector.swift:84-87, 165-176).
            Toggle(stringResource(R.string.title_border_toggle), design.outlined) { setDesign(design.copy(outlined = it)) }
            if (design.outlined) {
                val presets = listOf(stringResource(R.string.title_border_thin) to 0.025, stringResource(R.string.title_border_medium) to 0.045, stringResource(R.string.title_border_bold) to 0.09)
                val custom = presets.none { it.second == design.borderWidth }
                val thickness = stringResource(R.string.title_border_thickness)
                Box(Modifier.semantics { contentDescription = thickness }) {
                    Segments(presets + if (custom) listOf(stringResource(R.string.title_border_custom) to design.borderWidth) else emptyList(), design.borderWidth) {
                        setDesign(design.copy(borderWidth = it))
                    }
                }
                Amount(stringResource(R.string.title_border_width), design.borderWidth, 0.01..0.20, 0.005) { setDesign(design.copy(borderWidth = it)) }
                ColorControl(stringResource(R.string.title_border_color), design.border, memory, sample) { setDesign(design.copy(border = it)) }
            }
        }
        TitleTab.Shadow -> {
            Toggle(stringResource(R.string.title_shadow_toggle), design.shadow) { setDesign(design.copy(shadow = it)) }
            if (design.shadow) {
                ColorControl(stringResource(R.string.title_shadow_color), design.shade, memory, sample) { setDesign(design.copy(shade = it)) }
                Amount(stringResource(R.string.title_blur), design.shadowBlur, 0.0..0.6) { setDesign(design.copy(shadowBlur = it)) }
                Amount(stringResource(R.string.title_offset), design.shadowOffset, -0.5..0.5) { setDesign(design.copy(shadowOffset = it)) }
            }
        }
        TitleTab.Background -> {
            Segments(
                listOf(stringResource(R.string.title_plate_none) to TitlePlate.Off, stringResource(R.string.title_plate_block) to TitlePlate.Block, stringResource(R.string.title_plate_lines) to TitlePlate.Lines),
                design.plate,
            ) { setDesign(design.copy(plate = it)) }
            if (design.plate != TitlePlate.Off) {
                // The box's color and its opacity apart (YouRec/TitleInspector.swift:101-106).
                ColorControl(stringResource(R.string.title_box_color), design.backing, memory, sample, editsOpacity = false) { setDesign(design.copy(backing = it)) }
                Amount(stringResource(R.string.title_background_opacity), design.backing.alpha, 0.0..1.0) {
                    setDesign(design.copy(backing = TitleInk(design.backing.rgb, (it * 255).roundToInt())))
                }
                Amount(stringResource(R.string.title_padding), design.padding, 0.0..0.8) { setDesign(design.copy(padding = it)) }
                Amount(stringResource(R.string.title_radius), design.radius, 0.0..0.8) { setDesign(design.copy(radius = it)) }
            }
        }
        TitleTab.Motion -> Motion(motion, setMotion, replay)
    }
    HorizontalDivider(color = Plain)
}

/** A tappable tile, lit when chosen. */
@Composable
private fun Cell(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, minHeight: Int = 44, label: String? = null, content: @Composable () -> Unit) {
    Box(
        modifier.heightIn(min = minHeight.dp).clip(Rounded).background(if (selected) Chosen else Plain)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab; if (label != null) contentDescription = label }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) Color.White else Soft.copy(alpha = 0.5f)) { content() }
    }
}

@Composable
private fun <T> Segments(options: List<Pair<String, T>>, selected: T, columns: Int = options.size, choose: (T) -> Unit) {
    FitGrid(columns, Modifier.fillMaxWidth(), spacing = 4.dp) {
        for ((name, value) in options) {
            Cell(value == selected, { choose(value) }) { Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(on, set, Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun Disclosure(label: String, open: Boolean, toggle: () -> Unit) {
    TextButton(onClick = toggle, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text((if (open) "▾ " else "▸ ") + label)
    }
}

/** A slider with its name and value above it. The value is a percentage unless [label] says otherwise. */
@Composable
private fun Amount(
    name: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double = 0.01,
    label: String = "${(value * 100).roundToInt()}%", change: (Double) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(label, color = Soft, fontSize = 14.sp)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { change(((it / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.height(36.dp).semantics { contentDescription = name },
        )
    }
}

/** How long a title or a picture stays: a row that opens the original's presets and its start and end sliders (YouRec/EditorTools.swift:1353-1393). */
@Composable
internal fun DurationPicker(range: ClosedFloatingPointRange<Double>, bounds: ClosedFloatingPointRange<Double>, change: (ClosedFloatingPointRange<Double>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // All in seconds of the movie, as the original: a title may run across cuts and clips of any speed.
    val timing = OverlayTiming(range, bounds)
    val shown = timing.duration
    val name = stringResource(R.string.title_duration)
    val set = { next: OverlayTiming -> change(next.range) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Rounded).background(Plain).clickable { expanded = !expanded }
                .semantics { contentDescription = name }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = Color.White)
            Text(name, color = Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text(stringResource(R.string.title_seconds, seconds(shown)), color = Color.White)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = Soft)
        }
        if (expanded) {
            FitGrid(5) {
                for (value in EditorDurationPresets.wholeSeconds) {
                    Cell(
                        abs(shown - value) < 1e-4, { set(timing.setting(OverlayTiming.Edge.End, timing.range.start + value)) },
                        enabled = EditorDurationPresets.fits(value, timing.available),
                    ) { Text(stringResource(R.string.title_seconds, seconds(value)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.title_available, seconds(timing.available)), color = Soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { set(timing.setting(OverlayTiming.Edge.End, bounds.endInclusive)) }) { Text(stringResource(R.string.title_to_end)) }
            }
            for (edge in OverlayTiming.Edge.entries) {
                val label = stringResource(if (edge == OverlayTiming.Edge.Start) R.string.title_start else R.string.title_end)
                val limits = timing.limits(edge)
                val time = if (edge == OverlayTiming.Edge.Start) timing.range.start else timing.range.endInclusive
                val span = limits.endInclusive - limits.start
                Row(Modifier.fillMaxWidth()) {
                    Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(seconds(time) + " s", color = Soft, fontSize = 13.sp)
                }
                Slider(
                    value = if (span > 0) ((time - limits.start) / span).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = { set(timing.setting(edge, timing.time(it.toDouble(), edge))) },
                    enabled = span > 0, modifier = Modifier.height(36.dp).semantics { contentDescription = label },
                )
            }
        }
    }
}

/**
 * A small picture of [words] in [design], as the title will be drawn: the
 * words on one line, cut to 20 characters (YouRec/TitleInspector.swift:609-617).
 */
@Composable
private fun Specimen(words: String, design: TitleDesign, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val line = words.split(' ', '\n', '\t').filter { it.isNotEmpty() }.joinToString(" ")
    val excerpt = (if (line.length > 20) line.take(19) + "…" else line).ifEmpty { stringResource(R.string.title_specimen_default) }
    // Letters 48 units tall in lines at most 560 wide, as the original's tiles, drawn at twice that for a sharp picture.
    val bitmap = remember(excerpt, design) { StyledText(assets, excerpt, design, 48f, 560f).image(2f) }
    Box(modifier.fillMaxWidth().height(54.dp).background(Color.Black).padding(horizontal = 9.dp, vertical = 3.dp), Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit)
    }
}

/** A look in a gallery: its specimen over its name, lit and ticked when chosen (YouRec/TitleInspector.swift:553-572). */
@Composable
internal fun LookTile(name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, specimen: @Composable () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Column(
        modifier.clip(shape).background(Plain).border(1.5.dp, if (selected) Accent else Color.Transparent, shape).clickable(onClick = onClick)
            .semantics { contentDescription = name; role = Role.Button; this.selected = selected },
    ) {
        specimen()
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = Accent)
        }
    }
}

/**
 * The Looks tab (YouRec/TitleInspector.swift:207-250): the four collections
 * of built-in looks, six each, with the one the design matches ticked, then
 * the saved styles and "Save as channel style".
 */
@Composable
private fun Looks(appearance: TitleDesign?, design: TitleDesign, words: String, memory: TitleMemory, setDesign: (TitleDesign) -> Unit) {
    val library = TitleLibrary.looks(memory.looks)
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    // The look last chosen or saved, which stays ticked while the design is still the one it gave.
    var chosen by remember { mutableStateOf<String?>(null) }
    var browsed by remember { mutableStateOf<TitleLookCollection?>(null) }
    // Outline is a tool of its own: a look never changes it. A title that has no design yet shows a
    // look as it was made, without the dark outline such a title is drawn with.
    val applied = { look: TitleDesign -> (if (appearance == null) TitleDesign() else design).applyingLook(look) }
    val builtIn = TitleDesign.looks.firstOrNull { appearance != null && applied(it.design) == appearance }
    val matches = listOfNotNull(builtIn?.name) + library.filter { appearance != null && applied(it.design) == appearance }.map { it.id }
    val selected = chosen?.takeIf { it in matches } ?: matches.firstOrNull()
    val collection = browsed ?: builtIn?.collection ?: TitleLookCollection.Editorial

    @Composable
    fun look(id: String, label: String, value: TitleDesign, modifier: Modifier) {
        val result = applied(value)
        LookTile(label, id == selected, { setDesign(result); chosen = id }, modifier) { Specimen(words, result) }
    }
    val pick = stringResource(R.string.title_look_collection)
    Box(Modifier.semantics { contentDescription = pick }) {
        Segments(TitleLookCollection.entries.map { stringResource(collectionLabel(it)) to it }, collection) { browsed = it }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The tiles keep their width as text grows, as the original's scaled metric does.
        val tile = TitleLookCollection.TILE_WIDTH * LocalDensity.current.fontScale
        val columns = TitleLookCollection.columns(maxWidth.value.toDouble(), tile)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in TitleDesign.looks.filter { it.collection == collection }.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in row) look(item.name, lookLabel(item.name)?.let { stringResource(it) } ?: item.name, item.design, Modifier.weight(1f))
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (library.isNotEmpty()) {
        Text(stringResource(R.string.title_my_styles), color = Color.White, fontWeight = FontWeight.SemiBold)
        for (entry in library) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                look(entry.id, entry.name, entry.design, Modifier.weight(1f))
                IconButton(onClick = { deleting = entry.id }) { Icon(Icons.Outlined.Delete, stringResource(R.string.title_delete_named, entry.name), tint = Color.White) }
            }
        }
    }
    TextButton(onClick = { saving = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
        Text(stringResource(R.string.title_save_style), modifier = Modifier.padding(start = 6.dp))
    }
    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(stringResource(R.string.title_save_style_title), fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.title_style_name)) }, singleLine = true)
                    Text(stringResource(R.string.title_save_style_message), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = TitleLibrary.saving(memory.looks, name, design)
                    memory.saveLooks(saved)
                    chosen = TitleLibrary.looks(saved).firstOrNull()?.id
                    saving = false
                }, enabled = TitleLibrary.canSave(library, name)) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.title_delete_style_title), fontSize = 18.sp) },
            confirmButton = { TextButton(onClick = { memory.saveLooks(TitleLibrary.deleting(memory.looks, id)); deleting = null }) { Text(stringResource(R.string.title_delete_style), color = RecordRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun faceLabel(id: String, imported: Map<String, String> = emptyMap()): String = when {
    id == "system" -> stringResource(R.string.title_font_system)
    else -> imported[id] ?: ImportedFonts.label(id) ?: TitleTypeface.family(id)?.name ?: id
}

/**
 * The faces of the library and what each is called: its name, followed by its
 * file's name where two files have a face of that name (YouRec/FontLibraryView.swift:11-24).
 */
private fun importedLabels(files: List<ImportedFontFile>): Map<String, String> {
    val names = files.flatMap { it.faces }.groupingBy { it.name }.eachCount()
    return files.flatMap { file -> file.faces.map { it.id to if ((names[it.name] ?: 0) > 1) it.name + " · " + file.filename else it.name } }.toMap()
}

/** The design with [id] as its face, as the original chooses one (YouRec/TitleInspector.swift:205-214). */
private fun choosing(design: TitleDesign, id: String): TitleDesign {
    val family = TitleTypeface.family(id)
    val imported = if (id.startsWith("import:")) ImportedFonts.resolve(id)?.second else null
    val weight = when {
        imported != null -> if (id != design.face) imported.defaultWeight else design.weight
        family != null && design.weight !in family.weights -> family.weights[0]
        else -> design.weight
    }
    return design.copy(face = id, weight = weight, italic = design.italic && family?.slanted != null)
}

@Composable
private fun Fonts(sample: String, design: TitleDesign, memory: TitleMemory, setDesign: (TitleDesign) -> Unit) {
    val context = LocalContext.current
    val assets = context.assets
    val demo = sample.trim().ifEmpty { stringResource(R.string.editor_title) }.replace('\n', ' ').take(48)
    val favorites = TitleLibrary.favorites(memory.favorites)
    var more by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    // The library is read again when it closes, for what was imported or removed there.
    val files = remember(library) { ImportedFonts.library(context).files() }
    val labels = remember(files) { importedLabels(files) }
    val added = files.flatMap { file -> file.faces.map { it.id } }
    val listed = added + TitleTypeface.families.map { it.id }
    // A starred face that left the library is not offered, unless the title uses it (YouRec/TitleInspector.swift:198-204).
    val starred = favorites.filter { !it.startsWith("import:") || it in added || it == design.face }
    val ordered = (starred + (if (design.face in listed) emptyList() else listOf(design.face)) + listed).distinct()
    val choose = { id: String -> setDesign(choosing(design, id)) }
    val hint = stringResource(R.string.title_font_import_hint)
    TextButton(onClick = { library = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.semantics { onClick(label = hint) { library = true; true } }) {
        Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
        Text(stringResource(R.string.title_font_import), modifier = Modifier.padding(start = 6.dp))
    }
    if (TitleFonts.missing(design.face)) Text(stringResource(R.string.title_font_fallback, faceLabel(design.face, labels)), color = Soft, fontSize = 13.sp)
    for (id in ordered) {
        val label = faceLabel(id, labels)
        val specimen = choosing(design, id)
        val starred = id in favorites
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).clickable { choose(id) }.semantics { contentDescription = label; selected = design.face == id; role = Role.Button },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val typeface = remember(specimen.face, specimen.weight, specimen.italic) { TitleFonts.typeface(assets, specimen) }
                    Text(demo, color = Color.White, fontSize = 22.sp, fontFamily = FontFamily(typeface), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, color = Soft, fontSize = 12.sp)
                }
                if (design.face == id) Icon(Icons.Filled.Check, null, tint = Accent)
            }
            IconButton(onClick = { memory.saveFavorites(TitleLibrary.togglingFavorite(memory.favorites, id)) }) {
                Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder, stringResource(if (starred) R.string.title_unfavorite else R.string.title_favorite, label), tint = Color.White)
            }
        }
    }
    TextButton(onClick = { more = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(stringResource(R.string.title_more_fonts)) }
    val family = TitleTypeface.family(design.face)
    val weights = family?.weights ?: (if (design.face.startsWith("import:")) ImportedFonts.resolve(design.face)?.second?.weights else null).orEmpty()
    if (weights.size > 1) {
        Text(stringResource(R.string.title_weight), color = Soft, fontSize = 13.sp)
        Segments(weights.map { (weightLabel(it)?.let { name -> stringResource(name) } ?: it.toString()) to it }, design.weight, columns = 3) { setDesign(design.copy(weight = it)) }
    }
    if (family?.slanted != null) Toggle(stringResource(R.string.title_italic), design.italic) { setDesign(design.copy(italic = it)) }
    Text(stringResource(R.string.title_alignment), color = Soft, fontSize = 13.sp)
    Segments(
        listOf(stringResource(R.string.title_left) to TitleAlignment.Left, stringResource(R.string.title_center) to TitleAlignment.Center, stringResource(R.string.title_right) to TitleAlignment.Right),
        design.alignment,
    ) { setDesign(design.copy(alignment = it)) }
    Amount(stringResource(R.string.title_letter_spacing), design.tracking, -0.05..0.25) { setDesign(design.copy(tracking = it)) }
    Amount(stringResource(R.string.title_line_spacing), design.lineSpacing, 0.0..0.8) { setDesign(design.copy(lineSpacing = it)) }
    if (library) FontLibraryDialog(use = { choose(it.id) }, draftFace = design.face) { library = false }
    if (more) {
        // The phone's own font families, in place of the original's system font picker.
        AlertDialog(
            onDismissRequest = { more = false },
            title = { Text(stringResource(R.string.title_more_fonts).trimEnd('…'), fontSize = 18.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (name in TitleFonts.deviceFamilies.filter { !TitleFonts.missing(it) }) {
                        val typeface = remember(name) { TitleFonts.typeface(assets, TitleDesign(face = name, weight = 400)) }
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { setDesign(design.copy(face = name, weight = 400, italic = false)); more = false }.semantics { contentDescription = name }) {
                            Text(demo, fontSize = 20.sp, fontFamily = FontFamily(typeface), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(name, color = Soft, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { more = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun Fill(design: TitleDesign, memory: TitleMemory, sample: suspend () -> Bitmap?, setDesign: (TitleDesign) -> Unit) {
    var last by remember { mutableStateOf(design.gradient ?: TitleGradient.gallery[0].second) }
    val current = design.gradient
    Segments(listOf(stringResource(R.string.title_fill_solid) to false, stringResource(R.string.title_fill_gradient) to true), current != null) { on ->
        if (on) setDesign(design.copy(gradient = last)) else { current?.let { last = it }; setDesign(design.copy(gradient = null)) }
    }
    if (current == null) {
        ColorControl(stringResource(R.string.title_text_color), design.fill, memory, sample) { setDesign(design.copy(fill = it)) }
        return
    }
    for (row in TitleGradient.gallery.chunked(4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, value) in row) {
                val label = gradientLabel(name)?.let { stringResource(it) } ?: name
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable { last = value; setDesign(design.copy(gradient = value)) }
                        .semantics { contentDescription = label; selected = current == value; role = Role.Button },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Drawn along the same axis as on the letters, for a swatch of 3 by 1.
                    val (from, to) = value.axis(Size(3.0, 1.0))
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(6.dp)).background(
                            object : androidx.compose.ui.graphics.ShaderBrush() {
                                override fun createShader(size: androidx.compose.ui.geometry.Size) = android.graphics.LinearGradient(
                                    (from.x / 3 * size.width).toFloat(), (from.y * size.height).toFloat(), (to.x / 3 * size.width).toFloat(), (to.y * size.height).toFloat(),
                                    value.first.toColor(), value.last.toColor(), android.graphics.Shader.TileMode.CLAMP,
                                )
                            },
                        ),
                        Alignment.Center,
                    ) {
                        if (current == value) Icon(Icons.Filled.Check, null, Modifier.size(18.dp).background(Color.White, CircleShape), tint = Color.Black)
                    }
                    Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    ColorControl(stringResource(R.string.title_first_color), current.first, memory, sample) { setDesign(design.copy(gradient = current.copy(first = it))) }
    ColorControl(stringResource(R.string.title_second_color), current.last, memory, sample) { setDesign(design.copy(gradient = current.copy(last = it))) }
    Amount(stringResource(R.string.title_angle), current.angle, 0.0..360.0, 1.0, label = "${current.angle.roundToInt()}°") { setDesign(design.copy(gradient = current.copy(angle = it))) }
}

/**
 * One color of the design: a row with its swatch that opens the ways to
 * choose it. Hue, saturation and brightness stand in for the original's
 * system color picker, which Android does not have.
 */
@Composable
private fun ColorControl(title: String, ink: TitleInk, memory: TitleMemory, sample: suspend () -> Bitmap?, editsOpacity: Boolean = true, change: (TitleInk) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val alpha = (ink.rgba and 0xff).toInt()
    val encoded = "%06X".format(ink.rgb)
    var hex by remember(encoded) { mutableStateOf(encoded) }
    // Kept apart from the color: at no saturation or no brightness a color has forgotten its hue.
    var hsv by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(ink.toColor(), it) }) }
    if ((android.graphics.Color.HSVToColor(hsv) and 0xffffff).toLong() != ink.rgb) hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(ink.toColor(), it) }
    // Without its own opacity, a color keeps the one it has, and is remembered opaque (YouRec/TitleInspector.swift:679-690).
    val select = { next: TitleInk ->
        val kept = if (editsOpacity) next else TitleInk(next.rgb, alpha)
        change(kept)
        memory.remember(if (editsOpacity) kept else TitleInk.opaque(kept.rgb))
    }
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { if (expanded) memory.remember(ink); expanded = !expanded }.semantics { contentDescription = title },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = Soft)
            Text(title, color = Color.White, modifier = Modifier.weight(1f).padding(start = 6.dp))
            Box(Modifier.size(22.dp).background(Color(ink.toColor()), CircleShape).border(1.dp, Color.Gray, CircleShape))
        }
        if (!expanded) return@Column
        val setHsv = { index: Int, value: Double ->
            val next = hsv.copyOf().also { it[index] = value.toFloat() }
            hsv = next
            change(TitleInk((android.graphics.Color.HSVToColor(next) and 0xffffff).toLong(), alpha))
        }
        Amount(stringResource(R.string.title_hue), hsv[0].toDouble(), 0.0..360.0, 1.0, label = "${hsv[0].roundToInt()}°") { setHsv(0, it) }
        Amount(stringResource(R.string.title_saturation), hsv[1].toDouble(), 0.0..1.0) { setHsv(1, it) }
        Amount(stringResource(R.string.title_brightness), hsv[2].toDouble(), 0.0..1.0) { setHsv(2, it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = stringResource(R.string.title_hex)
            val clean = hex.trim('#', ' ')
            OutlinedTextField(
                hex, { hex = it.take(8) }, Modifier.weight(1f).semantics { contentDescription = "$title $name" }, label = { Text(name) }, placeholder = { Text("RRGGBB") },
                singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            )
            TextButton(onClick = { select(TitleInk(clean.toLong(16), alpha)) }, enabled = clean.length == 6 && clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
                Text(stringResource(R.string.title_set))
            }
        }
        if (editsOpacity) Amount(stringResource(R.string.title_opacity), ink.alpha, 0.0..1.0) { change(TitleInk(ink.rgb, (it * 255).roundToInt())) }
        val palette = TextColor.entries.map(TitleInk::of)
        val swatches = (palette + TitleLibrary.recentColors(memory.colors).map { if (editsOpacity) it else TitleInk.opaque(it.rgb) }).distinct()
        for (row in swatches.chunked(7)) {
            Row {
                for (color in row) {
                    val label = if (color.alpha < 1) stringResource(R.string.title_color_named_opacity, "%06X".format(color.rgb), "${(color.alpha * 100).roundToInt()}%")
                    else stringResource(R.string.title_color_named, "%06X".format(color.rgb))
                    Box(Modifier.weight(1f).height(44.dp).clickable { select(color) }.semantics { contentDescription = label; role = Role.Button }, Alignment.Center) {
                        Box(Modifier.size(24.dp).background(Color(color.toColor()), CircleShape).border(1.dp, Color.Gray, CircleShape))
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        TextButton(onClick = { scope.launch { sample().let { if (it == null) failed = true else frame = it } } }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Icon(Icons.Outlined.Colorize, null, Modifier.size(18.dp))
            Text(stringResource(R.string.title_sample_frame), modifier = Modifier.padding(start = 6.dp))
        }
        if (failed) Text(stringResource(R.string.title_sample_failed), color = Soft, fontSize = 13.sp)
    }
    frame?.let { picture ->
        AlertDialog(
            onDismissRequest = { frame = null },
            title = { Text(stringResource(R.string.title_sample_title), fontSize = 18.sp) },
            text = {
                val label = stringResource(R.string.title_sample_title)
                Image(
                    picture.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height).semantics { contentDescription = label }.pointerInput(picture) {
                        detectTapGestures { point ->
                            val x = (point.x / size.width * picture.width).toInt().coerceIn(0, picture.width - 1)
                            val y = (point.y / size.height * picture.height).toInt().coerceIn(0, picture.height - 1)
                            select(TitleInk.opaque((picture.getPixel(x, y) and 0xffffff).toLong()))
                            frame = null
                        }
                    },
                )
            },
            confirmButton = { TextButton(onClick = { frame = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun Motion(saved: TitleMotion?, set: (TitleMotion) -> Unit, replay: (Boolean, TitleMotion) -> Unit) {
    val context = LocalContext.current
    var exit by remember { mutableStateOf(false) }
    val motion = saved ?: TitleMotion()
    val move = if (exit) motion.exit else motion.entrance
    val length = if (exit) motion.outSeconds else motion.inSeconds
    Segments(listOf(stringResource(R.string.title_entrance) to false, stringResource(R.string.title_exit) to true), exit) { exit = it }
    val names = mapOf(
        TitleMove.None to R.string.title_move_none, TitleMove.Fade to R.string.title_move_fade, TitleMove.Drift to R.string.title_move_drift,
        TitleMove.Scale to R.string.title_move_scale, TitleMove.Wipe to R.string.title_move_wipe,
    )
    FitGrid(3) {
        for (mode in TitleMove.entries) {
            Cell(move == mode, {
                val next = if (exit) motion.copy(exit = mode) else motion.copy(entrance = mode)
                set(next)
                // As the original: choosing a move plays it once, unless the phone has animations turned off.
                val still = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                if (mode != TitleMove.None && !still) replay(exit, next)
            }) { Text(stringResource(names.getValue(mode)), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (move == TitleMove.Drift || move == TitleMove.Wipe) {
        val direction = (if (exit) motion.outDirection else motion.inDirection) ?: if (move == TitleMove.Drift) TitleDirection.Up else TitleDirection.Right
        Text(stringResource(R.string.title_direction), color = Soft, fontSize = 13.sp)
        Segments(
            listOf(
                stringResource(R.string.title_up) to TitleDirection.Up, stringResource(R.string.title_down_direction) to TitleDirection.Down,
                stringResource(R.string.title_left) to TitleDirection.Left, stringResource(R.string.title_right) to TitleDirection.Right,
            ),
            direction,
        ) { set(if (exit) motion.copy(outDirection = it) else motion.copy(inDirection = it)) }
    }
    if (move != TitleMove.None) {
        Amount(stringResource(R.string.title_motion_duration), length, 0.05..5.0, 0.05, label = String.format(Locale.getDefault(), "%.2f s", length)) {
            set(if (exit) motion.copy(outSeconds = it) else motion.copy(inSeconds = it))
        }
        TextButton(onClick = { replay(exit, motion) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Text(stringResource(if (exit) R.string.title_preview_exit else R.string.title_preview_entrance), modifier = Modifier.padding(start = 6.dp))
        }
    }
}
