package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SnapshotIoTest {

    private fun write(buffer: Int = 256 * 1024, body: (SnapshotWriter) -> Unit): Pair<ByteArray, Long> {
        val out = ByteArrayOutputStream()
        val w = SnapshotWriter(out, buffer)
        body(w)
        val crc = w.finish()
        return out.toByteArray() to crc
    }

    private fun reader(bytes: ByteArray, buffer: Int = 256 * 1024) = SnapshotReader(ByteArrayInputStream(bytes), bytes.size.toLong(), buffer)

    @Test
    fun unsignedNumbersAtTheEdges() {
        val values = longArrayOf(0, 1, 127, 128, 255, 16_383, 16_384, Int.MAX_VALUE.toLong(), 0xFFFFFFFFL, Long.MAX_VALUE, -1L, Long.MIN_VALUE)
        val (bytes, _) = write { w -> values.forEach { w.writeVarLong(it) } }
        val r = reader(bytes)
        values.forEach { assertEquals(it, r.readVarLong()) }
        r.finish()
    }

    @Test
    fun sizesOfSmallNumbersStaySmall() {
        assertEquals(1, write { it.writeVarInt(127) }.first.size)
        assertEquals(2, write { it.writeVarInt(128) }.first.size)
        assertEquals(1, write { it.writeZigZagInt(-1) }.first.size)
        assertEquals(1, write { it.writeZigZagInt(63) }.first.size)
        assertEquals(2, write { it.writeZigZagInt(64) }.first.size)
    }

    @Test
    fun intsKeepAllThirtyTwoBits() {
        val values = intArrayOf(0, 1, 127, 128, Int.MAX_VALUE, -1, Int.MIN_VALUE)
        val (bytes, _) = write { w -> values.forEach { w.writeVarInt(it) } }
        val r = reader(bytes)
        values.forEach { assertEquals(it, r.readVarInt()) }
    }

    @Test
    fun signedNumbersRoundTrip() {
        val ints = intArrayOf(0, 1, -1, 63, -64, 64, -65, Int.MAX_VALUE, Int.MIN_VALUE, 1_000_000, -1_000_000)
        val longs = longArrayOf(0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 1_700_000_000L, -1_700_000_000L)
        val (bytes, _) = write { w ->
            ints.forEach { w.writeZigZagInt(it) }
            longs.forEach { w.writeZigZagLong(it) }
        }
        val r = reader(bytes)
        ints.forEach { assertEquals(it, r.readZigZagInt()) }
        longs.forEach { assertEquals(it, r.readZigZagLong()) }
        r.finish()
    }

    @Test
    fun stringsKeepNullEmptyAndEveryScript() {
        val long = "x".repeat(100_000) + "ऄ" + "y".repeat(5)
        val strings = listOf(
            null, "", " ", "a", "plain ascii", "Star Jalsha HD", "हिन्दी सिनेमा", "বাংলা নিউজ", "تلفزيون", "日本放送", "Ελληνικά",
            "Télé Française", "emoji 🔥🇬🇧 end", "⁴ᴷ ᴿᴬᵂ", "tab\tnew\nline", "quote \" back \\ slash", "\u0000nul", long,
        )
        val (bytes, _) = write { w -> strings.forEach { w.writeString(it) } }
        val r = reader(bytes)
        strings.forEach { assertEquals(it, r.readString()) }
        r.finish()
    }

    @Test
    fun nullAndEmptyStayDifferent() {
        val (bytes, _) = write { w -> w.writeString(null); w.writeString("") }
        val r = reader(bytes)
        assertNull(r.readString())
        assertEquals("", r.readString())
    }

    @Test
    fun stringsLongerThanTheBufferStillRoundTrip() {
        val s = "ab😀".repeat(5_000)
        val (bytes, _) = write(buffer = 64) { w -> w.writeString(s); w.writeString("tail") }
        val r = reader(bytes, buffer = 64)
        assertEquals(s, r.readString())
        assertEquals("tail", r.readString())
        r.finish()
    }

    @Test
    fun anUnpairedSurrogateIsRefusedNotChangedToAQuestionMark() {
        for (bad in listOf("a\uD800b", "\uDC00", "end\uD83D")) {
            try {
                write { it.writeString(bad) }
                fail("accepted ${bad.length} chars")
            } catch (e: SnapshotFormatException) {
                assertTrue(e.message!!.contains("surrogate"))
            }
        }
    }

    @Test
    fun doublesComeBackBitForBit() {
        val values = doubleArrayOf(0.0, -0.0, 7.25, 6.8, 9.999, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE, -3.5)
        val (bytes, _) = write { w -> values.forEach { w.writeDouble(it) } }
        val r = reader(bytes)
        values.forEach { assertEquals(it.toRawBits(), r.readDouble().toRawBits()) }
        assertEquals(values.size * 8, bytes.size)
    }

    @Test
    fun sharedTextIsWrittenOnceAndReadAsOneInstance() {
        val shared = SharedWriter()
        val items = listOf("1", "2", null, "1", "2", "1", "")
        val (bytes, _) = write { w -> items.forEach { shared.write(w, it) } }
        val reader = SharedReader()
        val r = reader(bytes)
        val back = items.map { reader.read(r) }
        assertEquals(items, back)
        assertSame(back[0], back[3])
        assertSame(back[0], back[5])
        assertSame(back[1], back[4])
        // Text 1 and 2 are written once each: 7 codes plus "1", "2", "" as text.
        assertEquals(7 + 2 + 2 + 1, bytes.size)
    }

    @Test
    fun checksumMatchesAnIndependentCrc() {
        val (bytes, crc) = write { w -> w.writeString("hello"); w.writeVarInt(300) }
        val expected = java.util.zip.CRC32().apply { update(bytes) }.value
        assertEquals(expected, crc)
        val r = reader(bytes)
        r.readString(); r.readVarInt()
        assertEquals(expected, r.finish())
    }

    @Test
    fun smallBuffersGiveTheSameBytesAndChecksum() {
        fun body(w: SnapshotWriter) {
            repeat(2_000) { w.writeZigZagInt(it - 1_000); w.writeString("name $it ⁴ᴷ") }
        }
        val a = write(buffer = 256 * 1024, body = ::body)
        val b = write(buffer = 16, body = ::body)
        assertTrue(a.first.contentEquals(b.first))
        assertEquals(a.second, b.second)
        val r = reader(a.first, buffer = 7)
        repeat(2_000) { assertEquals(it - 1_000, r.readZigZagInt()); assertEquals("name $it ⁴ᴷ", r.readString()) }
        assertEquals(a.second, r.finish())
    }

    @Test
    fun runningOutOfBytesIsAFormatError() {
        val (bytes, _) = write { it.writeString("hello world") }
        for (cut in 0 until bytes.size) {
            val r = SnapshotReader(ByteArrayInputStream(bytes.copyOf(cut)), bytes.size.toLong())
            try {
                r.readString()
                fail("read a string from $cut of ${bytes.size} bytes")
            } catch (e: SnapshotFormatException) {
                // expected
            }
        }
    }

    @Test
    fun aStringLongerThanTheDataLeftIsRefusedBeforeAnythingIsAllocated() {
        val (bytes, _) = write { w -> w.writeVarLong(Int.MAX_VALUE.toLong()) }
        try {
            reader(bytes).readString()
            fail("accepted")
        } catch (e: SnapshotFormatException) {
            assertTrue(e.message!!.contains("longer"))
        }
    }

    @Test
    fun numbersTooLongOrTooBigAreRefused() {
        val tooLong = ByteArray(11) { 0x80.toByte() }
        try {
            reader(tooLong).readVarLong()
            fail("accepted 11 bytes")
        } catch (e: SnapshotFormatException) {
            // expected
        }
        val tooBig = ByteArray(10) { 0xFF.toByte() }.also { it[9] = 0x7F }
        try {
            reader(tooBig).readVarLong()
            fail("accepted a 70 bit number")
        } catch (e: SnapshotFormatException) {
            // expected
        }
        val (big, _) = write { it.writeVarLong(0x1_0000_0000L) }
        try {
            reader(big).readVarInt()
            fail("accepted a 33 bit number as an int")
        } catch (e: SnapshotFormatException) {
            // expected
        }
    }

    @Test
    fun boundedCountsRefuseWhatCannotBeRight() {
        val (bytes, _) = write { w -> w.writeVarInt(5); w.writeVarInt(6) }
        val r = reader(bytes)
        assertEquals(5, r.readBounded(5))
        try {
            r.readBounded(5)
            fail("accepted 6 of 5")
        } catch (e: SnapshotFormatException) {
            // expected
        }
    }

    @Test
    fun unusedBytesAreAFormatError() {
        val (bytes, _) = write { w -> w.writeVarInt(1); w.writeVarInt(2) }
        val r = reader(bytes)
        r.readVarInt()
        try {
            r.finish()
            fail("accepted a section with bytes left")
        } catch (e: SnapshotFormatException) {
            assertTrue(e.message!!.contains("longer"))
        }
    }

    @Test
    fun readerNeverReadsPastItsSection() {
        val (bytes, _) = write { w -> w.writeString("one") }
        val all = bytes + byteArrayOf(9, 9, 9, 9)
        val input = ByteArrayInputStream(all)
        val r = SnapshotReader(input, bytes.size.toLong())
        assertEquals("one", r.readString())
        r.finish()
        assertEquals(4, input.available())
    }

    @Test
    fun unknownSharedTextIsAFormatError() {
        val (bytes, _) = write { w -> w.writeVarInt(5) }
        try {
            SharedReader().read(reader(bytes))
            fail("accepted")
        } catch (e: SnapshotFormatException) {
            // expected
        }
    }
}
