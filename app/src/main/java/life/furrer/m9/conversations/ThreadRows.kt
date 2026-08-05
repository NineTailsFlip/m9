package life.furrer.m9.conversations

import android.provider.Telephony
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.m9.sms.OtpCodes
import life.furrer.m9.sms.SmsMessage
import life.furrer.m9.sms.isNumericAddress
import life.furrer.nine.core.util.DateFmt
import life.furrer.nine.core.util.StringMatch

private val OLDER_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.", Locale.getDefault())
private val OTHER_YEAR_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.getDefault())

/** How many days back still get a weekday name rather than a date. Six, not seven, so the label
 *  is never ambiguous: "WED" a full week ago would read as the Wednesday just gone. */
private const val WEEKDAY_LABEL_DAYS = 6

/** The trailing timestamp on a conversation row: the time for today's threads, a weekday name for
 *  the last few days, a date beyond that — and a date carrying the year once it's a different one.
 *
 *  This exists because the conversation list, unlike every other time-ordered list in the suite,
 *  has no day headers to supply the date: a thread is one row, and its rows span whatever dates
 *  the conversations happened on. Confirmed on-device that always showing the time (what this
 *  first shipped as) makes a correctly-sorted list look broken — 19:49 above 11:04 above 14:52
 *  reads as unsorted until you realise those are three different days. */
fun formatThreadTimestamp(date: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
    return when {
        day == today -> DateFmt.formatTime(date)
        day.year != today.year -> day.format(OTHER_YEAR_FORMAT)
        day.isAfter(today.minusDays(WEEKDAY_LABEL_DAYS.toLong() + 1)) && day.isBefore(today) ->
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
        else -> day.format(OLDER_DATE_FORMAT)
    }
}

/** One row of the conversation list: a whole thread, collapsed to its newest message.
 *
 *  Unlike P9's `CallLogRow`/`DialRow` this is a plain data class rather than a sealed hierarchy —
 *  the conversation list has no day headers, no section headers and no typed-value pseudo-row, so
 *  a one-variant `sealed interface` would be ceremony describing a choice that doesn't exist. Add
 *  one the day this list grows a second kind of row, not before. */
data class ThreadRow(
    val threadId: Long,
    /** The thread's correspondent, as it arrived over the air. May be an alphanumeric sender id
     *  ([canCall] is what a screen should ask before offering to dial it). */
    val address: String,
    val displayName: String?,
    val resolvedContactId: Long?,
    /** The newest message's body — what the list shows under the name. */
    val snippet: String,
    /** The newest message's timestamp, which is also what the list sorts on. */
    val date: Long,
    val unreadCount: Int,
    val messageCount: Int,
    /** The one-time code in the newest message, when it has one — so the list can offer to copy
     *  it without opening the thread. Detected once here rather than per render. */
    val code: String? = null,
) {
    /** The name if the address book knows one, else the raw address — never blank. */
    val title: String get() = displayName ?: address

    val hasUnread: Boolean get() = unreadCount > 0

    /** Whether a screen may offer "call this thread" at all. A shop's alphanumeric sender id is
     *  not a phone number and calling it is not a thing that can happen. */
    val canCall: Boolean get() = isNumericAddress(address)
}

/** Groups [messages] into threads, newest thread first.
 *
 *  Grouping happens here, in pure Kotlin over rows the store already read, rather than through
 *  `content://mms-sms/conversations?simple=true` — see docs/m9-plan.md §3. That URI is barely
 *  documented, and its `recipient_ids` column needs a second join against
 *  `canonical-addresses` before it can even name the correspondent, whereas every `Telephony.Sms`
 *  row already carries both `thread_id` and `address`. The cost is that the thread list only
 *  knows about threads within the loaded window ("messages to load" in Settings); the escape
 *  hatch, if a large store ever makes that read too slow on real hardware, is that documented-
 *  enough URI — measure before reaching for it.
 *
 *  [messages] must already be newest-first, as `SmsStore.queryMessages` returns it: the first
 *  message seen for a thread is therefore its newest, which is what makes the snippet and
 *  timestamp a single pass rather than a max-by per group.
 *
 *  [query] filters on the contact name, the address, or any message body in the thread — the
 *  three things someone could plausibly remember about a conversation. Name and number matching
 *  reuse [StringMatch] so a contact matches identically here, in P9's dialer and in C9's contacts
 *  list; body matching is a plain case-insensitive substring, because a message body is prose and
 *  word-prefix rules would make "eight" fail to find "at eight." */
fun buildThreadRows(
    messages: List<SmsMessage>,
    query: String = "",
    unreadOnly: Boolean = false,
): List<ThreadRow> {
    val byThread = LinkedHashMap<Long, MutableList<SmsMessage>>()
    messages.forEach { message ->
        byThread.getOrPut(message.threadId) { mutableListOf() }.add(message)
    }

    return byThread.values
        .map { threadMessages ->
            val newest = threadMessages.first()
            ThreadRow(
                threadId = newest.threadId,
                address = newest.address,
                displayName = newest.displayName,
                resolvedContactId = newest.resolvedContactId,
                snippet = newest.body,
                date = newest.date,
                // Only an inbound message can be unread. An outbound row's READ column is
                // whatever the sending app happened to write, and on some stacks that's 0 —
                // counting it would show a permanent unread badge on a thread you just replied to.
                unreadCount = threadMessages.count { !it.read && !isOutgoing(it) },
                messageCount = threadMessages.size,
                // Only inbound messages carry codes worth copying — a code you sent is not a
                // code you need.
                code = if (isOutgoing(newest)) null else OtpCodes.find(newest.body),
            )
        }
        .filter { row -> !unreadOnly || row.hasUnread }
        .filter { row -> matchesQuery(row, byThread[row.threadId].orEmpty(), query) }
        .sortedByDescending { it.date }
}

private fun isOutgoing(message: SmsMessage): Boolean =
    message.type != Telephony.Sms.MESSAGE_TYPE_INBOX

private fun matchesQuery(row: ThreadRow, threadMessages: List<SmsMessage>, query: String): Boolean {
    if (query.isEmpty()) return true
    if (row.displayName != null && StringMatch.matchesName(row.displayName, query)) return true
    // Gated on an all-digit query the way DialRows gates its own number matching: a query with
    // letters in it should never match by number, or "Anna" would match a thread whose address
    // happens to contain those digits' keypad equivalents.
    if (StringMatch.isAllDigits(query) && StringMatch.matchesPhoneNumber(row.address, query)) return true
    // An alphanumeric sender is matched as a name, since that's what it reads as.
    if (StringMatch.matchesName(row.address, query)) return true
    return threadMessages.any { it.body.contains(query, ignoreCase = true) }
}
