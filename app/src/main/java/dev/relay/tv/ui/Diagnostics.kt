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
        Text("Recent activity", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Newest first. Passwords, usernames and tokens are hidden. Turn on Save detailed logs to keep them across restarts.",
            fontSize = 13.sp, color = Palette.dim,
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
                        fontSize = 12.sp,
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
