package life.furrer.m9.core

import android.content.Context
import life.furrer.nine.core.prefs.NinePrefs

object Prefs {
    private const val KEY_MESSAGES_TO_LOAD = "messages_to_load"

    /** How many messages the conversation list reads in one go, newest first. This bounds the
     *  whole app's view of the store: thread grouping happens over exactly this window (see
     *  `ThreadRows`), so a thread whose newest message falls outside it isn't listed at all.
     *
     *  500 is deliberately generous for a keypad phone — the read is one indexed query and the
     *  grouping is a single pass — while still keeping a decade-old message store from being
     *  loaded in full on every refresh. Mirrors P9's own call-history length setting, which is
     *  the same trade in the same shape. */
    const val DEFAULT_MESSAGES_TO_LOAD = 500

    fun messagesToLoad(context: Context): Int =
        NinePrefs.of(context).getInt(KEY_MESSAGES_TO_LOAD, DEFAULT_MESSAGES_TO_LOAD)

    fun setMessagesToLoad(context: Context, value: Int) {
        NinePrefs.of(context).edit().putInt(KEY_MESSAGES_TO_LOAD, value).apply()
    }

    /** Whether to ask the network to confirm delivery. Off by default, matching the platform's
     *  own default and most phones' out-of-the-box behaviour: reports aren't free on every
     *  network, and a ✓✓ that never arrives because the carrier doesn't send them reads as a
     *  failure to anyone who doesn't know that. Someone who wants them can turn them on. */
    fun deliveryReports(context: Context): Boolean =
        NinePrefs.of(context).getBoolean(KEY_DELIVERY_REPORTS, false)

    fun setDeliveryReports(context: Context, value: Boolean) {
        NinePrefs.of(context).edit().putBoolean(KEY_DELIVERY_REPORTS, value).apply()
    }

    /** Which SIM outgoing messages go out on, as a subscription id. -1 means "no preference" —
     *  the system default, which is the only sensible answer on a single-SIM phone and the
     *  correct starting point on a dual-SIM one. */
    fun sendingSubscriptionId(context: Context): Int =
        NinePrefs.of(context).getInt(KEY_SENDING_SUBSCRIPTION_ID, NO_SUBSCRIPTION_PREFERENCE)

    fun setSendingSubscriptionId(context: Context, value: Int) {
        NinePrefs.of(context).edit().putInt(KEY_SENDING_SUBSCRIPTION_ID, value).apply()
    }

    /** Whether a notification may show the message text, or only who it's from. A phone that
     *  lives face-up on a table has a real answer to this, and it isn't the same for everyone —
     *  default on, matching every other messaging app, but one row away from off. */
    fun showMessageInNotification(context: Context): Boolean =
        NinePrefs.of(context).getBoolean(KEY_SHOW_MESSAGE_IN_NOTIFICATION, true)

    fun setShowMessageInNotification(context: Context, value: Boolean) {
        NinePrefs.of(context).edit().putBoolean(KEY_SHOW_MESSAGE_IN_NOTIFICATION, value).apply()
    }

    /** An unsent reply, kept per thread.
     *
     *  Stored in prefs rather than as a `Telephony.Sms` DRAFT row: a provider draft is shared with
     *  every other SMS app on the phone, which means M9 would be editing and deleting rows other
     *  apps also show — a lot of cross-app state for something whose whole job is "the words you
     *  hadn't finished". Prefs are private, survive a reboot, and cost nothing.
     *
     *  Keyed by thread id, so replies to different people don't overwrite each other. */
    fun draft(context: Context, threadId: Long): String =
        NinePrefs.of(context).getString(draftKey(threadId), "").orEmpty()

    fun setDraft(context: Context, threadId: Long, text: String) {
        NinePrefs.of(context).edit().apply {
            // Blank clears rather than storing "": an empty draft is the absence of a draft, and
            // leaving the key behind would make the list mark the thread forever.
            if (text.isBlank()) remove(draftKey(threadId)) else putString(draftKey(threadId), text)
        }.apply()
    }

