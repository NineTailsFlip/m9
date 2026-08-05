package life.furrer.m9.conversations

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import life.furrer.m9.core.Prefs
import life.furrer.m9.sms.SmsMessage
import life.furrer.m9.sms.SmsStore
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.applyConfiguredTheme
import life.furrer.nine.core.ui.hideStatusBar
import life.furrer.nine.core.util.Async

private const val SIDE_PADDING_DP = 16

/** One message in full. The conversation list truncates a long SMS to a few lines and the thread
 *  screen caps it at six, so this is where the rest of it lives — plus the details a row has no
 *  room for: the exact timestamp, the delivery state in words, and which SIM carried it.
 *
 *  A [ScrollView] rather than a [life.furrer.nine.core.ui.RowListView]: there is exactly one
 *  variable-height thing on this screen, so d-pad scrolling of a single text block is the whole
 *  interaction, and building it out of rows would be ceremony. */
class MessageDetailActivity : Activity() {

    private var messageId: Long = -1L
    private lateinit var bodyView: TextView
    private lateinit var metaView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyConfiguredTheme()

        messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)

        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            textSize = 18f
            setTextColor(Palette.WHITE)
            setPadding(dp(SIDE_PADDING_DP), dp(10), dp(SIDE_PADDING_DP), dp(6))
        }
        bodyView = TextView(this).apply {
            // The message body honours the font setting, like the thread's own rows do — this is
            // the screen you open *because* a message was too long to read in the thread, so
            // ignoring the setting here would fail it at exactly the moment it matters. The
            // title and the metadata below stay fixed: they frame the message rather than being
            // it, and scaling them would eat the room the larger body needs.
            textSize = Prefs.messageFontSize(this@MessageDetailActivity).toFloat()
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), dp(12))
        }
        metaView = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), dp(12))
        }
        scrollView = ScrollView(this).apply {
            // Not focusable: Up/Down are handled in onKeyDown below, so the ScrollView never
            // needs framework focus to scroll — and a focused ScrollView draws the theme's focus
            // background, which on-device turned the whole message area grey against the rest of
            // the suite's black.
            isFocusable = false
            setBackgroundColor(Palette.BLACK)
            addView(
                LinearLayout(this@MessageDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(bodyView)
                    addView(metaView)
                },
            )
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(HintBar(this@MessageDetailActivity).apply { setText("↑↓ scroll · ‹ back") })
            },
        )
        hideStatusBar()
        load()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    /** Re-read from the provider rather than passed in as extras: unlike the thread screen's
     *  title (which only needs to be right at the moment it's drawn), a message's *status* is
     *  exactly the thing this screen exists to report, and it can have changed since the row that
     *  launched it was built. */
    private fun load() {
        Async.run(
            background = {
                SmsStore.queryMessages(this, Prefs.messagesToLoad(this)).firstOrNull { it.id == messageId }
            },
            onResult = { message ->
                if (message == null) {
                    // Deleted underneath us — nothing to show, and no reason to keep a blank
                    // screen open.
                    finish()
                    return@run
                }
                bodyView.text = message.body
                metaView.text = metaLines(message)
            },
        )
    }

    private fun metaLines(message: SmsMessage): String {
        val status = sendStatusOf(message.type, message.status)
        val lines = mutableListOf<String>()
        lines += if (status == SendStatus.RECEIVED) "Received from ${message.address}" else "Sent to ${message.address}"
        lines += TIMESTAMP_FORMAT.format(Date(message.date))
        lines += statusWord(status)
        // Only worth a line on a phone where more than one SIM could have carried it; a
        // single-SIM phone's answer is never interesting.
        if (message.subscriptionId > 1) lines += "SIM ${message.subscriptionId}"
        return lines.joinToString("\n")
    }

    private fun statusWord(status: SendStatus): String = when (status) {
        SendStatus.RECEIVED -> "Received"
        SendStatus.SENDING -> "Sending…"
        SendStatus.SENT -> "Sent"
        SendStatus.DELIVERED -> "Delivered"
        SendStatus.FAILED -> "Failed to send"
        SendStatus.DRAFT -> "Draft"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                scrollView.smoothScrollBy(0, -dp(SCROLL_STEP_DP))
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                scrollView.smoothScrollBy(0, dp(SCROLL_STEP_DP))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_TITLE = "title"
        private const val SCROLL_STEP_DP = 60
        private val TIMESTAMP_FORMAT = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault())

        fun intentFor(context: Context, messageId: Long, title: String): Intent =
            Intent(context, MessageDetailActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
