package app.takes.core

/** The first, the last and some reports between, of [count] progress reports: where a test cancels. */
fun reportsToCancelAt(count: Int): List<Int> =
    (listOf(0, 1, count - 2, count - 1) + (1..8).map { it * count / 9 }).filter { it in 0 until count }.distinct().sorted()
