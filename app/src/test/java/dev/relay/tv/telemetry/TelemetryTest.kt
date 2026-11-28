package dev.relay.tv.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {

    @Test
    fun installIdsAreRandom16CharHexStrings() {
        val a = TelemetryLogic.newInstallId()
        val b = TelemetryLogic.newInstallId()
        assertTrue(a.matches(Regex("^[0-9a-f]{16}$")))
        assertTrue(b.matches(Regex("^[0-9a-f]{16}$")))
        assertNotEquals(a, b)
    }

    @Test
    fun enablementRequiresHttpsEndpointOptInAndNonBench() {
        assertTrue(TelemetryLogic.isEnabled("https://relay-telemetry.example.workers.dev/", optedIn = true, bench = false))
        assertFalse(TelemetryLogic.isEnabled("http://relay-telemetry.example.workers.dev", optedIn = true, bench = false))
        assertFalse(TelemetryLogic.isEnabled("", optedIn = true, bench = false))
        assertFalse(TelemetryLogic.isEnabled("https://relay-telemetry.example.workers.dev", optedIn = false, bench = false))
        assertFalse(TelemetryLogic.isEnabled("https://relay-telemetry.example.workers.dev", optedIn = true, bench = true))
    }

    @Test
    fun mergeAccumulatesDayAndKeepsAtMost14Days() {
        var map = emptyMap<String, TelemetryBucket>()
        for (i in 1..18) {
            val day = "2026-11-%02d".format(i)
            map = TelemetryLogic.merge(map, day, addSessions = 1, addAppSec = 120, addLiveSec = 90)
        }
        assertEquals(14, map.size)
        assertFalse(map.containsKey("2026-11-01"))
        assertTrue(map.containsKey("2026-11-18"))
        val updated = TelemetryLogic.merge(map, "2026-11-18", addLiveSec = 100_000, addVodSec = 30)
        assertEquals(86_400, updated.getValue("2026-11-18").liveSec)
        assertEquals(30, updated.getValue("2026-11-18").vodSec)
    }

    @Test
    fun heartbeatJsonContainsExpectedAggregateFieldsOnly() {
        val json = TelemetryLogic.heartbeatJson(
            installId = "0123456789abcdef",
            deviceType = "tv",
            deviceModel = "onn 4K",
            androidSdk = 34,
            appVersion = "1.0.140",
            versionCode = 140,
            days = listOf(TelemetryBucket("2026-11-27", sessions = 2, appSec = 300, liveSec = 200, vodSec = 50, crashes = 1)),
        )
        assertTrue(json.contains("\"installId\":\"0123456789abcdef\""))
        assertTrue(json.contains("\"deviceType\":\"tv\""))
        assertTrue(json.contains("\"liveSec\":200"))
        assertFalse(json.contains("password"))
    }
}
