package dev.relay.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.relay.tv.companion.QrCode
import dev.relay.tv.data.AppLog

/**
 * Trailers play in the YouTube app, not inside Relay: Relay's own player cannot play YouTube
 * (extracting its streams is against YouTube's terms and breaks every few months), and a web page
 * inside the app handles a remote control badly and is refused by many trailers.
 */
object TrailerLauncher {

    /**
     * Hands [url] to whichever app handles YouTube addresses. Returns false when nothing could open
     * it, so the caller can show [TrailerFallbackDialog]. Back from YouTube returns to the page that called.
     */
    fun open(ctx: Context, url: String, what: String): Boolean =
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            AppLog.i("Trailer", "Opened for $what")
            true
        } catch (e: Exception) {
            // ActivityNotFoundException on a TV without YouTube; anything else is treated the same way.
            AppLog.w("Trailer", "No app could open the trailer for $what", e)
            false
        }
}

/** Shown when no app on the device can open YouTube: the address as a QR code for a phone. */
@Composable
fun TrailerFallbackDialog(url: String, onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    InitialFocus(close)
    TvDialog(onDismiss, width = 520.dp) {
        Text("Watch the trailer on your phone", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "This device has no app that can open YouTube. Scan the code to watch it on your phone.",
            fontSize = Type.small, color = Palette.dim,
        )
        QrCode(url, 160.dp)
        PillButton("Close", onDismiss, focusRequester = close)
    }
}
