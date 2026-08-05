package life.furrer.m9.conversations

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.Nine
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar

private const val SIDE_PADDING_DP = 24

/** M9's launcher entry. This is the M1 skeleton (docs/m9-plan.md's milestone list): no
 *  [life.furrer.m9.sms.SmsStore] exists yet, so there is nothing to list — the screen shows a
 *  placeholder message and only the furniture every Nine app gets for free (hint bar, "*" menu,
 *  hold-"*" Settings). M2 replaces [emptyMessage] with a real, searchable, day-grouped
 *  conversation list; the "*" menu below grows New message/Contacts/Mark read/Delete alongside
 *  it (M2/M5). The tap-vs-hold "*" handling and [MenuSheet] wiring are copied from
 *  [life.furrer.t9.tasks.TaskListActivity] — see its own doc comment for why hold needs
 *  [android.view.KeyEvent.startTracking] rather than a fixed-delay timer. */
class ConversationListActivity : Activity() {

    // Star tap-vs-hold needs the same "wait for key-up/long-press" timing as
    // TaskListActivity/C9's CalendarActivity.
    private var pendingStarKeyCode: Int? = null
    private var starLongPressHandled = false

    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        val emptyMessage = TextView(this).apply {
            text = "No messages yet"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(SIDE_PADDING_DP), dp(SIDE_PADDING_DP), dp(SIDE_PADDING_DP), dp(SIDE_PADDING_DP))
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                emptyMessage,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { gravity = Gravity.CENTER },
            )
            addView(hintBar)
        }
        val root = FrameLayout(this).apply {
            addView(mainColumn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(menuSheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        hideStatusBar()
        updateHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun updateHint() {
        hintBar.setText("* menu · hold * settings")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true

        when (keyCode) {
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

    /** Only "Settings" exists in M1 — New message/Contacts/Mark read/Delete all need a
     *  conversation list to act on, and arrive with the milestones that build one (M2/M5). */
    private fun openMenu() {
        menuSheet.show(listOf(MenuItem("Settings") { openSettings() }))
    }

    private fun openSettings() {
        startActivity(NineSettingsActivity.intentFor(this, Nine.app.settingsActivity))
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
