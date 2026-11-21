package dev.relay.tv.companion

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket

/** The few pieces of plain HTTP that Relay's small home-network servers share (the phone page and Play on TV). */
object LanHttp {
    /** A parsed request: method, path without its query, and the body. */
    class Request(val method: String, val path: String, val body: ByteArray)

    /**
     * Reads one request from [input]. Null when it is malformed. A body over [maxBody] is answered with
     * 413 here and also gives null.
     */
    fun read(sock: Socket, input: InputStream, maxBody: Int): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: return null
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return null
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (length < 0 || length > maxBody) {
            respond(sock, 413, "text/plain", "Too large".toByteArray())
            return null
        }
        return Request(method, path, readBytes(input, length))
    }

    fun respond(sock: Socket, code: Int, type: String, bytes: ByteArray, extra: String = "") {
        val status = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n${extra}Connection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
            if (buf.size() > 8192) return null
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) out else out.copyOf(off)
    }
}
