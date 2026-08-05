package life.furrer.m9.sms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast

private const val TAG = "M9CopyCode"

/** The notification's "Copy" action: puts the one-time code on the clipboard without the user
 *  having to open the message, find the digits and hold them.
 *
 *  A receiver rather than an Activity, so nothing flashes on screen — copying from the background
 *  is allowed (the read side is what Android restricts, not the write side), and the whole point
 *  of the action is that it costs one keypress and no context switch.
 *
 *  The clip is flagged sensitive: on Android 13+ the system shows a copy confirmation with a
 *  preview of what was copied, and a one-time code is exactly the thing that shouldn't be
 *  rendered in a toast over the lock screen. */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE)
        if (code.isNullOrBlank()) {
            Log.w(TAG, "Copy action with no code attached")
            return
        }

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Log.e(TAG, "No clipboard service — nothing copied")
            return
        }

        val clip = ClipData.newPlainText("One-time code", code).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)

        // Android 13+ shows its own copy confirmation, so a toast on top of it would be a
        // duplicate; below that there is no system feedback at all and a silent copy is
        // indistinguishable from a dead key.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
        }

        // The notification has done its job the moment the code is on the clipboard.
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        if (threadId >= 0) {
            context.getSystemService(NotificationManager::class.java)?.cancel(threadId.toInt())
        }
    }

    companion object {
        const val ACTION_COPY_CODE = "life.furrer.m9.COPY_CODE"
        const val EXTRA_CODE = "code"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}
