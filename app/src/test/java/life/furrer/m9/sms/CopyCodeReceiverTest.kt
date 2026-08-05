package life.furrer.m9.sms

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The notification's Copy action. Worth testing rather than eyeballing, because the one thing it
 *  must never do is put the *wrong* string on the clipboard — a wrong code is discovered at the
 *  login screen, after the real one has expired. */
@RunWith(RobolectricTestRunner::class)
class CopyCodeReceiverTest {

    private val context = RuntimeEnvironment.getApplication()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private fun deliver(code: String?, threadId: Long = 7L) {
        val intent = Intent(context, CopyCodeReceiver::class.java).apply {
            action = CopyCodeReceiver.ACTION_COPY_CODE
            if (code != null) putExtra(CopyCodeReceiver.EXTRA_CODE, code)
            putExtra(CopyCodeReceiver.EXTRA_THREAD_ID, threadId)
        }
        CopyCodeReceiver().onReceive(context, intent)
    }

    @Test
    fun `copies the code to the clipboard`() {
        deliver("445566")

        assertEquals("445566", clipboard.primaryClip?.getItemAt(0)?.text)
    }

    @Test
    fun `marks the clip sensitive so the system does not preview it`() {
        // Android 13+ shows a copy confirmation with a preview of what was copied. A one-time
        // code is exactly the thing that shouldn't be rendered over a lock screen.
        deliver("445566")

        val extras = clipboard.primaryClip?.description?.extras
        assertTrue(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    @Test
    fun `a missing code copies nothing at all`() {
        // Rather than clearing the clipboard or copying an empty string over whatever the user
        // had there.
        deliver(code = null)

        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `a blank code copies nothing`() {
        deliver("   ")

        assertNull(clipboard.primaryClip)
    }
}
