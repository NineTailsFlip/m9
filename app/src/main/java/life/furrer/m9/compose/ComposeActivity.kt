package life.furrer.m9.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.provider.Telephony
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.m9.conversations.ConversationActivity
import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.SmsSender
import life.furrer.m9.sms.SmsStore
import life.furrer.m9.sms.segmentLabel
import life.furrer.nine.contacts.ContactValuePickerActivity
import life.furrer.nine.contacts.DataKind
import life.furrer.nine.core.Nine
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

private const val SIDE_PADDING_DP = 16
private const val REQUEST_PICK_NUMBER = 200

/** A new message: who to, what to say, send.
 *
 *  **This screen has no contact-matching code of its own, on purpose.** Picking a recipient is
 *  `:contacts`' [ContactValuePickerActivity] with `EXTRA_KIND = PHONE` — the identical screen,
 *  identical keys and identical behaviour as C9's "Add attendee", which picks an email with the
 *  same code. Choosing a number and choosing an address are one interaction users learn once.
 *
 *  It also means the multi-value chooser lands *here* rather than in the picker, which is
 *  load-bearing rather than tidy: the picker owns a live [life.furrer.nine.core.ui.SearchField],
 *  and this hardware's T9 IME keeps composing into a focused field behind an open menu (see that
 *  class's own doc comment for the on-device finding). This screen has no search field, so a
 *  [MenuSheet] over it is safe — exactly the position `EventEditActivity` is in when it asks
 *  which of several emails to invite.
 *
 *  Fields are edited inline in `TaskEditActivity`/`EventEditActivity`'s style, including their
 *  Autofill workaround: [commitInlineEdit] unconditionally `recreate()`s and [RetainedState]
 *  carries the unsaved draft across it. */
class ComposeActivity : Activity() {

    private var recipient: String = ""
    private var recipientName: String? = null
    private var body: String = ""
    private var selectedField = Field.RECIPIENT

    private var inlineEdit: EditText? = null
    private var pendingStarKeyCode: Int? = null
    private var starLongPressHandled = false

    private lateinit var recipientRow: TextView
    private lateinit var bodyRow: TextView
    private lateinit var fieldColumn: LinearLayout
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var keyFocusAnchor: View

    private enum class Field { RECIPIENT, BODY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        // A draft carried across the Autofill-driven recreate() below, or a fresh screen.
        val retained = lastNonConfigurationInstance as? RetainedState
        if (retained != null) {
            recipient = retained.recipient
            recipientName = retained.recipientName
            body = retained.body
            selectedField = retained.selectedField
        } else {
            val (draftTo, draftBody) = Prefs.composeDraft(this)
            recipient = draftTo
            body = draftBody
            recipientName = draftTo.takeIf { it.isNotBlank() }?.let { SmsStore.lookupContactName(this, it) }
            selectedField = if (recipient.isBlank()) Field.RECIPIENT else Field.BODY
            // An explicit intent always wins over a leftover draft — being handed a recipient is
            // a clearer statement of intent than something abandoned earlier.
            if (intent.data != null || intent.hasExtra(Intent.EXTRA_TEXT)) prefillFromIntent()
        }

