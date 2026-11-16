package app.takes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.takes.core.FontLibrary
import app.takes.core.ImportedFontException
import app.takes.core.ImportedFontFace
import app.takes.core.ImportedFontFile
import app.takes.core.ImportedFontReference
import app.takes.core.TitleDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The fonts imported into the app, shared by the title tools, the drawing of
 * titles and captions, and the saving of projects ([FontLibrary]). A face is
 * looked for in the copy the open project keeps, then in the library, then in
 * the other projects: the files are named by their content, so any copy is
 * the same font.
 */
object ImportedFonts {
    @Volatile private var library: FontLibrary? = null
    @Volatile private var projects: File? = null

    /** The folder of the project open in the editor, whose copies come first. */
    @Volatile var openFolder: File? = null

    fun library(context: Context): FontLibrary = library ?: synchronized(this) {
        library ?: FontLibrary(File(context.applicationContext.filesDir, "Fonts")).also {
            projects = File(context.applicationContext.filesDir, "Projects")
            library = it
        }
    }

    /** The face [id] names and its file, or null when no copy is left: the title then falls back. */
    fun resolve(id: String): Pair<File, ImportedFontFace>? {
        val fonts = library ?: return null
        val reference = ImportedFontReference.parse(id) ?: return null
        fonts.resolve(id, openFolder)?.let { return it }
        for (folder in projects?.listFiles().orEmpty()) {
            if (folder == openFolder || !File(folder, reference.filename).isFile) continue
            fonts.resolve(id, folder)?.let { return it }
        }
        return null
    }

    /** What a face is called on screen: its name, else the name saved in its id. */
    fun label(id: String): String? = ImportedFontReference.parse(id)?.let { resolve(id)?.second?.name ?: it.postScriptName }
}

/** The kinds of file offered by the picker. Android names fonts in several ways; the extension decides. */
private val FontTypes = arrayOf(
    "font/ttf", "font/otf", "font/collection", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
    "application/font-sfnt", "application/vnd.ms-opentype", "application/x-font-truetype", "application/octet-stream",
)

private val Soft = Color(0xFFA0A4AB)
private val Plain = Color.White.copy(alpha = 0.07f)

/**
 * The library of imported fonts, as the original's Fonts screen
 * (YouRec/FontLibraryView.swift:27-174): import TTF, OTF, TTC and OTC files
 * from the phone's files, list them with their faces, remove them. Opened from
 * a title, a tap on a face uses it, and a file of one face imported alone is
 * used at once.
 *
 * @param draftFace the face of the title being worked on: if its file is removed, the open project keeps a copy first.
 */
