package dev.relay.tv.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Account
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.HostLabels
import dev.relay.tv.data.HostNet
import dev.relay.tv.data.KeysResult
import dev.relay.tv.data.ProviderText
import dev.relay.tv.data.XtreamApi
import dev.relay.tv.ui.InitialFocus
import dev.relay.tv.ui.Palette
import dev.relay.tv.ui.PillButton
import dev.relay.tv.ui.TvDialog
import kotlinx.coroutines.launch
import dev.relay.tv.ui.Type

/** Starts the phone page while this composable is on screen; returns its URL (or null). */
@Composable
fun rememberCompanion(
    onKeys: (KeysResult) -> Unit = {},
    onSync: (Boolean, String) -> Unit = { _, _ -> },
    onLogin: (LoginForm) -> Unit,
): String? {
    val latest by rememberUpdatedState(onLogin)
    val latestKeys by rememberUpdatedState(onKeys)
    val latestSync by rememberUpdatedState(onSync)
    var url by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val server = CompanionServer(onLogin = { latest(it) }, onKeys = { latestKeys(it) }, onSync = { ok, text -> latestSync(ok, text) })
        url = try {
            server.start()
        } catch (e: Exception) {
            AppLog.w("Companion", "Couldn't open phone page", e)
            null
        }
        onDispose {
            server.stop()
            url = null
        }
    }
    return url
}

@Composable
fun QrCode(text: String, size: Dp) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Canvas(Modifier.size(size)) {
            val n = matrix.width
            val cell = this.size.width / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix.get(x, y)) {
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

/** Panel with the QR code and the address, for screens that host the phone page. */
@Composable
fun CompanionPanel(url: String?, title: String, subtitle: String) {
    Row {
        if (url != null) {
            QrCode(url, 132.dp)
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.width(300.dp)) {
            Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Palette.dim, fontSize = Type.caption, lineHeight = 19.sp)
            Spacer(Modifier.size(6.dp))
            Text(
                url ?: "Not available: connect this device to Wi-Fi or Ethernet first.",
                color = if (url != null) Palette.accent2 else Palette.danger, fontSize = Type.caption,
            )
        }
    }
}

object Accounts {
    /** Turns phone form input into an account (paste box wins for anything it contains). */
    fun fromForm(f: LoginForm, fallbackName: String = "My IPTV"): Account? {
        val parsed = ProviderText.parse(f.paste)
        val hosts = (parsed.hosts + listOf(f.host) + f.backups.split(',', '\n', ' ', ';'))
            .map { XtreamApi.normalizeHost(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val user = (parsed.username ?: f.username).trim()
        val pass = (parsed.password ?: f.password).trim()
        if (hosts.isEmpty() || user.isEmpty() || pass.isEmpty()) return null
        val vpnList = f.vpnHosts.split(',', '\n', ' ', ';').map { XtreamApi.normalizeHost(it) }.filter { it.isNotEmpty() }
        val before = RelayApp.instance.store.account
        val nets = HostLabels.merge(hosts, parsed.nets, vpnList) { h -> before?.netOf(h) ?: HostNet.ANY }
        return Account(f.name.trim().ifEmpty { fallbackName }, hosts, user, pass, Account.netsFor(hosts, nets))
    }

    /** Checks the login and saves it. Returns null on success or an error message. */
    suspend fun signIn(acc: Account): String? {
        val app = RelayApp.instance
        val prevAcc = app.api.account
        val prevHost = app.api.host
        app.api.account = acc
        app.api.host = null
        return try {
            app.api.authenticate()
            AppLog.i("Login", "Signed in via ${app.api.host} (${acc.hosts.size} server(s) saved)")
            // Only a different login (or a different set of servers) needs the library again; new server marks don't.
            val changed = !acc.sameLogin(prevAcc)
            app.store.account = acc
            app.store.activeHost = app.api.host
            if (changed) app.catalog.clear()
            null
        } catch (e: Exception) {
            AppLog.w("Login", "Sign-in failed", e)
            app.api.account = prevAcc
            app.api.host = prevHost
            e.message ?: "Couldn't reach any of the servers."
        }
    }
}

/**
 * Settings → Phone companion: sign in, send keys, back up or restore from a phone browser.
 * [onKeys] is called when a key was saved or removed from the phone; the key rows in Settings use it
 * to close the window once the key has arrived.
 */
@Composable
fun CompanionDialog(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
    title: String = "Phone companion",
    subtitle: String = "Your phone must be on the same Wi-Fi. The page lets you sign in, send API keys, turn on sync, download a backup, or restore one. " +
        "It closes when you close this window.",
    onKeys: ((KeysResult) -> Unit)? = null,
    /** Called after sync was turned on (or its token replaced) from the phone. */
    onSync: ((Boolean, String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val close = remember { FocusRequester() }
    InitialFocus(close)
    val url = rememberCompanion(onKeys = { r ->
        status = r.lines.joinToString(". ")
        onKeys?.invoke(r)
    }, onSync = { ok, text ->
        status = text
        onSync?.invoke(ok, text)
    }) { form ->
        val acc = Accounts.fromForm(form, RelayApp.instance.store.account?.name ?: "My IPTV")
        if (acc == null) {
            status = "The phone sent incomplete details: server, username and password are needed."
        } else {
            status = "Signing in…"
            scope.launch {
                val err = Accounts.signIn(acc)
                status = err ?: "Signed in. Loading your channels…"
                if (err == null) onSignedIn()
            }
        }
    }
    TvDialog(onDismiss, width = 640.dp) {
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        CompanionPanel(url, "Scan with your phone", subtitle)
        status?.let { Text(it, color = Palette.accent, fontSize = Type.small) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Close", onDismiss, focusRequester = close)
        }
    }
}
