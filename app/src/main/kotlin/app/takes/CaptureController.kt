package app.takes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.AudioStats
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.takes.core.CaptureEvent
import app.takes.core.Project
import app.takes.core.SourceMedia
import app.takes.core.ZoomTransition
import app.takes.core.newID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Camera, zoom and take lifecycle. A take is a run of segments: Pause and Stop
 * both finalize the current movie file into the project, Resume starts the next.
 * Main thread only.
 */
@OptIn(ExperimentalPersistentRecording::class, ExperimentalCamera2Interop::class)
class CaptureController(private val context: Context, private val store: ProjectStore) {
    enum class Phase { Idle, Recording, Paused, Saving }
    data class CameraChoice(val id: String, val label: String, val info: CameraInfo)
    /** An input the user can record from. The system routes by output device, so that is what is kept. */
    data class MicrophoneChoice(val id: Int, val label: String, val device: AudioDeviceInfo)

    private class Segment(val id: String, val projectID: String, val file: File) {
        val events = mutableListOf<CaptureEvent>()
        var nanos = 0L
        var requested: Phase? = null // null: the system ended this segment
    }

    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    private val scope = MainScope()
    // Same shape as the recorded movie, so the preview shows the real frame.
    private val preview = Preview.Builder()
        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
        .build()
    private var recorder = recorder(MediaRecorder.AudioSource.CAMCORDER)
    private var videoCapture = VideoCapture.withOutput(recorder)
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var recording: Recording? = null
    private var segment: Segment? = null
    private var finished = 0.0
    private var zoomRamp: Job? = null

