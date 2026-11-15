package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parity suite ([TrackParity]) and writes what it found.
 *
 * As it stands it does not fail on a difference between today's functions
 * and the track model: it counts them by kind, prints the counts, and checks
 * that docs/TRACKS-PARITY.md is what it would write now. Run the tests with
 * `TAKES_RECORD_PARITY=1` in the environment to write that file anew, and
 * read what changed in it. With `TAKES_PARITY_GATE=1` it fails on every
 * difference whose kind is not in [TrackParityAccepted].
 */
class TrackParityTest {
    private val file = File("../docs/TRACKS-PARITY.md")
    private val recording = System.getenv("TAKES_RECORD_PARITY") == "1"
    private val gate = System.getenv("TAKES_PARITY_GATE") == "1"

    @Test
    fun theDifferencesBetweenTodayAndTheTrackModelAreTheOnesWrittenDown() {
        val cases = TrackParityReport.cases
        val text = TrackParityReport.text(cases)
        println(TrackParityReport.summary(cases))
        if (recording) file.writeText(text)
        // Whatever the track model answers, a project could hold it.
        assertEquals("Timelines the invariants refuse", emptyList<String>(), TrackParityReport.refused(cases))
        if (gate) {
            val open = cases.filter { it.verdict != TrackParity.Verdict.Identical && (it.unexplained.isNotEmpty() || !TrackParityAccepted.kinds.containsAll(it.kinds)) }.groupingBy { it.kind }.eachCount()
            assertTrue("Differences that are not accepted:\n" + open.entries.joinToString("\n") { "${it.value} × ${it.key}" }, open.isEmpty())
        }
        if (!recording) assertEquals("docs/TRACKS-PARITY.md is not what the suite finds now; record it and read the difference", file.takeIf { it.isFile }?.readText(), text)
    }
}
