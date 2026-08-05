package life.furrer.m9.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

private const val TAG = "M9SmsResult"

/** Where a send's outcome actually lands.
 *
 *  [SmsSender] attaches one of these per part, per outcome, carrying the outbox row's Uri as the
 *  intent data. The result can arrive minutes later with M9 backgrounded or dead, which is
 *  exactly why the provider row — not any in-memory state — is what gets updated: the same
 *  "never mirror the state, derive it" rule P9 follows for Telecom.
 *
 *  Only failures are given the last word. A multipart message produces one result per part, and
 *  they can disagree; a message where part 2 of 3 failed is a failed message however cheerful the
 *  other two were, so a failure is never overwritten by a later success. */
class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val appContext = context.applicationContext

        when (intent.action) {
            ACTION_SENT -> onSent(appContext, intent, uri, partIndex)
            ACTION_DELIVERED -> onDelivered(appContext, uri, partIndex)
        }
    }

    private fun onSent(context: Context, intent: Intent, uri: android.net.Uri, partIndex: Int) {
        // getResultCode() is the send's own outcome, set by the platform on the PendingIntent —
        // not something in the intent's extras.
        if (resultCode == Activity.RESULT_OK) {
            // Any part still in flight leaves the row in the outbox; the last successful part is
            // what promotes it. Re-reading the row would be racy across parts, so the promotion
            // is idempotent instead: setting SENT twice is harmless, and a FAILED row is never
            // demoted back (see below).
            if (!isFailed(context, uri)) {
                SmsStore.updateSendState(context, uri, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
        } else {
            Log.w(TAG, "Part $partIndex failed to send (${describe(resultCode)}) for $uri")
            SmsStore.updateSendState(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            // Say so. A silent failure is the one thing a messaging app must never do — see
            // MessageNotifications.notifySendFailed.
            SmsStore.queryMessage(context, uri)?.let { message ->
                MessageNotifications.notifySendFailed(context, message.threadId, uri, message.address, message.body)
            }
        }
    }

    /** A delivery report is about the *network's* confirmation, and it only exists when delivery
     *  reports are switched on. A negative one leaves the message SENT — it genuinely was — and
     *  records STATUS_FAILED, which is what makes the thread show it as undelivered rather than
     *  unsent. */
    private fun onDelivered(context: Context, uri: android.net.Uri, partIndex: Int) {
        val delivered = resultCode == Activity.RESULT_OK
        if (!delivered) Log.w(TAG, "Part $partIndex was not delivered for $uri")
        SmsStore.updateSendState(
            context,
            uri,
            Telephony.Sms.MESSAGE_TYPE_SENT,
            if (delivered) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED,
        )
    }

    private fun isFailed(context: Context, uri: android.net.Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(Telephony.Sms.TYPE), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == Telephony.Sms.MESSAGE_TYPE_FAILED
        } ?: false

    /** The platform's send-failure codes, in words — a log line saying "4" helps nobody
     *  diagnosing why their message didn't go out. */
    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        else -> "code $code"
    }

    companion object {
        const val ACTION_SENT = "life.furrer.m9.SMS_SENT"
        const val ACTION_DELIVERED = "life.furrer.m9.SMS_DELIVERED"
        const val EXTRA_PART_INDEX = "part_index"
    }
}
