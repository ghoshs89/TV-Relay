package dev.relay.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.relay.tv.ui.LocalNav
import dev.relay.tv.ui.Nav
import dev.relay.tv.ui.RelayTheme
import dev.relay.tv.ui.Route
import dev.relay.tv.ui.SettingsTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun backRestoresTheOpenerInBothSettingsMenus() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nav = Nav(Route.Home)
                activity.setContent {
                    RelayTheme {
                        CompositionLocalProvider(LocalNav provides nav) {
                            Box(Modifier.fillMaxSize()) { SettingsTab() }
                        }
                    }
                }
            }
            val playback = compose.onNodeWithText("Playback & subtitles")
            val advanced = compose.onNodeWithText("Advanced playback")
            playback.performClick()
            advanced.performClick()
            compose.onNodeWithText("Live stream format").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            advanced.assertIsFocused()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            playback.assertIsFocused()
            playback.performKeyInput { pressKey(Key.DirectionCenter) }
            advanced.assertIsDisplayed()
        }
    }
}
