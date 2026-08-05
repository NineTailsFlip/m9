package life.furrer.m9.sms

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony

/** One row of `Telephony.Sms`, before thread-grouping (see [life.furrer.m9.conversations.ThreadRows]).
 *  [resolvedName]/[resolvedContactId] come from [ContactsContract.PhoneLookup], the same live
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
 *  `StringMatch.looksDialable` does: "is a PhoneLookup even worth attempting", not "may this be
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
     *  Contact resolution is cached per distinct address within one call — one PhoneLookup query
     *  per *distinct* address in the batch rather than one per row — so a long message store
     *  against a large address book doesn't do a provider round trip per message. Exactly what
     *  `CallLogStore.queryCallLog` does, and load-bearing for the same reason: a thread of 200
     *  messages with one correspondent is one lookup, not 200. */
    fun queryMessages(context: Context, limit: Int): List<SmsMessage> {
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
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
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

        val resolved = resolveContacts(context, raw.map { it.address }.distinct())
        return raw.map { message ->
            val match = resolved[message.address]
            message.copy(resolvedName = match?.first, resolvedContactId = match?.second)
        }
    }

    /** One address, one lookup — for callers that have a single address and no reason to read the
     *  message store at all (a notification being built from a broadcast, say). Returns null for
     *  an alphanumeric sender, an unknown number, or a missing READ_CONTACTS. */
    fun lookupContactName(context: Context, address: String): String? =
        resolveContacts(context, listOf(address))[address]?.first

    private fun resolveContacts(context: Context, addresses: List<String>): Map<String, Pair<String, Long>> {
        if (addresses.isEmpty()) return emptyMap()
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()

        val result = mutableMapOf<String, Pair<String, Long>>()
        addresses.forEach { address ->
            // An alphanumeric sender can't match a phone number, so the query is skipped rather
            // than issued and thrown away — PhoneLookup would answer "no match" for it anyway,
            // but a shop's marketing SMS shouldn't cost a provider round trip to learn that.
            if (address.isBlank() || !isNumericAddress(address)) return@forEach
            val uri: Uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val name = cursor.getString(nameIndex)
                    if (name != null) result[address] = name to cursor.getLong(idIndex)
                }
            }
        }
        return result
    }

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
