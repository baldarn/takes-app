package app.takes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.takes.core.RecordingSpace
import app.takes.core.VoiceRecording
import app.takes.core.WaveFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** The key of the phone's own microphones, as the camera recorder names it (A/CaptureController.kt). */
private const val PHONE_MICROPHONE = "phone"

private val Mint = Color(0xFF66D4CF)
private val Soft = Color(0xFFA0A4AB)
private val Warning = Color(0xFFFF9F0A)

/**
 * Add audio at the playhead, as the original's sheet (YouRec/AudioToolsEditor.swift:5-65): "Record now" opens
 * the voice recorder, "Choose audio from Files" the system's picker ([chooseFile]). [inserted] is called once a
 * recording is on the timeline; [close] leaves without adding anything.
 */
@Composable
internal fun AddAudio(store: ProjectStore, projectID: String, position: Double, chooseFile: () -> Unit, inserted: () -> Unit, close: () -> Unit) {
    var recording by remember { mutableStateOf(false) }
    if (recording) {
        VoiceRecordingDialog(store, projectID, position, cancel = { recording = false }, inserted = inserted)
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.editor_add_audio)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.add_audio_at, timeLabel(position)), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.add_audio_note), color = Soft)
                Button(
                    onClick = { recording = true }, Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                ) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.add_audio_record_now), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = chooseFile, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = Mint)
                    Text(stringResource(R.string.add_audio_files), Modifier.padding(start = 8.dp), color = Mint)
                }
                Text(stringResource(R.string.add_audio_files_note), color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** A microphone the recorder can be asked for, known by the same keys as the camera recorder's. */
internal data class VoiceInput(val key: String, val label: String, val device: AudioDeviceInfo)

/**
 * The takes of one voice recording, as the original's draft (YouRec/VoiceRecorder.swift:5-251). Each take is
 * finished before it is heard or before the input changes; every take has the same format, so joining them
 * copies their samples one after another. The takes live in the app's cache until they are added or thrown away.
 */
internal class VoiceDraft(context: Context) {
    enum class Transport { Stopped, Recording, Playing }

    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var transport by mutableStateOf(Transport.Stopped); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var inputs by mutableStateOf(emptyList<VoiceInput>()); private set
    var inputKey by mutableStateOf<String?>(null); private set
    var denied by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var chosen: String? = null
    private var routed = false
    private var take: Take? = null
    private var player: MediaPlayer? = null
    private val parts = mutableListOf<File>()
    private val directory = File(workspace(this.context), UUID.randomUUID().toString())
    private val joined = File(directory, "Joined/Voice recording.wav")

    /** One take being written: the recorder, its file and its thread. */
    private class Take(val record: AudioRecord, val file: File, val maxFrames: Long) {
        @Volatile var running = true
        @Volatile var frames = 0L
        @Volatile var level = 0.0
        @Volatile var failed = false
        var key: String? = null
        var thread: Thread? = null
    }

    val hasAudio: Boolean get() = duration > 0 || transport == Transport.Recording

    fun clock(): Double = when (transport) {
        Transport.Recording -> duration + VoiceRecording.seconds(take?.frames ?: 0)
        Transport.Playing -> (runCatching { player?.currentPosition }.getOrNull() ?: 0) / 1000.0
        Transport.Stopped -> duration
    }

    fun level(): Double = take?.level ?: 0.0

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshInputs()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshInputs()
    }

    // Another app or a call silences the microphone: the take ends there, as the original's interruption does.
    private val silencing = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val current = take ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val session = runCatching { current.record.audioSessionId }.getOrNull() ?: return
            if (configs.any { it.clientAudioSessionId == session && it.isClientSilenced }) interrupt(context.getString(R.string.voice_paused_by_system))
        }
    }

    private var opened = false

    /** Starts following the microphones; the permission to record is asked for by the screen. */
    fun open(granted: Boolean) {
        denied = !granted
        if (!granted || opened) return
        opened = true
        audio.registerAudioDeviceCallback(devices, main)
        audio.registerAudioRecordingCallback(silencing, main)
        refreshInputs()
    }

    private enum class Kind { Phone, Wired, Bluetooth, Usb, Other }

    private fun kind(device: AudioDeviceInfo) = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_TELEPHONY -> Kind.Phone
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> Kind.Bluetooth
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.Usb
        else -> Kind.Other
    }

    // The camera recorder's keys and names (A/CaptureController.kt), so a microphone is called the same in both.
    private fun key(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
        return when (kind(device)) {
            Kind.Phone -> PHONE_MICROPHONE
            Kind.Wired -> "wired"
            Kind.Bluetooth -> "bluetooth:$name"
            Kind.Usb -> "usb:$name"
            Kind.Other -> "other:$name"
        }
    }

    private fun describe(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return when (kind(device)) {
            Kind.Phone -> context.getString(R.string.recorder_mic_phone)
            Kind.Wired -> context.getString(R.string.recorder_mic_wired)
            Kind.Bluetooth -> name?.let { context.getString(R.string.recorder_mic_named, "Bluetooth", it) } ?: "Bluetooth"
            Kind.Usb -> name?.let { context.getString(R.string.recorder_mic_named, "USB", it) } ?: "USB"
            Kind.Other -> name ?: context.getString(R.string.recorder_mic_external)
        }
    }

    /**
     * The inputs, the phone's own first. A Bluetooth headset is reached through the communication route, which
     * Android offers from version 12; before it only the inputs that need no route are listed.
     */
    private fun refreshInputs() {
        val found = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { kind(it) != Kind.Other && (kind(it) != Kind.Bluetooth || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) }
            .sortedBy { kind(it) != Kind.Phone }
            .map { VoiceInput(key(it), describe(it), it) }
            .distinctBy { it.key }
        inputs = found
        if (chosen != null && found.none { it.key == chosen }) chosen = null
        val current = take
        inputKey = current?.key ?: chosen ?: found.firstOrNull()?.key
        // The input in use went away: the take ends, as the original's does on a route change (VoiceRecorder.swift:84-86).
        if (current != null && current.key != null && found.none { it.key == current.key }) interrupt(context.getString(R.string.voice_mic_changed))
    }

    /** The microphone for the next take (VoiceRecorder.swift:89-95). */
    fun select(input: VoiceInput) {
        chosen = input.key
        inputKey = input.key
    }

    private fun freeBytes(): Long = directory.parentFile?.usableSpace ?: 0L

    /** Starts a take (VoiceRecorder.swift:97-131). The caller has the permission to record. */
    @SuppressLint("MissingPermission")
    fun record() {
        stopPlayback()
        error = null
        try {
            val input = inputs.firstOrNull { it.key == (chosen ?: inputKey) } ?: inputs.firstOrNull() ?: throw Failure(R.string.voice_no_microphone)
            directory.mkdirs()
            // Joined takes are joined again with the new one.
            joined.delete()
            // The recorder keeps the floor the camera recorder keeps on this platform (C/RecordingSpace.kt ANDROID_FLAT).
            val floor = RecordingSpace.ANDROID_FLAT.floor(directory.totalSpace)
            val maxSeconds = VoiceRecording.maxSeconds(freeBytes(), floor, duration, firstTake = parts.isEmpty())
            if (!VoiceRecording.canRecord(maxSeconds)) throw Failure(R.string.voice_no_space)
            val source = route(input)
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(VoiceRecording.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(VoiceRecording.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum, BUFFER_FRAMES * VoiceRecording.BYTES_PER_FRAME * 4)).build()
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: throw Failure(R.string.voice_could_not_start)
            record.setPreferredDevice(input.device)
            val file = File(directory, UUID.randomUUID().toString() + ".wav")
            val next = Take(record, file, (maxSeconds * VoiceRecording.SAMPLE_RATE).toLong())
            runCatching { record.startRecording() }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                throw Failure(R.string.voice_could_not_start)
            }
            next.key = record.routedDevice?.let(::key) ?: input.key
            inputKey = next.key
            next.thread = thread(name = "voice-take") { write(next) }
            take = next
            transport = Transport.Recording
        } catch (failure: Failure) {
            error = context.getString(failure.resource)
        }
    }

    /**
     * Asks the system for [input] and gives the audio source that records from it: the phone's microphone and
     * wired or USB ones as they are, a Bluetooth headset through the communication route, as the camera
     * recorder does (A/CaptureController.kt `request`).
     */
    private fun route(input: VoiceInput): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && kind(input.device) == Kind.Bluetooth) {
            val name = input.device.productName?.toString()
            val output = audio.availableCommunicationDevices.firstOrNull { kind(it) == Kind.Bluetooth && it.productName?.toString() == name }
            if (output != null && audio.setCommunicationDevice(output)) {
                routed = true
                return MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
        } else if (routed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
            routed = false
        }
        return MediaRecorder.AudioSource.MIC
    }

    /** The take's thread: samples to the file as they come, the level of each buffer, and the end when the storage is full. */
    private fun write(current: Take) {
        val samples = ShortArray(BUFFER_FRAMES)
        val bytes = ByteArray(BUFFER_FRAMES * VoiceRecording.BYTES_PER_FRAME)
        var reason: Int? = null
        try {
            current.file.outputStream().buffered(1 shl 16).use { out ->
                out.write(WaveFile.header(0))
                while (current.running) {
                    val wanted = minOf(samples.size.toLong(), current.maxFrames - current.frames).toInt()
                    if (wanted <= 0) { reason = R.string.voice_stopped_for_space; break }
                    val read = current.record.read(samples, 0, wanted)
                    if (read < 0) { reason = R.string.voice_interrupted; break }
                    for (index in 0 until read) {
                        val value = samples[index].toInt()
                        bytes[index * 2] = value.toByte()
                        bytes[index * 2 + 1] = (value shr 8).toByte()
                    }
                    out.write(bytes, 0, read * 2)
                    current.frames += read
                    current.level = VoiceRecording.level(VoiceRecording.averagePower(samples, read))
                }
            }
        } catch (_: Exception) {
            current.failed = true
            reason = R.string.voice_interrupted
        }
        reason?.let { message -> main.post { if (take === current) interrupt(context.getString(message)) } }
    }

    /** Ends the take and keeps it when it holds sound (VoiceRecorder.swift:133-150); false when it could not be kept. */
    fun stopRecording(): Boolean {
        val current = take ?: return true
        take = null
        current.running = false
        runCatching { current.record.stop() }
        runCatching { current.thread?.join(2000) }
        runCatching { current.record.release() }
        transport = Transport.Stopped
        inputKey = chosen ?: inputs.firstOrNull()?.key
        return try {
            val frames = WaveFile.finish(current.file)
            if (frames <= 0) throw Failure(R.string.voice_no_sound)
            parts += current.file
            duration += VoiceRecording.seconds(frames)
            true
        } catch (failure: Exception) {
            error = if (failure is Failure) context.getString(failure.resource) else context.getString(R.string.voice_interrupted)
            current.file.delete()
            false
        }
    }

    fun play(file: File) {
        val next = MediaPlayer()
        try {
            next.setDataSource(file.path)
            next.prepare()
            next.setOnCompletionListener { if (player === it) stopPlayback() }
            next.setOnErrorListener { mp, _, _ ->
                if (player === mp) { stopPlayback(); error = context.getString(R.string.voice_playback_interrupted) }
                true
            }
            next.start()
        } catch (_: Exception) {
            next.release()
            throw Failure(R.string.voice_could_not_play)
        }
        player = next
        transport = Transport.Playing
    }

    fun stopPlayback() {
        val current = player ?: return
        player = null
        runCatching { current.stop() }
        current.release()
        transport = Transport.Stopped
    }

    /** Ends what is going on and says why (VoiceRecorder.swift:168-171). */
    fun interrupt(message: String) {
        if (transport == Transport.Recording && stopRecording()) error = message
        stopPlayback()
    }

    /**
     * The file that is heard and added: a recording of one take is ready as it is; several are joined once
     * (VoiceRecorder.swift:198-235).
     */
    suspend fun audioFile(): File {
        val first = parts.firstOrNull() ?: throw Failure(R.string.voice_record_first)
        if (parts.size == 1) return first
        if (joined.exists()) return joined
        val takes = parts.toList()
        withContext(Dispatchers.IO) {
            val space = takes.sumOf { it.length() }
            if (freeBytes() < space) throw Failure(R.string.voice_no_space)
            WaveFile.join(takes, joined) { ensureActive() }
        }
        return joined
    }

    /** Throws every take away (VoiceRecorder.swift:237-244). */
    fun discard() {
        stopPlayback()
        take?.let { current ->
            take = null
            current.running = false
            runCatching { current.record.stop() }
            runCatching { current.thread?.join(2000) }
            runCatching { current.record.release() }
        }
        parts.clear()
        duration = 0.0
        transport = Transport.Stopped
        error = null
        directory.deleteRecursively()
    }

    /** Leaves the recorder: the takes are thrown away and the route is given back (VoiceRecorder.swift:246-254). */
    fun close() {
        discard()
        if (opened) {
            audio.unregisterAudioDeviceCallback(devices)
            audio.unregisterAudioRecordingCallback(silencing)
            opened = false
        }
        if (routed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        routed = false
    }

    class Failure(val resource: Int) : Exception()

    companion object {
        /** A tenth of a second at a time, so the clock and the meter move smoothly. */
        private const val BUFFER_FRAMES = 4_800

        @Volatile private var cleaned = false

        /**
         * Where takes are written. Once per process it is emptied: only takes nobody added can be there from
         * before, since an added recording lives in its project (VoiceRecorder.swift:23-29).
         */
        fun workspace(context: Context): File {
            val folder = File(context.cacheDir, "VoiceDrafts")
            synchronized(this) {
                if (!cleaned) { folder.deleteRecursively(); cleaned = true }
            }
            return folder
        }
    }
}

