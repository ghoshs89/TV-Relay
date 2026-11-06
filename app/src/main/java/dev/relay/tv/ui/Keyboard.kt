package dev.relay.tv.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp

/** True on Android TV / Google TV. */
@Composable
fun rememberIsTv(): Boolean {
    val ctx = LocalContext.current
    return remember {
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }
}

/** Settings → Keyboard: "auto" = Relay's keyboard on TVs, the system keyboard on phones. */
@Composable
fun useRelayKeyboard(): Boolean {
    val store = RelayApp.instance.store
    store.version.intValue
    val tv = rememberIsTv()
    return when (store.settings.keyboard) {
        "relay" -> true
        "system" -> false
        else -> tv
    }
}

private val LETTERS = listOf("1234567890", "abcdefghij", "klmnopqrst", "uvwxyz.-_@")
private val SYMBOLS = listOf("!?:/&=+%#*", ";,()[]'\"$~", "^|\\<>{}`€£")
private val URL_KEYS = listOf("http://", "https://", "www.", ".com", ".net", ".tv")

/**
 * D-pad friendly keyboard drawn by Relay. Letters are alphabetical (quicker to find with a
 * remote than QWERTY). A hardware keyboard or the remote's number keys also type into it.
 */
@Composable
fun RelayKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester? = null,
    allowVoice: Boolean = true,
    keyHeight: Dp = 42.dp,
    doneLabel: String = "Done",
) {
    val ctx = LocalContext.current
    var symbols by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)

    val voiceAvailable = remember {
        allowVoice && ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        change(if (current.isBlank()) heard else "${current.trimEnd()} $heard")
    }

    fun type(s: String) = change(current + s)

    Column(
        modifier.onPreviewKeyEvent { e ->
            // Typing on a hardware keyboard or the remote's digits goes straight in.
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (e.key == Key.Backspace || e.key == Key.Delete) {
                change(current.dropLast(1))
                return@onPreviewKeyEvent true
            }
            val ch = e.nativeKeyEvent.unicodeChar
            if (ch > 0 && !Character.isISOControl(ch)) {
                change(current + ch.toChar())
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val rows = if (symbols) SYMBOLS else LETTERS
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, c ->
                    val label = if (caps && !symbols) c.uppercaseChar().toString() else c.toString()
                    Key(
                        label, { type(label) }, keyHeight,
                        focusRequester = if (r == (if (symbols) 0 else 1) && i == 0) firstKey else null,
                    )
                }
            }
        }
        if (symbols) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                URL_KEYS.forEach { k -> Key(k, { type(k) }, keyHeight, small = true) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!symbols) Key(if (caps) "abc" else "ABC", { caps = !caps }, keyHeight, weight = 1.3f, small = true)
            Key(if (symbols) "abc" else "#+=", { symbols = !symbols }, keyHeight, weight = 1.3f, small = true)
            Key("Space", { type(" ") }, keyHeight, weight = 2.6f, small = true)
            Key("⌫", { change(current.dropLast(1)) }, keyHeight, weight = 1.2f)
            Key("Clear", { change("") }, keyHeight, weight = 1.3f, small = true)
            if (voiceAvailable) {
                Key("🎤", {
                    try {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type"),
                        )
                    } catch (_: Exception) {
                    }
                }, keyHeight, weight = 1.1f)
            }
            Key(doneLabel, onDone, keyHeight, weight = 1.6f, small = true, accent = true)
        }
    }
}

@Composable
private fun RowScope.Key(
    label: String,
    onClick: () -> Unit,
    height: Dp,
    weight: Float = 1f,
    small: Boolean = false,
    accent: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Focusable(
        onClick,
        Modifier.weight(weight).height(height),
        shape = RoundedCornerShape(8.dp),
        scaleOnFocus = 1.08f,
        focusRequester = focusRequester,
    ) { f ->
        Box(
            Modifier.fillMaxSize().background(
                when {
                    f -> Palette.accent
                    accent -> Palette.surface2.copy(alpha = 0.9f)
                    else -> Palette.surface2
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (f) Color.Black else if (accent) Palette.accent else Palette.text,
                fontSize = if (small) 13.sp else 17.sp,
                fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** A text field that opens Relay's keyboard instead of the system one. */
@Composable
fun RelayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    focusRequester: FocusRequester? = null,
    onDone: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Focusable({ open = true }, modifier, shape = RoundedCornerShape(6.dp), scaleOnFocus = 1.0f, focusRequester = focusRequester) { f ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (f) Palette.surface2 else Palette.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(label, color = if (f) Palette.accent else Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    value.isEmpty() -> "Press OK to type"
                    password -> "•".repeat(value.length)
                    else -> value
                },
                color = if (value.isEmpty()) Palette.dim else Palette.text,
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (open) {
        KeyboardDialog(
            label = label,
            value = value,
            onValueChange = onValueChange,
            password = password,
            onDone = {
                open = false
                onDone()
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun KeyboardDialog(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss, width = 780.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (password) PillButton(if (reveal) "Hide" else "Show", { reveal = !reveal })
        }
        Box(
            Modifier.fillMaxWidth().background(Palette.bg, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                (if (password && !reveal) "•".repeat(value.length) else value) + "▏",
                color = Palette.text, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        RelayKeyboard(
            value = value,
            onValueChange = onValueChange,
            onDone = onDone,
            firstKey = first,
            allowVoice = !password,
        )
    }
}
