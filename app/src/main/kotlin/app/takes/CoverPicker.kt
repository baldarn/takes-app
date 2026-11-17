package app.takes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.takes.core.CoverPick
import app.takes.core.Project
import kotlinx.coroutines.launch
import java.io.File

private val Secondary = Color(0xFF98989F)
private val Cell = Color(0xFF171717)
private val Stage = Color(0xFF121212)
private val Badge = Color.Black.copy(alpha = 0.78f)

/** Times with digits of one width, as the original's `monospacedDigit`. */
private val Digits = TextStyle(fontFeatureSettings = "tnum")

/**
 * Select cover: nine frames of the part of the movie the person is looking at, [source], one from each ninth of
 * it, drawn as the movie draws them; Update shows nine others, and a frame chosen opens large, to be shared as a
 * JPEG (YouRec/CoverPicker.swift:82-217).
 *
 * The original pushes its screens over the tools; here they are one full-screen dialog, the grid and the frame
 * chosen in turn, Back going from the frame to the grid and from the grid to the editor.
 */
@Composable
fun CoverPicker(project: Project, store: ProjectStore, source: CoverPick.Source, close: () -> Unit) {
    val context = LocalContext.current
    // The movie is captured once, when the picker opens: the grid and the JPEG are drawn from the same edit (CoverPicker.swift:6-7).
    val frames by produceState<CoverFrames?>(null) { value = CoverFrames.of(context.applicationContext, project, store) }
    val pool = source.pool
    var picks by remember { mutableStateOf(emptyList<Int>()) }
    val thumbnails = remember { mutableStateMapOf<Int, Bitmap>() }
    val failures = remember { mutableStateMapOf<Int, Unit>() }
    var selected by remember { mutableStateOf<Int?>(null) }
    fun update() {
        val next = pool.picks(picks)
        thumbnails.keys.retainAll(next.toSet())
        failures.clear()
        picks = next
    }
    LaunchedEffect(Unit) { if (picks.isEmpty()) update() }
    val aspect = frames?.canvas?.let { CoverPick.aspect(it.first, it.second) } ?: CoverPick.aspect(0, 0)
    // One frame drawn at a time, in the order of the grid; while a frame is open large it goes first.
    LaunchedEffect(picks, frames, selected == null) {
        val drawing = frames ?: return@LaunchedEffect
        if (selected != null) return@LaunchedEffect
        for (pick in picks) {
            if (thumbnails[pick] != null || pick in failures) continue
            val image = drawing.frame(pool.time(pick), thumbnail = true)
            if (image != null) thumbnails[pick] = image else failures[pick] = Unit
        }
    }
    val back = { if (selected != null) selected = null else close() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val chosen = selected
        if (chosen == null) {
            val title = stringResource(R.string.cover_title)
            Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().semantics { paneTitle = title }) {
                Bar(title, back)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(source.scope.label), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(CoverPick.label(source.start) + " – " + CoverPick.label(source.end), color = Secondary, fontSize = 12.sp, style = Digits)
                    }
                    OutlinedButton(onClick = ::update, enabled = CoverPick.canUpdate(pool), modifier = Modifier.heightIn(min = 44.dp)) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = Color.White)
                        Text(stringResource(R.string.cover_update), color = Color.White, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (pool.count == 0) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Movie, null, Modifier.size(44.dp), tint = Secondary)
                        Text(stringResource(R.string.cover_no_video), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                        Text(stringResource(R.string.cover_no_video_detail), color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    val minimum = CoverPick.columnWidth(aspect, accessibilityText())
                    LazyVerticalGrid(
                        GridCells.Adaptive(minimum.dp), Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(15.dp),
                    ) {
                        items(picks, key = { it }) { pick ->
                            val stamp = CoverPick.label(pool.time(pick))
                            val label = stringResource(R.string.cover_frame_at, stamp)
                            Column(
                                Modifier.clickable { selected = pick }.clearAndSetSemantics { contentDescription = label; role = Role.Button },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(aspect.toFloat()).clip(RoundedCornerShape(6.dp)).background(Cell), Alignment.Center) {
                                    val image = thumbnails[pick]
                                    when {
                                        image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                        pick in failures -> Icon(Icons.Outlined.BrokenImage, null, tint = Secondary)
                                        else -> CircularProgressIndicator(Modifier.size(22.dp), color = Secondary, strokeWidth = 2.dp)
                                    }
                                }
                                Text(stamp, color = Secondary, fontSize = 12.sp, style = Digits)
                            }
                        }
                    }
                }
            }
        } else {
            CoverDetail(project, store, frames, pool.time(chosen), thumbnails[chosen], aspect, back)
        }
    }
}

