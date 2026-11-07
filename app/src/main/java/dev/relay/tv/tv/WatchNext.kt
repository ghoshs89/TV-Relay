package dev.relay.tv.tv

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.WatchEntry

/**
 * Movies and episodes in progress appear in "Continue watching" on the Google TV / Android TV
 * home screen. Only for grown-up profiles, never for adult categories, and only on TVs.
 * Choosing one opens Relay at relay://resume, which resumes it on the same profile.
 */
object WatchNext {
    private const val PREFS = "watchnext"
    const val SCHEME = "relay"
    const val HOST = "resume"

    fun supported(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Adds, updates or removes the home-screen entry for [e]. Call off the main thread. */
    fun update(ctx: Context, pid: String, e: WatchEntry) {
        if (!supported(ctx)) return
        val app = RelayApp.instance
        if (!app.store.settings.watchNext) return
        val profile = app.store.profiles.firstOrNull { it.id == pid } ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$pid|${e.key}"
        val existing = prefs.getLong(key, -1L)
        val probe = PlayItem(e.key, e.kind, e.title, streamId = e.streamId, ext = e.ext, seriesId = e.seriesId)
        val show = !profile.kids && app.catalog.restrictions(true).allows(probe) &&
            e.duration > 0 && e.position >= 60_000 && e.position < e.duration * 95 / 100
        try {
            if (!show) {
                if (existing >= 0) {
                    ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null)
                    prefs.edit().remove(key).apply()
                }
                return
            }
            val episode = e.kind == "episode"
            val b = WatchNextProgram.Builder()
            b.setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setLastPlaybackPositionMillis(e.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(e.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setInternalProviderId(key)
                .setIntentUri(
                    Uri.Builder().scheme(SCHEME).authority(HOST)
                        .appendQueryParameter("p", pid).appendQueryParameter("k", e.key).build(),
                )
            b.setType(if (episode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setTitle(e.title)
            if (episode) b.setEpisodeTitle(e.subtitle) else b.setDescription(e.subtitle)
            e.image?.takeIf { it.startsWith("http") }?.let {
                b.setPosterArtUri(Uri.parse(it))
                b.setPosterArtAspectRatio(
                    if (episode) TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9 else TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3,
                )
            }
            val values = b.build().toContentValues()
            if (existing >= 0 && ctx.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null) > 0) return
            val uri = ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: return
            prefs.edit().putLong(key, ContentUris.parseId(uri)).apply()
        } catch (ex: Exception) {
            // Some launchers don't offer Watch Next; never let that disturb playback.
            AppLog.w("WatchNext", "Home screen entry not updated", ex)
        }
    }

    /** Removes every entry Relay added, e.g. when the setting is turned off or on sign-out. */
    fun clear(ctx: Context) {
        if (!supported(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            prefs.all.values.filterIsInstance<Long>().forEach {
                ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null)
            }
        } catch (ex: Exception) {
            AppLog.w("WatchNext", "Couldn't clear home screen entries", ex)
        }
        prefs.edit().clear().apply()
    }

    /** (profile id, history key) from a relay://resume link, or null. */
    fun parse(uri: Uri?): Pair<String, String>? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val p = uri.getQueryParameter("p") ?: return null
        val k = uri.getQueryParameter("k") ?: return null
        return p to k
    }
}
