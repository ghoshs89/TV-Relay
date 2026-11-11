package dev.relay.tv.bench

import dev.relay.tv.RelayApp
import dev.relay.tv.data.Account

/**
 * Only in the benchmark build (never in the release APK). Signs the app in to the made-up provider
 * that tools/fake-provider runs on the machine hosting the emulator, so CI can drive the app
 * through the same screens a person would without typing a login. See tools/bench/bench.sh.
 */
object BenchSetup {
    /** The emulator reaches the machine it runs on at 10.0.2.2. */
    const val HOST = "http://10.0.2.2:8099"

    @JvmStatic
    fun run(app: RelayApp) {
        if (app.store.account != null) return
        val account = Account(name = "Bench", hosts = listOf(HOST), username = "bench", password = "bench")
        app.store.account = account
        app.store.activeHost = HOST
        app.store.settings = app.store.settings.copy(setupDone = true)
        app.api.account = account
        app.api.host = HOST
    }
}
