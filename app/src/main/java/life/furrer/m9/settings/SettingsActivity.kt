package life.furrer.m9.settings

import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.SmsRole
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SettingsScreen

// The window the whole app's view of the store is bounded by (see Prefs.messagesToLoad). The
// floor is high enough that a phone with a handful of active threads never notices a bound at
// all; the ceiling is where reading and grouping in one pass stops being obviously cheap.
// The same range T9 and L9 offer for their own font sizes — one suite, one set of expectations.
private const val MIN_FONT_SIZE = 14
private const val MAX_FONT_SIZE = 48

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

/** The unread marker's preset swatches. Lavender first because it's the default; the rest span
 *  the hues someone might actually want to tell apart at 11sp on a black screen, rather than a
 *  full colour wheel most of which would be indistinguishable at that size. The picker also
 *  offers a typed custom colour, so this list is a shortcut, not a limit. */
private val UNREAD_COLOR_PRESETS = intArrayOf(
    Prefs.DEFAULT_UNREAD_DOT_COLOR,
    0xFFFFFFFF.toInt(),
    0xFF89E1A7.toInt(),
    0xFFCF6679.toInt(),
    0xFF8AB4F8.toInt(),
    0xFFE8C07D.toInt(),
    0xFF7FD8D8.toInt(),
    0xFFE7A6D6.toInt(),
)

private val UNREAD_COLOR_LABELS = arrayOf(
    "Lavender", "White", "Green", "Red", "Blue", "Amber", "Cyan", "Pink",
)

private const val REQUEST_PICK_UNREAD_COLOR = 300

class SettingsActivity : NineSettingsActivity() {

    /** Reuses `:core`'s ColorPickerActivity — the same grid C9 picks an event colour with and T9
     *  a task colour, so "choose a colour" is one interaction in the suite rather than three.
     *  No "none" cell: an unread marker that isn't shown is just the unread state going
     *  unreported, which is a worse answer than any colour. */
    private fun openUnreadColorPicker() {
        val current = Prefs.unreadDotColor(this)
        startActivityForResult(
            android.content.Intent(this, life.furrer.nine.core.ui.ColorPickerActivity::class.java)
                .putExtra(life.furrer.nine.core.ui.ColorPickerActivity.EXTRA_COLORS, UNREAD_COLOR_PRESETS)
                .putExtra(life.furrer.nine.core.ui.ColorPickerActivity.EXTRA_LABELS, UNREAD_COLOR_LABELS)
                .putExtra(life.furrer.nine.core.ui.ColorPickerActivity.EXTRA_ALLOW_CUSTOM, true)
                .putExtra(life.furrer.nine.core.ui.ColorPickerActivity.EXTRA_ALLOW_NONE, false)
                .putExtra(
                    life.furrer.nine.core.ui.ColorPickerActivity.EXTRA_INITIAL_INDEX,
                    UNREAD_COLOR_PRESETS.indexOf(current),
                ),
            REQUEST_PICK_UNREAD_COLOR,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_UNREAD_COLOR || resultCode != RESULT_OK || data == null) return
        // EXTRA_RESULT_ARGB carries the colour for both a preset and a typed custom one, so the
        // stored value never has to know which the user picked.
        val argb = data.getIntExtra(life.furrer.nine.core.ui.ColorPickerActivity.EXTRA_RESULT_ARGB, 0)
        if (argb != 0) {
            Prefs.setUnreadDotColor(this, argb)
            // onActivityResult doesn't retrigger onResume, so the row's own text needs telling.
            refresh()
        }
    }

    /** The preset's name when it is one, else the hex — a custom colour has no name to give, and
     *  "#8AB4F8" at least says exactly what was chosen. */
    private fun colorLabel(argb: Int): String {
        val index = UNREAD_COLOR_PRESETS.indexOf(argb)
        return if (index >= 0) UNREAD_COLOR_LABELS[index] else "#%06X".format(argb and 0xFFFFFF)
    }

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
            action(
                label = "Unread marker",
                value = { colorLabel(Prefs.unreadDotColor(this@SettingsActivity)) },
                onSelect = { openUnreadColorPicker() },
            )
            intRange(
                label = "Font size",
                min = MIN_FONT_SIZE,
                max = MAX_FONT_SIZE,
                get = { Prefs.fontSize(this@SettingsActivity) },
                set = { value -> Prefs.setFontSize(this@SettingsActivity, value) },
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
