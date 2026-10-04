package life.furrer.m9.sms

import android.app.role.RoleManager
import life.furrer.nine.core.util.SystemRole

/** Whether M9 holds `RoleManager.ROLE_SMS`, and the request flow to get it. The same
 *  [SystemRole] P9's `DialerRole` is — but the consequence of *not* holding it is much sharper
 *  here, and the difference is worth stating where someone will read it:
 *
 *  P9 without its role is still a working dialer; it just hands the call off. M9 without this
 *  role is a **read-only viewer**. Since KitKat the SMS provider silently drops writes from an
 *  app that isn't the default SMS app — no exception, no error return — so M9 cannot store a
 *  message it sends, cannot mark a thread read, and cannot delete anything. Inbound messages
 *  reach the role holder alone, so M9 doesn't see them arrive either.
 *
 *  The other half of that trade is the one users have to be told about before they grant it:
 *  taking this role makes M9 the only app receiving MMS, which it cannot display (see
 *  [MmsPushReceiver] for what it does with them instead).
 *
 *  Starting the request with plain `startActivity` instead of `startActivityForResult` is what
 *  once made "Set M9 as default messaging app" silently do nothing (see
 *  [SystemRole.requestIntent]). The prefs key is what existing installs already store — never
 *  rename it. */
val SmsRole = SystemRole(RoleManager.ROLE_SMS, "asked_sms_role")
