package life.furrer.m9.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "M9ThreadNotification"

/** What the incoming-message notification does without opening the app: its "Mark as read"
 *  action, and its delete intent for when it's swiped away or cleared.
 *
 *  The two differ in exactly the way READ and SEEN do (see [SmsStore.markThreadRead]). Mark as
 *  read is "I've dealt with this" and clears both, so the thread drops out of the unread list.
 *  Dismissing is only "I know it's there": it clears SEEN and leaves READ alone, so a message
 *  someone swiped away to read later is still marked unread when they get to the app.
 *
 *  Opening the notification is neither — that goes to the conversation, which marks it read
 *  itself, and the platform doesn't send the delete intent for an auto-cancel on tap. */
class ThreadNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                handle(appContext, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Handling ${intent.action} failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_MARK_READ = "life.furrer.m9.MARK_READ"
        const val ACTION_DISMISSED = "life.furrer.m9.NOTIFICATION_DISMISSED"
        const val EXTRA_THREAD_ID = "thread_id"

        /** The provider work, off [onReceive] so it can be tested without a pending broadcast. */
        fun handle(context: Context, intent: Intent) {
            val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
            if (threadId < 0) {
                Log.w(TAG, "${intent.action} with no thread id")
                return
            }
            when (intent.action) {
                ACTION_MARK_READ -> {
                    // The action doesn't retire its notification by itself.
                    MessageNotifications.cancel(context, threadId)
                    SmsStore.markThreadRead(context, threadId)
                }
                ACTION_DISMISSED -> SmsStore.markThreadSeen(context, threadId)
                else -> Log.w(TAG, "Unknown action ${intent.action}")
            }
        }
    }
}
