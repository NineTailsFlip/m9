package life.furrer.m9.conversations

import android.Manifest
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
import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.SmsMessage
import life.furrer.m9.sms.SmsStore
import life.furrer.nine.contacts.ContactsActivity
import life.furrer.nine.core.Nine
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.PermissionScreen
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

private const val REQUEST_SMS_PERMISSIONS = 100
private const val SIDE_PADDING_DP = 16

/** Reserved exactly as `ContactsActivity` reserves them, and for the reason its own comment
 *  gives: Up/Down/OK are this screen's list navigation and must never reach the search field,
 *  and `*`/`#`/the call key are this screen's menu, filter and call actions. Left/Right are
 *  deliberately NOT reserved here — the conversation list has no sections to jump between (no day
 *  headers, no letter groups), so they stay available to the text field's own cursor. */
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND,
    KeyEvent.KEYCODE_CALL,
)

/** M9's launcher entry: every conversation, newest first, filtered live by whatever is typed.
 *
 *  The key handling here is `ContactsActivity`'s, not `TaskListActivity`'s, because this screen
 *  has a live [SearchField] and that changes everything about dispatch — `*`'s tap-vs-hold has to
 *  be tracked by hand (the framework's own `onKeyLongPress` never fires for a key this screen
 *  intercepts in `dispatchKeyEvent`), and reserved keys have to be pulled out before the IME sees
 *  them. See `ContactsActivity`'s own comments for the on-device findings behind each piece; this
 *  screen is a deliberate mirror of it rather than a second solution to the same problem. */
class ConversationListActivity : Activity() {

    private var allMessages: List<SmsMessage> = emptyList()
    private var rows: List<ThreadRow> = emptyList()
    private var unreadOnly = false

    // See ContactsActivity's identical fields for the on-device reasoning behind all three.
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var starLongPressFired = false
    private val starLongPressRunnable = Runnable {
        starLongPressFired = true
        openSettings()
    }
    private var menuConsumedKeyUp: Int? = null
    private val seenKeyDowns = mutableSetOf<Int>()

