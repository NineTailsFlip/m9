package life.furrer.m9.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "M9MmsPush"
private const val PDU_DIRECTORY = "mms-pdu"

/** Inbound MMS, which M9 cannot display — and this is the class that makes that an honest
 *  limitation rather than a silent data loss.
 *
 *  Holding `ROLE_SMS` means WAP push messages are delivered here **and nowhere else**. Turning one
 *  into a readable message needs the notification-ind PDU parsed for its location URL (so
 *  `SmsManager.downloadMultimediaMessage` can fetch the actual content), and there is no public
 *  platform API for that parsing — every app that does it vendors AOSP's
 *  `com.google.android.mms.pdu` or a fork. That's a large vendoring job under this repo's
 *  no-third-party-dependencies rule, so it's its own milestone, not a corner of this one.
 *
 *  Until then, the two things this must never do are drop the broadcast and pretend nothing
 *  happened. So it does the two things it can:
 *
 *  1. **Writes the raw PDU to app-private storage.** A future version with a parser can recover
 *     messages received in the meantime, and a bug report can carry a real PDU instead of a
 *     description of one. Nothing else reads these files yet — that is the point of writing them.
 *  2. **Tells the user.** A notification saying a multimedia message arrived and M9 can't show
 *     it, so the response ("hand the SMS role back to an app that can") is at least available to
 *     them. A silently vanished MMS is the worst outcome this app can produce.
 *
 *  If MMS matters on a given phone, the correct answer is to not give M9 the role yet, and the
 *  first-run screen says so. */
class MmsPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The raw PDU rides on the intent as a byte array; without it there is nothing to keep.
        val pdu = intent.getByteArrayExtra("data")
        val appContext = context.applicationContext

        val pending = goAsync()
        Thread {
            try {
                val saved = if (pdu != null) savePdu(appContext, pdu) else null
                if (pdu == null) {
                    Log.w(TAG, "WAP push with no PDU payload — nothing to preserve")
                }
                MessageNotifications.notifyUnsupportedMms(appContext, saved?.name)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle an incoming MMS", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Timestamped filename so several MMS in one day don't overwrite each other, and so the
     *  order they arrived in survives — a parser written later has no other way to know it. */
    private fun savePdu(context: Context, pdu: ByteArray): File {
        val directory = File(context.filesDir, PDU_DIRECTORY).apply { mkdirs() }
        val name = "mms-${FILENAME_FORMAT.format(Date())}.pdu"
        val file = File(directory, name)
        file.writeBytes(pdu)
        Log.i(TAG, "Preserved an undisplayable MMS PDU (${pdu.size} bytes) as $name")
        return file
    }

    private companion object {
        val FILENAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
