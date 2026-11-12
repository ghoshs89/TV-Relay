package dev.relay.tv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.focus.FocusRequester
import dev.relay.tv.companion.CompanionDialog
import dev.relay.tv.data.ChannelGroup
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.HomeLayout
import dev.relay.tv.data.KeyText
import dev.relay.tv.data.KidsLimits
import dev.relay.tv.tv.WatchNext
import dev.relay.tv.data.Langs
import dev.relay.tv.player.SkipSizes
import dev.relay.tv.player.SleepTimer
import dev.relay.tv.data.AccountInfo
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Catalog
import dev.relay.tv.data.Profile
import dev.relay.tv.player.Playback
import dev.relay.tv.sync.SyncResult
import dev.relay.tv.sync.SyncStatusText
import dev.relay.tv.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Settings tab and its dialogs.

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
    // The key rows first ask how the key will arrive; "Send from my phone" opens the phone page.
    var keyChoice by remember { mutableStateOf<Edit?>(null) }
    var keysFromPhone by remember { mutableStateOf<Edit?>(null) }
    var lipSync by remember { mutableStateOf(false) }
    var offsetDialog by remember { mutableStateOf(false) }
    var managingGroup by remember { mutableStateOf<ChannelGroup?>(null) }
    val ctx = LocalContext.current
    val guide = app.catalog.guide
    val newGroupPrompt = remember { mutableStateOf(false) }
    val gate = rememberParentGate()
    var parentPinDialog by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var langsOpen by remember { mutableStateOf(false) }
    var homeOpen by remember { mutableStateOf(false) }
    var catsOpen by remember { mutableStateOf(false) }
    var limitsFor by remember { mutableStateOf<Profile?>(null) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var syncSetup by remember { mutableStateOf(false) }
    var confirmStopSync by remember { mutableStateOf(false) }
    var joinSync by remember { mutableStateOf(false) }
    var addScreen by remember { mutableStateOf(false) }
    var removeScreen by remember { mutableStateOf(false) }
    var confirmNewKey by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }

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
                if (app.catalog.loading) app.catalog.progress?.text ?: "Refreshing…"
                else "Last updated ${if (app.catalog.updatedAt > 0) timeFmt.format(Date(app.catalog.updatedAt)) else "never"}" +
                    (app.catalog.error?.let { " · $it" } ?: ""),
                locked = false,
            ) {
                if (app.catalog.loading) return@Setting
                AppLog.i("Catalog", "Refresh requested from Settings")
                // App scope: the refresh keeps going if you leave Settings.
                app.appScope.launch { app.catalog.load(force = true) }
            }
        }
        item {
            Setting("Change servers or login", "${acc?.hosts?.size ?: 0} server(s) saved", kidsLocked) {
                gate.run("Parent PIN to change servers") { nav.push(Route.Login) }
            }
        }

        item { Section("Playback") }
        if (WatchNext.supported(ctx)) {
            item {
                Setting(
                    "Continue watching on the TV home screen",
                    if (s.watchNext) "On · movies and episodes in progress (not kids profiles)" else "Off",
                    kidsLocked,
                ) {
                    val on = !s.watchNext
                    store.settings = s.copy(watchNext = on)
                    if (!on) app.appScope.launch(Dispatchers.IO) { WatchNext.clear(ctx.applicationContext) }
                }
            }
        }
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
        item {
            Setting(
                "Audio language order",
                s.audioLangs.ifBlank { "Follow my languages (" + app.session.langOrder(s).take(3).joinToString(", ") { Langs.label(it) } + ")" },
                false,
            ) { edit = Edit.AUDIO }
        }
        item { Setting("Subtitle languages", s.subtitleLang.ifBlank { "Off" }, false) { edit = Edit.SUBS } }
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
        item {
            Setting("Jump back (Left key)", SkipSizes.label(SkipSizes.clean(s.skipBackSec, SkipSizes.DEFAULT_BACK)), false) {
                store.settings = s.copy(skipBackSec = SkipSizes.next(SkipSizes.clean(s.skipBackSec, SkipSizes.DEFAULT_BACK)))
            }
        }
        item {
            Setting("Jump forward (Right key)", SkipSizes.label(SkipSizes.clean(s.skipForwardSec, SkipSizes.DEFAULT_FORWARD)), false) {
                store.settings = s.copy(skipForwardSec = SkipSizes.next(SkipSizes.clean(s.skipForwardSec, SkipSizes.DEFAULT_FORWARD)))
            }
        }
        item {
            val sleep = SleepTimer.clean(s.sleepMinutes)
            Setting("Sleep timer", if (sleep == 0) "Off" else "${SleepTimer.label(sleep)} after playback starts (press a key in the last minute to add more)", false) {
                store.settings = s.copy(sleepMinutes = SleepTimer.next(sleep))
            }
        }
        item {
            Setting("Ask \"Are you still watching?\"", if (s.askStillWatching) "On (after 3 episodes in a row or 4 hours of live TV)" else "Off", false) {
                store.settings = s.copy(askStillWatching = !s.askStillWatching)
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
            val n = app.catalog.versionGroupCount
            Setting(
                "Show each channel once",
                if (s.mergeVariants) "On · $n channels have more than one version (HD, FHD, backup)" else "Off · every version listed",
                false,
            ) { store.settings = s.copy(mergeVariants = !s.mergeVariants) }
        }
        item {
            Setting(
                "Search the whole TV guide",
                when {
                    s.xmltvUrl.isNotBlank() -> "Uses your external guide"
                    s.fullGuideSearch -> "On · your languages and favourites (${guide?.status ?: "downloading"})"
                    else -> "Off · finds what's on channels you've browsed"
                },
                kidsLocked,
            ) {
                if (s.xmltvUrl.isNotBlank()) return@Setting
                val on = !s.fullGuideSearch
                store.settings = s.copy(fullGuideSearch = on)
                if (on) {
                    app.appScope.launch { app.catalog.refreshGuideIfNeeded(force = true) }
                } else if (guide?.source == Catalog.PROVIDER_GUIDE) {
                    guide?.clear()
                    app.catalog.clearGuideCache()
                }
            }
        }
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
                    if (app.sync.enabled) {
                        confirmRestore = true
                    } else {
                        try {
                            openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        } catch (e: Exception) {
                            backupNote = "No file picker on this device. Use Phone companion instead."
                        }
                    }
                }
            }
        }
        backupNote?.let { n -> item { Info("", n) } }

        item { Section("Sync between screens") }
        if (!app.sync.enabled) {
            item {
                Setting("Turn on sync", "Off · same favourites, history and settings on every screen", kidsLocked) {
                    gate.run("Parent PIN to set up sync") { syncSetup = true }
                }
            }
            item {
                Setting("Join sync from another screen", "Already syncing on another screen? Add this one to it", kidsLocked) {
                    gate.run("Parent PIN to join sync") { joinSync = true }
                }
            }
        } else {
            val st = app.sync.status
            item {
                Info(
                    "Sync",
                    SyncStatusText.line(st.result, st.message, st.lastOk, System.currentTimeMillis()) + if (st.running) " · syncing…" else "",
                )
            }
            if (st.note.isNotEmpty()) item { Info("Last change", st.note) }
            item {
                Setting("Sync now", "", kidsLocked) { app.sync.runNow() }
            }
            item {
                val me = store.deviceId
                val now = System.currentTimeMillis()
                Info(
                    "Screens",
                    if (st.screens.isEmpty()) "This screen" else st.screens.joinToString("\n") {
                        "${it.name}${if (it.id == me) " (this screen)" else ""} · ${SyncStatusText.ago(now - it.seen)}"
                    },
                )
            }
            if (st.result == SyncResult.WrongKey || st.result == SyncResult.Refused || st.result == SyncResult.NotFound) {
                item {
                    Setting("Join again from another screen", "For a new key or a new token", kidsLocked) {
                        gate.run("Parent PIN to join sync") { joinSync = true }
                    }
                }
            }
            item {
                Setting("Add a screen", "Another TV or phone joins this household", kidsLocked) {
                    gate.run("Parent PIN to add a screen") { addScreen = true }
                }
            }
            if (st.screens.any { it.id != store.deviceId }) {
                item {
                    Setting("Remove a screen", "Takes it off the list", kidsLocked) {
                        gate.run("Parent PIN to remove a screen") { removeScreen = true }
                    }
                }
            }
            item {
                Setting("New household key", "Locks out a lost or sold screen", kidsLocked, danger = true) {
                    gate.run("Parent PIN to make a new household key") { confirmNewKey = true }
                }
            }
            syncNote?.let { n -> item { Info("", n) } }
            item {
                Setting("Stop syncing on this screen", "Keeps everything here", kidsLocked, danger = true) {
                    gate.run("Parent PIN to stop syncing") { confirmStopSync = true }
                }
            }
        }

        item { Section("Library") }
        item {
            Setting("My languages", app.session.langOrder(s).joinToString(" · ") { Langs.label(it) }, false) { langsOpen = true }
        }
        item {
            val rows = HomeLayout.resolve(current?.homeRows, app.session.langFeatured(s))
            Setting("Home screen", "${rows.size} rows" + if (current?.homeRows == null) " (default)" else "", false) { homeOpen = true }
        }
        item {
            Setting("Manage categories", if (s.hiddenCats.isEmpty()) "Hide, show or pin categories" else "${s.hiddenCats.size} hidden", kidsLocked) {
                catsOpen = true
            }
        }
        item {
            Setting(
                "Start screen",
                when (s.startScreen) {
                    "live" -> "Live TV"
                    "guide" -> "Live TV guide"
                    else -> "Home"
                },
                false,
            ) {
                store.settings = s.copy(
                    startScreen = when (s.startScreen) {
                        "home" -> "live"
                        "live" -> "guide"
                        else -> "home"
                    },
                )
            }
        }
        item {
            Setting("Set up my library again", "Languages and Live TV countries", kidsLocked) { nav.push(Route.Setup) }
        }
        item {
            Setting("TMDB API key (optional)", tmdbStatus ?: if (s.tmdbKey.isBlank()) "Not set" else "Set", kidsLocked) {
                keyChoice = Edit.TMDB
            }
        }
        item {
            Setting(
                "Rotten Tomatoes scores (MDBList key)",
                ratingsStatus ?: if (s.ratingsKey.isBlank()) "Not set" else "Set · ${app.ratings.cache.usedToday} lookups today",
                kidsLocked,
            ) { keyChoice = Edit.RATINGS }
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
        store.profiles.filter { it.kids }.forEach { p ->
            item {
                // On the kids profile itself this needs a parent PIN to exist, or a child could change it.
                Setting(
                    "Viewing time for ${p.name}",
                    KidsLimits.describe(p.dailyLimitMin, p.bedtime, p.wakeTime),
                    kidsLocked && !store.hasParentPin,
                ) { gate.run("Parent PIN for viewing time") { limitsFor = p } }
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
                if (on) "On until ${timeFmt.format(Date(until))} · ${AppLog.diskBytes() / 1024} KB"
                else "Off · important lines are always kept (${AppLog.diskBytes() / 1024} KB)",
                false,
            ) {
                val next = if (on) 0L else System.currentTimeMillis() + AppLog.SAVE_WINDOW_MS
                AppLog.saveUntil = next
                store.settings = s.copy(logUntil = next)
                AppLog.i("App", if (next > 0) "Detailed logging on for 24h" else "Detailed logging off")
            }
        }
        item { Setting("View recent activity", "${AppLog.recent().size} lines", false) { viewingLog = true } }
        item { Setting("Library and language report", "What Relay recognised in your playlist", false) { reportOpen = true } }
        item {
            val crash = AppLog.lastCrash()
            val how = if (rememberIsTv()) "On a TV: Backup & phone → Phone companion → Download logs" else ""
            Setting("Share logs", listOfNotNull(crash?.let { "Includes crash from ${timeFmt.format(Date(it))}" }, how.ifEmpty { null }).joinToString(" · "), false) {
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
                (AppLog.directory?.absolutePath ?: "unavailable") + "  (max ~1 MB; detail lines auto-off after 24 h)",
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
    if (syncSetup) {
        CompanionDialog(
            onDismiss = { syncSetup = false },
            onSignedIn = { app.appScope.launch { app.catalog.load() } },
            title = "Turn on sync from your phone",
            subtitle = "Your phone must be on the same Wi-Fi. Open the page, find \"Sync between screens\", paste your private GitHub " +
                "repository and its token, and press Turn on sync. This window closes when sync is on.",
            onSync = { ok, _ -> if (ok) syncSetup = false },
        )
    }
    if (joinSync) {
        JoinSyncDialog(onDismiss = { joinSync = false }, onJoined = { joinSync = false; app.appScope.launch { app.catalog.load() } })
    }
    if (addScreen) AddScreenDialog(onDismiss = { addScreen = false })
    if (removeScreen) {
        RemoveScreenDialog(
            screens = app.sync.status.screens.filter { it.id != store.deviceId },
            onDismiss = { removeScreen = false },
            onPick = { sc ->
                removeScreen = false
                app.appScope.launch {
                    syncNote = if (app.sync.removeScreen(sc.id)) "${sc.name} was taken off the list." else "Couldn't reach the sync file. Try again in a moment."
                }
            },
        )
    }
    if (confirmNewKey) {
        ConfirmDialog(
            "Make a new household key?",
            "The sync file is sealed again with a new key, so a lost or sold screen that still has the old key can no longer open it. " +
                "Every other screen, including the ones you still use, stops syncing until you add it again (Settings, Sync between screens, Add a screen). " +
                "To keep a lost screen out for good, also make a new GitHub token and delete the old one in GitHub, because the old token " +
                "can still change the file even though it can't read it.",
            "Make a new key",
            onDismiss = { confirmNewKey = false },
            onConfirm = {
                confirmNewKey = false
                app.appScope.launch {
                    syncNote = app.sync.newHouseholdKey() ?: "New key made. Add your other screens again with Add a screen."
                }
            },
        )
    }
    if (confirmRestore) {
        ConfirmDialog(
            "Restore while syncing?",
            "What you restore will spread to your other screens, because sync treats it as a change made here. " +
                "To restore on this screen only, stop syncing first, restore, and join again afterwards.",
            "Choose a file",
            onDismiss = { confirmRestore = false },
            onConfirm = {
                confirmRestore = false
                try {
                    openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } catch (e: Exception) {
                    backupNote = "No file picker on this device. Use Phone companion instead."
                }
            },
        )
    }
    if (confirmStopSync) {
        ConfirmDialog(
            "Stop syncing on this screen?",
            "Everything stays on this screen. It forgets the repository, the token and the key, and leaves the list of screens. " +
                "The other screens keep syncing with each other.",
            "Stop syncing",
            onDismiss = { confirmStopSync = false },
            onConfirm = {
                confirmStopSync = false
                app.appScope.launch { app.sync.stop() }
            },
        )
    }
    keyChoice?.let { which ->
        KeySourceDialog(
            title = if (which == Edit.TMDB) "TMDB API key" else "MDBList API key",
            onPhone = { keyChoice = null; keysFromPhone = which },
            onType = { keyChoice = null; edit = which },
            onDismiss = { keyChoice = null },
        )
    }
    keysFromPhone?.let {
        CompanionDialog(
            onDismiss = { keysFromPhone = null },
            onSignedIn = { app.appScope.launch { app.catalog.load() } },
            title = "Send API keys from your phone",
            subtitle = "Your phone must be on the same Wi-Fi. Open the page, paste the key under \"Keys for scores and artwork\" " +
                "and press Save keys. The key is checked before it is saved, and this window closes when it arrives.",
            onKeys = { keysFromPhone = null },
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
            onSave = { typed ->
                val k = KeyText.clean(typed)
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
            onSave = { typed ->
                val k = KeyText.clean(typed)
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
            "Audio language order", s.audioLangs, { edit = null },
            onSave = { store.settings = s.copy(audioLangs = it.lowercase().trim()); edit = null },
            hint = "Codes in order, e.g. en, hi, bn. On films with several audio tracks the first one available plays. Leave empty to follow your language order.",
        )
        Edit.SUBS -> TextEditDialog(
            "Subtitle languages", s.subtitleLang, { edit = null },
            onSave = { store.settings = s.copy(subtitleLang = it.lowercase()); edit = null },
            hint = "Codes in order, e.g. en, bn, to turn subtitles on automatically. Leave empty for off.",
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
    if (reportOpen) LibraryReportDialog(onDismiss = { reportOpen = false })
    if (langsOpen) MyLanguagesDialog(onDismiss = { langsOpen = false })
    if (homeOpen) HomeLayoutDialog(onDismiss = { homeOpen = false })
    if (catsOpen) CategoryManagerDialog(onDismiss = { catsOpen = false })
    limitsFor?.let { p -> KidsLimitsDialog(p.id, onDismiss = { limitsFor = null }) }
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
            "This removes your login, saved lists and cache from this TV. Profiles and history stay." +
                if (app.sync.enabled) " It also stops syncing on this screen, otherwise the login would come back from the other screens." else "",
            "Sign out",
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                if (app.sync.enabled) app.appScope.launch { app.sync.stop() }
                store.account = null
                store.activeHost = null
                app.api.account = null
                app.api.host = null
                app.catalog.clear()
                app.appScope.launch(Dispatchers.IO) { WatchNext.clear(ctx.applicationContext) }
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
        color = Palette.accent, fontSize = Type.caption, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, Modifier.width(260.dp), color = Palette.dim, fontSize = Type.body)
        Text(value, color = Palette.text, fontSize = Type.body)
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

/** Asks how a key will arrive: typed on the TV, or pasted on a phone (much easier). */
@Composable
private fun KeySourceDialog(title: String, onPhone: () -> Unit, onType: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss, width = 520.dp) {
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Typing a key with the remote is slow. Scan a code with your phone and paste it there instead.",
            fontSize = Type.small, color = Palette.dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Send from my phone", onPhone, primary = true, focusRequester = first)
            PillButton("Type it here", onType)
        }
        PillButton("Cancel", onDismiss)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    TvDialog(onDismiss) {
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(body, color = Palette.dim, fontSize = Type.body)
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
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(hint, fontSize = Type.small, color = Palette.dim)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("−", onMinus, focusRequester = first)
            PillButton("+", onPlus)
            PillButton("Reset", onReset)
            PillButton("Done", onDismiss, primary = true)
        }
    }
}

/** Daily viewing time and bedtime for a kids profile, plus extra time for today. */
@Composable
private fun KidsLimitsDialog(pid: String, onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    store.version.intValue
    val p = store.profiles.firstOrNull { it.id == pid } ?: return
    val save: (Profile) -> Unit = { updated ->
        store.profiles = store.profiles.map { if (it.id == pid) updated else it }
        if (app.session.profile?.id == pid) app.session.profile = updated
        AppLog.i("Access", "Viewing time for ${updated.name}: ${KidsLimits.describe(updated.dailyLimitMin, updated.bedtime, updated.wakeTime)}")
    }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val usedMin = store.usedToday(pid) / 60_000
    val extraLeft = ((store.allowUntil(pid) - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    TvDialog(onDismiss, width = 600.dp) {
        Text("Viewing time for ${p.name}", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Watched today: ${KidsLimits.label(usedMin.toInt()).let { if (it == "Off") "nothing yet" else it }}" +
                if (extraLeft > 0) " · extra time: $extraLeft min left" else "",
            fontSize = Type.small, color = Palette.dim,
        )
        ListRow(
            "Daily limit", { save(p.copy(dailyLimitMin = KidsLimits.next(KidsLimits.DAILY_CHOICES, p.dailyLimitMin))) },
            key = "kl:daily", trailing = KidsLimits.label(p.dailyLimitMin), focusRequester = first,
        )
        ListRow(
            "Bedtime", { save(p.copy(bedtime = KidsLimits.next(KidsLimits.BEDTIME_CHOICES, p.bedtime))) },
            key = "kl:bed", trailing = p.bedtime.ifBlank { "Off" },
        )
        if (p.bedtime.isNotBlank()) {
            ListRow(
                "Watching opens again at", { save(p.copy(wakeTime = KidsLimits.next(KidsLimits.WAKE_CHOICES, p.wakeTime.ifBlank { KidsLimits.DEFAULT_WAKE }))) },
                key = "kl:wake", trailing = p.wakeTime.ifBlank { KidsLimits.DEFAULT_WAKE },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Done", onDismiss, primary = true)
            PillButton("Allow ${KidsLimits.EXTRA_MINUTES} more minutes now", {
                store.allowExtra(pid, KidsLimits.EXTRA_MINUTES)
                AppLog.i("Access", "Extra ${KidsLimits.EXTRA_MINUTES} minutes allowed for ${p.name}")
            })
        }
        Text("Only time spent playing counts. Grown-up profiles have no limits.", fontSize = Type.caption, color = Palette.dim)
    }
}
