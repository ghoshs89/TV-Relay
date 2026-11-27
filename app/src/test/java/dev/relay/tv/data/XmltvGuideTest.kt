package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class XmltvGuideTest {
    private val baseNowSec = 1_764_200_000L // fixed reference timestamp
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun ts(epochSec: Long): String = fmt.format(java.util.Date(epochSec * 1000)) + " +0000"

    @Test
    fun unsortedProgrammesAreSortedBeforeApplyingPerChannelCap() {
        val ch = Channel(10, 1, "UK: Sky Sports Main Event HD", null, "uk", epgId = "sky.sports", archiveDays = 0)
        // Build 180 programmes in reverse chronological order so the earliest (current) ones come LAST in XML.
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><tv>""")
            append("""<channel id="sky.sports"><display-name>Sky Sports Main Event</display-name></channel>""")
            for (i in 179 downTo 0) {
                val s = baseNowSec + i * 600L
                val e = s + 600L
                append("""<programme start="${ts(s)}" stop="${ts(e)}" channel="sky.sports"><title>Show $i</title></programme>""")
            }
            append("</tv>")
        }
        val cache = XmltvGuide.parseStream(ByteArrayInputStream(xml.toByteArray()), listOf(ch), "test", nowSec = baseNowSec)
        val kept = cache.byChannel.getValue(10)
        assertEquals(XmltvGuide.MAX_PER_CHANNEL, kept.size)
        // Show 0 (starting right at baseNowSec) must be first, even though it was at the end of the XML feed.
        assertEquals("Show 0", kept.first().t)
        assertEquals("Show ${XmltvGuide.MAX_PER_CHANNEL - 1}", kept.last().t)
    }

    @Test
    fun gzippedXmlMatchesChannelByNormalisedDisplayName() {
        val ch = Channel(42, 0, "CA: CBC News FHD", null, "ca", epgId = null, archiveDays = 0)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cbc-1"><display-name>CBC News</display-name></channel>
              <programme start="${ts(baseNowSec)}" stop="${ts(baseNowSec + 1800)}" channel="cbc-1">
                <title>The National</title>
                <desc>Evening news</desc>
              </programme>
            </tv>
        """.trimIndent()
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val stream = XmltvGuide.maybeGunzip(ByteArrayInputStream(gz))
        val cache = XmltvGuide.parseStream(stream, listOf(ch), "provider", nowSec = baseNowSec)
        val progs = cache.byChannel.getValue(42)
        assertEquals(1, progs.size)
        assertEquals("The National", progs[0].t)
    }

    @Test
    fun oversizedStreamIsRejectedBeforeMemoryExhaustion() {
        val payload = ByteArray(4096) { 'x'.code.toByte() }
        val bounded = XmltvGuide.bounded(ByteArrayInputStream(payload), maxBytes = 1024)
        try {
            bounded.readBytes()
            fail("Expected IOException when stream exceeds cap")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("limit"))
        }
    }
}
