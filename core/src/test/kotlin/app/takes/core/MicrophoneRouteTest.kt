package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The microphone order of preference and what happens when the microphone
 * in use goes away.
 *
 * The test marked TRANSLATED carries over, line for line and with its
 * messages, the microphone part of `captureAudioTests` in the iPhone app's
 * Tests/CoreTests.swift:555-593. The others are this port's own, from
 * YouRec/CaptureAudio.swift:22-108 and
 * YouRec/CaptureController.swift:2573-2581 and 2595.
 */
class MicrophoneRouteTest {
    private var route = MicrophoneRoute()

    private fun observe(available: List<String>, actual: String?, preferred: String?, time: Double, take: Boolean = true) =
        route.resolve(ranked = available, actual = actual, preferred = preferred, inTake = take, now = time)

    /** TRANSLATED: Tests/CoreTests.swift:555-593. */
    @Test fun priorityTakePinNegotiatedRouteDriftAndConfirmedDisconnectFallback() {
        val order = listOf("dji", "phone", "headset")
        val ranked = MicrophoneRoute.priority(saved = order, discovered = listOf("headset", "phone", "dji"), actual = "phone")
        assertEquals("Discovery and system route never reorder a saved DJI-first list", order, ranked)
        assertEquals("Seed a new list from the active headset", listOf("headset", "phone"),
            MicrophoneRoute.priority(saved = emptyList(), discovered = listOf("phone", "headset"), actual = "headset"))
        assertEquals("Saved order wins before recording", "dji", observe(order, "phone", null, 0.0, take = false).request)
        assertEquals("Starting during negotiation pins the ranked mic and reasserts it even when preferredInput already matches",
            "dji", observe(order, "phone", "dji", 0.3).request)
        assertNull("No route churn once confirmed", observe(order, "dji", "dji", 1.0).request)
        route.select("phone")
        assertEquals("Manual selection beats the saved priority mid-take", "phone", observe(order, "dji", "dji", 2.0).request)
        assertNull("Manual choice settles", observe(order, "phone", "phone", 2.1).request)
        assertNull("Do not accept a transient replacement", observe(listOf("dji", "headset"), "dji", "phone", 3.0).request)
        assertNull("An empty enumeration cannot cancel a manual choice", observe(emptyList(), null, null, 3.2).disconnected)
        assertEquals("Reassert manual choice after transient route drift", "phone", observe(order, "dji", "phone", 3.5).request)
        assertNull("Reordering or reconnecting a preferred mic leaves the take alone",
            observe(listOf("headset", "dji", "phone"), "phone", "phone", 4.0).request)
        assertNull("A still-active mic stays pinned even if temporarily missing from the list",
            observe(listOf("dji", "headset"), "phone", "phone", 5.0).disconnected)
        route.deviceDisconnected(previousInputs = listOf("phone"))
        assertNotNull("Wait for evidence of a real disconnect", observe(listOf("dji", "headset"), "headset", null, 6.0).recheckAfter)
        val fallback = observe(listOf("dji", "headset"), "headset", null, 7.1)
        assertEquals("Request the highest available fallback and wait for its real route", "dji", fallback.request)
        assertNull("Request the highest available fallback and wait for its real route", fallback.disconnected)
        assertEquals("Announce a confirmed disconnection", "phone", observe(listOf("dji", "headset"), "dji", "dji", 7.5).disconnected)
        assertNull("Fallback stays pinned and warns just once", observe(listOf("phone", "dji"), "dji", "dji", 8.0).disconnected)
        assertEquals("A returning mic cannot take over a take", "dji", observe(listOf("phone", "dji"), "phone", "dji", 9.0).request)
        route = MicrophoneRoute()
        assertEquals("After Stop the saved order applies again", "dji", observe(order, "phone", "phone", 10.0, take = false).request)
        route.select("headset")
        assertNull("Ignore an unconfirmed missing manual input", observe(listOf("dji", "phone"), "phone", "headset", 11.0).request)
        assertNull("Protect a manual input throughout format negotiation", observe(listOf("dji", "phone"), "phone", "headset", 13.0).request)
        for (time in listOf(14.1, 14.5, 15.1, 16.2)) {
            assertEquals("Bounded retries when iOS refuses a ranked fallback", "dji", observe(listOf("dji", "phone"), "phone", "dji", time).request)
        }
        assertNotNull("Proceed to the next available fallback", observe(listOf("dji", "phone"), "phone", "dji", 18.0).recheckAfter)
        assertEquals("Announce the working fallback after a persistent routing failure",
            "headset", observe(listOf("dji", "phone"), "phone", "dji", 18.1).disconnected)
        assertNull("Keep the working fallback through reconnect", observe(order, "phone", "phone", 19.0).request)
    }

    // MARK: The waits

    @Test fun aMicrophoneThatVanishesWithoutNoticeIsGivenThreeSeconds() {
        route.select("usb")
        assertEquals(MicrophoneRoute.Decision(), observe(listOf("usb", "phone"), "usb", "usb", 0.0))
        assertEquals(3.0, observe(listOf("phone"), "phone", null, 10.0).recheckAfter!!, 0.0)
        val waiting = observe(listOf("phone"), "phone", null, 12.0)
        assertEquals(1.0, waiting.recheckAfter!!, 0.0)
        assertNull(waiting.request)
        assertNull(waiting.disconnected)
        // Sound already comes from the phone: the change is announced at once, and the phone is asked for by name.
        val after = observe(listOf("phone"), "phone", null, 13.0)
        assertEquals("usb", after.disconnected)
        assertEquals("phone", after.request)
        assertEquals(0.25, after.recheckAfter!!, 0.0)
        assertEquals(MicrophoneRoute.Decision(), observe(listOf("phone"), "phone", "phone", 13.25))
    }

