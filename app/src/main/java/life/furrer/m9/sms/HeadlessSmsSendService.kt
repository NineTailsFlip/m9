package life.furrer.m9.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log

private const val TAG = "M9RespondVia"

/** `ACTION_RESPOND_VIA_MESSAGE` — the "reject this call with a text" seam. Declaring it is one of
 *  the four components `ROLE_SMS` requires, so M9 cannot become the default SMS app without it.
 *
 *  It is also the one place M9 and P9 could meet without either naming the other: a phone app
 *  offering "reject with a message" reaches whatever holds this role, through a platform intent.
 *
 *  It sends through [SmsSender], the same path the compose screen uses, so a quick reply is
 *  stored in its thread exactly like any other outgoing message rather than vanishing into the
 *  radio unrecorded. The destination arrives as the intent's URI (`smsto:+41...`) and the text as
 *  `Intent.EXTRA_TEXT`; an intent missing either is logged rather than half-handled, because the
 *  one thing this must never do is report success for a message that never went out. */
class HeadlessSmsSendService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            respond(intent)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun respond(intent: Intent) {
        // "smsto:+41791234567" -> "+41791234567". schemeSpecificPart rather than a manual prefix
        // strip, so every one of the four schemes the filter accepts works the same way.
        val address = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (address == null || body == null) {
            Log.w(TAG, "Respond-via-message without a destination or a body — nothing sent")
            return
        }
        val threadId = Telephony.Threads.getOrCreateThreadId(this, address)
        SmsSender.send(this, threadId, address, body)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