@Composable
fun FontLibraryDialog(use: ((ImportedFontFace) -> Unit)?, draftFace: String?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val library = remember { ImportedFonts.library(context) }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<ImportedFontFile>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<ImportedFontFile?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val refresh: suspend () -> Unit = { files = withContext(Dispatchers.IO) { library.files() } }
    LaunchedEffect(Unit) { refresh() }

    val invalid = stringResource(R.string.fonts_invalid)
    val unsupported = stringResource(R.string.fonts_unsupported)
    val unknown = stringResource(R.string.common_unknown_error)
    val already = stringResource(R.string.fonts_already_imported)
    val imported = stringResource(R.string.fonts_imported)
    val counted = stringResource(R.string.fonts_added_count)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        working = true
        feedback = null
        scope.launch {
            val results = mutableListOf<Pair<ImportedFontFile, Boolean>>()
            val errors = mutableListOf<String>()
            for (uri in uris) {
                val name = displayName(context, uri)
                try {
                    results += withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException()
                        library.add(bytes, name)
                    }
                } catch (error: ImportedFontException) {
                    errors += name + " — " + if (error.reason == ImportedFontException.Reason.Unsupported) unsupported else invalid
                } catch (error: Exception) {
                    errors += name + " — " + (error.message ?: unknown)
                } catch (_: OutOfMemoryError) {
                    errors += "$name — $invalid"
                }
            }
            refresh()
            working = false
            if (errors.isNotEmpty()) problem = errors.joinToString("\n")
            val added = results.count { it.second }
            if (results.size == 1) {
                val file = results[0].first
                expanded = expanded + file.id
                feedback = (if (added == 0) already else imported).format(file.filename)
                if (errors.isEmpty() && file.faces.size == 1 && use != null) { use(file.faces[0]); dismiss() }
            } else if (results.isNotEmpty()) {
                feedback = counted.format(added, results.size - added)
            }
        }
    }

    // The same name for two faces of different files gets the file's name after it (YouRec/FontLibraryView.swift:11-24).
    val repeated = files.flatMap { it.faces }.groupingBy { it.name }.eachCount()
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1B1D21))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fonts_title), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp))
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picker.launch(FontTypes) }, enabled = !working, modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.fonts_import), modifier = Modifier.padding(start = 6.dp))
                }
                if (working) {
                    Text(stringResource(R.string.fonts_importing), color = Color.White, fontSize = 14.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                feedback?.let { Text(it, color = Soft, fontSize = 14.sp) }
                Text(stringResource(R.string.fonts_import_note), color = Soft, fontSize = 12.sp)
                if (files.isNotEmpty()) {
                    HorizontalDivider(color = Plain)
                    Text(stringResource(if (use == null) R.string.fonts_imported_header else R.string.fonts_choose_face), color = Color.White, fontWeight = FontWeight.SemiBold)
                    for (file in files) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Plain).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    if (file.faces.size == 1) {
                                        FaceRow(file.faces[0], file.filename, repeated, use?.let { pick -> { face: ImportedFontFace -> pick(face); dismiss() } })
                                    } else {
                                        val open = file.id in expanded
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { expanded = if (open) expanded - file.id else expanded + file.id },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(file.filename, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(pluralStringResource(R.plurals.fonts_faces, file.faces.size, file.faces.size), color = Soft, fontSize = 12.sp)
                                            }
                                            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Soft)
                                        }
                                    }
                                }
                                IconButton(onClick = { removal = file }) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.fonts_remove_named, file.filename), tint = RecordRed)
                                }
                            }
                            if (file.faces.size > 1 && file.id in expanded) {
                                for (face in file.faces) {
                                    HorizontalDivider(color = Plain)
                                    Box(Modifier.padding(start = 12.dp)) {
                                        FaceRow(face, null, repeated, use?.let { pick -> { chosen: ImportedFontFace -> pick(chosen); dismiss() } })
                                    }
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.fonts_projects_keep), color = Soft, fontSize = 12.sp)
                }
            }
        }
    }

    removal?.let { file ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(stringResource(R.string.fonts_remove_title, file.filename), fontSize = 18.sp) },
            text = { Text(stringResource(R.string.fonts_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    removal = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                // The title being worked on is not saved yet: the open project keeps its copy first (YouRec/FontLibraryView.swift:84-93).
                                val reference = draftFace?.let(ImportedFontReference::parse)
                                val folder = ImportedFonts.openFolder
                                if (reference != null && reference.digest == file.id && folder != null) library.preserve(listOf(reference.filename), folder)
                                library.remove(file.id)
                            }
                            refresh()
                            feedback = null
                        } catch (error: Exception) {
                            problem = error.message ?: unknown
                        }
                    }
                }) { Text(stringResource(R.string.fonts_remove_confirm), color = RecordRed) }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text(stringResource(R.string.fonts_problem_title), fontSize = 18.sp) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

/** One face: "Aa" in it, its name, and the file's name when the file has only this face. */
@Composable
private fun FaceRow(face: ImportedFontFace, filename: String?, repeated: Map<String, Int>, use: ((ImportedFontFace) -> Unit)?) {
    val assets = LocalContext.current.assets
    val label = stringResource(R.string.fonts_use_named, face.name)
    val typeface = remember(face.id) { TitleFonts.typeface(assets, TitleDesign(face = face.id, weight = face.defaultWeight)) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (use != null) Modifier.clickable { use(face) }.semantics { contentDescription = label; role = Role.Button } else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp, 40.dp).clip(RoundedCornerShape(7.dp)).background(Plain), Alignment.Center) {
            Text("Aa", color = Color.White, fontSize = 22.sp, fontFamily = FontFamily(typeface), maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(face.name, color = Color.White, fontWeight = FontWeight.Medium)
            if (filename != null) Text(filename, color = Soft, fontSize = 12.sp)
            else if ((repeated[face.name] ?: 0) > 1) Text(face.reference.postScriptName, color = Soft, fontSize = 12.sp)
        }
    }
}

/** The name the picked file has in the phone's files, which decides its kind. */
private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font"
