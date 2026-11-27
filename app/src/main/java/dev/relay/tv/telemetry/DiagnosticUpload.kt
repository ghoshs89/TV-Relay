package dev.relay.tv.telemetry

import android.os.Build
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Jsons
import dev.relay.tv.data.at
import dev.relay.tv.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object DiagnosticUpload {
    const val MAX_UPLOAD_BYTES = 256 * 1024
    val CATEGORIES = listOf(
        "Stream won't play",
        "App froze or crashed",
        "Guide or library",
        "Sync or Play on TV",
        "Other",
    )

    /**
     * Removes secrets (via [AppLog.redact]) plus the viewer's provider server URLs/hostnames and
     * external guide URL/hostname before leaving the device, then caps total UTF-8 size.
     */
    fun scrubForUpload(
        rawText: String,
        providerHosts: List<String>,
        guideUrl: String = "",
        maxBytes: Int = MAX_UPLOAD_BYTES,
    ): String {
        var text = AppLog.redact(rawText)

        // Replace longer tokens (full URL with scheme/port) before bare hostnames.
        val hostTargets = ArrayList<Pair<String, String>>()
        providerHosts.distinct().forEachIndexed { idx, rawHost ->
            val label = "<provider-server-${idx + 1}>"
            val trimmed = rawHost.trim().trimEnd('/')
            if (trimmed.length >= 4) hostTargets += trimmed to label
            trimmed.toHttpUrlOrNull()?.host?.takeIf { it.length >= 4 }?.let { host ->
                hostTargets += host to label
            }
        }
        val cleanGuide = guideUrl.trim()
        if (cleanGuide.length >= 4) {
            hostTargets += cleanGuide to "<guide-server>"
            cleanGuide.toHttpUrlOrNull()?.host?.takeIf { it.length >= 4 }?.let { host ->
                hostTargets += host to "<guide-server>"
            }
        }
        for ((needle, replacement) in hostTargets.sortedByDescending { it.first.length }) {
            text = text.replace(needle, replacement, ignoreCase = true)
        }

        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text

        // Keep device/crash header plus newest trailing activity within budget.
        val headChars = (maxBytes / 16).coerceAtLeast(2048).coerceAtMost(text.length / 2)
        val tailBudget = (maxBytes - 8192).coerceAtLeast(4096)
        val head = text.take(headChars)
        val tail = text.takeLast(tailBudget)
        val omitted = "\n\n... [earlier lines trimmed for upload size] ...\n\n"
        var combined = head + omitted + tail
        while (combined.toByteArray(Charsets.UTF_8).size > maxBytes && tailBudget > 1024) {
            combined = head + omitted + combined.takeLast((combined.length * 3) / 4)
        }
        return combined
    }

    suspend fun upload(
        app: RelayApp,
        category: String,
        note: String = "",
        markCrashHandled: Boolean = false,
    ): Result<String> = runCatching {
        val endpoint = TelemetryLogic.cleanEndpoint(BuildConfig.TELEMETRY_URL)
        if (endpoint.isEmpty()) throw IOException("Diagnostic upload isn't configured in this build.")
        val logText = withContext(Dispatchers.IO) {
            val file = AppLog.bundle(app)
            val raw = file.readText(Charsets.UTF_8)
            val hosts = (app.store.account?.hosts.orEmpty() + app.api.account?.hosts.orEmpty()).distinct()
            scrubForUpload(raw, hosts, app.store.settings.xmltvUrl)
        }
        val payload = buildJsonObject {
            put("installId", app.telemetry.installId)
            put("category", category.trim().ifEmpty { "Other" }.take(40))
            put("note", note.trim().take(500))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("deviceType", if (app.isTv()) "tv" else "phone")
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80))
            put("hasCrash", AppLog.lastCrash() != null)
            put("log", logText)
        }.toString()

        val code = withContext(Dispatchers.IO) {
            val client = app.http.newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("$endpoint/v1/report")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", "RelayTV-DiagUpload")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Upload failed (HTTP ${r.code})")
                val root = Jsons.json.parseToJsonElement(r.body!!.string())
                root.at("code").str() ?: throw IOException("Server didn't return a report code.")
            }
        }
        if (markCrashHandled) {
            AppLog.lastCrash()?.let { app.telemetry.lastPromptedCrashAt = it }
        }
        AppLog.i("Diagnostics", "Uploaded report #$code ($category)")
        code
    }
}
