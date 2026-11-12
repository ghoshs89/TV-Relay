package dev.relay.tv.sync

import dev.relay.tv.data.Jsons
import dev.relay.tv.data.SyncSeal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** Everything a new screen needs to join: where the file is, the token for it, and the key that opens it. */
class PairBundle(val repo: String, val token: String, val key: String) {
    override fun toString() = "PairBundle(repo=$repo)" // never prints the token or key
}

/**
 * The pairing bundle as one line of text, `relay-pair:<...>`, with a version number and a checksum so
 * a link damaged on the way (cut off, a character changed) is refused rather than half-used.
 */
object Pairing {
    const val PREFIX = "relay-pair:"
    private const val VERSION = 1

    private fun sum(repo: String, token: String, key: String): String =
        MessageDigest.getInstance("SHA-256").digest("relay-pair|$VERSION|$repo|$token|$key".toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }

    fun encode(b: PairBundle): String {
        val o = JsonObject(
            mapOf(
                "v" to JsonPrimitive(VERSION), "repo" to JsonPrimitive(b.repo), "token" to JsonPrimitive(b.token),
                "key" to JsonPrimitive(b.key), "sum" to JsonPrimitive(sum(b.repo, b.token, b.key)),
            ),
        )
        return PREFIX + Base64Lite.encodeUrl(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** The bundle in [text], or null if it is damaged, from another version, or does not hold a usable repository, token and key. */
    fun decode(text: String): PairBundle? {
        var t = text.filter { !it.isWhitespace() }
        if (t.startsWith(PREFIX, ignoreCase = true)) t = t.substring(PREFIX.length)
        val bytes = Base64Lite.decodeUrl(t) ?: return null
        val o = try {
            Jsons.json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((o["v"] as? JsonPrimitive)?.content?.toIntOrNull() != VERSION) return null
        val repo = str("repo") ?: return null
        val token = str("token") ?: return null
        val key = str("key") ?: return null
        if (str("sum") != sum(repo, token, key)) return null
        if (Repo.parse(repo) == null || SyncInput.token(token) == null || SyncSeal.keyFromText(key) == null) return null
        return PairBundle(repo, token, key)
    }
}

/**
 * One side of the pairing handshake. Each screen makes a fresh key pair, they swap public keys, and
 * both work out the same shared secret (ECDH) that nobody listening on the network can. From it each
 * side derives a 6-digit code to show and a key to seal the bundle with. Because the code depends on
 * both public keys, someone sitting between the two screens would make them show different codes, which
 * the person comparing them sees. Needs only `java.security` and `javax.crypto` (Android 7 has them).
 */
class PairSession private constructor(private val pair: KeyPair) {

    /** This side's public key as text, to send to the other screen. */
    val publicText: String = Base64Lite.encodeUrl(pair.public.encoded)

    /** The agreement with the other side's public key, or null if that key is not a valid one. */
    fun agree(theirPublic: String): PairAgreement? {
        val theirs = Base64Lite.decodeUrl(theirPublic.trim()) ?: return null
        return try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(pair.private)
            ka.doPhase(pub, true)
            val secret = ka.generateSecret()
            // Same order on both sides, whichever is "mine".
            val keys = listOf(publicText, Base64Lite.encodeUrl(theirs)).sorted()
            PairAgreement(secret, keys[0] + "|" + keys[1])
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun create(): PairSession {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            return PairSession(g.generateKeyPair())
        }
    }
}

class PairAgreement internal constructor(private val secret: ByteArray, private val transcript: String) {
    private fun derive(label: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update("relay-pair-$label|$transcript|".toByteArray(Charsets.UTF_8))
            digest(secret)
        }

    /** Six digits both screens show; the person checks they are the same. */
    val code: String by lazy {
        val d = derive("code")
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        "%06d".format(n % 1_000_000L)
    }

    private val key: ByteArray by lazy { derive("key") }

    fun seal(plain: ByteArray): ByteArray = SyncSeal.seal(plain, key)

    /** The text sealed by the other side, or null if it was not sealed with this agreement. */
    fun open(blob: ByteArray): ByteArray? = try {
        SyncSeal.open(blob, key)
    } catch (e: Exception) {
        null
    }
}
