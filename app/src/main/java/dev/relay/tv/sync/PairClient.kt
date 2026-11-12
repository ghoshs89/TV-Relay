package dev.relay.tv.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The calls a screen that already syncs makes to a screen that is waiting to join. Blocking: run off the main thread. */
object PairClient {

    private fun post(http: OkHttpClient, s: WaitingScreen, path: String, body: ByteArray): String {
        val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url("http://${s.host}:${s.port}$path")
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when (r.code) {
                    200 -> return text
                    403 -> throw IOException("${s.name} is not ready to join. Open Settings, Sync, Join on it and try again.")
                    else -> throw IOException("${s.name} answered in a way Relay doesn't understand (${r.code}).")
                }
            }
        } catch (e: IOException) {
            if (e.message?.contains(s.name) == true) throw e
            throw IOException("Couldn't reach ${s.name}. Both screens need to be on the same network.")
        }
    }

    /** Sends this side's public key; the answer is the other screen's. */
    fun hello(http: OkHttpClient, s: WaitingScreen, myPublic: String): String =
        post(http, s, "/pair/hello", myPublic.toByteArray(Charsets.UTF_8))

    /** Sends the sealed bundle. */
    fun send(http: OkHttpClient, s: WaitingScreen, sealed: ByteArray) {
        post(http, s, "/pair/send", sealed)
    }
}
