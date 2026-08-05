package life.furrer.m9.conversations

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import life.furrer.m9.compose.ComposeActivity
import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.MessageNotifications
import life.furrer.m9.sms.OtpCodes
import life.furrer.m9.sms.SmsSender
import life.furrer.m9.sms.SmsStore
import life.furrer.m9.sms.segmentLabel
import life.furrer.m9.sms.isNumericAddress
import life.furrer.nine.core.Nine
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

private const val SIDE_PADDING_DP = 16
private const val REQUEST_CALL_PHONE_PERMISSION = 101

/** The gap between the newest message and the reply box. Only visible when the thread is long
 *  enough to reach the bottom, which is exactly when it's needed. */
private const val COMPOSE_TOP_GAP_DP = 12

/** Padding under the reply text, between it and the hint bar. */
private const val COMPOSE_BOTTOM_PADDING_DP = 12

/** One thread: every message with its correspondent, oldest at the top.
 *
 *  No [life.furrer.nine.core.ui.SearchField] here, which makes the key handling the simpler
 *  `onKeyDown`/`onKeyLongPress` shape T9's `TaskListActivity` uses rather than the
 *  `dispatchKeyEvent` interception the conversation list needs. That difference is not cosmetic:
 *  with no live IME connection on this screen, a [MenuSheet] shown over it is safe — the quirk
 *  documented on `:contacts`' `ContactValuePickerActivity` (this hardware's T9 IME composing into
 *  a focused field behind an open menu) needs a focused field to happen at all. It's also why
 *  M5's multi-number chooser will belong on the compose screen rather than inside the picker.
 *
 *  Replying happens inline, at the bottom of this screen, rather than on a screen of its own:
 *  it's the most common action in the app, and a screen transition per reply is a keystroke and a
 *  lost place for nothing. Down past the last message enters the field, so it costs no key of its
 *  own. A *new* message still gets its own screen (M5), because that one needs a recipient. */
class ConversationActivity : Activity() {

    private var threadId: Long = -1L
    private var address: String = ""
    private var contactName: String? = null
    private var rows: List<MessageRow> = emptyList()

    private var pendingCallNumber: String? = null
    private var pendingStarKeyCode: Int? = null
    private var starLongPressHandled = false

    /** True while the compose field has focus and is taking typed characters. The list keeps its
     *  own selection underneath, so leaving the field puts the user back exactly where they were. */
    private var composing = false

    private lateinit var rowListView: RowListView
    private lateinit var composeField: EditText

