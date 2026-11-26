package dev.relay.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class LanHttpTest {
    private fun head(text: String) = LanHttp.readHead(ByteArrayInputStream(text.toByteArray()))
    private val tv: InetAddress = InetAddress.getByName("192.168.1.20")

    @Test
    fun theRequestLineAndHeadersAreRead() {
        val h = head("POST /abc/keys?x=1 HTTP/1.1\r\nHost: 192.168.1.20:8787\r\nContent-Length: 12\r\n\r\nbody")!!
        assertEquals("POST", h.method)
        assertEquals("/abc/keys", h.path)
        assertEquals("192.168.1.20:8787", h.host)
        assertEquals(12, h.length)
    }

    @Test
    fun headerNamesAreNotCaseSensitive() {
        val h = head("GET / HTTP/1.1\r\nhOsT: 192.168.1.20:8787\r\ncontent-LENGTH: 3\r\n\r\n")!!
        assertEquals("192.168.1.20:8787", h.host)
        assertEquals(3, h.length)
    }

    @Test
    fun aNegativeLengthIsKeptSoItCanBeRefused() {
        assertEquals(-5, head("POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n")!!.length)
        assertEquals(0, head("POST / HTTP/1.1\r\nContent-Length: lots\r\n\r\n")!!.length)
    }

    @Test
    fun aMissingRequestLineIsMalformed() {
        assertNull(head(""))
        assertNull(head("GET\r\n\r\n"))
    }

    @Test
    fun onlyThisDevicesOwnAddressAndPortAreAccepted() {
        assertTrue(LanHttp.hostMatches("192.168.1.20:8787", tv, 8787))
        assertFalse(LanHttp.hostMatches("192.168.1.21:8787", tv, 8787))
        assertFalse(LanHttp.hostMatches("192.168.1.20:8788", tv, 8787))
        assertFalse(LanHttp.hostMatches("192.168.1.20", tv, 8787))
        assertFalse(LanHttp.hostMatches(null, tv, 8787))
        assertFalse(LanHttp.hostMatches("", tv, 8787))
    }

    @Test
    fun aNameIsNeverAcceptedEvenIfItPointsHere() {
        assertFalse(LanHttp.hostMatches("evil.example:8787", tv, 8787))
        assertFalse(LanHttp.hostMatches("localhost:8787", InetAddress.getByName("127.0.0.1"), 8787))
        assertNull(LanHttp.literal("192.168.1.256"))
        assertNull(LanHttp.literal("1.2.3"))
    }

    @Test
    fun ipv6AddressesAreComparedAsAddresses() {
        val v6 = InetAddress.getByName("fe80::1")
        assertTrue(LanHttp.hostMatches("[fe80::1%wlan0]:9000", v6, 9000))
        assertTrue(LanHttp.hostMatches("[FE80:0:0:0:0:0:0:1]:9000", v6, 9000))
        assertFalse(LanHttp.hostMatches("[fe80::2]:9000", v6, 9000))
        assertTrue(LanHttp.hostMatches("[::ffff:192.168.1.20]:8787", tv, 8787))
    }
}
