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
}
