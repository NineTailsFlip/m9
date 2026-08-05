package life.furrer.m9.conversations

import android.provider.Telephony
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.m9.sms.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `a thread carries the code from its newest message`() {
        val rows = buildThreadRows(
            listOf(
                message(id = 2, threadId = 1, address = "ZugerKB", body = "Code: 31080", date = 200),
                message(id = 1, threadId = 1, address = "ZugerKB", body = "Code: 11111", date = 100),
            ),
        )

        assertEquals("31080", rows.single().code)
    }

    @Test
    fun `a thread with no code carries none`() {
        val rows = buildThreadRows(listOf(message(id = 1, threadId = 1, body = "See you at eight", date = 100)))
        assertNull(rows.single().code)
    }

    @Test
    fun `a code you sent is not offered back to you`() {
        val rows = buildThreadRows(
            listOf(
                message(id = 1, threadId = 1, body = "Code: 31080", date = 100, type = Telephony.Sms.MESSAGE_TYPE_SENT),
            ),
        )

        assertNull(rows.single().code)
    }

    @Test
    fun `a thread with a waiting draft is marked, and others are not`() {
        val messages = listOf(
            message(id = 2, threadId = 2, address = "079999", date = 200),
            message(id = 1, threadId = 1, address = "079123", date = 100),
        )

        val rows = buildThreadRows(messages, draftThreadIds = setOf(1L)).associateBy { it.threadId }

        assertTrue(rows.getValue(1L).hasDraft)
        assertFalse(rows.getValue(2L).hasDraft)
    }

    @Test
    fun `no drafts means no thread is marked`() {
        val rows = buildThreadRows(listOf(message(id = 1, threadId = 1, date = 100)))
        assertFalse(rows.single().hasDraft)
    }

    // The timestamp label — the bug a real message store caught: a correctly-sorted list looks
    // unsorted when every row shows only a time-of-day.

    @Test
    fun `today's thread shows the time, older ones do not`() {
        val today = LocalDate.of(2026, 8, 5)
        val zone = ZoneId.of("Europe/Zurich")

        assertEquals("19:49", formatThreadTimestamp(at(2026, 8, 5, 19, 49, zone), today, zone))
        assertNotEquals("11:04", formatThreadTimestamp(at(2026, 8, 4, 11, 4, zone), today, zone))
    }

    @Test
    fun `the last few days show a weekday name`() {
        val today = LocalDate.of(2026, 8, 5)
        val zone = ZoneId.of("Europe/Zurich")

        // 2026-08-04 was a Tuesday.
        assertEquals("TUE", formatThreadTimestamp(at(2026, 8, 4, 11, 4, zone), today, zone))
        // Six days back is still a weekday name; seven would be ambiguous with the day just gone,
        // so it becomes a date instead. (2026-08-05 is itself a Wednesday, which makes 07-30 the
        // Thursday six days earlier — the exact pair that would collide at a seven-day window.)
        assertEquals("THU", formatThreadTimestamp(at(2026, 7, 30, 9, 0, zone), today, zone))
        assertEquals("29.07.", formatThreadTimestamp(at(2026, 7, 29, 9, 0, zone), today, zone))
    }

    @Test
    fun `a different year carries the year`() {
        val today = LocalDate.of(2026, 8, 5)
        val zone = ZoneId.of("Europe/Zurich")

        assertEquals("31.12.25", formatThreadTimestamp(at(2025, 12, 31, 23, 0, zone), today, zone))
    }

    @Test
    fun `a future-dated message is never mistaken for today`() {
        val today = LocalDate.of(2026, 8, 5)
        val zone = ZoneId.of("Europe/Zurich")

        // A wrong device clock or a network-supplied timestamp can produce one; it must not fall
        // into the weekday window either, which only looks backwards.
        assertEquals("06.08.", formatThreadTimestamp(at(2026, 8, 6, 9, 0, zone), today, zone))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

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
