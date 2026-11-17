package app.takes.pixels

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/** What one case found. */
class Checks {
    val failures = mutableListOf<String>()
    val notes = mutableListOf<String>()
    val differences = mutableListOf<String>()

    /** An assertion: the case fails when it does not hold. */
    fun check(holds: Boolean, message: () -> String) {
        if (!holds) failures += message()
    }

    /** A number measured, for the report. */
    fun note(text: String) {
        notes += text
    }

    /**
     * An assertion of the original's value that the shader is known not to
     * meet. It is disabled: worked out and reported, never a failure. When the
     * shader is changed to meet it, turn it into a [check].
     */
    fun original(what: String, holds: Boolean, measured: String) {
        differences += "${if (holds) "now met" else "not met"}: $what ($measured)"
    }
}

class Case(val name: String, val body: Checks.() -> Unit)

/**
 * Runs the pixel tests of the transition shader on a device or an emulator,
 * with nothing but what the app is built with:
 *
 *     adb shell am instrument -w app.takes.dev.test/app.takes.pixels.TransitionPixelRunner
 *
 * `-e only <text>` runs the cases whose name contains the text, or one of
 * several texts between `|`. Each case is reported as an instrumentation
 * status in the form test runners print, and the closing report ends with `OK (n tests)` or `FAILURES!!!`.
 */
class TransitionPixelRunner : Instrumentation() {
    private var only: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        only = arguments?.getString("only")
        start()
    }

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var failed = 0
        var documented = 0
        var ran = 0
        try {
            val bench = Bench(targetContext)
            report.append("\nRenderer: ${bench.renderer}; highp float in a fragment shader: ${bench.fragmentPrecision} bits\n")
            val suite = TransitionPixelCases(bench)
            val effects = EffectPixelCases(bench)
            val layers = LayerPixelCases(bench)
            val cases = (suite.all() + effects.all() + EffectPixelCases(bench, 480, 270).batchB() + EffectPixelCases(bench, 320, 180).batchC() + TimePixelCases(bench).all() + layers.all() + TitlePixelCases(bench).all() + ImagePixelCases(bench).all() + PictureToolPixelCases(bench).all() + PersonPixelCases(bench).all() + TemporalMattePixelCases(bench).all() + NightFusionPixelCases(bench).all() + HdrPixelCases(bench).all() + BackgroundMoviePixelCases(bench).all())
                .filter { case -> only?.split('|')?.any { case.name.contains(it) } ?: true }
            cases.forEachIndexed { index, case ->
                val status = Bundle().apply {
                    putString("id", "TransitionPixelRunner")
                    putString("class", "app.takes.pixels.TransitionPixelCases")
                    putString("test", case.name)
                    putInt("numtests", cases.size)
                    putInt("current", index + 1)
                }
                sendStatus(1, status)
                val checks = Checks()
                val began = SystemClock.elapsedRealtime()
                try {
                    case.body(checks)
                } catch (problem: Throwable) {
                    checks.failures += Log.getStackTraceString(problem)
                }
                checks.notes += "${SystemClock.elapsedRealtime() - began} ms"
                ran++
                documented += checks.differences.size
                val line = StringBuilder(if (checks.failures.isEmpty()) "ok    " else "FAIL  ").append(case.name)
                for (note in checks.notes) line.append("\n        ").append(note)
                for (difference in checks.differences) line.append("\n        disabled, documents the original — ").append(difference)
                for (failure in checks.failures) line.append("\n        ! ").append(failure)
                Log.i("TransitionPixels", line.toString())
                report.append(line).append('\n')
                if (checks.failures.isEmpty()) sendStatus(0, status) else {
                    failed++
                    status.putString("stack", checks.failures.joinToString("\n"))
                    sendStatus(-2, status)
                }
            }
            suite.release()
            layers.release()
            bench.release()
        } catch (problem: Throwable) {
            failed++
            report.append("FAIL  the tests could not be set up\n").append(Log.getStackTraceString(problem)).append('\n')
        }
        report.append(if (failed == 0) "\nOK ($ran tests)" else "\nFAILURES!!!\nTests run: $ran,  Failures: $failed")
        report.append("; disabled assertions documenting the original: $documented\n")
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
    }
}