    private lateinit var rowListView: RowListView
    private lateinit var searchField: SearchField
    private lateinit var countLabel: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var permissionScreen: PermissionScreen
    private var contentObserver: ContentObserver? = null

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = createThreadRowContainer(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            bindThreadRow(view as LinearLayout, rows[index], selected)
        }
        override fun rowHeight(index: Int): Int = threadRowHeightDp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        unreadOnly = intent.getBooleanExtra(EXTRA_UNREAD_ONLY, false)

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildRows() }
            onDeactivated = { restartFresh() }
            editText.apply {
                hint = "Type to search"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(6))
            }
        }

        rowListView = RowListView(this).apply { setBinder(binder) }
        countLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), dp(4))
            visibility = View.GONE
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        permissionScreen = PermissionScreen(this).apply {
            onGrant = { requestSmsPermissions() }
            onLeave = { finish() }
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(countLabel)
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(permissionScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(root)
        hideStatusBar()
        updateHint()

        if (hasNeededPermissions()) loadMessages() else permissionScreen.show(permissionExplainer())
    }

    private fun hasNeededPermissions(): Boolean = SmsStore.hasReadPermission(this)

    /** Says what M9 can and can't do at this milestone, rather than promising the finished app.
     *  The "no network permission" line is the same reassurance every Nine app's explainer
     *  carries, and it stays literally true for M9 even once it sends: SMS goes over the radio
     *  via SmsManager, not a socket M9 opens. */
    private fun permissionExplainer(): String =
        "M9 needs access to your Messages to show your conversations, and to your Contacts to " +
            "show who they're with.\n\n" +
            "There is no network permission — nothing here ever talks to a server."

    private fun requestSmsPermissions() {
        requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), REQUEST_SMS_PERMISSIONS)
    }

    override fun onResume() {
        super.onResume()
        if (hasNeededPermissions()) {
            permissionScreen.hide()
            registerContentObserver()
            loadMessages()
        } else if (!permissionScreen.isShowing) {
            permissionScreen.show(permissionExplainer())
        }
    }

    override fun onPause() {
        super.onPause()
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        searchField.onPause()
        seenKeyDowns.clear()
    }

    /** [SearchField.onDeactivated]'s fix for this device's captured `*`/`#` needs a genuinely
     *  fresh window — see `ContactsActivity.restartFresh`, which this mirrors, for what was ruled
     *  out first. [unreadOnly] rides along as an extra for the same reason favourites-only does
     *  there: it's the one piece of state worth keeping, and everything else is either cheap to
     *  reload or already empty by the time this fires. */
    private fun restartFresh() {
        finish()
        overridePendingTransition(0, 0)
        startActivity(Intent(this, ConversationListActivity::class.java).putExtra(EXTRA_UNREAD_ONLY, unreadOnly))
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            searchField.onWindowFocusGained()
            hideStatusBar()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SMS_PERMISSIONS) return
        if (hasNeededPermissions()) {
            permissionScreen.hide()
            loadMessages()
        } else {
            permissionScreen.show(permissionExplainer())
        }
    }

    private fun registerContentObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = loadMessages()
        }
        contentObserver = observer
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
    }

    private fun loadMessages() {
        Async.run(
            background = { SmsStore.queryMessages(this, Prefs.messagesToLoad(this)) },
            onResult = { result ->
                allMessages = result
                rebuildRows()
            },
        )
    }

    private fun rebuildRows() {
        rows = buildThreadRows(allMessages, searchField.query, unreadOnly)
        rowListView.notifyDataChanged()
        val query = searchField.query
        countLabel.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        if (query.isNotEmpty()) {
            val total = buildThreadRows(allMessages).size
            countLabel.text = "${rows.size} of $total conversations"
        }
        updateHint()
    }

    private fun updateHint() {
        hintBar.setText(
            if (searchField.query.isEmpty()) {
                val toggleLabel = if (unreadOnly) "# all" else "# unread"
                "* menu · $toggleLabel · ok open"
            } else {
                "‹ clear · ok open"
            },
        )
    }

    /** See [SearchField]'s own doc comment for the pattern this follows, and `ContactsActivity`'s
     *  `dispatchKeyEvent` for the identical structure — including why the menu's trailing
     *  ACTION_UP has to be swallowed by hand ([menuConsumedKeyUp]) and why a `*` ACTION_UP is only
     *  trusted when this instance saw its own ACTION_DOWN ([seenKeyDowns]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (permissionScreen.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) permissionScreen.handleKeyDown(event.keyCode)
            return true
        }
        if (menuSheet.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                menuSheet.handleKeyDown(event.keyCode, event)
                menuConsumedKeyUp = event.keyCode
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == menuConsumedKeyUp) {
            menuConsumedKeyUp = null
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            onBackNotConsumedByIme()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_STAR) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    seenKeyDowns.add(event.keyCode)
                    starLongPressFired = false
                    longPressHandler.postDelayed(starLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                KeyEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(starLongPressRunnable)
                    if (seenKeyDowns.remove(event.keyCode) && !starLongPressFired) openMenu()
                }
            }
            return true
        }
        if (searchField.handleKeyEvent(event, RESERVED_KEYS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in RESERVED_KEYS) {
                handleReservedKeyDown(event.keyCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Back clears the search; on an already-empty search it leaves — the same rule
     *  `ContactsActivity` follows, and for the same reason: digits belong to the live search
     *  field, so no digit key is free to mean "clear". */
    private fun onBackNotConsumedByIme() {
        if (searchField.query.isEmpty()) finish() else searchField.clear()
    }

    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openConversation()
            KeyEvent.KEYCODE_POUND -> toggleUnreadOnly()
            KeyEvent.KEYCODE_CALL -> callSelected()
        }
    }

    /** Resets the selection to the top, unlike a plain [rebuildRows]. Confirmed on-device that
     *  keeping the index across a filter change lands somewhere arbitrary — toggling to unread-
     *  only while sitting on row 7 of 9 clamps to the last of the 5 that survive, so the newest
     *  unread thread (the whole reason for the filter) ends up off the top of the selection. */
    private fun toggleUnreadOnly() {
        unreadOnly = !unreadOnly
        rebuildRows()
        rowListView.selectFirstSelectable()
    }

    private fun selectedRow(): ThreadRow? = rows.getOrNull(rowListView.selectedIndex)

    private fun openConversation() {
        val row = selectedRow() ?: return
        startActivity(ConversationActivity.intentFor(this, row.threadId, row.address, row.displayName))
    }

    /** The call key dials the selected thread's correspondent — the single most likely thing to
     *  want next after reading a message. Silently does nothing for an alphanumeric sender, which
     *  is not a number and cannot be called (see `ThreadRow.canCall`). ACTION_DIAL rather than
     *  ACTION_CALL: it hands off to whatever dialer holds the role with the number pre-filled, so
     *  M9 never needs CALL_PHONE — a permission a messaging app has no business holding. */
    private fun callSelected() {
        val row = selectedRow() ?: return
        if (!row.canCall) return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${row.address}")))
    }

    private fun openMenu() {
        val row = selectedRow()
        val items = mutableListOf<MenuItem>()
        if (row != null) {
            items += MenuItem("Mark read") { markRead(row) }
            items += MenuItem("Delete conversation") { confirmDeleteThread(row) }
            if (row.canCall) items += MenuItem("Call") { callSelected() }
        }
        items += MenuItem("Contacts") { startActivity(Intent(this, ContactsActivity::class.java)) }
        items += MenuItem("Settings") { openSettings() }
        menuSheet.show(items)
    }

    private fun markRead(row: ThreadRow) {
        Async.run(
            background = { SmsStore.markThreadRead(this, row.threadId) },
            onResult = { loadMessages() },
        )
    }

    /** Deleting a conversation is the one irreversible action on this screen, so it asks — on the
     *  same sheet, the way C9's Delete does, rather than in a dialog this hardware would have to
     *  find a way to dismiss. */
    private fun confirmDeleteThread(row: ThreadRow) {
        menuSheet.show(
            listOf(
                MenuItem("Delete ${row.title}?") {
                    Async.run(
                        background = { SmsStore.deleteThread(this, row.threadId) },
                        onResult = { loadMessages() },
                    )
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

    private companion object {
        const val EXTRA_UNREAD_ONLY = "unread_only"
    }
}
