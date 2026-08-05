package life.furrer.m9.conversations

import android.provider.Telephony
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.m9.sms.SmsMessage
import life.furrer.nine.core.util.DateFmt

/** Where one message sits in its own lifecycle. Derived from the provider's TYPE and STATUS
 *  columns together (see [sendStatusOf]) rather than stored anywhere — the provider row is the
 *  source of truth, per docs/m9-plan.md §7, because a send can fail minutes after the screen
 *  that started it is gone. */
enum class SendStatus {
    /** Inbound. Not a send state at all, but every message needs one answer to "what is this". */
    RECEIVED,

    /** Queued or in the outbox: handed to the radio, no result yet. */
    SENDING,

    /** Sent, with no delivery report to say more — either none was requested or none arrived. */
    SENT,

    /** Sent *and* the network confirmed delivery. Only ever reachable with delivery reports on. */
    DELIVERED,

    /** The send failed. The only state that offers the user an action (Resend). */
    FAILED,

    /** A draft some app (M9 or another) left in this thread. M9 doesn't create these in v1, but
     *  the provider is shared, so a thread can contain one and the list must render it. */
    DRAFT,
}

/** The single place TYPE and STATUS are turned into one answer. Kept together and documented
 *  because M4 (sending) writes the very transitions this reads, and the two halves have to agree:
 *  a message moves `Outbox` → `Sent`, and its STATUS moves `NONE` → `COMPLETE` only if a delivery
 *  report comes back. Note STATUS is meaningful for *outbound* rows only — an inbound message
 *  carries `STATUS_NONE`, which must not be read as "not delivered". */
fun sendStatusOf(type: Int, status: Int): SendStatus = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> SendStatus.RECEIVED
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> SendStatus.DRAFT
    Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.MESSAGE_TYPE_OUTBOX -> SendStatus.SENDING
    Telephony.Sms.MESSAGE_TYPE_FAILED -> SendStatus.FAILED
    Telephony.Sms.MESSAGE_TYPE_SENT -> when (status) {
        Telephony.Sms.STATUS_COMPLETE -> SendStatus.DELIVERED
        // A delivery report can come back negative, which the provider records on an otherwise
        // successfully-*sent* row — the send worked, the delivery didn't, and the user needs to
        // know the second thing.
        Telephony.Sms.STATUS_FAILED -> SendStatus.FAILED
        else -> SendStatus.SENT
    }
    else -> SendStatus.SENT
}

/** ✓ sent, ✓✓ delivered, … sending, ! failed — the trailing marker on an outbound row. Inbound
 *  rows get nothing: their existence is the whole story, and a glyph on every row would be noise
 *  on a screen this narrow. */
fun sendStatusGlyph(status: SendStatus): String = when (status) {
    SendStatus.RECEIVED -> ""
    SendStatus.SENDING -> "…"
    SendStatus.SENT -> "✓"
    SendStatus.DELIVERED -> "✓✓"
    SendStatus.FAILED -> "!"
    SendStatus.DRAFT -> "✎"
}

/** One row of a single thread's message list — a day header, or one message. Pure,
 *  messages-in/rows-out, the same shape P9's `CallLogRows`/`DialRows` use.
 *
 *  Ordering is the one place this deliberately differs from every other list in the suite: a
 *  conversation reads oldest→newest, top to bottom, because that's how a conversation happened
 *  and because the newest message — the one you just received — belongs at the bottom next to
 *  the compose row that answers it. Every other time-ordered list in Nine is newest-first. */
sealed interface MessageRow {
    data class DayHeader(val label: String) : MessageRow
    data class Entry(
        val id: Long,
        val body: String,
        val date: Long,
        val outgoing: Boolean,
        val status: SendStatus,
        val subscriptionId: Int,
    ) : MessageRow
}

fun MessageRow.isSelectable(): Boolean = this is MessageRow.Entry

/** [messages] arrives newest-first (as `SmsStore.queryMessages` returns it) and is reversed here
 *  rather than at the call site, so the "conversations read downwards" decision above lives in
 *  exactly one place. */
fun buildMessageRows(
    messages: List<SmsMessage>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MessageRow> {
    val rows = mutableListOf<MessageRow>()
    var currentDay: LocalDate? = null
    messages.sortedBy { it.date }.forEach { message ->
        val day = Instant.ofEpochMilli(message.date).atZone(zone).toLocalDate()
        if (day != currentDay) {
            rows.add(MessageRow.DayHeader(DateFmt.dayLabel(day, today)))
            currentDay = day
        }
        rows.add(
            MessageRow.Entry(
                id = message.id,
                body = message.body,
                date = message.date,
                outgoing = message.type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                status = sendStatusOf(message.type, message.status),
                subscriptionId = message.subscriptionId,
            ),
        )
    }
    return rows
}
