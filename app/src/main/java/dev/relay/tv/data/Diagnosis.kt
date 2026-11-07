package dev.relay.tv.data

/**
 * Turns a playback failure plus a quick account check into a plain-language reason and a next
 * step. Pure, so every case is covered by tests.
 */
object PlaybackDiagnosis {

    data class Result(val title: String, val hint: String)

    sealed interface Login {
        data class Ok(val info: AccountInfo, val nowSec: Long) : Login
        data class Rejected(val message: String) : Login
        data object Unreachable : Login
    }

    /**
     * [httpCode] is the stream's HTTP status if there was one; [error] is the player's error
     * name (or "STALL" when the stream stopped sending data).
     */
    fun diagnose(httpCode: Int?, error: String, login: Login, live: Boolean): Result {
        when (login) {
            Login.Unreachable -> return Result(
                "Can't reach your provider",
                "Check this device's internet connection or VPN, then press Retry.",
            )
            is Login.Rejected -> return Result(
                "Your login was rejected",
                "Check the username and password in Settings → Account → Change servers or login.",
            )
            is Login.Ok -> {
                val i = login.info
                val status = i.status?.trim().orEmpty()
                if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
                    return Result("Your subscription is $status", "Contact your provider.")
                }
                val exp = i.expires
                if (exp != null && exp in 1 until login.nowSec) {
                    return Result("Your subscription has expired", "Renew it with your provider.")
                }
                val max = i.maxConnections ?: 0
                val active = i.activeConnections ?: 0
                if (max > 0 && active >= max) {
                    return Result(
                        "All your streams are in use ($active of $max)",
                        "Stop playback on another device, then press Retry.",
                    )
                }
            }
        }
        val e = error.uppercase()
        return when {
            httpCode == 401 || httpCode == 403 || httpCode == 458 -> Result(
                "The server refused this stream",
                "This usually means your stream limit was reached a moment ago. Wait a few seconds and press Retry.",
            )
            httpCode == 404 || httpCode == 410 -> Result(
                if (live) "This channel isn't available right now" else "This video isn't available",
                "The provider may have moved or removed it. Try again later" + if (live) " or pick another channel." else ".",
            )
            httpCode != null && httpCode >= 500 -> Result(
                "The provider's server has a problem (error $httpCode)",
                "Press Retry in a minute; Relay also tries your backup servers.",
            )
            "DECOD" in e || "FORMAT" in e || "PARSING" in e || "UNSUPPORTED" in e -> Result(
                "This device can't play this format",
                if (live) "Try Settings → Playback → Live stream format (MPEG-TS or HLS)." else "Try another version of this title if there is one.",
            )
            "TIMEOUT" in e || "STALL" in e || "NETWORK" in e || "IO_" in e -> Result(
                if (live) "This channel isn't responding" else "This video isn't responding",
                "The provider's server is busy or the stream stopped. Press Retry" + if (live) ", or Next channel." else ".",
            )
            else -> Result(
                if (live) "This channel isn't responding" else "Can't play this video",
                "Press Retry. If it keeps happening, share logs from Settings → Diagnostics.",
            )
        }
    }
}
