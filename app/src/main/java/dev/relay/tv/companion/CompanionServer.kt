package dev.relay.tv.companion

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.KeysForm
import dev.relay.tv.data.KeysIntake
import dev.relay.tv.data.KeysResult
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
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
    /** Servers for use with a VPN, comma separated; empty keeps the marks read from the message. */
    val vpnHosts: String = "",
)

/**
 * What a screen that is waiting to join sync does when another screen contacts it (see
 * `ui/SyncUi.kt`). The calls come from the server's threads and must be quick and thread-safe.
 */
interface PairHost {
    /** Another screen sent its public key; the answer is this screen's own, or null when joining is not open. */
    fun hello(theirPublic: String): String?

    /** The sealed pairing bundle; true if it opened with the agreed key and was understood. */
    fun bundle(sealed: ByteArray): Boolean

    /** A pairing link pasted on the phone page; the answer is shown on the phone. */
    fun link(text: String): String
}

/**
 * Tiny LAN web page so a phone can sign in, download a backup, or restore one.
 * Runs only while a screen that shows its QR code is open. Every path starts with a random
 * one-time code shown only on the TV, so other devices on the network can't use it. The [scope]
 * limits the page to what the screen that opened it is for (see [CompanionScope]), and only requests
 * addressed to this TV's own address are answered.
 */
