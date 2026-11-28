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
import dev.relay.tv.data.TrailerApps

/**
 * Trailers play in a YouTube app, not inside Relay: Relay's own player cannot play YouTube (extracting its
 * streams is against YouTube's terms and breaks every few months). The app is chosen by [TrailerApps]:
 * SmartTube, then Morphe, then ReVanced, then YouTube, on TVs and phones alike. With none of them the
 * caller shows [TrailerQrDialog]. Back from the app returns to the page that called.
 */
object TrailerLauncher {

    /** Installed apps that are trusted YouTube clients, best first. */
    fun apps(ctx: Context, url: String): List<String> {
        val pm = ctx.packageManager
        val handlers = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(url)), 0).map { it.activityInfo.packageName }
        } catch (_: Exception) {
            emptyList()
        }
        val installed = TrailerApps.KNOWN.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
        val all = (handlers + installed).distinct()
        val out = ArrayList<String>()
        var left = all.filter { TrailerApps.familyOf(it) != null }
        while (true) {
            val next = TrailerApps.pick(left) ?: break
            out += next
            left = left - next
        }
        return out
    }

    /**
     * Opens [url] in the best installed YouTube app. Returns false when none could open it, so the
     * caller can show [TrailerQrDialog].
     */
    fun open(ctx: Context, url: String, what: String): Boolean {
        for (pkg in apps(ctx, url)) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                AppLog.i("Trailer", "Opened in $pkg for $what")
                return true
            } catch (e: Exception) {
                AppLog.w("Trailer", "$pkg couldn't open the trailer for $what", e)
            }
        }
        AppLog.i("Trailer", "No YouTube app here for $what; showing the QR code")
        return false
    }
}

/** The trailer's YouTube address as a QR code, to watch it on a phone, when this device has no YouTube app. */
@Composable
fun TrailerQrDialog(url: String, onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    val tv = rememberIsTv()
    InitialFocus(close)
    TvDialog(onDismiss, width = 560.dp) {
        Text("Scan to watch the trailer on your phone", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            if (tv) "This TV has no YouTube app (SmartTube, Morphe, ReVanced or YouTube). Point your phone's camera at the code to watch it there."
            else "This device has no app that can open YouTube. Scan the code with another phone to watch it there.",
            fontSize = Type.small, color = Palette.dim,
        )
        QrCode(url, if (tv) 220.dp else 160.dp)
        PillButton("Close", onDismiss, focusRequester = close, key = "trailer:close")
    }
}
