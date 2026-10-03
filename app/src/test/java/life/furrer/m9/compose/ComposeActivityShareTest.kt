package life.furrer.m9.compose

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.EditText
import life.furrer.nine.contacts.ContactValuePickerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** "Share via M9": shared text becomes the body and the recipient picker opens at once. */
@RunWith(RobolectricTestRunner::class)
class ComposeActivityShareTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun launch(intent: Intent): ComposeActivity =
        Robolectric.buildActivity(ComposeActivity::class.java, intent).setup().get()

    private fun body(activity: ComposeActivity): String =
        activity.findViewById<android.view.View>(android.R.id.content)
            .let { findEdit(it) }.text.toString()

    private fun findEdit(v: android.view.View): EditText {
        if (v is EditText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) runCatching { return findEdit(v.getChildAt(i)) }
        throw NoSuchElementException()
    }

    @Test
    fun `shared text fills the body and opens the recipient picker`() {
        val activity = launch(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "look at this"),
        )

        assertEquals("look at this", body(activity))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(ContactValuePickerActivity::class.java.name, started.intent.component?.className)
    }

    @Test
    fun `a non-String CharSequence share is still taken as text`() {
        val text = android.text.SpannableString("styled")
        val activity = launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))

        assertEquals("styled", body(activity))
    }

    @Test
    fun `an ordinary new message does not open the picker on its own`() {
        val activity = launch(ComposeActivity.intentFor(context))

        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `the manifest offers ACTION_SEND text-plain to ComposeActivity`() {
        val probe = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        val hits = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)

        assertEquals(listOf(ComposeActivity::class.java.name), hits.map { it.activityInfo.name })
    }

    @Test
    fun `other mime types are not offered`() {
        val probe = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(context.packageName)
        assertEquals(0, context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).size)
    }
}
