package app.takes

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechModelFetcher
import app.takes.speech.SpeechModelSource
import app.takes.speech.SpeechModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The speech models subtitles are made with. None is part of the app: one is
 * downloaded when the person asks for it, checked against the SHA-256 the app
 * carries for each file, and kept outside backups until removed.
 *
 * Which models there are and what each consists of is data in the speech
 * module ([SpeechModels], [SpeechModel]); here is where they live on the phone.
 *
 * This file holds the app's only network code. Nothing else opens a connection,
 * and this does so only inside [download].
 */
object SubtitleModel {
    /** The size of [model] in megabytes, as shown before a download starts. */
    fun megabytes(model: SpeechModel): Int = megabytes(model.bytes)

    fun megabytes(bytes: Long): Int = Math.round(bytes / 1e6).toInt()

    fun folder(context: Context, model: SpeechModel): File = File(context.noBackupFilesDir, "speech/${model.folder}")

    fun installed(context: Context, model: SpeechModel): Boolean = model.installed(folder(context, model))

    /** The bytes of [model] on the phone: all of it, the part an interrupted download left, or nothing. */
    fun present(context: Context, model: SpeechModel): Long = model.present(folder(context, model))

    /** The model in use: the person's choice, else the one this phone gets by its memory and by what it already has. */
    fun selected(context: Context): SpeechModel = SpeechModels.selected(
        prefs(context).getString(CHOSEN, null), SpeechModels.known.filter { installed(context, it) }.map { it.id }.toSet(), totalMemory(context),
    )

    /** The models to choose from: those offered, and an earlier one while any of it is on the phone. */
    fun choices(context: Context): List<SpeechModel> =
        SpeechModels.choices(SpeechModels.known.filter { present(context, it) > 0 }.map { it.id }.toSet(), selected(context))

    fun recommended(context: Context): SpeechModel = SpeechModels.recommended(totalMemory(context))

    fun choose(context: Context, model: SpeechModel) = prefs(context).edit().putString(CHOSEN, model.id).apply()

    /** The phone's memory in bytes as Android reports it, somewhat less than it is sold with; 0 when unknown. */
    fun totalMemory(context: Context): Long = memory(context)?.totalMem ?: 0

    /** Whether the phone has the memory free, at this moment, that recognizing with [model] takes. Unknown counts as yes. */
    fun hasMemory(context: Context, model: SpeechModel): Boolean = memory(context)?.let { SpeechModels.fits(model, it.availMem) } ?: true

    /**
     * The model a transcription was loading or running when the app was last
     * stopped from outside, which is what Android does to an app that takes
     * more memory than the phone can give. Null when the last job ended by itself.
     */
    fun interrupted(context: Context): SpeechModel? = prefs(context).getString(RUNNING, null)?.let { id -> SpeechModels.known.firstOrNull { it.id == id } }

    /** Notes that a job with [model] begins (written at once: the app may not live to write it later), or with null that it ended. */
    fun running(context: Context, model: SpeechModel?) {
        val edit = prefs(context).edit()
        if (model == null) edit.remove(RUNNING).apply() else edit.putString(RUNNING, model.id).commit()
    }

    /** Bytes free where the model goes, less what an interrupted download already holds there. */
    fun hasRoom(context: Context, model: SpeechModel): Boolean {
        val folder = folder(context, model)
        val free = generateSequence(folder) { it.parentFile }.first { it.exists() }.usableSpace
        return free <= 0 || free > model.bytes - model.present(folder) + RESERVE
    }

    /**
     * Fetches what is missing of [model]; a download interrupted earlier
     * continues where it stopped. [progress] receives the bytes present so far.
     * Cancelling the coroutine stops it within moments and keeps what has
     * arrived. Throws IOException when the network fails or a file does not
     * pass verification.
     */
    suspend fun download(context: Context, model: SpeechModel, progress: (Long) -> Unit) {
        val folder = folder(context, model)
        withContext(Dispatchers.IO) {
            val source = HttpsSource()
            // A read can wait a minute for a silent server: closing the connection ends it at once.
            val watcher = launch { try { awaitCancellation() } finally { source.close() } }
            try {
                SpeechModelFetcher.download(model, folder, source, { done, _ -> progress(done) }, { !isActive })
            } catch (failure: IOException) {
                coroutineContext.ensureActive() // cancelled, not failed
                throw failure
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Deletes [model] and any unfinished download of it. Subtitles already made are not touched. */
    fun remove(context: Context, model: SpeechModel) = model.remove(folder(context, model))

    private fun prefs(context: Context) = context.getSharedPreferences("subtitles", Context.MODE_PRIVATE)

    private fun memory(context: Context): ActivityManager.MemoryInfo? = runCatching {
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    }.getOrNull()

    private const val CHOSEN = "model"
    private const val RUNNING = "running"
    const val RESERVE = 20_000_000L
}

/**
 * Reads a file over HTTPS, from a given byte when the server allows. A plain
 * GET with no cookies and no account; the only things sent are the address, the
 * range wanted and a fixed client name, in place of the system's default one
 * that names the phone model and Android version.
 */
private class HttpsSource : SpeechModelSource {
    @Volatile private var current: HttpURLConnection? = null

    override fun open(url: String, offset: Long): SpeechModelSource.Opened {
        if (!url.startsWith("https://")) throw IOException("Not an HTTPS address")
        val connection = URL(url).openConnection() as HttpURLConnection
        current = connection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Takes")
        // Compression would make the byte positions meaningless.
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        val status = connection.responseCode
        // The whole of what the app does on the network, for anyone reading the log. After redirects, the host is the one that answered.
        Log.i("Subtitles", "${url.substringAfterLast('/')} from byte $offset: HTTP $status from ${connection.url.host}")
        return when (status) {
            HttpURLConnection.HTTP_PARTIAL -> SpeechModelSource.Opened(connection.inputStream, offset)
            HttpURLConnection.HTTP_OK -> SpeechModelSource.Opened(connection.inputStream, 0)
            else -> { connection.disconnect(); throw IOException("HTTP $status") }
        }
    }

    fun close() {
        runCatching { current?.disconnect() }
    }
}