    /** Somewhere for focus to go that isn't an EditText, so leaving the reply row doesn't leave
     *  the window focusing nothing (see [releaseImeFocus]). */
    private lateinit var keyFocusAnchor: View
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private var contentObserver: ContentObserver? = null

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createMessageRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            bindMessageRow(view as LinearLayout, rows[index], selected, Prefs.fontSize(this@ConversationActivity))
        }
        override fun rowHeight(index: Int): Int =
            messageRowHeightDp(this@ConversationActivity, rows[index], Prefs.fontSize(this@ConversationActivity))
        override fun isSelectable(index: Int): Boolean = rows[index].isSelectable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)

        val title = TextView(this).apply {
            text = contactName ?: address
            textSize = 18f
            setTextColor(Palette.WHITE)
            setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), 0)
        }
        // Only when a name is showing above it — otherwise this would repeat the title verbatim.
        val subtitle = TextView(this).apply {
            text = address
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), dp(6))
            visibility = if (contactName != null) View.VISIBLE else View.GONE
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        keyFocusAnchor = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        composeField = EditText(this).apply {
            hint = "Reply…"
            // Same size as the messages above it and as the compose screen's own field: what you
            // are writing should look like what you are reading.
            textSize = Prefs.fontSize(this@ConversationActivity).toFloat()
            // Multi-line: an SMS wraps, and a compose box that scrolled sideways on one line
            // would hide what you already typed.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Palette.WHITE)
            setHintTextColor(Palette.GRAY_FAINT)
            setBackgroundColor(Palette.BLACK)
            gravity = Gravity.START
            // More room below than above: the reply box sits directly on the hint bar, and text
            // with only a few dp under it reads as sitting on the line rather than above it.
            setPadding(dp(SIDE_PADDING_DP), dp(6), dp(SIDE_PADDING_DP), dp(COMPOSE_BOTTOM_PADDING_DP))
            // Focusable only while composing, so Down from the list is what enters it rather
            // than the framework handing it focus on open and swallowing every key.
            isFocusable = false
            isFocusableInTouchMode = false
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = updateHint()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyFocusAnchor, LinearLayout.LayoutParams(0, 0))
            addView(title)
            addView(subtitle)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // Breathing room above the reply box. Without it, a thread long enough to fill the
            // screen leaves its last message touching the box you type into, and the two read as
            // one block — the message you just received and the message you're about to write
            // should not look like the same thing.
            addView(
                composeField,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(COMPOSE_TOP_GAP_DP)
                },
            )
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        hideStatusBar()
        // A reply you started and walked away from is still a reply you meant to send.
        composeField.setText(Prefs.draft(this, threadId))
        updateHint()
        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        registerContentObserver()
        loadMessages()
        // Opening a thread is what "read" means. Done on every resume rather than once in
        // onCreate so a message arriving while this screen is in the foreground doesn't leave the
        // thread unread behind the user's back.
        markRead()
    }

    override fun onPause() {
        super.onPause()
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        // Saved on the way out rather than on every keystroke: this is the moment the text would
        // otherwise be lost, and prefs writes are not free.
        Prefs.setDraft(this, threadId, composeField.text.toString())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun registerContentObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = loadMessages()
        }
        contentObserver = observer
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
    }

    /** Reads the same window the list screen does and filters to this thread, rather than issuing
     *  a thread-scoped query. One code path to the provider means one place where ordering,
     *  contact resolution and the "messages to load" bound are decided — and a thread's messages
     *  are by definition inside a window that was big enough to show the thread at all. */
    private fun loadMessages() {
        Async.run(
            background = {
                SmsStore.queryMessages(this, Prefs.messagesToLoad(this)).filter { it.threadId == threadId }
            },
            onResult = { messages ->
                val wasAtNewest = rowListView.selectedIndex >= rows.size - 1
                rows = buildMessageRows(messages, LocalDate.now())
                rowListView.notifyDataChanged()
                // A conversation is read from the bottom: land on the newest message on open, and
                // follow it when a new one arrives — but never yank the selection away from an
                // older message the user has deliberately scrolled back to.
                if (wasAtNewest) rowListView.selectLastSelectable()
                updateHint()
            },
        )
    }

    /** Opening a thread both marks it read and retires its notification — the message has
     *  self-evidently been seen at that point, and a notification outliving that is the classic
     *  way a messaging app trains people to ignore it. */
    private fun markRead() {
        MessageNotifications.cancel(this, threadId)
        MessageNotifications.cancelSendFailure(this, threadId)
        Async.run(background = { SmsStore.markThreadRead(this, threadId) }, onResult = {})
    }

    private fun updateHint() {
        if (composing) {
            // The segment counter earns the hint bar's whole width while typing: on a prepaid SIM
            // "2 messages" is the difference between one charge and two, and it's the only place
            // that can warn before the message goes out. Silent for an ordinary short message.
            val segments = segmentLabel(composeField.text.toString())
            hintBar.setText(if (segments.isEmpty()) "ok send · ‹ back to messages" else "$segments · ok send")
            return
        }
        // Each item appears only when its key actually does something — a hint advertising a dead
        // key is worse than no hint — and on this screen that changes with every move of the
        // selection, which is why updateHint() runs on each. The call key earns a slot whenever
        // the correspondent is callable, including alongside a code: dropping it there hid the
        // one key that reaches the person rather than the message. "call dial" is the suite's
        // existing wording (ContactsActivity and ContactDetailActivity both use it).
        hintBar.setText(
            when {
                selectedCode() != null && canCall() -> "# copy · call dial · * menu"
                selectedCode() != null -> "# copy code · * menu · ↓ reply"
                canCall() -> "* menu · ↓ reply · call dial"
                else -> "* menu · ↓ reply · ← → day"
            },
        )
    }

    /** Down past the last message enters the compose field; Back leaves it. Focus is granted only
     *  for as long as it's being used, because an always-focusable EditText on this screen would
     *  take every keypress the list needs — and a focused field is also what makes a MenuSheet
     *  unsafe over it (the IME keeps composing behind an open menu on this hardware), so the menu
     *  is only reachable while not composing. */
    private fun startComposing() {
        if (composing) return
        composing = true
        composeField.isFocusable = true
        composeField.isFocusableInTouchMode = true
        composeField.requestFocus()
        updateHint()
    }

    /** Leaving the reply box needs a genuinely fresh window, not just dropped focus.
     *
     *  Confirmed on-device, twice: after typing in the reply row and pressing Back, "*" stopped
     *  opening the menu at all. Once this hardware's T9 IME has had a live input connection it
     *  goes on swallowing "*" for its own case-cycling before the event reaches dispatchKeyEvent —
     *  the same capture `ContactsActivity` documents for its search field.
     *
     *  Tried first and **not sufficient**: handing focus to a non-EditText anchor,
     *  `clearFocus()`, `hideSoftInputFromWindow`, and `restartInput` — in combination. The menu
     *  key stayed dead. `ContactsActivity.restartFresh` records the same conclusion (and that an
     *  in-place `recreate()` was ruled out before it), so this takes the same escape: finish and
     *  relaunch with the identical extras, transitions suppressed so it's invisible.
     *
     *  The screen it comes back to is the one it would have shown anyway — the reply row sits at
     *  the newest message, which is where a freshly-opened thread lands — and the draft survives
     *  because [onPause] writes it. */
    private fun restartFresh() {
        finish()
        overridePendingTransition(0, 0)
        startActivity(intentFor(this, threadId, address, contactName))
        overridePendingTransition(0, 0)
    }

    private fun stopComposing() {
        if (!composing) return
        composing = false
        composeField.isFocusable = false
        composeField.isFocusableInTouchMode = false
        composeField.clearFocus()
        keyFocusAnchor.requestFocus()
        restartFresh()
    }

    /** Sends what's in the compose field. Empty is a no-op rather than an empty message — an SMS
     *  with no body is a real thing the radio will happily send and nobody ever means to. */
    private fun sendComposed() {
        val body = composeField.text.toString().trim()
        if (body.isEmpty()) return
        if (address.isBlank()) return

        SmsSender.send(this, threadId, address, body)
        composeField.setText("")
        Prefs.setDraft(this, threadId, "")
        stopComposing()
        // The provider write is what the thread actually renders from; this just stops the list
        // waiting on a ContentObserver tick to show what was clearly just sent.
        loadMessages()
    }

    private fun canCall(): Boolean = isNumericAddress(address)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true

        if (composing) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                rowListView.moveSelection(-1)
                updateHint()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Down past the last message is what opens the reply field — the plan's inline
                // compose row, reached without a key of its own.
                if (!rowListView.canMoveSelection(1)) startComposing() else rowListView.moveSelection(1)
                updateHint()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                jumpDay(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                jumpDay(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                openSelectedMessage()
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                copySelectedCode()
                return true
            }
            KeyEvent.KEYCODE_CALL -> {
                call()
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                event.startTracking()
                pendingStarKeyCode = keyCode
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** The compose field's keys are intercepted ahead of normal dispatch, not in [onKeyDown]:
     *  a focused EditText consumes OK/ENTER (multi-line turns them into a newline), Back, and
     *  every character key including "#", so onKeyDown — which only runs for keys no view took —
     *  would never see any of them. Same reason `TaskEditActivity` intercepts its own inline
     *  editors here. Confirmed on-device: without this, OK inserted a line break and the send key
     *  fell through to the system, which opened P9's dialer instead. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (composing && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // Only reached when the field is empty: confirmed on-device that this
                    // hardware's IME consumes Back as a backspace while there's text, before the
                    // event ever reaches an app — the same capture ContactsActivity documents for
                    // "*". The result is the suite's usual rule anyway (Back clears, Back on
                    // empty leaves), arrived at by the device rather than by design.
                    stopComposing()
                    return true
                }
                KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    sendComposed()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Only leaves the field when nothing is typed; mid-message Up belongs to the
                    // text cursor.
                    if (composeField.text.isEmpty()) {
                        stopComposing()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            if (pendingStarKeyCode == keyCode && !starLongPressHandled) openMenu()
            starLongPressHandled = false
            pendingStarKeyCode = null
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            starLongPressHandled = true
            openSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /** A fast scrub through a long thread, the same one C9's contacts list does across letter
     *  groups — reading back through a year of messages one Down press at a time is not a thing
     *  anyone should have to do. */
    private fun jumpDay(direction: Int) {
        val next = dayJumpIndex(rows, rowListView.selectedIndex, direction) ?: return
        rowListView.setSelectedIndex(next)
        updateHint()
    }

    /** The one-time code in the selected message, if it has one. */
    private fun selectedCode(): String? = selectedMessage()?.body?.let { OtpCodes.find(it) }

    /** `#` on a message: the fastest possible path from "a code arrived" to "the code is on the
     *  clipboard" — no menu, no selection of text, one key. Silent when the selected message has
     *  no code, rather than copying something arbitrary. */
    private fun copySelectedCode() {
        val code = selectedCode() ?: return
        copyToClipboard(code)
        hintBar.setText("copied $code")
    }

    private fun selectedMessage(): MessageRow.Entry? = rows.getOrNull(rowListView.selectedIndex) as? MessageRow.Entry

    private fun openSelectedMessage() {
        val message = selectedMessage() ?: return
        startActivity(MessageDetailActivity.intentFor(this, message.id, contactName ?: address))
    }

    private fun call() {
        if (!canCall()) return
        placeCallOrRequestPermission(address)
    }

    /** Places the call itself rather than handing off to a dialer with the number filled in. The
     *  call key inside a conversation means "call the person I'm reading", and making that two
     *  presses in two apps is one too many.
     *
     *  Asks for CALL_PHONE the first time it's used rather than at first run, the same
     *  request-when-needed shape `ContactsActivity.placeCallOrRequestPermission` uses: someone who
     *  only ever texts is never asked for it at all. */
    private fun placeCallOrRequestPermission(number: String) {
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            pendingCallNumber = number
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CALL_PHONE_PERMISSION) return
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null &&
            checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        }
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        selectedMessage()?.let { message ->
            OtpCodes.find(message.body)?.let { code ->
                items += MenuItem("Copy code $code") { copyToClipboard(code) }
            }
            items += MenuItem("Message details") { openSelectedMessage() }
            items += MenuItem("Copy text") { copyToClipboard(message.body) }
            // Only for a message that actually failed — the one state that asks the user to do
            // something about it.
            if (message.status == SendStatus.FAILED) {
                items += MenuItem("Resend") { resend(message) }
            }
            // Forward hands the body to the compose screen with the recipient left empty —
            // that's the only thing a forward actually is on SMS, since there's nothing to
            // attach and no original to quote.
            items += MenuItem("Forward") { forward(message) }
            items += MenuItem("Delete message") { confirmDeleteMessage(message) }
        }
        if (canCall()) items += MenuItem("Call") { call() }
        items += MenuItem("Delete conversation") { confirmDeleteThread() }
        items += MenuItem("Settings") { openSettings() }
        menuSheet.show(items)
    }

    /** Reuses the failed message's own row rather than writing a new one, so resending doesn't
     *  leave two copies of the same message in the thread. */
    private fun resend(message: MessageRow.Entry) {
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id)
        Async.run(
            background = { SmsSender.resend(this, uri, address, message.body) },
            onResult = { loadMessages() },
        )
    }

    private fun forward(message: MessageRow.Entry) {
        startActivity(
            Intent(this, ComposeActivity::class.java)
                .putExtra(Intent.EXTRA_TEXT, message.body),
        )
    }

    /** Flagged sensitive for the same reason [life.furrer.m9.sms.CopyCodeReceiver] does it:
     *  Android 13+ previews what was copied in its confirmation, and a one-time code is exactly
     *  what shouldn't be rendered over a lock screen. */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("Message", text).apply {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            },
        )
    }

    private fun confirmDeleteMessage(message: MessageRow.Entry) {
        menuSheet.show(
            listOf(
                MenuItem("Delete this message?") {
                    Async.run(background = { SmsStore.deleteMessage(this, message.id) }, onResult = { loadMessages() })
                },
                MenuItem("Cancel") { menuSheet.hide() },
            ),
        )
    }

    private fun confirmDeleteThread() {
        menuSheet.show(
            listOf(
                MenuItem("Delete this whole conversation?") {
                    Async.run(background = { SmsStore.deleteThread(this, threadId) }, onResult = { finish() })
                },
                MenuItem("Cancel") { menuSheet.hide() },
            ),
        )
    }

    private fun openSettings() {
        startActivity(NineSettingsActivity.intentFor(this, Nine.app.settingsActivity))
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val EXTRA_THREAD_ID = "thread_id"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_CONTACT_NAME = "contact_name"

        /** The address and resolved name travel as extras rather than being re-derived here: the
         *  caller already resolved them for the row it drew, and re-querying PhoneLookup on open
         *  would make the title flicker in from blank. */
        fun intentFor(context: Context, threadId: Long, address: String, contactName: String?): Intent =
            Intent(context, ConversationActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_ADDRESS, address)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
    }
}
