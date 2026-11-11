package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.update.UpdateState
import dev.relay.tv.update.Updater
import kotlinx.coroutines.launch

/** Short status used in Settings. */
fun updateStatusText(): String = when (val s = Updater.state) {
    UpdateState.Idle -> "Tap to check"
    UpdateState.Checking -> "Checking…"
    UpdateState.UpToDate -> "Up to date"
    is UpdateState.Disabled -> "Not set up"
    is UpdateState.Available -> "${s.info.versionName} available"
    is UpdateState.NeedsPermission -> "Needs permission"
    is UpdateState.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
    is UpdateState.Installing -> "Installing…"
    is UpdateState.Failed -> "Problem, tap for details"
}

@Composable
fun UpdateDialog() {
    val app = RelayApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary = remember { FocusRequester() }
    val state = Updater.state
    val close: () -> Unit = {
        if (!Updater.busy) Updater.dialogOpen = false
    }

    InitialFocus(primary, state::class)

    TvDialog(onDismiss = close, width = 620.dp) {
        when (state) {
            is UpdateState.Available -> {
                Title("Relay ${state.info.versionName} is ready")
                Body("You have ${BuildConfig.VERSION_NAME}.")
                if (state.info.notes.isNotBlank()) Notes(state.info.notes)
                Buttons {
                    PillButton("Update now", {
                        scope.launch { Updater.downloadAndInstall(ctx, app.http, state.info) }
                    }, focusRequester = primary, primary = true)
                    PillButton("Later", close)
                }
            }
            is UpdateState.NeedsPermission -> {
                Title("Allow Relay to install updates")
                Body(
                    "Android needs a one time permission. Turn on Relay under Settings → Apps → " +
                        "Security & restrictions → Unknown sources, then come back and press Try again.",
                )
                Buttons {
                    PillButton("Open settings", { Updater.openInstallPermission(ctx) }, focusRequester = primary, primary = true)
                    PillButton("Try again", {
                        scope.launch { Updater.downloadAndInstall(ctx, app.http, state.info) }
                    })
                    PillButton("Later", close)
                }
            }
            is UpdateState.Downloading -> {
                Title("Downloading ${state.info.versionName}")
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Palette.accent,
                    trackColor = Palette.surface2,
                )
                Body("${(state.progress * 100).toInt()}%")
            }
            is UpdateState.Installing -> {
                Title("Installing ${state.info.versionName}")
                Body(
                    "Confirm on the next screen if Android asks. Relay closes while it updates; " +
                        "open it again from your apps. Your login, profiles and history are kept.",
                )
            }
            is UpdateState.Failed -> {
                Title("Update problem")
                Body(state.message)
                Buttons {
                    val info = state.info
                    if (info != null) {
                        PillButton("Try again", {
                            scope.launch { Updater.downloadAndInstall(ctx, app.http, info) }
                        }, focusRequester = primary, primary = true)
                    } else {
                        PillButton("Check again", {
                            scope.launch { Updater.check(app.http) }
                        }, focusRequester = primary, primary = true)
                    }
                    PillButton("Close", close)
                }
            }
            is UpdateState.Disabled -> {
                Title("Updates aren't set up")
                Body(state.reason)
                Buttons { PillButton("Close", close, focusRequester = primary) }
            }
            UpdateState.Checking -> {
                Title("Checking for updates…")
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Palette.accent,
                    trackColor = Palette.surface2,
                )
            }
            UpdateState.Idle, UpdateState.UpToDate -> {
                Title("You're up to date")
                Body("Relay ${BuildConfig.VERSION_NAME} is the latest build.")
                Buttons {
                    PillButton("Check again", { scope.launch { Updater.check(app.http) } }, focusRequester = primary, primary = true)
                    PillButton("Close", close)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = Type.bodyLarge, color = Palette.dim, lineHeight = 23.sp)
}

@Composable
private fun Notes(text: String) {
    Text(
        text, fontSize = Type.body, color = Palette.text, lineHeight = 22.sp,
        maxLines = 8, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
