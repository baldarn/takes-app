package app.takes

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.takes.core.CaptureEffectsFile
import app.takes.core.ChromaScreen
import app.takes.core.EffectPalette
import app.takes.core.EffectValue
import app.takes.core.IsolationHue
import app.takes.core.LiveVideoEffects
import app.takes.core.VideoEffectBackgroundPlacement
import app.takes.core.VideoEffectPlacementGesture
import app.takes.core.CaptureEvent
import app.takes.core.CaptureEventsFile
import app.takes.core.CaptureFormat
import app.takes.core.DollyHint
import app.takes.core.DollyMeasurement
import app.takes.core.DollyZoomFollower
import app.takes.core.DollyZoomMove
import app.takes.core.LiveCrashState
import app.takes.core.LiveCrashZooms
import app.takes.core.LiveDollyState
import app.takes.core.Point
import app.takes.core.Rect
import app.takes.core.SubjectSample
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
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
        var moves = emptyList<VideoEffectSettings>() // its live effects and Crash Zooms, as last saved beside it
    }

    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    /** The settings of the recorder: global, as in the original, and kept with the camera and the zooms. */
    val settings = RecorderSettings(prefs)
    /** The focus of the camera in use: tap to focus, the lock and manual focus. */
    val focus = CameraFocus(context, settings).apply { onZoom = { time, zoom -> frames.reportZoom(time, zoom) } }
    /** The recorder's own pass over the frames, for the level horizon, follow face, focus peaking and the camera moves. */
    val frames = CameraFrames(context).apply {
        onFollowStatus = { followStatus = it }
        onSubject = { subject(it) }
        onLookQuality = { note, warm -> lookQuality = note; lookWarm = warm }
        onPalette = { id, palette -> palettePicked(id, palette) }
    }
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
        // A camera set up afresh has no subject chosen yet, as in the original (CaptureController.swift:1760).
        subjectPoint = null
        frames.target(null)
        dollyMeasurement = null
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

    // MARK: Live camera moves: Crash Zoom and Dolly Zoom (CaptureController.swift:212-233, 1382-1389, 2005-2106).

    /** The live video effects: the moves' settings, chosen in the recorder's Video effects panel. */
    val liveEffects: List<VideoEffectSettings> get() = settings.liveVideoEffects
    private fun template(kind: VideoEffectID) = liveEffects.firstOrNull { it.enabled && it.kind == kind }
    /** The Crash Zoom button shows. */
    val crashAvailable: Boolean get() = template(VideoEffectID.CrashZoom) != null
    /** The Dolly Zoom button shows. */
    val dollyAvailable: Boolean get() = template(VideoEffectID.DollyZoom) != null
    /** The subject is looked for: for a Dolly Zoom, and for a Crash Zoom that follows a face. LiveVideoEffects.swift:532. */
    private val tracksSubject: Boolean get() = dollyAvailable || template(VideoEffectID.CrashZoom)?.toggle("trackFace") == true

    var crashState by mutableStateOf(LiveCrashState.Ready); private set
    val canTriggerCrash: Boolean get() = ready && phase != Phase.Saving && (crashState == LiveCrashState.Ready || crashState == LiveCrashState.Holding)
    var dollyState by mutableStateOf(LiveDollyState.Ready); private set
    var dollyHint by mutableStateOf(DollyHint.TapFace); private set
    /** The room left for the move, from 1 to 0. */
    var dollyHeadroom by mutableDoubleStateOf(1.0); private set
    val canTriggerDolly: Boolean get() = ready && phase != Phase.Saving && dollyState != LiveDollyState.Stopping
    /** A tap on the picture chooses the subject of a move, rather than focusing. CaptureController.swift:3236. */
    val choosesSubject: Boolean get() = crashAvailable && crashState == LiveCrashState.Ready || dollyAvailable && !dollyState.moving

    /** The zooms the Dolly Zoom works between without upscaling, as the panel says them. CaptureController.swift:1761-1764. */
    val dollyCleanRange: ClosedFloatingPointRange<Double> get() = zoomRange.start..cleanZoom

    // The lens shows its own picture, unscaled, up to its 1×: the camera library says no more of where upscaling begins.
    private val cleanZoom: Double get() = maxOf(zoomRange.start, minOf(zoomRange.endInclusive, 1.0))

    /** Changes the live effects, between takes. LiveVideoEffects.swift:181-193. */
    fun setLiveEffects(effects: List<VideoEffectSettings>) {
        if (hasActiveTake || effects.any { !it.valid }) return
        val before = settings.liveVideoEffects
        fun moves(list: List<VideoEffectSettings>) = list.filter { it.kind == VideoEffectID.DollyZoom || it.kind == VideoEffectID.CrashZoom }
        val dollyChanged = before.firstOrNull { it.kind == VideoEffectID.DollyZoom } != effects.firstOrNull { it.kind == VideoEffectID.DollyZoom }
        settings.liveVideoEffects = effects
        if (dollyChanged) stopDolly(immediate = false, reason = DollyHint.TapFace)
        if (moves(before) != moves(effects)) {
            // Other moves: the one under way is dropped, and the subject looked for again.
            crash = LiveCrashZooms()
            movesOrigin = SystemClock.elapsedRealtimeNanos()
            frames.target(subjectPoint)
        }
        publishMoves()
        if (LiveVideoEffects.looks(before) != LiveVideoEffects.looks(effects)) frames.resetLooks()
        publishLooks()
        if (canChangeFormat) refreshAssists() else updateFrames()
    }

    // The Crash Zooms, on the effect clock: the segment's seconds while one is recorded, else the preview's from [movesOrigin].
    private var crash = LiveCrashZooms()
    private var movesOrigin = SystemClock.elapsedRealtimeNanos()
    private var crashTicker: Job? = null
    /** The subject tapped, in the recorded frame. */
    private var subjectPoint: Point? = null
    /** The subject's centre in the recorded frame, and when it was seen. */
    private var subjectCenter: Pair<Point, Double>? = null

    private fun effectTime(): Double = (SystemClock.elapsedRealtimeNanos() - movesOrigin) / 1e9

    /**
     * The segment's clock runs from the recorder's start, as [segmentSeconds] does: set once, so that it never goes
     * back under a move.
     */
    private fun anchorMoves() {
        if (phase != Phase.Recording) return
        movesOrigin = SystemClock.elapsedRealtimeNanos()
        frames.moves = CameraFrames.Moves(crash, movesOrigin)
    }

    /** Tells [frames] the moves, and follows the state of the one under way for the button. */
    private fun publishMoves() {
        frames.moves = CameraFrames.Moves(crash, movesOrigin)
        crashState = crash.state(effectTime())
        if (crashTicker?.isActive == true) return
        crashTicker = scope.launch {
            while (crash.moves.any { it.end > effectTime() }) {
                crashState = crash.state(effectTime())
                delay(33)
            }
            crashState = crash.state(effectTime())
        }
    }

    /**
     * Saves the live effects and the Crash Zooms of [current] beside its movie, at once, as the original does when the
     * segment begins and at each Crash Zoom; a picture they show is copied beside it. LiveVideoEffects.swift:281-310, 375-382.
     */
    private fun saveRecipe(current: Segment) {
        val recipe = LiveVideoEffects.recipe(liveEffects, crash.moves)
        if (recipe.isEmpty()) return
        current.moves = recipe
        val pictures = LiveVideoEffects.mediaFiles(liveEffects)
        val source = frames.effectsFolder
        scope.launch(eventFiles) {
            runCatching {
                for (name in pictures) {
                    val target = File(current.file.parentFile, name)
                    if (!target.exists()) File(source, name).takeIf { it.isFile }?.copyTo(target)
                }
                CaptureEffectsFile.write(recipe, current.file)
            }
        }
    }

    // MARK: Live looks: the other effects of the panel, drawn over the preview (LiveVideoEffects.swift, CaptureController.swift:254-366).

    /** The note on the looks' quality, and whether the phone is warm (CaptureController.swift:209-210). */
    var lookQuality by mutableStateOf<LiveVideoEffects.QualityNote?>(null); private set
    var lookWarm by mutableStateOf(false); private set

    /** A frame tool under way: the effect whose colour is picked from the preview, and its control. */
    var colorPick by mutableStateOf<Pair<String, String>?>(null); private set
    /** The Quantization whose palette is taken from the next frame, and whether it is saved. */
    var paletteSampling by mutableStateOf<String?>(null); private set
    private var paletteSave = false
    private var paletteTimeout: Job? = null
    /** The effect whose picture is being placed on the preview, with the placement so far. */
    var positioning by mutableStateOf<String?>(null); private set
    var placement by mutableStateOf<VideoEffectBackgroundPlacement?>(null); private set
    private var positionDraft: VideoEffectSettings? = null
    /** A frame tool could not be used, or a sample was not a colour the effect takes. */
    var effectWarning by mutableStateOf<String?>(null)

    /** A frame tool is open over the preview: the panel steps aside for it. RecorderView.swift:86. */
    val frameToolActive: Boolean get() = colorPick != null || positioning != null

    /** Tells [frames] the looks to draw: those set, or while a tool is open what it is chosen against. */
    private fun publishLooks() {
        val effects = liveEffects
        val shown = colorPick?.let { (id, _) -> LiveVideoEffects.prefix(effects, id) }
            ?: positioning?.let { id -> LiveVideoEffects.prefix(effects, id) + listOfNotNull(positionDraft?.copy(enabled = true)) }
            ?: LiveVideoEffects.looks(effects)
        frames.looks = CameraFrames.Looks(shown, recording = phase == Phase.Recording, tool = colorPick != null || positioning != null)
    }

    /** The looks need the frames to pass through the recorder's own pass. */
    private val drawsLooks: Boolean get() = LiveVideoEffects.drawsLook(liveEffects) || frameToolActive

    /** Tries a Negative Strobe in the preview. CaptureController.swift:287. */
    fun tryStrobe(id: String) { if (!hasActiveTake) frames.tryStrobe(id) }

    /** The panel shows another effect's controls: a strobe being tried stops. CaptureController.swift:266-270. */
    fun endStrobe() { frames.endStrobe() }

    /** Ends a frame tool without its result. CaptureController.swift:367-376. */
    fun cancelFrameTools() {
        val changed = colorPick != null || positioning != null
        colorPick = null
        positioning = null
        placement = null
        positionDraft = null
        paletteSampling = null
        paletteTimeout?.cancel()
        if (changed) {
            publishLooks()
            if (canChangeFormat) refreshAssists()
        }
    }

    /** Picks [parameter] of [id] from the preview: the next tap on it. CaptureController.swift:335-341. */
    fun beginColorPick(id: String, parameter: String) {
        if (hasActiveTake) return
        cancelFrameTools()
        stopDolly(immediate = false, reason = DollyHint.TapFace)
        colorPick = id to parameter
        publishLooks()
        if (canChangeFormat) refreshAssists()
    }

    /** The tap of a colour pick, at [point] of the recorded frame. LiveVideoEffects.swift:256-263, CaptureController.swift:516-528. */
    fun pickColor(point: Point) {
        val (id, parameter) = colorPick ?: return
        frames.pickColor(point.x, point.y) { color ->
            if (colorPick?.first != id || hasActiveTake) return@pickColor
            val effect = liveEffects.firstOrNull { it.id == id }
            cancelFrameTools()
            if (color == null || effect == null) { effectWarning = context.getString(R.string.live_effects_tool_failed); return@pickColor }
            if (parameter == "screenColor" && !ChromaScreen.accepts(color)) { effectWarning = context.getString(R.string.live_effects_screen_invalid); return@pickColor }
            if (effect.kind == VideoEffectID.ColorIsolation && parameter == "color" && !IsolationHue.accepts(color)) { effectWarning = context.getString(R.string.isolation_invalid_hue); return@pickColor }
            var next = effect.with(parameter, EffectValue.Color(color))
            if (parameter == "screenColor") next = next.with("autoScreen", EffectValue.Toggle(false)).with("screenType", EffectValue.Choice("Auto"))
            setLiveEffects(liveEffects.map { if (it.id == id) next else it })
        }
    }

    /** Takes a Quantization's palette from the next frame: locks it, and with [save] keeps it as Saved. CaptureController.swift:325-334. */
    fun sampleEffectPalette(id: String, save: Boolean = false) {
        if (hasActiveTake) return
        cancelFrameTools()
        paletteSampling = id
        paletteSave = save
        frames.samplePalette(id)
        paletteTimeout = scope.launch {
            delay(6000)
            if (paletteSampling == id) {
                cancelFrameTools()
                effectWarning = context.getString(R.string.live_effects_palette_failed)
            }
        }
    }

    private fun palettePicked(id: String, palette: EffectPalette?) {
        if (paletteSampling != id || hasActiveTake) return
        val save = paletteSave
        cancelFrameTools()
        val effect = liveEffects.firstOrNull { it.id == id } ?: return
        if (palette == null || palette.colors.size != effect.number("colors").toInt()) { effectWarning = context.getString(R.string.live_effects_palette_failed); return }
        var next = effect.copy(lockedPalette = palette).with("lockPalette", EffectValue.Toggle(true))
        if (save) next = next.copy(palette = palette.colors).with("palette", EffectValue.Choice("Saved"))
        setLiveEffects(liveEffects.map { if (it.id == id) next else it })
    }

    /** Places the picked picture of [id] on the preview with the fingers. CaptureController.swift:288-300. */
    fun beginBackgroundPosition(id: String) {
        if (hasActiveTake) return
        val effect = liveEffects.firstOrNull { it.id == id } ?: return
        val name = (effect.value("media", 0.0) as? EffectValue.Media)?.file ?: return
        val picture = EffectPictures.get(File(frames.effectsFolder, name)) ?: run { effectWarning = context.getString(R.string.live_effects_tool_failed); return }
        val canvas = frameSize ?: run { effectWarning = context.getString(R.string.live_effects_wait_preview); return }
        cancelFrameTools()
        stopDolly(immediate = false, reason = DollyHint.TapFace)
        placement = VideoEffectBackgroundPlacement(
            effect.number("backgroundX"), effect.number("backgroundY"), effect.number("backgroundZoom"),
            canvas, app.takes.core.Size(picture.width.toDouble(), picture.height.toDouble()), fill = effect.choice("fit") != "Fit",
        )
        positionDraft = effect
        positioning = id
        publishLooks()
        if (canChangeFormat) refreshAssists()
    }

    /** A drag or a pinch on the preview while placing a picture. CaptureController.swift:302-314. */
    fun moveBackground(gesture: VideoEffectPlacementGesture) {
        val current = placement ?: return
        val next = current.applying(gesture)
        placement = next
        positionDraft = positionDraft?.with("backgroundX", EffectValue.Number(next.x))?.with("backgroundY", EffectValue.Number(next.y))
            ?.with("backgroundZoom", EffectValue.Number(next.zoom))
        publishLooks()
    }

    /** Centre: the picture back in the middle at its smallest. */
    fun resetBackgroundPosition() {
        val current = placement ?: return
        placement = current.copy(x = 0.5, y = 0.5, zoom = 1.0)
        moveBackground(VideoEffectPlacementGesture.Ended)
    }

    /** Done keeps the placement, Revert leaves the effect as it was. CaptureController.swift:315-324. */
    fun finishBackgroundPosition(revert: Boolean = false) {
        val draft = positionDraft
        cancelFrameTools()
        if (!revert && draft != null) setLiveEffects(liveEffects.map { if (it.id == draft.id) draft.copy(enabled = it.enabled) else it })
    }

    /** The recorded frame's size, upright, for placing a picture on it. */
    private val frameSize: app.takes.core.Size?
        get() {
            val info = cases.preview.resolutionInfo ?: return null
            val size = info.resolution
            return if (info.rotationDegrees % 180 != 0) app.takes.core.Size(size.height.toDouble(), size.width.toDouble())
            else app.takes.core.Size(size.width.toDouble(), size.height.toDouble())
        }

    /** A picture from the phone for a look's background, kept in the live effects' folder: its name, or null. */
    suspend fun importLivePicture(uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val extension = when (bounds.outMimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heif", "image/heic" -> "heic"
                else -> "jpg"
            }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            // Named as the editor names a picture it keeps, so that it is the same file once beside a take.
            val file = File(frames.effectsFolder, "effect-picture-${hash.take(32)}.$extension")
            if (!file.isFile) app.takes.core.ProjectDisk.writeAtomically(bytes, file)
            file.name
        } catch (_: Exception) {
            null
        }
    }

    /** Follows the phone's heat, for the looks' lighter processing (LiveVideoEffects.swift:495-498). */
    private fun followHeat() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val power = context.getSystemService(android.os.PowerManager::class.java) ?: return
        frames.thermal = power.currentThermalStatus
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { frames.thermal = it }
    }

    init {
        followHeat()
        publishLooks()
    }

    /**
     * Crash Zoom's button or a volume key: a push toward the subject, or the return of the one holding. Toward the
     * subject tapped; else, following a face, the face seen now, so that the editor, which does not follow faces, has
     * it too. Says whether anything happened. CaptureController.swift:216, LiveVideoEffects.swift:354-372.
     */
    fun triggerCrashZoom(): Boolean {
        if (!canTriggerCrash) return false
        val time = effectTime()
        val template = template(VideoEffectID.CrashZoom)
        val seen = subjectCenter?.takeIf { SystemClock.elapsedRealtimeNanos() / 1e9 - it.second <= 0.5 }?.first
        val target = subjectPoint ?: seen?.takeIf { template?.toggle("trackFace") == true }
        crash = crash.triggered(time, template, target) ?: return false
        publishMoves()
        segment?.takeIf { phase == Phase.Recording }?.let(::saveRecipe)
        return true
    }

    /** A tap on the picture at [point] of the recorded frame chooses the subject. CaptureController.swift:217. */
    fun selectSubject(point: Point) {
        subjectPoint = point
        frames.target(point)
    }

    private var dollyFollower: DollyZoomFollower? = null
    private var dollyLens = ""
    private var dollyStretch = true
    private var dollyClean = 1.0
    private var dollyEndsAt = Double.POSITIVE_INFINITY
    private var dollyPreparation: Job? = null
    private var dollyMeasurement: DollyMeasurement? = null
    private var dollyLastUI = Double.NEGATIVE_INFINITY
    private var dollyStopReason: DollyHint? = null
    private var dollyTicker: Job? = null
    private var probe: Job? = null

    private fun seconds(): Double = SystemClock.elapsedRealtimeNanos() / 1e9

    /** A look for the subject: Crash Zoom's target, and Dolly Zoom's measurement. CaptureController.swift:2056-2073. */
    private fun subject(report: CameraFrames.Subject) {
        report.framed?.let { subjectCenter = it.center to report.time }
        if (probe?.isActive == true) return
        report.whole?.let { measure(it, report.time, report.zoom) }
    }

    private fun measure(subject: SubjectSample, time: Double, lensZoom: Double) {
        val lens = cameraID ?: return
        val measurement = DollyMeasurement(subject, time, lensZoom, lens)
        if (!DollyZoomMove.accepts(measurement, lens, dollyMeasurement, dollyFollower != null)) return
        dollyMeasurement = measurement
        if (lens == dollyLens) dollyFollower?.observe(subject.box.height, lensZoom, time)
    }

    private fun freshMeasurement(): DollyMeasurement? =
        dollyMeasurement?.takeIf { it.lens == cameraID && seconds() - it.time < DollyZoomMove.FRESH }

    /** Dolly Zoom's button: starts a move, or holds the one under way. CaptureController.swift:2005-2032. */
    fun triggerDollyZoom() {
        if (!canTriggerDolly) return
        val template = template(VideoEffectID.DollyZoom) ?: return
        if (dollyFollower != null || dollyPreparation != null) { stopDolly(immediate = false, reason = DollyHint.MoveHeld); return }
        if (!ready || !inFront) return
        val range = DollyZoomMove.range(zoomRange.start, zoomRange.endInclusive, cleanZoom)
        if (range == null) { dollyHint = DollyHint.MoreRange; return }
        val measurement = freshMeasurement()
        if (measurement == null) { dollyHint = DollyHint.TapVisible; return }
        zoomRamp?.cancel()
        dollyClean = cleanZoom
        dollyStretch = template.choice("direction") != "Compress"
        val current = zoom
        val desired = DollyZoomMove.prepared(dollyStretch, current, range.endInclusive, measurement.subject.box.height)
        if (DollyZoomMove.needsPreparation(desired, current)) {
            dollyState = LiveDollyState.Preparing
            dollyHint = DollyHint.HoldStill
            zoomTo(desired, DollyZoomMove.PREPARE_SECONDS, forDolly = true)
            dollyPreparation = scope.launch {
                delay((DollyZoomMove.ARM_DELAY * 1000).toLong())
                dollyPreparation = null
                armDolly(template, range)
            }
        } else armDolly(template, range)
    }

    /** CaptureController.swift:2033-2055. */
    private fun armDolly(template: VideoEffectSettings, range: ClosedFloatingPointRange<Double>) {
        val measurement = freshMeasurement()
        if (measurement == null || !ready || !inFront) {
            dollyState = LiveDollyState.Ready
            dollyHint = DollyHint.KeepInFrame
            return
        }
        val now = seconds()
        if (zoom !in range) { dollyState = LiveDollyState.Ready; dollyHint = DollyHint.ZoomOut; return }
        if (!DollyZoomMove.hasRoom(dollyStretch, zoom, range)) { dollyState = LiveDollyState.Ready; dollyHint = DollyHint.LeaveRoom; return }
        dollyLens = cameraID ?: return
        dollyStopReason = null
        dollyFollower = DollyZoomFollower(measurement.subject.box.height * zoom / measurement.zoom, zoom, range, measurement.time)
        dollyEndsAt = now + template.number("duration")
        dollyLastUI = Double.NEGATIVE_INFINITY
        segment?.takeIf { phase == Phase.Recording }?.let {
            note(it, CaptureEvent(time = segmentSeconds(), kind = "dollyStart", detail = String.format(java.util.Locale.ROOT, "Dolly Zoom on %s at %.2f×", current?.label ?: "", zoom)))
        }
        dollyState = LiveDollyState.Following
        dollyHint = if (dollyStretch) DollyHint.WalkToward else DollyHint.WalkAway
        dollyTicker?.cancel()
        // At each picture, as the original advances the move with each frame.
        dollyTicker = scope.launch { while (dollyFollower != null) { advanceDolly(); delay(33) } }
    }

    /** CaptureController.swift:2074-2090. */
    private fun advanceDolly() {
        val follower = dollyFollower ?: return
        if (cameraID != dollyLens || !ready || !inFront) { stopDolly(immediate = true, reason = DollyHint.MoveStopped); return }
        val now = seconds()
        if (now >= dollyEndsAt) { follower.stop(now); if (dollyStopReason == null) dollyStopReason = DollyHint.MoveComplete }
        val value = follower.value(now)
        applyZoom(value, remember = false)
        if (follower.hasStopped(now)) { stopDolly(immediate = true, reason = dollyStopReason ?: DollyHint.SubjectLost); return }
        if (now - dollyLastUI >= 0.2) {
            dollyLastUI = now
            val remaining = DollyZoomMove.remaining(dollyStretch, value, follower.range)
            dollyState = if (follower.isStopping) LiveDollyState.Stopping else LiveDollyState.Following
            dollyHint = DollyZoomMove.hint(follower.isStopping, dollyMeasurement?.subject?.face, remaining, value, dollyClean, dollyStretch)
            dollyHeadroom = remaining
        }
    }

    /** Ends a move: [immediate]ly, or easing to a hold first. CaptureController.swift:2091-2106. */
    private fun stopDolly(immediate: Boolean, reason: DollyHint) {
        val wasMoving = dollyFollower != null || dollyPreparation != null
        dollyPreparation?.let { it.cancel(); dollyPreparation = null; zoomRamp?.cancel() }
        val follower = dollyFollower
        if (!immediate && follower != null) {
            dollyStopReason = reason
            follower.stop(seconds())
            dollyState = LiveDollyState.Stopping
            dollyHint = DollyHint.Holding
            return
        }
        if (follower != null) {
            // The framing reached is the camera's zoom from now on.
            applyZoom(zoom)
            segment?.takeIf { phase == Phase.Recording }?.let {
                note(it, CaptureEvent(time = segmentSeconds(), kind = "dollyEnd", detail = String.format(java.util.Locale.ROOT, "%s at %.2f×", reason.words, zoom)))
            }
        }
        dollyFollower = null
        dollyTicker?.cancel()
        dollyTicker = null
        dollyEndsAt = Double.POSITIVE_INFINITY
        dollyState = if (wasMoving) LiveDollyState.Holding else LiveDollyState.Ready
        dollyHint = if (wasMoving) reason else DollyHint.TapFace
    }

    /**
     * The development build's stand-in for a person walking, as the original's `probeDollyMeasurement`
     * (CaptureController.swift:2883-2892): a face 0.18 of the frame high at one metre, seen from [from] metres to [to]
     * over [seconds], fifteen times a second, in place of what the camera sees.
     */
    private fun probeDolly(from: Double, to: Double, seconds: Double) {
        probe?.cancel()
        probe = scope.launch {
            val start = seconds()
            while (true) {
                val now = seconds()
                val f = ((now - start) / seconds).coerceIn(0.0, 1.0)
                val distance = from + (to - from) * f
                val lensZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio?.toDouble() ?: zoom
                val height = 0.18 / distance * lensZoom
                measure(SubjectSample(now, Rect(0.4, 0.5 - height / 2, 0.2, height), 1.0, true), now, lensZoom)
                if (f >= 1 && now - start > seconds + 3) break
                delay(66)
            }
        }
    }

    private val probes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("action")) {
                "dolly" -> probeDolly(intent.getFloatExtra("from", 2f).toDouble(), intent.getFloatExtra("to", 1f).toDouble(), intent.getFloatExtra("seconds", 3f).toDouble())
                "start" -> triggerDollyZoom()
                "crash" -> triggerCrashZoom()
                "stop" -> { probe?.cancel(); probe = null }
            }
        }
    }

    init {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            ContextCompat.registerReceiver(context, probes, IntentFilter(context.packageName + ".MOVES_PROBE"), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    // MARK: Frame assists. The level horizon, follow face and focus peaking, drawn by [frames] while one is on.

    /** The level horizon is the rear cameras', as in the original, and needs to know where down is. */
    private fun levels(choice: CameraChoice?): Boolean = settings.levelHorizon && choice != null && !choice.front && frames.levelAvailable

    /** What the frames are drawn for when [choice] is bound: the recording too for the level and the follow, the preview alone for peaking. */
    private fun assistTargets(choice: CameraChoice): Int = when {
        levels(choice) || settings.followFace -> CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
        // The camera moves draw over the preview alone, and look at the pictures for the subject.
        settings.focusPeaking || crashAvailable || dollyAvailable || drawsLooks -> CameraEffect.PREVIEW
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
            tracking = camera != null && cases.effects != 0 && tracksSubject,
            mirrored = current?.front == true && settings.mirrorRecording,
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
        stopDolly(immediate = true, reason = DollyHint.MoveStopped)
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

    fun zoomTo(target: Double, seconds: Double = zoomDuration, forDolly: Boolean = false) {
        // A zoom chosen by hand ends a Dolly Zoom (CaptureController.swift:2275, 2350-2351).
        if (!forDolly) stopDolly(immediate = true, reason = DollyHint.MoveStopped)
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
    val canPinch: Boolean get() = ready && phase != Phase.Saving && zoomRange.endInclusive > zoomRange.start && positioning == null

    /**
     * A pinch beginning with the fingers [span] apart, measured from here on as the original measures it.
     * A move that is running stops where it is.
     */
    fun beginPinch(span: Double): PinchZoom? {
        if (!canPinch) return null
        stopDolly(immediate = true, reason = DollyHint.MoveStopped)
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
        // A Crash Zoom under way goes on in the segment, on its clock; a Dolly Zoom goes on too (CaptureController.swift:1463).
        crash = crash.carried(effectTime())
        phase = Phase.Recording
        cancelFrameTools()
        movesOrigin = SystemClock.elapsedRealtimeNanos()
        publishMoves()
        publishLooks()
        saveRecipe(next)
        if (dollyFollower != null) note(next, CaptureEvent(time = 0.0, kind = "dollyStart", detail = "Dolly Zoom continuing from the preview"))
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
        stopDolly(immediate = false, reason = if (next == Phase.Paused) DollyHint.MovePaused else DollyHint.RecordingStopped)
        // The moves of the segment are saved with it; one under way goes on in the preview.
        saveRecipe(current)
        crash = crash.carried(effectTime())
        phase = Phase.Saving
        movesOrigin = SystemClock.elapsedRealtimeNanos()
        publishMoves()
        publishLooks()
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
            is VideoRecordEvent.Start -> if (segment === current) {
                current.reportedAt = SystemClock.elapsedRealtimeNanos()
                // The moves of the segment are timed on its own clock, from here.
                anchorMoves()
            }
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
        if (!current.file.exists()) withContext(eventFiles) { CaptureEventsFile.delete(current.file); CaptureEffectsFile.delete(current.file) }
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
        // The live effects and Crash Zooms of the take arrive as effects of its clip: the movie itself is clean.
        val effects = current.moves.takeIf { it.isNotEmpty() }?.let { VideoEffectSettings.capturedRecipe(it, seconds).ifEmpty { null } }
        current.indexed = store.addRecording(current.projectID, SourceMedia(id = current.id, filename = current.file.name, duration = seconds, events = events), gain, effects)
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
