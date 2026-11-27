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
        if (!restrict || req.vetted) return req
        val kept = req.items.filter(::allows)
        val index = kept.indexOf(chosen).coerceAtLeast(0)
        // Fallback versions of a channel must pass too.
        val clean = kept.map { item ->
            if (item.alternates.isEmpty()) item
            else item.copy(alternates = item.alternates.filter { a -> allowsCategory(a.toIntOrNull()?.let(channelCat)) })
        }
        return req.copy(items = clean, index = index)
    }

    /**
     * Stream ids to try when a channel fails: its other versions, in the order given, minus any
     * this profile can't see (a kids profile must never fall back into an adult category).
     */
    fun fallbackIds(channelId: Int, versions: List<Channel>): List<String> =
        versions.filter { it.id != channelId && allowsCategory(it.categoryId) }.map { it.id.toString() }

    /** Channel-number lookup across the whole catalog, skipping channels this profile can't see. */
    fun findByNumber(channels: List<Channel>, num: Int): Channel? =
        channels.firstOrNull { it.num == num && allowsCategory(it.categoryId) }

    companion object {
        val NONE = Restrictions(false, emptySet(), { null }, { null }, { null })
    }
}

/** A parent approval applies to one picker visit and the PIN that was checked then. */
data class ProfileSwitchLock(val profileId: String, val parentPin: String) {
    companion object {
        fun forProfile(profile: Profile?, parentPin: String?): ProfileSwitchLock? =
            if (profile?.kids == true && !parentPin.isNullOrEmpty()) ProfileSwitchLock(profile.id, parentPin) else null
    }
}

/**
 * Parent PIN, stored as a salted hash. [verify] accepts both the legacy `salt:sha256` format (kept
 * on write so screens still on 1.0.135 in a synced household can verify it) and `v2$iter$salt$dk`
 * PBKDF2-HMAC-SHA256 hashes.
 */
object ParentPin {
    private const val V2_PREFIX = "v2\$"
    private const val V2_ITERATIONS = 60_000

    fun hash(pin: String, salt: String = newSalt()): String = "$salt:${sha256("$salt:$pin")}"

    fun hashV2(pin: String, salt: String = newSalt(), iterations: Int = V2_ITERATIONS): String =
        "${V2_PREFIX}${iterations}\$${salt}\$${pbkdf2(pin, salt, iterations)}"

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty() || pin.isEmpty()) return false
        if (stored.startsWith(V2_PREFIX)) {
            val parts = stored.removePrefix(V2_PREFIX).split('$')
            if (parts.size != 3) return false
            val iter = parts[0].toIntOrNull()?.takeIf { it in 1_000..500_000 } ?: return false
            val salt = parts[1]
            val expected = parts[2]
            if (salt.isEmpty() || expected.isEmpty()) return false
            val actual = pbkdf2(pin, salt, iter)
            return MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        }
        val salt = stored.substringBefore(':', "")
        if (salt.isEmpty()) return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(Charsets.UTF_8), stored.toByteArray(Charsets.UTF_8))
    }

    fun isValid(pin: String) = pin.length in 4..8 && pin.all(Char::isDigit)

    private fun newSalt(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun pbkdf2(pin: String, salt: String, iterations: Int): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, 256)
        val bytes = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
