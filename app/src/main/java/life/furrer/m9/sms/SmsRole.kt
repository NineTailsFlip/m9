package life.furrer.m9.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import life.furrer.nine.core.prefs.NinePrefs

private const val KEY_ASKED_SMS_ROLE = "asked_sms_role"

/** Whether M9 holds `RoleManager.ROLE_SMS`, and the request flow to get it. A direct mirror of
 *  P9's `DialerRole` — but the consequence of *not* holding it is much sharper here, and the
 *  difference is worth stating where someone will read it:
 *
 *  P9 without its role is still a working dialer; it just hands the call off. M9 without this
 *  role is a **read-only viewer**. Since KitKat the SMS provider silently drops writes from an
 *  app that isn't the default SMS app — no exception, no error return — so M9 cannot store a
 *  message it sends, cannot mark a thread read, and cannot delete anything. Inbound messages
 *  reach the role holder alone, so M9 doesn't see them arrive either.
 *
 *  The other half of that trade is the one users have to be told about before they grant it:
 *  taking this role makes M9 the only app receiving MMS, which it cannot display (see
 *  [MmsPushReceiver] for what it does with them instead). */
object SmsRole {
    fun isHeld(context: Context): Boolean =
        roleManager(context)?.isRoleHeld(RoleManager.ROLE_SMS) ?: false

    fun isAvailable(context: Context): Boolean =
        roleManager(context)?.isRoleAvailable(RoleManager.ROLE_SMS) ?: false

    /** Start with `startActivityForResult`; the result code itself isn't meaningful to check —
     *  the only reliable way to know it took is to re-check [isHeld] once control returns. Same
     *  caveat `DialerRole.requestIntent` carries. */
    fun requestIntent(context: Context): Intent =
        roleManager(context)!!.createRequestRoleIntent(RoleManager.ROLE_SMS)

    /** First run asks once; after that, granting the role lives as a Settings row rather than a
     *  prompt on every launch. */
    fun hasAskedBefore(context: Context): Boolean =
        NinePrefs.of(context).getBoolean(KEY_ASKED_SMS_ROLE, false)

    fun markAsked(context: Context) {
        NinePrefs.of(context).edit().putBoolean(KEY_ASKED_SMS_ROLE, true).apply()
    }

    private fun roleManager(context: Context): RoleManager? = context.getSystemService(RoleManager::class.java)
}
