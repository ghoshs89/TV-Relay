package dev.relay.tv.data

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the current profile may play. Every way into playback (lists, number keys, reminders,
 * Continue watching, catch-up, start on last channel) goes through [admit], so a restriction
 * can't be skipped by reaching a title some other way.
 *
 * When [restrict] is on, anything whose category is adult, or can't be identified, is blocked.
 */
class Restrictions(
    val restrict: Boolean,
    private val adultCats: Set<String>,
    private val channelCat: (Int) -> String?,
    private val movieCat: (Int) -> String?,
    private val seriesCat: (Int) -> String?,
) {
    fun allowsCategory(categoryId: String?): Boolean = !restrict || (categoryId != null && categoryId !in adultCats)

    fun allows(item: PlayItem): Boolean {
        if (!restrict) return true
        val cat = when (item.kind) {
            "live", "catchup" -> item.streamId.toIntOrNull()?.let(channelCat)
            "movie" -> item.streamId.toIntOrNull()?.let(movieCat)
            "episode" -> item.seriesId?.let(seriesCat)
            else -> null
        }
        return allowsCategory(cat)
    }

    /**
     * The request with blocked items removed, still pointing at the chosen item; null when the
     * chosen item itself is blocked.
     */
    fun admit(req: PlayRequest): PlayRequest? {
        val chosen = req.items.getOrNull(req.index) ?: return null
        if (!allows(chosen)) return null
        if (!restrict) return req
        val kept = req.items.filter(::allows)
        return req.copy(items = kept, index = kept.indexOf(chosen).coerceAtLeast(0))
    }

    /** Channel-number lookup across the whole catalog, skipping channels this profile can't see. */
    fun findByNumber(channels: List<Channel>, num: Int): Channel? =
        channels.firstOrNull { it.num == num && allowsCategory(it.categoryId) }

    companion object {
        val NONE = Restrictions(false, emptySet(), { null }, { null }, { null })
    }
}

/** Parent PIN, stored as a salted hash. */
object ParentPin {
    fun hash(pin: String, salt: String = newSalt()): String = "$salt:${sha256("$salt:$pin")}"

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty() || pin.isEmpty()) return false
        val salt = stored.substringBefore(':', "")
        if (salt.isEmpty()) return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), stored.toByteArray())
    }

    fun isValid(pin: String) = pin.length in 4..8 && pin.all(Char::isDigit)

    private fun newSalt(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