/** The name of what the picker offers frames from (CoverPicker.swift:24-31). */
private val CoverPick.Scope.label: Int
    get() = when (this) {
        CoverPick.Scope.Range -> R.string.cover_scope_range
        CoverPick.Scope.Clips -> R.string.cover_scope_clips
        CoverPick.Scope.Whole -> R.string.cover_scope_whole
    }

/** The bar over each screen: Back and the screen's name. */
@Composable
private fun Bar(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cover_back), tint = Color.White) }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
        Spacer(Modifier.width(48.dp))
    }
}

/**
 * The frame at [time], large: the grid's small one at first, then the frame at the movie's own size, and once it
 * is a JPEG, Share (CoverPicker.swift:164-217). Save to gallery stands beside it, for the share sheet's Save Image
 * of the original's system.
 */
@Composable
private fun CoverDetail(project: Project, store: ProjectStore, frames: CoverFrames?, time: Double, thumbnail: Bitmap?, aspect: Double, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var jpeg by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt, frames) {
        val drawing = frames ?: return@LaunchedEffect
        if (jpeg != null) return@LaunchedEffect
        val drawn = drawing.frame(time, thumbnail = false)
        if (drawn == null) { failed = true; return@LaunchedEffect }
        frame = drawn
        jpeg = runCatching { CoverFrames.jpeg(drawn, CoverFrames.folder(store, project), CoverPick.fileName(project.title, time)) }.getOrNull()
        if (jpeg == null) failed = true
    }
    val stamp = CoverPick.label(time)
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().semantics { paneTitle = stamp }) {
        Bar(stamp, back)
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Box(Modifier.aspectRatio(aspect.toFloat()).background(Stage)) {
                (frame ?: thumbnail)?.let { picture ->
                    val description = stringResource(R.string.cover_image)
                    val size = stringResource(R.string.cover_image_size, picture.width, picture.height)
                    Image(picture.asImageBitmap(), description, Modifier.fillMaxSize().semantics { stateDescription = size }, contentScale = ContentScale.Fit)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val file = jpeg
                    if (file != null && frame != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val saved = stringResource(R.string.cover_saved)
                            val notSaved = stringResource(R.string.cover_save_failed)
                            Round(Icons.Outlined.Download, stringResource(R.string.cover_save)) {
                                scope.launch { store.error = if (CoverFrames.saveToGallery(context, file)) saved else notSaved }
                            }
                        }
                        Round(Icons.Outlined.Share, stringResource(R.string.cover_share)) { share(context, file) }
                    } else if (!failed) {
                        val preparing = stringResource(R.string.cover_preparing)
                        Box(Modifier.size(46.dp).background(Badge, CircleShape).semantics { contentDescription = preparing }, Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                    }
                }
            }
            if (failed) {
                Column(Modifier.background(Color(0xF02C2C2E), RoundedCornerShape(12.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cover_failed), color = Color.White)
                    OutlinedButton(onClick = { failed = false; attempt += 1 }) { Text(stringResource(R.string.cover_try_again), color = Color.White) }
                }
            }
        }
    }
}

/** A round button over the picture, as the original's Share. */
@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Badge).clickable(onClickLabel = null, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, Alignment.Center) {
        Icon(icon, null, tint = Color.White)
    }
}

/** Offers the JPEG to the apps that take pictures, through the system share sheet, with the picture as its preview. */
private fun share(context: Context, jpeg: File) {
    val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", jpeg) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, jpeg.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(jpeg.name, uri)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
