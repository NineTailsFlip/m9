package life.furrer.m9.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.m9.conversations.ConversationActivity
import life.furrer.m9.sms.SmsSender
import life.furrer.m9.sms.SmsStore
import life.furrer.m9.sms.SmsUri
import life.furrer.m9.sms.segmentLabel
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.core.settings.openNineSettings
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.LongPressKey
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

private const val REQUEST_PICK_NUMBER = 200

/** A new message — deliberately the same screen as a conversation, with the messages missing.
 *
 *  The recipient sits in the header where a thread puts the person you're talking to, the message
 *  is written in the row at the bottom where a thread puts the reply, and the empty space between
 *  them is exactly the space a conversation's history would occupy. Sending lands you in the real
 *  thread, which by then looks identical except that it has the message in it.
 *
 *  Its first version was a form — a "To" field above a "Text" field, each opened for editing — and
 *  that was the wrong shape: it made composing a different activity from replying, when they're
 *  the same act with one extra question. The keys follow the conversation screen's too: Up/Down
 *  move between the two things on screen, OK acts on the selected one, and OK in the message row
 *  sends.
 *
 *  There is no inline-edit-then-`recreate()` dance here (unlike `TaskEditActivity`, and unlike
 *  this screen's own first version): the message field is a permanent part of the layout rather
 *  than a row swapped for an `EditText`, so the Autofill quirk that workaround exists for never
 *  arises. `ConversationActivity`'s reply row is built the same way for the same reason. */
class ComposeActivity : Activity() {

    /** Usually one number. Several only arrive from an `smsto:a,b` link; M9 has no MMS, so they
     *  get the message one by one, each in their own thread — the same as Android's own
     *  messaging app does for a group without MMS. Picking a recipient here always replaces them. */
    private var recipients: List<String> = emptyList()
    private var recipientNames: List<String?> = emptyList()

    /** Which of the screen's two parts the keys act on. Mirrors the conversation screen, where
     *  the same Down that walks off the last message lands in the reply row. */
    private enum class Focus { RECIPIENT, MESSAGE }

    private var focus = Focus.RECIPIENT

    private val starKey = LongPressKey(::openMenu) { openNineSettings() }

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var messageField: ComposeField
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        // A new message starts blank, always. The only things that can pre-fill it are the intent
        // that opened it — an addressed smsto: from another app, or a Forward carrying its text —
        // never anything left over from last time. A link's own ?body= wins over the extras;
        // sms_body is the extra older apps put the text in instead of EXTRA_TEXT.
        val addressing = SmsUri.parse(intent.data?.encodedSchemeSpecificPart)
        recipients = addressing.recipients
        recipientNames = recipients.map { SmsStore.lookupContactName(this, it) }
        val initialBody = addressing.body
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra("sms_body")
            ?: ""

