package life.furrer.m9

import android.app.Application
import life.furrer.m9.settings.SettingsActivity
import life.furrer.nine.core.KeyHelp
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface

class M9Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Nine.init(m9App)
    }
}

// Every key below is live as of 0.1.0 and was checked against the running app on hardware — a
// Manual that documents a key the app doesn't have is worse than no Manual, since it's the one
// place a user goes when a key doesn't do what they expected.
private val m9App = NineApp(
    id = "m9",
    name = "M9",
    tagline = "Messages on 9 keys",
    settingsActivity = SettingsActivity::class.java,
    surfaces = listOf(
        NineSurface(
            id = "",
            name = "M9",
            introText = """
                M9 ("Messages on 9 keys") is an SMS app for a keypad phone with no touchscreen:
                one launcher entry showing your conversations newest-first, a thread screen, and
                a compose screen whose recipient field searches contacts the same T9 way as every
                other Nine app.

                Type any digit to filter the conversation list by contact name, number, or
                message body. With nothing typed, the list shows every conversation, newest
                first. Open one to read it top to bottom, day by day, with a compose row at the
                bottom for replying without leaving the thread.

                Storing your own outgoing messages and receiving new ones only works once M9 is
                set as your phone's default messaging app — the list's "*" menu offers this, and
                it also lives in Settings as "Default messaging app". Until it's granted, M9 can
                still show your conversations, it just hands composing off to whatever app
                already holds that role. M9 has no network permission of its own: everything here
                goes through the phone's own SMS radio, and M9 does not support MMS (see "About"
                for why) — a multimedia message arriving is reported, not shown.
            """.trimIndent(),
            keyMap = listOf(
                KeyHelp("— Conversation list —", ""),
                KeyHelp("any key", "Type to filter: contact name, number, or message body."),
                KeyHelp("Up / Down", "Move the selection."),
                KeyHelp("OK", "Open the selected conversation."),
                KeyHelp("Call key", "Call this thread's number (inert for a sender that isn't a number)."),
                KeyHelp("Back", "Clear the search; on an empty search, close."),
                KeyHelp("#", "Toggle showing unread conversations only."),
                KeyHelp("*", "Open the menu: new message, contacts, mark read/unread, delete, settings."),
                KeyHelp("Hold *", "Open Settings."),
                KeyHelp("— Conversation screen —", ""),
                KeyHelp("Up / Down", "Move the selection; Down past the last message focuses the reply row."),
                KeyHelp("Left / Right", "Jump to the previous/next day."),
                KeyHelp("OK", "Open the selected message's details; in the reply row, send."),
                KeyHelp("Call key", "Call this thread's number, or send from the reply row."),
                KeyHelp("Back", "Leave the reply row if inside it, else close the conversation."),
                KeyHelp("#", "Copy the selected message's one-time code, when it has one."),
                KeyHelp("*", "Open the menu: forward, copy, delete message, resend, call, contact, settings."),
                KeyHelp("— Compose —", ""),
                KeyHelp("OK, on \"To\"", "Pick a recipient by name or typed number."),
                KeyHelp("OK, on the message", "Edit the message text."),
                KeyHelp("Call key", "Send."),
                KeyHelp("#", "Send, when not editing a field."),
                KeyHelp("*", "Open the menu: send, settings."),
                KeyHelp("Back", "Close without sending."),
            ),
        ),
    ),
)