    @Test fun aReportedRemovalWaitsHalfASecond() {
        route.select("usb")
        observe(listOf("usb", "phone"), "usb", "usb", 0.0)
        route.deviceDisconnected(previousInputs = listOf("usb"))
        assertEquals(0.5, observe(listOf("phone"), "phone", "phone", 10.0).recheckAfter!!, 0.0)
        assertEquals(0.25, observe(listOf("phone"), "phone", "phone", 10.25).recheckAfter!!, 0.0)
        assertEquals(MicrophoneRoute.Decision(disconnected = "usb"), observe(listOf("phone"), "phone", "phone", 10.5))
        // The report of another device's removal does not shorten the wait.
        route = MicrophoneRoute()
        route.select("usb")
        route.deviceDisconnected(previousInputs = listOf("headset"))
        assertEquals(3.0, observe(listOf("phone"), "phone", "phone", 20.0).recheckAfter!!, 0.0)
    }

    @Test fun aMicrophoneThatComesBackDuringTheWaitIsKept() {
        route.select("usb")
        observe(listOf("phone"), "phone", "phone", 10.0)
        assertEquals(MicrophoneRoute.Decision(), observe(listOf("usb", "phone"), "usb", "usb", 11.0))
        // A later absence starts a new wait.
        assertEquals(3.0, observe(listOf("phone"), "phone", "phone", 20.0).recheckAfter!!, 0.0)
    }

    @Test fun whileTheListIsEmptyOrTheMicrophoneStillDeliversTheRouteLooksAgainEverySecond() {
        route.select("usb")
        assertEquals(MicrophoneRoute.Decision(recheckAfter = 1.0), observe(emptyList(), null, null, 1.0))
        assertEquals(MicrophoneRoute.Decision(recheckAfter = 1.0), observe(listOf("phone"), "usb", "usb", 9.0))
    }

    @Test fun outsideATakeALostChoiceReturnsToTheSavedOrderWithoutAWarning() {
        route.select("usb")
        observe(listOf("dji", "phone"), "phone", "phone", 0.0, take = false)
        val after = observe(listOf("dji", "phone"), "phone", "phone", 3.0, take = false)
        assertNull(after.disconnected)
        assertEquals("dji", after.request)
        assertEquals(MicrophoneRoute.Decision(), observe(listOf("dji", "phone"), "dji", "dji", 3.3, take = false))
    }

    // MARK: Requests

    @Test fun aRequestIsRepeatedFourTimesAndThenReportedAsFailed() {
        route.select("usb")
        val microphones = listOf("usb", "phone")
        assertEquals(MicrophoneRoute.Decision(request = "usb", recheckAfter = 0.25), observe(microphones, "phone", "usb", 0.0))
        // Looking again too early asks for nothing and says how long is left.
        val early = observe(microphones, "phone", "usb", 0.1)
        assertNull(early.request)
        assertEquals(0.15, early.recheckAfter!!, 1e-12)
        assertEquals(MicrophoneRoute.Decision(request = "usb", recheckAfter = 0.5), observe(microphones, "phone", "usb", 0.25))
        assertEquals(MicrophoneRoute.Decision(request = "usb", recheckAfter = 1.0), observe(microphones, "phone", "usb", 0.75))
        assertEquals(MicrophoneRoute.Decision(request = "usb", recheckAfter = 1.5), observe(microphones, "phone", "usb", 1.75))
        assertEquals(MicrophoneRoute.Decision(failed = "usb"), observe(microphones, "phone", "usb", 3.25))
        // The failure is reported once.
        assertEquals(MicrophoneRoute.Decision(), observe(microphones, "phone", "usb", 4.0))
        // A change in where the sound comes from starts the requests again.
        assertEquals("usb", observe(microphones, "headset", "usb", 5.0).request)
    }

    // MARK: The saved order

    @Test fun theSavedOrderKeepsAbsentMicrophonesAndAddsNewOnesAtTheEnd() {
        assertEquals(listOf("dji", "phone", "headset"),
            MicrophoneRoute.priority(saved = listOf("dji", "phone"), discovered = listOf("headset", "phone"), actual = "headset"))
        assertEquals(listOf("phone", "headset"),
            MicrophoneRoute.priority(saved = emptyList(), discovered = listOf("phone", "headset"), actual = null))
        // A microphone in use that the system does not list is not put first.
        assertEquals(listOf("phone"), MicrophoneRoute.priority(saved = emptyList(), discovered = listOf("phone"), actual = "ghost"))
        assertEquals(listOf("phone", "headset"),
            MicrophoneRoute.available(priority = listOf("dji", "phone", "headset"), discovered = listOf("headset", "phone")))
    }

    @Test fun anEntryMovesOnePlaceAtATime() {
        val order = listOf("dji", "phone", "headset")
        assertEquals(listOf("phone", "dji", "headset"), MicrophoneRoute.moved(order, "phone", -1))
        assertEquals(listOf("dji", "headset", "phone"), MicrophoneRoute.moved(order, "phone", 1))
        assertEquals(order, MicrophoneRoute.moved(order, "dji", -1))
        assertEquals(order, MicrophoneRoute.moved(order, "headset", 1))
        assertEquals(order, MicrophoneRoute.moved(order, "ghost", 1))
    }
}
