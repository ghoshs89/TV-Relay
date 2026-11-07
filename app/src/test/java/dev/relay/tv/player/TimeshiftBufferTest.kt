package dev.relay.tv.player

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** The recorder against a local server that misbehaves the way overloaded IPTV servers do. */
class TimeshiftBufferTest {

    @get:Rule val tmp = TemporaryFolder()

    private val a = MockWebServer()
    private val b = MockWebServer()
    private var buffer: TimeshiftBuffer? = null

    @Before fun up() {
        a.start()
        b.start()
    }

    @After fun down() {
        buffer?.close()
        a.shutdown()
        b.shutdown()
    }

    private fun serve(r: () -> MockResponse) {
        val d = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = r()
        }
        a.dispatcher = d
        b.dispatcher = d
    }

    private fun start(): TimeshiftBuffer {
        val t = TimeshiftBuffer(
            tmp.newFolder(), OkHttpClient(),
            listOf(a.url("/live/1.ts").toString(), b.url("/live/1.ts").toString()),
            "test", 15,
            clock = { System.currentTimeMillis() },
            retryDelayMs = 10,
            firstDataTimeoutMs = 300,
        )
        buffer = t
        t.start()
        return t
    }

    /** Waits for the recorder to give up; fails the test if it keeps going. */
    private fun awaitGiveUp(t: TimeshiftBuffer, withinMs: Long): String {
        val end = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < end) {
            t.failure?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("Recorder still retrying after $withinMs ms")
    }

    @Test
    fun emptySuccessfulResponsesFailOverThenStop() {
        serve { MockResponse().setResponseCode(200) }
        val t = start()
        awaitGiveUp(t, 10_000)
        // Three rounds across both servers.
        assertEquals(6, a.requestCount + b.requestCount)
        assertTrue(a.requestCount > 0 && b.requestCount > 0)
    }

    @Test
    fun responsesThatCloseAfterAFewKilobytesFailOverThenStop() {
        serve { MockResponse().setBody(Buffer().write(ByteArray(8 * 1024) { 0x47 })) }
        val t = start()
        awaitGiveUp(t, 10_000)
        assertEquals(6, a.requestCount + b.requestCount)
    }

    @Test
    fun serversThatNeverAnswerFailOverThenStop() {
        serve { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val t = start()
        awaitGiveUp(t, 15_000)
    }

    @Test
    fun aPlaylistStopsTheRecorderStraightAway() {
        serve { MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:3\n") }
        val t = start()
        val why = awaitGiveUp(t, 5_000)
        assertTrue(why.contains("playlist"))
        assertEquals(1, a.requestCount + b.requestCount)
    }

    @Test
    fun aHealthyStreamKeepsRecording() {
        val size = 3 * 1024 * 1024
        serve {
            MockResponse().setBody(Buffer().write(ByteArray(size) { 0x47 }))
                .throttleBody(512 * 1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val t = start()
        val end = System.currentTimeMillis() + 10_000
        while (t.written < size && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("recorded ${t.written} bytes", t.written >= size)
        assertNull(t.failure)
        // The reader gets the recorded bytes.
        assertTrue(t.waitFor(0))
    }

    @Test
    fun readersWaitingOnAFailedRecorderGetAnError() {
        serve { MockResponse().setResponseCode(503) }
        val t = start()
        awaitGiveUp(t, 10_000)
        val e = runCatching { t.waitFor(0) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is IOException)
    }
}
