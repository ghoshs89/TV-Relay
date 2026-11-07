package dev.relay.tv.companion

import android.os.Handler
import android.os.Looper
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** What the phone sent from the sign-in form. */
data class LoginForm(
    val name: String,
    val host: String,
    val backups: String,
    val username: String,
    val password: String,
    val paste: String,
)

/**
 * Tiny LAN web page so a phone can sign in, download a backup, or restore one.
 * Runs only while a screen that shows its QR code is open. Every path starts with a random
 * one-time code shown only on the TV, so other devices on the network can't use it.
 */
class CompanionServer(private val onLogin: (LoginForm) -> Unit) {
    private val token: String = randomToken()
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "relay-companion").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** The address to show as a QR code, or null if there's no network. */
    var url: String? = null
        private set

    fun start(): String? {
        val ip = lanAddress() ?: return null
        val server = try {
            ServerSocket(PREFERRED_PORT)
        } catch (_: Exception) {
            ServerSocket(0)
        }
        socket = server
        url = "http://$ip:${server.localPort}/$token/"
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "relay-companion-accept").apply { isDaemon = true }.start()
        AppLog.i("Companion", "Phone page open on port ${server.localPort}")
        return url
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pool.shutdownNow()
        AppLog.i("Companion", "Phone page closed")
    }

    // ---------- HTTP ----------

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 15_000
            s.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1)?.substringBefore('?') ?: return
                var length = 0
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                if (length > MAX_BODY) {
                    respond(sock, 413, "text/plain", "Too large".toByteArray())
                    return
                }
                val body = readBytes(input, length)
                route(sock, method, path, body)
            }
        } catch (e: Exception) {
            AppLog.w("Companion", "Request failed", e)
        }
    }

    private fun route(sock: Socket, method: String, path: String, body: ByteArray) {
        val prefix = "/$token/"
        if (!path.startsWith(prefix) && path != "/$token") {
            respond(sock, 404, "text/plain", "Not found".toByteArray())
            return
        }
        val action = path.removePrefix("/$token").trim('/')
        when {
            method == "GET" && action.isEmpty() -> respond(sock, 200, "text/html; charset=utf-8", page().toByteArray())

            method == "POST" && action == "login" -> {
                val f = parseForm(String(body, Charsets.UTF_8))
                val form = LoginForm(
                    name = f["name"].orEmpty(), host = f["host"].orEmpty(), backups = f["backups"].orEmpty(),
                    username = f["user"].orEmpty(), password = f["pass"].orEmpty(), paste = f["paste"].orEmpty(),
                )
                main.post { onLogin(form) }
                AppLog.i("Companion", "Sign-in details received from phone")
                respond(sock, 200, "text/html; charset=utf-8", message("Sent to your TV", "Look at the TV: Relay is signing in now.").toByteArray())
            }

            method == "GET" && action == "backup.json" -> {
                val json = onMain { RelayApp.instance.store.exportJson(BuildConfig.VERSION_NAME) }
                AppLog.i("Companion", "Backup downloaded")
                respond(
                    sock, 200, "application/json", json.toByteArray(),
                    extra = "Content-Disposition: attachment; filename=\"relay-backup.json\"\r\n",
                )
            }

            method == "POST" && action == "restore" -> {
                val text = String(body, Charsets.UTF_8)
                val result = onMain {
                    try {
                        val r = RelayApp.instance.store.importJson(text)
                        RelayApp.instance.catalog.applyCustom()
                        dev.relay.tv.ui.Palette.neutral = RelayApp.instance.store.settings.neutralTheme
                        r.summary()
                    } catch (e: Exception) {
                        "Couldn't restore: ${e.message}"
                    }
                }
                AppLog.i("Companion", result)
                respond(sock, 200, "text/plain; charset=utf-8", result.toByteArray())
            }

            method == "GET" && action == "library" -> respond(sock, 200, "text/html; charset=utf-8", LibraryPage.html(token, CSS).toByteArray())

            method == "GET" && action == "api/library" -> {
                val json = onMain { LibraryPage.snapshot() }
                respond(sock, 200, "application/json; charset=utf-8", json.toByteArray())
            }

            method == "POST" && action == "api/library" -> {
                val text = String(body, Charsets.UTF_8)
                val result = onMain {
                    try {
                        LibraryPage.apply(text)
                    } catch (e: IllegalArgumentException) {
                        "Nothing changed: ${e.message}"
                    }
                }
                AppLog.i("Companion", "Library edit from phone: $result")
                respond(sock, 200, "text/plain; charset=utf-8", result.toByteArray())
            }

            else -> respond(sock, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        main.post(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun respond(sock: Socket, code: Int, type: String, bytes: ByteArray, extra: String = "") {
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
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

    private fun readLine(input: InputStream): String? {
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

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) out else out.copyOf(off)
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()

    // ---------- Pages ----------

    private fun message(title: String, text: String) = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Relay</title><style>$CSS</style></head><body><main><h1>$title</h1><p>$text</p>
        <p><a href="/$token/">Back</a></p></main></body></html>
    """.trimIndent()

    private fun page() = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Relay on your TV</title><style>$CSS</style></head><body><main>
        <h1>Relay <span>on your TV</span></h1>

        <section><h2>Sign in</h2>
        <p class="hint">Paste your provider's whole message and leave the rest empty, or fill the fields.</p>
        <form method="post" action="/$token/login">
          <label>Provider message<textarea name="paste" rows="5" placeholder="Paste the message here"></textarea></label>
          <label>Playlist name<input name="name" placeholder="My IPTV"></label>
          <label>Server URL<input name="host" inputmode="url" autocapitalize="off" placeholder="http://example.com:8080"></label>
          <label>Backup servers<input name="backups" inputmode="url" autocapitalize="off" placeholder="comma separated"></label>
          <label>Username<input name="user" autocapitalize="off" autocomplete="off"></label>
          <label>Password<input name="pass" type="password" autocomplete="off"></label>
          <button type="submit">Send to TV</button>
        </form></section>

        <section><h2>Edit your library</h2>
        <p class="hint">Hide, show or pin categories and whole countries, reorder or remove favourite channels and rename channels, with your phone's keyboard.</p>
        <a class="button" href="/$token/library">Open the editor</a></section>

        <section><h2>Backup</h2>
        <p class="hint">Profiles, favourites, history, channel groups and settings. Your login, PINs and API keys aren't included.</p>
        <a class="button" href="/$token/backup.json" download="relay-backup.json">Download backup</a></section>

        <section><h2>Restore</h2>
        <p class="hint">Choose a relay-backup.json file. It replaces this TV's profiles, favourites, history, channel groups and settings. The file is checked first; if it isn't valid, nothing changes. PINs already set on this TV are kept.</p>
        <input type="file" id="file" accept=".json,application/json">
        <button id="restore" type="button">Restore to TV</button>
        <p id="result" class="hint"></p></section>

        <script>
        document.getElementById('restore').onclick = function () {
          var f = document.getElementById('file').files[0], out = document.getElementById('result');
          if (!f) { out.textContent = 'Choose a backup file first.'; return; }
          var r = new FileReader();
          r.onload = function () {
            fetch('/$token/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: r.result })
              .then(function (x) { return x.text(); }).then(function (t) { out.textContent = t; })
              .catch(function () { out.textContent = 'The TV did not answer. Is Relay still showing the QR code?'; });
          };
          r.readAsText(f);
        };
        </script>
        </main></body></html>
    """.trimIndent()

    companion object {
        private const val PREFERRED_PORT = 8787
        private const val MAX_BODY = 4 * 1024 * 1024

        internal const val CSS = """
            body{margin:0;background:#0b1220;color:#e8eef7;font:16px/1.45 system-ui,-apple-system,Roboto,sans-serif}
            main{max-width:560px;margin:0 auto;padding:20px}
            h1{color:#3ddc97;font-size:28px;margin:8px 0 18px}h1 span{color:#8a98b0;font-size:16px;font-weight:400}
            h2{font-size:18px;margin:0 0 6px}
            section{background:#141e30;border-radius:14px;padding:16px;margin:0 0 16px}
            label{display:block;font-size:13px;color:#8a98b0;margin:10px 0 0}
            input,textarea{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:11px;border-radius:9px;
              border:1px solid #1e2b45;background:#0b1220;color:#e8eef7;font-size:16px}
            button,.button{display:inline-block;margin-top:14px;padding:12px 20px;border:0;border-radius:999px;
              background:#3ddc97;color:#000;font-weight:600;font-size:16px;text-decoration:none}
            .hint{color:#8a98b0;font-size:13px;margin:4px 0}
            a{color:#6cb4ff}
        """

        private fun randomToken(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        /** The TV's address on the home network (Wi-Fi or Ethernet). */
        fun lanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
