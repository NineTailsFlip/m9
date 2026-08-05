package life.furrer.m9.conversations

import life.furrer.m9.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure arithmetic — the part of the renderer that decides whether text fits. */
class ThreadRowRendererTest {

    @Test
    fun `the default font size still yields the height this row shipped with`() {
        // 56dp was a constant before the size became configurable; the formula has to agree with
        // it at the default, or every existing install's list would visibly reflow on upgrade.
        assertEquals(56, threadRowHeightDp(Prefs.DEFAULT_FONT_SIZE))
    }

    @Test
    fun `a larger font buys proportionally more room`() {
        // The row holds two lines of text, so height has to grow with the size or the second line
        // simply clips — the failure this scaling exists to prevent.
        assertTrue(threadRowHeightDp(28) > threadRowHeightDp(18))
        assertTrue(threadRowHeightDp(48) > threadRowHeightDp(28))
    }

    @Test
    fun `the snippet stays smaller than the title across the whole range`() {
        listOf(14, 18, 28, 48).forEach { size ->
            assertTrue("snippet should stay below the title at $size", snippetTextSize(size) < size.toFloat())
        }
    }

    @Test
    fun `the snippet never shrinks into illegibility at the smallest font`() {
        assertTrue(snippetTextSize(14) >= 10f)
    }
}
