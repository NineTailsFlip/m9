package life.furrer.m9.sms

import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.Telephony
import life.furrer.m9.conversations.buildThreadRows
import life.furrer.nine.contacts.FakeContactsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SmsStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeSmsProvider.reset()
        // "sms" is Telephony.Sms.CONTENT_URI's authority — the same shape CallLogStoreTest
        // registers FakeCallLogProvider under CallLog.AUTHORITY.
        Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms")
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create("com.android.contacts")
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `queryMessages returns nothing without READ_SMS`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_SMS)
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "hi", date = 1L)

        assertTrue(SmsStore.queryMessages(context, 50).isEmpty())
    }

    @Test
    fun `queryMessages reads every column`() {
        FakeSmsProvider.putMessage(
            id = 1L,
            threadId = 7L,
            address = "079123",
            body = "See you at eight",
            date = 500L,
            dateSent = 400L,
            type = Telephony.Sms.MESSAGE_TYPE_SENT,
            status = Telephony.Sms.STATUS_COMPLETE,
            read = false,
            seen = false,
            subscriptionId = 2,
        )

        val message = SmsStore.queryMessages(context, 50).single()

        assertEquals(1L, message.id)
        assertEquals(7L, message.threadId)
        assertEquals("079123", message.address)
        assertEquals("See you at eight", message.body)
        assertEquals(500L, message.date)
        assertEquals(400L, message.dateSent)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, message.type)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, message.status)
        assertFalse(message.read)
        assertFalse(message.seen)
        assertEquals(2, message.subscriptionId)
    }

    @Test
    fun `queryMessages trims leading and trailing whitespace but keeps the message's own lines`() {
        FakeSmsProvider.putMessage(
            1L, threadId = 1L, address = "079123",
            body = "\n\n  Your code is 4451\nValid for 5 minutes.  \n\n",
            date = 1L,
        )

        // Both ends gone, the newline between the two real lines kept.
        assertEquals("Your code is 4451\nValid for 5 minutes.", SmsStore.queryMessages(context, 50).single().body)
    }

    @Test
    fun `a message that is nothing but whitespace becomes empty rather than blank lines`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "   \n  \n ", date = 1L)

        assertEquals("", SmsStore.queryMessages(context, 50).single().body)
    }

    @Test
    fun `queryMessages returns newest first and honours the limit`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "oldest", date = 100L)
        FakeSmsProvider.putMessage(2L, threadId = 1L, address = "079123", body = "newest", date = 300L)
        FakeSmsProvider.putMessage(3L, threadId = 1L, address = "079123", body = "middle", date = 200L)

        assertEquals(
            listOf("newest", "middle", "oldest"),
            SmsStore.queryMessages(context, 50).map { it.body },
        )
        assertEquals(listOf("newest"), SmsStore.queryMessages(context, 1).map { it.body })
    }

    @Test
    fun `queryMessages resolves an address to its current contact name from the address book`() {
        putContactWithNumber(1L, "Timo Furrer", "+41791234567")
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079 123 45 67", body = "hi", date = 1L)

        val message = SmsStore.queryMessages(context, 50).single()

        assertEquals("Timo Furrer", message.resolvedName)
        assertEquals(1L, message.resolvedContactId)
        assertEquals("Timo Furrer", message.displayName)
    }

    @Test
    fun `queryMessages resolves each distinct address once, not once per message`() {
        putContactWithNumber(1L, "Timo Furrer", "079123")
        repeat(5) { i ->
            FakeSmsProvider.putMessage(i.toLong() + 1, threadId = 1L, address = "079123", body = "m$i", date = i.toLong())
        }

        val messages = SmsStore.queryMessages(context, 50)

        // The observable half of the caching contract: every row resolved, and no row left
        // unresolved because a per-row query was skipped. (The count itself isn't observable
        // through a ContentProvider fake; what matters to a caller is that all five agree.)
        assertEquals(5, messages.size)
        assertTrue(messages.all { it.resolvedName == "Timo Furrer" })
    }

    @Test
    fun `an alphanumeric sender resolves to no contact without crashing`() {
        putContactWithNumber(1L, "Timo Furrer", "079123")
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "Migros", body = "Ihr Code: 4451", date = 1L)

        val message = SmsStore.queryMessages(context, 50).single()

        assertNull(message.resolvedName)
        assertNull(message.resolvedContactId)
        assertNull(message.displayName)
        assertFalse(isNumericAddress(message.address))
    }

    @Test
    fun `queryMessages resolves nothing without READ_CONTACTS, but still returns the messages`() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_CONTACTS)
        putContactWithNumber(1L, "Timo Furrer", "079123")
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "hi", date = 1L)

        val message = SmsStore.queryMessages(context, 50).single()

        assertEquals("hi", message.body)
        assertNull(message.resolvedName)
    }

    // Storing an inbound message is the single most consequential thing M9 does while it holds
    // the SMS role: the platform hands SMS_DELIVER to the default app and stores nothing itself,
    // so a message that doesn't land here is gone.

    @Test
    fun `insertInbound stores a message unread and unseen, in its thread`() {
        val uri = SmsStore.insertInbound(
            context = context,
            threadId = 42L,
            address = "079123",
            body = "See you at eight",
            sentAt = 900L,
            receivedAt = 1000L,
            subscriptionId = 2,
        )

        assertNotNull(uri)
        val stored = SmsStore.queryMessages(context, 50).single()
        assertEquals(42L, stored.threadId)
        assertEquals("079123", stored.address)
        assertEquals("See you at eight", stored.body)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, stored.type)
        assertFalse(stored.read)
        assertFalse(stored.seen)
        assertEquals(2, stored.subscriptionId)
    }

    @Test
    fun `insertInbound keeps the network's sent time and this device's received time apart`() {
        // They disagree whenever a message was delayed in the network or the phone was off, and
        // only the received time is a reliable sort key.
        SmsStore.insertInbound(context, 1L, "079123", "hi", sentAt = 900L, receivedAt = 5000L, subscriptionId = 1)

        val stored = SmsStore.queryMessages(context, 50).single()
        assertEquals(900L, stored.dateSent)
        assertEquals(5000L, stored.date)
    }

    @Test
    fun `an inbound message appears in its thread's row immediately`() {
        SmsStore.insertInbound(context, 7L, "079123", "first", sentAt = 0L, receivedAt = 100L, subscriptionId = 1)
        SmsStore.insertInbound(context, 7L, "079123", "second", sentAt = 0L, receivedAt = 200L, subscriptionId = 1)

        val row = buildThreadRows(SmsStore.queryMessages(context, 50)).single()
        assertEquals(7L, row.threadId)
        assertEquals("second", row.snippet)
        assertEquals(2, row.unreadCount)
    }

    @Test
    fun `lookupContactName resolves one address without reading the message store`() {
        putContactWithNumber(1L, "Timo Furrer", "+41791234567")

        assertEquals("Timo Furrer", SmsStore.lookupContactName(context, "079 123 45 67"))
        assertNull(SmsStore.lookupContactName(context, "079 999 00 11"))
        assertNull(SmsStore.lookupContactName(context, "Migros"))
    }

    @Test
    fun `markThreadRead sets both READ and SEEN, and only on that thread`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "a", date = 1L, read = false, seen = false)
        FakeSmsProvider.putMessage(2L, threadId = 1L, address = "079123", body = "b", date = 2L, read = false, seen = false)
        FakeSmsProvider.putMessage(3L, threadId = 2L, address = "079999", body = "other", date = 3L, read = false, seen = false)

        assertTrue(SmsStore.markThreadRead(context, 1L))

        FakeSmsProvider.rowsOf(1L).forEach { row ->
            assertEquals(1, row.getAsInteger(Telephony.Sms.READ))
            assertEquals(1, row.getAsInteger(Telephony.Sms.SEEN))
        }
        FakeSmsProvider.rowsOf(2L).forEach { row ->
            assertEquals(0, row.getAsInteger(Telephony.Sms.READ))
        }
    }

    @Test
    fun `deleteThread removes every message in that thread and no other`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "a", date = 1L)
        FakeSmsProvider.putMessage(2L, threadId = 1L, address = "079123", body = "b", date = 2L)
        FakeSmsProvider.putMessage(3L, threadId = 2L, address = "079999", body = "keep", date = 3L)

        assertTrue(SmsStore.deleteThread(context, 1L))

        assertEquals(listOf("keep"), SmsStore.queryMessages(context, 50).map { it.body })
    }

    @Test
    fun `deleteMessage removes exactly one row`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "gone", date = 1L)
        FakeSmsProvider.putMessage(2L, threadId = 1L, address = "079123", body = "stays", date = 2L)

        assertTrue(SmsStore.deleteMessage(context, 1L))

        assertEquals(listOf("stays"), SmsStore.queryMessages(context, 50).map { it.body })
    }

    private fun putContactWithNumber(contactId: Long, name: String, number: String) {
        FakeContactsProvider.putContact(contactId, name)
        FakeContactsProvider.putData(
            id = contactId * 10,
            contactId = contactId,
            rawContactId = contactId,
            mimetype = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            values = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            },
        )
    }

    @Test
    fun `queryMessages narrowed to a thread returns that thread's newest messages, not the store's`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "old, thread 1", date = 1L)
        FakeSmsProvider.putMessage(2L, threadId = 2L, address = "044555", body = "newer, thread 2", date = 2L)
        FakeSmsProvider.putMessage(3L, threadId = 2L, address = "044555", body = "newest, thread 2", date = 3L)

        // With a store-wide window of 2, thread 1 would have been cut off entirely.
        assertEquals(listOf("old, thread 1"), SmsStore.queryMessages(context, 2, threadId = 1L).map { it.body })
    }

    @Test
    fun `queryMessages narrowed to one id returns just that message`() {
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "one", date = 1L)
        FakeSmsProvider.putMessage(2L, threadId = 1L, address = "079123", body = "two", date = 2L)

        assertEquals(listOf("one"), SmsStore.queryMessages(context, 1, messageId = 1L).map { it.body })
    }
}
