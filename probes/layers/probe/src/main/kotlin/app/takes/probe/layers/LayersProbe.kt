@file:OptIn(UnstableApi::class)

package app.takes.probe.layers

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Runs one scenario and writes what it measured to `out/report.txt` in the app's files folder
 * (`in/` holds the footage of `tools/make-clips.sh`):
 *
 *     am instrument -w -e scenario <name> [-e key value ...] app.takes.probe.layers/.LayersProbe
 *
 * - `info`: the GPU's texture limit and the device's video codecs.
 * - `export`: `-e lanes N` sequences of `-e clip F1080-h264` (lane k from second k of the clip)
 *   plus the clock, `-e seconds 10` long, laid out as a `-e layout column|grid`, drawn as picture
 *   in picture; `-e gaps yes` puts black stills before and after the clip on lanes 1 and up;
 *   `-e check yes` reads every lane's frame number back from every composed frame; `-e canvas
 *   WxH` (else the clip's size), `-e keep yes` keeps the movie.
 * - `preview`: `-e players 3` ExoPlayers of `-e clip` on one screen, playing `-e play 20`
 *   seconds, then `-e seeks 10` seeks of all at once, paused and playing.
 */
class LayersProbe : Instrumentation() {
    init {
        java.util.Locale.setDefault(java.util.Locale.ROOT)
    }

    private lateinit var arguments: Bundle
    private lateinit var input: File
    private lateinit var output: File
    private val lines = mutableListOf<String>()

    override fun onCreate(arguments: Bundle) {
        this.arguments = arguments
        start()
    }

    private fun say(line: String) {
        Log.i("TakesLayers", line)
        synchronized(lines) { lines += line }
    }

    private fun text(name: String, fallback: String) = arguments.getString(name) ?: fallback
    private fun number(name: String, fallback: Int) = arguments.getString(name)?.toInt() ?: fallback

    override fun onStart() {
        input = File(targetContext.filesDir, "in")
        output = File(targetContext.filesDir, "out").apply { mkdirs() }
        val scenario = text("scenario", "info")
        say("== $scenario " + arguments.keySet().filter { it != "scenario" }.sorted().joinToString(" ") { "$it=${arguments.getString(it)}" })
        try {
            when (scenario) {
                "info" -> info()
                "export" -> export()
                "preview" -> preview()
                else -> say("unknown scenario")
            }
        } catch (problem: Throwable) {
            say("FAILED " + Log.getStackTraceString(problem))
        }
        File(output, "report.txt").appendText(lines.joinToString("\n", postfix = "\n"))
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", lines.joinToString("\n", postfix = "\n")) })
    }

    // --- What the device has ---

    private fun info() {
        say("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}, ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        say(graphics())
        val memory = android.app.ActivityManager.MemoryInfo().also { (targetContext.getSystemService(android.app.ActivityManager::class.java)).getMemoryInfo(it) }
        say("memory total ${memory.totalMem / 1_000_000} MB, available ${memory.availMem / 1_000_000} MB, memoryClass ${targetContext.getSystemService(android.app.ActivityManager::class.java).largeMemoryClass} MB large")
        for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            for (type in codec.supportedTypes.filter { it == MimeTypes.VIDEO_H264 || it == MimeTypes.VIDEO_H265 }) {
                val capabilities = codec.getCapabilitiesForType(type)
                val video = capabilities.videoCapabilities
                val hardware = if (Build.VERSION.SDK_INT >= 29) (if (codec.isHardwareAccelerated) "hardware" else "software") else "?"
                fun rates(width: Int, height: Int) = try { video.getAchievableFrameRatesFor(width, height)?.let { "${it.lower.roundToInt()}-${it.upper.roundToInt()}" } ?: "-" } catch (_: Exception) { "no" }
                say(
                    "codec ${codec.name} ${if (codec.isEncoder) "encoder" else "decoder"} $type $hardware instances=${capabilities.maxSupportedInstances} " +
                        "max ${video.supportedWidths.upper}x${video.supportedHeights.upper} 4K30=${video.areSizeAndRateSupported(3840, 2160, 30.0)} " +
                        "achievable 1080p=${rates(1920, 1080)} 4K=${rates(3840, 2160)}",
                )
            }
        }
    }

    // --- Heat ---

    private val power by lazy { targetContext.getSystemService(PowerManager::class.java) }

    private fun heat(): String {
        val status = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus.toString() else "?"
        val headroom = if (Build.VERSION.SDK_INT >= 30) "%.2f".format(power.getThermalHeadroom(10)) else "?"
        val battery = targetContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { it / 10.0 }
        return "thermal=$status headroom10s=$headroom battery=${battery}C"
    }

    private fun shell(command: String): String =
        uiAutomation.executeShellCommand(command).let { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() } }

    private fun temperatures(): String =
        shell("dumpsys thermalservice").substringAfter("Current temperatures from HAL:", "").substringBefore("Current cooling devices", "").lines()
            .mapNotNull { line -> Regex("mValue=([0-9.]+).*mName=([^,]+)").find(line)?.let { "${it.groupValues[2]}=${it.groupValues[1]}" } }
            .joinToString(" ")

    // --- Export ---

    private fun black(): File = File(output, "black.png").also { file ->
        if (!file.isFile) file.outputStream().use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun still(micros: Long, effects: List<androidx.media3.common.Effect>): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(black())).setImageDurationMs(maxOf(1, micros / 1000)).build())
            .setDurationUs(micros).setFrameRate(FPS).setEffects(Effects(emptyList(), effects)).build()

    private fun export() {
        val lanes = number("lanes", 3)
        val clip = File(input, text("clip", "F1080-h264") + ".mp4")
        val seconds = number("seconds", 10)
        val columns = if (text("layout", "column") == "grid") 2 else 1
        val gaps = text("gaps", "no") == "yes"
        val checking = text("check", "yes") == "yes"
        val source = MediaMetadataRetriever().use { it.setDataSource(clip.path); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() to it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt() }
        val canvas = arguments.getString("canvas")?.split("x")?.let { Size(it[0].toInt(), it[1].toInt()) } ?: Size(source.first, source.second)
        val grid = LaneGrid(canvas, lanes, columns)
        val fit = { Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT) }
        // Where the footage lies in a lane's frame after fitting.
        val scale = minOf(canvas.width.toFloat() / source.first, canvas.height.toFloat() / source.second)
        val shownWidth = source.first * scale / canvas.width
        val shownHeight = source.second * scale / canvas.height
        val content = floatArrayOf((1 - shownWidth) / 2, (1 - shownHeight) / 2, shownWidth, shownHeight)
        val movie = seconds * 1_000_000L
        val frames = seconds * FPS

        // Lane k plays the clip from its second k; with gaps, lanes 1 and up begin with k/2 s of black and end with 1 s of it.
        fun lead(lane: Int) = if (gaps && lane > 0) lane * 500_000L else 0L
        fun tail(lane: Int) = if (gaps && lane > 0) 1_000_000L else 0L
        val sequences = List(lanes) { lane ->
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).apply {
                if (lead(lane) > 0) addItem(still(lead(lane), listOf(fit())))
                val from = lane * 1_000_000L
                // `-e loop <seconds>`: the clip's stretch from its second k, that long, again and again (a movie longer than the clip); else once.
                val piece = arguments.getString("loop")?.toLong()?.times(1_000_000L) ?: (movie - lead(lane) - tail(lane))
                var left = movie - lead(lane) - tail(lane)
                while (left > 0) {
                    val length = minOf(piece, left)
                    val item = MediaItem.Builder().setUri(Uri.fromFile(clip))
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(from).setEndPositionUs(from + length).build()).build()
                    addItem(EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), listOf(fit()))).build())
                    left -= length
                }
                if (tail(lane) > 0) addItem(still(tail(lane), listOf(fit())))
            }.build()
        }
        val clock = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(still(movie, emptyList())).build()

        // What each lane should show at a frame: the clip's frame number, or black (-1).
        fun due(lane: Int, frame: Int): Int {
            val time = frame * 1_000_000L / FPS
            if (time < lead(lane) || time >= movie - tail(lane)) return -1
            return frame - (lead(lane) * FPS / 1_000_000L).toInt() + lane * FPS
        }
        val tally = List(lanes) { Tally() }
        val counted = AtomicLong()
        val composited = buildList {
            add(LayersEffect(grid))
            add(CheckEffect(canvas, if (checking) lanes else 0, content, counted) { time, seen ->
                val frame = Math.round(time * FPS / 1e6).toInt()
                seen.forEachIndexed { lane, what -> tally[lane].add(frame, due(lane, frame), what) }
            })
        }
        val composition = Composition.Builder(listOf(clock) + sequences)
            .setVideoCompositorSettings(LanesCompositor(grid))
            .setEffects(Effects(emptyList(), composited))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val file = File(output, "export.mp4")
        file.delete()
        say("export: $lanes lanes of ${clip.name} (${source.first}x${source.second}), canvas ${canvas.width}x${canvas.height}, composed frame ${grid.size.width}x${grid.size.height} (${if (columns == 1) "column" else "grid"}), $seconds s, gaps=$gaps check=$checking")
        say("  before: ${heat()} | ${temperatures()}")

        // Speed and heat every few seconds while it runs.
        val began = SystemClock.elapsedRealtime()
        val every = if (seconds >= 120) 10_000L else 2_000L
        val sampler = Thread {
            var last = 0L
            var lastAt = began
            try {
                while (true) {
                    Thread.sleep(every)
                    val now = SystemClock.elapsedRealtime()
                    val done = counted.get()
                    say("  at %.0f s: %d frames, %.1f fps over the last %.0f s, %s".format((now - began) / 1e3, done, (done - last) * 1e3 / (now - lastAt), (now - lastAt) / 1e3, heat()))
                    last = done
                    lastAt = now
                }
            } catch (_: InterruptedException) {
            }
        }.apply { start() }
        val run = transform(composition, file, patience = maxOf(600_000L, seconds * 20_000L))
        sampler.interrupt()
        sampler.join()
        say("  ${run.describe(frames)}")
        say("  decoders opened: " + synchronized(opened) { opened.joinToString() })
        say("  after: ${heat()} | ${temperatures()}")
        if (checking) tally.forEachIndexed { lane, it -> say("  lane $lane: ${it.describe()}") }
        if (text("keep", "no") != "yes") file.delete()
    }

    /** What one lane showed against what it should, over a movie. */
    private class Tally {
        var right = 0
        val off = sortedMapOf<Int, Int>()
        var blackForPicture = 0
        var pictureForBlack = 0
        var unreadable = 0
        val wrong = mutableListOf<String>()

        @Synchronized fun add(frame: Int, due: Int, seen: Seen) {
            val note = when (seen) {
                Seen.Black -> if (due < 0) { right++; null } else { blackForPicture++; "black" }
                Seen.Unreadable -> { unreadable++; "unreadable" }
                is Seen.Frame -> when {
                    due < 0 -> { pictureForBlack++; "picture ${seen.number}" }
                    seen.number == due -> { right++; null }
                    else -> { off.merge(seen.number - due, 1, Int::plus); "shown ${seen.number}" }
                }
            }
            if (note != null && wrong.size < 12) wrong += "$frame:$note (due $due)"
        }

        @Synchronized fun describe() = "right $right, off by $off, black for a picture $blackForPicture, a picture for black $pictureForBlack, unreadable $unreadable" +
            if (wrong.isEmpty()) "" else "; first wrong: " + wrong.joinToString()
    }

    private class Run(val millis: Long, val result: ExportResult?, val error: ExportException?) {
        fun describe(frames: Int): String = when {
            error != null -> "ERROR ${error.errorCodeName}: ${error.message} (" + generateSequence(error.cause) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" } + ") after $millis ms"
            result == null -> "NO RESULT after $millis ms (timed out)"
            else -> "done in $millis ms: ${result.videoFrameCount} of $frames frames, %.1f fps, ${result.fileSizeBytes} bytes, encoder ${result.videoEncoderName}".format(result.videoFrameCount * 1e3 / millis)
        }
    }

    /** The decoders Media3 opened in the last export, with the attempts that failed before each. */
    private val opened = mutableListOf<String>()

    private fun transform(composition: Composition, file: File, patience: Long): Run {
        synchronized(opened) { opened.clear() }
        // Media3's own decoder factory, asked to say which codec it took; `-e fallback yes` lets it try the next codec when one fails.
        val decoders = DefaultDecoderFactory.Builder(targetContext)
            .setEnableDecoderFallback(text("fallback", "no") == "yes")
            .apply { if (text("realtime", "no") == "yes") { setCodecPriority(0); setShouldConfigureOperatingRate(true) } }
            .apply {
                // `-e decoders software` keeps to the platform's software decoders, `hardware` to the others.
                when (text("decoders", "any")) {
                    "software" -> setMediaCodecSelector { mime, secure, tunneling -> MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.softwareOnly } }
                    "hardware" -> setMediaCodecSelector { mime, secure, tunneling -> MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.hardwareAccelerated } }
                }
            }
            .setListener { name, failures -> synchronized(opened) { opened += name + if (failures.isEmpty()) "" else " after " + failures.joinToString { it.message ?: it.errorCodeName } } }
            .build()
        val loader = DefaultAssetLoaderFactory(targetContext, decoders, Clock.DEFAULT, DefaultMediaSourceFactory(targetContext), DataSourceBitmapLoader(targetContext))
        val done = CountDownLatch(1)
        var result: ExportResult? = null
        var error: ExportException? = null
        val began = SystemClock.elapsedRealtime()
        var transformer: Transformer? = null
        val main = Handler(Looper.getMainLooper())
        main.post {
            transformer = Transformer.Builder(targetContext).setAssetLoaderFactory(loader)
                .setVideoMimeType(if (text("codec", "h264") == "hevc") MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { result = exportResult; done.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { result = exportResult; error = exportException; done.countDown() }
                }).build().apply { start(composition, file.path) }
        }
        if (!done.await(patience, TimeUnit.MILLISECONDS)) {
            main.post { transformer?.cancel() }
            return Run(SystemClock.elapsedRealtime() - began, null, null)
        }
        return Run(SystemClock.elapsedRealtime() - began, if (error == null) result else null, error)
    }

    // --- Preview ---

    private fun front(): String {
        val activities = shell("dumpsys activity activities")
        val top = activities.lines().firstOrNull { "topResumedActivity" in it || "mResumedActivity" in it }?.trim() ?: "?"
        val awake = shell("dumpsys power").lines().firstOrNull { "mWakefulness=" in it }?.trim() ?: "?"
        return (if ("app.takes.probe.layers/" in top) "FRONT" else "NOT IN FRONT: $top") + " $awake"
    }

    private fun gfx(reset: Boolean): String {
        val dump = shell("dumpsys gfxinfo app.takes.probe.layers" + if (reset) " reset" else "")
        if (reset) return ""
        fun field(name: String) = dump.lines().firstOrNull { it.trim().startsWith(name) }?.substringAfter(":")?.trim() ?: "?"
        return "rendered ${field("Total frames rendered")}, janky ${field("Janky frames")}, 50th ${field("50th percentile")}, 90th ${field("90th percentile")}, 99th ${field("99th percentile")}"
    }

    private fun memory(): String {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val total = shell("dumpsys meminfo app.takes.probe.layers").lines().firstOrNull { it.trim().startsWith("TOTAL PSS:") || it.trim().startsWith("TOTAL ") }?.trim()?.replace(Regex("\\s+"), " ")
        return "[$total] pss ${info.getMemoryStat("summary.total-pss").toInt() / 1024} MB (graphics ${info.getMemoryStat("summary.graphics").toInt() / 1024} MB, native ${info.getMemoryStat("summary.native-heap").toInt() / 1024} MB, java ${info.getMemoryStat("summary.java-heap").toInt() / 1024} MB)"
    }

    private fun preview() {
        val count = number("players", 3)
        val clip = File(input, text("clip", "F1080-h264") + ".mp4")
        val playSeconds = number("play", 20)
        val seeks = number("seeks", 10)
        val activity = startActivitySync(Intent(targetContext, PreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("players", count)) as PreviewActivity
        waitForIdleSync()
        say("preview: $count players of ${clip.name}; ${front()}; before players ${memory()}")
        val firstFrames = List(count) { java.util.concurrent.LinkedBlockingQueue<Long>() }
        val decoders = Array(count) { "?" }
        val dropped = Array(count) { AtomicLong() }
        lateinit var players: List<ExoPlayer>
        runOnMainSync {
            players = List(count) { index ->
                ExoPlayer.Builder(targetContext, ProbeRenderers(targetContext, text("realtime", "yes") == "yes") { if (index == 0 || it.contains("priority=0").not()) say("  player $index $it") }).build().apply {
                    setVideoTextureView(activity.views[index])
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)))
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = if (index == 0) 1f else 0f
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoders[index] = decoderName }
                        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) { firstFrames[index].offer(SystemClock.elapsedRealtime()) }
                        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
                            say("  player $index ERROR ${error.errorCodeName}: ${error.message}" + generateSequence(error.cause) { it.cause }.joinToString("") { " <- ${it.javaClass.simpleName}: ${it.message?.take(160)}" })
                        }
                        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) { say("  player $index codec error: $videoCodecError") }
                        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped[index].addAndGet(droppedFrames.toLong()) }
                    })
                    if (text("stagger", "no") != "yes") prepare()
                }
            }
        }
        if (text("stagger", "no") == "yes") for (index in 0 until count) { runOnMainSync { players[index].prepare() }; firstFrames[index].poll(10, TimeUnit.SECONDS)?.let { firstFrames[index].offer(it) } }
        // All ready, then playing together for a while.
        repeat(count) { firstFrames[it].poll(10, TimeUnit.SECONDS) }
        say("  decoders: ${decoders.joinToString()}")
        fun counters(): List<IntArray> {
            val out = mutableListOf<IntArray>()
            runOnMainSync { players.forEach { player -> val c = player.videoDecoderCounters; c?.ensureUpdated(); out += intArrayOf(c?.renderedOutputBufferCount ?: -1, c?.droppedBufferCount ?: -1, c?.skippedOutputBufferCount ?: -1, c?.maxConsecutiveDroppedBufferCount ?: -1) } }
            return out
        }
        runOnMainSync { players.forEach { it.play() } }
        Thread.sleep(2_000)
        activity.interrupted = false
        val before = counters()
        gfx(reset = true)
        val playStart = SystemClock.elapsedRealtime()
        Thread.sleep(playSeconds * 1000L)
        val after = counters()
        val playMillis = SystemClock.elapsedRealtime() - playStart
        val graphicsLine = gfx(reset = false)
        val valid = !activity.interrupted
        say("  playing ${playMillis / 1000.0} s (${if (valid) "valid" else "INVALID: left the front"}; ${front()}): ${memory()}")
        for (index in 0 until count) {
            val (rendered, droppedCount, skipped, run) = after[index].zip(before[index]).map { (a, b) -> a - b }
            say("    player $index: rendered $rendered (%.1f fps), dropped $droppedCount, skipped $skipped, max consecutive dropped ${after[index][3]}".format(rendered * 1e3 / playMillis))
        }
        say("    gfxinfo: $graphicsLine")

        // Seeks of all players at once to the same moment, as a scrub moves every deck: paused, then playing.
        val random = java.util.Random(7)
        for (playing in listOf(false, true)) {
            runOnMainSync { players.forEach { if (playing) it.play() else it.pause() } }
            Thread.sleep(1_000)
            activity.interrupted = false
            val latencies = mutableListOf<Long>()
            var missed = 0
            repeat(seeks) {
                val position = 1_000L + random.nextInt(15_000)
                firstFrames.forEach { it.clear() }
                val began = SystemClock.elapsedRealtime()
                runOnMainSync { players.forEach { it.seekTo(position) } }
                val each = firstFrames.map { it.poll(5, TimeUnit.SECONDS) }
                if (each.any { it == null }) missed++ else latencies += each.maxOf { it!! } - began
                Thread.sleep(300)
            }
            latencies.sort()
            val seekValid = !activity.interrupted
            say(
                "  seeks while ${if (playing) "playing" else "paused"} (${if (seekValid) "valid" else "INVALID: left the front"}; ${front()}): until all ${count} show the frame, " +
                    "median ${latencies.getOrNull(latencies.size / 2)} ms, max ${latencies.maxOrNull()} ms, all ${latencies}, no frame within 5 s: $missed; ${memory()}",
            )
        }
        say("  dropped as reported: ${dropped.joinToString { it.get().toString() }}")
        runOnMainSync { players.forEach { it.release() } }
        activity.finish()
        waitForIdleSync()
    }

    private companion object {
        const val FPS = 30
    }
}
