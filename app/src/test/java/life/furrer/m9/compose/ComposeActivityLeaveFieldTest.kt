package life.furrer.m9.compose

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Leaving the message field relaunches the screen (the T9 IME otherwise keeps swallowing `*`),
 *  carrying the recipients over and landing on the recipient row. */
@RunWith(RobolectricTestRunner::class)
class ComposeActivityLeaveFieldTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun launch(intent: Intent): ComposeActivity =
        Robolectric.buildActivity(ComposeActivity::class.java, intent).setup().get()

    private fun press(activity: ComposeActivity, code: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        if (!activity.dispatchKeyEvent(down)) activity.onKeyDown(code, down)
    }

    private fun addressed(vararg numbers: String): Intent =
        ComposeActivity.intentFor(context).setData(Uri.parse("smsto:" + numbers.joinToString(",") { Uri.encode(it) }))

    @Test
    fun `Back out of an empty field relaunches with the recipients, on the recipient row`() {
        val activity = launch(addressed("+41791234567", "+41797654321"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN) // into the message field
        press(activity, KeyEvent.KEYCODE_BACK)

        assertTrue(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(ComposeActivity::class.java.name, next.component?.className)

        val relaunched = launch(next)
        val title = relaunched.window.decorView.findViewById<android.view.View>(android.R.id.content)
        assertTrue(textOf(title).contains("+41791234567"))
        assertTrue(textOf(title).contains("+41797654321"))
        // On the recipient row: OK there opens the picker rather than the message field.
        press(relaunched, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(
            life.furrer.nine.contacts.ContactValuePickerActivity::class.java.name,
            shadowOf(relaunched).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `Up out of an empty field relaunches too, and without a recipient stays without one`() {
        val activity = launch(ComposeActivity.intentFor(context))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)

        assertTrue(activity.isFinishing)
        val relaunched = launch(shadowOf(activity).nextStartedActivity)
        assertFalse(relaunched.isFinishing)
        assertTrue(textOf(relaunched.findViewById(android.R.id.content)).contains("No recipient yet"))
    }

    private fun textOf(v: android.view.View): String = when (v) {
        is android.widget.TextView -> v.text.toString() + "\n"
        is android.view.ViewGroup -> (0 until v.childCount).joinToString("") { textOf(v.getChildAt(it)) }
        else -> ""
    }
}
