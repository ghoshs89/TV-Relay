package dev.relay.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.companion.Accounts
import dev.relay.tv.companion.CompanionPanel
import dev.relay.tv.companion.rememberCompanion
import dev.relay.tv.data.Account
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.HostNet
import dev.relay.tv.data.Profile
import dev.relay.tv.data.ProviderText
import dev.relay.tv.data.XtreamApi
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun LoginScreen(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val app = RelayApp.instance
    val clipboard = LocalClipboardManager.current
    val existing = remember { app.store.account }
    var name by remember { mutableStateOf(existing?.name ?: "My IPTV") }
    var host by remember { mutableStateOf(existing?.hosts?.firstOrNull() ?: "") }
    var backups by remember { mutableStateOf(existing?.hosts?.drop(1)?.joinToString(", ") ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    // Which network each server is for (read from a pasted message, or kept from before).
    var nets by remember { mutableStateOf<Map<String, HostNet>>(existing?.let { a -> a.hosts.associateWith { a.netOf(it) } } ?: emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    InitialFocus(first)

    val paste: () -> Unit = {
        val text = clipboard.getText()?.text.orEmpty()
        val p = ProviderText.parse(text)
        if (p.hosts.isEmpty() && p.username == null && p.password == null) {
            err = "Nothing to paste. Copy your provider's whole message first, then press Paste."
            note = null
        } else {
            if (p.hosts.isNotEmpty()) {
                host = p.hosts.first()
                backups = p.hosts.drop(1).joinToString(", ")
                nets = p.nets
            }
            p.username?.let { user = it }
            p.password?.let { pass = it }
            err = null
            val forVpn = p.nets.values.count { it == HostNet.VPN }
            note = "Filled from your provider's message: ${p.hosts.size} server(s)" +
                (if (forVpn > 0) " ($forVpn for use with a VPN)" else "") +
                (if (p.username != null) ", username" else "") +
                (if (p.password != null) ", password" else "") + ". Check them, then Sign in."
        }
    }

    val submit: () -> Unit = submit@{
        if (busy) return@submit
        val hosts = (listOf(host) + backups.split(',', '\n', ' ', ';'))
            .map { XtreamApi.normalizeHost(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (hosts.isEmpty() || user.isBlank() || pass.isBlank()) {
            err = "Server, username and password are all required."
            return@submit
        }
        val marks = nets
        val acc = Account(
            name.trim().ifEmpty { "My IPTV" }, hosts, user.trim(), pass.trim(),
            Account.netsFor(hosts, hosts.associateWith { marks[it] ?: HostNet.ANY }),
        )
        scope.launch {
            busy = true
            err = null
            val e = Accounts.signIn(acc)
            busy = false
            if (e == null) {
                onDone()
            } else {
                err = e
            }
        }
    }

    // Phone companion: details sent from a phone fill the form, then sign in.
    var wide by remember { mutableStateOf(false) }
    val companionUrl = if (wide) {
        rememberCompanion(scope = dev.relay.tv.companion.CompanionScope.LOGIN) { form ->
            val acc = Accounts.fromForm(form, name.ifBlank { "My IPTV" })
            if (acc == null) {
                err = "Your phone sent incomplete details: server, username and password are needed."
                note = null
            } else {
                name = acc.name
                host = acc.hosts.first()
                backups = acc.hosts.drop(1).joinToString(", ")
                nets = acc.hosts.associateWith { acc.netOf(it) }
                user = acc.username
                pass = acc.password
                note = "Received from your phone."
                submit()
            }
        }
    } else {
        null
    }

    val intro: @Composable () -> Unit = {
        Text(
            "Relay", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = Palette.accent,
            maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.height(8.dp))
        Text("Sign in with the Xtream Codes details from your provider.", fontSize = 19.sp, color = Palette.text)
    }
    val help: @Composable () -> Unit = {
        Text(
            "Tip: copy the message your provider sent you and press Paste provider message. " +
                "Add every server URL you were given; if one stops answering, Relay moves to the next " +
                "automatically. Your login stays on this device only.",
            fontSize = Type.small, color = Palette.dim, lineHeight = 21.sp,
        )
    }
    val form: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Paste provider message", paste, icon = Icons.Filled.Edit)
            note?.let { Text(it, color = Palette.accent, fontSize = Type.small) }
            TvTextField(name, { name = it }, "Playlist name", Modifier.fillMaxWidth(), focusRequester = first)
            TvTextField(host, { host = it }, "Server URL (e.g. http://example.com:8080)", Modifier.fillMaxWidth())
            TvTextField(backups, { backups = it }, "Backup servers (comma separated, optional)", Modifier.fillMaxWidth())
            TvTextField(user, { user = it }, "Username", Modifier.fillMaxWidth())
            TvTextField(
                pass, { pass = it }, "Password", Modifier.fillMaxWidth(), password = true,
                imeAction = ImeAction.Done, onDone = submit,
            )
            err?.let { Text(it, color = Palette.danger, fontSize = Type.body) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (busy) "Checking…" else "Sign in", submit, primary = true)
                if (onCancel != null) PillButton("Cancel", onCancel)
            }
            if (existing == null && !app.sync.enabled) {
                PillButton("I already use Relay on another screen", { joining = true }, compact = true)
            }
        }
    }

    if (joining) {
        JoinSyncDialog(onDismiss = { joining = false }, onJoined = { joining = false; onDone() })
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val isWide = maxWidth >= 760.dp
        LaunchedEffect(isWide) { wide = isWide }
        if (!isWide) {
            // Phones and narrow windows: one scrolling column so the keyboard never hides a field.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column { intro() }
                form()
                help()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
                Column(
                    Modifier.weight(0.42f).fillMaxHeight().padding(end = 48.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    intro()
                    Spacer(Modifier.height(20.dp))
                    help()
                    Spacer(Modifier.height(24.dp))
                    CompanionPanel(
                        companionUrl,
                        "Or sign in from your phone",
                        "Scan the code on the same Wi-Fi and paste your provider's message there.",
                    )
                }
                Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    form()
                }
            }
        }
    }
}

@Composable
fun ProfilesScreen(onPick: (Profile) -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    store.version.intValue
    val profiles = store.profiles
    var adding by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var askParentPin by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val gate = rememberParentGate()

    val nav = LocalNav.current
    val lock = app.session.profileSwitchLock(store)
    val approved = lock != null && lock == nav.profileSwitchApproval
    if (lock != null && !approved) {
        val lockedProfile = profiles.firstOrNull { it.id == lock.profileId }
        LaunchedEffect(lockedProfile) {
            lockedProfile?.let {
                AppLog.i("Access", "Kids lock: reopening ${it.name}")
                app.session.autoPickDone = true
                onPick(it)
            }
        }
        return
    }
    BackHandler(enabled = approved) { nav.cancelProfileSwitch() }
    LaunchedEffect(Unit) {
        if (store.profiles.isEmpty()) {
            store.profiles = listOf(Profile(id = UUID.randomUUID().toString(), name = "Me", color = 0))
        }
        if (!approved && !app.session.autoPickDone) {
            app.session.autoPickDone = true
            val only = store.profiles.singleOrNull()
            if (only != null && only.pin.isNullOrEmpty()) onPick(only)
        }
    }
    InitialFocus(first, profiles.size)

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Who's watching?", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            profiles.forEachIndexed { i, p ->
                ProfileTile(
                    name = p.name,
                    color = Palette.avatars[p.color.coerceAtLeast(0) % Palette.avatars.size],
                    locked = !p.pin.isNullOrEmpty(),
                    kids = p.kids,
                    focusRequester = if (i == 0) first else null,
                    onClick = { if (p.pin.isNullOrEmpty()) onPick(p) else pinFor = p },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Focusable({ gate.run("Parent PIN to add a profile") { adding = true } }, Modifier.size(132.dp), shape = CircleShape) { f ->
                    Box(
                        Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = Palette.dim, modifier = Modifier.size(44.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Add profile", color = Palette.dim, fontSize = Type.bodyLarge)
            }
        }
    }

    if (adding) {
        AddProfileDialog(
            nextColor = profiles.size,
            onDismiss = { adding = false },
            onSave = { p ->
                store.profiles = store.profiles + p
                adding = false
                if (p.kids && !store.hasParentPin) askParentPin = true
            },
        )
    }
    if (askParentPin) {
        ParentPinDialog(
            onDismiss = { askParentPin = false },
            intro = "Set a parent PIN so the kids profile can't be left or changed without you. " +
                "With one set, Relay reopens the kids profile when it was the last one used.",
        )
    }
    pinFor?.let { p ->
        PinDialog(
            title = "PIN for ${p.name}",
            onDismiss = {
                pinFor = null
                if (approved) nav.cancelProfileSwitch()
            },
            check = { it == p.pin },
            onOk = {
                pinFor = null
                onPick(p)
            },
        )
    }
}

@Composable
private fun ProfileTile(
    name: String,
    color: Color,
    locked: Boolean,
    kids: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Focusable(onClick, Modifier.size(132.dp), shape = CircleShape, focusRequester = focusRequester, scaleOnFocus = 1.1f) { _ ->
            Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
                Text(initials(name).ifEmpty { "?" }, fontSize = 46.sp, fontWeight = FontWeight.Bold, color = Palette.bg)
                if (locked) {
                    Icon(
                        Icons.Filled.Lock, null, tint = Palette.bg,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(name, color = Palette.text, fontSize = 18.sp)
        if (kids) Text("Kids", color = Palette.accent, fontSize = Type.caption)
    }
}

@Composable
fun AddProfileDialog(nextColor: Int, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var kids by remember { mutableStateOf(false) }
    var kidsMaxAge by remember { mutableIntStateOf(dev.relay.tv.data.KidsAge.DEFAULT_MAX_AGE) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    TvDialog(onDismiss) {
        Text("New profile", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        TvTextField(name, { name = it.take(20) }, "Name", Modifier.fillMaxWidth(), focusRequester = fr)
        TvTextField(
            pin, { pin = it.filter(Char::isDigit).take(8) }, "PIN (optional, digits)", Modifier.fillMaxWidth(),
            password = true, number = true,
        )
        PillButton(
            if (kids) "Kids profile: On (age-rated kids content only)" else "Kids profile: Off",
            { kids = !kids },
        )
        if (kids) {
            PillButton(
                "Kids age rating: ${dev.relay.tv.data.KidsAge.label(kidsMaxAge)}",
                {
                    val tiers = dev.relay.tv.data.KidsAge.TIERS
                    kidsMaxAge = tiers[(tiers.indexOf(kidsMaxAge) + 1) % tiers.size]
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Save", {
                if (name.isNotBlank()) {
                    onSave(
                        Profile(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            pin = pin.ifBlank { null },
                            kids = kids,
                            color = nextColor % Palette.avatars.size,
                            kidsMaxAge = if (kids) kidsMaxAge else 0,
                        ),
                    )
                }
            }, primary = true)
            PillButton("Cancel", onDismiss)
        }
    }
}
