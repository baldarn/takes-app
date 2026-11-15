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
import android.text.format.Formatter
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalMirrorMode
import androidx.camera.core.MirrorMode
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.takes.core.CaptureEvent
import app.takes.core.CaptureEventsFile
import app.takes.core.CaptureFormat
import app.takes.core.PinchZoom
import app.takes.core.Project
import app.takes.core.RecorderCamera
import app.takes.core.RecorderCameras
import app.takes.core.RecordingSpace
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
@OptIn(ExperimentalPersistentRecording::class, ExperimentalCamera2Interop::class, ExperimentalMirrorMode::class)
class CaptureController(private val context: Context, private val store: ProjectStore) {
    enum class Phase { Idle, Recording, Paused, Saving }
    /** A camera of the phone, with what it offers to the recorder: asked once, when the cameras are listed. */
    data class CameraChoice(val id: String, val label: String, val info: CameraInfo, val offers: CaptureFormat.Camera = CaptureFormat.Camera()) {
        val front: Boolean get() = info.lensFacing == CameraSelector.LENS_FACING_FRONT
    }
    /** The preview and the recording of one bind. They are built together, for the camera and the settings of the moment. */
    private class UseCases(val preview: Preview, val recorder: Recorder, val video: VideoCapture<Recorder>)
    /** An input the user can record from. The system routes by output device, so that is what is kept. */
    data class MicrophoneChoice(val id: Int, val label: String, val device: AudioDeviceInfo)

    private class Segment(val id: String, val projectID: String, val file: File) {
        val events = mutableListOf<CaptureEvent>()
        var nanos = 0L
        var reportedAt = 0L // when [nanos] was reported, on the elapsed-time clock; zero until the recording starts
        var requested: Phase? = null // null: the system ended this segment
        var abandoned = false // the recorder did not report its end in time; see [abandon]
        var indexed = false // in the project
    }

    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    /** The settings of the recorder: global, as in the original, and kept with the camera and the zooms. */
    val settings = RecorderSettings(prefs)
    /** The focus of the camera in use: tap to focus, the lock and manual focus. */
    val focus = CameraFocus(context, settings)
    private val scope = MainScope()
    private var audioSource = MediaRecorder.AudioSource.CAMCORDER
    private var surface: Preview.SurfaceProvider? = null
    /** The picture the bound camera records with: what the settings ask for, as far as this camera goes. */
    var format by mutableStateOf(CaptureFormat.of(CaptureFormat.Wish(), CaptureFormat.Camera())); private set
    private var cases = build(format, Surface.ROTATION_0)
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? by mutableStateOf(null)
    private var target: CameraChoice? = null // the camera to show, bound or not
    private var stale = false // what is bound is to be bound again: it was kept through the background, or is not what is wanted now
    // The camera is bound to this, which is always resumed, and not to the screen: the camera library takes its
    // use cases away the moment the screen stops, before the take can be stopped, and gives them back on return
    // whatever the recorder is doing. Here the camera goes and returns when [background] and [close] say so.
    private val held = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    private val inFront: Boolean get() = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    private var recording: Recording? = null
    private var segment: Segment? = null
    private var zoomRamp: Job? = null

    var phase by mutableStateOf(Phase.Idle); private set
    /** A segment is being written or saved. The original lets nobody leave the recorder meanwhile. */
    val hasActiveTake: Boolean get() = phase == Phase.Recording || phase == Phase.Saving
    var cameras by mutableStateOf(emptyList<CameraChoice>()); private set
    var cameraID by mutableStateOf<String?>(null); private set
    /** A camera is bound: its controls can be used. */
    val ready: Boolean get() = cameraID != null && camera != null
    var zoom by mutableStateOf(1.0); private set
    var zoomRange by mutableStateOf(1.0..1.0); private set
    /** Seconds a zoom preset takes. Instant until the person chooses a time, as in the original. */
    val zoomDuration: Double get() = settings.zoomSeconds
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
    /** Called when a take recorded into the middle of an edit has ended: the original returns to the editor then. */
    var onInsertionEnded: (() -> Unit)? = null
    /** Bookmarks placed in the current take. */
    var marks by mutableStateOf(0); private set

