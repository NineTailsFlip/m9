package life.furrer.m9.conversations

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.util.DateFmt

private const val HEADER_ROW_HEIGHT_DP = 24
private const val ROW_SIDE_PADDING_DP = 16
private const val MESSAGE_LINE_HEIGHT_DP = 22
private const val MESSAGE_META_HEIGHT_DP = 18
private const val MESSAGE_TEXT_SIZE_SP = 16f

/** Roughly how many characters of body fit on one line at [MESSAGE_TEXT_SIZE_SP] on a keypad
 *  phone's narrow screen. Used only to *estimate* a row's height ahead of layout, because
 *  [life.furrer.nine.core.ui.RowListView] asks for a row's height before it has a measured view
 *  to ask — an estimate that runs slightly long costs a few dp of slack, while one that runs
 *  short clips the message, so this errs low on purpose. */
private const val CHARS_PER_LINE = 30
private const val MAX_LINES = 6

/** Message rows are the one variable-height row in the suite: an SMS is between one and a few
 *  hundred characters, and a fixed height would either clip the long ones or waste most of the
 *  screen on the short ones (which are the common case). Estimated from the body length rather
 *  than measured, per [CHARS_PER_LINE]; [MAX_LINES] caps it so one pathological message can't
 *  fill the screen — MessageDetailActivity shows the rest. */
fun messageRowHeightDp(row: MessageRow): Int = when (row) {
    is MessageRow.DayHeader -> HEADER_ROW_HEIGHT_DP
    is MessageRow.Entry -> {
        val lines = (row.body.length / CHARS_PER_LINE + 1).coerceIn(1, MAX_LINES)
        lines * MESSAGE_LINE_HEIGHT_DP + MESSAGE_META_HEIGHT_DP
    }
}

fun createMessageRowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
}

fun bindMessageRow(container: LinearLayout, row: MessageRow, selected: Boolean) {
    container.removeAllViews()
    when (row) {
        is MessageRow.DayHeader -> bindDayHeader(container, row)
        is MessageRow.Entry -> bindMessage(container, row, selected)
    }
}

private fun bindDayHeader(container: LinearLayout, row: MessageRow.DayHeader) {
    val context = container.context
    container.addView(
        TextView(context).apply {
            text = row.label
            textSize = 11f
            letterSpacing = 0.11f
            gravity = Gravity.CENTER
            setTextColor(Palette.GRAY_FAINT)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

/** Inbound flush left, outbound flush right. Alignment alone carries the direction — no bubbles,
 *  no colours, no "You:" prefix: on a small monochrome screen a right-aligned line reads as
 *  "mine" instantly, and the suite's whole visual language is one column of plain text. */
private fun bindMessage(container: LinearLayout, row: MessageRow.Entry, selected: Boolean) {
    val context = container.context
    val alignment = if (row.outgoing) Gravity.END else Gravity.START

    container.addView(
        TextView(context).apply {
            text = row.body
            textSize = MESSAGE_TEXT_SIZE_SP
            maxLines = MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = alignment
            setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
            paintFlags = if (selected) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            setPadding(dp(context, ROW_SIDE_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), 0)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
    )

    container.addView(
        TextView(context).apply {
            // Time and delivery state on one line, so the message body above it is never
            // interrupted. A failed message is the only one that gets colour — it's the only one
            // asking the user to do something about it.
            val glyph = sendStatusGlyph(row.status)
            text = if (glyph.isEmpty()) DateFmt.formatTime(row.date) else "${DateFmt.formatTime(row.date)} $glyph"
            textSize = 11f
            gravity = alignment
            setTextColor(if (row.status == SendStatus.FAILED) Palette.RED else Palette.GRAY_MUTED)
            setPadding(dp(context, ROW_SIDE_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), dp(context, 2))
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

private fun dp(context: Context, value: Int): Int = android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
).toInt()
