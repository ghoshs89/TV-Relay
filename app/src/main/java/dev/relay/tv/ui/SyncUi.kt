package dev.relay.tv.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.companion.CompanionPanel
import dev.relay.tv.companion.CompanionServer
import dev.relay.tv.companion.PairHost
import dev.relay.tv.data.AppLog
import dev.relay.tv.sync.PairAgreement
import dev.relay.tv.sync.PairBundle
import dev.relay.tv.sync.PairClient
import dev.relay.tv.sync.PairDiscovery
import dev.relay.tv.sync.PairGate
import dev.relay.tv.sync.PairSession
import dev.relay.tv.sync.Pairing
import dev.relay.tv.sync.ScreenInfo
import dev.relay.tv.sync.SyncStatusText
import dev.relay.tv.sync.WaitingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "482913" shown as "482 913". */
private fun spaced(code: String) = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

/**
 * A new screen asks to join the household's sync. It can be found by the other screens on the home
 * network, and it shows the usual phone page for the pairing-link way. Nothing is applied until the
 * viewer presses Join; when a screen sent the bundle, both screens show the same six digits first.
 */
@Composable
fun JoinSyncDialog(onDismiss: () -> Unit, onJoined: () -> Unit) {
    val app = RelayApp.instance
    val scope = rememberCoroutineScope()
    val gate = remember { PairGate() }
    var code by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<PairBundle?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf<String?>(null) }
    val ui = remember { Handler(Looper.getMainLooper()) }
    val first = remember { FocusRequester() }
    InitialFocus(first, offer != null)

    val host = remember {
        object : PairHost {
            override fun hello(theirPublic: String): String? {
                val accepted = gate.hello(theirPublic) ?: return null
                ui.post {
                    code = accepted.code
                    offer = null
                    status = null
                }
                return accepted.replyPublicKey
            }

            override fun bundle(sealed: ByteArray): Boolean {
                val b = gate.openBundle(sealed) ?: return false
                ui.post { offer = b }
                return true
            }

            override fun link(text: String): String {
                val b = Pairing.decode(text) ?: return "That pairing link isn't valid. Copy it again on the other screen."
                gate.reset()
                ui.post {
                    code = null
                    offer = b
                }
                return "Received. Look at the TV and choose Join."
            }
        }
    }

    DisposableEffect(Unit) {
        val server = CompanionServer(onLogin = {}, pair = host, scope = dev.relay.tv.companion.CompanionScope.JOIN)
        url = try {
            server.start()
        } catch (e: Exception) {
            AppLog.w("Pairing", "Couldn't open the join page", e)
            null
        }
        val port = server.port
        val stopAdvert: () -> Unit = if (port != null) PairDiscovery.advertise(app, app.sync.screenName(), port) else ({})
        onDispose {
            stopAdvert()
            server.stop()
        }
    }

    val doJoin: () -> Unit = {
        val b = offer
        if (b != null && !busy) {
            busy = true
            status = null
            scope.launch {
                val err = app.sync.join(b)
                busy = false
                if (err == null) onJoined() else status = err
            }
        }
    }

    TvDialog(onDismiss, width = 680.dp) {
        Text("Join sync from another screen", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        val o = offer
        if (o != null) {
            Text(
                "Another screen is inviting this one to sync with it (repository ${o.repo}). Joining brings its favourites, history, " +
                    "profiles and shared settings here, and sends this screen's to the others. A copy of this screen's data is kept first.",
                fontSize = Type.small, color = Palette.dim, lineHeight = 20.sp,
            )
            code?.let {
                Text("Code ${spaced(it)}. The other screen should show the same code.", color = Palette.accent, fontSize = Type.body)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (busy) "Joining…" else "Join", doJoin, primary = true, focusRequester = first)
                PillButton("Not now", { offer = null; code = null })
            }
        } else {
            Text(
                "On a screen that already syncs, open Settings, Sync between screens, Add a screen and choose \"${app.sync.screenName()}\".",
                fontSize = Type.small, color = Palette.dim, lineHeight = 20.sp,
            )
            code?.let {
                Text(spaced(it), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
                Text("The other screen shows the same six digits. If it doesn't, close this window.", fontSize = Type.small, color = Palette.dim)
            }
            CompanionPanel(
                url,
                "Or use your phone",
                "If the other screen can't find this one, scan the code, copy the pairing link on a screen that already syncs " +
                    "(Settings, Sync between screens, Add a screen, Copy pairing link) and paste it on that page.",
            )
            PillButton("Close", onDismiss, focusRequester = first)
        }
        status?.let { Text(it, color = Palette.danger, fontSize = Type.small) }
    }
}

