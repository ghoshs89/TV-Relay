package dev.relay.tv.telemetry

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Jsons
import dev.relay.tv.data.at
import dev.relay.tv.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@Serializable
data class TelemetryBucket(
    val day: String,
    val sessions: Int = 0,
    val appSec: Int = 0,
    val liveSec: Int = 0,
    val vodSec: Int = 0,
    val crashes: Int = 0,
)

object TelemetryLogic {
    const val MAX_DAYS = 14
    private const val MAX_SEC_PER_DAY = 86_400

    fun cleanEndpoint(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val url = trimmed.toHttpUrlOrNull() ?: return ""
        return if (url.isHttps) trimmed else ""
    }

    fun isEnabled(endpointUrl: String, optedIn: Boolean, bench: Boolean): Boolean =
        !bench && optedIn && cleanEndpoint(endpointUrl).isNotEmpty()

    fun newInstallId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun merge(
        existing: Map<String, TelemetryBucket>,
        day: String,
        addSessions: Int = 0,
        addAppSec: Int = 0,
        addLiveSec: Int = 0,
        addVodSec: Int = 0,
        addCrashes: Int = 0,
    ): Map<String, TelemetryBucket> {
        val cur = existing[day] ?: TelemetryBucket(day)
        val updated = cur.copy(
            sessions = (cur.sessions + addSessions.coerceAtLeast(0)).coerceAtMost(10_000),
            appSec = (cur.appSec + addAppSec.coerceAtLeast(0)).coerceAtMost(MAX_SEC_PER_DAY),
            liveSec = (cur.liveSec + addLiveSec.coerceAtLeast(0)).coerceAtMost(MAX_SEC_PER_DAY),
            vodSec = (cur.vodSec + addVodSec.coerceAtLeast(0)).coerceAtMost(MAX_SEC_PER_DAY),
            crashes = (cur.crashes + addCrashes.coerceAtLeast(0)).coerceAtMost(1_000),
        )
        val combined = existing + (day to updated)
        if (combined.size <= MAX_DAYS) return combined
        return combined.entries
            .sortedByDescending { it.key }
            .take(MAX_DAYS)
            .associate { it.key to it.value }
    }

    fun heartbeatJson(
        installId: String,
        deviceType: String,
        deviceModel: String,
        androidSdk: Int,
        appVersion: String,
        versionCode: Int,
        days: List<TelemetryBucket>,
    ): String {
        val arr = JsonArray(
            days.sortedBy { it.day }.takeLast(MAX_DAYS).map { d ->
                buildJsonObject {
                    put("day", d.day)
                    put("sessions", d.sessions)
                    put("appSec", d.appSec)
                    put("liveSec", d.liveSec)
                    put("vodSec", d.vodSec)
                    put("crashes", d.crashes)
                }
            },
        )
        return buildJsonObject {
            put("installId", installId)
            put("deviceType", deviceType)
            put("deviceModel", deviceModel.take(80))
            put("androidSdk", androidSdk)
            put("appVersion", appVersion.take(32))
            put("versionCode", versionCode)
            put("days", arr)
        }.toString()
    }
}

/**
 * Privacy-preserving anonymous usage rollup tracker. No secrets live in the APK: payloads contain
 * only a random install ID, device type/model, Relay version, daily open/playback seconds and crash
 * count, never logins, provider addresses, IP addresses or watched titles.
 */
class TelemetryTracker(private val app: RelayApp) : Application.ActivityLifecycleCallbacks {
    private val prefs = app.getSharedPreferences("relay_telemetry", Context.MODE_PRIVATE)
    private val bucketSer = MapSerializer(String.serializer(), TelemetryBucket.serializer())
    private val sendLock = Mutex()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val utcFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private var foreground = 0
    private var foregroundStartMs = 0L
    private var lastFlushAttemptMs = 0L
    private var unsavedPlayTicks = 0

    @Volatile private var bucketsCache: Map<String, TelemetryBucket>? = null

    val configured: Boolean
        get() = !BuildConfig.BENCH && TelemetryLogic.cleanEndpoint(BuildConfig.TELEMETRY_URL).isNotEmpty()

    val installId: String
        get() {
            prefs.getString(KEY_INSTALL_ID, null)?.let { if (it.isNotEmpty()) return it }
            val created = TelemetryLogic.newInstallId()
            prefs.edit().putString(KEY_INSTALL_ID, created).apply()
            return created
        }

    var lastPromptedCrashAt: Long
        get() = prefs.getLong(KEY_PROMPTED_CRASH, 0L)
        set(value) = prefs.edit().putLong(KEY_PROMPTED_CRASH, value).apply()

