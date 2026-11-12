package dev.relay.tv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The sync file could not be opened. [wrongKey] is true when the key is the likely reason. */
class SealException(message: String, val wrongKey: Boolean = false) : Exception(message)

/**
 * The sync file as it is kept in the store: compressed, then encrypted with AES-256-GCM using a key
 * that exists only on the household's own screens. The store therefore holds nothing readable. A new
 * random nonce is used for every write; the header is authenticated too, so a file cannot be passed
 * off as another version. Uses only `javax.crypto` and `java.util.zip`, which every supported Android
 * version has.
 */
object SyncSeal {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'Y'.code.toByte(), 'S'.code.toByte())
    private const val HEADER = 4 + 1 + NONCE_BYTES

    /** Largest document that will be opened, so a damaged file cannot use all the memory. */
    private const val MAX_PLAIN = 24 * 1024 * 1024

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun keyToText(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }

    /** The key written by [keyToText], or null if the text is not exactly a 256-bit key in hex. */
    fun keyFromText(text: String): ByteArray? {
        val t = text.trim().lowercase()
        if (t.length != KEY_BYTES * 2) return null
        val out = ByteArray(KEY_BYTES)
        for (i in 0 until KEY_BYTES) {
            val hi = Character.digit(t[i * 2], 16)
            val lo = Character.digit(t[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** [plain] compressed and encrypted. [nonce] is only given by tests that check a fixed result. */
    fun seal(plain: ByteArray, key: ByteArray, nonce: ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }): ByteArray {
        require(key.size == KEY_BYTES) { "The key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "The nonce must be 12 bytes" }
        val zipped = ByteArrayOutputStream(plain.size / 4 + 64).also { bos ->
            GZIPOutputStream(bos).use { it.write(plain) }
        }.toByteArray()
        val header = MAGIC + byteArrayOf(VERSION) + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(zipped)
    }

    /** The document inside [blob]. Throws [SealException]; never returns a partial result. */
    fun open(blob: ByteArray, key: ByteArray): ByteArray {
        if (key.size != KEY_BYTES) throw SealException("This screen has no valid sync key.", wrongKey = true)
        if (blob.size < HEADER + TAG_BITS / 8) throw SealException("The sync file is damaged (too short).")
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) throw SealException("This is not a Relay sync file.")
        if (blob[4] > VERSION) throw SealException("The sync file was written by a newer Relay. Update Relay on this screen.")
        if (blob[4] < 1) throw SealException("The sync file is damaged (unknown version).")
        val header = blob.copyOfRange(0, HEADER)
        val nonce = blob.copyOfRange(5, HEADER)
        val zipped = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(blob, HEADER, blob.size - HEADER)
        } catch (e: GeneralSecurityException) {
            throw SealException("The sync file can't be opened with this screen's key. It may have been made with another key, or damaged.", wrongKey = true)
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(zipped)).use { input ->
                val out = ByteArrayOutputStream(zipped.size * 4)
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_PLAIN) throw SealException("The sync file is too large to open.")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: SealException) {
            throw e
        } catch (e: Exception) {
            throw SealException("The sync file is damaged (could not be unpacked).")
        }
    }
}
