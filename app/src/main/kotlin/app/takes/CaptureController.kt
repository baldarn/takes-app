package app.takes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
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
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalMirrorMode
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
import app.takes.core.MicrophoneRoute
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
    /** [effects]: the outputs the recorder's own pass over the frames ([CameraFrames]) draws, as [CameraEffect] targets; none when 0. */
    private class UseCases(val preview: Preview, val recorder: Recorder, val video: VideoCapture<Recorder>, val effects: Int = 0)
    /**
     * An input the user can record from. The system routes by output device, so that is what is kept.
     * [key] is the same at every connection, which the system's own id is not.
     */
    data class MicrophoneChoice(val key: String, val label: String, val device: AudioDeviceInfo)
    /** A place in the order of preference; [connected] is false for a microphone that is remembered and not there. */
    data class MicrophonePreference(val key: String, val label: String, val connected: Boolean)

    private class Segment(val id: String, val projectID: String, val file: File) {
        val events = mutableListOf<CaptureEvent>()
        var nanos = 0L
        var reportedAt = 0L // when [nanos] was reported, on the elapsed-time clock; zero until the recording starts
        var requestedAt = 0.0 // when the recording was asked for, in seconds of the same clock
        var sound = false // recorded with the microphone
        var requested: Phase? = null // null: the system ended this segment
        var abandoned = false // the recorder did not report its end in time; see [abandon]
        var indexed = false // in the project
        var microphone: String? = null // the microphone it began with, whose recording gain it is given
    }

    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    /** The settings of the recorder: global, as in the original, and kept with the camera and the zooms. */
    val settings = RecorderSettings(prefs)
    /** The focus of the camera in use: tap to focus, the lock and manual focus. */
    val focus = CameraFocus(context, settings)
    /** The recorder's own pass over the frames, for the level horizon, follow face and focus peaking. */
    val frames = CameraFrames(context).apply { onFollowStatus = { followStatus = it } }
    /** What follow face is doing, for its tile. */
    var followStatus by mutableStateOf(CameraFrames.FollowStatus.Searching); private set
    /** The recording gain of the microphone in use, in decibels. */
    var recordingGain by mutableStateOf(0.0); private set
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
    /** The level of the microphone, before a take and during it. The screen reads it at each frame. */
    val meter = MicrophoneMeter()
    /** There is sound to measure: the permission to record is given. */
    var meterAvailable by mutableStateOf(false); private set
    var audioWarning by mutableStateOf<String?>(null); private set
    /**
     * How the interface stands: the display's rotation, a `Surface.ROTATION_*`. The interface turns with the phone,
     * except upside down, and is held from the first Record to Stop, as the original's (YouRecApp.swift:77-110,
     * RecorderView.swift:341-390); the take is recorded the way it stands.
     */
    var rotation by mutableStateOf(Surface.ROTATION_0); private set
    var message by mutableStateOf<String?>(null)
    /** The name of the microphone in use: the one sound comes from when the system tells, else the one asked for. */
    var microphone by mutableStateOf<String?>(null); private set
    /** The key of that microphone among [microphones]. */
    var microphoneKey by mutableStateOf<String?>(null); private set
    /** Inputs the user can choose; empty where the system offers no choice. */
    var microphones by mutableStateOf(emptyList<MicrophoneChoice>()); private set
    /** The remembered order of preference, best first, with the microphones that are not connected. */
    var microphonePriority by mutableStateOf(emptyList<MicrophonePreference>()); private set
    var microphoneWarning by mutableStateOf<String?>(null); private set
    /** Called when a take recorded into the middle of an edit has ended: the original returns to the editor then. */
    var onInsertionEnded: (() -> Unit)? = null
    /** Bookmarks placed in the current take. */
    var marks by mutableStateOf(0); private set

    private val displays = context.getSystemService(DisplayManager::class.java)
    private val display get() = displays.getDisplay(Display.DEFAULT_DISPLAY)

    // A half turn from one landscape to the other changes no configuration: the display says it.
    private val turns = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) followDisplay()
        }
    }

    /**
     * The interface follows the phone between takes; while a take is under way the activity is held as it stands
     * (RecorderScreen), so a take keeps the orientation it began in from the first Record to Stop, pauses included,
     * and its segments share one frame, as in the original (RecorderView.swift:350-355, CaptureController.swift:1207-1209).
     * Only the frame of the preview and of the recording is set, unless an assist draws the frames.
     */
    private fun followDisplay() {
        val now = display?.rotation ?: return
        val turned = now != rotation
        rotation = now
        cases.preview.targetRotation = now
        if (phase != Phase.Idle) return
        cases.video.targetRotation = now
        // The pass over the frames is given its pictures' turn when it is bound: with an assist on, the camera is
        // bound again for the new turn, between takes only.
        if (turned && cases.effects != 0) rebind() else updateFrames()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Show the input really in use, so a wrong microphone is noticed whatever was chosen.
    private val routes = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configurations: List<AudioRecordingConfiguration>) = showActiveMicrophone(configurations)
    }

    private fun showActiveMicrophone(configurations: List<AudioRecordingConfiguration>) {
        val active = configurations.firstNotNullOfOrNull { it.audioDevice }
        actualMicrophone = active
        // The order of preference decides again with what the system reports: see [refreshMicrophones].
        refreshMicrophones()
        val asked = microphones.firstOrNull { it.key == requestedMicrophone }
        microphoneWarning = if (active != null && asked != null && phase == Phase.Recording && key(active) != asked.key) {
            context.getString(R.string.recorder_mic_mismatch, describe(active), asked.label)
        } else null
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshMicrophones()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            // The system says the device is gone: the wait before another microphone is used is the short one.
            if (removed.any { kind(it) != Kind.Other }) route.deviceDisconnected(listOfNotNull(microphoneKey))
            refreshMicrophones()
        }
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

    /** What a microphone is known by across connections: its kind, and its name where the kind has several. */
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

    suspend fun attach(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        this.owner = owner
        val provider = ProcessCameraProvider.awaitInstance(context)
        if (this.owner !== owner) return
        this.provider = provider
        this.surface = surface
        cases.preview.setSurfaceProvider(surface)
        cameras = describe(provider.availableCameraInfos)
        if (cameras.isEmpty()) { message = context.getString(R.string.recorder_no_camera); return }
        displays.registerDisplayListener(turns, null)
        followDisplay()
        audioManager.registerAudioRecordingCallback(routes, null)
        // Reports the devices already connected at once, which fills the list.
        audioManager.registerAudioDeviceCallback(devices, null)
        attached = true
        val remembered = prefs.getString("camera", null)
        bind(cameras.firstOrNull { it.id == remembered }
            ?: cameras.firstOrNull { it.info.lensFacing == CameraSelector.LENS_FACING_BACK }
            ?: cameras.first())
        listen()
    }

    fun detach() {
        finishTake()
        zoomRamp?.cancel()
        displays.unregisterDisplayListener(turns)
        audioManager.unregisterAudioRecordingCallback(routes)
        audioManager.unregisterAudioDeviceCallback(devices)
        attached = false
        routeCheck?.cancel()
        meter.stopListening()
        releaseMicrophone()
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
        meter.stopListening()
        if (phase == Phase.Saving) stale = true else release()
    }

    /** Back in front. The camera returns now, or as soon as the segment being saved has ended. */
    fun foreground() {
        restore()
        listen()
    }

    /**
     * Measures the microphone while the recorder shows and no segment holds it, as the original's meter
     * runs before a take: with the source the next segment records from, and only in front.
     */
    private fun listen() {
        meterAvailable = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (attached && inFront && meterAvailable && (phase == Phase.Idle || phase == Phase.Paused)) meter.listen(audioSource) else meter.stopListening()
    }

    /** Binds the camera when none is bound, or when what is bound was only kept for a segment to end. */
    private fun restore() {
        if (camera == null || stale) rebind()
    }

    private fun release() {
        provider?.unbindAll()
        camera = null
        focus.unbound()
        frames.listenToGravity(false)
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
    private fun build(format: CaptureFormat, rotation: Int, effects: Int = 0): UseCases {
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
        return UseCases(preview, recorder, video, effects)
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
        // The frame assists stay as they were bound while a segment is written: they change between segments.
        val effects = if (writing) cases.effects else assistTargets(choice)
        // The pass over the frames draws 8 bits a colour: a take it changes is recorded in SDR.
        val hdr = settings.hdr && effects and CameraEffect.VIDEO_CAPTURE == 0
        val wanted = CaptureFormat.of(CaptureFormat.Wish(settings.ultraHD, hdr, settings.stabilization), choice.offers)
        var bound: Camera? = null
        // Without the assists as a last resort: the camera that refuses them still records.
        val tries = if (writing) listOf(format to effects) else CaptureFormat.attempts(wanted).map { it to effects } +
            if (effects != 0) CaptureFormat.attempts(wanted).take(1).map { it to 0 } else emptyList()
        for ((attempt, assists) in tries) {
            if (!writing) {
                cases = build(attempt, cases.video.targetRotation, assists)
                format = attempt
            }
            bound = try {
                if (cases.effects == 0) provider.bindToLifecycle(held, choice.info.cameraSelector, cases.preview, cases.video)
                else provider.bindToLifecycle(
                    held, choice.info.cameraSelector,
                    UseCaseGroup.Builder().addUseCase(cases.preview).addUseCase(cases.video).addEffect(frames.effect(cases.effects)).build(),
                )
            } catch (_: Exception) {
                provider.unbindAll()
                null
            }
            if (bound != null) break
        }
        if (bound != null && effects != 0 && cases.effects == 0) {
            message = context.getString(R.string.recorder_frames_failed)
            settings.levelHorizon = false
            settings.followFace = false
            settings.focusPeaking = false
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
        updateFrames()
    }

    // MARK: Frame assists. The level horizon, follow face and focus peaking, drawn by [frames] while one is on.

    /** The level horizon is the rear cameras', as in the original, and needs to know where down is. */
    private fun levels(choice: CameraChoice?): Boolean = settings.levelHorizon && choice != null && !choice.front && frames.levelAvailable

    /** What the frames are drawn for when [choice] is bound: the recording too for the level and the follow, the preview alone for peaking. */
    private fun assistTargets(choice: CameraChoice): Int = when {
        levels(choice) || settings.followFace -> CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
        settings.focusPeaking -> CameraEffect.PREVIEW
        else -> 0
    }

    /** The bound camera's frames go through [frames] into the recording as well. */
    private val framesRecorded: Boolean get() = camera != null && cases.effects and CameraEffect.VIDEO_CAPTURE != 0

    /** Follow face crops a lens kept at its widest, so the face can be followed across all of it: the zoom chosen is the crop's. */
    private val followsDigitally: Boolean get() = framesRecorded && settings.followFace

    /** The level horizon can be used with the camera in use. */
    val canLevel: Boolean get() = !(current?.front ?: true) && frames.levelAvailable
    val levelling: Boolean get() = framesRecorded && levels(current)
    /** A rear camera of this phone can level the horizon: the tile and its settings are shown. */
    val offersLevel: Boolean get() = frames.levelAvailable && cameras.any { !it.front }
    /** The frames of the take go through [frames], which records them in SDR. */
    val framesInRecording: Boolean get() = framesRecorded
    /** Follow face can be turned on or off now: between segments, or at any time once the frames go into the recording. */
    val canChangeFollow: Boolean get() = ready && phase != Phase.Saving && (canChangeFormat || framesRecorded)

    fun chooseLevelHorizon(on: Boolean) {
        if (!canChangeFormat || settings.levelHorizon == on || (on && !canLevel)) return
        settings.levelHorizon = on
        refreshAssists()
    }

    fun chooseHorizonDegrees(degrees: Double) {
        settings.horizonDegrees = app.takes.core.HorizonFraming.degrees(degrees)
        updateFrames()
    }

    fun chooseFollowFace(on: Boolean) {
        if (!canChangeFollow || settings.followFace == on) return
        settings.followFace = on
        if (canChangeFormat) refreshAssists() else applyZoom(zoom)
    }

    /** Focus peaking, from a long press on the focus button; between segments unless the frames already pass through [frames]. */
    fun toggleFocusPeaking() {
        val on = !settings.focusPeaking
        if (on && camera != null && cases.effects == 0 && !canChangeFormat) {
            focus.show(context.getString(R.string.recorder_peaking_paused_only))
            return
        }
        settings.focusPeaking = on
        focus.show(context.getString(if (on) R.string.recorder_peaking_on else R.string.recorder_peaking_off))
        if (canChangeFormat) refreshAssists() else updateFrames()
    }

    /** Binds again when what the frames are drawn for has changed, so that with no assist on nothing is in the way. */
    private fun refreshAssists() {
        val choice = current ?: return
        if (assistTargets(choice) != cases.effects) rebind() else applyZoom(zoom)
    }

    /** Tells [frames] what to do with the next frames. */
    private fun updateFrames() {
        val digital = followsDigitally
        val level = if (levelling) settings.horizonDegrees else null
        frames.assists = CameraFrames.Assists(
            levelDegrees = level,
            follow = framesRecorded && settings.followFace,
            peaking = camera != null && cases.effects != 0 && settings.focusPeaking,
            stabilized = format.stabilization != CaptureFormat.Stabilization.Off,
            upright = camera?.cameraInfo?.getSensorRotationDegrees(cases.video.targetRotation) ?: 90,
            inputZoom = if (digital) zoomRange.start else zoom,
            minimumZoom = zoomRange.start,
            selectedZoom = if (digital) zoom else null,
        )
        frames.listenToGravity(level != null && inFront)
    }

    /** Sets the recording gain of the microphone in use, from the next segment's sound on. */
    fun chooseRecordingGain(db: Double) {
        val key = microphoneKey ?: PHONE_MICROPHONE
        settings.setRecordingGain(key, db)
        showGain()
    }

    private fun showGain() {
        recordingGain = settings.recordingGain(microphoneKey ?: PHONE_MICROPHONE)
        meter.gain = Math.pow(10.0, recordingGain / 20)
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

    // MARK: Microphone. The remembered order decides; a choice by hand lasts until Stop. Routed only while the recorder is on screen.

    private var attached = false
    // One from each Stop to the next, as in the original.
    private var route = MicrophoneRoute()
    private var routeCheck: Job? = null
    /** The microphone the system last accepted a request for; none before the first request. */
    private var requestedMicrophone: String? = null
    /** The input the system says a recording comes from: the meter's or the segment's. */
    private var actualMicrophone: AudioDeviceInfo? = null

    /** Records from this microphone from the next segment on, until Stop: then the order of preference rules again. */
    fun chooseMicrophone(key: String) {
        if (phase != Phase.Idle && phase != Phase.Paused) return
        if (microphones.none { it.key == key }) return
        route.select(key)
        refreshMicrophones()
    }

    /** Moves a microphone one place earlier (−1) or later (+1) in the remembered order. */
    fun moveMicrophonePreference(key: String, offset: Int) {
        settings.microphonePriority = MicrophoneRoute.moved(settings.microphonePriority, key, offset)
        refreshMicrophones()
    }

    /** Takes the route again on returning to the recorder. The route is system wide, so it is held only while the recorder shows. */
    fun claimMicrophone() {
        // What was asked for before is not held any more.
        requestedMicrophone = null
        refreshMicrophones()
    }

    /** Gives the route back, so no other app is left talking through the chosen device. */
    fun releaseMicrophone() {
        routeCheck?.cancel()
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

    /**
     * Looks at the microphones once, as the original does at every change the system reports and after each
     * wait its route asks for: keeps the order of preference and the names, asks the system for the microphone
     * the route decides on, and says when one was lost or refused.
     */
    private fun refreshMicrophones() {
        val active = actualMicrophone
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // No choice below Android 12: the system's microphone is shown as it is.
            microphone = active?.let(::describe) ?: context.getString(R.string.recorder_mic_phone)
            showGain()
            return
        }
        if (!attached) return
        val found = audioManager.availableCommunicationDevices
            .filter { kind(it) != Kind.Other }
            // The phone offers its speaker and its earpiece; the speaker route listens from a distance.
            .sortedBy { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            .map { MicrophoneChoice(key(it), describe(it), it) }
            .distinctBy { it.key }
        microphones = found
        val connected = found.map { it.key }
        // Where the system does not say what it records from, it is taken to be what was asked for.
        val actual = active?.let(::key) ?: requestedMicrophone
        settings.microphonePriority = MicrophoneRoute.priority(settings.microphonePriority, connected, actual)
        settings.microphoneNames = settings.microphoneNames + found.associate { it.key to it.label }
        val names = settings.microphoneNames
        microphonePriority = settings.microphonePriority.map { MicrophonePreference(it, names[it] ?: it, it in connected) }
        val ranked = MicrophoneRoute.available(settings.microphonePriority, connected)
        microphoneKey = actual ?: ranked.firstOrNull()
        microphone = active?.let(::describe) ?: names[microphoneKey]
        showGain()
        // The route is asked for only in front: it is the whole system's.
        if (!inFront) return
        val decision = route.resolve(ranked, actual, requestedMicrophone, inTake = phase != Phase.Idle, now = SystemClock.elapsedRealtimeNanos() / 1e9)
        routeCheck?.cancel()
        decision.recheckAfter?.let { seconds ->
            routeCheck = scope.launch {
                delay((seconds * 1000).toLong().coerceAtLeast(1))
                routeCheck = null
                refreshMicrophones()
            }
        }
        decision.request?.let { wanted -> found.firstOrNull { it.key == wanted }?.let(::request) }
        val using = active?.let(::describe) ?: names[actual]
        decision.failed?.let { refused ->
            val name = names[refused] ?: context.getString(R.string.recorder_mic_external)
            message = if (using != null) context.getString(R.string.recorder_mic_refused_using, name, using) else context.getString(R.string.recorder_mic_refused, name)
        }
        decision.disconnected?.let { lost ->
            val text = context.getString(R.string.recorder_mic_lost_using, names[lost] ?: context.getString(R.string.recorder_mic_external), using.orEmpty())
            segment?.takeIf { phase == Phase.Recording }?.let { note(it, CaptureEvent(time = segmentSeconds(), kind = MicrophoneRoute.FALLBACK_EVENT, detail = text)) }
            message = text
        }
    }

    /**
     * Asks the system for [choice]. The phone's own microphones are recorded with the camcorder source and no
     * route; any other needs the route and the communication source, the only one that follows it.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun request(choice: MicrophoneChoice) {
        if (choice.key == PHONE_MICROPHONE) {
            silence?.release()
            silence = null
            audioManager.clearCommunicationDevice()
            requestedMicrophone = choice.key
            useAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        } else if (audioManager.setCommunicationDevice(choice.device)) {
            requestedMicrophone = choice.key
            holdRoute()
            useAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
    }

    // The audio source is fixed when a Recorder is built, so a new one replaces it between segments:
    // at once when nothing is being written, else when the segment has ended (see [close]).
    private fun useAudioSource(source: Int) {
        if (audioSource == source) return
        audioSource = source
        if (phase == Phase.Recording || phase == Phase.Saving) stale = true else rebind()
        listen()
    }

    // MARK: Zoom. Each camera remembers its own last applied value.

    private fun applyZoom(value: Double, remember: Boolean = true) {
        val bounded = value.coerceIn(zoomRange)
        zoom = bounded
        // Follow face keeps the lens at its widest and makes the zoom with its crop.
        camera?.cameraControl?.setZoomRatio((if (followsDigitally) zoomRange.start else bounded).toFloat())
        updateFrames()
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
        // The recorder takes the microphone now; its reports move the meter from here on.
        meter.stopListening()
        meter.newRun()
        message = null
        var pending = cases.recorder.prepareRecording(context, FileOutputOptions.Builder(next.file).build()).asPersistentRecording()
        if (audio) pending = pending.withAudioEnabled()
        val now = SystemClock.elapsedRealtimeNanos() / 1e9
        next.requestedAt = now
        next.sound = audio
        next.microphone = microphoneKey ?: PHONE_MICROPHONE
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
     * length with each frame it writes: between two reports it is the last one plus the time since, bounded
     * because a recording that stalls reports nothing. The clock adds it to the length of the edit.
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
        followDisplay()
        marks = 0
        segmentProject = null
        // A choice of microphone made by hand lasts until Stop, as in the original.
        route = MicrophoneRoute()
        refreshMicrophones()
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
                // The peak of the recorder's latest buffer of sound; nothing is counted while none arrives.
                if (audio.audioState == AudioStats.AUDIO_STATE_ACTIVE) meter.reported(audio.audioAmplitude)
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
                // The microphone failed and no sound has come for more than a second: the original pauses the take
                // rather than go on silent. Only on a failure the recorder names: it also says "disabled" while it starts.
                val failed = audio.audioState == AudioStats.AUDIO_STATE_SOURCE_ERROR || audio.audioState == AudioStats.AUDIO_STATE_ENCODER_ERROR
                if (current.sound && failed && phase == Phase.Recording && meter.silentTooLong(current.requestedAt)) {
                    pause()
                    message = context.getString(R.string.recorder_paused_no_sound)
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
        // The recording gain of its microphone, as the level of the take's clip: the file stays as it was recorded.
        val gain = settings.recordingGain(current.microphone)
        current.indexed = store.addRecording(current.projectID, SourceMedia(id = current.id, filename = current.file.name, duration = seconds, events = events), gain)
    }

    /** The segment has ended: the take is paused or over, as was asked, and the camera returns. */
    private fun close(current: Segment, seconds: Double?) {
        index(current, seconds)
        // The segment is in the edit now, and the clock counts it there.
        if (current.requested == Phase.Idle) reset() else phase = Phase.Paused
        if (inFront) restore() else release()
        // The recorder has let the microphone go.
        meter.newRun()
        listen()
    }

    private companion object {
        /** The key of the phone's own microphones in the order of preference. */
        const val PHONE_MICROPHONE = "phone"
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
