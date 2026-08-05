package life.furrer.m9.conversations

import android.content.ContentValues
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.view.KeyEvent
import android.widget.FrameLayout
import life.furrer.m9.sms.FakeSmsProvider
import life.furrer.nine.contacts.FakeContactsProvider
import life.furrer.nine.core.ui.PermissionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Key handling and gating, in the shape of P9's `DialerActivityTest` — what a screen does with a
 *  keypress is the part of this app a unit test can actually hold onto. */
@RunWith(RobolectricTestRunner::class)
class ConversationListActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeSmsProvider.reset()
        Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms")
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `shows the permission screen without READ_SMS, and no list`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_SMS)

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()

        assertTrue(permissionScreenOf(activity).isShowing)
    }

    @Test
    fun `lists one row per thread, newest first`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "older", date = 100L)
        FakeSmsProvider.putMessage(2L, threadId = 1L, address = "079123", body = "newer", date = 200L)
        FakeSmsProvider.putMessage(3L, threadId = 2L, address = "079999", body = "newest", date = 300L)

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()

        assertEquals(listOf(2L, 1L), rowsOf(activity).map { it.threadId })
    }

    @Test
    fun `hash toggles unread-only and back`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "read", date = 100L, read = true)
        FakeSmsProvider.putMessage(2L, threadId = 2L, address = "079999", body = "unread", date = 200L, read = false)

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()
        assertEquals(2, rowsOf(activity).size)

        tap(activity, KeyEvent.KEYCODE_POUND)
        assertEquals(listOf(2L), rowsOf(activity).map { it.threadId })

        tap(activity, KeyEvent.KEYCODE_POUND)
        assertEquals(2, rowsOf(activity).size)
    }

    @Test
    fun `typing filters the list`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "See you at eight", date = 100L)
        FakeSmsProvider.putMessage(2L, threadId = 2L, address = "079999", body = "bring bread", date = 200L)

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()

        searchFieldOf(activity).setText("bread")
        assertEquals(listOf(2L), rowsOf(activity).map { it.threadId })
    }

    @Test
    fun `OK opens the selected conversation`() {
        FakeSmsProvider.putMessage(1L, threadId = 7L, address = "079123", body = "hi", date = 100L)

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()
        tap(activity, KeyEvent.KEYCODE_DPAD_CENTER)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ConversationActivity::class.java.name, started.component?.className)
        assertEquals(7L, started.getLongExtra("thread_id", -1L))
    }

    @Test
    fun `the call key places the call itself rather than handing off to a dialer`() {
        // ACTION_CALL, not ACTION_DIAL: the call key inside messages means "call this person",
        // and opening a dialer for a second press is one press too many.
        shadowOf(context).grantPermissions(android.Manifest.permission.CALL_PHONE)
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "hi", date = 100L)
        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()

        tap(activity, KeyEvent.KEYCODE_CALL)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CALL, started.action)
        assertEquals("tel:079123", started.data.toString())

        FakeSmsProvider.reset()
        FakeSmsProvider.putMessage(2L, threadId = 2L, address = "Migros", body = "code", date = 200L)
        val shopActivity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()

        tap(shopActivity, KeyEvent.KEYCODE_CALL)
        // Nothing started at all — an alphanumeric sender is not a number, and the call key must
        // not invent one.
        assertEquals(null, shadowOf(shopActivity).nextStartedActivity)
    }

    @Test
    fun `without CALL_PHONE the call key asks for it instead of dialling`() {
        // The permission is requested when the key is first used, not at first run — someone who
        // only ever texts is never asked for it. What must not happen is a keypress that silently
        // does nothing.
        shadowOf(context).denyPermissions(android.Manifest.permission.CALL_PHONE)
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "hi", date = 100L)
        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()

        tap(activity, KeyEvent.KEYCODE_CALL)

        assertTrue(
            shadowOf(activity).lastRequestedPermission.requestedPermissions
                .contains(android.Manifest.permission.CALL_PHONE),
        )
        // No call went out in the meantime. (The permission request is itself an started
        // activity under Robolectric, so this asserts on the action rather than on nothing
        // having started.)
        assertFalse(
            generateSequence { shadowOf(activity).nextStartedActivity }
                .any { it.action == android.content.Intent.ACTION_CALL },
        )
    }

    @Test
    fun `an unread thread is marked as such, a read one is not`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "hi", date = 100L, read = false)
        FakeSmsProvider.putMessage(
            2L, threadId = 2L, address = "079999", body = "sent", date = 200L,
            type = Telephony.Sms.MESSAGE_TYPE_SENT, read = false,
        )

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()
        val rows = rowsOf(activity).associateBy { it.threadId }

        assertTrue(rows.getValue(1L).hasUnread)
        // Outbound rows never count as unread, however their READ column was written.
        assertFalse(rows.getValue(2L).hasUnread)
    }

    @Test
    fun `a resolved contact titles the thread, an unknown number shows itself`() {
        FakeContactsProvider.putContact(1L, "Timo Furrer")
        FakeContactsProvider.putData(
            id = 10L, contactId = 1L, rawContactId = 1L,
            mimetype = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply { put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+41791234567") },
        )
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079 123 45 67", body = "hi", date = 200L)
        FakeSmsProvider.putMessage(2L, threadId = 2L, address = "079 999 00 11", body = "hi", date = 100L)

        val activity = Robolectric.buildActivity(ConversationListActivity::class.java).setup().get()
        settle()

        assertEquals(listOf("Timo Furrer", "079 999 00 11"), rowsOf(activity).map { it.title })
    }

    /** Async.run posts its result back to the main looper; nothing is on screen until it drains. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(activity: ConversationListActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0))
        settle()
    }

    private fun rootOf(activity: ConversationListActivity): FrameLayout =
        activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout

    private fun mainColumnOf(activity: ConversationListActivity): android.widget.LinearLayout =
        rootOf(activity).getChildAt(0) as android.widget.LinearLayout

    private fun searchFieldOf(activity: ConversationListActivity): android.widget.EditText =
        mainColumnOf(activity).getChildAt(0) as android.widget.EditText

    private fun permissionScreenOf(activity: ConversationListActivity): PermissionScreen =
        rootOf(activity).getChildAt(2) as PermissionScreen

    /** The rows the screen currently holds, read back through its own binder rather than through
     *  a test-only accessor — same approach P9's own Activity tests take. */
    private fun rowsOf(activity: ConversationListActivity): List<ThreadRow> {
        val field = ConversationListActivity::class.java.getDeclaredField("rows")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(activity) as List<ThreadRow>
    }
}
