package dev.relay.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Catalog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.StallWatch
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.Ratings
import dev.relay.tv.data.Store
import dev.relay.tv.data.Tmdb
import dev.relay.tv.data.XtreamApi
import dev.relay.tv.data.XmltvGuide
import dev.relay.tv.ui.Palette
import dev.relay.tv.ui.ReminderCenter
import dev.relay.tv.ui.Session
import dev.relay.tv.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RelayApp : Application(), ImageLoaderFactory {
    lateinit var http: OkHttpClient
    lateinit var store: Store
    lateinit var api: XtreamApi
    lateinit var catalog: Catalog
    lateinit var tmdb: Tmdb
    lateinit var ratings: Ratings
    lateinit var sync: dev.relay.tv.sync.SyncRunner

    val session = Session()

    /** Outlives any screen; used for work that must not be cancelled by navigation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var ui by mutableStateOf(UiState())

    /** Handed to PlayerActivity in memory; channel lists can be too big for an Intent. */
    var pendingPlay: PlayRequest? = null

    /** A relay://resume link from the home screen: (profile id, history key), handled by Home. */
    var pendingResume by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        store = Store(this)
        registerActivityLifecycleCallbacks(dev.relay.tv.data.FlushOnStop(store))
        AppLog.secrets = {
            val acc = store.account
            val s = store.settings
            listOfNotNull(
                acc?.username, acc?.password, BuildConfig.UPDATE_TOKEN.ifBlank { null },
                s.tmdbKey.ifBlank { null }, s.ratingsKey.ifBlank { null },
            ) + (if (::sync.isInitialized) sync.settings.secrets() else emptyList())
        }
        AppLog.saveUntil = store.settings.logUntil
        AppLog.init(this)
        api = XtreamApi(http) { store.settings.userAgent.ifBlank { "RelayTV/1.0" } }
        api.account = store.account
        api.host = store.activeHost
        api.onHostChanged = { h ->
            store.activeHost = h
            AppLog.i("Api", "Now using server $h")
        }
        catalog = Catalog(this, api)
        catalog.custom = { store.channelCustom }
        catalog.appScope = appScope
        catalog.preferredVersions = { store.settings.preferredVersions }
        catalog.guideSearchScope = {
            val s = store.settings
            val featured = session.langFeatured(s).toSet()
            val favs = store.favs(session.profileId).filter { it.kind == "live" }.map { it.id }.toSet()
            val hidden = s.hiddenCats
            val pick: (Channel) -> Boolean = { ch ->
                ch.id in favs || (catalog.channelLang(ch.id) in featured && "live:${ch.categoryId}" !in hidden)
            }
            pick
        }
        catalog.settings = { store.settings }
        catalog.guide = XmltvGuide(this, http)
        Palette.neutral = store.settings.neutralTheme
        ReminderCenter.start(appScope)
        sync = dev.relay.tv.sync.SyncRunner(this)
        tmdb = Tmdb(http)
        ratings = Ratings(http, java.io.File(filesDir, "ratings.json"))
        AppLog.stateDump = { stateDump() }
        AppLog.i("App", deviceLine())
        StallWatch.start()
        sync.start()
        if (BuildConfig.BENCH) {
            // The benchmark build ships dev.relay.tv.bench.BenchSetup (src/benchmark); it is looked up by
            // name so the release build, which has no such class, compiles and never reaches this.
            runCatching {
                Class.forName("dev.relay.tv.bench.BenchSetup").getMethod("run", RelayApp::class.java).invoke(null, this)
            }.onFailure { AppLog.e("Bench", "Setup failed", it) }
        }
    }

    /** Memory and processor facts that explain why a TV behaves differently from a phone. */
    private fun deviceLine(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = resources.displayMetrics
        return "Device: ${Runtime.getRuntime().availableProcessors()} cores, RAM ${mi.totalMem / 1_048_576} MB " +
            "(${mi.availMem / 1_048_576} MB free${if (am.isLowRamDevice) ", low-RAM device" else ""}), " +
            "app memory limit ${Runtime.getRuntime().maxMemory() / 1_048_576} MB, " +
            "screen ${dm.widthPixels}x${dm.heightPixels} at ${dm.densityDpi} dpi, " +
            (if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) "TV" else "phone or tablet")
    }

    /** Added to shared logs. No logins, keys or PINs. */
    private fun stateDump(): String = buildString {
        appendLine(deviceLine())
        appendLine(catalog.loadSummary())
        appendLine("Loading now: ${if (catalog.loading) catalog.progress?.text ?: "yes" else "no"}; last error: ${catalog.error ?: "none"}")
        appendLine("Library saved: ${if (catalog.updatedAt > 0) java.util.Date(catalog.updatedAt) else "never"}")
        appendLine("Library snapshot: ${catalog.snapshotStatus}")
        appendLine("Channels with several versions: ${catalog.versionGroupCount}")
        val s = store.settings
        appendLine(
            "Settings: languages ${session.langOrder(s).joinToString(",")}, show each channel once ${s.mergeVariants}, " +
                "whole-guide search ${s.fullGuideSearch}, external guide ${if (s.xmltvUrl.isBlank()) "no" else "yes"}, " +
                "live format ${s.liveExt}, hidden categories ${s.hiddenCats.size}, setup done ${s.setupDone}",
        )
        appendLine("Profiles: ${store.profiles.size} (${store.profiles.count { it.kids }} kids); current ${if (session.profile?.kids == true) "kids" else "grown-up"}")
        appendLine("Servers saved: ${store.account?.hosts?.size ?: 0}; in use ${api.host ?: "none"}")
    }

    /**
     * Images load through their own request queue (8 at a time, 4 per server) built from the same
     * client, so connections, timeouts and the user agent are shared but a screenful of posters can't
     * crowd out the playlist and guide requests, or open dozens of connections to one provider.
     * No fade-in by default: a fade per poster costs frames while scrolling; the large backdrops ask for one.
     */
    override fun newImageLoader(): ImageLoader {
        val imageHttp = http.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(imageHttp)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
            .crossfade(false)
            .build()
    }

    companion object {
        lateinit var instance: RelayApp
            private set
    }
}
