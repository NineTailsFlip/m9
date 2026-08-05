package life.furrer.m9.conversations

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.SmsStore
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
 *  M2 is read-only: the compose row the plan puts at the bottom of this screen, and the actions
 *  that write (Forward, Resend), arrive with M4/M5 once sending exists. What's here now is
 *  everything you can do to a message you can only read. */
class ConversationActivity : Activity() {

    private var threadId: Long = -1L
    private var address: String = ""
    private var contactName: String? = null
    private var rows: List<MessageRow> = emptyList()

    private var pendingStarKeyCode: Int? = null
    private var starLongPressHandled = false

    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private var contentObserver: ContentObserver? = null

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createMessageRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            bindMessageRow(view as LinearLayout, rows[index], selected)
        }
        override fun rowHeight(index: Int): Int = messageRowHeightDp(this@ConversationActivity, rows[index])
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
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        hideStatusBar()
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

    private fun markRead() {
        Async.run(background = { SmsStore.markThreadRead(this, threadId) }, onResult = {})
    }

    private fun updateHint() {
        hintBar.setText(if (canCall()) "* menu · call ring · ok open" else "* menu · ok open")
    }

    private fun canCall(): Boolean = isNumericAddress(address)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                rowListView.moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                rowListView.moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                openSelectedMessage()
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

    private fun selectedMessage(): MessageRow.Entry? = rows.getOrNull(rowListView.selectedIndex) as? MessageRow.Entry

    private fun openSelectedMessage() {
        val message = selectedMessage() ?: return
        startActivity(MessageDetailActivity.intentFor(this, message.id, contactName ?: address))
    }

    private fun call() {
        if (!canCall()) return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address")))
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        selectedMessage()?.let { message ->
            items += MenuItem("Message details") { openSelectedMessage() }
            items += MenuItem("Copy text") { copyToClipboard(message.body) }
            items += MenuItem("Delete message") { confirmDeleteMessage(message) }
        }
        if (canCall()) items += MenuItem("Call") { call() }
        items += MenuItem("Delete conversation") { confirmDeleteThread() }
        items += MenuItem("Settings") { openSettings() }
        menuSheet.show(items)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", text))
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
