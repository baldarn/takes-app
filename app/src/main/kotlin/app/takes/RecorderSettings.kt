package app.takes

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.takes.core.FocusControl
import app.takes.core.FrontLight
import app.takes.core.HorizonFraming
import app.takes.core.RecordingGain
import app.takes.core.ZoomTransition
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The settings of the recorder. Every one is global, as in the original, which keeps them in its
 * `UserDefaults`: none is stored in a project. They live in the `recorder` preferences, beside the
 * camera used last and the zoom of each camera, and are read by the interface as they change.
 */
class RecorderSettings(private val prefs: SharedPreferences) {
    /** Seconds a zoom preset or a pinch takes. Instant until the person chooses a time (the original's `zoomTransitionSeconds`). */
    var zoomSeconds: Double by stored(ZoomTransition.boundedDuration(prefs.getFloat("zoomDuration", 0f).toDouble())) {
        putFloat("zoomDuration", it.toFloat())
    }

    /** Show the whole recorded frame, or fill the screen with it (the original's `fitRecordedFrame`). */
    var fitFrame: Boolean by flag("fitRecordedFrame", true)

    /** The camera used last on each side of the phone, for the flip button (the original's `lastFrontCamera`, `lastRearCamera`). */
    var lastFrontCamera: String? by text("lastFrontCamera")
    var lastRearCamera: String? by text("lastRearCamera")

    /** Each camera remembers whether its focus was locked and where its lens was (the original's `locked` and `lens` under `camera.<id>`). */
    fun focusLocked(camera: String): Boolean = prefs.getBoolean("focusLocked.$camera", false)

    fun focusLens(camera: String): Double = FocusControl.bounded(prefs.getFloat("focusLens.$camera", FocusControl.DEFAULT.toFloat()).toDouble())

    fun rememberFocus(camera: String, locked: Boolean, lens: Double) {
        prefs.edit().putBoolean("focusLocked.$camera", locked).putFloat("focusLens.$camera", lens.toFloat()).apply()
    }

    /** Record in 4K where the camera has it (the original's `captureQuality`); 1080p until chosen. */
    var ultraHD: Boolean by flag("captureUltraHD", false)

    /** Record HDR, as HLG 10 bit, where the camera has it (the original's `captureHDR`). Off until chosen: the export is SDR. */
    var hdr: Boolean by flag("captureHDR", false)

    /** Steady the picture where the camera can (the original's `dualStabilization`). */
    var stabilization: Boolean by flag("stabilization", false)

    /** The front preview behaves like a mirror (the original's `mirrorFrontPreview`). */
    var mirrorPreview: Boolean by flag("mirrorFrontPreview", true)

    /** The front camera's takes are saved mirrored (the original's `mirrorFrontRecording`). */
    var mirrorRecording: Boolean by flag("mirrorFrontRecording", false)

    /** Keep small tilts level on the rear cameras (the original's `levelHorizon`), up to [horizonDegrees]. */
    var levelHorizon: Boolean by flag("levelHorizon", false)
    var horizonDegrees: Double by stored(HorizonFraming.degrees(prefs.getFloat("levelHorizonDegrees", HorizonFraming.DEFAULT_DEGREES.toFloat()).toDouble())) {
        putFloat("levelHorizonDegrees", it.toFloat())
    }

    /** Follow a face with the picture of a single camera (the original's `dualFaceFollow`). */
    var followFace: Boolean by flag("dualFaceFollow", false)

    /** Mark sharp edges in the preview (the original's `focusPeaking`). */
    var focusPeaking: Boolean by flag("focusPeaking", false)

    /** The recording gain of each microphone, in decibels (the original's `recordingGain` for each microphone). */
    fun recordingGain(microphone: String?): Double =
        if (microphone == null) 0.0 else RecordingGain.normalized(prefs.getFloat("recordingGain.$microphone", 0f).toDouble())

    fun setRecordingGain(microphone: String, db: Double) {
        prefs.edit().putFloat("recordingGain.$microphone", RecordingGain.normalized(db).toFloat()).apply()
    }

    /** The front light: its width, its colour and its opacity are kept; being on is not, as in the original. */
    var frontLightWidth: FrontLight.Width by stored(FrontLight.width(prefs.getString("frontLightWidth", null))) { putString("frontLightWidth", it.name) }
    var frontLightTone: FrontLight.Tone by stored(FrontLight.tone(prefs.getString("frontLightTone", null))) { putString("frontLightTone", it.name) }
    /** The hue of the custom colour, in degrees. */
    var frontLightHue: Double by stored(FrontLight.hue(prefs.getFloat("frontLightHue", FrontLight.DEFAULT_HUE.toFloat()).toDouble())) { putFloat("frontLightHue", it.toFloat()) }
    var frontLightOpacity: Double by stored(FrontLight.opacity(prefs.getFloat("frontLightOpacity", FrontLight.DEFAULT_OPACITY.toFloat()).toDouble())) {
        putFloat("frontLightOpacity", it.toFloat())
    }

    /**
     * The microphones in order of preference, best first, with the ones that are not connected
     * (the original's `microphonePriority`), and the name each was last seen with (`microphoneNames`).
     */
    var microphonePriority: List<String> by stored(strings(prefs.getString("microphonePriority", null))) { putString("microphonePriority", JSONArray(it).toString()) }
    var microphoneNames: Map<String, String> by stored(names(prefs.getString("microphoneNames", null))) { putString("microphoneNames", JSONObject(it).toString()) }

    private fun strings(saved: String?): List<String> =
        runCatching { JSONArray(saved ?: "[]").let { list -> List(list.length(), list::getString) } }.getOrDefault(emptyList())

    private fun names(saved: String?): Map<String, String> =
        runCatching { JSONObject(saved ?: "{}").let { map -> map.keys().asSequence().associateWith(map::getString) } }.getOrDefault(emptyMap())

    private fun text(key: String) = stored(prefs.getString(key, null)) { putString(key, it) }

    private fun flag(key: String, default: Boolean) = stored(prefs.getBoolean(key, default)) { putBoolean(key, it) }

    /** A value the interface observes, written to the preferences at each change. */
    private fun <T> stored(initial: T, write: SharedPreferences.Editor.(T) -> Unit): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            private val state: MutableState<T> = mutableStateOf(initial)
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                if (state.value == value) return
                state.value = value
                prefs.edit().apply { write(value) }.apply()
            }
        }
}
