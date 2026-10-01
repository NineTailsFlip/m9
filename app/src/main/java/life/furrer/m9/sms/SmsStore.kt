package life.furrer.m9.sms

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import life.furrer.nine.contacts.ContactStore
import life.furrer.nine.contacts.resolveNumbers

/** One row of `Telephony.Sms`, before thread-grouping (see [life.furrer.m9.conversations.ThreadRows]).
 *  [resolvedName]/[resolvedContactId] come from [ContactStore.resolveNumbers], the same live
 *  address-book answer P9's `CallLogStore` resolves for a call log entry — and for the same
 *  reason it doesn't trust the log's own cached label. The SMS provider's
 *  nearest equivalent, `Sms.PERSON`, is worse still: it's a raw contacts id written by whichever
 *  app stored the message, with nothing keeping it correct across a contact merge, a re-sync or a
 *  delete, so it's deliberately not read here at all. */
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    /** Whatever arrived over the air: `+41791234567`, `079 123 45 67`, or an alphanumeric sender
     *  id like `Migros`. Never assume this is dialable — see [isNumericAddress]. */
    val address: String,
    val body: String,
    val date: Long,
    /** When the sender's network says it was sent, as opposed to when this device stored it.
     *  0 when the provider has no value, which is common for older rows. */
    val dateSent: Long,
    val type: Int,
    val status: Int,
    val read: Boolean,
    val seen: Boolean,
    val subscriptionId: Int,
    val resolvedName: String?,
    val resolvedContactId: Long?,
) {
    /** What a list actually shows: the address book's live answer, or nothing — in which case the
     *  caller shows [address] on its own. Unlike a call log row there is no cached-name fallback
     *  to try (see the class comment). */
    val displayName: String? get() = resolvedName
}

/** True when [address] contains at least one digit, i.e. when it could be a phone number at all.
 *  Alphanumeric sender ids (`Migros`, `Swisscom`, `AUTHMSG`) are ordinary, common SMS senders:
 *  they can never be dialed, never resolve to a contact, and must never reach a code path that
 *  assumes a number — the rule docs/m9-plan.md's "Rules for implementers" spells out. Kept here
 *  rather than in `StringMatch` because this asks a weaker question than
 *  `StringMatch.looksDialable` does: "is a contact lookup even worth attempting", not "may this be
 *  dialed". */
fun isNumericAddress(address: String): Boolean = address.any { it.isDigit() }

object SmsStore {

