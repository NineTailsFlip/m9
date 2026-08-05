package life.furrer.m9.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import life.furrer.m9.core.Prefs

private const val TAG = "M9SmsSender"

/** Sends a text message, and records what happened to it.
 *
 *  The order here is deliberate and is the whole design: **insert into the outbox first, then hand
 *  the message to the radio.** A crash, a kill, or a battery death between the two leaves a
 *  visible outbox message the user can resend — the other order leaves a message that was sent
 *  but that nothing on the phone remembers, or nothing at all.
 *
 *  Results arrive asynchronously at [SmsResultReceiver], possibly minutes later and with M9 long
 *  since backgrounded, which is why the row's Uri travels on the PendingIntent rather than being
 *  held anywhere in memory. */
object SmsSender {

    /** Sends [body] to [address], returning the outbox row's Uri, or null if it couldn't even be
     *  stored — in which case nothing was sent either, by construction. */
    fun send(context: Context, threadId: Long, address: String, body: String): Uri? {
        val subscriptionId = Prefs.sendingSubscriptionId(context)

        val uri = SmsStore.insertOutbox(context, threadId, address, body, subscriptionId)
        if (uri == null) {
            // With the role held this shouldn't happen; without it, the provider drops the write
            // silently and this is the only place that says so.
            Log.e(TAG, "Could not store an outgoing message — not sending it either")
            return null
        }

        val manager = smsManager(context, subscriptionId)
        if (manager == null) {
            Log.e(TAG, "No SmsManager available — marking the message failed")
            SmsStore.updateSendState(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            return uri
        }

        return try {
            // divideMessage is the platform's own splitter, and the only thing that agrees
            // exactly with the encoding rules SmsSegments models for the composer's counter.
            val parts = manager.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveryIntents = ArrayList<PendingIntent>(parts.size)
            val wantDeliveryReports = Prefs.deliveryReports(context)

            parts.indices.forEach { index ->
                sentIntents.add(resultIntent(context, uri, SmsResultReceiver.ACTION_SENT, index))
                deliveryIntents.add(resultIntent(context, uri, SmsResultReceiver.ACTION_DELIVERED, index))
            }

            manager.sendMultipartTextMessage(
                address,
                null,
                parts,
                sentIntents,
                if (wantDeliveryReports) deliveryIntents else null,
            )
            uri
        } catch (t: Throwable) {
            // An invalid destination, an empty body, or a radio that refused it. The message is
            // already in the outbox, so failing it here is what turns it into a resendable row
            // rather than one stuck at "sending" forever.
            Log.e(TAG, "Send threw for $address", t)
            SmsStore.updateSendState(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            uri
        }
    }

    /** Re-sends a message that failed, reusing its existing row: it goes back to the outbox and
     *  through the same path, so a resent message doesn't duplicate itself in the thread. */
    fun resend(context: Context, uri: Uri, address: String, body: String): Boolean {
        SmsStore.updateSendState(context, uri, Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.STATUS_NONE)
        val manager = smsManager(context, Prefs.sendingSubscriptionId(context)) ?: return false
        return try {
            val parts = manager.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents.add(resultIntent(context, uri, SmsResultReceiver.ACTION_SENT, index))
            }
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Resend threw for $address", t)
            SmsStore.updateSendState(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            false
        }
    }

    /** `SmsManager.getDefault()` is deprecated and picks a SIM the user didn't choose; the
     *  subscription-scoped instance is what makes "send from this SIM" mean anything on a dual-SIM
     *  phone. A subscription id of -1 means "no preference recorded", so the system default it
     *  is. */
    private fun smsManager(context: Context, subscriptionId: Int): SmsManager? {
        val manager = context.getSystemService(SmsManager::class.java) ?: return null
        return if (subscriptionId >= 0) manager.createForSubscriptionId(subscriptionId) else manager
    }

    /** One PendingIntent per part per outcome. The request code has to be distinct per
     *  (row, part, action) or `FLAG_UPDATE_CURRENT` would collapse them into one and every part's
     *  result would report against whichever was created last. */
    private fun resultIntent(context: Context, uri: Uri, action: String, partIndex: Int): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java).apply {
            this.action = action
            data = uri
            putExtra(SmsResultReceiver.EXTRA_PART_INDEX, partIndex)
        }
        val requestCode = (uri.hashCode() * 31 + partIndex) * 31 + action.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
