package dev.relay.tv.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.relay.tv.data.AppLog

object LogShare {
    /** Opens the share sheet with a log bundle. Returns a status message for the UI. */
    fun share(ctx: Context): String = try {
        val file = AppLog.bundle(ctx)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Relay logs")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (send.resolveActivity(ctx.packageManager) == null) throw IllegalStateException("no share targets")
        ctx.startActivity(Intent.createChooser(send, "Share Relay logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Log bundle ready (${file.length() / 1024} KB)."
    } catch (e: Exception) {
        // Most TVs have no share targets; adb can still pull the folder shown below.
        "No app here can receive the log. From a computer: adb pull ${AppLog.directory?.absolutePath ?: "<log folder>"}"
    }
}

@Composable
fun LogViewer(onDismiss: () -> Unit) {
    val lines = remember { AppLog.recent().asReversed() }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss, width = 980.dp) {
        Text("Recent activity", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Newest first. Passwords, usernames and tokens are hidden. Important lines are kept across restarts; Save detailed logs keeps everything.",
            fontSize = Type.caption, color = Palette.dim,
        )
        LazyColumn(
            Modifier.fillMaxWidth().height(420.dp).background(Palette.bg, RoundedCornerShape(8.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (lines.isEmpty()) {
                item { Text("Nothing logged yet.", Modifier.padding(12.dp), color = Palette.dim) }
            }
            items(lines.size) { i ->
                val line = lines[i]
                Focusable(
                    {}, Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp), scaleOnFocus = 1f,
                    focusRequester = if (i == 0) first else null,
                ) { f ->
                    Text(
                        line,
                        Modifier.fillMaxWidth().background(if (f) Palette.surface2 else Palette.bg).padding(horizontal = 10.dp, vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = Type.label,
                        color = when {
                            " E/" in line -> Palette.danger
                            " W/" in line -> Palette.accent2
                            else -> Palette.text
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Close", onDismiss, focusRequester = if (lines.isEmpty()) first else null)
        }
    }
}

/**
 * What Relay recognised in the library: languages per channel, movie and series, the title tags
 * and categories it couldn't place, and how long the library took to load. Sharing it lets the
 * language rules be tuned to this provider's naming. No login or history is included.
 */
@Composable
fun LibraryReportDialog(onDismiss: () -> Unit) {
    val app = dev.relay.tv.RelayApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("Working it out…") }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            app.catalog.loadSummary() + "\n\n" + app.catalog.languageReport().text()
        }
    }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss, width = 900.dp) {
        Text("Library and language report", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Counts only: no login, history or titles you watched. Add it to the log, then use Share logs (on a TV, Download logs on the phone companion).",
            fontSize = Type.caption, color = Palette.dim,
        )
        LazyColumn(
            Modifier.fillMaxWidth().height(380.dp).background(Palette.bg, RoundedCornerShape(8.dp)).padding(10.dp),
        ) {
            val lines = text.lines()
            items(lines.size) { i ->
                Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = Type.label, color = Palette.text)
            }
        }
        note?.let { Text(it, fontSize = Type.caption, color = Palette.accent) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Add to log", {
                text.lines().forEach { AppLog.i("Report", it) }
                note = "Added. Settings → Diagnostics → Share logs sends it."
            }, primary = true, focusRequester = first)
            PillButton("Share", {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Relay library report").putExtra(Intent.EXTRA_TEXT, text)
                note = if (send.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    null
                } else {
                    "Nothing on this device can receive it. Use Add to log, then adb pull the log folder."
                }
            })
            PillButton("Close", onDismiss)
        }
    }
}
