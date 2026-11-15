package app.takes.core

/**
 * Which microphone the recorder asks the system for, as the original app
 * decides it (`RecordingMicrophoneRoute`, YouRec/CaptureAudio.swift:4-109).
 *
 * The saved order of preference rules while nothing is recorded. A choice
 * made by hand, or the microphone a take started on, is held until Stop: a
 * better microphone that connects during a take does not take it over, and a
 * microphone that only drops out of the system's list for a moment, as
 * Bluetooth and USB ones do while they negotiate, is not given up.
 *
 * When the held microphone really goes away, the route waits, then asks for
 * the best one left, and reports the change only once the system confirms
 * that sound comes from it.
 *
 * Microphones are known by an id that must stay the same across
 * connections. [resolve] is called whenever the system reports a change and
 * again after the delay it asks for; it never talks to the system itself.
 * One instance lives from one Stop to the next: the original starts a fresh
 * one when a take ends (YouRec/CaptureController.swift:575).
 */
class MicrophoneRoute {
    /** What to do after looking at the system's microphones once. Every part can be absent. */
    data class Decision(
        /** The microphone to ask the system for now. */
        val request: String? = null,
        /** Seconds after which to look again, even if the system reports nothing. */
        val recheckAfter: Double? = null,
        /**
         * The microphone that went away during a take, now that sound comes
         * from another. The original saves an event [FALLBACK_EVENT] beside
         * the take and warns "X disconnected. Using Y."
         * (CaptureController.swift:2617-2622).
         */
        val disconnected: String? = null,
        /**
         * The microphone the system would not switch to after every retry.
         * The original warns "Could not switch to X. Using Y."
         * (CaptureController.swift:2612-2616).
         */
        val failed: String? = null,
    )

    private var pinned: String? = null
    private var missingSince: Double? = null
    private var removalReported = false
    private val refusedFallbacks = mutableSetOf<String>()
    private var pending: String? = null
    private var pendingActual: String? = null
    private var attempts = 0
    private var nextAttempt = 0.0

    /** The person chose [id]. It is held until Stop, whatever the saved order says (CaptureAudio.swift:30-33). */
    fun select(id: String) {
        missingSince = null
        removalReported = false
        refusedFallbacks.clear()
        pending = null
        pendingActual = null
        attempts = 0
        nextAttempt = 0.0
        pinned = id
    }

    /**
     * The system reported that a device was unplugged or switched off.
     * [previousInputs] are the microphones in use just before. When the held
     * one was among them the wait before a fallback is [REPORTED_WAIT]
     * instead of [MISSING_WAIT] (CaptureAudio.swift:35-39).
     */
    fun deviceDisconnected(previousInputs: List<String>) {
        if (pinned?.let(previousInputs::contains) == true) removalReported = true
        pending = null
        refusedFallbacks.clear()
    }

