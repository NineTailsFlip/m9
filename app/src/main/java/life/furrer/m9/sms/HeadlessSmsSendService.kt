package life.furrer.m9.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log

private const val TAG = "M9RespondVia"

/** `ACTION_RESPOND_VIA_MESSAGE` — the "reject this call with a text" seam. Declaring it is one of
 *  the four components `ROLE_SMS` requires, so M9 cannot become the default SMS app without it.
 *
 *  It is also the one place M9 and P9 could meet without either naming the other: a phone app
 *  offering "reject with a message" reaches whatever holds this role, through a platform intent.
 *
 *  **It does not send yet.** Sending is M4 — `SmsSender`, the outbox row, the sent/failed
 *  lifecycle — and none of it exists at M3. What this must not do in the meantime is accept the
 *  intent and quietly drop it, leaving someone believing they replied to a caller when nothing
 *  went out. So it logs plainly and does nothing else, and M4 replaces this body with a real
 *  send through the same path the compose screen uses. Nothing on the phone can reach it today
 *  regardless: P9 has no reject-with-message action, and this only fires when some dialer offers
 *  one. */
class HeadlessSmsSendService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            Log.w(TAG, "Respond-via-message is not implemented until M4 — no message was sent")
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
