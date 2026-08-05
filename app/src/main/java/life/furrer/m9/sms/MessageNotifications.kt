package life.furrer.m9.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import life.furrer.m9.R
import life.furrer.m9.conversations.ConversationActivity

private const val CHANNEL_INCOMING = "incoming_messages"
private const val CHANNEL_MMS = "unsupported_mms"

/** The MMS notification's own fixed id — one is enough, since every one of them says the same
 *  thing and a stack of them would be noise about a limitation the user already knows about. */
private const val NOTIFICATION_ID_MMS = 1

/** Incoming-message notifications. Modelled on P9's `CallNotifications`, including reusing
 *  `ic_launcher_monochrome` as the small icon — it's already exactly the white-on-transparent
 *  silhouette a notification icon needs, so no separate asset exists.
 *
 *  Deliberately *not* a full-screen intent, unlike P9's ringing notification: a text message is
 *  not a call, and taking over the screen for one would be wrong on a phone that lives in a
 *  pocket. M6 grows this into a real MessagingStyle conversation with history; M3 needs it to
 *  reliably say "something arrived, here's who from". */
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
    }

    /** One notification per thread, keyed by [threadId], so a second message from the same person
     *  updates their notification instead of stacking a new one. */
    fun notifyIncoming(context: Context, threadId: Long, address: String, body: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val contactName = SmsStore.lookupContactName(context, address)
        val sender = Person.Builder().setName(contactName ?: address).build()
        val style = Notification.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage(body, System.currentTimeMillis(), sender)

        val openThread = PendingIntent.getActivity(
            context,
            // The thread id as the request code, so each thread's PendingIntent is distinct —
            // without it, FLAG_UPDATE_CURRENT would make every notification open whichever
            // conversation was notified last.
            threadId.toInt(),
            ConversationActivity.intentFor(context, threadId, address, contactName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setStyle(style)
            .setContentIntent(openThread)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

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
