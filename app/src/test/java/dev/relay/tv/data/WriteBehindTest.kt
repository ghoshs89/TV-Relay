package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteBehindTest {
    private val tasks = ArrayDeque<Runnable>()
    private val saved = mutableListOf<Map<String, String?>>()
    private val wb = WriteBehind({ tasks.addLast(it) }, { saved += it })

    private fun runLater() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    @Test
    fun nothingIsSavedUntilTheBackgroundTaskRuns() {
        wb.put("a") { "1" }
        assertTrue(saved.isEmpty())
        assertEquals(1, wb.waiting)
        runLater()
        assertEquals(listOf(mapOf<String, String?>("a" to "1")), saved)
        assertEquals(0, wb.waiting)
    }

    @Test
    fun twoQuickWritesToOneKeySaveOnlyTheSecond() {
        wb.put("hist") { "old" }
        wb.put("hist") { "new" }
        assertEquals("one task is enough for both", 1, tasks.size)
        runLater()
        assertEquals(listOf(mapOf<String, String?>("hist" to "new")), saved)
    }

    @Test
    fun theValueIsEncodedWhenSavedNotWhenWritten() {
        var encoded = 0
        wb.put("a") { encoded++; "x" }
        wb.put("a") { encoded++; "y" }
        assertEquals(0, encoded)
        runLater()
        assertEquals("only the latest value was ever turned into text", 1, encoded)
    }

    @Test
    fun differentKeysAreSavedTogetherInOrder() {
        wb.put("a") { "1" }
        wb.put("b") { "2" }
        wb.put("c", null)
        runLater()
        assertEquals(1, saved.size)
        assertEquals(listOf("a", "b", "c"), saved[0].keys.toList())
        assertEquals(null, saved[0]["c"])
    }

    @Test
    fun flushSavesEverythingWaitingAtOnceAndTheLaterTaskFindsNothing() {
        wb.put("a") { "1" }
        wb.put("b") { "2" }
        wb.flush()
        assertEquals(1, saved.size)
        assertEquals(mapOf<String, String?>("a" to "1", "b" to "2"), saved[0])
        runLater()
        assertEquals("the queued task had nothing left to save", 1, saved.size)
    }

    @Test
    fun flushWithNothingWaitingSavesNothing() {
        wb.flush()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aWriteAfterASaveIsSavedAgain() {
        wb.put("a") { "1" }
        runLater()
        wb.put("a") { "2" }
        runLater()
        assertEquals(listOf("1", "2"), saved.map { it["a"] })
    }

    @Test
    fun aValueThatCantBeEncodedDoesNotStopTheOthers() {
        wb.put("bad") { error("no") }
        wb.put("good") { "ok" }
        runLater()
        assertEquals(listOf(mapOf<String, String?>("good" to "ok")), saved)
    }
}
