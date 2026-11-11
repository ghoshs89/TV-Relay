package dev.relay.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.relay.tv.BuildConfig
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Jsons
import dev.relay.tv.data.arr
import dev.relay.tv.data.at
import dev.relay.tv.data.long
import dev.relay.tv.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Disabled(val reason: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Self-updater. CI publishes each signed build as a GitHub Release; Relay polls the
 * latest release, downloads the APK and hands it to Android's PackageInstaller.
 * Android installs it only if it's signed with the same key as the installed app.
 */
object Updater {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    var dialogOpen by mutableStateOf(false)
    var announcedCode = 0

    private const val API = "https://api.github.com"
    val enabled: Boolean get() = BuildConfig.UPDATE_TOKEN.isNotBlank()

    val busy: Boolean
        get() = state is UpdateState.Downloading || state is UpdateState.Installing

    val pending: UpdateInfo?
        get() = when (val s = state) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            is UpdateState.Downloading -> s.info
            is UpdateState.Installing -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        }

    private fun request(url: String, accept: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "RelayTV-Updater")
        .build()

    /** Checks GitHub. Quiet mode keeps an earlier "available" result if the network blips. */
    suspend fun check(http: OkHttpClient, quiet: Boolean = false) {
        if (!enabled) {
            state = UpdateState.Disabled("This build has no update token. Add the RELAY_UPDATE_TOKEN secret on GitHub and install the next build manually once.")
            return
        }
        if (busy) return
        val previous = state
        if (!quiet) state = UpdateState.Checking
        state = try {
            val info = latest(http)
            AppLog.i("Update", "Latest release ${info?.versionName ?: "none"}, installed ${BuildConfig.VERSION_NAME}")
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Update", "Check failed", e)
            if (quiet) previous else UpdateState.Failed(e.message ?: "Update check failed", null)
        }
    }

    private suspend fun latest(http: OkHttpClient): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = "$API/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
        http.newCall(request(url, "application/vnd.github+json")).execute().use { r ->
            when (r.code) {
                401, 403 -> throw IOException("GitHub rejected the update token (HTTP ${r.code}). It may have expired.")
                404 -> throw IOException("No release found, or the update token can't see ${BuildConfig.UPDATE_REPO}.")
            }
            if (!r.isSuccessful) throw IOException("GitHub returned HTTP ${r.code}")
            val j = Jsons.json.parseToJsonElement(r.body!!.string())
            val tag = j.at("tag_name").str() ?: return@withContext null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            val asset = j.at("assets").arr()
                ?.firstOrNull { it.at("name").str()?.endsWith(".apk") == true }
                ?: return@withContext null
            UpdateInfo(
                versionCode = code,
                versionName = tag.removePrefix("v"),
                notes = j.at("body").str().orEmpty().trim(),
                assetUrl = asset.at("url").str() ?: return@withContext null,
                size = asset.at("size").long() ?: 0L,
            )
        }
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" screen for Relay. Returns false if the TV has none. */
    fun openInstallPermission(ctx: Context): Boolean {
        val attempts = listOfNotNull(
            // The per-app screen exists from Android 8; older TVs go straight to Security settings.
            if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")) else null,
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in attempts) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    suspend fun downloadAndInstall(ctx: Context, http: OkHttpClient, info: UpdateInfo) {
        if (busy) return
        if (!canInstall(ctx)) {
            state = UpdateState.NeedsPermission(info)
            return
        }
        try {
            state = UpdateState.Downloading(info, 0f)
            val apk = download(ctx, http, info)
            verify(ctx, apk)
            state = UpdateState.Installing(info)
            withContext(Dispatchers.IO) { install(ctx, apk) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Update", "Update to ${info.versionName} failed", e)
            state = UpdateState.Failed(e.message ?: "Update failed", info)
        }
    }

    private suspend fun download(ctx: Context, http: OkHttpClient, info: UpdateInfo): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply {
                deleteRecursively()
                mkdirs()
            }
            val file = File(dir, "Relay-${info.versionName}.apk")
            // GitHub answers with a redirect to its file host; OkHttp drops the token on the way.
            http.newCall(request(info.assetUrl, "application/octet-stream")).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
                val body = r.body ?: throw IOException("Empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastShown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0 && read - lastShown >= 512 * 1024) {
                                lastShown = read
                                val p = (read.toFloat() / total).coerceIn(0f, 1f)
                                withContext(Dispatchers.Main) { state = UpdateState.Downloading(info, p) }
                            }
                        }
                    }
                }
            }
            file
        }

    private fun verify(ctx: Context, apk: File) {
        val pkg = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("The downloaded file isn't a valid APK.")
        if (pkg.packageName != ctx.packageName) throw IOException("The downloaded APK is for a different app.")
        val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
        if (code <= BuildConfig.VERSION_CODE) throw IOException("The downloaded build isn't newer than this one.")
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // After Relay has installed itself once, Android 12+ can skip the confirm screen.
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("relay.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, InstallReceiver::class.java), flags,
            )
            session.commit(callback.intentSender)
        }
    }
}
