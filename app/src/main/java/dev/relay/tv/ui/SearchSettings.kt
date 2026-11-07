package dev.relay.tv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.focus.FocusRequester
import dev.relay.tv.companion.CompanionDialog
import dev.relay.tv.data.ChannelGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
    val focusManager = LocalFocusManager.current
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

    val relay = useRelayKeyboard()
    val entry = remember { FocusRequester() }
    // Opening Search goes straight to typing.
    InitialFocus(entry)

    val r = results
    val resultRows: LazyListScope.() -> Unit = {
        if (ui.query.isNotBlank() && !searching && r.channels.isEmpty() && r.movies.isEmpty() && r.series.isEmpty()) {
            item { Text("No matches for \"${ui.query}\"", Modifier.padding(horizontal = 48.dp), color = Palette.dim) }
        }
        if (ui.query.isBlank()) {
            item {
                Text(
                    "Start typing: results appear as you go.", Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    color = Palette.dim,
                )
            }
        }
        item {
            Rail("Channels", r.channels) { ch ->
                ChannelTile(
                    ch, { Playback.live(ctx, r.channels, r.channels.indexOf(ch)) },
                    key = "s:ch:${ch.id}", nowPlaying = catalog.nowPlaying(ch.id),
                )
            }
        }
        item {
            Rail("Movies", r.movies) { m ->
                val rt = posterScore("m", m.id)
                PosterCard(
                    m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "s:mv:${m.id}", subtitle = m.year,
                    badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
                )
            }
        }
        item {
            Rail("Series", r.series) { s ->
                val rt = posterScore("s", s.id)
                PosterCard(
                    s.name, s.cover, { nav.push(Route.SeriesDetail(s)) }, key = "s:sr:${s.id}", subtitle = s.year,
                    badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
                )
            }
        }
    }

    if (relay) {
        // Relay keyboard docked on the left; results update on the right while typing.
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(420.dp).padding(start = 32.dp, top = 16.dp, end = 4.dp)) {
                Box(
                    Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (ui.query.isEmpty()) "Search channels, movies, series" else ui.query + "▏",
                        color = if (ui.query.isEmpty()) Palette.dim else Palette.text,
                        fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                RelayKeyboard(
                    value = ui.query,
                    onValueChange = { ui.query = it },
                    onDone = { focusManager.moveFocus(FocusDirection.Right) },
                    firstKey = entry,
                    keyHeight = 40.dp,
                    doneLabel = "Results",
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().fadeEdges(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                content = resultRows,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)) {
            item {
                Row(Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
                    TvTextField(
                        ui.query, { ui.query = it }, "Search channels, movies and series",
                        Modifier.width(680.dp), imeAction = ImeAction.Search,
                        focusRequester = entry, autoOpen = true,
                        // Keyboard closes on the search key; focus jumps to the first result.
                        onDone = { focusManager.moveFocus(FocusDirection.Down) },
                    )
                }
            }
            resultRows()
        }
    }
}

// ---------------- Settings ----------------

