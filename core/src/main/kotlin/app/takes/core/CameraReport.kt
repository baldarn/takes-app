package app.takes.core

import java.util.Locale
import kotlin.math.sqrt

/**
 * What the cameras of one phone offer to the recorder, as plain values, and
 * the text that is copied out of the report screen.
 *
 * The screen fills this in from the camera library and the system; nothing
 * here knows either, so the text can be checked on any machine. A value that
 * could not be read is null and is written as "unknown", never guessed.
 *
 * The text is English whatever the language of the phone: it is read by
 * whoever asked for it, and its lines are found by their names.
 */
data class CameraReport(
    /** The app, its version and its package. */
    val app: String,
    /** Maker and model. */
    val device: String,
    /** The system's version and API level. */
    val system: String,
    /** The camera library's version. */
    val library: String,
    val cameras: List<Camera>,
    /** The sets of cameras that can run at the same time, by id. */
    val concurrent: List<List<String>> = emptyList(),
    /** Anything else about the phone as a whole: sensors, microphones. */
    val facts: List<Fact> = emptyList(),
    /** What went wrong while reading the phone as a whole. */
    val problems: List<String> = emptyList(),
) {
    /** One named line of the report. */
    data class Fact(val name: String, val value: String)

    enum class Facing(val label: String) { Back("back"), Front("front"), External("external"), Unknown("unknown") }

    /** A recording quality and the frame it gives, when that is known. */
    data class Quality(val name: String, val width: Int? = null, val height: Int? = null)

    /** A colour mode the recorder can write, with the qualities it has in that mode. */
    data class DynamicRange(val name: String, val qualities: List<Quality>)

    /** A range of frames per second the camera can be held to. */
    data class FrameRates(val minimum: Int, val maximum: Int)

    /**
     * Whether a preview and a recording at [quality] can run together with
     * all of [features] at once; null when the phone could not be asked, and
     * [problem] then says why.
     */
    data class Combination(val quality: String, val features: List<String>, val supported: Boolean?, val problem: String? = null)

    /** One line of what a later step of the recorder work may offer on a camera. */
    data class Gate(val name: String, val open: Boolean?)

    data class Camera(
        val id: String,
        /** The name the recorder shows for it. */
        val label: String,
        val facing: Facing,
        /** How much of the camera API the camera implements, as the system names it. */
        val hardwareLevel: String? = null,
        /** The lenses behind a camera that changes lens by itself while zooming; empty for a single lens. */
        val physicalIds: List<String> = emptyList(),
        /** Millimetres. */
        val focalLengths: List<Double> = emptyList(),
        val apertures: List<Double> = emptyList(),
        /** Millimetres. */
        val sensorWidth: Double? = null,
        val sensorHeight: Double? = null,
        /** The lens against the phone's main one: under 1 for an ultrawide, over 1 for a tele. */
        val intrinsicZoom: Double? = null,
        val zoomMinimum: Double? = null,
        val zoomMaximum: Double? = null,
        /** What the recorder can write, by colour mode. */
        val dynamicRanges: List<DynamicRange> = emptyList(),
        val frameRates: List<FrameRates> = emptyList(),
        /** Stabilisation of the recording alone. */
        val videoStabilization: Boolean? = null,
        /** Stabilisation of preview and recording alike. */
        val previewStabilization: Boolean? = null,
        /** The stabilisation modes the camera itself lists. */
        val stabilizationModes: List<String> = emptyList(),
        val opticalStabilization: Boolean? = null,
        val combinations: List<Combination> = emptyList(),
        /** The nearest focus in dioptres; zero for a lens with fixed focus. */
        val minimumFocusDistance: Double? = null,
        /** Dioptres. */
        val hyperfocalDistance: Double? = null,
        /** Whether the focus scale is in real dioptres, as the system names it. */
        val focusCalibration: String? = null,
        val autofocusModes: List<String> = emptyList(),
        /** How many regions autofocus can be pointed at. */
        val focusRegions: Int? = null,
        /** Whether the camera library accepts a focus point on this camera. */
        val focusMetering: Boolean? = null,
        val faceDetectModes: List<String> = emptyList(),
        val maximumFaces: Int? = null,
        /** The clock frames are stamped with, as the system names it. */
        val timestampSource: String? = null,
        /** Whether the sensor can be cropped anywhere or only around its centre, as the system names it. */
        val croppingType: String? = null,
        val capabilities: List<String> = emptyList(),
        /** Anything else worth a line. */
        val facts: List<Fact> = emptyList(),
        /** What went wrong while reading this camera. */
        val problems: List<String> = emptyList(),
    ) {
        /** The qualities the recorder has in the colour mode called [range]; empty when it lacks the mode. */
        fun qualities(range: String): List<Quality> = dynamicRanges.firstOrNull { it.name == range }?.qualities.orEmpty()

        /** Each focal length as a full-frame camera would name the same view, or null without the sensor's size. */
        fun equivalentFocalLengths(): List<Double>? {
            val width = sensorWidth ?: return null
            val height = sensorHeight ?: return null
            val diagonal = sqrt(width * width + height * height)
            if (diagonal <= 0) return null
            return focalLengths.map { it * FULL_FRAME_DIAGONAL / diagonal }
        }

        /**
         * What the later steps of the recorder work can offer on this
         * camera, each named with the items of the recorder review it
         * decides. Null where the value it depends on could not be read.
         */
        fun gates(concurrent: List<List<String>> = emptyList()): List<Gate> = listOf(
            Gate("4K (P1)", dynamicRanges.takeIf { it.isNotEmpty() }?.let { qualities(SDR).any { quality -> quality.name == UHD } }),
            Gate("HDR as HLG 10 bit (P3)", dynamicRanges.takeIf { it.isNotEmpty() }?.let { qualities(HLG10).isNotEmpty() }),
            Gate("Fixed 30 frames per second (P2)", frameRates.takeIf { it.isNotEmpty() }?.contains(FrameRates(30, 30))),
            Gate("Stabilisation of preview and recording (P9)", previewStabilization),
            Gate("Stabilisation of the recording only (P9)", videoStabilization),
            Gate("Tap to focus (F1)", focusMetering),
            Gate("Focus lock and manual focus (F2, F3)",
                minimumFocusDistance?.let { nearest -> autofocusModes.takeIf { it.isNotEmpty() }?.let { nearest > 0 && FOCUS_OFF in it } }),
            Gate("Face detection by the camera (A2)", faceDetectModes.takeIf { it.isNotEmpty() }?.any { it != FACE_DETECT_OFF }),
            Gate("Pan by sensor crop (Z13, A2)", croppingType?.let { it == CROP_FREEFORM }),
            Gate("Frame times on the sensor clock (A1)", timestampSource?.let { it == TIMESTAMP_REALTIME }),
            Gate("Ultrawide listed as a camera of its own (Z7)", intrinsicZoom?.let { facing == Facing.Back && it < ULTRAWIDE_BELOW }),
            Gate("Runs beside another camera (D1)", concurrent.any { id in it && it.size > 1 }),
        )
    }

    /** The whole report as plain text, one fact per line. */
    fun text(): String = buildString {
        appendLine(TITLE)
        appendLine("App: $app")
        appendLine("Device: $device")
        appendLine("System: $system")
        appendLine("Camera library: $library")
        appendLine("Cameras: " + if (cameras.isEmpty()) "none" else cameras.joinToString(", ") { "${it.id} (${it.label})" })
        appendLine("Cameras that run together: " + if (concurrent.isEmpty()) "none" else concurrent.joinToString("; ") { it.joinToString(" + ") })
        facts.forEach { appendLine("${it.name}: ${it.value}") }
        problems.forEach { appendLine("Problem: $it") }
        cameras.forEach { camera ->
            appendLine()
            appendLine("Camera ${camera.id}: ${camera.label}")
            line("Facing", camera.facing.label)
            line("Hardware level", camera.hardwareLevel ?: UNKNOWN)
            line("Lenses behind it", list(camera.physicalIds, "one lens"))
            line("Focal lengths", focalLengths(camera))
            line("Apertures", list(camera.apertures.map { "f/" + number(it) }, UNKNOWN))
            line("Sensor", camera.sensorWidth?.let { width -> camera.sensorHeight?.let { number(width) + " × " + number(it) + " mm" } } ?: UNKNOWN)
            line("Lens against the main one", camera.intrinsicZoom?.let { number(it) + "×" } ?: UNKNOWN)
            line("Zoom", camera.zoomMinimum?.let { minimum -> camera.zoomMaximum?.let { number(minimum) + "× to " + number(it) + "×" } } ?: UNKNOWN)
            if (camera.dynamicRanges.isEmpty()) line("Recording", UNKNOWN)
            camera.dynamicRanges.forEach { range -> line("Recording, ${range.name}", list(range.qualities.map(::quality), "nothing")) }
            line("Frame rates", list(camera.frameRates.map { if (it.minimum == it.maximum) "${it.minimum}" else "${it.minimum} to ${it.maximum}" }, UNKNOWN))
            line("Stabilisation of preview and recording", answer(camera.previewStabilization))
            line("Stabilisation of the recording only", answer(camera.videoStabilization))
            line("Stabilisation modes of the camera", list(camera.stabilizationModes, UNKNOWN))
            line("Optical stabilisation", answer(camera.opticalStabilization))
            if (camera.combinations.isNotEmpty()) {
                appendLine("  Preview and recording together with:")
                camera.combinations.forEach { appendLine("    " + combination(it)) }
            }
            line("Nearest focus", focusDistance(camera.minimumFocusDistance))
            line("Hyperfocal distance", camera.hyperfocalDistance?.let { number(it) + " dioptres" } ?: UNKNOWN)
            line("Focus scale", camera.focusCalibration ?: UNKNOWN)
            line("Autofocus modes", list(camera.autofocusModes, UNKNOWN))
            line("Autofocus regions", camera.focusRegions?.toString() ?: UNKNOWN)
            line("Focus point accepted", answer(camera.focusMetering))
            line("Face detection modes", list(camera.faceDetectModes, UNKNOWN))
            line("Faces at most", camera.maximumFaces?.toString() ?: UNKNOWN)
            line("Frame clock", camera.timestampSource ?: UNKNOWN)
            line("Sensor crop", camera.croppingType ?: UNKNOWN)
            line("Capabilities", list(camera.capabilities, UNKNOWN))
            camera.facts.forEach { line(it.name, it.value) }
            appendLine("  For the recorder:")
            camera.gates(concurrent).forEach { appendLine("    ${it.name}: ${answer(it.open)}") }
            camera.problems.forEach { line("Problem", it) }
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.line(name: String, value: String) {
        appendLine("  $name: $value")
    }

    private fun focalLengths(camera: Camera): String {
        val equivalents = camera.equivalentFocalLengths()
        return list(camera.focalLengths.mapIndexed { index, length ->
            number(length) + " mm" + (equivalents?.let { " (as " + Math.round(it[index]) + " mm on full frame)" } ?: "")
        }, UNKNOWN)
    }

    private fun focusDistance(dioptres: Double?): String = when {
        dioptres == null -> UNKNOWN
        dioptres <= 0 -> "fixed focus"
        else -> number(dioptres) + " dioptres (" + number(100 / dioptres) + " cm)"
    }

    private fun quality(quality: Quality): String =
        quality.name + (quality.width?.let { width -> quality.height?.let { " $width×$it" } } ?: "")

    private fun combination(combination: Combination): String =
        (listOf(combination.quality) + combination.features).joinToString(" + ") + ": " +
            answer(combination.supported) + (combination.problem?.let { " ($it)" } ?: "")

    companion object {
        /** The first line of every report. */
        const val TITLE = "Takes camera report"

        const val UNKNOWN = "unknown"

        /** The names the screen gives to what the gates look for. */
        const val SDR = "SDR"
        const val HLG10 = "HLG 10 bit"
        const val UHD = "UHD"
        const val FHD = "FHD"
        const val FOCUS_OFF = "OFF"
        const val FACE_DETECT_OFF = "OFF"
        const val CROP_FREEFORM = "FREEFORM"
        const val TIMESTAMP_REALTIME = "REALTIME"

        /** A rear lens this much wider than the main one is an ultrawide, as the recorder already names it. */
        const val ULTRAWIDE_BELOW = 0.9

        /** The diagonal of a full-frame sensor, 36 × 24 mm. */
        val FULL_FRAME_DIAGONAL = sqrt(36.0 * 36.0 + 24.0 * 24.0)

        /** Yes, no, or unknown for what could not be read. */
        fun answer(value: Boolean?): String = when (value) {
            true -> "yes"
            false -> "no"
            null -> UNKNOWN
        }

        /** A number with at most two decimals and no trailing zeros, written the same on every phone. */
        fun number(value: Double): String {
            if (!value.isFinite()) return UNKNOWN
            val text = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
            return if (text == "-0") "0" else text
        }

        private fun list(values: List<String>, empty: String): String = if (values.isEmpty()) empty else values.joinToString(", ")
    }
}
