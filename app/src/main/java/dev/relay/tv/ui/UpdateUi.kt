package dev.relay.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
        if (state is UpdateState.Installing) Updater.dismissInstalling()
        else if (!Updater.busy) Updater.dialogOpen = false
    }

    InitialFocus(primary, state::class)

    val hasNotes = state is UpdateState.Available && state.info.notes.isNotBlank()
    TvDialog(onDismiss = close, width = 620.dp, scrollContent = !hasNotes) {
        when (state) {
            is UpdateState.Available -> {
                Title("Relay ${state.info.versionName} is ready")
                Body("You have ${BuildConfig.VERSION_NAME}.")
                if (hasNotes) {
                    key(state.info.versionCode) {
                        Notes(state.info.notes, Modifier.weight(1f, fill = false))
                    }
                }
                Buttons {
                    PillButton("Update now", {
                        Updater.startDownloadAndInstall(ctx, app.http, app.appScope, state.info)
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
                        Updater.startDownloadAndInstall(ctx, app.http, app.appScope, state.info)
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
                Buttons {
                    PillButton("Close", { Updater.dismissInstalling() }, focusRequester = primary)
                }
            }
            is UpdateState.Failed -> {
                Title("Update problem")
                Body(state.message)
                Buttons {
                    val info = state.info
                    if (info != null) {
                        PillButton("Try again", {
                            Updater.startDownloadAndInstall(ctx, app.http, app.appScope, info)
                        }, focusRequester = primary, primary = true)
                    } else {
                        PillButton("Check again", {
                            app.appScope.launch { Updater.check(app.http) }
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
private fun Notes(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 72.dp.toPx() }
    val overflows = scroll.maxValue in 1 until Int.MAX_VALUE
    var focused by remember { mutableStateOf(false) }
    val thumb = if (focused) Palette.accent else Palette.dim
    Box(
        modifier.fillMaxWidth()
            .border(1.dp, if (focused) Palette.accent else Color.Transparent, RoundedCornerShape(8.dp))
            .drawWithContent {
                drawContent()
                val range = scroll.maxValue
                if (range in 1 until Int.MAX_VALUE && size.height > 0f) {
                    val thumbHeight = (size.height * size.height / (size.height + range))
                        .coerceIn(minOf(16.dp.toPx(), size.height), size.height)
                    val y = (size.height - thumbHeight) * (scroll.value.toFloat() / range)
                    drawRoundRect(
                        thumb, Offset(size.width - 6.dp.toPx(), y),
                        Size(3.dp.toPx(), thumbHeight), CornerRadius(2.dp.toPx()),
                    )
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when {
                    event.key == Key.DirectionUp && scroll.canScrollBackward -> -step
                    event.key == Key.DirectionDown && scroll.canScrollForward -> step
                    else -> return@onPreviewKeyEvent false
                }
                scope.launch { scroll.scrollBy(delta) }
                true
            }
            .focusable(enabled = overflows)
            .verticalScroll(scroll),
    ) {
        Text(
            text, modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
            fontSize = Type.body, color = Palette.text, lineHeight = 22.sp,
        )
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
