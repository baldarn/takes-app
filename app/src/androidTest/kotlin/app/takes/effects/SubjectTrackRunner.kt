package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.takes.SubjectTracks
import app.takes.core.EffectSubjectTrack
import app.takes.core.EffectValue
import app.takes.core.SourceMedia
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSettings
import java.io.File

/**
 * The subject analysis of a Dolly Zoom and of a Crash Zoom following a face on a real recording, timed: the clip
 * named by `-e clip`, taken from `/data/local/tmp` into the development build's `files/subjects/`, which stands for a
 * project folder. YouRec/VideoEffects/VideoEffectSubjectTracking.swift:108-150.
 *
 *     adb shell am instrument -w -e clip front.mp4 app.takes.dev.test/app.takes.effects.SubjectTrackRunner
 *
 * It reports the seconds the analysis takes against the clip's, how many samples it found and how many are faces,
 * where the subject is at each second, and that the second time the track is read back from the project's cache.
 * `-e x 0.5 -e y 0.4` sets the point it starts from.
 */
class SubjectTrackRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    private lateinit var arguments: Bundle

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        fun say(line: String) {
            Log.i(TAG, line)
            report.append(line).append('\n')
        }
        try {
            val folder = File(targetContext.filesDir, "subjects")
            val clip = File(folder, arguments.getString("clip") ?: error("-e clip <name in /data/local/tmp>"))
            if (!clip.isFile) File("/data/local/tmp", clip.name).takeIf { it.canRead() }?.copyTo(clip.also { folder.mkdirs() })
            val duration = MediaMetadataRetriever().run {
                try {
                    setDataSource(clip.path)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
                } finally {
                    release()
                }
            }
            val source = SourceMedia(id = "RUNNER", filename = clip.name, duration = duration)
            val timeline = TimelineClip(sourceID = source.id, start = 0.0, end = duration)
            val x = arguments.getString("x")?.toDouble() ?: 0.5
            val y = arguments.getString("y")?.toDouble() ?: 0.5
            File(folder, "Cache").deleteRecursively()
            for (kind in listOf(VideoEffectID.DollyZoom, VideoEffectID.CrashZoom)) {
                val effect = VideoEffectSettings(kind, 0.0, duration).with("targetX", EffectValue.Number(x)).with("targetY", EffectValue.Number(y))
                    .let { if (kind == VideoEffectID.CrashZoom) it.with("trackFace", EffectValue.Toggle(true)) else it }
                val started = SystemClock.elapsedRealtime()
                val track = SubjectTracks.track(targetContext, clip, source, timeline, effect)
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                val faces = track.samples.count { it.face }
                say("$kind: ${"%.1f".format(seconds)} s for ${"%.1f".format(duration)} s of footage, ${track.samples.size} samples (${faces} faces) of ${(duration / EffectSubjectTrack.STEP).toInt()} frames looked at")
                var second = 0.0
                while (second < duration) {
                    val at = track.sample(second)
                    say("  at ${"%.1f".format(second)} s: " + (at?.let { "centre ${"%.3f".format(it.center.x)}, ${"%.3f".format(it.center.y)}, box ${"%.3f".format(it.width)} × ${"%.3f".format(it.height)}, ${if (it.face) "face" else "person"}" } ?: "none"))
                    second += 1.0
                }
                if (track.samples.size < (duration / EffectSubjectTrack.STEP).toInt() / 2) { failed++; say("  fewer than half the frames have a subject") }
                // A second asking reads the same track back, from memory or the project's cache.
                val again = SystemClock.elapsedRealtime()
                val read = SubjectTracks.track(targetContext, clip, source, timeline, effect)
                say("  again: ${SystemClock.elapsedRealtime() - again} ms, ${if (read == track) "the same" else "DIFFERENT"}")
                if (read != track) failed++
            }
            val kept = File(File(folder, "Cache"), "subjects").listFiles().orEmpty()
            say("cache: ${kept.size} files, ${kept.sumOf { it.length() }} bytes")
            // Both are named by the recording, the span and the point alone, as in the original: one analysis.
            if (kept.size != 1) failed++
            if (arguments.getString("keep") != "true") File(folder, "Cache").deleteRecursively()
        } catch (problem: Throwable) {
            failed++
            say(Log.getStackTraceString(problem))
        }
        report.append(if (failed == 0) "\nOK" else "\nFAILURES!!! $failed")
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString() + "\n")
        })
    }

    private companion object {
        const val TAG = "SubjectTrackRunner"
    }
}
