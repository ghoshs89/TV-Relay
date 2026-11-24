package dev.relay.tv

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.relay.tv.ui.RelayTheme
import dev.relay.tv.ui.UpdateDialog
import dev.relay.tv.update.UpdateInfo
import dev.relay.tv.update.UpdateState
import dev.relay.tv.update.Updater
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun longNotesScrollWithTouchAndRemoteWhileButtonsStayVisible() {
        val notes = (1..35).joinToString("\n") { "Release note $it. This text should be available by scrolling." }
        val previous = Updater.state
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Updater.state = UpdateState.Available(UpdateInfo(999, "1.0.999", notes, "", 0))
                    activity.setContent { RelayTheme { UpdateDialog() } }
                }

                fun checkButtons() {
                    compose.onNodeWithText("Update now").assertIsDisplayed()
                    compose.onNodeWithText("Later").assertIsDisplayed()
                }
                checkButtons()

                val axis = SemanticsProperties.VerticalScrollAxisRange
                val notesArea = compose.onNode(SemanticsMatcher.keyIsDefined(axis))
                val position = notesArea.fetchSemanticsNode().config[axis].value
                val start = compose.runOnIdle(position)
                val device = UiDevice.getInstance(instrumentation)
                device.pressDPadUp()
                notesArea.assertIsFocused()
                device.pressDPadDown()
                compose.waitUntil(5_000) { position() > start }
                val afterRemote = compose.runOnIdle(position)
                checkButtons()

                notesArea.performTouchInput { swipeUp() }
                compose.waitUntil(5_000) { position() > afterRemote }
                checkButtons()
            }
        } finally {
            instrumentation.runOnMainSync { Updater.state = previous }
        }
    }
}
