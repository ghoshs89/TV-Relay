package dev.relay.tv

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
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

    @OptIn(UnstableApi::class)
    @Test fun movieKeepsPlayingOrPausedAcrossStopAndStart() {
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
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertFalse(player(it).playWhenReady)
                val positionDifference = abs(pausedAt - player(it).currentPosition)
                assertTrue("Paused movie moved by ${positionDifference}ms", positionDifference <= 250)
                player(it).play()
            }
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