private fun recordGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The voice recorder of the editor, "Record now" (YouRec/VoiceRecorder.swift:256-414): a microphone to choose,
 * the clock and the level, Record, Record more and Stop, Play to hear the takes, Start over, and Done, which puts
 * the takes joined at [position] as a sound of the project in one Undo step. It records with the permission the
 * camera recorder already has.
 */
@Composable
internal fun VoiceRecordingDialog(store: ProjectStore, projectID: String, position: Double, cancel: () -> Unit, inserted: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val resources = LocalResources.current
    val draft = remember { VoiceDraft(context) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(recordGranted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        draft.open(granted)
    }
    val recordingName = stringResource(R.string.voice_file_name)
    val failedToAdd = stringResource(R.string.voice_add_failed)

    // On opening, and on coming back to the app, as the original asks each time it becomes active (VoiceRecorder.swift:351-353).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (draft.transport == VoiceDraft.Transport.Stopped) {
                    permitted = recordGranted(context)
                    if (permitted) draft.open(true) else if (!draft.denied) ask.launch(Manifest.permission.RECORD_AUDIO)
                }
                // In the background the take ends and is kept (VoiceRecorder.swift:362-367).
                Lifecycle.Event.ON_STOP -> {
                    if (!saving) job?.cancel()
                    draft.interrupt(resources.getString(R.string.voice_paused))
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            job?.cancel()
            draft.close()
            view.keepScreenOn = false
        }
    }
    val recording = draft.transport == VoiceDraft.Transport.Recording
    val playing = draft.transport == VoiceDraft.Transport.Playing
    LaunchedEffect(recording) { view.keepScreenOn = recording }

    // The clock and the level, read once a frame while something runs.
    var clock by remember { mutableDoubleStateOf(0.0) }
    var level by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(draft.transport, draft.duration) {
        clock = draft.clock()
        level = draft.level()
        while (draft.transport != VoiceDraft.Transport.Stopped) {
            withFrameNanos { }
            clock = draft.clock()
            level = draft.level()
        }
        clock = draft.clock()
        level = 0.0
    }

    fun askToDiscard(leave: Boolean) {
        if (!draft.hasAudio) { if (leave) cancel(); return }
        draft.stopRecording()
        draft.stopPlayback()
        leaving = leave
        confirmDiscard = true
    }

    fun useTake(save: Boolean) {
        if (job != null || !draft.stopRecording()) return
        draft.stopPlayback()
        draft.error = null
        saving = save
        job = scope.launch {
            try {
                val file = draft.audioFile()
                if (save) {
                    val problem = store.insertRecording(projectID, file, recordingName, position)
                    if (problem == null) inserted() else draft.error = problem.ifBlank { failedToAdd }
                } else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    draft.play(file)
                }
            } catch (_: CancellationException) {
            } catch (failure: VoiceDraft.Failure) {
                draft.error = resources.getString(failure.resource)
            } catch (_: Exception) {
                draft.error = resources.getString(R.string.voice_interrupted)
            } finally {
                job = null
            }
        }
    }

    val busy = job != null
    Dialog(onDismissRequest = { if (!busy) askToDiscard(leave = true) }, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { askToDiscard(leave = true) }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
                Text(stringResource(R.string.voice_title), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                TextButton(onClick = { useTake(save = true) }, enabled = draft.hasAudio && !busy) {
                    Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally) {
                MicrophoneMenu(draft, enabled = !recording && draft.inputs.isNotEmpty() && !busy)
                val elapsed = stringResource(R.string.voice_elapsed)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        timeLabel(clock), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = elapsed; stateDescription = timeLabel(clock) },
                    )
                    LinearProgressIndicator(
                        progress = { level.toFloat() }, modifier = Modifier.width(120.dp).alpha(if (recording) 1f else 0f),
                        color = Mint, trackColor = Color.White.copy(alpha = 0.15f), drawStopIndicator = {},
                    )
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    val title = when (VoiceRecording.recordTitle(recording, draft.hasAudio)) {
                        VoiceRecording.RecordTitle.Stop -> R.string.voice_stop
                        VoiceRecording.RecordTitle.RecordMore -> R.string.voice_record_more
                        VoiceRecording.RecordTitle.Record -> R.string.voice_record
                    }
                    Button(
                        onClick = { if (recording) draft.stopRecording() else draft.record() },
                        Modifier.weight(1f).heightIn(min = 44.dp), enabled = !busy && !draft.denied && permitted,
                        colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                    ) {
                        Icon(if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp))
                        Text(stringResource(title), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = { if (playing) draft.stopPlayback() else useTake(save = false) },
                        Modifier.widthIn(min = 72.dp).heightIn(min = 44.dp), enabled = draft.hasAudio && !recording && !busy,
                    ) {
                        Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text(stringResource(if (playing) R.string.voice_stop else R.string.voice_play), Modifier.padding(start = 6.dp))
                    }
                }
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (saving) R.string.voice_adding else R.string.voice_preparing), color = Color.White, fontSize = 14.sp)
                    }
                    draft.denied || !permitted -> {
                        Text(stringResource(R.string.voice_denied), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text(stringResource(R.string.recorder_open_settings)) }
                    }
                    draft.error != null -> Text(draft.error.orEmpty(), color = Warning, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                if (draft.hasAudio) {
                    TextButton(onClick = { askToDiscard(leave = false) }, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp)) {
                        Icon(Icons.Outlined.Replay, null, Modifier.size(18.dp), tint = Soft)
                        Text(stringResource(R.string.voice_start_over), Modifier.padding(start = 6.dp), color = Soft)
                    }
                }
                Spacer(Modifier.size(4.dp))
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.voice_discard_title)) },
            text = { Text(stringResource(R.string.voice_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    draft.discard()
                    if (leaving) cancel()
                }) { Text(stringResource(R.string.voice_discard), color = RecordRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.voice_keep_take)) } },
        )
    }
}

/** The microphone for the next take, with the one in use ticked (VoiceRecorder.swift:369-386). */
@Composable
private fun MicrophoneMenu(draft: VoiceDraft, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val name = draft.inputs.firstOrNull { it.key == draft.inputKey }?.label ?: stringResource(R.string.voice_microphone)
    val hint = stringResource(R.string.voice_mic_hint)
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "$name. $hint" }) {
            Icon(Icons.Filled.Mic, null, Modifier.size(18.dp), tint = Mint)
            Text(name, Modifier.padding(horizontal = 8.dp), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        DropdownMenu(open, { open = false }) {
            draft.inputs.forEach { input ->
                DropdownMenuItem(
                    { Text(input.label) }, { draft.select(input); open = false },
                    leadingIcon = { if (input.key == draft.inputKey) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                )
            }
        }
    }
}
