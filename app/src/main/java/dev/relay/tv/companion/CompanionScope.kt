package dev.relay.tv.companion

/**
 * What one phone page may do. Each screen that shows a QR code opens the page for its own job only:
 * the window for sending an API key can't be used to restore a backup or change the login. Only the
 * full phone companion (Settings, behind the parent PIN when one is set) serves everything.
 */
enum class CompanionScope {
    /** Settings → Backup & phone → Phone companion. */
    FULL,

    /** The sign-in screen. */
    LOGIN,

    /** Send API keys from my phone. */
    KEYS,

    /** Turn on sync from your phone. */
    SYNC,

    /** A screen waiting to join sync. */
    JOIN,
    ;

    /** Whether this page serves [action] (the path after the one-time code) with [method]. */
    fun allows(method: String, action: String): Boolean {
        if (method == "GET" && action.isEmpty()) return true
        return when (this) {
            FULL -> true
            LOGIN -> method == "POST" && action == "login"
            KEYS -> method == "POST" && action == "keys"
            SYNC -> method == "POST" && action == "sync"
            JOIN -> method == "POST" && action == "join"
        }
    }

    /** Whether the `/pair/` paths, which sit outside the one-time code, are open. */
    val pairs: Boolean get() = this == JOIN || this == FULL

    /** The sections the page shows. */
    val showsLogin: Boolean get() = this == FULL || this == LOGIN
    val showsKeys: Boolean get() = this == FULL || this == KEYS
    val showsSync: Boolean get() = this == FULL || this == SYNC
    val showsJoin: Boolean get() = this == JOIN || this == FULL
    val showsRest: Boolean get() = this == FULL
}
