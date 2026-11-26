package dev.relay.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class XtreamCancellationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun cancellationBeforeHeadersReleasesTheCallWithoutFailingOver() = runBlocking {
        MockWebServer().use { primary ->
            MockWebServer().use { backup ->
                primary.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val started = CompletableDeferred<Unit>()
                val client = client(primary, backup, object : EventListener() {
                    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                        started.complete(Unit)
                    }
                })
                val request = async { client.getJson(emptyMap()) }
                withTimeout(3_000) {
                    started.await()
                    request.cancelAndJoin()
                }
                assertTrue(request.isCancelled)
                assertEquals(0, backup.requestCount)
                primary.enqueue(MockResponse().setBody("[]"))
                assertEquals("[]", withTimeout(3_000) { client.getJson(emptyMap()) }.toString())
            }
        }
    }

    @Test fun cancellationDuringBodyReadKeepsTheSavedFileAndAnImmediateRetryWorks() = runBlocking {
        MockWebServer().use { primary ->
            MockWebServer().use { backup ->
                primary.enqueue(MockResponse().setBody("a".repeat(1_000_000)).throttleBody(1024, 1, TimeUnit.SECONDS))
                val client = client(primary, backup)
                val dir = files.newFolder()
                val saved = dir.resolve("channels.json").apply { writeText("previous") }
                val receivedData = CompletableDeferred<Unit>()
                val request = async {
                    client.download("get_live_streams", saved) { bytes, _ ->
                        if (bytes > 0) receivedData.complete(Unit)
                    }
                }
                withTimeout(3_000) {
                    receivedData.await()
                    request.cancelAndJoin()
                }
                assertEquals(0, backup.requestCount)
                assertEquals("previous", saved.readText())
                assertEquals(listOf(saved), dir.listFiles()?.toList())
                primary.enqueue(MockResponse().setBody("[]"))
                withTimeout(3_000) { client.download("get_live_streams", saved) }
                assertEquals("[]", saved.readText())
            }
        }
    }

    private fun client(primary: MockWebServer, backup: MockWebServer, listener: EventListener = EventListener.NONE): XtreamApi {
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).eventListener(listener).build()
        return XtreamApi(http) { "Relay test" }.apply {
            account = Account("Test", listOf(primary.url("/").toString().trimEnd('/'), backup.url("/").toString().trimEnd('/')), "demo", "demo")
        }
    }
}
