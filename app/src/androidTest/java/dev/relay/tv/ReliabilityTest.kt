package dev.relay.tv

import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.relay.tv.data.Account
import dev.relay.tv.data.Channel
import dev.relay.tv.data.FavItem
import dev.relay.tv.data.ParentPin
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.Profile
import dev.relay.tv.data.Store
import dev.relay.tv.data.WatchEntry
import dev.relay.tv.player.PlayerActivity
import dev.relay.tv.ui.FocusMemory
import dev.relay.tv.ui.GuideGrid
import dev.relay.tv.ui.LocalFocusMemory
import dev.relay.tv.ui.RelayTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReliabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = RelayApp.instance
    private val adult = Profile("adult", "Grown-up")
    private val kid = Profile("kid", "Kids", kids = true)
    private val testAccount = Account("Test", listOf("http://10.0.2.2:8099"), "bench", "bench")

    @Test fun concurrentProfileEditPreservesLocalDataButAcceptedDeletionCleansItUp() {
        instrumentation.runOnMainSync {
            val store = app.store
            store.profiles = listOf(adult, kid)
            val favourite = FavItem("live", 42, "News")
            val history = WatchEntry("movie:1", "movie", "Film", streamId = "1", ext = "mp4", position = 60_000)
            for (profile in store.profiles) {
                if (!store.isFav(profile.id, favourite.kind, favourite.id)) store.toggleFav(profile.id, favourite)
                store.record(profile.id, history)
                store.addUsage(profile.id, 20_000)
                store.allowExtra(profile.id, 30)
            }
            val before = store.syncSnapshot()
            val remote = before.copy(
                profiles = listOf(adult),
                favs = before.favs - kid.id,
                history = before.history - kid.id,
            )
            val edited = kid.copy(name = "Local name")
            store.profiles = listOf(adult, edited)
            store.syncApply(before, remote)
            store.flush()
            with(Store(app)) {
                assertEquals(edited, profiles.last())
                assertTrue(isFav(kid.id, favourite.kind, favourite.id))
                assertEquals(history, entry(kid.id, history.key))
                assertTrue(usedToday(kid.id) >= 20_000)
                assertEquals(before.allow[kid.id], allowUntil(kid.id))
            }

            store.syncApply(store.syncSnapshot(), remote)
            store.flush()
            with(Store(app)) {
                assertEquals(listOf(adult), profiles)
                assertTrue(favs(kid.id).isEmpty())
                assertTrue(history(kid.id).isEmpty())
                assertEquals(0L, usedToday(kid.id))
                assertEquals(0L, allowUntil(kid.id))
                assertTrue(isFav(adult.id, favourite.kind, favourite.id))
            }
        }
    }

    @Test fun lockedKidsProfileReopensOnActivityRecreationAndFreshSession() {
        instrumentation.runOnMainSync {
            app.store.profiles = listOf(adult, kid)
            app.store.parentPin = ParentPin.hash("1234")
            app.store.lastProfileId = kid.id
            app.session.profile = null
            app.session.autoPickDone = false
            configureProvider()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForKidsHome(scenario)
            scenario.recreate()
            waitForKidsHome(scenario)
        }
        instrumentation.runOnMainSync {
            app.session.profile = null
            app.session.autoPickDone = false
        }
        ActivityScenario.launch(MainActivity::class.java).use(::waitForKidsHome)
    }

    private fun waitForKidsHome(scenario: ActivityScenario<MainActivity>) {
        val home = By.text("Home")
        assertTrue("The locked profile must reach Home", UiDevice.getInstance(instrumentation).wait(Until.hasObject(home), 20_000))
        scenario.onActivity {
            assertEquals(kid, app.session.profile)
            assertEquals(kid.id, app.store.lastProfileId)
        }
    }

    @Test fun guideDisplaysListingsFromTheFakeProvider() {
        instrumentation.runOnMainSync { configureProvider() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val focus = FocusMemory()
                val channel = Channel(1, 1, "Test channel", null, "1", null, 1)
                activity.setContent {
                    RelayTheme {
                        CompositionLocalProvider(LocalFocusMemory provides focus) {
                            GuideGrid(listOf(channel), Modifier.fillMaxSize())
                        }
                    }
                }
            }
            val programme = By.textStartsWith("Programme 1-")
            assertTrue("The guide must display a programme", UiDevice.getInstance(instrumentation).wait(Until.hasObject(programme), 20_000))
        }
    }

    @Test fun syncAppliesDeletesEvenWhenOtherItemsInBothListsChange() {
        instrumentation.runOnMainSync {
            val store = app.store
            val profile = Profile("concurrent-sync", "Demo")
            store.profiles = listOf(profile)
            val a = FavItem("movie", 1, "A")
            val b = FavItem("movie", 2, "B")
            val first = WatchEntry("movie:1", "movie", "A", streamId = "1", ext = "mp4", position = 10, updated = 1)
            val second = first.copy(key = "movie:2", title = "B", streamId = "2", updated = 2)
            store.setFavs(profile.id, listOf(a, b))
            store.record(profile.id, first)
            store.record(profile.id, second)
            val before = store.syncSnapshot()
            val remote = before.copy(
                favs = mapOf(profile.id to listOf(b)),
                history = mapOf(profile.id to listOf(second)),
            )
            val edited = second.copy(position = 20, updated = 3)
            val favEdited = b.copy(name = "B renamed")
            store.record(profile.id, edited)
            store.setFavs(profile.id, listOf(a, favEdited))

            store.syncApply(before, remote)
            assertEquals(listOf(favEdited), store.favs(profile.id))
            assertEquals(listOf(edited), store.history(profile.id))
        }
    }

    @Test fun settingsReturnFocusToTheOpenedCategory() {
        instrumentation.runOnMainSync {
            app.store.profiles = listOf(adult)
            app.store.lastProfileId = adult.id
            app.store.parentPin = null
            app.session.profile = adult
            app.session.autoStartDone = true
            app.ui.tab = 0
            configureProvider()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val device = UiDevice.getInstance(instrumentation)
            val settings = By.text("Settings")
            checkNotNull(device.wait(Until.findObject(settings), 15_000)).click()
            val playback = By.text("Playback & subtitles")
            checkNotNull(device.wait(Until.findObject(playback), 5_000)).click()
            val advanced = By.text("Advanced playback")
            checkNotNull(device.wait(Until.findObject(advanced), 5_000)).click()
            assertTrue(device.wait(Until.hasObject(By.text("Live stream format")), 5_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(advanced), 5_000))
            device.pressBack()
            assertTrue("Back should focus Playback & subtitles",
                device.wait(Until.hasObject(By.text("Playback & subtitles").focused(true)), 5_000))
            device.pressDPadCenter()
            assertTrue("OK should reopen the same category after Back", device.wait(Until.hasObject(advanced), 5_000))
        }
    }

    @Test @OptIn(UnstableApi::class)
    fun livePlaylistAtTsUrlPlaysWithAndWithoutTimeshiftThenAnotherChannelCanPauseAndRewind() {
        val hls = PlayItem("live:1", "live", "Playlist channel", streamId = "1", ext = "ts")
        val ts = PlayItem("live:2", "live", "Transport stream channel", streamId = "2", ext = "ts")
        val device = UiDevice.getInstance(instrumentation)
        for (buffer in listOf(false, true)) {
            instrumentation.runOnMainSync {
                app.store.profiles = listOf(adult)
                app.store.parentPin = null
                app.session.profile = adult
                configureProvider()
                app.store.settings = app.store.settings.copy(timeshift = buffer, liveExt = "ts")
                app.pendingPlay = PlayRequest(adult.id, listOf(hls, ts), 0, live = true)
            }
            ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
                awaitPlayer(scenario, "HLS should play with timeshift=$buffer") {
                    it.isPlaying && it.currentMediaItem?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8
                }
                if (buffer) {
                    scenario.onActivity { activity -> findPlayerView(activity.window.decorView)?.hideController() }
                    sendPlayerKey(scenario, KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitPlayer(scenario, "MPEG-TS channel should use the pause buffer") {
                        it.isPlaying && it.currentMediaItem?.mediaId == ts.key &&
                            it.currentMediaItem?.localConfiguration?.uri?.scheme == "relayts"
                    }
                    Thread.sleep(10_500)
                    runBlocking { withTimeout(5_000) { app.catalog.shortEpgOrNull(2, fresh = true) } }
                    sendPlayerKey(scenario, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    awaitPlayer(scenario, "The MPEG-TS channel should pause") { !it.playWhenReady }
                    assertTrue(device.wait(Until.hasObject(By.textContains("Paused ·")), 5_000))
                    assertTrue(device.wait(Until.hasObject(By.textStartsWith("Current programme")), 5_000))
                    sendPlayerKey(scenario, KeyEvent.KEYCODE_MEDIA_REWIND)
                    awaitPlayer(scenario, "The same channel should resume after rewinding") {
                        it.isPlaying && it.currentMediaItem?.mediaId == ts.key
                    }
                    sendPlayerKey(scenario, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    assertTrue("Rewound frames should show the earlier programme",
                        device.wait(Until.hasObject(By.textStartsWith("Earlier programme")), 5_000))
                    sendPlayerKey(scenario, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    awaitPlayer(scenario, "Seeking in the buffer should resume playback") { it.isPlaying }
                }
            }
        }
    }

    /** Deliver remote input to the active player, independent of the emulator's system media buttons. */
    private fun sendPlayerKey(scenario: ActivityScenario<PlayerActivity>, key: Int) {
        scenario.onActivity { activity ->
            assertTrue("The player should handle key $key", activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    @OptIn(UnstableApi::class)
    private fun awaitPlayer(
        scenario: ActivityScenario<PlayerActivity>,
        message: String,
        condition: (androidx.media3.common.Player) -> Boolean,
    ) {
        var lastState = ""
        repeat(100) {
            var matched = false
            scenario.onActivity { activity ->
                val player = checkNotNull(findPlayerView(activity.window.decorView)?.player)
                matched = condition(player)
                lastState = "item=${player.currentMediaItem?.mediaId}, state=${player.playbackState}, " +
                    "source=${player.currentMediaItem?.localConfiguration?.uri?.scheme}, error=${player.playerError?.errorCodeName}"
            }
            if (matched) return
            Thread.sleep(200)
        }
        throw AssertionError("$message ($lastState)")
    }

    @Test @OptIn(UnstableApi::class)
    fun pauseTitleFollowsEpisodeChangesAndUsesTheCatchupProgrammeName() {
        val fakeMovie = "http://10.0.2.2:8099/movie/bench/bench/100001.ts"
        val title = "Northbound"
        val first = PlayItem("ep:1", "episode", title, "S1 E1 · The Departure", streamId = "1", ext = "ts", urls = listOf(fakeMovie))
        val second = first.copy(key = "ep:2", subtitle = "S1 E2 · A Long Journey Through the Mountains", streamId = "2")
        val catchup = first.copy(key = "catchup:7:now", kind = "catchup", title = "Coast News", subtitle = "Evening News")
        val device = UiDevice.getInstance(instrumentation)

        fun queue(items: List<PlayItem>) {
            instrumentation.runOnMainSync {
                app.store.profiles = listOf(adult)
                app.store.parentPin = null
                app.session.profile = adult
                configureProvider()
                app.pendingPlay = PlayRequest(adult.id, items, 0)
            }
        }

        queue(listOf(first, second))
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            awaitPlayer(scenario, "The first episode should play") { it.isPlaying }
            scenario.onActivity {
                val player = checkNotNull(findPlayerView(it.window.decorView)?.player)
                player.seekToNextMediaItem()
            }
            awaitPlayer(scenario, "The second episode should play") {
                it.isPlaying && it.currentMediaItem?.mediaId == second.key
            }
            sendPlayerKey(scenario, KeyEvent.KEYCODE_MEDIA_PAUSE)
            awaitPlayer(scenario, "The next episode should pause") { !it.playWhenReady }
            assertTrue("The pause title should include the active episode",
                device.wait(Until.hasObject(By.textContains("S1 E2 · A Long Journey")), 5_000))
        }
        queue(listOf(catchup))
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            awaitPlayer(scenario, "The catch-up programme should play") { it.isPlaying }
            val playPause = device.findObject(By.desc("Pause"))
            if (playPause == null) {
                scenario.onActivity { findPlayerView(it.window.decorView)?.showController() }
            }
            val pause = checkNotNull(device.wait(Until.findObject(By.desc("Pause")), 5_000))
            pause.click()
            awaitPlayer(scenario, "Tapping Pause should pause catch-up") { !it.playWhenReady }
            assertTrue("The pause title should start with the programme name",
                device.wait(Until.hasObject(By.textStartsWith("Evening News")), 5_000))
        }
    }

    @Test fun movieKeepsPlayingOrPausedAcrossStopAndStart() {
        // ActivityScenario backgrounds the player with a user-leave hint. Disable PiP on this
        // emulator for this test so the playing case reaches onStop as well as onPause.
        val op = "appops set ${app.packageName} PICTURE_IN_PICTURE"
        shell("$op ignore")
        try {
            checkMovieBackgroundPlayback()
        } finally {
            shell("$op default")
        }
    }

    private fun shell(command: String) {
        val result = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { it.readText() }
    }

    @OptIn(UnstableApi::class)
    private fun checkMovieBackgroundPlayback() {
        instrumentation.runOnMainSync {
            app.store.profiles = listOf(adult)
            app.store.parentPin = null
            app.store.lastProfileId = adult.id
            app.session.profile = adult
            configureProvider()
            val movie = PlayItem("movie:100001", "movie", "Test movie", streamId = "100001", ext = "ts")
            app.pendingPlay = PlayRequest(adult.id, listOf(movie), 0)
        }
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            val player = { activity: PlayerActivity -> checkNotNull(findPlayerView(activity.window.decorView)?.player) }
            var started = false
            for (attempt in 0 until 100) {
                scenario.onActivity { started = player(it).isPlaying }
                if (started) break
                Thread.sleep(200)
            }
            assertTrue("The fake provider movie must start", started)

            var pausedAt = 0L
            scenario.onActivity {
                player(it).pause()
                pausedAt = player(it).currentPosition
            }
            val device = UiDevice.getInstance(instrumentation)
            scenario.onActivity { findPlayerView(it.window.decorView)?.hideController() }
            assertTrue(device.wait(Until.hasObject(By.text("Test movie")), 5_000))
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(device.wait(Until.hasObject(By.text("Test movie")), 5_000))
            scenario.onActivity {
                assertFalse(player(it).playWhenReady)
                val positionDifference = abs(pausedAt - player(it).currentPosition)
                assertTrue("Paused movie moved by ${positionDifference}ms", positionDifference <= 250)
                player(it).play()
            }
            assertTrue(device.wait(Until.gone(By.text("Test movie")), 5_000))
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertTrue(player(it).playWhenReady) }
        }
    }

    private fun configureProvider() {
        app.store.account = testAccount
        app.api.account = testAccount
        app.store.settings = app.store.settings.copy(setupDone = true)
    }

    @OptIn(UnstableApi::class)
    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findPlayerView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
