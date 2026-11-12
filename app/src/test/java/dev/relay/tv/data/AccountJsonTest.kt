package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Needs the serialization plugin, so it runs on CI only. */
class AccountJsonTest {
    private val a = Account("Home", listOf("http://a.example.com", "http://b.example.com"), "u", "p")

    @Test fun marksRoundTrip() {
        val m = a.copy(hostNets = mapOf("http://b.example.com" to "vpn"))
        assertEquals(m, Jsons.json.decodeFromString(Account.serializer(), Jsons.json.encodeToString(Account.serializer(), m)))
    }

    @Test fun anAccountSavedBeforeMarksStillReads() {
        val old = """{"name":"Home","hosts":["http://a.example.com","http://b.example.com"],"username":"u","password":"p"}"""
        assertEquals(a, Jsons.json.decodeFromString(Account.serializer(), old))
    }

    @Test fun aMarkFromANewerRelayStillReads() {
        val newer = """{"name":"Home","hosts":["http://a.example.com"],"username":"u","password":"p","hostNets":{"http://a.example.com":"satellite"},"later":1}"""
        val got = Jsons.json.decodeFromString(Account.serializer(), newer)
        assertEquals(HostNet.ANY, got.netOf("http://a.example.com"))
    }
}
