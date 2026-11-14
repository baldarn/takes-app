package app.takes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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

    private class Segment(val id: String, val projectID: String, val file: File) {
        val events = mutableListOf<CaptureEvent>()
        var nanos = 0L
        var requested: Phase? = null // null: the system ended this segment
    }

    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    private val scope = MainScope()
    private val preview = Preview.Builder().build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
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
    var zoomDuration by mutableStateOf(ZoomTransition.boundedDuration(prefs.getFloat("zoomDuration", 1f).toDouble())); private set
    var elapsed by mutableStateOf(0.0); private set
    var level by mutableStateOf(0f); private set
    var audioWarning by mutableStateOf<String?>(null); private set
    var landscape by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

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

    suspend fun attach(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        this.owner = owner
        val provider = ProcessCameraProvider.awaitInstance(context)
        if (this.owner !== owner) return
        this.provider = provider
        preview.setSurfaceProvider(surface)
        cameras = describe(provider.availableCameraInfos)
        if (cameras.isEmpty()) { message = "No camera is available."; return }
        orientation.enable()
        val remembered = prefs.getString("camera", null)
        bind(cameras.firstOrNull { it.id == remembered }
            ?: cameras.firstOrNull { it.info.lensFacing == CameraSelector.LENS_FACING_BACK }
            ?: cameras.first())
    }

    fun detach() {
        finishTake()
        zoomRamp?.cancel()
        orientation.disable()
        provider?.unbindAll()
        camera = null
        owner = null
    }

    private fun describe(infos: List<CameraInfo>): List<CameraChoice> {
        val labelled = infos.map { info ->
            val facing = when (info.lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> "Front"
                CameraSelector.LENS_FACING_BACK -> "Rear"
                else -> "External"
            }
            val lens = when {
                info.intrinsicZoomRatio < 0.9f -> " ultrawide"
                info.intrinsicZoomRatio > 1.5f -> " tele"
                else -> ""
            }
            CameraChoice(Camera2CameraInfo.from(info).cameraId, facing + lens, info)
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
            message = "The ${choice.label} camera could not be opened."
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
        if (camera == null) { message = "No camera is ready."; return }
        val projectID = segmentProject ?: (store.selected ?: store.create())?.id ?: return
        val folder = store.disk.folder(projectID)
        if (folder.usableSpace < MINIMUM_FREE_BYTES) {
            message = "Storage is almost full. Free some space before recording."
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
        audioWarning = if (audio) null else "Microphone access is off. Recording without sound."
        level = 0f
        message = null
        var pending = recorder.prepareRecording(context, FileOutputOptions.Builder(next.file).build()).asPersistentRecording()
        if (audio) pending = pending.withAudioEnabled()
        segment = next
        phase = Phase.Recording
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event -> handle(next, event) }
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
        phase = Phase.Idle
        segmentProject = null
        finished = 0.0
        elapsed = 0.0
        level = 0f
    }

    private fun handle(current: Segment, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> if (segment === current) {
                current.nanos = event.recordingStats.recordedDurationNanos
                elapsed = finished + current.nanos / 1e9
                val audio = event.recordingStats.audioStats
                level = audio.audioAmplitude.toFloat().coerceIn(0f, 1f)
                audioWarning = when (audio.audioState) {
                    AudioStats.AUDIO_STATE_ACTIVE -> null
                    AudioStats.AUDIO_STATE_DISABLED -> "Microphone access is off. Recording without sound."
                    AudioStats.AUDIO_STATE_SOURCE_SILENCED -> "Another app is using the microphone. This take is silent."
                    AudioStats.AUDIO_STATE_MUTED -> "The microphone is muted."
                    else -> "The microphone stopped working. This take may have no sound."
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
        message = when {
            requested == null -> "Recording stopped: ${reason(event)}. " + if (seconds != null) "The footage so far is saved." else "Nothing could be saved."
            seconds == null -> "Nothing was recorded."
            else -> message
        }
    }

    private fun reason(event: VideoRecordEvent.Finalize): String = when (event.error) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "storage is full"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "the file reached its size limit"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "the camera became unavailable"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "the video encoder failed"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "the recorder failed"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "no video arrived"
        else -> "an unexpected error occurred"
    }

    private companion object {
        const val MINIMUM_FREE_BYTES = 300L * 1024 * 1024
    }
}
