package app.takes

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
