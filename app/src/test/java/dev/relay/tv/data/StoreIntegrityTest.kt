package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreIntegrityTest {

    @Test
    fun mergeSharedSettingsPreservesPerScreenSettingsAndLocallyEditedSharedFields() {
        val snapshot = Settings(
            tmdbKey = "old-key",
            hideAdult = false,
            skipBackSec = 10,
            subSize = 1,
            audioDelayMs = 0,
        )
        // Another screen updated tmdbKey and hideAdult; its target carries default per-screen fields.
        val target = snapshot.copy(
            tmdbKey = "household-key",
            hideAdult = true,
            skipBackSec = 15,
            subSize = 0,
            audioDelayMs = -200,
        )
        // Meanwhile on this TV, the viewer changed per-screen subSize + audioDelayMs AND shared skipBackSec.
        val current = snapshot.copy(
            skipBackSec = 30,
            subSize = 3,
            audioDelayMs = 150,
        )
        val merged = Store.mergeSharedSettings(snapshot, target, current)
        assertEquals("household-key", merged.tmdbKey)
        assertEquals(true, merged.hideAdult)
        assertEquals("local shared edit made during sync is preserved", 30, merged.skipBackSec)
        assertEquals("per-screen subtitle size stays untouched", 3, merged.subSize)
        assertEquals("per-screen audio delay stays untouched", 150, merged.audioDelayMs)
    }

    @Test
    fun sumTodayUsageAddsBothProfilesForTodayAndIgnoresYesterday() {
        val today = "2026-11-26"
        assertEquals(
            "2026-11-26|900000",
            Store.sumTodayUsage("2026-11-26|600000", "2026-11-26|300000", today),
        )
        assertEquals(
            "2026-11-26|600000",
            Store.sumTodayUsage("2026-11-26|600000", "2026-11-25|500000", today),
        )
        assertNull(Store.sumTodayUsage("2026-11-25|600000", "2026-11-25|300000", today))
    }
}
