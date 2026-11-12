package dev.relay.tv.sync

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.SyncApplied
import dev.relay.tv.data.SyncSeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides when to sync and keeps the result for Settings. Syncing is off until it is set up from the
 * phone page (or by joining from another screen), so a fresh install changes nothing. One run at a time;
 * runs happen on a background thread at low priority; a slow TV that is still reading its library waits.
 */
class SyncRunner(private val app: RelayApp) : Application.ActivityLifecycleCallbacks {
    val settings = SyncSettings(app)

    class Status(
        val result: SyncResult? = null,
        val message: String = "",
        val lastOk: Long = 0L,
        val running: Boolean = false,
        val screens: List<ScreenInfo> = emptyList(),
        /** What the last run changed, in words. */
        val note: String = "",
    )

    var status by mutableStateOf(Status(lastOk = settings.stateStore.load().lastOk))
        private set

    val enabled: Boolean get() = settings.enabled

    private var running = false
    private var rerun = false
    private var failures = 0
    private var lastFailAt = 0L
    private var lastEnd = 0L
    private var foreground = 0
    private var debounce: Job? = null
    private var retry: Job? = null

    /** Called once from [RelayApp.onCreate]. Nothing happens unless sync was set up. */
    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        app.store.onSyncChange = { changed() }
        app.appScope.launch {
            delay(SyncSchedule.START_DELAY_MS)
            request(manual = false)
            while (true) {
                delay(SyncSchedule.PERIODIC_MS)
                if (foreground > 0) request(manual = false)
            }
        }
    }

    /** Something that syncs was changed here: sync a little later, once for a burst of changes. */
    fun changed() {
        if (!enabled) return
        debounce?.cancel()
        debounce = app.appScope.launch {
            delay(SyncSchedule.DEBOUNCE_MS)
            request(manual = false)
        }
    }

    /** The player closed: its progress and kids viewing time go out now, not every ten seconds. */
    fun afterPlayback() {
        if (!enabled) return
        app.appScope.launch {
            delay(2_000)
            request(manual = false)
        }
    }

    /** "Sync now" in Settings. */
    fun runNow() = request(manual = true)

    private fun request(manual: Boolean) {
        val cfg = settings.config ?: return
        val now = System.currentTimeMillis()
        if (!manual && now - lastEnd < MIN_GAP_MS) {
            if (running) rerun = true
            return
        }
        when (SyncSchedule.decide(now, running, app.catalog.loading, failures, lastFailAt, manual)) {
            SyncSchedule.Decision.Busy -> {
                rerun = true
                return
            }
            SyncSchedule.Decision.Parsing -> {
                retry?.cancel()
                retry = app.appScope.launch {
                    delay(PARSING_RETRY_MS)
                    request(manual = false)
                }
                return
            }
            SyncSchedule.Decision.Backoff -> return
            SyncSchedule.Decision.Run -> Unit
        }
        running = true
        status = Status(status.result, status.message, status.lastOk, running = true, screens = status.screens, note = status.note)
        app.appScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val outcome = withContext(Dispatchers.IO) { execute(cfg) }
            finish(outcome, SystemClock.elapsedRealtime() - t0)
        }
    }

    private fun execute(cfg: SyncSettings.Config): SyncOutcome {
        val tid = Process.myTid()
        val before = Process.getThreadPriority(tid)
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val local = StoreSyncLocal(app, screenName())
            val engine = SyncEngine(local, GitHubTransport(app.http, cfg.repo, cfg.token), cfg.key, settings.stateStore)
            return engine.run()
        } catch (e: Exception) {
            AppLog.w("Sync", "Sync stopped by an error", e)
            return SyncOutcome(SyncResult.Problem, "Sync stopped by an unexpected error (${e.javaClass.simpleName}).")
        } finally {
            Process.setThreadPriority(tid, before)
        }
    }

    private fun finish(outcome: SyncOutcome, ms: Long) {
        running = false
        val t = System.currentTimeMillis()
        lastEnd = t
        if (outcome.ok) {
            failures = 0
        } else if (SyncSchedule.failure(outcome.result)) {
            failures++
            lastFailAt = t
        }
        val note = listOf(
            outcome.pulled.takeIf { it.isNotEmpty() }?.let { "Received $it" },
            outcome.pushed.takeIf { it.isNotEmpty() }?.let { "Sent $it" },
        ).filterNotNull().joinToString(". ")
        status = Status(
            result = outcome.result, message = outcome.message,
            lastOk = if (outcome.ok) t else status.lastOk,
            screens = outcome.screens.ifEmpty { status.screens },
            note = note,
        )
        val size = if (outcome.bytes > 0) ", wrote ${outcome.bytes / 1024} KB" else ""
        when {
            outcome.result == SyncResult.UpToDate -> AppLog.d("Sync", "Up to date ($ms ms)")
            outcome.ok -> AppLog.i("Sync", "${outcome.result} in $ms ms$size${if (note.isNotEmpty()) ". $note" else ""}")
            else -> AppLog.w("Sync", "${outcome.result} after $ms ms: ${outcome.message}")
        }
        outcome.applied?.let { afterApply(it) }
        if (rerun) {
            rerun = false
            changed()
        }
    }

    /** What other screens' changes mean for what is on this one. */
    private fun afterApply(a: SyncApplied) {
        if (a.accountChanged) {
            AppLog.i("Sync", "The login came from another screen; loading the library")
            app.api.account = app.store.account
            app.api.host = null
            app.store.activeHost = null
            app.catalog.clear()
            app.appScope.launch { app.catalog.load() }
        }
        if (a.profilesChanged) {
            val cur = app.session.profile
            if (cur != null) {
                app.store.profiles.firstOrNull { it.id == cur.id }?.let { if (it != cur) app.session.profile = it }
            }
        }
        if (a.libraryChanged && !a.accountChanged) app.catalog.applyCustom()
    }

    // ---------- set-up and stopping ----------

    /**
     * Turns sync on from this screen as the first one: checks the repository and token, makes the
     * household key, and does the first sync. Returns a sentence for the viewer if it did not work, null if it did.
     */
    suspend fun setUp(repoText: String, tokenText: String): String? = withContext(Dispatchers.IO) {
        SyncInput.problem(repoText, tokenText)?.let { return@withContext it }
        val repo = Repo.parse(repoText)!!
        val token = SyncInput.token(tokenText)!!
        val transport = GitHubTransport(app.http, repo, token)
        transport.check()?.let { return@withContext it }
        val existing = try {
            transport.read()
        } catch (e: TransportException) {
            return@withContext e.message
        }
        if (existing != null) {
            return@withContext "That repository already holds a Relay sync file. To add this screen to it, use Join in Settings, Sync, on this screen, " +
                "and Add a screen on a screen that already syncs."
        }
        val cfg = SyncSettings.Config(repo, token, SyncSeal.newKey())
        settings.clear()
        settings.config = cfg
        val t0 = SystemClock.elapsedRealtime()
        val outcome = execute(cfg)
        if (!outcome.ok) {
            settings.clear()
            return@withContext outcome.message.ifBlank { "The first sync did not work." }
        }
        withContext(Dispatchers.Main) {
            failures = 0
            finish(outcome, SystemClock.elapsedRealtime() - t0)
        }
        AppLog.i("Sync", "Sync is on (first screen)")
        null
    }

    /** A new token for the repository this screen already syncs with (the old one expired). */
    suspend fun replaceToken(tokenText: String): String? = withContext(Dispatchers.IO) {
        val cfg = settings.config ?: return@withContext "Sync is not set up on this screen."
        val token = SyncInput.token(tokenText)
            ?: return@withContext "That doesn't look like a GitHub token. Copy the whole token, it starts with github_pat_."
        GitHubTransport(app.http, cfg.repo, token).check()?.let { return@withContext it }
        settings.replaceToken(token)
        withContext(Dispatchers.Main) {
            failures = 0
            request(manual = true)
        }
        null
    }

    /**
     * Stops syncing on this screen: takes it off the list in the file (best effort), then forgets the
     * repository, token, key and what was agreed. The data on this screen stays as it is.
     */
    suspend fun stop() {
        val cfg = settings.config
        if (cfg != null) {
            withContext(Dispatchers.IO) {
                try {
                    val local = StoreSyncLocal(app, screenName())
                    SyncEngine(local, GitHubTransport(app.http, cfg.repo, cfg.token), cfg.key, settings.stateStore).forget(app.store.deviceId)
                } catch (e: Exception) {
                    AppLog.w("Sync", "Could not take this screen off the list", e)
                }
            }
        }
        settings.clear()
        debounce?.cancel()
        retry?.cancel()
        failures = 0
        rerun = false
        status = Status()
        AppLog.i("Sync", "Sync stopped on this screen")
    }

    // ---------- adding, removing and locking out screens ----------

    /** What another screen needs to join this household, or null when sync is off here. */
    fun pairBundle(): PairBundle? = settings.config?.let { PairBundle(it.repo.toString(), it.token, SyncSeal.keyToText(it.key)) }

    /**
     * Joins the household in [b]: checks the repository, takes its token and key, and does the first
     * sync, which brings this screen's data together with the others'. If it does not work, what this
     * screen had before (sync on or off) is put back. Returns a sentence for the viewer on failure.
     */
    suspend fun join(b: PairBundle): String? = withContext(Dispatchers.IO) {
        val repo = Repo.parse(b.repo) ?: return@withContext "The pairing details are not valid."
        val token = SyncInput.token(b.token) ?: return@withContext "The pairing details are not valid."
        val key = SyncSeal.keyFromText(b.key) ?: return@withContext "The pairing details are not valid."
        GitHubTransport(app.http, repo, token).check()?.let { return@withContext it }
        val previous = settings.config
        val previousState = settings.stateStore.load()
        val cfg = SyncSettings.Config(repo, token, key)
        settings.config = cfg
        settings.stateStore.save(SyncState())
        val t0 = SystemClock.elapsedRealtime()
        val outcome = execute(cfg)
        if (!outcome.ok) {
            if (previous != null) {
                settings.config = previous
                settings.stateStore.save(previousState)
            } else {
                settings.clear()
            }
            return@withContext outcome.message.ifBlank { "Joining did not work." }
        }
        withContext(Dispatchers.Main) {
            failures = 0
            finish(outcome, SystemClock.elapsedRealtime() - t0)
        }
        AppLog.i("Sync", "Joined sync from another screen")
        null
    }

    /**
     * Seals the sync file again with a new household key, so a screen that still has the old one (a lost
     * one) can no longer open it. The other screens must be added again.
     */
    suspend fun newHouseholdKey(): String? = withContext(Dispatchers.IO) {
        val cfg = settings.config ?: return@withContext "Sync is not set up on this screen."
        val newKey = SyncSeal.newKey()
        val local = StoreSyncLocal(app, screenName())
        val ok = SyncEngine(local, GitHubTransport(app.http, cfg.repo, cfg.token), cfg.key, settings.stateStore).rekey(newKey)
        if (!ok) return@withContext "The sync file could not be sealed again. Check the connection and try again."
        settings.config = SyncSettings.Config(cfg.repo, cfg.token, newKey)
        AppLog.i("Sync", "New household key made; other screens must be added again")
        withContext(Dispatchers.Main) { request(manual = true) }
        null
    }

    /** Takes another screen off the list. Cosmetic: a screen that still has the key puts itself back when it syncs. */
    suspend fun removeScreen(id: String): Boolean {
        val cfg = settings.config ?: return false
        val done = withContext(Dispatchers.IO) {
            val local = StoreSyncLocal(app, screenName())
            SyncEngine(local, GitHubTransport(app.http, cfg.repo, cfg.token), cfg.key, settings.stateStore).forget(id)
        }
        if (done) request(manual = true)
        return done
    }

    /** Name shown to the other screens. */
    fun screenName(): String {
        val model = Build.MODEL.orEmpty().trim().ifEmpty { "Android device" }
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val base = if (maker.isNotEmpty() && !model.startsWith(maker, ignoreCase = true)) "${maker.replaceFirstChar { it.uppercase() }} $model" else model
        return "$base (${app.store.deviceId.take(4)})"
    }

    // ---------- when the app is in front ----------

    override fun onActivityStarted(activity: Activity) {
        foreground++
        if (foreground == 1 && enabled) {
            app.appScope.launch {
                delay(1_500)
                request(manual = false)
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        foreground = (foreground - 1).coerceAtLeast(0)
        // Best effort: the process may be stopped before this finishes, and the next start picks up.
        if (foreground == 0 && enabled) request(manual = false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        /** Runs closer together than this are merged into one. */
        private const val MIN_GAP_MS = 10_000L
        private const val PARSING_RETRY_MS = 60_000L
    }
}