        titleView = TextView(this).apply {
            textSize = 18f
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), 0)
        }
        subtitleView = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), 0, dp(Dimens.SIDE_PADDING_DP), dp(6))
        }
        // The gap where a conversation's messages would be. Left empty rather than filled with an
        // explanation: the header already says "New message", and a screen this small is not
        // worth spending on telling someone what they just chose to do.
        val emptySpace = View(this)

        messageField = ComposeField(this, "Message…", bottomPaddingDp = 6, ::updateHint)
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        // Only now: the field's watcher updates the hint bar, which has to exist first.
        messageField.view.setText(initialBody)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subtitleView)
            addView(emptySpace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(messageField.view)
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()

        // With a recipient already known there is nothing left to choose, so start where the
        // writing happens — the same landing spot an addressed smsto: intent deserves.
        focus = if (recipients.isEmpty()) Focus.RECIPIENT else Focus.MESSAGE
        render()

        // "Share via M9" brings text and no addressee, so the one thing left to ask is who it
        // goes to: open the picker straight away. Only on a fresh start — a recreate keeps the
        // recipient already chosen. Backing out of the picker leaves the screen as Forward does.
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND && recipients.isEmpty()) {
            openNumberPicker()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(android.view.autofill.AutofillManager::class.java)?.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun render() {
        val selected = focus == Focus.RECIPIENT && !messageField.isComposing
        titleView.text = if (recipients.isEmpty()) {
            "New message"
        } else {
            recipients.indices.joinToString(", ") { recipientNames[it] ?: recipients[it] }
        }
        titleView.setTextColor(if (selected) Palette.WHITE else Palette.GRAY_DIM)
        titleView.paintFlags = if (selected) {
            titleView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            titleView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }

        // The number only earns its own line when it isn't already the title.
        subtitleView.text = when {
            recipients.isEmpty() -> "No recipient yet"
            recipients.size > 1 -> "${recipients.size} recipients · sent to each separately"
            recipientNames[0] != null -> recipients[0]
            else -> ""
        }
        subtitleView.visibility = if (subtitleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        updateHint()
    }

    private fun updateHint() {
        if (messageField.isComposing) {
            val segments = segmentLabel(messageField.text.toString())
            hintBar.setText(if (segments.isEmpty()) "ok send · ↑ recipient" else "$segments · ok send")
            return
        }
        hintBar.setText(
            when (focus) {
                Focus.RECIPIENT -> "* menu · ok pick recipient · ↓ message"
                Focus.MESSAGE -> "* menu · ok write · ↑ recipient"
            },
        )
    }

    private fun startComposing() {
        focus = Focus.MESSAGE
        messageField.start()
        render()
    }

    private fun stopComposing() {
        messageField.stop()
    }

    /** The message field's keys are intercepted ahead of normal dispatch, for the reason
     *  `ConversationActivity`'s reply row documents: a focused EditText consumes OK/ENTER as a
     *  newline and every character key, so [onKeyDown] — which only runs for keys no view took —
     *  would never see them. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (messageField.isComposing && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL -> {
                    send()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Back up to the recipient only from an empty message; mid-sentence, Up
                    // belongs to the text cursor.
                    if (messageField.text.isEmpty()) {
                        stopComposing()
                        focus = Focus.RECIPIENT
                        render()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    // Reached only once the field is empty — this hardware's IME consumes Back as
                    // a backspace while there's text (see ConversationActivity's own note).
                    stopComposing()
                    focus = Focus.RECIPIENT
                    render()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (messageField.isComposing) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                startComposing()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                focus = Focus.RECIPIENT
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focus) {
                    Focus.RECIPIENT -> openNumberPicker()
                    Focus.MESSAGE -> startComposing()
                }
                return true
            }
            KeyEvent.KEYCODE_CALL -> {
                send()
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                if (event.repeatCount == 0) starKey.onDown()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            starKey.onUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        starKey.reset()
    }

    /** The whole recipient interaction: search by name, browse letter-grouped, or type the number
     *  itself and take the `Add "…"` row the picker puts at the top. Identical to the screen C9
     *  picks an attendee's email with. */
    private fun openNumberPicker() {
        startActivityForResult(
            Intent(this, ContactValuePickerActivity::class.java)
                .putExtra(ContactValuePickerActivity.EXTRA_KIND, DataKind.PHONE.name),
            REQUEST_PICK_NUMBER,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_NUMBER || resultCode != RESULT_OK || data == null) return

        val name = data.getStringExtra(ContactValuePickerActivity.EXTRA_NAME)
        val single = data.getStringExtra(ContactValuePickerActivity.EXTRA_VALUE)
        if (single != null) {
            applyRecipient(single, name)
            return
        }
        // Several numbers on the picked contact — asked here rather than in the picker, because
        // this screen has no search field for the IME to keep composing into behind the menu.
        val values = data.getStringArrayExtra(ContactValuePickerActivity.EXTRA_VALUES) ?: return
        val labels = data.getStringArrayExtra(ContactValuePickerActivity.EXTRA_LABELS).orEmpty()
        menuSheet.show(
            values.mapIndexed { index, value ->
                val label = labels.getOrNull(index).orEmpty()
                MenuItem(if (label.isEmpty()) value else "$label: $value") { applyRecipient(value, name) }
            },
        )
    }

    private fun applyRecipient(number: String, name: String?) {
        recipients = listOf(number)
        recipientNames = listOf(name)
        // Straight on to what you actually came here to write.
        startComposing()
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        items += MenuItem(if (recipients.isEmpty()) "Pick recipient" else "Change recipient") { openNumberPicker() }
        if (recipients.isNotEmpty() && messageField.text.isNotBlank()) items += MenuItem("Send") { send() }
        items += MenuItem("Discard") { finish() }
        items += MenuItem("Settings") { openNineSettings() }
        menuSheet.show(items)
    }

    /** Never a silent no-op: on a screen with no send button, a keypress that appears to do
     *  nothing is indistinguishable from a broken key. */
    private fun send() {
        val text = messageField.text.toString().trim()
        if (recipients.isEmpty()) {
            hintBar.setText("no recipient yet — ↑ then ok to pick one")
            return
        }
        if (text.isBlank()) {
            hintBar.setText("nothing to send — write something first")
            return
        }
        val addresses = recipients
        Async.run(
            background = {
                addresses.map { address ->
                    val threadId = Telephony.Threads.getOrCreateThreadId(this, address)
                    SmsSender.send(this, threadId, address, text)
                    threadId
                }
            },
            onResult = { threadIds ->
                // Into the conversation, which is where the message now lives and where its
                // delivery state will appear — and which is what this screen was already shaped
                // like. Sent to several, there's no one conversation to land in, so back to the
                // list, where each of the threads now sits on top.
                if (threadIds.size == 1) {
                    startActivity(ConversationActivity.intentFor(this, threadIds[0], addresses[0], recipientNames[0]))
                }
                finish()
            },
        )
    }

    companion object {
        fun intentFor(context: Context): Intent = Intent(context, ComposeActivity::class.java)
    }
}
