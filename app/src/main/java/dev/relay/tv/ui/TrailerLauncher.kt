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
 * Trailers play in YouTube, not inside Relay: Relay's own player cannot play YouTube (extracting its
 * streams is against YouTube's terms and breaks every few months), and a web page inside the app handles
 * a remote control badly and is refused by many trailers. On TVs the trailer is offered as a QR code for
 * a phone (see TrailerQrDialog); on phones this opens the YouTube app.
 */
object TrailerLauncher {

    /**
     * Hands [url] to whichever app handles YouTube addresses. Returns false when nothing could open
     * it, so the caller can show [TrailerQrDialog]. Back from YouTube returns to the page that called.
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

/**
 * The trailer's YouTube address as a QR code, to watch it on a phone. What TVs always show (the trailer
 * plays on the viewer's own phone, nothing opens on the TV); on a phone, only when no app can open YouTube.
 */
@Composable
fun TrailerQrDialog(url: String, onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    val tv = rememberIsTv()
    InitialFocus(close)
    TvDialog(onDismiss, width = 560.dp) {
        Text("Scan to watch the trailer on your phone", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            if (tv) "Point your phone's camera at the code to open it in YouTube."
            else "This device has no app that can open YouTube. Scan the code with another phone to watch it there.",
            fontSize = Type.small, color = Palette.dim,
        )
        QrCode(url, if (tv) 220.dp else 160.dp)
        PillButton("Close", onDismiss, focusRequester = close, key = "trailer:close")
    }
}
