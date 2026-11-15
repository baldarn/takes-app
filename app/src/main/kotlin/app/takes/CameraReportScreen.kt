package app.takes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.Vibrator
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.PackageInfoCompat
import app.takes.core.CameraReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * The way into the camera report from the recorder: a small chip, in the
 * development build only. The store build compiles the report and never
 * shows a way to it.
 */
@Composable
internal fun CameraReportEntry(capture: CaptureController) {
    val context = LocalContext.current
    if (!isDevelopmentBuild(context)) return
    var open by rememberSaveable { mutableStateOf(false) }
    Text(
        stringResource(R.string.recorder_report_open), color = Color.White, fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp).background(Color(0x99000000), RoundedCornerShape(50))
            .clickable(role = Role.Button) { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    if (open) CameraReportScreen(capture.cameras.associate { it.id to it.label }) { open = false }
}

/**
 * This module generates no BuildConfig, so the development build is told
 * apart by the flag every debuggable package carries. The store build and
 * the smoke build are not debuggable.
 */
private fun isDevelopmentBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * What each camera of this phone offers, as text to copy and send. It only
 * asks the phone questions: nothing is bound, started or changed, so the
 * recorder underneath keeps its camera.
 */
@Composable
private fun CameraReportScreen(labels: Map<String, String>, close: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { text = CameraReportReader(context.applicationContext, labels).read().text() }
    val clipLabel = stringResource(R.string.recorder_report_clip_label)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recorder_report_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = text != null,
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(clipLabel, text.orEmpty()))
                        copied = true
                    },
                ) { Text(stringResource(if (copied) R.string.recorder_report_copied else R.string.recorder_report_copy)) }
                TextButton(onClick = close) { Text(stringResource(R.string.common_done)) }
            }
            Text(stringResource(R.string.recorder_report_hint), color = Color(0xFFD0D3D8), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text ?: stringResource(R.string.recorder_report_reading), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

/**
 * Reads the phone into a [CameraReport]. Every question is asked on its own:
 * one that fails becomes a line under "Problem" and the rest is still
 * reported. [labels] are the names the recorder shows, by camera id.
 */
@OptIn(ExperimentalCamera2Interop::class)
private class CameraReportReader(private val context: Context, private val labels: Map<String, String>) {
    private val manager: CameraManager? = context.getSystemService(CameraManager::class.java)

    suspend fun read(): CameraReport {
        val problems = mutableListOf<String>()
        val provider = attempt(problems, "camera library") { ProcessCameraProvider.awaitInstance(context) }
        val infos = provider?.let { attempt(problems, "camera list") { it.availableCameraInfos } }.orEmpty()
        val cameras = infos.map { info ->
            // Let the screen draw between two cameras.
            yield()
            camera(info)
        }
        val concurrent = provider?.let {
            attempt(problems, "cameras that run together") { it.availableConcurrentCameraInfos.map { group -> group.map(::id) } }
        }.orEmpty()
        return CameraReport(
            app = attempt(problems, "app") { app() } ?: context.packageName,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            system = "Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}",
            library = "CameraX " + (attempt(problems, "camera library version") { libraryVersion() } ?: CameraReport.UNKNOWN),
            cameras = cameras,
            concurrent = concurrent,
            facts = phone(problems),
            problems = problems,
        )
    }

    private fun app(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = context.applicationInfo.loadLabel(context.packageManager)
        return "$name ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)}), ${context.packageName}"
    }

    /** The camera library ships its version as a small text file among its classes. */
    private fun libraryVersion(): String? =
        javaClass.classLoader?.getResourceAsStream("META-INF/androidx.camera_camera-core.version")
            ?.bufferedReader()?.use { it.readText().trim() }?.takeIf { it.isNotEmpty() }

    // MARK: The phone as a whole

    private fun phone(problems: MutableList<String>): List<CameraReport.Fact> = buildList {
        attempt(problems, "camera ids of the system") { manager?.cameraIdList?.joinToString(", ") }?.let {
            add(CameraReport.Fact("Camera ids of the system", it.ifEmpty { "none" }))
        }
        attempt(problems, "gravity sensor") {
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GRAVITY) != null
        }?.let { add(CameraReport.Fact("Gravity sensor", CameraReport.answer(it))) }
        attempt(problems, "vibration") { context.getSystemService(Vibrator::class.java)?.hasVibrator() }?.let {
            add(CameraReport.Fact("Vibration motor", CameraReport.answer(it)))
        }
        add(CameraReport.Fact("Heat reported by the system", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attempt(problems, "heat") { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }?.let { "yes, now at level $it" } ?: CameraReport.UNKNOWN
        } else "no, needs Android 10"))
        val audio = context.getSystemService(AudioManager::class.java)
        // Kinds only: the names of connected devices can carry a person's name.
        attempt(problems, "microphones") { audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.map { microphone(it.type) } }?.let {
            add(CameraReport.Fact("Sound inputs", counted(it)))
        }
        add(CameraReport.Fact("Sound devices the recorder can choose", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            attempt(problems, "sound devices") { audio?.availableCommunicationDevices?.map { microphone(it.type) } }?.let(::counted) ?: CameraReport.UNKNOWN
        } else "none, needs Android 12"))
    }

    private fun counted(names: List<String>): String =
        names.groupingBy { it }.eachCount().entries.joinToString(", ") { (name, count) -> if (count == 1) name else "$name × $count" }.ifEmpty { "none" }

    private fun microphone(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        else -> "type $type"
    }

    // MARK: One camera

    private fun id(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    private fun camera(info: CameraInfo): CameraReport.Camera {
        val problems = mutableListOf<String>()
        val id = attempt(problems, "camera id") { id(info) } ?: "?"
        val traits = attempt(problems, "characteristics") { manager?.getCameraCharacteristics(id) }
        fun <T> trait(key: CameraCharacteristics.Key<T>): T? =
            attempt(problems, key.name) { traits?.get(key) ?: Camera2CameraInfo.from(info).getCameraCharacteristic(key) }

        val facing = when (trait(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> CameraReport.Facing.Back
            CameraCharacteristics.LENS_FACING_FRONT -> CameraReport.Facing.Front
            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraReport.Facing.External
            else -> when (attempt(problems, "facing") { info.lensFacing }) {
                CameraSelector.LENS_FACING_BACK -> CameraReport.Facing.Back
                CameraSelector.LENS_FACING_FRONT -> CameraReport.Facing.Front
                else -> CameraReport.Facing.Unknown
            }
        }
        val video = attempt(problems, "recording capabilities") { Recorder.getVideoCapabilities(info) }
        val ranges = video?.let { attempt(problems, "recording qualities") { ranges(it, info) } }.orEmpty()
        val zoom = attempt(problems, "zoom") { info.zoomState.value }
        val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attempt(problems, "lenses") { traits?.physicalCameraIds?.sorted() }.orEmpty()
        } else emptyList()
        val size = trait(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        return CameraReport.Camera(
            id = id,
            label = labels[id] ?: facing.label,
            facing = facing,
            hardwareLevel = trait(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.let { named(HARDWARE_LEVELS, it) },
            physicalIds = physical,
            focalLengths = trait(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { it.toDouble() }.orEmpty(),
            apertures = trait(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.map { it.toDouble() }.orEmpty(),
            sensorWidth = size?.width?.toDouble(),
            sensorHeight = size?.height?.toDouble(),
            intrinsicZoom = attempt(problems, "lens against the main one") { info.intrinsicZoomRatio.toDouble() },
            zoomMinimum = zoom?.minZoomRatio?.toDouble(),
            zoomMaximum = zoom?.maxZoomRatio?.toDouble(),
            dynamicRanges = ranges,
            frameRates = attempt(problems, "frame rates") {
                info.supportedFrameRateRanges.map { CameraReport.FrameRates(it.lower, it.upper) }.sortedWith(compareBy({ it.maximum }, { it.minimum }))
            }.orEmpty(),
            videoStabilization = video?.let { attempt(problems, "stabilisation of the recording") { it.isStabilizationSupported } },
            previewStabilization = attempt(problems, "stabilisation of the preview") { Preview.getPreviewCapabilities(info).isStabilizationSupported },
            stabilizationModes = trait(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.map { named(STABILIZATION_MODES, it) }.orEmpty(),
            opticalStabilization = trait(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON),
            combinations = combinations(info, ranges.firstOrNull { it.name == CameraReport.SDR }?.qualities.orEmpty()),
            minimumFocusDistance = trait(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.toDouble(),
            hyperfocalDistance = trait(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)?.toDouble(),
            focusCalibration = trait(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)?.let { named(FOCUS_CALIBRATIONS, it) },
            autofocusModes = trait(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.map { named(AUTOFOCUS_MODES, it) }.orEmpty(),
            focusRegions = trait(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),
            focusMetering = attempt(problems, "focus point") {
                val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                info.isFocusMeteringSupported(FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).build())
            },
            faceDetectModes = trait(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.map { named(FACE_DETECT_MODES, it) }.orEmpty(),
            maximumFaces = trait(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT),
            timestampSource = trait(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)?.let { named(TIMESTAMP_SOURCES, it) },
            croppingType = trait(CameraCharacteristics.SCALER_CROPPING_TYPE)?.let { named(CROPPING_TYPES, it) },
            capabilities = trait(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map { named(CAPABILITIES, it) }.orEmpty(),
            facts = facts(info, traits, physical, problems),
            problems = problems,
        )
    }

    /** What the recorder can write on this camera, by colour mode; frame sizes are known for SDR only. */
    private fun ranges(video: VideoCapabilities, info: CameraInfo): List<CameraReport.DynamicRange> =
        video.supportedDynamicRanges.sortedBy { if (it == DynamicRange.SDR) 0 else 1 }.map { range ->
            CameraReport.DynamicRange(name(range), video.getSupportedQualities(range).map { quality ->
                val size = if (range == DynamicRange.SDR) runCatching { QualitySelector.getResolution(info, quality) }.getOrNull() else null
                CameraReport.Quality(name(quality), size?.width, size?.height)
            })
        }

    private fun name(range: DynamicRange): String = when (range) {
        DynamicRange.SDR -> CameraReport.SDR
        DynamicRange.HLG_10_BIT -> CameraReport.HLG10
        DynamicRange.HDR10_10_BIT -> "HDR10 10 bit"
        DynamicRange.HDR10_PLUS_10_BIT -> "HDR10+ 10 bit"
        DynamicRange.DOLBY_VISION_10_BIT -> "Dolby Vision 10 bit"
        DynamicRange.DOLBY_VISION_8_BIT -> "Dolby Vision 8 bit"
        else -> range.toString()
    }

    private fun name(quality: Quality): String = when (quality) {
        Quality.UHD -> CameraReport.UHD
        Quality.FHD -> CameraReport.FHD
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> quality.toString()
    }

    /**
     * Whether a preview and a recording can run together at 1080p and at 4K
     * with HDR, stabilisation and 60 frames per second, in every mix of the
     * three. The camera library answers by working out the session without
     * opening it.
     *
     * The quality is asked for as one more feature of the group, on a
     * recorder left at its defaults: the library refuses a session that has
     * a feature group and a recorder with a quality selector of its own.
     */
    private fun combinations(info: CameraInfo, qualities: List<CameraReport.Quality>): List<CameraReport.Combination> {
        val features = listOf(
            CameraReport.HLG10 to GroupableFeature.HDR_HLG10,
            "preview stabilisation" to GroupableFeature.PREVIEW_STABILIZATION,
            "60 fps" to GroupableFeature.FPS_60,
        )
        return listOf(CameraReport.FHD to GroupableFeatures.FHD_RECORDING, CameraReport.UHD to GroupableFeatures.UHD_RECORDING)
            .filter { (name, _) -> qualities.any { it.name == name } }
            .flatMap { (name, quality) ->
                (0 until (1 shl features.size)).map { mix ->
                    val chosen = features.filterIndexed { index, _ -> (mix and (1 shl index)) != 0 }
                    try {
                        val session = SessionConfig.Builder(Preview.Builder().build(), VideoCapture.withOutput(Recorder.Builder().build()))
                            .setRequiredFeatureGroup(quality, *chosen.map { it.second }.toTypedArray())
                        CameraReport.Combination(name, chosen.map { it.first }, info.isSessionConfigSupported(session.build()))
                    } catch (error: Exception) {
                        CameraReport.Combination(name, chosen.map { it.first }, null, describe(error))
                    }
                }
            }
    }

    private fun facts(info: CameraInfo, traits: CameraCharacteristics?, physical: List<String>, problems: MutableList<String>): List<CameraReport.Fact> = buildList {
        fun <T> trait(key: CameraCharacteristics.Key<T>): T? = attempt(problems, key.name) { traits?.get(key) }
        fun fact(name: String, value: String?) { if (value != null) add(CameraReport.Fact(name, value)) }

        fact("Sensor turned by", trait(CameraCharacteristics.SENSOR_ORIENTATION)?.let { "$it°" })
        fact("Sensor pixels", trait(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { "${it.width}×${it.height}" })
        fact("Sensor area in use", trait(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { "${it.width()}×${it.height()}" })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fact("Zoom of the camera itself", trait(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                CameraReport.number(it.lower.toDouble()) + "× to " + CameraReport.number(it.upper.toDouble()) + "×"
            })
        }
        fact("Digital zoom at most", trait(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { CameraReport.number(it.toDouble()) + "×" })
        fact("Changes lens by itself", attempt(problems, "logical camera") { CameraReport.answer(info.isLogicalMultiCameraSupported) })
        // The lenses behind a camera that changes lens while zooming: is there an ultrawide, how near does each focus.
        val ratios = attempt(problems, "lenses of the camera library") {
            info.physicalCameraInfos.associate { id(it) to it.intrinsicZoomRatio.toDouble() }
        }.orEmpty()
        physical.forEach { lens ->
            val own = attempt(problems, "lens $lens") { manager?.getCameraCharacteristics(lens) } ?: return@forEach
            fact("Lens $lens", listOfNotNull(
                own.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(" and ") { CameraReport.number(it.toDouble()) + " mm" },
                own.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(" and ") { "f/" + CameraReport.number(it.toDouble()) },
                ratios[lens]?.let { CameraReport.number(it) + "× against the main one" },
                own.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
                    if (it > 0) "nearest focus " + CameraReport.number(it.toDouble()) + " dioptres" else "fixed focus"
                },
                own.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.let { if (it.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)) "optical stabilisation" else null },
            ).joinToString(", "))
        }
        fact("Frame rates of the camera itself", trait(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.joinToString(", ") { if (it.lower == it.upper) "${it.lower}" else "${it.lower} to ${it.upper}" })
        val sizes = trait(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fact("Largest recording frames", attempt(problems, "recording frames") { sizes?.getOutputSizes(MediaRecorder::class.java)?.let(::largest) })
        fact("High-speed frames", attempt(problems, "high-speed frames") {
            sizes?.let { map ->
                map.highSpeedVideoSizes?.takeIf { it.isNotEmpty() }?.let { frames ->
                    largest(frames) + " at up to " + (map.highSpeedVideoFpsRanges?.maxOfOrNull { it.upper } ?: 0) + " frames per second"
                }
            }
        })
        attempt(problems, "recording by encoder") { Recorder.getVideoCapabilities(info, Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES) }?.let { video ->
            attempt(problems, "recording qualities by encoder") { ranges(video, info) }?.forEach { range ->
                fact("Recording by what the encoders accept, ${range.name}", range.qualities.joinToString(", ") { it.name }.ifEmpty { "nothing" })
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fact("Colour modes of the camera itself", trait(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles?.sorted()?.joinToString(", ", transform = ::profile))
        }
        val steps = trait(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        fact("Exposure correction", trait(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
            if (steps == null || (range.lower == 0 && range.upper == 0)) "none"
            else CameraReport.number(range.lower * steps.toDouble()) + " to " + CameraReport.number(range.upper * steps.toDouble()) + " stops in steps of " + steps
        })
        fact("Exposure regions", trait(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)?.toString())
        fact("Light", attempt(problems, "light") { CameraReport.answer(info.hasFlashUnit()) })
    }

    /** The largest few frames, largest first. */
    private fun largest(sizes: Array<Size>): String =
        sizes.sortedByDescending { it.width.toLong() * it.height }.take(8).joinToString(", ") { "${it.width}×${it.height}" }.ifEmpty { "none" }

    private fun profile(value: Long): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "$value" else when (value) {
        DynamicRangeProfiles.STANDARD -> "SDR"
        DynamicRangeProfiles.HLG10 -> "HLG10"
        DynamicRangeProfiles.HDR10 -> "HDR10"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10+"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF, DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO,
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM, DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO -> "Dolby Vision 10 bit ($value)"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF, DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO,
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM, DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO -> "Dolby Vision 8 bit ($value)"
        else -> "profile $value"
    }

    // MARK: Asking safely

    private inline fun <T> attempt(problems: MutableList<String>, what: String, block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        problems += "$what: ${describe(error)}"
        null
    }

    private fun describe(error: Exception): String = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")

    private fun named(names: List<String>, value: Int): String = names.getOrNull(value) ?: "$value"

    /**
     * The system's names for its numbers, in the order of the numbers. They
     * are the constants of android.hardware.camera2.CameraMetadata, which
     * never change; a number newer than this list is written as it is.
     */
    private companion object {
        val HARDWARE_LEVELS = listOf("LIMITED", "FULL", "LEGACY", "LEVEL_3", "EXTERNAL")
        val STABILIZATION_MODES = listOf("OFF", "ON", "PREVIEW_STABILIZATION")
        val FOCUS_CALIBRATIONS = listOf("UNCALIBRATED", "APPROXIMATE", "CALIBRATED")
        val AUTOFOCUS_MODES = listOf(CameraReport.FOCUS_OFF, "AUTO", "MACRO", "CONTINUOUS_VIDEO", "CONTINUOUS_PICTURE", "EDOF")
        val FACE_DETECT_MODES = listOf(CameraReport.FACE_DETECT_OFF, "SIMPLE", "FULL")
        val TIMESTAMP_SOURCES = listOf("UNKNOWN", CameraReport.TIMESTAMP_REALTIME)
        val CROPPING_TYPES = listOf("CENTER_ONLY", CameraReport.CROP_FREEFORM)
        val CAPABILITIES = listOf(
            "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING", "READ_SENSOR_SETTINGS",
            "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT", "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA",
            "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING",
            "DYNAMIC_RANGE_TEN_BIT", "STREAM_USE_CASE", "COLOR_SPACE_PROFILES",
        )
    }
}
