package dev.relay.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SyncSealTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val other = ByteArray(32) { (it * 5 + 3).toByte() }
    private val nonce = ByteArray(12) { (it + 40).toByte() }
    private val plain = """{"app":"relay-sync","format":1,"recs":{"a":{"at":1,"dev":"x","v":"hello"}}}""".toByteArray()

    private fun fail(blob: ByteArray, k: ByteArray = key): SealException {
        try {
            SyncSeal.open(blob, k)
        } catch (e: SealException) {
            return e
        }
        fail("should not open")
        throw IllegalStateException()
    }

    @Test fun roundTrip() {
        assertArrayEquals(plain, SyncSeal.open(SyncSeal.seal(plain, key), key))
    }

    @Test fun emptyAndLargeRoundTrip() {
        assertArrayEquals(ByteArray(0), SyncSeal.open(SyncSeal.seal(ByteArray(0), key), key))
        val big = ByteArray(3_000_000) { (it % 251).toByte() }
        assertArrayEquals(big, SyncSeal.open(SyncSeal.seal(big, key), key))
    }

    @Test fun eachWriteUsesAFreshNonce() {
        val a = SyncSeal.seal(plain, key)
        val b = SyncSeal.seal(plain, key)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, SyncSeal.open(a, key))
        assertArrayEquals(plain, SyncSeal.open(b, key))
    }

    @Test fun sameNonceGivesSameBytes() {
        assertArrayEquals(SyncSeal.seal(plain, key, nonce), SyncSeal.seal(plain, key, nonce))
    }

    @Test fun layoutIsMagicVersionNonce() {
        val blob = SyncSeal.seal(plain, key, nonce)
        assertEquals("RLYS", String(blob, 0, 4))
        assertEquals(1, blob[4].toInt())
        assertArrayEquals(nonce, blob.copyOfRange(5, 17))
    }

    /** A file built here with plain javax.crypto, so the format is pinned by something other than the code under test. */
    @Test fun opensAFileBuiltByHand() {
        val zipped = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(plain) } }.toByteArray()
        val header = "RLYS".toByteArray() + byteArrayOf(1) + nonce
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        val blob = header + c.doFinal(zipped)
        assertArrayEquals(plain, SyncSeal.open(blob, key))
    }

    @Test fun contentIsNotReadable() {
        val blob = SyncSeal.seal("find-me-in-the-file".toByteArray(), key)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("find-me"))
    }

    @Test fun wrongKeyIsReportedAsWrongKey() {
        val e = fail(SyncSeal.seal(plain, key), other)
        assertTrue(e.wrongKey)
    }

    @Test fun shortKeyIsWrongKey() {
        assertTrue(fail(SyncSeal.seal(plain, key), ByteArray(5)).wrongKey)
    }

    @Test fun truncatedFileIsRefused() {
        val blob = SyncSeal.seal(plain, key)
        for (cut in listOf(0, 3, 10, 17, 20, blob.size - 1, blob.size - 16)) {
            fail(blob.copyOf(cut))
        }
    }

    @Test fun flippedByteIsRefusedAnywhere() {
        val blob = SyncSeal.seal(plain, key)
        for (i in blob.indices step 3) {
            val bad = blob.copyOf().also { it[i] = (it[i].toInt() xor 0x55).toByte() }
            fail(bad)
        }
    }

    @Test fun otherFileIsNotRelay() {
        val e = fail("PK-this-is-a-zip-file-or-something-else-entirely".toByteArray())
        assertFalse(e.wrongKey)
    }

    @Test fun newerVersionAsksForUpdate() {
        val blob = SyncSeal.seal(plain, key).also { it[4] = 2 }
        val e = fail(blob)
        assertTrue(e.message!!.contains("newer Relay"))
        assertFalse(e.wrongKey)
    }

    @Test fun validEncryptionOfNonsenseIsDamagedNotWrongKey() {
        val header = "RLYS".toByteArray() + byteArrayOf(1) + nonce
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        val e = fail(header + c.doFinal("not gzip at all".toByteArray()))
        assertFalse(e.wrongKey)
    }

    @Test fun keyTextRoundTrip() {
        val k = SyncSeal.newKey()
        assertEquals(32, k.size)
        val text = SyncSeal.keyToText(k)
        assertEquals(64, text.length)
        assertArrayEquals(k, SyncSeal.keyFromText(text))
        assertArrayEquals(k, SyncSeal.keyFromText("  ${text.uppercase()}\n"))
    }

    @Test fun badKeyTextIsRefused() {
        assertNull(SyncSeal.keyFromText(""))
        assertNull(SyncSeal.keyFromText("abcd"))
        assertNull(SyncSeal.keyFromText("z".repeat(64)))
        assertNull(SyncSeal.keyFromText("0".repeat(63)))
        assertNotNull(SyncSeal.keyFromText("0".repeat(64)))
    }

    @Test fun newKeysDiffer() {
        assertFalse(SyncSeal.newKey().contentEquals(SyncSeal.newKey()))
    }
}
