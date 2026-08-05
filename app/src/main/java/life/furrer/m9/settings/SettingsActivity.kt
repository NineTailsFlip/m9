package life.furrer.m9.settings

import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.SmsRole
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SettingsScreen

// The window the whole app's view of the store is bounded by (see Prefs.messagesToLoad). The
// floor is high enough that a phone with a handful of active threads never notices a bound at
// all; the ceiling is where reading and grouping in one pass stops being obviously cheap.
private const val MIN_MESSAGES_TO_LOAD = 100
private const val MAX_MESSAGES_TO_LOAD = 2000
private const val MESSAGES_TO_LOAD_STEP = 100

/** M9's Settings. The rest of the plan's "Messages" section — Default messaging app, SIM for
 *  sending, delivery reports, the notification privacy toggle — needs code that doesn't exist
 *  until M3/M4/M6, and each row lands with the milestone that makes it mean something. Theme,
 *  Backup and About come free from :core. */
/** One SIM the user can pick to send from. Only ever offered when there is more than one — a
 *  single-SIM phone has no interesting answer, and a row whose only value is "the only SIM" is
 *  a row that shouldn't exist. */
private data class SendingSim(val subscriptionId: Int, val label: String)

class SettingsActivity : NineSettingsActivity() {

    /** Reads the live subscription list rather than caching it: a SIM can be added, removed or
     *  disabled while M9 is installed, and a stale id would silently send from the wrong one. */
    private fun sendingSimOptions(): List<SendingSim> {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = getSystemService(android.telephony.SubscriptionManager::class.java) ?: return emptyList()
        val active = manager.activeSubscriptionInfoList.orEmpty()
        if (active.size < 2) return emptyList()
        return active.map { info ->
            SendingSim(
                subscriptionId = info.subscriptionId,
                label = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${info.simSlotIndex + 1}",
            )
        }
    }
    override fun buildAppSections(screen: SettingsScreen, surface: NineSurface) {
        screen.section("Messages") {
            // The role's permanent home, once first run has asked once and stopped asking. Shows
            // its current state rather than assuming: the role can be taken away from M9 by
            // granting it to another app, entirely outside M9's knowledge.
            action(
                label = "Default messaging app",
                value = { if (SmsRole.isHeld(this@SettingsActivity)) "M9" else "another app" },
                onSelect = {
                    if (!SmsRole.isHeld(this@SettingsActivity) && SmsRole.isAvailable(this@SettingsActivity)) {
                        startActivity(SmsRole.requestIntent(this@SettingsActivity))
                    }
                },
            )
            choice(
                label = "SIM for sending",
                options = { sendingSimOptions() },
                get = {
                    val current = Prefs.sendingSubscriptionId(this@SettingsActivity)
                    sendingSimOptions().firstOrNull { it.subscriptionId == current }
                },
                set = { sim -> Prefs.setSendingSubscriptionId(this@SettingsActivity, sim?.subscriptionId ?: Prefs.NO_SUBSCRIPTION_PREFERENCE) },
                render = { it.label },
                nullLabel = "system default",
                emptyLabel = "one SIM only",
            )
            toggle(
                label = "Show message text in notifications",
                get = { Prefs.showMessageInNotification(this@SettingsActivity) },
                set = { value -> Prefs.setShowMessageInNotification(this@SettingsActivity, value) },
            )
            toggle(
                label = "Delivery reports",
                get = { Prefs.deliveryReports(this@SettingsActivity) },
                set = { value -> Prefs.setDeliveryReports(this@SettingsActivity, value) },
            )
            intRange(
                label = "Messages to load",
                min = MIN_MESSAGES_TO_LOAD,
                max = MAX_MESSAGES_TO_LOAD,
                step = MESSAGES_TO_LOAD_STEP,
                get = { Prefs.messagesToLoad(this@SettingsActivity) },
                set = { value -> Prefs.setMessagesToLoad(this@SettingsActivity, value) },
            )
        }
    }
}