    /**
     * Looks at the microphones once (CaptureAudio.swift:41-108).
     *
     * [ranked] are the connected microphones in order of preference, best
     * first: see [available]. [actual] is the one sound really comes from,
     * [preferred] the one the system last accepted a request for; the two
     * differ while a change is negotiated. [inTake] is true from Record until
     * Stop, pauses included. [now] is in seconds on a clock that never goes
     * back.
     */
    fun resolve(ranked: List<String>, actual: String?, preferred: String?, inTake: Boolean, now: Double): Decision {
        if (inTake && pinned == null) pinned = ranked.firstOrNull() ?: actual
        val held = pinned
        var target = held ?: ranked.firstOrNull()
        var disconnected: String? = null
        if (held != null && held !in ranked) {
            // While Bluetooth or USB negotiate, the list can be empty or show
            // only the phone, even with a working accessory.
            val first = ranked.firstOrNull { it !in refusedFallbacks || it == actual }
            if (actual == held || first == null) {
                missingSince = null
                pending = null
                return Decision(recheckAfter = NEGOTIATION_RECHECK)
            }
            val since = missingSince ?: now
            missingSince = since
            val remaining = since + (if (removalReported) REPORTED_WAIT else MISSING_WAIT) - now
            if (remaining > 0) return Decision(recheckAfter = remaining)
            target = first
            if (actual == first) {
                if (inTake) disconnected = held
                pinned = if (inTake) first else null
                missingSince = null
                removalReported = false
                refusedFallbacks.clear()
            }
        } else {
            missingSince = null
            removalReported = false
            refusedFallbacks.clear()
        }

        if (target == null || (target == actual && target == preferred)) {
            pending = null
            return Decision(disconnected = disconnected)
        }
        // [preferred] acknowledges a request; [actual] confirms where the
        // sound comes from. Mismatches are retried a bounded number of times.
        if (pending != target || pendingActual != actual) {
            pending = target
            pendingActual = actual
            attempts = 0
            nextAttempt = now
        }
        if (now < nextAttempt) return Decision(recheckAfter = nextAttempt - now, disconnected = disconnected)
        if (attempts < RETRY_DELAYS.size) {
            val delay = RETRY_DELAYS[attempts]
            nextAttempt = now + delay
            attempts += 1
            return Decision(request = target, recheckAfter = delay, disconnected = disconnected)
        }
        if (attempts == RETRY_DELAYS.size && actual != target) {
            if (missingSince != null && ranked.any { it != target && it !in refusedFallbacks }) {
                // The held microphone went away and the system declined this
                // fallback. Try the others in order and confirm the winner.
                refusedFallbacks += target
                pending = null
                return Decision(recheckAfter = NEXT_FALLBACK_RECHECK, disconnected = disconnected)
            }
            attempts += 1
            return Decision(disconnected = disconnected, failed = target)
        }
        return Decision(disconnected = disconnected)
    }

    companion object {
        /** Seconds a held microphone may be missing, with no word from the system, before another is used (CaptureAudio.swift:56). */
        const val MISSING_WAIT = 3.0

        /** The same wait once the system reported the removal (CaptureAudio.swift:56). */
        const val REPORTED_WAIT = 0.5

        /** Seconds until the next look while the held microphone is absent from the list but still in use, or nothing else is there (CaptureAudio.swift:51). */
        const val NEGOTIATION_RECHECK = 1.0

        /** Seconds to wait after each request before asking again; after the last, the request has failed (CaptureAudio.swift:87). */
        val RETRY_DELAYS = listOf(0.25, 0.5, 1.0, 1.5)

        /** Seconds until the next fallback is tried after the system declined one (CaptureAudio.swift:101). */
        const val NEXT_FALLBACK_RECHECK = 0.05

        /** The kind of the event saved beside a take when its microphone was replaced (CaptureController.swift:2620). */
        const val FALLBACK_EVENT = "microphoneFallback"

        /**
         * The delays between the attempts to restart a microphone that
         * stopped delivering, in seconds. After the last one a running take
         * is paused: "Microphone disconnected. Recording paused."
         * (CaptureController.swift:2538-2549).
         */
        val RESTART_DELAYS = listOf(0.12, 0.25, 0.5, 0.8, 1.0)

        /**
         * The order of preference to save: [saved] as it is, then every
         * microphone seen for the first time, in the order the system lists
         * them. A list that was never saved begins with the microphone the
         * system is already using. Microphones that are not connected keep
         * their place (CaptureAudio.swift:22-28).
         */
        fun priority(saved: List<String>, discovered: List<String>, actual: String?): List<String> {
            val order = saved.toMutableList()
            if (order.isEmpty() && actual != null && actual in discovered) order += actual
            for (id in discovered) if (id !in order) order += id
            return order
        }

        /** The connected microphones in order of preference, as [resolve] takes them (CaptureController.swift:2595). */
        fun available(priority: List<String>, discovered: List<String>): List<String> = priority.filter { it in discovered }

        /**
         * [priority] with [id] moved [offset] places, −1 for one earlier and
         * +1 for one later, by changing places with the entry there.
         * Unchanged when that place does not exist (CaptureController.swift:2573-2581).
         */
        fun moved(priority: List<String>, id: String, offset: Int): List<String> {
            val index = priority.indexOf(id)
            if (index < 0 || index + offset !in priority.indices) return priority
            return priority.toMutableList().also {
                it[index] = priority[index + offset]
                it[index + offset] = id
            }
        }
    }
}
