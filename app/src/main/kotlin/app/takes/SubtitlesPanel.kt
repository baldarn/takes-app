package app.takes

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.takes.core.EditState
import app.takes.core.EditInterval
import app.takes.core.EditTime
import app.takes.core.SubtitleCheckpoint
import app.takes.core.toTimeline
import app.takes.core.SubtitleCue
import app.takes.core.SubtitleEditing
import app.takes.core.SubtitleSettings
import app.takes.core.SubtitleWord
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechModelCorrupt
import app.takes.speech.SpeechModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

private val Soft = Color(0xFFA0A4AB)
private val Warning = Color(0xFFFFB454)
private val Current = Color(0xFF4DD0E1)

/** The languages the original offers, by the name it stores in the project, with Whisper's code for each. */
private val Languages = listOf(
    "Auto" to null, "Italian" to "it", "English" to "en", "French" to "fr", "German" to "de", "Spanish" to "es",
    "Portuguese" to "pt", "Chinese" to "zh", "Japanese" to "ja", "Korean" to "ko", "Russian" to "ru", "Arabic" to "ar",
)

private fun languageLabel(name: String): Int = when (name) {
    "Italian" -> R.string.subtitles_language_italian
    "English" -> R.string.subtitles_language_english
    "French" -> R.string.subtitles_language_french
    "German" -> R.string.subtitles_language_german
    "Spanish" -> R.string.subtitles_language_spanish
    "Portuguese" -> R.string.subtitles_language_portuguese
    "Chinese" -> R.string.subtitles_language_chinese
    "Japanese" -> R.string.subtitles_language_japanese
    "Korean" -> R.string.subtitles_language_korean
    "Russian" -> R.string.subtitles_language_russian
    "Arabic" -> R.string.subtitles_language_arabic
    else -> R.string.subtitles_language_auto
}