    private var deviceRotation = Surface.ROTATION_0

    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            deviceRotation = when (degrees) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            followDevice()
        }
    }

    /**
     * A take keeps the orientation it began in from the first Record to Stop, pauses
     * included, so its segments share one frame. The device is followed between takes.
     */
    private fun followDevice() {
        if (phase != Phase.Idle) return
        cases.video.targetRotation = deviceRotation
        landscape = deviceRotation == Surface.ROTATION_90 || deviceRotation == Surface.ROTATION_270
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
        this.surface = surface
        cases.preview.setSurfaceProvider(surface)
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
        release()
        target = null
        owner = null
    }

    /**
     * Another app is in front: the take is paused, and the camera is let go once its segment is saved.
     * The recorder is stopped while the camera still feeds it. Stopped after the camera has taken its
     * surface back, it loses its encoder first and never ends the segment: "Saving…" stays, and the
     * surface it is given on return finds it still stopping, which kills the app from the recorder's thread.
     */
    fun background() {
        pause()
        if (phase == Phase.Saving) stale = true else release()
    }

    /** Back in front. The camera returns now, or as soon as the segment being saved has ended. */
    fun foreground() = restore()

    /** Binds the camera when none is bound, or when what is bound was only kept for a segment to end. */
    private fun restore() {
        if (camera == null || stale) rebind()
    }

    private fun release() {
        provider?.unbindAll()
        camera = null
        focus.unbound()
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
            CameraChoice(Camera2CameraInfo.from(info).cameraId, lens, info, offers(info))
        }
        return labelled.map { choice ->
            val twins = labelled.filter { it.label == choice.label }
            if (twins.size == 1) choice else choice.copy(label = "${choice.label} ${twins.indexOf(choice) + 1}")
        }
    }

    // One at a time and in order, so the newest list is the one left on disk.
    private val eventFiles = Dispatchers.IO.limitedParallelism(1)

    /**
     * Adds an event to the segment and saves the list beside the movie at once, as the original does:
     * the marks of a take that is interrupted are then found with it when it is indexed.
     */
    private fun note(segment: Segment, event: CaptureEvent) {
        segment.events += event
        val events = segment.events.toList()
        scope.launch(eventFiles) { runCatching { CaptureEventsFile.write(events, segment.file) } }
    }

    // MARK: Picture format. One rebuild and bind for all of it, between segments and never under one.

    /** What [info] offers to the recorder. Every question is asked on its own: one that fails counts as "no". */
    private fun offers(info: CameraInfo): CaptureFormat.Camera {
        val video = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
        fun sizes(range: DynamicRange) = runCatching { video?.getSupportedQualities(range) }.getOrNull().orEmpty().mapNotNull { quality ->
            FRAME_SIZES.entries.firstOrNull { it.value == quality }?.key
        }
        val sdr = sizes(DynamicRange.SDR).mapNotNull { size ->
            runCatching { QualitySelector.getResolution(info, FRAME_SIZES.getValue(size)) }.getOrNull()?.let { size to CaptureFormat.Pixels(it.width, it.height) }
        }.toMap()
        val hdr = runCatching { video?.supportedDynamicRanges?.contains(DynamicRange.HLG_10_BIT) }.getOrNull() == true
        return CaptureFormat.Camera(
            sizes = sdr,
            hdrSizes = if (hdr) sizes(DynamicRange.HLG_10_BIT).toSet() else emptySet(),
            previewStabilization = runCatching { Preview.getPreviewCapabilities(info).isStabilizationSupported }.getOrDefault(false),
            recordingStabilization = runCatching { video?.isStabilizationSupported }.getOrNull() == true,
            thirty = runCatching { info.supportedFrameRateRanges.contains(THIRTY) }.getOrDefault(false),
        )
    }

    private val current: CameraChoice? get() = cameras.firstOrNull { it.id == cameraID }

    /** What the camera in use offers; nothing while no camera is bound. */
    val offers: CaptureFormat.Camera get() = current?.offers ?: CaptureFormat.Camera()
    /** A control of the picture format is shown only where a camera of this phone has what it sets. */
    val offersUltraHD: Boolean get() = cameras.any { it.offers.ultraHD }
    val offersHDR: Boolean get() = cameras.any { it.offers.hdr }
    val offersStabilization: Boolean get() = cameras.any { it.offers.stabilization }
    val hasFrontCamera: Boolean get() = cameras.any { it.front }

    /** The format can change now: a camera is bound and no segment is being written. */
    val canChangeFormat: Boolean get() = ready && (phase == Phase.Idle || phase == Phase.Paused)
    /** The quality and the mirroring of the saved picture stay fixed from the first Record to Stop, as in the original. */
    val canChangeTake: Boolean get() = ready && phase == Phase.Idle

    fun chooseUltraHD(on: Boolean) { if (canChangeTake && settings.ultraHD != on) { settings.ultraHD = on; rebind() } }
    fun chooseHDR(on: Boolean) { if (canChangeFormat && settings.hdr != on) { settings.hdr = on; rebind() } }
    fun chooseStabilization(on: Boolean) { if (canChangeFormat && settings.stabilization != on) { settings.stabilization = on; rebind() } }
    fun chooseMirrorPreview(on: Boolean) { if (canChangeFormat && settings.mirrorPreview != on) { settings.mirrorPreview = on; rebind() } }
    fun chooseMirrorRecording(on: Boolean) { if (canChangeTake && settings.mirrorRecording != on) { settings.mirrorRecording = on; rebind() } }

    private fun rebind() { (target ?: current)?.let(::bind) }

    /**
     * The preview and the recording for [format]. Each part of the format was asked of the camera before
     * (see [offers]), so nothing here is requested from a camera that does not have it.
     */
    private fun build(format: CaptureFormat, rotation: Int): UseCases {
        val preview = Preview.Builder()
            // Same shape as the recorded movie, so the preview shows the real frame.
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
            // A mirror for the front camera, as in the original, unless the person turned it off.
            .setMirrorMode(if (settings.mirrorPreview) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .apply { if (format.stabilization == CaptureFormat.Stabilization.PreviewAndRecording) setPreviewStabilizationEnabled(true) }
            // The focus reads the position of the lens from what the camera reports with each frame.
            .also { Camera2Interop.Extender(it).setSessionCaptureCallback(focus.results) }
            .build()
        surface?.let(preview::setSurfaceProvider)
        val quality = if (format.ultraHD) Quality.UHD else Quality.FHD
        val recorder = recorder(audioSource)
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            // The original's bit rate: six bits for each pixel of the frame. The encoder keeps it within what it accepts.
            .apply { format.bitRate?.let { setTargetVideoEncodingBitRate(it) } }
            .build()
        val video = VideoCapture.Builder(recorder)
            // Saved as the scene is, so that writing stays readable, unless the person asked for mirrored selfies.
            .setMirrorMode(if (settings.mirrorRecording) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .apply {
                if (format.fixedRate) setTargetFrameRate(THIRTY)
                if (format.hdr) setDynamicRange(DynamicRange.HLG_10_BIT)
                if (format.stabilization == CaptureFormat.Stabilization.RecordingOnly) setVideoStabilizationEnabled(true)
            }
            .build()
        video.targetRotation = rotation
        return UseCases(preview, recorder, video)
    }

    // CameraX keeps the audio source to itself (a restricted API): there is no public way
    // to record from a headset, which needs the voice communication source. Check this
    // call again whenever the camera library is updated.
    @SuppressLint("RestrictedApi")
    private fun recorder(audioSource: Int) = Recorder.Builder().setAudioSource(audioSource)

    private fun bind(choice: CameraChoice) {
        target = choice
        val provider = provider ?: return
        // A recorder is given a new surface only in front and when no segment is being saved: [close] binds then.
        if (phase == Phase.Saving || !inFront) { stale = true; return }
        stale = false
        // A persistent recording pauses while nothing is bound and resumes by itself.
        provider.unbindAll()
        // A segment being written keeps its recorder: the format changes between segments.
        val writing = phase == Phase.Recording || phase == Phase.Saving
        val wanted = CaptureFormat.of(CaptureFormat.Wish(settings.ultraHD, settings.hdr, settings.stabilization), choice.offers)
        var bound: Camera? = null
        for (attempt in if (writing) listOf(format) else CaptureFormat.attempts(wanted)) {
            if (!writing) {
                cases = build(attempt, cases.video.targetRotation)
                format = attempt
            }
            bound = try {
                provider.bindToLifecycle(held, choice.info.cameraSelector, cases.preview, cases.video)
            } catch (_: Exception) {
                provider.unbindAll()
                null
            }
            if (bound != null) break
        }
        if (bound == null) {
            camera = null
            focus.unbound()
            message = context.getString(R.string.recorder_camera_failed, choice.label)
            // Never leave the recording indicator on with no valid capture.
            if (phase == Phase.Recording) pause()
            return
        }
        // The camera said it could and then refused: the take is recorded without, and the person is told.
        if (!writing && format != wanted) message = context.getString(R.string.recorder_format_reduced)
        camera = bound
        cameraID = choice.id
        prefs.edit().putString("camera", choice.id).apply()
        // The flip button returns to the camera used last on each side of the phone.
        if (choice.front) settings.lastFrontCamera = choice.id else settings.lastRearCamera = choice.id
        val state = bound.cameraInfo.zoomState.value
        val minimum = (state?.minZoomRatio ?: 1f).toDouble()
        // Never past ten times, whatever the lens offers, as in the original.
        zoomRange = minimum..maxOf(minimum, PinchZoom.maximum((state?.maxZoomRatio ?: 1f).toDouble()))
        applyZoom(prefs.getFloat("zoom.${choice.id}", 1f).toDouble())
        focus.bound(bound, choice.id)
    }

    private fun lens(choice: CameraChoice) =
        RecorderCamera(choice.id, choice.front, choice.info.intrinsicZoomRatio.toDouble())

    /** Where the flip button leads: the camera used last on the other side of the phone; null when that side has none. */
    val oppositeCamera: CameraChoice?
        get() = RecorderCameras.opposite(cameras.map(::lens), cameraID, settings.lastFrontCamera, settings.lastRearCamera)
            ?.let { target -> cameras.firstOrNull { it.id == target.id } }

    /** From a rear ultrawide listed as a camera of its own, the camera the 1× preset changes to. */
    val mainLensAtOneX: CameraChoice?
        get() = RecorderCameras.mainAtOne(cameras.map(::lens), cameraID)?.let { target -> cameras.firstOrNull { it.id == target.id } }

    /** The camera has a zoom to offer: a range, or the main camera behind the 1× preset. */
    val hasZoom: Boolean get() = zoomRange.endInclusive > zoomRange.start || mainLensAtOneX != null

    /** Changes camera, inside the segment being written when there is one. [nativeZoom] starts the camera at its own 1×. */
    fun switchCamera(id: String, nativeZoom: Boolean = false) {
        val choice = cameras.firstOrNull { it.id == id } ?: return
        if (id == cameraID || phase == Phase.Saving) return
        if (nativeZoom) prefs.edit().putFloat("zoom.$id", 1f).apply()
        // The old camera's move must not resume when that camera is selected again.
        zoomRamp?.cancel()
        segment?.takeIf { phase == Phase.Recording }?.let {
            note(it, CaptureEvent(time = it.nanos / 1e9, kind = "camera", detail = choice.label))
        }
        bind(choice)
    }

    // MARK: Microphone. Chosen for the session; routed only while the recorder is on screen.

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
    private fun useAudioSource(source: Int) {
        if (audioSource == source) return
        audioSource = source
        rebind()
    }

    // MARK: Zoom. Each camera remembers its own last applied value.

    private fun applyZoom(value: Double, remember: Boolean = true) {
        val bounded = value.coerceIn(zoomRange)
        zoom = bounded
        camera?.cameraControl?.setZoomRatio(bounded.toFloat())
        if (remember) cameraID?.let { prefs.edit().putFloat("zoom.$it", bounded.toFloat()).apply() }
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

    /** Whether the picture can be pinched: a camera with a zoom range, and nothing being saved. */
    val canPinch: Boolean get() = ready && phase != Phase.Saving && zoomRange.endInclusive > zoomRange.start

    /**
     * A pinch beginning with the fingers [span] apart, measured from here on as the original measures it.
     * A move that is running stops where it is.
     */
    fun beginPinch(span: Double): PinchZoom? {
        if (!canPinch) return null
        zoomRamp?.cancel()
        return PinchZoom.begin(zoom, span, zoomDuration, zoomRange.start, zoomRange.endInclusive)
    }

    /** A zoom the fingers are passing through: shown at once, remembered only when they lift. */
    fun pinchZoom(value: Double) = applyZoom(value, remember = false)

    /** The fingers lifted at [target]: an instant pinch is remembered there, a timed one now runs. */
    fun endPinch(pinch: PinchZoom) {
        val target = pinch.released() ?: return
        if (pinch.timed) zoomTo(target, pinch.seconds) else applyZoom(target)
    }

    /** Sets the zoom transition time, kept within its bounds; says whether that changed it. */
    fun chooseZoomDuration(seconds: Double): Boolean {
        val before = settings.zoomSeconds
        settings.zoomSeconds = ZoomTransition.boundedDuration(seconds)
        return settings.zoomSeconds != before
    }

    // MARK: Take lifecycle

    /** Starts a take, or the next segment of a paused one. */
    fun record() {
        if (phase != Phase.Idle && phase != Phase.Paused) return
        if (camera == null) { message = context.getString(R.string.recorder_camera_not_ready); return }
        val projectID = segmentProject ?: (store.selected ?: store.create())?.id ?: return
        val folder = store.disk.folder(projectID)
        val short = space.shortBy(freeBytes(folder), folder.totalSpace, format.ultraHD)
        if (short > 0) {
            message = context.getString(R.string.recorder_storage_short, Formatter.formatShortFileSize(context, short))
            return
        }
        segmentProject = projectID
        val id = newID()
        begin(Segment(id, projectID, File(folder, "${System.currentTimeMillis()}-$id.mp4")))
    }

    private var segmentProject: String? = null

    // MARK: Free storage. The original's rule with a flat figure to start and no floor.

    private val space = RecordingSpace.ANDROID_FLAT
    private var pace = space.begin(0.0, ultraHD = false)
    private var spaceCheckedAt = 0.0

    private fun freeBytes(folder: File): Long = folder.usableSpace

    /**
     * Every few seconds while a segment is written: pauses the take while there is still room
     * to finish its file and save the project, well before the recorder would stop by itself.
     */
    private fun checkSpace(current: Segment) {
        val now = SystemClock.elapsedRealtimeNanos() / 1e9
        if (phase != Phase.Recording || !space.isDue(now, spaceCheckedAt)) return
        spaceCheckedAt = now
        pace = pace.sampled(now, current.file.length())
        val folder = current.file.parentFile
        if (space.shouldPause(folder?.let(::freeBytes), folder?.totalSpace ?: 0L, pace.bytesPerSecond)) {
            pause()
            message = context.getString(R.string.recorder_paused_storage)
        }
    }

    @SuppressLint("MissingPermission")
    private fun begin(next: Segment) {
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audioWarning = if (audio) null else context.getString(R.string.recorder_mic_off)
        level = 0f
        message = null
        var pending = cases.recorder.prepareRecording(context, FileOutputOptions.Builder(next.file).build()).asPersistentRecording()
        if (audio) pending = pending.withAudioEnabled()
        val now = SystemClock.elapsedRealtimeNanos() / 1e9
        pace = space.begin(now, format.ultraHD)
        spaceCheckedAt = now
        segment = next
        phase = Phase.Recording
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event -> handle(next, event) }
    }

    /** Marks this moment of the take, for example a mistake to find again in the editor. */
    fun bookmark() {
        val current = segment?.takeIf { phase == Phase.Recording } ?: return
        // The moment of the tap, as the original reads its clock at the tap.
        note(current, CaptureEvent(time = segmentSeconds(), kind = Project.BOOKMARK_EVENT, detail = ""))
        marks++
    }

    /**
     * Seconds written to the segment being recorded, zero when there is none. The recorder reports that
     * length once a second: between two reports it is the last one plus the time since, bounded because
     * a recording that stalls reports nothing. The clock adds it to the length of the edit.
     */
    fun segmentSeconds(): Double {
        val current = segment?.takeIf { phase == Phase.Recording } ?: return 0.0
        val since = if (current.reportedAt == 0L) 0L else (SystemClock.elapsedRealtimeNanos() - current.reportedAt).coerceIn(0L, 1_500_000_000L)
        return (current.nanos + since) / 1e9
    }

    /** Saves the running segment and keeps the take open. A Stop already asked for is not undone. */
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
        scope.launch {
            // In steps, so that time the system kept the app frozen in the background is not counted.
            repeat(SAVE_STEPS) { delay(SAVE_STEP_MILLIS) }
            if (segment === current) abandon(current)
        }
    }

    /**
     * The recorder has not reported the end of this segment, and "Saving…" would stay for good with every
     * control off. The take goes on with the recorder the next bind builds and this one is never bound
     * again. What it wrote stays on disk: it joins the project now if it can be read, or when its end is
     * reported after all.
     */
    private suspend fun abandon(current: Segment) {
        current.abandoned = true
        segment = null
        recording = null
        // [close] binds anew, or lets the camera go; either way this recorder gets no further surface.
        stale = true
        val seconds = withContext(Dispatchers.IO) { mediaDuration(current.file) }
        close(current, seconds)
        if (!current.indexed) message = context.getString(R.string.recorder_saving_late)
    }

    private fun reset() {
        // Stop ends a run of takes recorded into the middle of an edit.
        val projectID = segmentProject ?: store.selectedID
        val inserted = store.projects.firstOrNull { it.id == projectID }?.recordingInsertion != null
        projectID?.let(store::recordAtEnd)
        phase = Phase.Idle
        // The phone may have been turned during the take.
        followDevice()
        marks = 0
        segmentProject = null
        level = 0f
        if (inserted) onInsertionEnded?.invoke()
    }

    private fun handle(current: Segment, event: VideoRecordEvent) {
        when (event) {
            // The first report comes a second into the recording; time runs from here until then.
            is VideoRecordEvent.Start -> if (segment === current) current.reportedAt = SystemClock.elapsedRealtimeNanos()
            is VideoRecordEvent.Status -> if (segment === current) {
                // The system does not always report a route that changes under a running recording.
                val second = event.recordingStats.recordedDurationNanos / 1_000_000_000
                if (second != current.nanos / 1_000_000_000) showActiveMicrophone(audioManager.activeRecordingConfigurations)
                current.nanos = event.recordingStats.recordedDurationNanos
                current.reportedAt = SystemClock.elapsedRealtimeNanos()
                val audio = event.recordingStats.audioStats
                level = audio.audioAmplitude.toFloat().coerceIn(0f, 1f)
                audioWarning = when (audio.audioState) {
                    AudioStats.AUDIO_STATE_ACTIVE -> null
                    AudioStats.AUDIO_STATE_DISABLED -> context.getString(R.string.recorder_mic_off)
                    AudioStats.AUDIO_STATE_SOURCE_SILENCED -> null // the take is paused below
                    AudioStats.AUDIO_STATE_MUTED -> context.getString(R.string.recorder_mic_muted)
                    else -> context.getString(R.string.recorder_mic_stopped)
                }
                checkSpace(current)
                // A call or another app has taken the microphone: the original pauses the take rather than go on silent.
                if (audio.audioState == AudioStats.AUDIO_STATE_SOURCE_SILENCED && phase == Phase.Recording) {
                    pause()
                    message = context.getString(R.string.recorder_paused_microphone_taken)
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
        // After the last write of its events, which may still be on its way.
        if (!current.file.exists()) withContext(eventFiles) { CaptureEventsFile.delete(current.file) }
        // The take went on without waiting for this segment: it only joins the project.
        if (current.abandoned) { index(current, seconds); return }
        if (segment === current) { segment = null; recording = null }
        val requested = current.requested
        close(current, seconds)
        message = when {
            requested == null -> context.getString(if (seconds != null) R.string.recorder_stopped_saved else R.string.recorder_stopped_lost, reason(event))
            seconds == null -> context.getString(R.string.recorder_nothing_recorded)
            else -> message
        }
    }

    private fun index(current: Segment, seconds: Double?) {
        if (seconds == null || current.indexed) return
        // A mark made as the take stops can fall a moment past what was written: it belongs to the last frame.
        val last = maxOf(0.0, seconds - 1.0 / 30)
        val events = CaptureEvent.recovered(current.events.map { if (it.time >= seconds) it.copy(time = last) else it }, seconds).ifEmpty { null }
        current.indexed = store.addRecording(current.projectID, SourceMedia(id = current.id, filename = current.file.name, duration = seconds, events = events))
    }

    /** The segment has ended: the take is paused or over, as was asked, and the camera returns. */
    private fun close(current: Segment, seconds: Double?) {
        index(current, seconds)
        // The segment is in the edit now, and the clock counts it there.
        if (current.requested == Phase.Idle) reset() else { phase = Phase.Paused; level = 0f }
        dropLostMicrophone()
        if (inFront) restore() else release()
    }

    private companion object {
        // A segment ends in a second or two; after five the recorder is taken to be stuck.
        const val SAVE_STEPS = 50
        const val SAVE_STEP_MILLIS = 100L
        /** Thirty frames per second, exactly, as the original sets it. */
        val THIRTY = Range(CaptureFormat.FRAMES_PER_SECOND, CaptureFormat.FRAMES_PER_SECOND)
        val FRAME_SIZES = mapOf(
            CaptureFormat.FrameSize.UHD to Quality.UHD, CaptureFormat.FrameSize.FHD to Quality.FHD,
            CaptureFormat.FrameSize.HD to Quality.HD, CaptureFormat.FrameSize.SD to Quality.SD,
        )
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
}
