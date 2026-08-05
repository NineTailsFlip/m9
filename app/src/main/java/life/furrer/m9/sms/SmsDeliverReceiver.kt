package life.furrer.m9.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage as PlatformSmsMessage
import android.util.Log

private const val TAG = "M9SmsDeliver"

/** What actually arrives when a text message does — and, while M9 holds `ROLE_SMS`, the only
 *  thing standing between that message and oblivion.
 *
 *  `SMS_DELIVER` goes to the default SMS app alone, and the platform does **not** write the
 *  provider on its behalf: storing the message is this receiver's job. Every path out of here has
 *  to end in something persisted. That's why the catch below is broad and logs loudly rather than
 *  narrow and quiet — the rule from docs/m9-plan.md is that a `catch` which logs and returns is a
 *  lost message the user will never know about, so if this ever fails it should at least be
 *  findable in a bug report.
 *
 *  [goAsync] because the work is a provider insert plus a PhoneLookup plus a notification, which
 *  is more than a receiver's synchronous main-thread budget should be spent on — and a receiver
 *  killed mid-insert is exactly the failure this whole class exists to avoid. */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) {
            Log.w(TAG, "SMS_DELIVER with no messages in it — nothing to store")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                store(appContext, parts, intent)
            } catch (t: Throwable) {
                // Deliberately broad: whatever went wrong, the alternative to logging it is a
                // message that silently never existed.
                Log.e(TAG, "Failed to store an incoming message", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun store(context: Context, parts: Array<PlatformSmsMessage>, intent: Intent) {
        val first = parts.first()
        // A multipart message arrives as several parts of one logical message; concatenating the
        // bodies is what makes it one row rather than three fragments in the thread. displayMessageBody
        // (not messageBody) is the decoded form, which is what a reader wants.
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val address = first.displayOriginatingAddress ?: first.originatingAddress.orEmpty()
        val subscriptionId = intent.getIntExtra("subscription", -1)

        // getOrCreateThreadId is the platform's own canonical answer to "which conversation is
        // this" — deriving it by string-matching addresses would disagree with every other SMS
        // app on the phone the first time a number appeared in two formats.
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)

        val uri = SmsStore.insertInbound(
            context = context,
            threadId = threadId,
            address = address,
            body = body,
            sentAt = first.timestampMillis,
            receivedAt = System.currentTimeMillis(),
            subscriptionId = subscriptionId,
        )
        if (uri == null) {
            Log.e(TAG, "Provider refused the insert for a message from $address — message lost")
            return
        }

        MessageNotifications.notifyIncoming(
            context = context,
            threadId = threadId,
            address = address,
            body = body.trim(),
        )
    }
}
