package life.furrer.m9.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony

/** A minimal in-memory `Telephony.Sms` provider for tests — Robolectric ships no working SMS
 *  provider, the same gap `FakeCallLogProvider`/`FakeCalendarProvider`/`FakeTasksProvider`
 *  already work around in the other apps, and this follows their shape.
 *
 *  Always returns every row newest-first regardless of the literal sortOrder string
 *  [SmsStore.queryMessages] passes ("date DESC LIMIT n") — good enough for the small datasets
 *  these tests use, exactly as FakeCallLogProvider does. The one place that isn't good enough is
 *  the limit itself, which real callers rely on, so [query] honours the `LIMIT n` suffix rather
 *  than ignoring the whole string.
 *
 *  Unlike the real provider it accepts writes from anyone: the platform silently drops writes
 *  from an app without ROLE_SMS, which is a property of the device, not of SmsStore's code, and
 *  a fake that reproduced it could only ever assert "nothing happened". */
class FakeSmsProvider : ContentProvider() {

    companion object {
        val messages = mutableMapOf<Long, ContentValues>()

        fun reset() = messages.clear()

        @Suppress("LongParameterList")
        fun putMessage(
            id: Long,
            threadId: Long,
            address: String,
            body: String,
            date: Long,
            type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status: Int = Telephony.Sms.STATUS_NONE,
            read: Boolean = true,
            seen: Boolean = true,
            dateSent: Long = 0L,
            subscriptionId: Int = 1,
        ) {
            messages[id] = ContentValues().apply {
                put(Telephony.Sms.THREAD_ID, threadId)
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, dateSent)
                put(Telephony.Sms.TYPE, type)
                put(Telephony.Sms.STATUS, status)
                put(Telephony.Sms.READ, if (read) 1 else 0)
                put(Telephony.Sms.SEEN, if (seen) 1 else 0)
                put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
        }

        fun rowsOf(threadId: Long): List<ContentValues> =
            messages.values.filter { it.getAsLong(Telephony.Sms.THREAD_ID) == threadId }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.STATUS,
            Telephony.Sms.READ, Telephony.Sms.SEEN, Telephony.Sms.SUBSCRIPTION_ID,
        )
        val cursor = MatrixCursor(cols)
        val limit = Regex("LIMIT (\\d+)").find(sortOrder ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        messages.entries
            .sortedByDescending { it.value.getAsLong(Telephony.Sms.DATE) }
            .take(limit)
            .forEach { (id, values) ->
                cursor.addRow(cols.map { col -> if (col == Telephony.Sms._ID) id else values.get(col) })
            }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    /** Only the `thread_id = ?` selection [SmsStore.markThreadRead] builds is understood — a fake
     *  that pretended to parse arbitrary SQL would be a worse lie than one that doesn't try. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val threadId = selectionArgs?.firstOrNull()?.toLongOrNull() ?: return 0
        if (values == null) return 0
        var updated = 0
        messages.values.filter { it.getAsLong(Telephony.Sms.THREAD_ID) == threadId }.forEach { row ->
            row.putAll(values)
            updated++
        }
        return updated
    }

    /** A single-id URI (what deleteMessage builds) deletes that row; the bare CONTENT_URI with a
     *  `thread_id = ?` selection (what deleteThread builds) deletes the whole thread. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = uri.lastPathSegment?.toLongOrNull()
        if (id != null) return if (messages.remove(id) != null) 1 else 0

        val threadId = selectionArgs?.firstOrNull()?.toLongOrNull() ?: return 0
        val doomed = messages.filterValues { it.getAsLong(Telephony.Sms.THREAD_ID) == threadId }.keys.toList()
        doomed.forEach { messages.remove(it) }
        return doomed.size
    }

    override fun getType(uri: Uri): String? = null
}