    private fun today(): String = synchronized(utcFmt) { utcFmt.format(Date()) }

    private fun enabledNow(): Boolean = TelemetryLogic.isEnabled(
        BuildConfig.TELEMETRY_URL,
        app.store.settings.shareUsageStats,
        BuildConfig.BENCH,
    )

    @Synchronized
    private fun buckets(): Map<String, TelemetryBucket> {
        bucketsCache?.let { return it }
        val raw = prefs.getString(KEY_BUCKETS, null)
        val parsed = if (raw.isNullOrBlank()) emptyMap()
        else runCatching { Jsons.json.decodeFromString(bucketSer, raw) }.getOrDefault(emptyMap())
        bucketsCache = parsed
        return parsed
    }

    @Synchronized
    private fun saveBuckets(next: Map<String, TelemetryBucket>, persist: Boolean) {
        bucketsCache = next
        if (persist) {
            prefs.edit().putString(KEY_BUCKETS, Jsons.json.encodeToString(bucketSer, next)).apply()
        }
    }

    fun onOptInChanged(enabled: Boolean) {
        if (!enabled) {
            synchronized(this) {
                bucketsCache = emptyMap()
                prefs.edit().remove(KEY_BUCKETS).apply()
            }
        } else {
            flushAsync()
        }
    }

    fun onPlaybackSecond(live: Boolean) {
        if (!enabledNow()) return
        synchronized(this) {
            val next = TelemetryLogic.merge(
                buckets(),
                today(),
                addLiveSec = if (live) 1 else 0,
                addVodSec = if (!live) 1 else 0,
            )
            unsavedPlayTicks++
            val persist = unsavedPlayTicks >= 30
            if (persist) unsavedPlayTicks = 0
            saveBuckets(next, persist)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushAttemptMs > 15 * 60_000L) {
            flushAsync()
        }
    }

    private fun checkNewCrashLocked() {
        val crashAt = AppLog.lastCrash() ?: return
        val seen = prefs.getLong(KEY_COUNTED_CRASH, 0L)
        if (crashAt > seen) {
            prefs.edit().putLong(KEY_COUNTED_CRASH, crashAt).apply()
            saveBuckets(TelemetryLogic.merge(buckets(), today(), addCrashes = 1), persist = true)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (!enabledNow()) return
        val opening = foreground == 0
        foreground++
        if (opening) {
            foregroundStartMs = SystemClock.elapsedRealtime()
            synchronized(this) {
                checkNewCrashLocked()
                saveBuckets(TelemetryLogic.merge(buckets(), today(), addSessions = 1), persist = true)
            }
            flushAsync()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (foreground > 0) foreground--
        if (foreground == 0 && enabledNow()) {
            val elapsedSec = if (foregroundStartMs > 0L) {
                ((SystemClock.elapsedRealtime() - foregroundStartMs) / 1_000L).toInt().coerceAtLeast(0)
            } else 0
            foregroundStartMs = 0L
            synchronized(this) {
                unsavedPlayTicks = 0
                saveBuckets(TelemetryLogic.merge(buckets(), today(), addAppSec = elapsedSec), persist = true)
            }
            flushAsync()
        }
    }

    fun flushAsync() {
        if (!enabledNow()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushAttemptMs < 30_000L && foreground > 0) return
        lastFlushAttemptMs = now
        app.appScope.launch { flushNow() }
    }

    suspend fun flushNow(): Boolean {
        if (!enabledNow()) return false
        val endpoint = TelemetryLogic.cleanEndpoint(BuildConfig.TELEMETRY_URL).ifEmpty { return false }
        val snap = synchronized(this) {
            checkNewCrashLocked()
            val cur = buckets()
            saveBuckets(cur, persist = true)
            cur.values.toList()
        }
        if (snap.isEmpty()) return true
        val payload = TelemetryLogic.heartbeatJson(
            installId = installId,
            deviceType = if (app.isTv()) "tv" else "phone",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidSdk = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            days = snap,
        )
        return sendLock.withLock {
            try {
                val client = app.http.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("$endpoint/v1/heartbeat")
                        .post(payload.toRequestBody(jsonMedia))
                        .header("User-Agent", "RelayTV-Telemetry")
                        .build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    }
                }
                true
            } catch (e: Exception) {
                AppLog.d("Telemetry", "Heartbeat skipped: ${e.message}")
                false
            }
        }
    }

    override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
    override fun onActivityResumed(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit

    companion object {
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_BUCKETS = "buckets"
        private const val KEY_COUNTED_CRASH = "counted_crash_at"
        private const val KEY_PROMPTED_CRASH = "prompted_crash_at"
    }
}
