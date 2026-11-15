package app.takes.speech

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * A [SpeechModelSource] over HTTP(S) with the JDK's own client, which Android has too. It
 * lives with the tests because the module itself must not reach the network; an app that
 * decides to download can copy it.
 */
object HttpSource : SpeechModelSource {
    override fun open(url: String, offset: Long): SpeechModelSource.Opened {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        // Compression would make the byte positions meaningless.
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        return when (val status = connection.responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> SpeechModelSource.Opened(connection.inputStream, offset)
            HttpURLConnection.HTTP_OK -> SpeechModelSource.Opened(connection.inputStream, 0)
            else -> { connection.disconnect(); throw IOException("$url: HTTP $status") }
        }
    }
}
