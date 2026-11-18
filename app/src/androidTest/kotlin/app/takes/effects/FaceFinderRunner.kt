package app.takes.effects

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import app.takes.FaceFinder
import app.takes.core.FaceDetection
import java.io.File

/**
 * MediaPipe's full-range face detector on still pictures, timed: the pictures named by `-e images`, a comma-separated
 * list of files in `/data/local/tmp`, each looked at as it stands and turned a quarter (as the recorder's camera
 * gives its picture), the faces found reported in fractions of the picture, then the time a look takes over 100 looks.
 *
 *     adb shell am instrument -w -e images face.jpg,profile.jpg app.takes.dev.test/app.takes.effects.FaceFinderRunner
 *
 * `-e expect 1,1` fails a picture where fewer faces than that are found.
 */
class FaceFinderRunner : Instrumentation() {
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
            val names = (arguments.getString("images") ?: error("-e images <names in /data/local/tmp>")).split(',')
            val expected = arguments.getString("expect")?.split(',')?.map { it.toInt() }
            FaceFinder(targetContext).use { finder ->
                names.forEachIndexed { index, name ->
                    val picture = BitmapFactory.decodeFile(File("/data/local/tmp", name).path) ?: error("cannot read $name")
                    val width = picture.width
                    val height = picture.height
                    val colours = IntArray(width * height).also { picture.getPixels(it, 0, width, 0, 0, width, height) }
                    val standing = FaceDetection.Pixels { x, y -> colours[y * width + x] and 0xFFFFFF }
                    // The same picture as a camera lying on its side gives it: turned a quarter back, stood up by a quarter clockwise.
                    val lying = FaceDetection.Pixels { x, y -> colours[x * width + (width - 1 - y)] and 0xFFFFFF }
                    val faces = finder.faces(width, height, 0, standing)
                    val turned = finder.faces(height, width, 90, lying)
                    fun line(faces: List<FaceDetection.Face>) = faces.joinToString("; ") {
                        "score %.2f at %.3f, %.3f, %.3f × %.3f".format(it.score, it.box.x, it.box.y, it.box.width, it.box.height)
                    }.ifEmpty { "none" }
                    say("$name ($width × $height): ${line(faces)}")
                    // The boxes come back on the picture stood up: the same as the standing picture's.
                    say("  turned: ${line(turned)}")
                    val wanted = expected?.getOrNull(index) ?: 0
                    if (faces.size < wanted || turned.size < wanted) { failed++; say("  expected $wanted") }
                    val started = System.nanoTime()
                    repeat(100) { finder.faces(width, height, 0, standing) }
                    say("  %.1f ms a look over 100".format((System.nanoTime() - started) / 1e6 / 100))
                }
            }
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
        const val TAG = "FaceFinderRunner"
    }
}
