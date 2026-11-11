package dev.relay.tv.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.EpgEntry
import dev.relay.tv.data.Reminder
import dev.relay.tv.player.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Programme reminders. Checked every 15 s while Relay is open (Android TV doesn't reliably show
 * notifications from closed apps). A due reminder appears once as a dialog in the app, or as a
 * banner in the player with OK to switch.
 */
object ReminderCenter {
    var due by mutableStateOf<Reminder?>(null)

    private val app get() = RelayApp.instance
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                check()
                delay(15_000)
            }
        }
    }

    private fun check() {
        val now = System.currentTimeMillis() / 1000
        val list = app.store.reminders
        if (list.isEmpty()) return
        val fire = if (due == null) list.firstOrNull { now >= it.start - 30 && now < it.start + 300 } else null
        val keep = list.filter { it != fire && it.end > now }
        if (keep.size != list.size) app.store.reminders = keep
        if (fire != null) {
            due = fire
            AppLog.i("Reminder", "Due: ${fire.title} on ${fire.channelName}")
        }
    }

    fun isSet(channelId: Int, start: Long) = app.store.reminders.any { it.channelId == channelId && it.start == start }

    /** Returns a short message for the UI. */
    fun toggle(ch: Channel, e: EpgEntry): String {
        val list = app.store.reminders
        return if (isSet(ch.id, e.start)) {
            app.store.reminders = list.filterNot { it.channelId == ch.id && it.start == e.start }
            "Reminder removed"
        } else {
            app.store.reminders = list + Reminder(ch.id, ch.name, e.title, e.start, e.end)
            AppLog.i("Reminder", "Set: ${e.title} at ${e.start}")
            "Reminder set for ${timeFmt.format(Date(e.start * 1000))}"
        }
    }

    fun label(r: Reminder): String = "${r.title} starts ${timeFmt.format(Date(r.start * 1000))} on ${r.channelName}"

    /** Switches to the reminder's channel, inside its category so Up/Down keep working. */
    fun watch(ctx: Context, r: Reminder) {
        due = null
        val ch = app.catalog.channelById[r.channelId] ?: return
        val list = app.catalog.channelsByCat[ch.categoryId].orEmpty().ifEmpty { listOf(ch) }
        Playback.live(ctx, list, list.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
    }
}

@Composable
fun ReminderDialog(r: Reminder) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss = { ReminderCenter.due = null }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Starting now", fontSize = Type.body, color = Palette.accent, fontWeight = FontWeight.SemiBold)
        }
        Text(r.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text("on ${r.channelName}", fontSize = Type.bodyLarge, color = Palette.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Watch", { ReminderCenter.watch(ctx, r) }, focusRequester = first, primary = true)
            PillButton("Dismiss", { ReminderCenter.due = null })
        }
    }
}
