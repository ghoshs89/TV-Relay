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
        AppLog.secrets = {
            val acc = store.account
            val s = store.settings
            listOfNotNull(
                acc?.username, acc?.password, BuildConfig.UPDATE_TOKEN.ifBlank { null },
                s.tmdbKey.ifBlank { null }, s.ratingsKey.ifBlank { null },
            )
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
        tmdb = Tmdb(http)
        ratings = Ratings(http, java.io.File(filesDir, "ratings.json"))
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

    companion object {
        lateinit var instance: RelayApp
            private set
    }
}
