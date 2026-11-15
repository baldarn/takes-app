package app.takes.speech

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A web server for the fetcher tests, on this computer only: GET with an optional
 * `Range: bytes=N-`, one request for each connection. Android's unit tests are compiled
 * without the JDK's own test server, so this is written out.
 */
class FileServer : AutoCloseable {
    val content = mutableMapOf<String, ByteArray>()
    /** The Range header of every request, in order; "" when there was none. */
    val ranges = mutableListOf<String>()
    @Volatile var honorRanges = true

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val base = "http://127.0.0.1:${socket.localPort}/"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::answer) } }
            }
        }
    }

    private fun answer(client: Socket) {
        val lines = generateSequence { line(client) }.takeWhile { it.isNotEmpty() }.toList()
        val name = lines.first().split(" ")[1].removePrefix("/")
        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(":")?.trim() ?: ""
        synchronized(ranges) { ranges += range }
        val body = synchronized(content) { content[name] }
        val out = client.getOutputStream()
        fun head(status: String, length: Int, extra: String = "") =
            out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n$extra\r\n".toByteArray())
        if (body == null) {
            head("404 Not Found", 0)
        } else if (range.isNotEmpty() && honorRanges) {
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            head("206 Partial Content", body.size - from, "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n")
            out.write(body, from, body.size - from)
        } else {
            head("200 OK", body.size)
            out.write(body)
        }
        out.flush()
    }

    private fun line(client: Socket): String? {
        val text = StringBuilder()
        while (true) {
            val byte = client.getInputStream().read()
            if (byte < 0) return if (text.isEmpty()) null else text.toString()
            if (byte == '\n'.code) return text.toString().trimEnd('\r')
            text.append(byte.toChar())
        }
    }

    override fun close() = socket.close()
}