private enum class Edit { TMDB, RATINGS, AUDIO, SUBS, UA, XMLTV }

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
    var ratingsStatus by remember { mutableStateOf<String?>(null) }
    var viewingLog by remember { mutableStateOf(false) }
    var logNote by remember { mutableStateOf<String?>(null) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var companion by remember { mutableStateOf(false) }
    var lipSync by remember { mutableStateOf(false) }
    var offsetDialog by remember { mutableStateOf(false) }
    var managingGroup by remember { mutableStateOf<ChannelGroup?>(null) }
    val ctx = LocalContext.current
    val guide = app.catalog.guide
    val newGroupPrompt = remember { mutableStateOf(false) }
    val gate = rememberParentGate()
    var parentPinDialog by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }

    // Phones: back up to / restore from a file through Android's file picker.
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupNote = try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson(BuildConfig.VERSION_NAME).toByteArray()) }
            AppLog.i("Backup", "Saved backup file")
            "Backup saved."
        } catch (e: Exception) {
            "Couldn't save the backup: ${e.message}"
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupNote = try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val r = store.importJson(text)
            app.catalog.applyCustom()
            Palette.neutral = store.settings.neutralTheme
            AppLog.i("Backup", "Restored from file: ${r.profiles} profiles, ${r.favourites} favourites")
            r.summary()
        } catch (e: Exception) {
            "Couldn't restore: ${e.message}"
        }
    }

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
        Modifier.fillMaxSize().fadeEdges(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 64.dp),
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
                gate.run("Parent PIN to change servers") { nav.push(Route.Login) }
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
        item {
            Setting("Match TV refresh rate to video", if (s.matchFrameRate) "On (TVs only)" else "Off", false) {
                store.settings = s.copy(matchFrameRate = !s.matchFrameRate)
            }
        }
        item {
            Setting("Show stream info when playback starts", if (s.showStreamInfo) "On, 5 seconds" else "Off", false) {
                store.settings = s.copy(showStreamInfo = !s.showStreamInfo)
            }
        }
        item { Setting("Lip-sync adjustment", lipSyncLabel(s.audioDelayMs), false) { lipSync = true } }
        item {
            Setting("Pause & rewind live TV", if (s.timeshift) "On" else "Off", false) {
                store.settings = s.copy(timeshift = !s.timeshift)
            }
        }
        if (s.timeshift) {
            item {
                Setting("Rewind buffer", "${s.timeshiftMinutes} minutes (max 1.5 GB, deleted on exit)", false) {
                    store.settings = s.copy(
                        timeshiftMinutes = when (s.timeshiftMinutes) {
                            15 -> 30
                            30 -> 60
                            else -> 15
                        },
                    )
                }
            }
        }
        item {
            Setting("Start on last channel", if (s.startOnLastChannel) "On" else "Off", false) {
                store.settings = s.copy(startOnLastChannel = !s.startOnLastChannel)
            }
        }

        item { Section("Subtitles") }
        item {
            Setting("Size", SUB_SIZES[s.subSize.coerceIn(0, 3)], false) {
                store.settings = s.copy(subSize = (s.subSize + 1) % 4)
            }
        }
        item {
            Setting("Colour", if (s.subYellow) "Yellow" else "White", false) {
                store.settings = s.copy(subYellow = !s.subYellow)
            }
        }
        item {
            Setting("Background", if (s.subBox) "Dark box" else "Outline only", false) {
                store.settings = s.copy(subBox = !s.subBox)
            }
        }

        item { Section("Appearance") }
        item {
            Setting("Theme", if (s.neutralTheme) "Neutral black" else "Navy", false) {
                val next = !s.neutralTheme
                store.settings = s.copy(neutralTheme = next)
                Palette.neutral = next
            }
        }

        item {
            Setting(
                "Keyboard",
                when (s.keyboard) {
                    "relay" -> "Relay keyboard"
                    "system" -> "System keyboard"
                    else -> "Auto (Relay on TVs, system on phones)"
                },
                false,
            ) {
                store.settings = s.copy(
                    keyboard = when (s.keyboard) {
                        "auto" -> "relay"
                        "relay" -> "system"
                        else -> "auto"
                    },
                )
            }
        }

        item { Section("Channels & guide") }
        item {
            Setting("Create a channel group", "${store.channelCustom.groups.size} group(s)", kidsLocked) {
                edit = null
                newGroupPrompt.value = true
            }
        }
        store.channelCustom.groups.forEach { g ->
            item { Setting("Group: ${g.name}", "${g.channelIds.size} channels", kidsLocked) { managingGroup = g } }
        }
        item {
            Setting(
                "External guide (XMLTV) URL",
                if (s.xmltvUrl.isBlank()) "Not set (optional)" else (guide?.status ?: "Set"),
                kidsLocked,
            ) { edit = Edit.XMLTV }
        }
        if (s.xmltvUrl.isNotBlank()) {
            item {
                Setting("Refresh external guide", "", false) {
                    app.appScope.launch { app.catalog.refreshGuideIfNeeded(force = true) }
                }
            }
        }
        item { Setting("Guide time offset", offsetLabel(s.epgOffsetMin), false) { offsetDialog = true } }
        item {
            val n = store.reminders.size
            Setting("Programme reminders", if (n == 0) "None set · set them from the guide grid" else "$n set · clear all", false) {
                if (n > 0) store.reminders = emptyList()
            }
        }

        item { Section("Backup & phone") }
        item {
            Setting("Phone companion", "Sign in, back up or restore from your phone", kidsLocked) {
                gate.run("Parent PIN for the phone companion") { companion = true }
            }
        }
        item {
            Setting("Back up to a file", "Not including your login, PINs or API keys", false) {
                try {
                    createDoc.launch("relay-backup.json")
                } catch (e: Exception) {
                    backupNote = "No file picker on this device. Use Phone companion instead."
                }
            }
        }
        item {
            Setting("Restore from a file", "", kidsLocked) {
                gate.run("Parent PIN to restore a backup") {
                    try {
                        openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    } catch (e: Exception) {
                        backupNote = "No file picker on this device. Use Phone companion instead."
                    }
                }
            }
        }
        backupNote?.let { n -> item { Info("", n) } }

        item { Section("Library") }
        item {
            Setting("TMDB API key (optional)", tmdbStatus ?: if (s.tmdbKey.isBlank()) "Not set" else "Set", kidsLocked) {
                edit = Edit.TMDB
            }
        }
        item {
            Setting(
                "Rotten Tomatoes scores (MDBList key)",
                ratingsStatus ?: if (s.ratingsKey.isBlank()) "Not set" else "Set · ${app.ratings.cache.usedToday} lookups today",
                kidsLocked,
            ) { edit = Edit.RATINGS }
        }
        if (s.ratingsKey.isNotBlank()) {
            item {
                Setting("Scores on posters", if (s.posterScores) "On (titles you've opened)" else "Off", false) {
                    store.settings = s.copy(posterScores = !s.posterScores)
                }
            }
        }
        item {
            Setting(
                "Hide adult categories for all profiles",
                if (s.hideAdult) "On" else "Off",
                kidsLocked,
            ) {
                if (!s.hideAdult) {
                    store.settings = s.copy(hideAdult = true)
                } else if (store.hasParentPin) {
                    gate.run("Parent PIN to show adult categories") {
                        store.settings = store.settings.copy(hideAdult = false)
                    }
                } else if (!current?.pin.isNullOrEmpty()) {
                    pinGate = true
                } else {
                    store.settings = s.copy(hideAdult = false)
                }
            }
        }

        item { Section("Profiles") }
        item {
            Setting("Switch profile", current?.name ?: "", false) {
                val leave = {
                    app.session.profile = null
                    app.ui = UiState()
                    nav.reset(Route.Profiles)
                }
                if (current?.kids == true) gate.run("Parent PIN to leave the kids profile", leave) else leave()
            }
        }
        item { Setting("Add a profile", "", kidsLocked) { gate.run("Parent PIN to add a profile") { addingProfile = true } } }
        store.profiles.filter { it.id != current?.id }.forEach { p ->
            item {
                Setting("Remove profile \"${p.name}\"", if (p.kids) "Kids" else "", kidsLocked, danger = true) {
                    gate.run("Parent PIN to remove a profile") { deleting = p }
                }
            }
        }
        store.profiles.forEach { p ->
            item {
                Setting("PIN for ${p.name}", if (p.pin.isNullOrEmpty()) "None" else "Set", kidsLocked) {
                    gate.run("Parent PIN to change a profile PIN") { pinFor = p }
                }
            }
        }
        item {
            Setting("Parent PIN", if (store.hasParentPin) "Set · change or remove" else "Not set", kidsLocked) {
                gate.run("Current parent PIN") { parentPinDialog = true }
            }
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
        item {
            Setting("Sign out and remove playlist", "", kidsLocked, danger = true) {
                gate.run("Parent PIN to sign out") { confirmSignOut = true }
            }
        }
    }

    if (companion) {
        CompanionDialog(
            onDismiss = { companion = false },
            onSignedIn = { app.appScope.launch { app.catalog.load() } },
        )
    }
    if (lipSync) {
        StepperDialog(
            title = "Lip-sync adjustment",
            value = lipSyncLabel(s.audioDelayMs),
            hint = "If speech comes after lips move (common with soundbars), increase. If it comes before, decrease. " +
                "Applies from the next video you start.",
            onMinus = { store.settings = store.settings.let { it.copy(audioDelayMs = (it.audioDelayMs - 25).coerceAtLeast(-500)) } },
            onPlus = { store.settings = store.settings.let { it.copy(audioDelayMs = (it.audioDelayMs + 25).coerceAtMost(500)) } },
            onReset = { store.settings = store.settings.copy(audioDelayMs = 0) },
            onDismiss = { lipSync = false },
        )
    }
    if (offsetDialog) {
        StepperDialog(
            title = "Guide time offset",
            value = offsetLabel(s.epgOffsetMin),
            hint = "Shifts guide times if they're consistently early or late. Catch-up still uses the provider's times.",
            onMinus = {
                store.settings = store.settings.let { it.copy(epgOffsetMin = (it.epgOffsetMin - 30).coerceAtLeast(-720)) }
                app.catalog.clearGuideCache()
            },
            onPlus = {
                store.settings = store.settings.let { it.copy(epgOffsetMin = (it.epgOffsetMin + 30).coerceAtMost(720)) }
                app.catalog.clearGuideCache()
            },
            onReset = {
                store.settings = store.settings.copy(epgOffsetMin = 0)
                app.catalog.clearGuideCache()
            },
            onDismiss = { offsetDialog = false },
        )
    }
    managingGroup?.let { g -> GroupManageDialog(g, onDismiss = { managingGroup = null }) }
    if (newGroupPrompt.value) {
        TextEditDialog(
            "New channel group", "", { newGroupPrompt.value = false },
            onSave = {
                if (it.isNotBlank()) ChannelEdits.createGroup(it, null)
                newGroupPrompt.value = false
            },
            hint = "Then add channels from Live TV → a channel → Options.",
        )
    }

    when (edit) {
        Edit.XMLTV -> TextEditDialog(
            "External guide URL", s.xmltvUrl, { edit = null },
            onSave = {
                store.settings = s.copy(xmltvUrl = it.trim())
                edit = null
                if (it.isBlank()) {
                    guide?.clear()
                    app.catalog.clearGuideCache()
                } else {
                    app.appScope.launch { app.catalog.refreshGuideIfNeeded(force = true) }
                }
            },
            hint = "An XMLTV link (.xml or .xml.gz). Used only for channels your provider has no guide for.",
        )
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
        Edit.RATINGS -> TextEditDialog(
            "MDBList API key", s.ratingsKey, { edit = null },
            onSave = { k ->
                store.settings = s.copy(ratingsKey = k)
                edit = null
                if (k.isNotBlank()) {
                    ratingsStatus = "Checking…"
                    scope.launch { ratingsStatus = if (app.ratings.validate(k)) "Key works" else "Key rejected" }
                } else {
                    ratingsStatus = null
                }
            },
            hint = "Free at mdblist.com: sign in, then Preferences → API Access. Adds Rotten Tomatoes critics " +
                "and audience scores to movie and series pages (up to 1,000 lookups a day).",
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
                if (it.kids && !store.hasParentPin) parentPinDialog = true
            },
        )
    }
    if (parentPinDialog) ParentPinDialog(onDismiss = { parentPinDialog = false })
    pinFor?.let { p ->
        TextEditDialog(
            "PIN for ${p.name}", "", { pinFor = null },
            onSave = { v ->
                val digits = v.filter(Char::isDigit).take(8)
                store.profiles = store.profiles.map { if (it.id == p.id) it.copy(pin = digits.ifBlank { null }) else it }
                pinFor = null
            },
            hint = "Digits only. Leave empty to remove the PIN.",
            password = true,
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


private val SUB_SIZES = listOf("Small", "Medium", "Large", "Extra large")

private fun lipSyncLabel(ms: Int): String = when {
    ms == 0 -> "Off"
    ms > 0 -> "+$ms ms (sound was late)"
    else -> "$ms ms (sound was early)"
}

private fun offsetLabel(min: Int): String = when {
    min == 0 -> "None"
    else -> {
        val sign = if (min > 0) "+" else "−"
        val a = kotlin.math.abs(min)
        "$sign${a / 60}h${if (a % 60 != 0) " ${a % 60}m" else ""}"
    }
}

@Composable
private fun StepperDialog(
    title: String,
    value: String,
    hint: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(hint, fontSize = 14.sp, color = Palette.dim)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("−", onMinus, focusRequester = first)
            PillButton("+", onPlus)
            PillButton("Reset", onReset)
            PillButton("Done", onDismiss, primary = true)
        }
    }
}
