package life.furrer.m9.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import life.furrer.m9.R
import life.furrer.m9.conversations.ConversationActivity
import life.furrer.m9.core.Prefs

private const val CHANNEL_INCOMING = "incoming_messages"
private const val CHANNEL_MMS = "unsupported_mms"
private const val CHANNEL_SEND_FAILED = "send_failures"

/** The MMS notification's own fixed id — one is enough, since every one of them says the same
 *  thing and a stack of them would be noise about a limitation the user already knows about. */
private const val NOTIFICATION_ID_MMS = 1

/** Keeps the copy action's PendingIntent request code from colliding with the open-thread one
 *  built from the same thread id. */
private const val COPY_REQUEST_SALT = 0x0C0DE

/** Keeps a thread's failure notification from replacing its incoming-message one — they are
 *  about different things and can be true at the same time. */
private const val SEND_FAILED_ID_SALT = 0x0FA11
private const val RESEND_REQUEST_SALT = 0x0E5E4D

/** Incoming-message notifications. Modelled on P9's `CallNotifications`, including reusing
 *  `ic_launcher_monochrome` as the small icon — it's already exactly the white-on-transparent
 *  silhouette a notification icon needs, so no separate asset exists.
 *
 *  Deliberately *not* a full-screen intent, unlike P9's ringing notification: a text message is
 *  not a call, and taking over the screen for one would be wrong on a phone that lives in a
 *  pocket. One notification per thread, so a talkative correspondent updates theirs instead of
 *  stacking; opening the conversation retires it. */
object MessageNotifications {

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Incoming messages", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            // Low importance: this one reports a limitation, and it should never interrupt.
            NotificationChannel(CHANNEL_MMS, "Multimedia messages", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            // High, deliberately: a message you believe you sent and haven't is the one failure
            // in this app that can't wait to be noticed.
            NotificationChannel(CHANNEL_SEND_FAILED, "Failed messages", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** A send failed. Without this the failure is completely silent — the row is marked FAILED and
     *  nothing tells anyone, so a message written in a lift or a tunnel looks sent until the
     *  thread is next opened and a "!" is noticed. For an app whose whole job is delivering
     *  messages, that's the failure mode worth spending a notification channel on.
     *
     *  Carries a Resend action, because "try again" is the only thing anyone wants to do here and
     *  it shouldn't cost opening the app. */
    fun notifySendFailed(context: Context, threadId: Long, uri: android.net.Uri, address: String, body: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val contactName = SmsStore.lookupContactName(context, address)
        val resendIntent = Intent(context, ResendReceiver::class.java).apply {
            action = ResendReceiver.ACTION_RESEND
            data = uri
        }
        val resendPending = PendingIntent.getBroadcast(
            context,
            threadId.toInt() xor RESEND_REQUEST_SALT,
            resendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openThread = PendingIntent.getActivity(
            context,
            threadId.toInt() xor SEND_FAILED_ID_SALT,
            ConversationActivity.intentFor(context, threadId, address, contactName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_SEND_FAILED)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Message not sent")
            .setContentText("To ${contactName ?: address}: $body")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openThread)
            .setCategory(Notification.CATEGORY_ERROR)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Resend", resendPending).build())
            .build()

        manager.notify(sendFailureId(threadId), notification)
    }

    fun cancelSendFailure(context: Context, threadId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(sendFailureId(threadId))
    }

    private fun sendFailureId(threadId: Long): Int = threadId.toInt() xor SEND_FAILED_ID_SALT

    /** One notification per thread, keyed by [threadId], so a second message from the same person
     *  updates their notification instead of stacking a new one. */
    fun notifyIncoming(context: Context, threadId: Long, address: String, body: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val contactName = SmsStore.lookupContactName(context, address)
        val sender = Person.Builder().setName(contactName ?: address).build()
        // Who it's from is never hidden — that's what makes the notification worth having at all,
        // and it's already on the lock screen of every phone. Only the text is optional.
        val shown = if (Prefs.showMessageInNotification(context)) body else "New message"
        val style = Notification.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage(shown, System.currentTimeMillis(), sender)

        val openThread = PendingIntent.getActivity(
            context,
            // The thread id as the request code, so each thread's PendingIntent is distinct —
            // without it, FLAG_UPDATE_CURRENT would make every notification open whichever
            // conversation was notified last.
            threadId.toInt(),
            ConversationActivity.intentFor(context, threadId, address, contactName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setStyle(style)
            .setContentIntent(openThread)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)

        // "Copy" on a verification message — the whole reason this feature exists is that on a
        // keypad phone, getting six digits out of a message and into the clipboard is otherwise
        // several screens and a text selection. Only added when there genuinely is a code:
        // an action that sometimes copies nothing is worse than no action.
        OtpCodes.find(body)?.let { code ->
            val copyIntent = Intent(context, CopyCodeReceiver::class.java).apply {
                action = CopyCodeReceiver.ACTION_COPY_CODE
                putExtra(CopyCodeReceiver.EXTRA_CODE, code)
                putExtra(CopyCodeReceiver.EXTRA_THREAD_ID, threadId)
            }
            val copyPending = PendingIntent.getBroadcast(
                context,
                // Distinct from the open-thread PendingIntent's request code, or
                // FLAG_UPDATE_CURRENT would have one overwrite the other.
                threadId.toInt() xor COPY_REQUEST_SALT,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Copy $code", copyPending).build(),
            )
        }

        val notification = builder.build()

        manager.notify(threadId.toInt(), notification)
    }

    /** Cancels a thread's notification — called when that conversation is opened, since the
     *  message has self-evidently been seen at that point. */
    fun cancel(context: Context, threadId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(threadId.toInt())
    }

    /** Says plainly that a multimedia message arrived and M9 can't show it. The alternative — a
     *  silently dropped MMS — is the worst thing this app could do to someone, so this exists
     *  even though it can only report the problem rather than solve it (see [MmsPushReceiver]). */
    fun notifyUnsupportedMms(context: Context, pduFileName: String?) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val detail = if (pduFileName != null) {
            "M9 can't display multimedia messages. It has been saved unread; set another app as " +
                "your default messaging app to read it."
        } else {
            "M9 can't display multimedia messages, and this one arrived without any content to keep."
        }

        val notification = Notification.Builder(context, CHANNEL_MMS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Multimedia message received")
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        manager.notify(NOTIFICATION_ID_MMS, notification)
    }
}