    fun hasReadPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Newest first, capped by [limit] (the Settings "messages to load" count). Thread grouping
     *  happens in pure Kotlin afterwards rather than through `content://mms-sms/conversations`,
     *  per docs/m9-plan.md §3: that URI is barely documented and needs a second join against
     *  `canonical-addresses` to get an address at all, whereas every row here already carries
     *  its own `thread_id` and `address`.
     *
     *  Contact resolution is one batched read of the address book's phone table for every
     *  address at once ([ContactStore.resolveNumbers]) — not a PhoneLookup round trip per distinct
     *  address, which cost ~15ms each and was the bulk of this load. Exactly what
     *  `CallLogStore.queryCallLog` does.
     *
     *  [threadId]/[messageId] narrow the read in the provider itself, for the screens that only
     *  ever show one thread or one message: besides not reading (and resolving) the whole store to
     *  throw most of it away, it means a thread shows its own newest [limit] messages, not
     *  whichever of them happen to fall inside the whole store's newest [limit]. */
    fun queryMessages(context: Context, limit: Int, threadId: Long? = null, messageId: Long? = null): List<SmsMessage> {
        if (!hasReadPermission(context)) return emptyList()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.READ,
            Telephony.Sms.SEEN,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        val raw = mutableListOf<SmsMessage>()
        val filters = listOfNotNull(
            threadId?.let { "${Telephony.Sms.THREAD_ID} = ?" to it.toString() },
            messageId?.let { "${Telephony.Sms._ID} = ?" to it.toString() },
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            filters.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { it.first },
            filters.takeIf { it.isNotEmpty() }?.map { it.second }?.toTypedArray(),
            "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val dateSentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val statusIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val seenIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN)
            val subscriptionIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            while (cursor.moveToNext()) {
                raw.add(
                    SmsMessage(
                        id = cursor.getLong(idIndex),
                        threadId = cursor.getLong(threadIndex),
                        address = cursor.getString(addressIndex) ?: "",
                        // Trimmed at the boundary, so every consumer — the list snippet, the
                        // thread row, the detail screen, search — sees the same normalized body
                        // and none of them has to remember to do it. Senders pad messages with
                        // leading and trailing newlines routinely; on a screen this small that
                        // costs real, visible space, and a trailing newline is measured as a
                        // genuine line by the thread row's own height measurement, making the row
                        // taller than the text it holds. Internal whitespace is untouched — a
                        // multi-line message is meant to be multi-line.
                        //
                        // This normalizes M9's *view* of the message, never the provider: the
                        // stored row keeps whatever arrived over the air.
                        body = cursor.getString(bodyIndex)?.trim() ?: "",
                        date = cursor.getLong(dateIndex),
                        dateSent = cursor.getLong(dateSentIndex),
                        type = cursor.getInt(typeIndex),
                        status = cursor.getInt(statusIndex),
                        read = cursor.getInt(readIndex) != 0,
                        seen = cursor.getInt(seenIndex) != 0,
                        subscriptionId = cursor.getInt(subscriptionIndex),
                        resolvedName = null,
                        resolvedContactId = null,
                    ),
                )
            }
        }

        val resolved = ContactStore.resolveNumbers(context, raw.map { it.address })
        return raw.map { message ->
            val match = resolved[message.address]
            message.copy(resolvedName = match?.displayName, resolvedContactId = match?.contactId)
        }
    }

    /** One address, for callers that have a single address and no reason to read the message
     *  store at all (a notification being built from a broadcast, say). Returns null for an
     *  alphanumeric sender, an unknown number, or a missing READ_CONTACTS. */
    fun lookupContactName(context: Context, address: String): String? =
        ContactStore.resolveNumbers(context, listOf(address))[address]?.displayName

    // ---------------------------------------------------------------------------------------
    // Writes.
    //
    // Every function below is a no-op on a phone where M9 does not hold RoleManager.ROLE_SMS:
    // since KitKat the SMS provider silently drops writes from an app that isn't the default SMS
    // app — no exception, no error return, just nothing happening (docs/m9-plan.md §1). That is
    // expected and correct at M2, where M9 is a read-only viewer, and none of this code changes
    // when the role arrives in M3. What it does mean is that a caller must never treat a `false`
    // here as "the message is gone" — re-read the provider instead of assuming, the same
    // "never mirror the state, derive it" rule P9 has for Telecom.
    // ---------------------------------------------------------------------------------------

