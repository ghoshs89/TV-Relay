package dev.relay.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.relay.tv.data.Account
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.Profile
import dev.relay.tv.data.WatchEntry
import dev.relay.tv.player.PlayerActivity
import dev.relay.tv.ui.UiState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures the README gallery from the current code using the local fake provider. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class DocumentationScreenshotsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = RelayApp.instance
    private val profile = Profile("gallery", "Demo")
    private val provider = "http://10.0.2.2:8099"
    private val demoTitle = "The Long Way Home"

    @Test fun captureGallery() {
        val layout = InstrumentationRegistry.getArguments().getString("gallery")
        assumeTrue(layout == "tv" || layout == "phone")
        val mode = app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        assertEquals(layout == "tv", mode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION)
        val folder = checkNotNull(app.getExternalFilesDir("gallery-$layout"))
        val signIn = Account("Demo library", listOf(provider), "bench", "bench")
        instrumentation.runOnMainSync {
            app.store.account = signIn
            app.api.account = signIn
            app.store.settings = app.store.settings.copy(setupDone = true, startScreen = "home", startOnLastChannel = false)
            app.store.profiles = listOf(profile)
            app.store.lastProfileId = profile.id
            app.store.parentPin = null
            app.session.profile = profile
            app.session.autoStartDone = true
            app.ui = UiState()
        }
        runBlocking { withTimeout(45_000) { app.catalog.load(force = true) } }
        instrumentation.runOnMainSync {
            val now = System.currentTimeMillis()
            for ((index, movie) in app.catalog.movies.take(3).withIndex()) {
                app.store.record(
                    profile.id, WatchEntry(
                        "movie:${movie.id}", "movie", movie.name, movie.year, movie.poster,
                        streamId = movie.id.toString(), ext = "mp4",
                        position = (index + 1) * 720_000L, duration = 5_400_000L, updated = now - index * 1_000,
                    ),
                )
            }
            app.catalog.channels.take(6).forEach { channel ->
                app.store.record(
                    profile.id, WatchEntry(
                        "live:${channel.id}", "live", channel.name, image = channel.logo,
                        streamId = channel.id.toString(), ext = "ts", updated = now,
                    ),
                )
            }
        }
        if (layout == "tv") {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("Continue watching")
                capture(folder, "home")
                tap("Live TV")
                waitFor("Guide grid")
                capture(folder, "live-tv")
                tap("Guide grid")
                assertTrue(device.wait(Until.hasObject(By.textStartsWith("Programme ")), 20_000))
                capture(folder, "guide")
                tap("Movies")
                waitFor("Recently added")
                capture(folder, "movies")
                tap("Series")
                waitFor("Recently added")
                capture(folder, "series")
                tap("Settings")
                waitFor("About & diagnostics")
                capture(folder, "settings")
            }
        }
        instrumentation.runOnMainSync {
            app.pendingPlay = PlayRequest(
                profile.id, listOf(PlayItem("movie:100001", "movie", demoTitle, "2026", streamId = "100001", ext = "ts")), 0,
            )
        }
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            var paused = false
            for (tryNumber in 0 until 100) {
                scenario.onActivity { activity ->
                    val view = checkNotNull(findPlayer(activity.window.decorView))
                    if (view.player?.isPlaying == true && (view.player?.currentPosition ?: 0) >= 2_000L) {
                        view.player?.pause()
                        view.showController()
                        paused = true
                    }
                }
                if (paused) break
                Thread.sleep(200)
            }
            assertTrue("The demo movie must play", paused)
            assertTrue(device.wait(Until.hasObject(By.textStartsWith(demoTitle)), 5_000))
            capture(folder, if (layout == "tv") "player-paused" else "phone-paused", settleMs = 400)
        }
    }

    private fun waitFor(label: String) = checkNotNull(device.wait(Until.findObject(By.text(label)), 15_000)) { label }

    private fun tap(label: String) = waitFor(label).click()

    private fun capture(folder: java.io.File, name: String, settleMs: Long = 1_700) {
        Thread.sleep(settleMs)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        folder.resolve("$name.jpg").outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, output))
        }
        bitmap.recycle()
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