class CompanionServer(
    private val onLogin: (LoginForm) -> Unit,
    /** Called on the main thread after a key was saved or removed from the phone. */
    private val onKeys: (KeysResult) -> Unit = {},
    /** Called on the main thread after sync was set up (or its token replaced) from the phone; the text is what the phone was told. */
    private val onSync: (ok: Boolean, text: String) -> Unit = { _, _ -> },
    /** Set while this screen is waiting to join sync; opens the `/pair/` paths and a "Join sync" box on the page. */
    private val pair: PairHost? = null,
    /** What this page may do; anything else is refused. */
    private val scope: CompanionScope = CompanionScope.FULL,
) {
    private val token: String = randomToken()
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "relay-companion").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** The address to show as a QR code, or null if there's no network. */
    var url: String? = null
        private set

    /** The port the page is on while it is open. */
    val port: Int? get() = socket?.localPort

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
                try {
                    pool.execute { serve(client) }
                } catch (_: Exception) {
                    // The page is closing: turn the phone away instead of crashing.
                    try { client.close() } catch (_: Exception) {}
                }
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
                val req = LanHttp.read(sock, BufferedInputStream(sock.getInputStream()), MAX_BODY) ?: return
                route(sock, req.method, req.path, req.body)
            }
        } catch (e: Exception) {
            AppLog.w("Companion", "Request failed", e)
        }
    }

    /**
     * The pairing paths sit outside the secret path so a nearby screen can find them without the code
     * on this TV's screen. They do nothing unless this screen is waiting to join, and what they accept
     * must be sealed with a key only the two screens share.
     */
    private fun handlePair(sock: Socket, method: String, path: String, body: ByteArray) {
        val host = pair
        if (host == null || !scope.pairs || method != "POST") {
            respond(sock, 404, "text/plain", "Not found".toByteArray())
            return
        }
        when (path) {
            "/pair/hello" -> {
                val mine = host.hello(String(body, Charsets.UTF_8))
                if (mine == null) respond(sock, 403, "text/plain", "No".toByteArray())
                else respond(sock, 200, "text/plain", mine.toByteArray())
            }
            "/pair/send" -> {
                if (host.bundle(body)) respond(sock, 200, "text/plain", "ok".toByteArray())
                else respond(sock, 403, "text/plain", "No".toByteArray())
            }
            else -> respond(sock, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun route(sock: Socket, method: String, path: String, body: ByteArray) {
        if (path.startsWith("/pair/")) {
            handlePair(sock, method, path, body)
            return
        }
        val prefix = "/$token/"
        if (!path.startsWith(prefix) && path != "/$token") {
            respond(sock, 404, "text/plain", "Not found".toByteArray())
            return
        }
        val action = path.removePrefix("/$token").trim('/')
        if (!scope.allows(method, action)) {
            AppLog.i("Companion", "Refused a request this page is not for")
            respond(sock, 403, "text/plain", "This page can't do that. Open the phone companion from Settings on the TV.".toByteArray())
            return
        }
        when {
            method == "GET" && action.isEmpty() -> {
                val (tmdbSet, ratingsSet) = onMain {
                    RelayApp.instance.store.settings.let { it.tmdbKey.isNotBlank() to it.ratingsKey.isNotBlank() }
                }
                val syncRepo = RelayApp.instance.sync.settings.config?.repo?.toString()
                respond(sock, 200, "text/html; charset=utf-8", page(tmdbSet, ratingsSet, syncRepo, pair != null).toByteArray())
            }

            method == "POST" && action == "login" -> {
                val f = parseForm(String(body, Charsets.UTF_8))
                val form = LoginForm(
                    name = f["name"].orEmpty(), host = f["host"].orEmpty(), backups = f["backups"].orEmpty(),
                    username = f["user"].orEmpty(), password = f["pass"].orEmpty(), paste = f["paste"].orEmpty(),
                    vpnHosts = f["vpn"].orEmpty(),
                )
                main.post { onLogin(form) }
                AppLog.i("Companion", "Sign-in details received from phone")
                respond(sock, 200, "text/html; charset=utf-8", message("Sent to your TV", "Look at the TV: Relay is signing in now.").toByteArray())
            }

            method == "POST" && action == "keys" -> {
                val f = parseForm(String(body, Charsets.UTF_8))
                val form = KeysForm(
                    tmdb = f["tmdb"].orEmpty(), ratings = f["ratings"].orEmpty(),
                    removeTmdb = f["rm_tmdb"] != null, removeRatings = f["rm_ratings"] != null,
                )
                val result = receiveKeys(form)
                AppLog.i("Companion", "Keys received from phone")
                val text = result.lines.joinToString("<br>") { escape(it) }
                respond(sock, 200, "text/html; charset=utf-8", message("Keys", text).toByteArray())
            }

            method == "POST" && action == "sync" -> {
                val f = parseForm(String(body, Charsets.UTF_8))
                val text = receiveSync(f["repo"].orEmpty(), f["token"].orEmpty())
                respond(sock, 200, "text/html; charset=utf-8", message("Sync between screens", escape(text)).toByteArray())
            }

            method == "POST" && action == "join" && pair != null -> {
                val f = parseForm(String(body, Charsets.UTF_8))
                val text = pair.link(f["link"].orEmpty())
                AppLog.i("Companion", "Pairing link received from phone")
                respond(sock, 200, "text/html; charset=utf-8", message("Join sync", escape(text)).toByteArray())
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

            method == "GET" && action == "logs" -> {
                val text = onMain {
                    AppLog.i("Companion", "Logs downloaded to a phone")
                    AppLog.bundle(RelayApp.instance).readBytes()
                }
                respond(
                    sock, 200, "text/plain; charset=utf-8", text,
                    extra = "Content-Disposition: attachment; filename=\"relay-logs.txt\"\r\n",
                )
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

    /**
     * Checks each key with its service on this background thread, then saves on the main thread.
     * The TV shows the same words the phone gets. Keys are never written to the log.
     */
    private fun receiveKeys(form: KeysForm): KeysResult {
        val app = RelayApp.instance
        val result = runBlocking {
            KeysIntake.process(
                form,
                checkTmdb = { app.tmdb.check(it) },
                checkRatings = { app.ratings.check(it) },
                apply = { tmdb, ratings ->
                    onMain {
                        val now = app.store.settings
                        app.store.settings = now.copy(tmdbKey = tmdb ?: now.tmdbKey, ratingsKey = ratings ?: now.ratingsKey)
                    }
                },
            )
        }
        main.post {
            Toast.makeText(app, result.lines.joinToString("\n"), Toast.LENGTH_LONG).show()
            if (result.changed) onKeys(result)
        }
        return result
    }

    /**
     * Turns sync on from this screen (first screen) or replaces its token. The repository is checked and
     * the first sync done before this answers. Neither the token nor the key is ever logged.
     */
    private fun receiveSync(repo: String, token: String): String {
        val app = RelayApp.instance
        val replacing = app.sync.enabled
        val err = runBlocking { if (replacing) app.sync.replaceToken(token) else app.sync.setUp(repo, token) }
        AppLog.i("Companion", "Sync details received from phone: ${if (err == null) "accepted" else "not accepted"}")
        val text = err ?: if (replacing) "The new token works. Syncing now." else {
            val n = app.sync.status.screens.size.coerceAtLeast(1)
            "Sync is on. $n screen${if (n == 1) "" else "s"}. To add another screen, open Settings, Sync between screens, Join sync from another screen on that screen."
        }
        main.post {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            onSync(err == null, text)
        }
        return text
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        main.post(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun respond(sock: Socket, code: Int, type: String, bytes: ByteArray, extra: String = "") =
        LanHttp.respond(sock, code, type, bytes, extra)

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

    private fun syncSection(repo: String?): String = if (repo == null) """
        <section><h2>Sync between screens</h2>
        <p class="hint">Optional. Keeps profiles, favourites, watch history and shared settings the same on every screen you own. It needs a
        private GitHub repository of your own and a token that can only read and write that one repository. Everything is encrypted on the
        TV before it leaves, with a key that stays on your screens. The README on GitHub has the steps.</p>
        <form method="post" action="/$token/sync">
          <label>Repository
            <input name="repo" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="your-name/relay-sync"></label>
          <label>Token
            <input name="token" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="github_pat_..."></label>
          <button type="submit">Turn on sync</button>
        </form></section>
    """ else """
        <section><h2>Sync between screens</h2>
        <p class="hint">Sync is on for <b>${escape(repo)}</b>. Status, Sync now, adding a screen and stopping are in Settings, Sync between screens on the TV.
        If GitHub says the token expired, paste the new one here.</p>
        <form method="post" action="/$token/sync">
          <label>New token
            <input name="token" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="github_pat_..."></label>
          <button type="submit">Replace the token</button>
        </form></section>
    """

    private fun joinSection(): String = """
        <section><h2>Join sync</h2>
        <p class="hint">This screen is waiting to join your other screens. On a screen that already syncs, open Settings, Sync between screens, Add a screen
        and choose Copy pairing link, then paste the link here and press Join.</p>
        <form method="post" action="/$token/join">
          <label>Pairing link
            <textarea name="link" rows="3" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="relay-pair:..."></textarea></label>
          <button type="submit">Join</button>
        </form></section>
    """

    private fun page(tmdbSet: Boolean, ratingsSet: Boolean, syncRepo: String?, joining: Boolean) = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Relay on your TV</title><style>$CSS</style></head><body><main>
        <h1>Relay <span>on your TV</span></h1>
        ${if (scope.showsLogin) loginSection() else ""}
        ${if (scope.showsKeys) keysSection(tmdbSet, ratingsSet) else ""}
        ${if (joining && scope.showsJoin) joinSection() else ""}
        ${if (scope.showsSync) syncSection(syncRepo) else ""}
        ${if (scope.showsRest) restSections() else ""}
        </main></body></html>
    """.trimIndent()

    private fun loginSection() = """
        <section><h2>Sign in</h2>
        <p class="hint">Paste your provider's whole message and leave the rest empty, or fill the fields.</p>
        <form method="post" action="/$token/login">
          <label>Provider message<textarea name="paste" rows="5" placeholder="Paste the message here"></textarea></label>
          <label>Playlist name<input name="name" placeholder="My IPTV"></label>
          <label>Server URL<input name="host" inputmode="url" autocapitalize="off" placeholder="http://example.com:8080"></label>
          <label>Backup servers<input name="backups" inputmode="url" autocapitalize="off" placeholder="comma separated"></label>
          <label>Servers for use with a VPN<input name="vpn" inputmode="url" autocapitalize="off" placeholder="optional, comma separated"></label>
          <p class="hint">Relay reads which servers are for a VPN from the provider's message. Fill this only to set it yourself; every other server is then for use without a VPN. You can also change it later on the TV under Settings.</p>
          <label>Username<input name="user" autocapitalize="off" autocomplete="off"></label>
          <label>Password<input name="pass" type="password" autocomplete="off"></label>
          <button type="submit">Send to TV</button>
        </form></section>
    """

    private fun keysSection(tmdbSet: Boolean, ratingsSet: Boolean) = """
        <section><h2>Keys for scores and artwork</h2>
        <p class="hint">Optional. A TMDB key adds artwork, cast and trailers; an MDBList key adds Rotten Tomatoes scores. Paste one or both. A key is checked before it is saved, and a box left empty keeps what the TV has. Saved keys are never shown here.</p>
        <form method="post" action="/$token/keys">
          <label>TMDB key <b class="state">${if (tmdbSet) "Set" else "Not set"}</b>
            <input name="tmdb" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Paste the key"></label>
          <label class="check"><input type="checkbox" name="rm_tmdb" value="1"> Remove the saved TMDB key</label>
          <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">Where do I get this?</a>
          <label>MDBList key <b class="state">${if (ratingsSet) "Set" else "Not set"}</b>
            <input name="ratings" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Paste the key"></label>
          <label class="check"><input type="checkbox" name="rm_ratings" value="1"> Remove the saved MDBList key</label>
          <a href="https://mdblist.com/preferences/" target="_blank" rel="noopener">Where do I get this?</a><br>
          <button type="submit">Save keys</button>
        </form></section>
    """

    private fun restSections() = """
        <section><h2>Edit your library</h2>
        <p class="hint">Hide, show or pin categories and whole countries, reorder or remove favourite channels and rename channels, with your phone's keyboard.</p>
        <a class="button" href="/$token/library">Open the editor</a></section>

        <section><h2>Logs</h2>
        <p class="hint">Save the TV's troubleshooting log on this phone, to share it. Logins, passwords and keys are removed.</p>
        <a class="button" href="/$token/logs">Download logs</a></section>

        <section><h2>Backup</h2>
        <p class="hint">Profiles, favourites, history, channel groups and settings. Your login, PINs and API keys aren't included.</p>
        <a class="button" href="/$token/backup.json" download="relay-backup.json">Download backup</a></section>

        <section><h2>Restore</h2>
        <p class="hint">Choose a relay-backup.json file. It replaces this TV's profiles, favourites, history, channel groups and settings. The file is checked first; if it isn't valid, nothing changes. PINs already set on this TV are kept. If sync is on, the restored data spreads to your other screens.</p>
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
    """

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
            .state{color:#3ddc97;font-weight:600;margin-left:6px}
            label.check{display:flex;align-items:center;color:#e8eef7;margin:8px 0 4px}
            label.check input{display:inline-block;width:auto;margin:0 8px 0 0}
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
