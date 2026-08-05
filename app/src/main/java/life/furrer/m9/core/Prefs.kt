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

    const val NO_SUBSCRIPTION_PREFERENCE = -1
    private const val KEY_DELIVERY_REPORTS = "delivery_reports"
    private const val KEY_SENDING_SUBSCRIPTION_ID = "sending_subscription_id"
}
