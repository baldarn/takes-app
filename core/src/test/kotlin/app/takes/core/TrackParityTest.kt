package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parity suite ([TrackParity]) and checks what it finds against
 * docs/TRACKS-PARITY.md, which it also writes.
 *
 * It does not fail on a difference between today's functions and the track
 * model: it counts them by kind, prints the counts, and fails when the file
 * is not what it would write now. What it runs is chosen in the environment,
 * as the file says under "Running it":
 *
 *  - nothing set: a sample of the edits ([TrackParityReport.sampleCases]),
 *    compared with the file's section on the sample. The whole suite takes
 *    about as long as the other core tests together, so the
 *    ordinary run of the tests does not make it;
 *  - `TAKES_PARITY_FULL=1`: every call, compared with the whole file;
 *  - `TAKES_RECORD_PARITY=1`: every call, and the file written anew;
 *  - `TAKES_PARITY_GATE=1`: every call, compared, and a failure on every
 *    difference whose kind is not in [TrackParityAccepted], naming the kinds.
 */
class TrackParityTest {
    private val file = File("../docs/TRACKS-PARITY.md")
    private val recording = System.getenv("TAKES_RECORD_PARITY") == "1"
    private val gate = System.getenv("TAKES_PARITY_GATE") == "1"
    private val full = recording || gate || System.getenv("TAKES_PARITY_FULL") == "1"

    @Test
    fun theDifferencesBetweenTodayAndTheTrackModelAreTheOnesWrittenDown() {
        val cases = if (full) TrackParityReport.cases else TrackParityReport.sampleCases
        println(TrackParityReport.summary(cases))
        val written = file.takeIf { it.isFile }?.readText()
        // Whatever the track model answers, a project could hold it.
        assertEquals("Timelines the invariants refuse", emptyList<String>(), TrackParityReport.refused(cases))
        if (!full) {
            val section = TrackParityReport.sample(cases)
            // On a failure the two sections are shown side by side.
            if (written?.contains(section) != true) {
                assertEquals(
                    "The sample is not what docs/TRACKS-PARITY.md says of it; run the whole suite (TAKES_PARITY_FULL=1), then record the file (TAKES_RECORD_PARITY=1) and read the difference",
                    written?.substringAfter("## The sample", "")?.substringBefore("\n## ")?.let { "## The sample$it" }, section,
                )
            }
            return
        }
        val text = TrackParityReport.text(cases)
        if (recording) file.writeText(text)
        if (gate) {
            val open = TrackParityReport.open(cases, TrackParityAccepted.kinds)
            assertTrue("Differences that are not accepted (calls × kind; a call that shows several is counted under each):\n" + open.joinToString("\n"), open.isEmpty())
        }
        if (!recording) assertEquals("docs/TRACKS-PARITY.md is not what the suite finds now; record it (TAKES_RECORD_PARITY=1) and read the difference", written, text)
    }
}
