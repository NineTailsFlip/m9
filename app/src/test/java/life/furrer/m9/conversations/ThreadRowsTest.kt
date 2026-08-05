package life.furrer.m9.conversations

import android.provider.Telephony
import life.furrer.m9.sms.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure — no Android provider access, plain JUnit (the `Telephony.Sms` constants it reads are
 *  compile-time ints, not a provider call). */
class ThreadRowsTest {

    @Test
    fun `groups messages by thread, newest thread first`() {
        val rows = buildThreadRows(
            listOf(
                message(id = 3, threadId = 2, address = "079999", body = "newest overall", date = 300),
                message(id = 2, threadId = 1, address = "079123", body = "newer in thread 1", date = 200),
                message(id = 1, threadId = 1, address = "079123", body = "older in thread 1", date = 100),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals(2L, rows[0].threadId)
        assertEquals(1L, rows[1].threadId)
    }

    @Test
    fun `a thread's snippet and timestamp come from its newest message`() {
        val rows = buildThreadRows(
            listOf(
                message(id = 2, threadId = 1, body = "newest", date = 200),
                message(id = 1, threadId = 1, body = "oldest", date = 100),
            ),
        )

        assertEquals("newest", rows.single().snippet)
        assertEquals(200L, rows.single().date)
        assertEquals(2, rows.single().messageCount)
    }

    @Test
    fun `unread counts only inbound messages`() {
        val rows = buildThreadRows(
            listOf(
                message(id = 3, threadId = 1, date = 300, read = false, type = Telephony.Sms.MESSAGE_TYPE_INBOX),
                message(id = 2, threadId = 1, date = 200, read = false, type = Telephony.Sms.MESSAGE_TYPE_INBOX),
                // An outbound row whose READ column some sending app left at 0. Counting it would
                // put a permanent unread badge on a thread the user just replied to.
                message(id = 1, threadId = 1, date = 100, read = false, type = Telephony.Sms.MESSAGE_TYPE_SENT),
            ),
        )

        assertEquals(2, rows.single().unreadCount)
        assertTrue(rows.single().hasUnread)
    }

    @Test
    fun `unreadOnly hides fully-read threads`() {
        val messages = listOf(
            message(id = 2, threadId = 2, address = "079999", date = 200, read = true),
            message(id = 1, threadId = 1, address = "079123", date = 100, read = false),
        )

        val rows = buildThreadRows(messages, unreadOnly = true)

        assertEquals(1, rows.size)
        assertEquals(1L, rows.single().threadId)
    }

    @Test
    fun `query matches a contact name by word prefix`() {
        val messages = listOf(
            message(id = 1, threadId = 1, address = "079123", resolvedName = "Anna Meier", date = 100),
            message(id = 2, threadId = 2, address = "079999", resolvedName = "Bob Roth", date = 200),
        )

        assertEquals(listOf("Anna Meier"), buildThreadRows(messages, query = "ann").map { it.title })
        // Word prefix, not just first-word prefix.
        assertEquals(listOf("Anna Meier"), buildThreadRows(messages, query = "mei").map { it.title })
    }

    @Test
    fun `query matches a digit substring of the address`() {
        val messages = listOf(
            message(id = 1, threadId = 1, address = "079 123 45 67", date = 100),
            message(id = 2, threadId = 2, address = "079 999 00 11", date = 200),
        )

        assertEquals(listOf("079 123 45 67"), buildThreadRows(messages, query = "12345").map { it.title })
    }

    @Test
    fun `query matches a message body anywhere, case-insensitively`() {
        val messages = listOf(
            message(id = 1, threadId = 1, address = "079123", body = "See you at eight", date = 100),
            message(id = 2, threadId = 2, address = "079999", body = "bring bread", date = 200),
        )

        // Mid-word, which is exactly why body matching is a substring rather than StringMatch's
        // word-prefix rule.
        assertEquals(1, buildThreadRows(messages, query = "eigh").size)
        assertEquals(1, buildThreadRows(messages, query = "BREAD").size)
    }

    @Test
    fun `query matches an older message in the thread, not only the snippet`() {
        val rows = buildThreadRows(
            listOf(
                message(id = 2, threadId = 1, body = "ok", date = 200),
                message(id = 1, threadId = 1, body = "the secret word is bananas", date = 100),
            ),
            query = "bananas",
        )

        assertEquals(1, rows.size)
        assertEquals("ok", rows.single().snippet)
    }

    @Test
    fun `an alphanumeric sender is searchable as a name and never callable`() {
        val rows = buildThreadRows(listOf(message(id = 1, threadId = 1, address = "Migros", date = 100)), query = "mig")

        assertEquals("Migros", rows.single().title)
        assertFalse(rows.single().canCall)
    }

    @Test
    fun `a numeric address is callable`() {
        val rows = buildThreadRows(listOf(message(id = 1, threadId = 1, address = "079123", date = 100)))
        assertTrue(rows.single().canCall)
    }

    @Test
    fun `title falls back to the raw address when no contact resolved`() {
        val rows = buildThreadRows(listOf(message(id = 1, threadId = 1, address = "079123", date = 100)))
        assertEquals("079123", rows.single().title)
    }

    @Test
    fun `an empty query matches everything`() {
        val messages = listOf(
            message(id = 1, threadId = 1, address = "079123", date = 100),
            message(id = 2, threadId = 2, address = "079999", date = 200),
        )
        assertEquals(2, buildThreadRows(messages, query = "").size)
    }

    @Suppress("LongParameterList")
    private fun message(
        id: Long,
        threadId: Long,
        address: String = "079123",
        body: String = "body",
        date: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        read: Boolean = true,
        resolvedName: String? = null,
    ) = SmsMessage(
        id = id,
        threadId = threadId,
        address = address,
        body = body,
        date = date,
        dateSent = 0,
        type = type,
        status = Telephony.Sms.STATUS_NONE,
        read = read,
        seen = read,
        subscriptionId = 1,
        resolvedName = resolvedName,
        resolvedContactId = resolvedName?.let { 1L },
    )
}
