package app.takes

import android.content.Context
import android.util.Log
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechModelFetcher
import app.takes.speech.SpeechModelSource
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
 * The speech model subtitles are made with. It is not part of the app: it is
 * downloaded when the person asks for it, checked against the SHA-256 the app
 * carries for each file, and kept outside backups until removed.
 *
 * This file holds the app's only network code. Nothing else opens a connection,
 * and this does so only inside [download].
 */
object SubtitleModel {
    val model = SpeechModel.Default

    /** The download in megabytes, as shown before it starts. */
    val megabytes: Int get() = Math.round(model.bytes / 1e6).toInt()

    fun folder(context: Context): File = File(context.noBackupFilesDir, "speech/base")

    fun installed(context: Context): Boolean = model.installed(folder(context))

    /** Bytes free where the model goes, less what an interrupted download already holds there. */
    fun hasRoom(context: Context): Boolean {
        val folder = folder(context)
        val present = folder.listFiles().orEmpty().sumOf { it.length() }
        val free = generateSequence(folder) { it.parentFile }.first { it.exists() }.usableSpace
        return free <= 0 || free > model.bytes - present + RESERVE
    }

    /**
     * Fetches what is missing of the model; a download interrupted earlier
     * continues where it stopped. Cancelling the coroutine stops it within
     * moments and keeps what has arrived. Throws IOException when the network
     * fails or a file does not pass verification.
     */
    suspend fun download(context: Context, progress: (Float) -> Unit) {
        val folder = folder(context)
        withContext(Dispatchers.IO) {
            val source = HttpsSource()
            // A read can wait a minute for a silent server: closing the connection ends it at once.
            val watcher = launch { try { awaitCancellation() } finally { source.close() } }
            try {
                SpeechModelFetcher.download(model, folder, source, { done, total -> progress(done.toFloat() / total) }, { !isActive })
            } catch (failure: IOException) {
                coroutineContext.ensureActive() // cancelled, not failed
                throw failure
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Deletes the model and any unfinished download. Subtitles already made are not touched. */
    fun remove(context: Context) = model.remove(folder(context))

    private const val RESERVE = 20_000_000L
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