    var phase by mutableStateOf(Phase.Idle); private set
    var cameras by mutableStateOf(emptyList<CameraChoice>()); private set
    var cameraID by mutableStateOf<String?>(null); private set
    var zoom by mutableStateOf(1.0); private set
    var zoomRange by mutableStateOf(1.0..1.0); private set
    /** Seconds a zoom preset takes. Instant until the person chooses a time, as in the original. */
    var zoomDuration by mutableStateOf(ZoomTransition.boundedDuration(prefs.getFloat("zoomDuration", 0f).toDouble())); private set
    var elapsed by mutableStateOf(0.0); private set
    var level by mutableStateOf(0f); private set
    var audioWarning by mutableStateOf<String?>(null); private set
    var landscape by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    /** The microphone the system is recording from, while a take is running. */
    var microphone by mutableStateOf<String?>(null); private set
    /** Inputs the user can choose; empty where the system offers no choice. */
    var microphones by mutableStateOf(emptyList<MicrophoneChoice>()); private set
    /** The user's choice for this session; null leaves the choice to the system. */
    var chosenMicrophone by mutableStateOf<MicrophoneChoice?>(null); private set
    var microphoneWarning by mutableStateOf<String?>(null); private set
    /** Bookmarks placed in the current take. */
    var marks by mutableStateOf(0); private set

    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            // A movie file has one orientation; follow the device between segments only.
            if (degrees == ORIENTATION_UNKNOWN || phase == Phase.Recording || phase == Phase.Saving) return
            val rotation = when (degrees) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            videoCapture.targetRotation = rotation
            landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        }
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Show the input really in use, so a wrong microphone is noticed whatever was chosen.
    private val routes = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configurations: List<AudioRecordingConfiguration>) = showActiveMicrophone(configurations)
    }

    private fun showActiveMicrophone(configurations: List<AudioRecordingConfiguration>) {
        val active = configurations.firstNotNullOfOrNull { it.audioDevice }
        microphone = active?.let(::describe)
        val chosen = chosenMicrophone
        microphoneWarning = if (active != null && chosen != null && phase == Phase.Recording && kind(active) != kind(chosen.device)) {
            context.getString(R.string.recorder_mic_mismatch, describe(active), chosen.label)
        } else null
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshMicrophones()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshMicrophones()
    }

    private enum class Kind { Phone, Wired, Bluetooth, Usb, Other }

    /** Groups the output a route is chosen by with the input recorded from it. */
    private fun kind(device: AudioDeviceInfo) = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_TELEPHONY,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Kind.Phone
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> Kind.Bluetooth
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.Usb
        else -> Kind.Other
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

    suspend fun attach(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        this.owner = owner
        val provider = ProcessCameraProvider.awaitInstance(context)
        if (this.owner !== owner) return
        this.provider = provider
        preview.setSurfaceProvider(surface)
        cameras = describe(provider.availableCameraInfos)
        if (cameras.isEmpty()) { message = context.getString(R.string.recorder_no_camera); return }
        orientation.enable()
        audioManager.registerAudioRecordingCallback(routes, null)
        // Reports the devices already connected at once, which fills the list.
        audioManager.registerAudioDeviceCallback(devices, null)
        val remembered = prefs.getString("camera", null)
        bind(cameras.firstOrNull { it.id == remembered }
            ?: cameras.firstOrNull { it.info.lensFacing == CameraSelector.LENS_FACING_BACK }
            ?: cameras.first())
    }

    fun detach() {
        finishTake()
        zoomRamp?.cancel()
        orientation.disable()
        audioManager.unregisterAudioRecordingCallback(routes)
        audioManager.unregisterAudioDeviceCallback(devices)
        releaseMicrophone()
        microphone = null
        microphoneWarning = null
        provider?.unbindAll()
        camera = null
        owner = null
    }

    private fun describe(infos: List<CameraInfo>): List<CameraChoice> {
        val labelled = infos.map { info ->
            val facing = when (info.lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> context.getString(R.string.recorder_camera_front)
                CameraSelector.LENS_FACING_BACK -> context.getString(R.string.recorder_camera_rear)
                else -> context.getString(R.string.recorder_camera_external)
            }
            val lens = when {
                info.intrinsicZoomRatio < 0.9f -> context.getString(R.string.recorder_lens_ultrawide, facing)
                info.intrinsicZoomRatio > 1.5f -> context.getString(R.string.recorder_lens_tele, facing)
                else -> facing
            }
            CameraChoice(Camera2CameraInfo.from(info).cameraId, lens, info)
        }
        return labelled.map { choice ->
            val twins = labelled.filter { it.label == choice.label }
            if (twins.size == 1) choice else choice.copy(label = "${choice.label} ${twins.indexOf(choice) + 1}")
        }
    }

    private fun bind(choice: CameraChoice) {
        val provider = provider ?: return
        val owner = owner ?: return
        // A persistent recording pauses while nothing is bound and resumes by itself.
        provider.unbindAll()
        val bound = try {
            provider.bindToLifecycle(owner, choice.info.cameraSelector, preview, videoCapture)
        } catch (_: Exception) {
            camera = null
            message = context.getString(R.string.recorder_camera_failed, choice.label)
            // Never leave the recording indicator on with no valid capture.
            if (phase == Phase.Recording) pause()
            return
        }
        camera = bound
        cameraID = choice.id
        prefs.edit().putString("camera", choice.id).apply()
        val state = bound.cameraInfo.zoomState.value
        zoomRange = (state?.minZoomRatio ?: 1f).toDouble()..(state?.maxZoomRatio ?: 1f).toDouble()
        applyZoom(prefs.getFloat("zoom.${choice.id}", 1f).toDouble())
    }

    fun switchCamera(id: String) {
        val choice = cameras.firstOrNull { it.id == id } ?: return
        if (id == cameraID || phase == Phase.Saving) return
        // The old camera's move must not resume when that camera is selected again.
        zoomRamp?.cancel()
        segment?.takeIf { phase == Phase.Recording }?.let {
            it.events += CaptureEvent(time = it.nanos / 1e9, kind = "camera", detail = choice.label)
        }
        bind(choice)
    }

    // MARK: Microphone. Chosen for the session; routed only while the recorder is on screen.

    // CameraX keeps the audio source to itself (a restricted API): there is no public way
    // to record from a headset, which needs the voice communication source. Check this
    // call again whenever the camera library is updated.
    @SuppressLint("RestrictedApi")
    private fun recorder(audioSource: Int) = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
        .setAudioSource(audioSource)
        .build()

    /** Records from this input from the next segment on; null returns the choice to the system. */
    fun chooseMicrophone(id: Int?) {
        if (phase != Phase.Idle && phase != Phase.Paused) return
        chosenMicrophone = microphones.firstOrNull { it.id == id }
        claimMicrophone()
    }

    /** Routes to the chosen input. The route is system wide, so it is held only while the recorder shows. */
    fun claimMicrophone() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        refreshMicrophones()
        val chosen = chosenMicrophone
        if (chosen != null && !audioManager.setCommunicationDevice(chosen.device)) {
            chosenMicrophone = null
            message = context.getString(R.string.recorder_mic_not_selected, chosen.label)
        }
        if (chosenMicrophone == null) {
            releaseMicrophone()
            useAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        } else {
            holdRoute()
            // The camcorder source ignores the route; the communication source is the one that follows it.
            useAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
    }

    /** Gives the route back, so no other app is left talking through the chosen device. */
    fun releaseMicrophone() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        silence?.release()
        silence = null
        audioManager.clearCommunicationDevice()
    }

    private var silence: AudioTrack? = null

    // The system drops the route of an app that stays silent for a few seconds, and a segment started
    // after that begins on the phone microphone with a click. Playing silence keeps the route up.
    private fun holdRoute() {
        if (silence != null) return
        val frames = 8000
        silence = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
                .apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                    play()
                }
        }.getOrNull()
    }

    private fun refreshMicrophones() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        microphones = audioManager.availableCommunicationDevices
            .filter { kind(it) != Kind.Other }
            // The phone offers its speaker and its earpiece; the speaker route listens from a distance.
            .sortedBy { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            .map { MicrophoneChoice(it.id, describe(it), it) }
            .distinctBy { it.label }
        dropLostMicrophone()
    }

    /**
     * Nothing is switched under a running segment: its warning shows the input the system fell back to.
     * The choice returns to the system once nothing is being written.
     */
    private fun dropLostMicrophone() {
        val chosen = chosenMicrophone ?: return
        if (microphones.any { it.id == chosen.id }) return
        if (phase == Phase.Recording || phase == Phase.Saving) return
        chosenMicrophone = null
        message = context.getString(R.string.recorder_mic_disconnected, chosen.label)
        claimMicrophone()
    }

    // The audio source is fixed when a Recorder is built, so a new one replaces it between segments.
    @SuppressLint("RestrictedApi")
    private fun useAudioSource(source: Int) {
        if (recorder.audioSource == source) return
        val rotation = videoCapture.targetRotation
        recorder = recorder(source)
        videoCapture = VideoCapture.withOutput(recorder).apply { targetRotation = rotation }
        cameras.firstOrNull { it.id == cameraID }?.let(::bind)
    }

    // MARK: Zoom. Each camera remembers its own last applied value.

    private fun applyZoom(value: Double) {
        val bounded = value.coerceIn(zoomRange)
        zoom = bounded
        camera?.cameraControl?.setZoomRatio(bounded.toFloat())
        cameraID?.let { prefs.edit().putFloat("zoom.$it", bounded.toFloat()).apply() }
    }

    fun zoomTo(target: Double, seconds: Double = zoomDuration) {
        val bounded = target.coerceIn(zoomRange)
        if (abs(bounded - zoom) < 0.001) return
        zoomRamp?.cancel()
        if (seconds <= 0) { applyZoom(bounded); return }
        val ramp = ZoomTransition(zoom, bounded, SystemClock.elapsedRealtimeNanos() / 1e9, seconds)
        zoomRamp = scope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtimeNanos() / 1e9
                applyZoom(ramp.value(now))
                if (ramp.isFinished(now)) break
                delay(16)
            }
        }
    }

    fun pinch(scale: Float) {
        if (scale == 1f || camera == null) return
        zoomRamp?.cancel()
        applyZoom(zoom * scale)
    }

    fun chooseZoomDuration(seconds: Double) {
        zoomDuration = ZoomTransition.boundedDuration(seconds)
        prefs.edit().putFloat("zoomDuration", zoomDuration.toFloat()).apply()
    }

    // MARK: Take lifecycle

    /** Starts a take, or the next segment of a paused one. */
    fun record() {
        if (phase != Phase.Idle && phase != Phase.Paused) return
        if (camera == null) { message = context.getString(R.string.recorder_camera_not_ready); return }
        val projectID = segmentProject ?: (store.selected ?: store.create())?.id ?: return
        val folder = store.disk.folder(projectID)
        if (folder.usableSpace < MINIMUM_FREE_BYTES) {
            message = context.getString(R.string.recorder_storage_low)
            return
        }
        segmentProject = projectID
        val id = newID()
        begin(Segment(id, projectID, File(folder, "${System.currentTimeMillis()}-$id.mp4")))
    }

    private var segmentProject: String? = null

    @SuppressLint("MissingPermission")
    private fun begin(next: Segment) {
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audioWarning = if (audio) null else context.getString(R.string.recorder_mic_off)
        level = 0f
        message = null
        var pending = recorder.prepareRecording(context, FileOutputOptions.Builder(next.file).build()).asPersistentRecording()
        if (audio) pending = pending.withAudioEnabled()
        segment = next
        phase = Phase.Recording
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event -> handle(next, event) }
    }

    /** Marks this moment of the take, for example a mistake to find again in the editor. */
    fun bookmark() {
        val current = segment?.takeIf { phase == Phase.Recording } ?: return
        current.events += CaptureEvent(time = current.nanos / 1e9, kind = Project.BOOKMARK_EVENT, detail = "")
        marks++
    }

    fun pause() = end(Phase.Paused)

    /** Finalizes the take and leaves the recorder ready for a new one. */
    fun finishTake() {
        if (phase == Phase.Paused) reset() else end(Phase.Idle)
    }

    private fun end(next: Phase) {
        val current = segment ?: return
        if (phase != Phase.Recording) {
            // Already saving for Pause: a later Stop still ends the take.
            if (next == Phase.Idle) current.requested = next
            return
        }
        current.requested = next
        phase = Phase.Saving
        recording?.stop()
    }

    private fun reset() {
        // Stop ends a run of takes recorded into the middle of an edit.
        (segmentProject ?: store.selectedID)?.let(store::recordAtEnd)
        phase = Phase.Idle
        marks = 0
        segmentProject = null
        finished = 0.0
        elapsed = 0.0
        level = 0f
    }

    private fun handle(current: Segment, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> if (segment === current) {
                // The system does not always report a route that changes under a running recording.
                val second = event.recordingStats.recordedDurationNanos / 1_000_000_000
                if (second != current.nanos / 1_000_000_000) showActiveMicrophone(audioManager.activeRecordingConfigurations)
                current.nanos = event.recordingStats.recordedDurationNanos
                elapsed = finished + current.nanos / 1e9
                val audio = event.recordingStats.audioStats
                level = audio.audioAmplitude.toFloat().coerceIn(0f, 1f)
                audioWarning = when (audio.audioState) {
                    AudioStats.AUDIO_STATE_ACTIVE -> null
                    AudioStats.AUDIO_STATE_DISABLED -> context.getString(R.string.recorder_mic_off)
                    AudioStats.AUDIO_STATE_SOURCE_SILENCED -> context.getString(R.string.recorder_mic_silenced)
                    AudioStats.AUDIO_STATE_MUTED -> context.getString(R.string.recorder_mic_muted)
                    else -> context.getString(R.string.recorder_mic_stopped)
                }
            }
            is VideoRecordEvent.Finalize -> scope.launch { finalize(current, event) }
            else -> Unit
        }
    }

    private suspend fun finalize(current: Segment, event: VideoRecordEvent.Finalize) {
        val seconds = withContext(Dispatchers.IO) {
            // An empty file holds nothing to preserve; any other one is kept.
            if (current.file.length() == 0L) { current.file.delete(); null } else mediaDuration(current.file)
        }
        if (segment === current) { segment = null; recording = null }
        if (seconds != null) {
            val events = CaptureEvent.recovered(current.events, seconds).ifEmpty { null }
            if (store.addRecording(current.projectID, SourceMedia(id = current.id, filename = current.file.name, duration = seconds, events = events))) {
                finished += seconds
            }
        }
        val requested = current.requested
        if (requested == Phase.Idle) reset() else { phase = Phase.Paused; elapsed = finished; level = 0f }
        dropLostMicrophone()
        message = when {
            requested == null -> context.getString(if (seconds != null) R.string.recorder_stopped_saved else R.string.recorder_stopped_lost, reason(event))
            seconds == null -> context.getString(R.string.recorder_nothing_recorded)
            else -> message
        }
    }

    private fun reason(event: VideoRecordEvent.Finalize): String = when (event.error) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> context.getString(R.string.recorder_reason_storage_full)
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> context.getString(R.string.recorder_reason_size_limit)
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> context.getString(R.string.recorder_reason_camera_lost)
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> context.getString(R.string.recorder_reason_encoder)
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> context.getString(R.string.recorder_reason_recorder)
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> context.getString(R.string.recorder_reason_no_data)
        else -> context.getString(R.string.recorder_reason_unexpected)
    }

    private companion object {
        const val MINIMUM_FREE_BYTES = 300L * 1024 * 1024
    }
}
