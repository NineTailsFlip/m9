package life.furrer.m9.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "M9Resend"

/** The failure notification's "Resend" action.
 *
 *  Reads the failed row back from the provider rather than carrying the message text on the
 *  intent: the row is the source of truth for what was actually stored, and a PendingIntent that
 *  outlives an edit or a delete shouldn't be able to resurrect stale content. If the row is gone,
 *  so is the message, and there is nothing to resend. */
class ResendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val appContext = context.applicationContext

        val pending = goAsync()
        Thread {
            try {
                val message = SmsStore.queryMessage(appContext, uri)
                if (message == null) {
                    Log.w(TAG, "Nothing left at $uri to resend")
                    return@Thread
                }
                MessageNotifications.cancelSendFailure(appContext, message.threadId)
                SmsSender.resend(appContext, uri, message.address, message.body)
            } catch (t: Throwable) {
                Log.e(TAG, "Resend failed for $uri", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_RESEND = "life.furrer.m9.RESEND"
    }
}
