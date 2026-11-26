package dev.relay.tv.companion

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/** The few pieces of plain HTTP that Relay's small home-network servers share (the phone page and Play on TV). */
object LanHttp {
    /** A parsed request: method, path without its query, the Host header (null when missing), and the body. */
    class Request(val method: String, val path: String, val body: ByteArray, val host: String? = null)

    /** The request line and the headers Relay reads. A length that is missing or not a number counts as 0. */
    class Head(val method: String, val path: String, val host: String?, val length: Int)

    /** Reads the request line and headers from [input]; null when they are malformed. */
    fun readHead(input: InputStream): Head? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return null
        var length = 0
        var host: String? = null
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val name = h.substringBefore(':').trim()
            val value = h.substringAfter(':', "").trim()
            when {
                name.equals("content-length", ignoreCase = true) -> length = value.toIntOrNull() ?: 0
                name.equals("host", ignoreCase = true) -> host = value
            }
        }
        return Head(method, path, host, length)
    }

    /**
     * Reads one request from [input]. Null when it is malformed. A body over [maxBody] (or a negative
     * length) is answered with 413 here and also gives null. A request addressed to any name other
     * than this device's own address and port (see [hostMatches]) is answered with 403 and gives null.
     */
    fun read(sock: Socket, input: InputStream, maxBody: Int): Request? {
        val head = readHead(input) ?: return null
        if (head.length < 0 || head.length > maxBody) {
            respond(sock, 413, "text/plain", "Too large".toByteArray())
            return null
        }
        if (!hostMatches(head.host, sock.localAddress, sock.localPort)) {
            respond(sock, 403, "text/plain", "Wrong address".toByteArray())
            return null
        }
        return Request(head.method, head.path, readBytes(input, head.length), head.host)
    }

    /**
     * True when the Host header names this device by its own address and the port the request came in
     * on. A web page elsewhere that points a name of its own at this device (DNS rebinding) sends that
     * name, so it is refused. Names are never looked up; only address literals can match.
     */
    fun hostMatches(header: String?, local: InetAddress, port: Int): Boolean {
        val h = header?.trim()?.lowercase() ?: return false
        val (addr, p) = when {
            h.startsWith("[") -> {
                val end = h.indexOf(']')
                if (end < 0) return false
                h.substring(1, end) to h.substring(end + 1).removePrefix(":")
            }
            h.count { it == ':' } == 1 -> h.substringBefore(':') to h.substringAfter(':')
            else -> return false
        }
        if (p.toIntOrNull() != port) return false
        val bytes = literal(addr) ?: return false
        return bytes.contentEquals(plain(local.address))
    }

    /** The bytes of an IPv4 or IPv6 address literal (zone dropped), or null for anything else, which is never looked up. */
    internal fun literal(s: String): ByteArray? {
        val a = s.substringBefore('%')
        if (a.isEmpty()) return null
        if (':' !in a) {
            val parts = a.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                val n = part.toInt()
                if (n > 255) return null
                out[i] = n.toByte()
            }
            return out
        }
        if (!a.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
        return try {
            plain(InetAddress.getByName(a).address)
        } catch (_: Exception) {
            null
        }
    }

    /** An IPv4 address carried as IPv6 (::ffff:a.b.c.d) as its four bytes; anything else unchanged. */
    private fun plain(b: ByteArray): ByteArray {
        if (b.size != 16) return b
        for (i in 0 until 10) if (b[i] != 0.toByte()) return b
        if (b[10] != 0xff.toByte() || b[11] != 0xff.toByte()) return b
        return b.copyOfRange(12, 16)
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
