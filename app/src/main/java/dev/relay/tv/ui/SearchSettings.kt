package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AccountInfo
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.Movie
import dev.relay.tv.data.Profile
import dev.relay.tv.data.Series
import dev.relay.tv.player.Playback
import dev.relay.tv.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Search ----------------

private class Results(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
)

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private fun norm(s: String) = s.lowercase().replace(NON_ALNUM, " ").trim()

/** Token match on normalized names, with a one-typo fallback for single-word queries. */
private fun matcher(query: String): ((String) -> Boolean)? {
    val tokens = norm(query).split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return { name ->
        val n = norm(name)
        tokens.all { t -> n.contains(t) } ||
            (tokens.size == 1 && tokens[0].length >= 5 && n.split(' ').any { w -> oneEdit(w, tokens[0]) })
    }
}

private fun oneEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++; j++
        } else {
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

@Composable
fun SearchTab() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val restrict = app.session.restrict(app.store.settings)
    var results by remember { mutableStateOf(Results()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(ui.query, catalog.channels, catalog.movies, catalog.series, restrict) {
        delay(350)
        val match = matcher(ui.query)
        if (match == null) {
            results = Results()
            return@LaunchedEffect
        }
        searching = true
        results = withContext(Dispatchers.Default) {
            Results(
                channels = catalog.channels.asSequence().filter { catalog.allowed(it.categoryId, restrict) && match(it.name) }.take(40).toList(),
                movies = catalog.movies.asSequence().filter { catalog.allowed(it.categoryId, restrict) && match(it.name) }.take(60).toList(),
                series = catalog.series.asSequence().filter { catalog.allowed(it.categoryId, restrict) && match(it.name) }.take(60).toList(),
            )
        }
        searching = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Row(Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
                TvTextField(
                    ui.query, { ui.query = it }, "Search channels, movies and series",
                    Modifier.width(680.dp), imeAction = ImeAction.Search,
                )
            }
        }
        val r = results
        if (ui.query.isNotBlank() && !searching && r.channels.isEmpty() && r.movies.isEmpty() && r.series.isEmpty()) {
            item { Text("No matches for \"${ui.query}\"", Modifier.padding(horizontal = 48.dp), color = Palette.dim) }
        }
        item {
            Rail("Channels", r.channels) { ch ->
                PosterCard(
                    ch.name, ch.logo, { Playback.live(ctx, r.channels, r.channels.indexOf(ch)) },
                    key = "s:ch:${ch.id}", width = 200.dp, aspect = 16f / 9f, fit = true,
                )
            }
        }
        item {
            Rail("Movies", r.movies) { m ->
                PosterCard(m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "s:mv:${m.id}", subtitle = m.year)
            }
        }
        item {
            Rail("Series", r.series) { s ->
                PosterCard(s.name, s.cover, { nav.push(Route.SeriesDetail(s)) }, key = "s:sr:${s.id}", subtitle = s.year)
            }
        }
    }
}

// ---------------- Settings ----------------

private enum class Edit { TMDB, AUDIO, SUBS, UA }

@Composable
fun SettingsTab() {
    val app = RelayApp.instance
    val store = app.store
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    store.version.intValue
    val s = store.settings
    val acc = store.account
    val current = app.session.profile

    var info by remember { mutableStateOf<AccountInfo?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Edit?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var pinGate by remember { mutableStateOf(false) }
    var addingProfile by remember { mutableStateOf(false) }
    var tmdbStatus by remember { mutableStateOf<String?>(null) }
    var viewingLog by remember { mutableStateOf(false) }
    var logNote by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        try {
            info = app.api.authenticate()
        } catch (e: Exception) {
            infoError = e.message
        }
    }

    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    val kidsLocked = current?.kids == true

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Section("Account") }
        item { Info("Playlist", acc?.name ?: "Not signed in") }
        item {
            Info(
                "Status",
                info?.status ?: infoError?.let { "Unreachable ($it)" } ?: "Checking…",
            )
        }
        item {
            val exp = info?.expires
            Info(
                "Expires",
                when {
                    exp == null -> if (info != null) "Unlimited" else "…"
                    else -> {
                        val days = ((exp * 1000 - System.currentTimeMillis()) / 86_400_000L)
                        "${dateFmt.format(Date(exp * 1000))}  (${if (days >= 0) "$days days left" else "expired"})"
                    }
                },
            )
        }
        item {
            val i = info
            Info("Connections", if (i?.maxConnections != null) "${i.activeConnections ?: 0} in use of ${i.maxConnections}" else "…")
        }
        item { Info("Server in use", app.api.host ?: acc?.hosts?.firstOrNull() ?: "None") }
        item {
            Setting(
                "Refresh channels and library",
                if (app.catalog.loading) "Refreshing…" else "Last updated ${if (app.catalog.updatedAt > 0) timeFmt.format(Date(app.catalog.updatedAt)) else "never"}",
                locked = false,
            ) { scope.launch { app.catalog.load(force = true) } }
        }
        item {
            Setting("Change servers or login", "${acc?.hosts?.size ?: 0} server(s) saved", kidsLocked) {
                nav.push(Route.Login)
            }
        }

        item { Section("Playback") }
        item {
            Setting("Play next episode automatically", if (s.autoNext) "On" else "Off", false) {
                store.settings = s.copy(autoNext = !s.autoNext)
            }
        }
        item {
            Setting("Live stream format", if (s.liveExt == "ts") "MPEG-TS (most compatible)" else "HLS (m3u8)", false) {
                store.settings = s.copy(liveExt = if (s.liveExt == "ts") "m3u8" else "ts")
            }
        }
        item { Setting("Preferred audio language", s.audioLang.ifBlank { "Device default" }, false) { edit = Edit.AUDIO } }
        item { Setting("Preferred subtitle language", s.subtitleLang.ifBlank { "Off" }, false) { edit = Edit.SUBS } }
        item { Setting("Player user agent", s.userAgent, kidsLocked) { edit = Edit.UA } }

        item { Section("Library") }
        item {
            Setting("TMDB API key (optional)", tmdbStatus ?: if (s.tmdbKey.isBlank()) "Not set" else "Set", kidsLocked) {
                edit = Edit.TMDB
            }
        }
        item {
            Setting(
                "Hide adult categories for all profiles",
                if (s.hideAdult) "On" else "Off",
                kidsLocked,
            ) {
                if (s.hideAdult && !current?.pin.isNullOrEmpty()) {
                    pinGate = true
                } else {
                    store.settings = s.copy(hideAdult = !s.hideAdult)
                }
            }
        }

        item { Section("Profiles") }
        item {
            Setting("Switch profile", current?.name ?: "", false) {
                app.session.profile = null
                app.ui = UiState()
                nav.reset(Route.Profiles)
            }
        }
        item { Setting("Add a profile", "", kidsLocked) { addingProfile = true } }
        store.profiles.filter { it.id != current?.id }.forEach { p ->
            item { Setting("Remove profile \"${p.name}\"", if (p.kids) "Kids" else "", kidsLocked, danger = true) { deleting = p } }
        }

        item { Section("Diagnostics") }
        item {
            val until = s.logUntil
            val on = until > System.currentTimeMillis()
            Setting(
                "Save detailed logs",
                if (on) "On until ${timeFmt.format(Date(until))} · ${AppLog.diskBytes() / 1024} KB" else "Off",
                false,
            ) {
                val next = if (on) 0L else System.currentTimeMillis() + AppLog.SAVE_WINDOW_MS
                AppLog.saveUntil = next
                store.settings = s.copy(logUntil = next)
                AppLog.i("App", if (next > 0) "Detailed logging on for 24h" else "Detailed logging off")
            }
        }
        item { Setting("View recent activity", "${AppLog.recent().size} lines", false) { viewingLog = true } }
        item {
            val crash = AppLog.lastCrash()
            Setting("Share logs", crash?.let { "Includes crash from ${timeFmt.format(Date(it))}" } ?: "", false) {
                logNote = LogShare.share(ctx)
            }
        }
        item {
            Setting("Clear logs", "", false) {
                AppLog.clear()
                logNote = "Logs cleared."
            }
        }
        logNote?.let { n -> item { Info("", n) } }
        item {
            Info(
                "Log folder",
                (AppLog.directory?.absolutePath ?: "unavailable") + "  (max ~1 MB, auto-off after 24 h)",
            )
        }

        item { Section("About") }
        item { Info("Version", "Relay ${BuildConfig.VERSION_NAME}") }
        item {
            Setting("Check for app updates", updateStatusText(), false) {
                Updater.dialogOpen = true
                if (Updater.pending == null && !Updater.busy) scope.launch { Updater.check(app.http) }
            }
        }
        item { Setting("Sign out and remove playlist", "", kidsLocked, danger = true) { confirmSignOut = true } }
    }

    when (edit) {
        Edit.TMDB -> TextEditDialog(
            "TMDB API key", s.tmdbKey, { edit = null },
            onSave = { k ->
                store.settings = s.copy(tmdbKey = k)
                edit = null
                if (k.isNotBlank()) {
                    tmdbStatus = "Checking…"
                    scope.launch { tmdbStatus = if (app.tmdb.validate(k)) "Valid" else "Key rejected" }
                } else {
                    tmdbStatus = null
                }
            },
            hint = "Free at themoviedb.org → Settings → API. Use the 32 character API key.",
        )
        Edit.AUDIO -> TextEditDialog(
            "Audio language", s.audioLang, { edit = null },
            onSave = { store.settings = s.copy(audioLang = it.lowercase()); edit = null },
            hint = "Two letter code, e.g. en, hi, bn, fr. Leave empty for the stream default.",
        )
        Edit.SUBS -> TextEditDialog(
            "Subtitle language", s.subtitleLang, { edit = null },
            onSave = { store.settings = s.copy(subtitleLang = it.lowercase()); edit = null },
            hint = "Two letter code to turn subtitles on automatically. Leave empty for off.",
        )
        Edit.UA -> TextEditDialog(
            "User agent", s.userAgent, { edit = null },
            onSave = { store.settings = s.copy(userAgent = it.ifBlank { "RelayTV/1.0" }); edit = null },
            hint = "Only change this if your provider blocks unknown players.",
        )
        else -> Unit
    }

    if (pinGate && current != null) {
        PinDialog(
            "Enter ${current.name}'s PIN",
            onDismiss = { pinGate = false },
            check = { it == current.pin },
            onOk = {
                pinGate = false
                store.settings = s.copy(hideAdult = false)
            },
        )
    }

    if (addingProfile) {
        AddProfileDialog(
            nextColor = store.profiles.size,
            onDismiss = { addingProfile = false },
            onSave = {
                store.profiles = store.profiles + it
                addingProfile = false
            },
        )
    }

    deleting?.let { p ->
        ConfirmDialog(
            "Remove ${p.name}?",
            "Their favourites and watch history on this TV are deleted too.",
            "Remove",
            onDismiss = { deleting = null },
            onConfirm = {
                store.deleteProfile(p.id)
                deleting = null
            },
        )
    }

    if (viewingLog) LogViewer(onDismiss = { viewingLog = false })

    if (confirmSignOut) {
        ConfirmDialog(
            "Sign out?",
            "This removes your login, saved lists and cache from this TV. Profiles and history stay.",
            "Sign out",
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                store.account = null
                store.activeHost = null
                app.api.account = null
                app.api.host = null
                app.catalog.clear()
                app.session.profile = null
                app.ui = UiState()
                nav.reset(Route.Login)
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), Modifier.padding(top = 22.dp, bottom = 6.dp, start = 4.dp),
        color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, Modifier.width(260.dp), color = Palette.dim, fontSize = 15.sp)
        Text(value, color = Palette.text, fontSize = 15.sp)
    }
}

@Composable
private fun Setting(label: String, value: String, locked: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    if (locked) {
        Info(label, "Locked on kids profiles")
        return
    }
    ListRow(
        label, onClick, Modifier.width(900.dp), key = "set:$label",
        trailing = value.ifBlank { null }, textColor = if (danger) Palette.danger else Palette.text,
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    TvDialog(onDismiss) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(body, color = Palette.dim, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Cancel", onDismiss)
            PillButton(action, onConfirm, danger = true)
        }
    }
}