    /** Which threads currently hold a draft — for the conversation list's own marker, answered in
     *  one pass over the prefs file rather than a lookup per row. */
    fun draftThreadIds(context: Context): Set<Long> =
        NinePrefs.of(context).all.keys
            .filter { it.startsWith(KEY_DRAFT_PREFIX) }
            .mapNotNull { it.removePrefix(KEY_DRAFT_PREFIX).toLongOrNull() }
            .toSet()

    // There is deliberately no compose-screen draft. Drafts are kept per *thread*, where they
    // belong to a conversation you can see them attached to; the new-message screen keeps
    // nothing, because "New message" that opens pre-filled with something abandoned days ago is
    // not a new message. An interrupted compose still survives a task switch the ordinary way —
    // Android keeps the Activity instance — it just doesn't outlive the screen being closed.

    private fun draftKey(threadId: Long) = "$KEY_DRAFT_PREFIX$threadId"

    /** Message and conversation-row text size, in sp — mirrors T9's `taskListFontSize` and L9's
     *  own font-size prefs, same "a plain Int applied directly as textSize" shape. Row heights
     *  scale with it (see `threadRowHeightDp`), and message rows are measured at it, so a larger
     *  setting never clips. */
    const val DEFAULT_FONT_SIZE = 18

    fun fontSize(context: Context): Int =
        NinePrefs.of(context).getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)

    fun setFontSize(context: Context, value: Int) {
        NinePrefs.of(context).edit().putInt(KEY_FONT_SIZE, value).apply()
    }

    /** The size of message text itself — in a thread, in the detail screen, and in the field you
     *  write one in. Separate from [fontSize], which sizes the conversation list, because the two
     *  are different reading tasks: the list is scanned for a name, a message is read as prose.
     *  Wanting bigger prose without a list that fits four rows on a screen is a reasonable thing
     *  to want, and vice versa.
     *
     *  Defaults to the same value, so nothing changes until it's deliberately moved. */
    fun messageFontSize(context: Context): Int =
        NinePrefs.of(context).getInt(KEY_MESSAGE_FONT_SIZE, DEFAULT_FONT_SIZE)

    fun setMessageFontSize(context: Context, value: Int) {
        NinePrefs.of(context).edit().putInt(KEY_MESSAGE_FONT_SIZE, value).apply()
    }

    /** The unread marker's colour, as ARGB.
     *
     *  Lavender by default: white made the dot the brightest thing on a screen where white
     *  already means "selected", so a list with several unread threads read as several
     *  selections. Lavender says "state" rather than "focus", and it's the accent this device's
     *  own system UI uses. Stored as a raw colour rather than an index into a preset list, so the
     *  picker's typed-custom option round-trips like any other choice. */
    const val DEFAULT_UNREAD_DOT_COLOR = 0xFFB9A7E8.toInt()

    fun unreadDotColor(context: Context): Int =
        NinePrefs.of(context).getInt(KEY_UNREAD_DOT_COLOR, DEFAULT_UNREAD_DOT_COLOR)

    fun setUnreadDotColor(context: Context, argb: Int) {
        NinePrefs.of(context).edit().putInt(KEY_UNREAD_DOT_COLOR, argb).apply()
    }

    const val NO_SUBSCRIPTION_PREFERENCE = -1
    private const val KEY_UNREAD_DOT_COLOR = "unread_dot_color"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_MESSAGE_FONT_SIZE = "message_font_size"
    private const val KEY_DRAFT_PREFIX = "draft_"
    private const val KEY_SHOW_MESSAGE_IN_NOTIFICATION = "show_message_in_notification"
    private const val KEY_DELIVERY_REPORTS = "delivery_reports"
    private const val KEY_SENDING_SUBSCRIPTION_ID = "sending_subscription_id"
}
