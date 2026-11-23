package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlotTextTest {
    @Test
    fun theImdbLinkTextIsRemovedAndTheCutIsMarked() {
        assertEquals(
            "While navigating the cha…",
            PlotText.clean("While navigating the cha... Read all"),
        )
        assertEquals("While navigating the cha…", PlotText.clean("While navigating the cha… Read all"))
        assertEquals("She cracks local cases.", PlotText.clean("She cracks local cases. See full summary »"))
        assertEquals("She cracks local cases.", PlotText.clean("She cracks local cases. Read more"))
        assertEquals("A story.", PlotText.clean("A story.  See full synopsis »  "))
        assertEquals("A story…", PlotText.clean("A story ... See full plot summary"))
    }

    @Test
    fun repeatedLinksGoToo() {
        assertEquals("Ends here.", PlotText.clean("Ends here. Read all Read all"))
    }

    @Test
    fun ordinaryTextIsKept() {
        val plot = "A girl who loves to read all night finds a secret library. She learns to read more than words."
        assertEquals(plot, PlotText.clean(plot))
        assertEquals("Read all about it: a newspaper drama.", PlotText.clean("Read all about it: a newspaper drama."))
        assertEquals("Trimmed.", PlotText.clean("  Trimmed.  "))
    }

    @Test
    fun nothingLeftIsNothing() {
        assertNull(PlotText.clean("Read all"))
        assertNull(PlotText.clean("   "))
        assertNull(PlotText.clean(null))
    }
}
