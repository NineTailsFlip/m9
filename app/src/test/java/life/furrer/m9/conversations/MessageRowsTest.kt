package life.furrer.m9.conversations

import android.provider.Telephony
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.m9.sms.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure — plain JUnit, no Android provider access. */
class MessageRowsTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val today = LocalDate.of(2026, 8, 5)

    @Test
    fun `a conversation reads oldest first, the opposite of every other list in the suite`() {
        val rows = buildMessageRows(
            listOf(
                message(id = 2, body = "newer", date = at(2026, 8, 5, 14, 0)),
                message(id = 1, body = "older", date = at(2026, 8, 5, 9, 0)),
            ),
            today,
            zone,
        )

        assertEquals(
            listOf("TODAY", "older", "newer"),
            rows.map { if (it is MessageRow.DayHeader) it.label else (it as MessageRow.Entry).body },
        )
    }

    @Test
    fun `a day header opens each new day, once`() {
        val rows = buildMessageRows(
            listOf(
                message(id = 3, body = "c", date = at(2026, 8, 5, 9, 0)),
                message(id = 2, body = "b", date = at(2026, 8, 4, 18, 0)),
                message(id = 1, body = "a", date = at(2026, 8, 4, 9, 0)),
            ),
            today,
            zone,
        )

        assertEquals(
            listOf("YESTERDAY", "a", "b", "TODAY", "c"),
            rows.map { if (it is MessageRow.DayHeader) it.label else (it as MessageRow.Entry).body },
        )
    }

    @Test
    fun `inbound and outbound are distinguished`() {
        val rows = buildMessageRows(
            listOf(
                message(id = 2, date = at(2026, 8, 5, 10, 0), type = Telephony.Sms.MESSAGE_TYPE_SENT),
                message(id = 1, date = at(2026, 8, 5, 9, 0), type = Telephony.Sms.MESSAGE_TYPE_INBOX),
            ),
            today,
            zone,
        )
        val entries = rows.filterIsInstance<MessageRow.Entry>()

        assertFalse(entries[0].outgoing)
        assertTrue(entries[1].outgoing)
    }

    @Test
    fun `only entries are selectable`() {
        val rows = buildMessageRows(listOf(message(id = 1, date = at(2026, 8, 5, 9, 0))), today, zone)

        assertFalse(rows.first { it is MessageRow.DayHeader }.isSelectable())
        assertTrue(rows.first { it is MessageRow.Entry }.isSelectable())
    }

    // The TYPE/STATUS mapping below is what M4's send lifecycle writes into. Each case is one
    // real provider state, not a hypothetical combination.

    @Test
    fun `an inbound message is RECEIVED regardless of its STATUS column`() {
        // STATUS is meaningful for outbound rows only; an inbound row carries STATUS_NONE and it
        // must never be read as "not delivered".
        assertEquals(
            SendStatus.RECEIVED,
            sendStatusOf(Telephony.Sms.MESSAGE_TYPE_INBOX, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `queued and outbox both mean sending`() {
        assertEquals(SendStatus.SENDING, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.STATUS_NONE))
        assertEquals(SendStatus.SENDING, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.STATUS_NONE))
    }

    @Test
    fun `a sent message is SENT without a delivery report and DELIVERED with one`() {
        assertEquals(SendStatus.SENT, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_NONE))
        assertEquals(SendStatus.SENT, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_PENDING))
        assertEquals(SendStatus.DELIVERED, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_COMPLETE))
    }

    @Test
    fun `a negative delivery report on a sent message is a failure the user must see`() {
        assertEquals(SendStatus.FAILED, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_FAILED))
    }

    @Test
    fun `a failed send is FAILED, and a draft is DRAFT`() {
        assertEquals(SendStatus.FAILED, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_NONE))
        assertEquals(SendStatus.DRAFT, sendStatusOf(Telephony.Sms.MESSAGE_TYPE_DRAFT, Telephony.Sms.STATUS_NONE))
    }

    @Test
    fun `only outbound rows carry a status glyph`() {
        assertEquals("", sendStatusGlyph(SendStatus.RECEIVED))
        assertEquals("✓", sendStatusGlyph(SendStatus.SENT))
        assertEquals("✓✓", sendStatusGlyph(SendStatus.DELIVERED))
        assertEquals("!", sendStatusGlyph(SendStatus.FAILED))
    }

    @Test
    fun `day jump lands on a day's first message, not its header`() {
        val rows = buildMessageRows(
            listOf(
                message(id = 3, body = "c", date = at(2026, 8, 5, 9, 0)),
                message(id = 2, body = "b", date = at(2026, 8, 4, 18, 0)),
                message(id = 1, body = "a", date = at(2026, 8, 4, 9, 0)),
            ),
            today,
            zone,
        )
        // rows: [YESTERDAY, a, b, TODAY, c]
        val fromFirstDay = rows.indexOfFirst { it is MessageRow.Entry }

        val forward = dayJumpIndex(rows, fromFirstDay, 1)
        assertTrue(rows[forward!!] is MessageRow.Entry)
        assertEquals("c", (rows[forward] as MessageRow.Entry).body)

        val back = dayJumpIndex(rows, forward, -1)
        assertEquals("a", (rows[back!!] as MessageRow.Entry).body)
    }

    @Test
    fun `day jump stops at the ends rather than wrapping`() {
        val rows = buildMessageRows(listOf(message(id = 1, date = at(2026, 8, 5, 9, 0))), today, zone)
        val only = rows.indexOfFirst { it is MessageRow.Entry }

        assertNull(dayJumpIndex(rows, only, 1))
        assertNull(dayJumpIndex(rows, only, -1))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun message(
        id: Long,
        body: String = "body",
        date: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status: Int = Telephony.Sms.STATUS_NONE,
    ) = SmsMessage(
        id = id,
        threadId = 1L,
        address = "079123",
        body = body,
        date = date,
        dateSent = 0,
        type = type,
        status = status,
        read = true,
        seen = true,
        subscriptionId = 1,
        resolvedName = null,
        resolvedContactId = null,
    )
}
