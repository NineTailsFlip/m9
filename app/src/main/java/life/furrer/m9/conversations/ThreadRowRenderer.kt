package life.furrer.m9.conversations

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import life.furrer.nine.core.ui.Palette

/** Always two lines — title and snippet — unlike P9's call log, where the second line is detail
 *  about an already-obvious row and therefore only appears on the selected one. Here the snippet
 *  *is* the row's content: a conversation list showing only names would make every thread look
 *  identical, and scanning "which of these do I need to answer" is the entire job of this screen. */
const val THREAD_ROW_HEIGHT_DP = 56
private const val ROW_SIDE_PADDING_DP = 16

// A fixed column for the unread dot, so every title starts at the same x whether or not its
// thread has unread messages — a ragged left edge is much harder to scan than a mostly-empty
// column. Same reasoning as CallLogRowRenderer's own glyph column.
private const val MARKER_COLUMN_WIDTH_DP = 20

/** How far into a snippet a code can sit and still count as "you'd have read it anyway" — beyond
 *  this it gets pulled to the front. Roughly the point where a narrow screen is still showing
 *  text rather than an ellipsis. */
private const val CODE_ALREADY_VISIBLE_CHARS = 16

fun threadRowHeightDp(): Int = THREAD_ROW_HEIGHT_DP

fun createThreadRowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
}

fun bindThreadRow(container: LinearLayout, row: ThreadRow, selected: Boolean) {
    container.removeAllViews()
    val context = container.context
    val textColor = if (selected) Color.WHITE else Palette.GRAY_DIM

    val mainLine = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, ROW_SIDE_PADDING_DP), 0, dp(context, ROW_SIDE_PADDING_DP), 0)
    }
    mainLine.addView(
        TextView(context).apply {
            // The one piece of state worth a glyph on this screen. Kept as a dot rather than an
            // unread *count*: on a thread you haven't opened, "there is something here" is the
            // actionable fact, and the count is visible the moment you open it.
            text = if (row.hasUnread) "●" else ""
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (row.hasUnread) Palette.WHITE else textColor)
        },
        LinearLayout.LayoutParams(dp(context, MARKER_COLUMN_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    mainLine.addView(
        TextView(context).apply {
            text = row.title
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textColor)
            paintFlags = if (selected) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    mainLine.addView(
        TextView(context).apply {
            // Date-aware, not just the time — see formatThreadTimestamp for what going without
            // it looked like on a real message store.
            text = formatThreadTimestamp(row.date, LocalDate.now())
            textSize = 13f
            setTextColor(Palette.GRAY_MUTED)
        },
    )
    // height=0 + weight=1 rather than WRAP_CONTENT, for the reason CallLogRowRenderer documents
    // at length: the container is measured at an exact height, and two WRAP_CONTENT children
    // whose natural heights exceed it silently overflow and get clipped instead of shrinking.
    container.addView(mainLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    container.addView(
        TextView(context).apply {
            // Newlines in a message body would each eat a row's worth of height in a fixed-height
            // row, pushing the rest out of view — flattened to spaces so a multi-line SMS still
            // previews as one line of prose.
            // A code message's snippet leads with the code, but only when the snippet wouldn't
            // have shown it anyway: the code is usually the only part anyone reads, and it's
            // often past the truncation point of the sentence announcing it ("Digitec - Galaxus
            // Onl…"). Prefixing unconditionally reads as a stutter on the many senders whose
            // message *starts* with the code — "31080 · Code: 31080" — so a code already visible
            // near the front is left where it is.
            val flattened = row.snippet.replace(Regex("\\s+"), " ").trim()
            val alreadyVisible = row.code != null && flattened.indexOf(row.code).let { it in 0 until CODE_ALREADY_VISIBLE_CHARS }
            text = if (row.code != null && !alreadyVisible) "${row.code} · $flattened" else flattened
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (row.code != null) Palette.GRAY_DIM else Palette.GRAY_MUTED)
            // Lines up with the title, not the marker column above it.
            setPadding(dp(context, ROW_SIDE_PADDING_DP + MARKER_COLUMN_WIDTH_DP), 0, dp(context, ROW_SIDE_PADDING_DP), dp(context, 6))
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

private fun dp(context: Context, value: Int): Int = android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
).toInt()
