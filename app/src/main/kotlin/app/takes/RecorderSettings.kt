package app.takes

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.takes.core.FocusControl
import app.takes.core.ZoomTransition
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