        recipientRow = fieldRow()
        bodyRow = fieldRow()
        // Something focusable that isn't an EditText, so removing an inline editor from the
        // hierarchy has somewhere to hand focus — the same anchor TaskEditActivity keeps.
        keyFocusAnchor = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        fieldColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyFocusAnchor, LinearLayout.LayoutParams(0, 0))
            // WRAP_CONTENT, not a fixed height: a resolved recipient renders as two lines (name
            // then number), and confirmed on-device that a fixed row clips the number away —
            // leaving the screen claiming to be addressed to a name with no visible number.
            addView(recipientRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bodyRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@ComposeActivity).apply {
                    text = "New message"
                    textSize = 18f
                    setTextColor(Palette.WHITE)
                    setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(8))
                },
            )
            addView(fieldColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()
        render()
    }

    /** An `smsto:` intent from another app (or from P9's contact detail screen), which arrives
     *  already addressed — so the useful landing spot is the body, not the recipient. */
    private fun prefillFromIntent() {
        body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()

        val address = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        if (address != null) {
            recipient = address
            recipientName = SmsStore.lookupContactName(this, address)
        }
        // An addressed intent lands on the body (there's nothing left to choose); a forward,
        // which arrives with text but nobody to send it to, lands on the recipient.
        selectedField = if (address != null) Field.BODY else Field.RECIPIENT
    }

    override fun onRetainNonConfigurationInstance(): Any =
        RetainedState(recipient, recipientName, body, selectedField)

    override fun onPause() {
        super.onPause()
        // Not while an inline editor is mid-recreate: onPause fires during the Autofill-driven
        // recreate() too, and RetainedState is already carrying the text across that. Writing
        // here as well would be harmless but would also persist a draft the user is still typing.
        if (!isChangingConfigurations) Prefs.setComposeDraft(this, recipient, body)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Same defensive cancel EventEditActivity/TaskEditActivity make — see KNOWN_ISSUE_T9_IME.md.
        getSystemService(android.view.autofill.AutofillManager::class.java)?.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun fieldRow(): TextView = TextView(this).apply {
        textSize = 16f
        setPadding(dp(SIDE_PADDING_DP), dp(8), dp(SIDE_PADDING_DP), dp(8))
    }

    private fun render() {
        renderRow(recipientRow, "To", recipientLabel(), selectedField == Field.RECIPIENT)
        renderRow(bodyRow, "Text", body.ifEmpty { "(empty)" }, selectedField == Field.BODY)
        updateHint()
    }

    private fun recipientLabel(): String = when {
        recipient.isEmpty() -> "(nobody yet)"
        recipientName != null -> "$recipientName\n$recipient"
        else -> recipient
    }

    private fun renderRow(row: TextView, label: String, value: String, selected: Boolean) {
        row.text = "$label   $value"
        row.setTextColor(if (selected) Palette.WHITE else Palette.GRAY_DIM)
        row.paintFlags = if (selected) {
            row.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            row.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun updateHint() {
        if (inlineEdit != null) {
            val segments = segmentLabel(inlineEdit?.text?.toString().orEmpty())
            hintBar.setText(if (segments.isEmpty()) "ok done" else "$segments · ok done")
            return
        }
        hintBar.setText(
            when (selectedField) {
                Field.RECIPIENT -> "ok pick · * menu · call send"
                Field.BODY -> "ok edit · * menu · call send"
            },
        )
    }

    /** Inline-edit keys are intercepted here rather than in [onKeyDown], for the reason
     *  `TaskEditActivity`/`EventEditActivity` document on their own equivalents: a focused
     *  EditText consumes OK/ENTER (a multi-line field turns them into a newline) and Back, so
     *  onKeyDown — which only runs for keys no view took — never sees them. Confirmed on-device
     *  as a real dead end: pressing OK to finish a message inserted a line break, the field
     *  stayed open, and the send key then fell through to the system, which opened P9's dialer. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val editing = inlineEdit
        if (editing != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    commitInlineEdit()
                    return true
                }
                KeyEvent.KEYCODE_CALL -> {
                    // Send straight from the open field — no commit-then-recreate first, since
                    // send() leaves this screen anyway and a recreate mid-send would act on a
                    // dying instance.
                    body = editing.text.toString()
                    inlineEdit = null
                    send()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (inlineEdit != null) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                selectedField = Field.RECIPIENT
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                selectedField = Field.BODY
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (selectedField) {
                    Field.RECIPIENT -> openNumberPicker()
                    Field.BODY -> editBody()
                }
                return true
            }
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_POUND -> {
                send()
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
            startActivity(NineSettingsActivity.intentFor(this, Nine.app.settingsActivity))
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /** The whole recipient interaction: search by name, browse letter-grouped, or type the number
     *  itself and take the `Add "…"` row the picker puts at the top. */
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
        // More than one number on the picked contact — asked here rather than in the picker, for
        // the reason this class's doc comment gives (and that EventEditActivity's own comment
        // gives for emails): no search field on this screen for the IME to keep composing into.
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
        recipient = number
        recipientName = name
        // Straight on to what you actually came to write.
        selectedField = Field.BODY
        render()
    }

    private fun editBody() {
        val editText = EditText(this).apply {
            setText(body)
            setSelection(body.length)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Palette.WHITE)
            setBackgroundColor(Palette.BLACK)
            setPadding(dp(SIDE_PADDING_DP), dp(8), dp(SIDE_PADDING_DP), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = updateHint()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        val index = fieldColumn.indexOfChild(bodyRow)
        fieldColumn.removeView(bodyRow)
        fieldColumn.addView(editText, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        editText.requestFocus()
        inlineEdit = editText
        updateHint()
    }

    /** Unconditionally `recreate()`s, exactly as `TaskEditActivity.commitInlineEdit` does and for
     *  the same confirmed-on-device reason: focusing an EditText on this hardware can pop an
     *  Autofill suggestion that corrupts the window's key dispatch, and a fresh window is the only
     *  reliable recovery. [RetainedState] is what makes that safe to do with an unsaved draft. */
    private fun commitInlineEdit() {
        val editText = inlineEdit ?: return
        body = editText.text.toString()
        keyFocusAnchor.requestFocus()
        inlineEdit = null
        recreate()
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        items += MenuItem("Pick recipient") { openNumberPicker() }
        if (recipient.isNotEmpty() && body.isNotBlank()) items += MenuItem("Send") { send() }
        items += MenuItem("Discard") {
            recipient = ""
            body = ""
            Prefs.setComposeDraft(this, "", "")
            finish()
        }
        items += MenuItem("Settings") { startActivity(NineSettingsActivity.intentFor(this, Nine.app.settingsActivity)) }
        menuSheet.show(items)
    }

    /** Never a silent no-op: an empty recipient or body says so in the hint bar, because on a
     *  screen with no send button the alternative is a keypress that appears to do nothing. */
    private fun send() {
        if (recipient.isEmpty()) {
            hintBar.setText("no recipient yet — ok to pick one")
            return
        }
        if (body.isBlank()) {
            hintBar.setText("nothing to send — write something first")
            return
        }
        val address = recipient
        val text = body.trim()
        Async.run(
            background = {
                val threadId = Telephony.Threads.getOrCreateThreadId(this, address)
                SmsSender.send(this, threadId, address, text)
                threadId
            },
            onResult = { threadId ->
                Prefs.setComposeDraft(this, "", "")
                // Straight into the conversation, which is where the message now lives and where
                // its delivery state will appear — a compose screen that just closed would leave
                // the user wondering whether anything happened.
                startActivity(ConversationActivity.intentFor(this, threadId, address, recipientName))
                finish()
            },
        )
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private data class RetainedState(
        val recipient: String,
        val recipientName: String?,
        val body: String,
        val selectedField: Field,
    )

    companion object {
        fun intentFor(context: Context): Intent = Intent(context, ComposeActivity::class.java)
    }
}
