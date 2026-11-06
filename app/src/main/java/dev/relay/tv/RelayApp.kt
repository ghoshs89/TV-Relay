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
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.Store
import dev.relay.tv.data.Tmdb
import dev.relay.tv.data.XtreamApi
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

    val session = Session()

    /** Outlives any screen; used for work that must not be cancelled by navigation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var ui by mutableStateOf(UiState())

    /** Handed to PlayerActivity in memory; channel lists can be too big for an Intent. */
    var pendingPlay: PlayRequest? = null

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
            listOfNotNull(acc?.username, acc?.password, BuildConfig.UPDATE_TOKEN.ifBlank { null }, store.settings.tmdbKey.ifBlank { null })
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
        tmdb = Tmdb(http)
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
