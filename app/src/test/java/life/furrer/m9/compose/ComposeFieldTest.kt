package life.furrer.m9.compose

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComposeFieldTest {

    private var edits = 0

    private fun attachedField(): ComposeField {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = ComposeField(activity, "Message…", 6) { edits++ }
        activity.setContentView(field.view)
        return field
    }

    @Test
    fun `starts out not composing and unfocusable`() {
        val field = attachedField()
        assertFalse(field.isComposing)
        assertFalse(field.view.isFocusable)
        assertFalse(field.view.isFocusableInTouchMode)
    }

    @Test
    fun `start focuses the field once, stop releases it once`() {
        val field = attachedField()

        assertTrue(field.start())
        assertTrue(field.isComposing)
        assertTrue(field.view.isFocusable)
        assertTrue(field.view.hasFocus())
        assertFalse(field.start())

        assertTrue(field.stop())
        assertFalse(field.isComposing)
        assertFalse(field.view.isFocusable)
        // Not asserting hasFocus() here: where focus ends up is the screen's business (the
        // thread screen hands it to an anchor and relaunches), not the field's.
        assertFalse(field.stop())
    }

    @Test
    fun `every edit reaches the screen`() {
        val field = attachedField()
        field.view.setText("hi")
        field.text.append("!")
        assertEquals(2, edits)
    }
}