/** On a screen that already syncs: pick the screen that is waiting, compare the digits, send. */
@Composable
fun AddScreenDialog(onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var waiting by remember { mutableStateOf<List<WaitingScreen>>(emptyList()) }
    var chosen by remember { mutableStateOf<WaitingScreen?>(null) }
    var agreement by remember { mutableStateOf<PairAgreement?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    InitialFocus(first, waiting.size to (chosen != null))

    DisposableEffect(Unit) {
        val stop = PairDiscovery.discover(app) { waiting = it }
        onDispose { stop() }
    }

    val pick: (WaitingScreen) -> Unit = { s ->
        if (!busy) {
            busy = true
            status = null
            scope.launch {
                try {
                    val session = PairSession.create()
                    val theirs = withContext(Dispatchers.IO) { PairClient.hello(app.http, s, session.publicText) }
                    val ag = session.agree(theirs)
                    if (ag == null) {
                        status = "${s.name} sent something Relay can't use."
                    } else {
                        agreement = ag
                        chosen = s
                    }
                } catch (e: Exception) {
                    status = e.message ?: "Couldn't reach that screen."
                }
                busy = false
            }
        }
    }

    val send: () -> Unit = {
        val ag = agreement
        val s = chosen
        val bundle = app.sync.pairBundle()
        if (ag != null && s != null && bundle != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val sealed = ag.seal(Pairing.encode(bundle).toByteArray(Charsets.UTF_8))
                    withContext(Dispatchers.IO) { PairClient.send(app.http, s, sealed) }
                    AppLog.i("Pairing", "Sent the pairing details to another screen")
                    status = "Sent. Now press Join on ${s.name}."
                    agreement = null
                    chosen = null
                } catch (e: Exception) {
                    status = e.message ?: "Couldn't send."
                }
                busy = false
            }
        }
    }

    TvDialog(onDismiss, width = 680.dp) {
        Text("Add a screen", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        val ag = agreement
        val s = chosen
        if (ag != null && s != null) {
            Text("Does ${s.name} show this code?", fontSize = Type.body, color = Palette.text)
            Text(spaced(ag.code), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
            Text(
                "Only send if the six digits are the same on both screens. Then press Join on the other screen.",
                fontSize = Type.small, color = Palette.dim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (busy) "Sending…" else "Yes, they match", send, primary = true, focusRequester = first)
                PillButton("No", { agreement = null; chosen = null })
            }
        } else {
            Text(
                if (waiting.isEmpty()) {
                    "Looking for screens that are waiting to join. On the new screen open Settings, Sync between screens, Join sync from another screen (or, on its sign-in page, " +
                        "\"I already use Relay on another screen\")."
                } else {
                    "Choose the screen to add:"
                },
                fontSize = Type.small, color = Palette.dim, lineHeight = 20.sp,
            )
            waiting.forEachIndexed { i, w ->
                PillButton(w.name, { pick(w) }, primary = i == 0, focusRequester = if (i == 0) first else null)
            }
            PillButton(
                "Copy pairing link",
                {
                    val b = app.sync.pairBundle()
                    if (b != null) {
                        clipboard.setText(AnnotatedString(Pairing.encode(b)))
                        status = "Copied. On the new screen, open Settings, Sync between screens, Join sync from another screen, scan its code with your phone and paste the link there. " +
                            "Anyone who has the link can join your sync, so don't share it."
                    }
                },
                focusRequester = if (waiting.isEmpty()) first else null,
            )
            PillButton("Close", onDismiss)
        }
        status?.let { Text(it, color = Palette.accent, fontSize = Type.small, lineHeight = 20.sp) }
    }
}

/** Takes another screen off the list. */
@Composable
fun RemoveScreenDialog(screens: List<ScreenInfo>, onPick: (ScreenInfo) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val now = System.currentTimeMillis()
    TvDialog(onDismiss, width = 600.dp) {
        Text("Remove a screen", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "This only takes the screen off the list. A screen that still has the key puts itself back when it syncs. " +
                "To lock a lost screen out, use New household key.",
            fontSize = Type.small, color = Palette.dim, lineHeight = 20.sp,
        )
        screens.forEachIndexed { i, s ->
            PillButton("${s.name} · ${SyncStatusText.ago(now - s.seen)}", { onPick(s) }, focusRequester = if (i == 0) first else null)
        }
        PillButton("Cancel", onDismiss)
    }
}
