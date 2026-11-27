package dev.relay.tv.sync

import dev.relay.tv.data.SyncSeal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PairingTest {
    private val token = "github_pat_11MADEUP0abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val key = SyncSeal.keyToText(ByteArray(32) { (it * 3 + 1).toByte() })
    private val bundle = PairBundle("me/relay-sync", token, key)

    // ---------- the text bundle ----------

    @Test fun roundTrip() {
        val text = Pairing.encode(bundle)
        assertTrue(text.startsWith("relay-pair:"))
        val back = Pairing.decode(text)!!
        assertEquals("me/relay-sync", back.repo)
        assertEquals(token, back.token)
        assertEquals(key, back.key)
    }

    @Test fun theTextHasNothingReadableInIt() {
        val text = Pairing.encode(bundle)
        assertFalse(text.contains(token))
        assertFalse(text.contains(key))
        assertTrue(text.all { it.isLetterOrDigit() || it in "-_:" })
    }

    @Test fun pasteHabitsAreForgiven() {
        val text = Pairing.encode(bundle)
        assertNotNull(Pairing.decode("  $text\n"))
        assertNotNull(Pairing.decode(text.removePrefix("relay-pair:")))
        assertNotNull(Pairing.decode(text.uppercase().replace(text.removePrefix("relay-pair:").uppercase(), text.removePrefix("relay-pair:"))))
        assertNotNull(Pairing.decode(text.chunked(30).joinToString("\n")))
    }

    @Test fun aDamagedLinkIsRefused() {
        val text = Pairing.encode(bundle)
        val body = text.removePrefix("relay-pair:")
        assertNull(Pairing.decode(""))
        assertNull(Pairing.decode("relay-pair:"))
        assertNull(Pairing.decode("hello"))
        assertNull(Pairing.decode("relay-pair:" + body.dropLast(10)))
        for (i in body.indices step 7) {
            val c = body[i]
            val swapped = if (c == 'A') 'B' else 'A'
            assertNull("position $i", Pairing.decode("relay-pair:" + body.substring(0, i) + swapped + body.substring(i + 1)))
        }
    }

    @Test fun unusableContentIsRefusedEvenWithAGoodChecksum() {
        assertNull(Pairing.decode(Pairing.encode(PairBundle("nonsense", token, key))))
        assertNull(Pairing.decode(Pairing.encode(PairBundle("me/relay-sync", "short", key))))
        assertNull(Pairing.decode(Pairing.encode(PairBundle("me/relay-sync", token, "00ff"))))
    }

    @Test fun printingABundleNeverShowsTheSecrets() {
        val s = bundle.toString()
        assertFalse(s.contains(token))
        assertFalse(s.contains(key))
    }

    @Test fun urlSafeBase64() {
        val rnd = Random(5)
        for (len in 0..60) {
            val b = ByteArray(len).also { rnd.nextBytes(it) }
            val t = Base64Lite.encodeUrl(b)
            assertTrue(t.none { it == '+' || it == '/' || it == '=' })
            assertArrayEquals(b, Base64Lite.decodeUrl(t))
            assertArrayEquals(b, Base64Lite.decodeUrl(Base64Lite.encode(b)))
        }
        assertNull(Base64Lite.decodeUrl("a"))
        assertNull(Base64Lite.decodeUrl("ab!d"))
    }

    // ---------- the handshake ----------

    @Test fun bothSidesShowTheSameCodeAndShareAKey() {
        val newScreen = PairSession.create()
        val oldScreen = PairSession.create()
        val a = newScreen.agree(oldScreen.publicText)!!
        val b = oldScreen.agree(newScreen.publicText)!!
        assertEquals(a.code, b.code)
        assertTrue(a.code, Regex("[0-9]{6}").matches(a.code))
        val sealed = b.seal("the bundle".toByteArray())
        assertEquals("the bundle", String(a.open(sealed)!!))
    }

    @Test fun codesAlwaysHaveSixDigits() {
        repeat(40) {
            val a = PairSession.create()
            val b = PairSession.create()
            val code = a.agree(b.publicText)!!.code
            assertEquals(6, code.length)
            assertEquals(code, b.agree(a.publicText)!!.code)
        }
    }

    @Test fun twoPairingsGiveDifferentCodesAndKeys() {
        val a = PairSession.create()
        val codes = (1..20).map { a.agree(PairSession.create().publicText)!!.code }.toSet()
        assertTrue(codes.size > 10)
    }

    @Test fun aManInTheMiddleSeesDifferentCodesOnEachSide() {
        val newScreen = PairSession.create()
        val oldScreen = PairSession.create()
        val mallory = PairSession.create()
        // The old screen is tricked into agreeing with Mallory; the new screen agrees with Mallory too.
        val oldSees = oldScreen.agree(mallory.publicText)!!.code
        val newSees = newScreen.agree(mallory.publicText)!!.code
        assertNotEquals(oldSees, newSees)
        // And Mallory cannot read what the genuine pair share.
        val real = oldScreen.agree(newScreen.publicText)!!
        val stolen = mallory.agree(newScreen.publicText)!!
        assertNull(stolen.open(real.seal("secret".toByteArray())))
    }

    @Test fun aSealedBundleFromAnotherAgreementIsRefused() {
        val a = PairSession.create()
        val b = PairSession.create()
        val c = PairSession.create()
        val sealed = a.agree(b.publicText)!!.seal("x".toByteArray())
        assertNull(c.agree(b.publicText)!!.open(sealed))
        assertNull(b.agree(a.publicText)!!.open(sealed.copyOf(sealed.size - 3)))
        assertNull(b.agree(a.publicText)!!.open(ByteArray(0)))
    }

    @Test fun anInvalidPublicKeyIsRefused() {
        val a = PairSession.create()
        assertNull(a.agree(""))
        assertNull(a.agree("not a key"))
        assertNull(a.agree(Base64Lite.encodeUrl(ByteArray(91) { 1 })))
    }

    @Test fun theBundleTravelsEndToEnd() {
        val newScreen = PairSession.create()
        val oldScreen = PairSession.create()
        val sealed = oldScreen.agree(newScreen.publicText)!!.seal(Pairing.encode(bundle).toByteArray())
        val got = Pairing.decode(String(newScreen.agree(oldScreen.publicText)!!.open(sealed)!!))!!
        assertEquals(token, got.token)
    }

    @Test fun pairGateRefusesConcurrentHelloWhileCodeIsOnScreen() {
        var now = 1_000L
        val gate = PairGate(clock = { now })
        val genuine = PairSession.create()
        val intruder = PairSession.create()

        val first = gate.hello(genuine.publicText)!!
        // Another device on the LAN cannot swap the code or reset tries while the 60 s window is live.
        assertNull(gate.hello(intruder.publicText))
        // The same peer retrying its own hello gets the same agreement.
        assertEquals(first.code, gate.hello(genuine.publicText)!!.code)

        // Five bad bundles exhaust the session; a sixth (even genuine) is refused until expiry or reset.
        repeat(PairGate.MAX_TRIES) {
            assertNull(gate.openBundle(ByteArray(32) { 7 }))
        }
        val genuineSealed = genuine.agree(first.replyPublicKey)!!.seal(Pairing.encode(bundle).toByteArray())
        assertNull(gate.openBundle(genuineSealed))

        // Once attempts are exhausted (or after HELLO_LOCK_MS), a fresh hello is allowed again.
        now += PairGate.HELLO_LOCK_MS + 1
        val second = gate.hello(intruder.publicText)!!
        val intruderSealed = intruder.agree(second.replyPublicKey)!!.seal(Pairing.encode(bundle).toByteArray())
        assertEquals("me/relay-sync", gate.openBundle(intruderSealed)!!.repo)
    }
}
