package dev.relay.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import dev.relay.tv.data.Account
import dev.relay.tv.data.Profile
import dev.relay.tv.data.XtreamApi
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun LoginScreen(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val app = RelayApp.instance
    val existing = remember { app.store.account }
    var name by remember { mutableStateOf(existing?.name ?: "My IPTV") }
    var host by remember { mutableStateOf(existing?.hosts?.firstOrNull() ?: "") }
    var backups by remember { mutableStateOf(existing?.hosts?.drop(1)?.joinToString(", ") ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    InitialFocus(first)

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
        val acc = Account(name.trim().ifEmpty { "My IPTV" }, hosts, user.trim(), pass.trim())
        scope.launch {
            busy = true
            err = null
            val prevAcc = app.api.account
            val prevHost = app.api.host
            app.api.account = acc
            app.api.host = null
            try {
                app.api.authenticate()
                val changed = prevAcc?.username != acc.username || prevAcc?.hosts != acc.hosts
                app.store.account = acc
                app.store.activeHost = app.api.host
                if (changed) app.catalog.clear()
                onDone()
            } catch (e: Exception) {
                app.api.account = prevAcc
                app.api.host = prevHost
                err = e.message ?: "Couldn't reach any of the servers."
            } finally {
                busy = false
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)) {
        Column(Modifier.weight(1f).padding(end = 48.dp), verticalArrangement = Arrangement.Center) {
            Text("Relay", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = Palette.accent)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sign in with the Xtream Codes details from your provider.",
                fontSize = 20.sp, color = Palette.text,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Add every server URL you were given under Backup servers. If one stops answering, " +
                    "Relay moves to the next automatically and remembers the one that worked.",
                fontSize = 15.sp, color = Palette.dim, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your login stays on this TV only.",
                fontSize = 15.sp, color = Palette.dim,
            )
        }
        Column(
            Modifier.width(560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvTextField(name, { name = it }, "Playlist name", Modifier.fillMaxWidth(), focusRequester = first)
            TvTextField(host, { host = it }, "Server URL (e.g. http://example.com:8080)", Modifier.fillMaxWidth())
            TvTextField(backups, { backups = it }, "Backup servers (comma separated, optional)", Modifier.fillMaxWidth())
            TvTextField(user, { user = it }, "Username", Modifier.fillMaxWidth())
            TvTextField(
                pass, { pass = it }, "Password", Modifier.fillMaxWidth(), password = true,
                imeAction = ImeAction.Done, onDone = submit,
            )
            if (err != null) Text(err ?: "", color = Palette.danger, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (busy) "Checking…" else "Sign in", submit, primary = true)
                if (onCancel != null) PillButton("Cancel", onCancel)
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
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (store.profiles.isEmpty()) {
            store.profiles = listOf(Profile(id = UUID.randomUUID().toString(), name = "Me", color = 0))
        }
        if (!app.session.autoPickDone) {
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
                    color = Palette.avatars[p.color % Palette.avatars.size],
                    locked = !p.pin.isNullOrEmpty(),
                    kids = p.kids,
                    focusRequester = if (i == 0) first else null,
                    onClick = { if (p.pin.isNullOrEmpty()) onPick(p) else pinFor = p },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Focusable({ adding = true }, Modifier.size(132.dp), shape = CircleShape) { f ->
                    Box(
                        Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = Palette.dim, modifier = Modifier.size(44.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Add profile", color = Palette.dim, fontSize = 16.sp)
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
            },
        )
    }
    pinFor?.let { p ->
        PinDialog(
            title = "PIN for ${p.name}",
            onDismiss = { pinFor = null },
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
        if (kids) Text("Kids", color = Palette.accent, fontSize = 13.sp)
    }
}

@Composable
fun AddProfileDialog(nextColor: Int, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var kids by remember { mutableStateOf(false) }
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
            if (kids) "Kids profile: On (hides adult categories)" else "Kids profile: Off",
            { kids = !kids },
        )
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
                        ),
                    )
                }
            }, primary = true)
            PillButton("Cancel", onDismiss)
        }
    }
}