    /** Stores one inbound message. This is M9's job and nobody else's once it holds the role: the
     *  platform hands `SMS_DELIVER` to the default SMS app and does not write the provider
     *  itself, so a message that isn't inserted here is simply gone — see [SmsDeliverReceiver],
     *  whose whole design is about never reaching an early return without having called this.
     *
     *  [sentAt] is the sender's network timestamp and [receivedAt] this device's clock; both are
     *  stored, because they disagree often enough to matter (a message delayed in the network, or
     *  a phone that was off) and only the second one is a reliable sort key.
     *
     *  Returns the new row's Uri, or null if the insert was refused — which, if it ever happens
     *  with the role held, is a lost message and worth surfacing rather than swallowing. */
    fun insertInbound(
        context: Context,
        threadId: Long,
        address: String,
        body: String,
        sentAt: Long,
        receivedAt: Long,
        subscriptionId: Int,
    ): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, receivedAt)
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            // Unread and unseen: the notification is what clears SEEN, opening the thread is what
            // clears READ (see markThreadRead).
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        return context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    /** Reads one row back by its Uri — for a caller holding only the reference a PendingIntent
     *  carried, which is every path that has to act on a send's outcome after the fact. */
    fun queryMessage(context: Context, uri: Uri): SmsMessage? {
        if (!hasReadPermission(context)) return null
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun str(column: String) = cursor.getColumnIndex(column).let { if (it >= 0) cursor.getString(it) else null }
            fun num(column: String) = cursor.getColumnIndex(column).let { if (it >= 0) cursor.getLong(it) else 0L }
            SmsMessage(
                id = num(Telephony.Sms._ID),
                threadId = num(Telephony.Sms.THREAD_ID),
                address = str(Telephony.Sms.ADDRESS).orEmpty(),
                body = str(Telephony.Sms.BODY)?.trim().orEmpty(),
                date = num(Telephony.Sms.DATE),
                dateSent = num(Telephony.Sms.DATE_SENT),
                type = num(Telephony.Sms.TYPE).toInt(),
                status = num(Telephony.Sms.STATUS).toInt(),
                read = num(Telephony.Sms.READ) != 0L,
                seen = num(Telephony.Sms.SEEN) != 0L,
                subscriptionId = num(Telephony.Sms.SUBSCRIPTION_ID).toInt(),
                resolvedName = null,
                resolvedContactId = null,
            )
        }
    }

    /** Stores an outgoing message in the outbox *before* it is handed to the radio, so a crash or
     *  a kill mid-send leaves a visible message the user can resend rather than nothing at all.
     *  [SmsSender] moves the row on from here as results arrive.
     *
     *  Outbound rows are stored read and seen: the user wrote it, so there is nothing to notify
     *  them about and it must never count toward a thread's unread badge. */
    fun insertOutbox(
        context: Context,
        threadId: Long,
        address: String,
        body: String,
        subscriptionId: Int,
    ): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        return context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
    }

    /** Moves a message between the states of a send. The provider row is the source of truth for
     *  a send's outcome (docs/m9-plan.md §7) — a result can arrive minutes after the screen that
     *  started it is gone, so nothing about it is ever held in an Activity field.
     *
     *  Note the deliberate separation of TYPE and STATUS: a *sent* message whose delivery report
     *  came back negative stays `MESSAGE_TYPE_SENT` with `STATUS_FAILED`, because the send did
     *  work and only the delivery didn't — [sendStatusOf] is what reads the pair back. */
    fun updateSendState(context: Context, uri: Uri, type: Int, status: Int? = null): Boolean {
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, type)
            if (status != null) put(Telephony.Sms.STATUS, status)
        }
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    /** READ and SEEN are different columns and both matter: SEEN is "the user knows this exists"
     *  (cleared by a notification being shown), READ is "the user has opened it". Opening a
     *  thread satisfies both, so this sets both — a thread marked READ but not SEEN would keep
     *  its notification alive after being read. */
    fun markThreadRead(context: Context, threadId: Long): Boolean {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        return context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
        ) > 0
    }

    /** The inverse: marks a thread unread again, for someone who opened it by accident and wants
     *  it back in their "still to deal with" list. Only READ is cleared, not SEEN — the user has
     *  demonstrably seen that it exists, and clearing SEEN would resurrect a notification for a
     *  message they just looked at. */
    fun markThreadUnread(context: Context, threadId: Long): Boolean {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 0) }
        return context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
        ) > 0
    }

    /** Deletes every message in the thread and lets the provider retire the thread row itself.
     *  `Telephony.Threads` is never written directly — it's derived bookkeeping, and a frontend
     *  writing it is the SMS equivalent of T9 setting a sync-adapter column by hand. */
    fun deleteThread(context: Context, threadId: Long): Boolean =
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
        ) > 0

    fun deleteMessage(context: Context, id: Long): Boolean =
        context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) > 0
}
