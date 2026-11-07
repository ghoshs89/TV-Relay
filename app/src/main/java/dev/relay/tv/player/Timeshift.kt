package dev.relay.tv.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.relay.tv.data.AppLog
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Pause and rewind for live TV. One connection records the channel into rotating segment files;
 * the player reads from those files, so pausing simply stops reading while recording continues.
 * Disk use is capped (buffer length, free space and a hard limit) and everything is deleted when
 * the channel changes or the player closes.
 */
class TimeshiftBuffer(
    private val dir: File,
    client: OkHttpClient,
    private val urls: List<String>,
    private val userAgent: String,
    private val bufferMinutes: Int,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val retryDelayMs: Long = 1_000,
    firstDataTimeoutMs: Long = RecorderPolicy.FIRST_DATA_TIMEOUT_MS,
) {
    // A connection that sends nothing for this long is treated as failed.
    private val http = client.newBuilder().readTimeout(firstDataTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val lock = Object()

    /** Total bytes recorded since start; also the global offset of the live edge. */
    @Volatile var written = 0L
        private set

    /** Oldest byte still on disk. */
    @Volatile var firstAvailable = 0L
        private set

    @Volatile private var closed = false
    @Volatile var failure: String? = null
        private set
    @Volatile private var call: Call? = null
    private var startedAt = 0L
    private var out: FileOutputStream? = null
    private var outSeg = -1L

    val bytesPerSecond: Double
        get() {
            val secs = (clock() - startedAt) / 1000.0
            return if (startedAt == 0L || secs < 2 || written <= 0) 0.0 else written / secs
        }

    fun start() {
        dir.deleteRecursively()
        dir.mkdirs()
        Thread({ run() }, "relay-timeshift").apply { isDaemon = true }.start()
    }

    fun close() {
        closed = true
        call?.cancel()
        synchronized(lock) { lock.notifyAll() }
        try {
            out?.close()
        } catch (_: Exception) {
        }
        dir.deleteRecursively()
    }

    fun segFile(i: Long) = File(dir, "s$i.ts")

    private fun run() {
        val policy = RecorderPolicy(urls.size, clock)
        val buf = ByteArray(64 * 1024)
        while (!closed) {
            val url = urls[policy.host % urls.size]
            val began = clock()
            var bytes = 0L
            var error = false
            try {
                val c = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
                call = c
                c.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val input = r.body?.byteStream() ?: throw IOException("Empty stream")
                    while (!closed) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (bytes == 0L && TimeshiftSupport.looksLikePlaylist(buf, n)) {
                            // An HLS playlist, not a transport stream: this channel must play directly.
                            giveUp("The server sent a playlist, not MPEG-TS")
                            return
                        }
                        append(buf, n)
                        bytes += n
                    }
                }
            } catch (e: Exception) {
                if (closed) break
                error = true
                AppLog.w("Timeshift", "Connection to ${hostOf(url)} failed after ${bytes / 1024} KB", e)
            }
            if (closed) break
            when (val d = policy.onEnded(bytes, clock() - began, error)) {
                RecorderPolicy.Decision.Retry ->
                    AppLog.i("Timeshift", "Stream closed after ${bytes / 1024} KB; reconnecting")
                RecorderPolicy.Decision.NextHost ->
                    AppLog.w("Timeshift", "Trying server ${policy.host + 1} of ${urls.size} (failure ${policy.failures})")
                is RecorderPolicy.Decision.GiveUp -> {
                    giveUp(d.reason)
                    break
                }
            }
            try {
                Thread.sleep(retryDelayMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun giveUp(reason: String) {
        AppLog.e("Timeshift", "Recorder stopped: $reason")
        failure = reason
        synchronized(lock) { lock.notifyAll() }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    private fun append(b: ByteArray, n: Int) {
        var off = 0
        while (off < n && !closed) {
            val seg = written / SEG
            val room = (SEG - written % SEG).toInt()
            val k = minOf(room, n - off)
            if (seg != outSeg) {
                out?.close()
                out = FileOutputStream(segFile(seg), true)
                outSeg = seg
                trim()
            }
            out!!.write(b, off, k)
            off += k
            synchronized(lock) {
                written += k
                if (startedAt == 0L) startedAt = clock().coerceAtLeast(1L)
                lock.notifyAll()
            }
        }
    }

    /** Drops the oldest segments to stay within the cap. */
    private fun trim() {
        val rate = bytesPerSecond
        val byTime = if (rate > 0) (rate * bufferMinutes * 60).toLong() else HARD_CAP
        val bySpace = (dir.usableSpace / 4).coerceAtLeast(SEG * 4)
        val cap = minOf(byTime, bySpace, HARD_CAP).coerceAtLeast(SEG * 3)
        val keepFrom = ((written - cap).coerceAtLeast(0) / SEG)
        var i = firstAvailable / SEG
        while (i < keepFrom) {
            segFile(i).delete()
            i++
        }
        if (keepFrom * SEG > firstAvailable) firstAvailable = keepFrom * SEG
    }

    /** Blocks until data exists at [pos]. False when the buffer is closed. */
    fun waitFor(pos: Long): Boolean {
        synchronized(lock) {
            while (!closed && failure == null && pos >= written) {
                try {
                    lock.wait(500)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException("Timeshift read cancelled")
                }
            }
        }
        if (closed) return false
        if (pos >= written) throw IOException(failure ?: "Stream ended")
        return true
    }

    companion object {
        const val SEG = 4L * 1024 * 1024
        const val HARD_CAP = 1536L * 1024 * 1024
        const val SCHEME = "relayts"

        fun uriAt(offset: Long): Uri = Uri.parse("$SCHEME://live?o=$offset")
    }
}

/** Reads the recorded live stream from [TimeshiftBuffer], following the live edge as it grows. */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val buffer: () -> TimeshiftBuffer?) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var buf: TimeshiftBuffer? = null
    private var pos = 0L
    private var raf: RandomAccessFile? = null
    private var rafSeg = -1L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val b = buffer() ?: throw IOException("Pause buffer isn't running")
        buf = b
        val base = dataSpec.uri.getQueryParameter("o")?.toLongOrNull() ?: 0L
        pos = maxOf(base + dataSpec.position, b.firstAvailable)
        spec = dataSpec
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = buf ?: return C.RESULT_END_OF_INPUT
        if (!b.waitFor(pos)) return C.RESULT_END_OF_INPUT
        // Fell behind the oldest kept data: skip forward (the TS reader resyncs).
        if (pos < b.firstAvailable) pos = b.firstAvailable
        val seg = pos / TimeshiftBuffer.SEG
        if (seg != rafSeg) {
            raf?.close()
            raf = RandomAccessFile(b.segFile(seg), "r")
            rafSeg = seg
        }
        val inSeg = pos % TimeshiftBuffer.SEG
        val avail = minOf(b.written - pos, TimeshiftBuffer.SEG - inSeg)
        val r = raf!!
        // Never return 0 bytes: retry briefly if the file system hasn't caught up yet.
        repeat(40) {
            r.seek(inSeg)
            val n = r.read(buffer, offset, minOf(length.toLong(), avail).toInt())
            if (n > 0) {
                pos += n
                bytesTransferred(n)
                return n
            }
            try {
                Thread.sleep(25)
            } catch (e: InterruptedException) {
                throw InterruptedIOException("Timeshift read cancelled")
            }
        }
        throw IOException("Pause buffer unreadable")
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
        rafSeg = -1
        spec = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val buffer: () -> TimeshiftBuffer?) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(buffer)
    }
}