private fun cueTime(seconds: Double): String {
    val tenths = (seconds.coerceAtLeast(0.0) * 10).toLong()
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

/**
 * The Subtitles tool, in place of the editing tools so the picture stays in
 * view: download the speech model, transcribe, correct and restyle. Everything
 * happens on [draft], which the preview shows; [apply] makes it one undo step
 * and [close] drops it.
 *
 * @param saved the edit as it is in the project, to tell whether anything changed.
 * @param listened what a transcription listens to: the marked range, the span of the selection, or the whole
 *   timeline ([TrackCaptions.scope]). Only the captions in it are listed, and only those are replaced.
 * @param working told while a download or a transcription runs, to keep the screen on.
 * @param audition plays the movie between two of its seconds, to see a caption arrive or leave.
 * @param sample a picture of the frame under the playhead to take a color from, or null when it cannot be read.
 */
@Composable
fun SubtitlesPanel(
    saved: EditState, draft: EditState, change: (EditState) -> Unit, listened: EditInterval,
    files: Map<String, File>, layout: SubtitleLayout?, playhead: Double, playing: Boolean,
    seek: (Double) -> Unit, working: (Boolean) -> Unit, apply: () -> Unit, close: () -> Unit,
    modifier: Modifier = Modifier, audition: (from: Double, to: Double) -> Unit = { _, _ -> }, sample: suspend () -> Bitmap? = { null },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = draft.subtitles
    val timeline = remember(draft) { draft.toTimeline() }
    // The whole timeline, or a part of it whose captions alone are listed and replaced.
    val whole = listened.start <= EditTime.ZERO && listened.end >= timeline.duration
    val cues = remember(draft, listened) {
        SubtitleEditing.cues(draft).filter { whole || (it.start < listened.end.seconds && it.end > listened.start.seconds) }
    }

    var language by remember { mutableStateOf(Languages.firstOrNull { it.first == saved.subtitles?.language }?.first ?: "Auto") }
    // The model in use and how much of it is on the phone; read again after anything that changes either.
    var model by remember { mutableStateOf(SubtitleModel.selected(context)) }
    var present by remember { mutableLongStateOf(SubtitleModel.present(context, model)) }
    var installed by remember { mutableStateOf(SubtitleModel.installed(context, model)) }
    val megabytes = SubtitleModel.megabytes(model)
    val left = SubtitleModel.megabytes(model.bytes - present) // what a download still has to fetch
    val refresh = {
        model = SubtitleModel.selected(context)
        present = SubtitleModel.present(context, model)
        installed = SubtitleModel.installed(context, model)
    }
    var job by remember { mutableStateOf<Job?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var step by remember { mutableStateOf<SubtitleTranscriber.Step?>(null) } // null while downloading
    var failure by remember { mutableStateOf<String?>(null) }
    var lighter by remember { mutableStateOf<SpeechModel?>(null) } // offered under a failure the model's size caused
    var selected by remember { mutableStateOf<String?>(null) }
    // The caption whose text is being typed, edited in place in its row: in the block that follows playback, or in the full list.
    var editing by remember { mutableStateOf<SubtitleCue?>(null) }
    var editingInAll by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(TextFieldValue()) }
    var showAppearance by remember { mutableStateOf(false) }
    var showPosition by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var askReplace by remember { mutableStateOf(false) }
    var askMetered by remember { mutableStateOf(false) }
    var askRemoveAll by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var askRemoveModel by remember { mutableStateOf<SpeechModel?>(null) }
    var choosingModel by remember { mutableStateOf(false) }
    // What a transcription had finished, saved after every piece; [paused] once the app leaving the screen stopped it.
    var checkpoint by remember { mutableStateOf<SubtitleCheckpoint?>(null) }
    var paused by remember { mutableStateOf(false) }
    var askApplyPaused by remember { mutableStateOf(false) }
    val busy = job != null

    // A job ends long after it was started: it must reach the draft as it is then.
    val latest by rememberUpdatedState(draft)
    val onChange by rememberUpdatedState(change)
    DisposableEffect(busy) {
        working(busy)
        onDispose { working(false) }
    }

    // The series stays inside the picture whatever was just changed. A series never moved keeps "no position".
    fun inside(value: SubtitleSettings): SubtitleSettings {
        val fitted = layout?.placement?.constrained(value) ?: return value
        return if (value.x == null && fitted.x == 0.5) fitted.copy(x = null) else fitted
    }
    fun restyle(next: SubtitleSettings) {
        SubtitleEditing.restyle(draft, inside(next))?.let(change)
    }
    LaunchedEffect(layout) {
        val current = latest.subtitles ?: return@LaunchedEffect
        SubtitleEditing.restyle(latest, inside(current))?.let(onChange)
    }

    val downloadFailed = stringResource(R.string.subtitles_download_failed)
    val downloadCorrupt = stringResource(R.string.subtitles_download_corrupt)
    val noRoom = stringResource(R.string.subtitles_no_room, SubtitleModel.megabytes(model.bytes - present + SubtitleModel.RESERVE))
    val unknown = stringResource(R.string.common_unknown_error)
    val reasons = mapOf(
        SubtitleTranscriber.Failure.Reason.NoSound to stringResource(if (whole) R.string.subtitles_no_sound else R.string.subtitles_no_sound_range),
        SubtitleTranscriber.Failure.Reason.NoSpeech to stringResource(R.string.subtitles_no_speech),
        SubtitleTranscriber.Failure.Reason.NoWords to stringResource(R.string.subtitles_no_words),
        SubtitleTranscriber.Failure.Reason.Model to stringResource(R.string.subtitles_model_failed),
        SubtitleTranscriber.Failure.Reason.Memory to stringResource(R.string.subtitles_model_memory, model.name),
    )
    // A job the app did not survive, most likely for lack of memory: said once, with the way out.
    val stopped = stringResource(R.string.subtitles_model_stopped)
    LaunchedEffect(Unit) {
        val interrupted = SubtitleModel.interrupted(context) ?: return@LaunchedEffect
        SubtitleModel.running(context, null)
        failure = stopped.format(interrupted.name)
        lighter = SpeechModels.lighter(interrupted)?.takeIf { interrupted == model }
    }
    val transcribeFailed = stringResource(R.string.subtitles_transcribe_failed)

    fun run(first: SubtitleTranscriber.Step?, work: suspend () -> Unit) {
        if (job != null) return
        failure = null
        lighter = null
        progress = 0f
        step = first
        job = scope.launch {
            try {
                work()
            } finally {
                job = null
                stopping = false
            }
        }
    }
    val download = {
        val wanted = model
        if (!SubtitleModel.hasRoom(context, wanted)) failure = noRoom else run(null) {
            try {
                progress = present.toFloat() / wanted.bytes
                SubtitleModel.download(context, wanted) { progress = it.toFloat() / wanted.bytes }
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: SpeechModelCorrupt) {
                failure = downloadCorrupt
            } catch (problem: IOException) {
                failure = downloadFailed.format(problem.message ?: unknown)
            } finally {
                refresh() // what arrived stays, also after a stop: the button then says how much is left
            }
        }
    }
    val transcribe = { resuming: Boolean ->
        val spoken = language
        val with = model
        val from = checkpoint.takeIf { resuming }
        val range = listened
        selected = null
        paused = false
        checkpoint = null
        run(SubtitleTranscriber.Step.Reading) {
            try {
                val result = SubtitleTranscriber.transcribe(context, with, latest.toTimeline(), range, files, Languages.first { it.first == spoken }.second, from, { checkpoint = it }) { fraction, at ->
                    progress = fraction
                    step = at
                }
                Log.i("Subtitles", "%s loaded in %.1f s; %.1f s of speech recognized in %.1f s".format(with.name, result.loadingSeconds, result.speechSeconds, result.recognizingSeconds))
                checkpoint = null
                onChange(SubtitleEditing.installHeard(latest, result.words, range, spoken))
            } catch (stopped: CancellationException) {
                if (!paused) checkpoint = null // a stop asked for keeps nothing
                throw stopped
            } catch (problem: SubtitleTranscriber.Failure) {
                checkpoint = null
                failure = reasons[problem.reason]
                // Too large for this phone, or broken: the smaller model is one tap away.
                if (problem.reason == SubtitleTranscriber.Failure.Reason.Memory || problem.reason == SubtitleTranscriber.Failure.Reason.Model) lighter = SpeechModels.lighter(with)
            } catch (problem: Exception) {
                checkpoint = null
                failure = transcribeFailed.format(problem.message ?: unknown)
            }
        }
    }
    // Leaving the screen stops a transcription after the piece in hand, keeping what is done, as the original
    // does on going to the background (YouRec/SubtitleEditor.swift:150-157): the tool then offers to resume.
    val owner = LocalLifecycleOwner.current
    val transcribing by rememberUpdatedState(job != null && step != null)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && transcribing) {
                paused = true
                job?.cancel()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val stop = {
        job?.let {
            stopping = true
            paused = false
            it.cancel()
        }
        Unit
    }
    val leave = { if (busy || paused || draft != saved) askDiscard = true else close() }
    val edit = { cue: SubtitleCue, inAll: Boolean ->
        editingInAll = inAll
        editing = cue
        // The cursor waits at the end of the text, with the keyboard up.
        edited = TextFieldValue(cue.text, TextRange(cue.text.length))
    }
    val saveText = {
        editing?.let { cue -> SubtitleEditing.correct(draft, cue, edited.text)?.let(change) } // an empty text removes the caption
        editing = null
        selected = null
    }
    val idle = !busy && editing == null

    val current = cues.indexOfLast { it.start <= playhead && playhead < it.end }
    // Once the playhead leaves the chosen caption it is no longer chosen: a tap on its row goes to it again rather than editing.
    LaunchedEffect(playhead) {
        val chosen = selected ?: return@LaunchedEffect
        if (cues.none { it.id == chosen && it.start <= playhead && playhead < it.end }) selected = null
    }
    // The caption the block that follows playback is centered on: the one showing, else the next to come, else the last.
    val focus = if (current >= 0) current else cues.indexOfFirst { it.start > playhead }.takeIf { it >= 0 } ?: maxOf(0, cues.lastIndex)
    val list = rememberLazyListState()
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // While a caption is edited the two buttons are about its text, as in the original.
            if (editing != null) {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.subtitles_cancel_edit)) }
            } else {
                TextButton(onClick = leave) { Text(stringResource(R.string.common_cancel)) }
            }
            Text(stringResource(R.string.subtitles_title), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (editing != null) {
                Button(onClick = saveText) { Text(stringResource(R.string.subtitles_save_text)) }
            } else {
                Button(onClick = { if (paused) askApplyPaused = true else apply() }, enabled = !busy) { Text(stringResource(R.string.subtitles_apply)) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "controls") {
                Column {
                    if (busy) {
                        val at = step
                        val status = when {
                            stopping -> stringResource(R.string.subtitles_stopping)
                            at == null -> stringResource(R.string.subtitles_downloading, model.name, (progress * megabytes).roundToInt(), megabytes)
                            at is SubtitleTranscriber.Step.Reading -> stringResource(R.string.subtitles_reading)
                            at is SubtitleTranscriber.Step.Loading -> stringResource(R.string.subtitles_loading)
                            at is SubtitleTranscriber.Step.Section -> stringResource(R.string.subtitles_section, at.index, at.count) + " · " + (at.remaining?.let {
                                val left = if (it >= 90) stringResource(R.string.subtitles_minutes, (it / 60).roundToInt()) else stringResource(R.string.subtitles_seconds, maxOf(1, it.roundToInt()))
                                stringResource(R.string.subtitles_time_left, left)
                            } ?: stringResource(R.string.subtitles_estimating))
                            else -> ""
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(status, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = stop, enabled = !stopping) { Text(stringResource(R.string.common_cancel)) }
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    } else {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            var choosing by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { choosing = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text(stringResource(R.string.subtitles_language, stringResource(languageLabel(language))))
                                    Icon(Icons.Filled.ArrowDropDown, null)
                                }
                                DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                                    for ((name, _) in Languages) {
                                        DropdownMenuItem(text = { Text(stringResource(languageLabel(name))) }, onClick = {
                                            // Another language starts again from the first piece.
                                            if (name != language) { paused = false; checkpoint = null }
                                            language = name
                                            choosing = false
                                        })
                                    }
                                }
                            }
                            if (installed) {
                                if (!whole) Text(stringResource(R.string.subtitles_range, timeLabel(listened.start.seconds), timeLabel(listened.end.seconds)), color = Soft, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                                Button(onClick = { if (paused) transcribe(true) else if (cues.isEmpty()) transcribe(false) else askReplace = true }, enabled = timeline.audio.isNotEmpty() && editing == null) {
                                    Icon(Icons.Outlined.Subtitles, null, Modifier.size(18.dp))
                                    Text(stringResource(if (paused) R.string.subtitles_resume else if (cues.isEmpty()) R.string.subtitles_transcribe else R.string.subtitles_retranscribe), modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                        if (paused) Text(stringResource(R.string.subtitles_paused), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        if (!installed) {
                            Text(stringResource(R.string.subtitles_download_note, model.name, megabytes), color = Soft, fontSize = 14.sp)
                            // On a connection that counts the data used, such as mobile data, the person is asked first.
                            Button(onClick = { if (metered(context)) askMetered = true else download() }, modifier = Modifier.padding(top = 6.dp)) {
                                // An interrupted download goes on from where it stopped, and says how much is left.
                                Text(if (present > 0) stringResource(R.string.subtitles_download_continue, left) else stringResource(R.string.subtitles_download, megabytes))
                            }
                        }
                    }
                    failure?.let { Text(it, color = Warning, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                    lighter?.takeIf { !busy }?.let { other ->
                        OutlinedButton(onClick = { SubtitleModel.choose(context, other); refresh(); failure = null; lighter = null }, modifier = Modifier.padding(top = 6.dp)) {
                            Text(stringResource(R.string.subtitles_model_use, other.name, SubtitleModel.megabytes(other)))
                        }
                    }
                    if (settings != null) {
                        if (cues.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, SubtitleEditing.transcript(draft)))
                                    copied = true
                                },
                                enabled = idle, contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Icon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                                Text(stringResource(if (copied) R.string.subtitles_copied else R.string.subtitles_copy), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        // How many words a caption holds at most. Punctuation and pauses end one sooner.
                        val regroup = SubtitleEditing.groupingAvailable(draft)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.subtitles_max_words, settings.maxWords), color = if (regroup) Color.White else Soft, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            val fewer = stringResource(R.string.subtitles_fewer_words)
                            val more = stringResource(R.string.subtitles_more_words)
                            val small = Modifier.defaultMinSize(minWidth = 44.dp)
                            TextButton(onClick = { SubtitleEditing.regroup(draft, settings.maxWords - 1)?.let(change) }, small.semantics { contentDescription = fewer },
                                enabled = regroup && idle && settings.maxWords > SubtitleSettings.MAX_WORDS.first) { Text("−", fontSize = 20.sp) }
                            TextButton(onClick = { SubtitleEditing.regroup(draft, settings.maxWords + 1)?.let(change) }, small.semantics { contentDescription = more },
                                enabled = regroup && idle && settings.maxWords < SubtitleSettings.MAX_WORDS.last) { Text("+", fontSize = 20.sp) }
                        }
                        if (!regroup) Text(stringResource(R.string.subtitles_stale), color = Warning, fontSize = 12.sp)
                        Text(stringResource(R.string.subtitles_drag_hint), color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

                        Disclosure(stringResource(R.string.subtitles_appearance), showAppearance) { showAppearance = !showAppearance }
                        if (showAppearance) {
                            // The title inspector, on the series' design and motion (YouRec/SubtitleEditor.swift:279-294); it starts
                            // from the series' style, color and font, which stay as they are until something is changed here.
                            Column(
                                Modifier.fillMaxWidth().padding(top = 4.dp).alpha(if (idle) 1f else 0.4f)
                                    .then(if (idle) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val words = cues.getOrNull(current)?.text ?: cues.firstOrNull()?.text ?: stringResource(R.string.subtitles_title)
                                TitleInspector(
                                    appearance = settings.appearance, plain = settings.design,
                                    setDesign = { restyle(settings.copy(appearance = it)) },
                                    motion = settings.motion, setMotion = { restyle(settings.copy(motion = it)) },
                                    words = words, sample = sample,
                                    replay = replay@{ exit, motion ->
                                        // The caption in focus, as far as the listened part of the movie goes.
                                        val cue = cues.getOrNull(focus) ?: return@replay
                                        val window = motion.previewWindow(exit, cue.end - cue.start)
                                        val from = maxOf(listened.start.seconds, cue.start + window.start)
                                        val to = minOf(listened.end.seconds, cue.start + window.endInclusive)
                                        if (to > from) audition(from, to)
                                    },
                                )
                            }
                            val smallest = (layout?.placement?.minimumSize ?: 0.012).toFloat()
                            val largest = (layout?.placement?.maximumSize ?: 0.10).toFloat().coerceAtLeast(smallest + 0.0001f)
                            Level(stringResource(R.string.subtitles_size), settings.size, smallest..largest, idle) { restyle(settings.copy(size = it)) }
                            Disclosure(stringResource(R.string.subtitles_position), showPosition) { showPosition = !showPosition }
                            if (showPosition) {
                                Level(stringResource(R.string.subtitles_horizontal), settings.centerX, 0f..1f, idle) { restyle(settings.copy(x = it)) }
                                Level(stringResource(R.string.subtitles_vertical), settings.y, 0f..1f, idle) { restyle(settings.copy(y = it)) }
                                TextButton(onClick = { restyle(settings.copy(x = 0.5, y = 0.86, size = 0.052)) }, enabled = idle, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text(stringResource(R.string.subtitles_reset_position))
                                }
                            }
                        }
                        // The five captions around the playhead, above the full list: one tap edits.
                        if (cues.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.subtitles_following), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (current < 0) Text(stringResource(R.string.subtitles_pause_in_speech), color = Soft, fontSize = 12.sp)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (index in focus - 2..focus + 2) {
                                    val cue = cues.getOrNull(index) ?: continue
                                    key(cue.id) {
                                        CueRow(cue, now = index == current, chosen = false, enabled = idle, editing = editing?.id == cue.id && !editingInAll, edited, { edited = it }) {
                                            seek(cue.start + 0.01)
                                            edit(cue, false)
                                        }
                                    }
                                }
                            }
                        } else Text(stringResource(R.string.subtitles_none), color = Soft, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                        // Before the list, as in the original, where it stays in reach above a long transcript.
                        TextButton(onClick = { askRemoveAll = true }, enabled = idle, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = if (idle) RecordRed else Soft)
                            Text(stringResource(R.string.subtitles_remove_all), color = if (idle) RecordRed else Soft, modifier = Modifier.padding(start = 6.dp))
                        }
                        if (cues.isNotEmpty()) {
                            Text(stringResource(R.string.subtitles_all, cues.size), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                            Text(stringResource(R.string.subtitles_row_hint), color = Soft, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
            if (settings != null) items(cues, key = { it.id }) { cue ->
                CueRow(cue, now = cues.getOrNull(current)?.id == cue.id, chosen = selected == cue.id, enabled = idle, editing = editing?.id == cue.id && editingInAll, edited, { edited = it }) {
                    // The first tap goes to the caption; a tap on the chosen one edits it.
                    if (selected == cue.id) edit(cue, true) else {
                        selected = cue.id
                        seek(cue.start + 0.01)
                    }
                }
            }
            item(key = "footer") {
                Column {
                    Text(stringResource(R.string.subtitles_note), color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    // The choice of model: out of the way of someone who only wants subtitles.
                    TextButton(onClick = { choosingModel = true }, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.subtitles_model, model.name, megabytes), color = if (busy) Soft else Color.Unspecified, fontSize = 12.sp)
                    }
                    if (installed) {
                        TextButton(onClick = { askRemoveModel = model }, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(stringResource(R.string.subtitles_remove_model, megabytes), color = if (busy) Soft else RecordRed, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (askMetered) Confirm(
        stringResource(R.string.subtitles_metered_title), stringResource(R.string.subtitles_metered_message, left),
        stringResource(R.string.subtitles_metered_download), stringResource(R.string.common_cancel),
        confirm = { askMetered = false; download() }, dismiss = { askMetered = false },
    )
    if (askReplace) Confirm(
        stringResource(if (whole) R.string.subtitles_replace_title else R.string.subtitles_replace_range_title), null, stringResource(R.string.subtitles_retranscribe), stringResource(R.string.common_cancel),
        confirm = { askReplace = false; transcribe(false) }, dismiss = { askReplace = false },
    )
    if (askRemoveAll) Confirm(
        stringResource(R.string.subtitles_remove_all_title), null, stringResource(R.string.subtitles_remove_all), stringResource(R.string.common_cancel), destructive = true,
        confirm = { askRemoveAll = false; selected = null; change(SubtitleEditing.remove(draft)) }, dismiss = { askRemoveAll = false },
    )
    if (askApplyPaused) Confirm(
        stringResource(R.string.subtitles_apply_paused_title), null, stringResource(R.string.subtitles_apply_paused_confirm), stringResource(R.string.subtitles_keep_editing), destructive = true,
        confirm = { askApplyPaused = false; apply() }, dismiss = { askApplyPaused = false },
    )
    if (askDiscard) Confirm(
        stringResource(R.string.subtitles_discard_title), null, stringResource(R.string.subtitles_discard_confirm), stringResource(R.string.subtitles_keep_editing), destructive = true,
        confirm = { askDiscard = false; close() }, dismiss = { askDiscard = false },
    )
    askRemoveModel?.let { target ->
        Confirm(
            stringResource(R.string.subtitles_remove_model_title, target.name), stringResource(R.string.subtitles_remove_model_message, SubtitleModel.megabytes(target)),
            stringResource(R.string.subtitles_remove_model_confirm), stringResource(R.string.common_cancel), destructive = true,
            confirm = {
                askRemoveModel = null
                SubtitleModel.remove(context, target)
                refresh()
            },
            dismiss = { askRemoveModel = null },
        )
    }
    if (choosingModel && askRemoveModel == null) ModelChoice(
        choices = remember(model, present) { SubtitleModel.choices(context).map { ModelRow(it, SubtitleModel.present(context, it)) } },
        selected = model, recommended = SubtitleModel.recommended(context),
        choose = { SubtitleModel.choose(context, it); refresh(); failure = null; lighter = null; choosingModel = false },
        remove = { askRemoveModel = it }, dismiss = { choosingModel = false },
    )
}

/**
 * One caption in the block that follows playback or in the full list: its
 * time and text, marked while it shows; while it is edited, a field in its
 * place, as the original edits in place.
 */
@Composable
private fun CueRow(cue: SubtitleCue, now: Boolean, chosen: Boolean, enabled: Boolean, editing: Boolean, text: TextFieldValue, type: (TextFieldValue) -> Unit, tap: () -> Unit) {
    if (editing) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        OutlinedTextField(
            text, { if (it.text.length <= SubtitleWord.MAXIMUM_TEXT) type(it) }, Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.subtitles_edit_title) + " · " + cueTime(cue.start)) }, minLines = 2, maxLines = 6,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (now) Current.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.035f))
            .clickable(enabled = enabled, onClick = tap).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(cueTime(cue.start), color = Soft, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Text(cue.text, color = if (now || chosen) Color.White else Soft, fontSize = 14.sp, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (now) Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.subtitles_current), Modifier.size(16.dp), tint = Current)
    }
}

/** A model in the choice, with the bytes of it on the phone. */
private class ModelRow(val model: SpeechModel, val present: Long)

/**
 * The short choice between the speech models: one line each with its size and
 * whether it is on the phone, and the bin for what is. Picking one closes the
 * choice; downloading it is the tool's own button.
 */
@Composable
private fun ModelChoice(choices: List<ModelRow>, selected: SpeechModel, recommended: SpeechModel, choose: (SpeechModel) -> Unit, remove: (SpeechModel) -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.subtitles_model_title), fontSize = 18.sp) },
        text = {
            Column {
                for (row in choices) {
                    val megabytes = SubtitleModel.megabytes(row.model)
                    // Which one suits the phone stays in sight after it was downloaded, for whoever is about to pick the other.
                    val state = listOfNotNull(
                        if (row.model == recommended) stringResource(R.string.subtitles_model_recommended) else null,
                        when {
                            row.present >= row.model.bytes -> stringResource(R.string.subtitles_model_downloaded)
                            row.present > 0 -> stringResource(R.string.subtitles_model_partly, SubtitleModel.megabytes(row.present), megabytes)
                            else -> null
                        },
                    ).joinToString(" · ").ifEmpty { null }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable { choose(row.model) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = row.model == selected, onClick = { choose(row.model) })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.subtitles_model_row, row.model.name, megabytes))
                            state?.let { Text(it, color = Soft, fontSize = 12.sp) }
                        }
                        if (row.present > 0) IconButton(onClick = { remove(row.model) }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.subtitles_model_remove, row.model.name), tint = RecordRed)
                        }
                    }
                }
                Text(stringResource(R.string.subtitles_model_note), color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

/** Whether the network in use counts the data it carries, as mobile data and some Wi-Fi do. False when that cannot be told. */
private fun metered(context: android.content.Context): Boolean =
    runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }.getOrDefault(false)

@Composable
private fun Confirm(title: String, message: String?, action: String, keep: String, destructive: Boolean = false, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title, fontSize = 18.sp) },
        text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = confirm) { Text(action, color = if (destructive) RecordRed else Color.Unspecified) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(keep) } },
    )
}

/** A heading that opens and closes what is under it. */
@Composable
private fun Disclosure(label: String, open: Boolean, toggle: () -> Unit) {
    TextButton(onClick = toggle, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text((if (open) "▾ " else "▸ ") + label)
    }
}

@Composable
private fun Level(label: String, value: Double, range: ClosedFloatingPointRange<Float>, enabled: Boolean, change: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Soft, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Slider(value = value.toFloat().coerceIn(range), onValueChange = { change(it.toDouble()) }, valueRange = range, enabled = enabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = label })
    }
}
