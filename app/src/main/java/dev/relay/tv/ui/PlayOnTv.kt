package dev.relay.tv.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.sync.FoundScreen
import dev.relay.tv.sync.PlayCommand
import dev.relay.tv.sync.PlayOnTvClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Play on TV" is offered on phones that sync; TVs are where things are sent, not where they are sent from. */
@Composable
fun playOnTvAvailable(): Boolean = !rememberIsTv() && RelayApp.instance.sync.enabled

/**
 * Lists this household's TVs that have Relay open and sends [command] to the one chosen. [onSent] gets the
 * TV's answer ("Playing on …") once the TV has accepted it; a refusal is shown in the dialog instead.
 */
@Composable
fun PlayOnTvDialog(command: () -> PlayCommand?, onSent: (String) -> Unit, onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = app.sync.settings.config?.key
    var tvs by remember { mutableStateOf<List<FoundScreen>>(emptyList()) }
    var waited by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    DisposableEffect(key) {
        val stop = if (key != null) PlayOnTvClient.discover(ctx, key) { tvs = it } else ({})
        onDispose { stop() }
    }
    LaunchedEffect(Unit) {
        delay(6_000)
        waited = true
    }

    TvDialog(onDismiss, width = 520.dp) {
        Text("Play on TV", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        when {
            key == null -> Text("Play on TV works between screens that use sync. Turn on sync in Settings first.", color = Palette.dim, fontSize = Type.body)
            tvs.isEmpty() && !waited -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Looking for TVs…", color = Palette.dim, fontSize = Type.body)
            }
            tvs.isEmpty() -> Text(
                "No TV found. Open Relay on the TV, on the same Wi-Fi as this phone, and make sure both use sync.",
                color = Palette.dim, fontSize = Type.body,
            )
            else -> tvs.forEach { tv ->
                ListRow(
                    if (sending == tv.name) "${tv.name}  ·  sending…" else tv.name,
                    onClick = {
                        if (sending != null) return@ListRow
                        val c = command()
                        if (c == null) {
                            problem = "This can't be sent to a TV."
                            return@ListRow
                        }
                        sending = tv.name
                        problem = null
                        scope.launch {
                            val answer = withContext(Dispatchers.IO) { PlayOnTvClient.send(app.http, tv, c, key!!) }
                            sending = null
                            AppLog.i("PlayOnTv", "Sent ${c.kind} '${c.title}' to ${tv.name}: ${if (answer.ok) "accepted" else answer.message}")
                            if (answer.ok) onSent(answer.message) else problem = answer.message
                        }
                    },
                    key = "ptv:${tv.name}",
                    icon = RelayIcons.Tv,
                )
            }
        }
        problem?.let { Text(it, color = Palette.danger, fontSize = Type.small) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Cancel", onDismiss, key = "ptv:cancel")
        }
    }
}

/** A "Play on TV" button for a screen that isn't playing yet (a film page, a channel). Shows a message when sent. */
@Composable
fun rememberPlayOnTvToast(): (String) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { message: String -> Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() } }
}
