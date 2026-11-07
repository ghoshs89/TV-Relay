package dev.relay.tv.data

import android.content.Context
import android.os.Build
import android.util.Log
import dev.relay.tv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Troubleshooting log with a hard disk budget.
 *
 *  - Always: the last [RING_SIZE] lines in memory and logcat, and information, warning and error
 *    lines in relay.log, so the story survives a freeze or force-close. At [MAX_FILE_BYTES] it
 *    rotates to relay.1.log (the older file is deleted), so disk use never exceeds ~1 MB.
 *  - When "Save detailed logs" is on: debug lines are saved too. That switches itself off after
 *    24 hours so a forgotten toggle can't run forever.
 *  - Crashes: one crash.txt, overwritten each time, capped at [MAX_CRASH_BYTES].
 *  - Every line is scrubbed of the username, password and tokens before it's stored.
 */
object AppLog {
    private const val RING_SIZE = 1500
    private const val MAX_FILE_BYTES = 512L * 1024
    private const val MAX_CRASH_BYTES = 64 * 1024
    const val SAVE_WINDOW_MS = 24L * 3600_000

    private val ring = ArrayDeque<String>(RING_SIZE)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-log").apply { isDaemon = true } }
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var dir: File? = null

    /** Epoch millis until which lines are saved to disk; 0 = off. */
    @Volatile var saveUntil: Long = 0L

    /** Device and library facts added to shared logs (set by the app at startup). */
    @Volatile var stateDump: () -> String = { "" }

    /** Values that must never reach a log line (set by the app at startup). */
    @Volatile var secrets: () -> List<String> = { emptyList() }

    val saving: Boolean get() = System.currentTimeMillis() < saveUntil
    val directory: File? get() = dir
    private val current: File? get() = dir?.let { File(it, "relay.log") }
    private val previous: File? get() = dir?.let { File(it, "relay.1.log") }
    val crashFile: File? get() = dir?.let { File(it, "crash.txt") }

    fun init(ctx: Context) {
        // App-specific external storage when available so `adb pull` can reach it; no permission needed.
        dir = (ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")).also { it.mkdirs() }
        installCrashHandler()
        i("App", "Relay ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun d(tag: String, msg: String) = log(Log.DEBUG, 'D', tag, msg, null)
    fun i(tag: String, msg: String) = log(Log.INFO, 'I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Log.WARN, 'W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Log.ERROR, 'E', tag, msg, t)

    private fun log(priority: Int, level: Char, tag: String, msg: String, t: Throwable?) {
        val detail = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        val clean = redact(detail)
        Log.println(priority, "Relay/$tag", clean)
        val stamp = synchronized(timeFmt) { timeFmt.format(Date()) }
        val line = "$stamp $level/$tag: $clean"
        synchronized(ring) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(line)
        }
        if (level == 'D' && !saving) return
        val stack = if (t != null && level != 'I') {
            // Relay's own frames are the useful ones; keep a few framework frames for context.
            val frames = t.stackTrace
            val own = frames.filter { it.className.startsWith("dev.relay") }.take(8)
            (frames.take(4) + own).distinct().joinToString("") { "\n    at $it" } +
                (t.cause?.let { "\n    caused by ${it.javaClass.simpleName}: ${redact(it.message.orEmpty())}" } ?: "")
        } else {
            ""
        }
        writer.execute { append(line + stack + "\n") }
    }

    private fun append(text: String) {
        val cur = current ?: return
        try {
            if (cur.length() > MAX_FILE_BYTES) {
                previous?.delete()
                cur.renameTo(previous!!)
            }
            cur.appendText(text)
        } catch (_: Exception) {
            // Logging must never break the app.
        }
    }

    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    fun diskBytes(): Long = listOfNotNull(current, previous, crashFile).sumOf { if (it.exists()) it.length() else 0L }

    fun lastCrash(): Long? = crashFile?.takeIf { it.exists() }?.lastModified()

    fun clear() {
        writer.execute { listOfNotNull(current, previous, crashFile).forEach { it.delete() } }
        synchronized(ring) { ring.clear() }
    }

    /** One text bundle for sharing: device header, last crash, saved logs (or the in-memory buffer). */
    fun bundle(ctx: Context): File {
        val out = File(ctx.cacheDir, "share").apply { mkdirs() }.resolve("relay-logs.txt")
        val saved = listOfNotNull(previous, current).filter { it.exists() }
        out.printWriter().use { pw ->
            pw.println("Relay ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("Created: ${Date()}")
            pw.println("Detailed logging: ${if (saving) "on" else "off"}")
            try {
                pw.println("\n===== Device and library =====")
                pw.println(redact(stateDump()))
            } catch (e: Throwable) {
                pw.println("(unavailable: ${e.javaClass.simpleName})")
            }
            crashFile?.takeIf { it.exists() }?.let {
                pw.println("\n===== Last crash (${Date(it.lastModified())}) =====")
                pw.println(it.readText())
            }
            if (saved.isNotEmpty()) {
                pw.println("\n===== Saved log (oldest first, includes earlier sessions) =====")
                saved.forEach { f -> f.forEachLine { pw.println(it) } }
            }
            pw.println("\n===== This session, including detail lines =====")
            recent().forEach { pw.println(it) }
        }
        return out
    }

    fun redact(s: String): String {
        var r = s
        for (secret in secrets()) {
            if (secret.length >= 3) r = r.replace(secret, "***")
        }
        r = QUERY_SECRET.replace(r) { "${it.groupValues[1]}=***" }
        r = BEARER.replace(r, "Bearer ***")
        return r
    }

    private val QUERY_SECRET = Regex("(?i)\\b(username|password|api_key|apikey|token)=[^&\\s]+")
    private val BEARER = Regex("(?i)Bearer\\s+\\S+")

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val text = buildString {
                    append("Crash on thread ${thread.name} at ${Date()}\n")
                    append(redact(Log.getStackTraceString(ex)))
                    append("\nRecent activity:\n")
                    recent().takeLast(80).forEach { append(it).append('\n') }
                }
                crashFile?.writeText(text.take(MAX_CRASH_BYTES))
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(thread, ex)
        }
    }
}
