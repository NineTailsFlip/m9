package life.furrer.m9.conversations

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.DateFmt

private const val HEADER_ROW_HEIGHT_DP = 24
private const val ROW_SIDE_PADDING_DP = 16
private const val MESSAGE_META_HEIGHT_DP = 18

/** Caps how tall one message row can get, so a pathological SMS can't fill the whole screen —
 *  [MessageDetailActivity] is where the rest of it lives. */
private const val MAX_LINES = 6

/** A couple of dp of headroom under the text, because a `TextView` laid out at exactly its
 *  `StaticLayout` height still clips descenders on the last line on this hardware — the same
 *  class of off-by-a-few-pixels clipping `CallLogRowRenderer`'s own 56dp-not-48dp comment
 *  documents for its two-line row. */
private const val VERTICAL_SLACK_DP = 4

/** Measures message bodies the way they'll actually be drawn.
 *
 *  Row heights have to be known before layout — [life.furrer.nine.core.ui.RowListView.
 *  recomputeRowTops] asks the binder for every row's height up front — so the first version of
 *  this estimated wrapped lines from body length at an assumed characters-per-line. That was
 *  badly wrong on real messages, confirmed on-device: a Supercard login-code SMS rendered as one
 *  clipped half-line with the rest of the message simply gone, because "Supercard Login Code:"
 *  alone already fills this screen's width at 16sp — nowhere near the 30 characters the estimate
 *  assumed.
 *
 *  So it measures instead of guessing: a [StaticLayout] over the real text, with the real paint
 *  (taken from a probe `TextView`, so the theme's monospace-or-not setting is honoured) at the
 *  real available width. The paint is cached per [Context] — one probe `TextView` per Activity
 *  instance rather than per row, since a thread's worth of rows is re-measured on every provider
 *  change — and re-derived whenever the Context changes, which is what a theme switch produces. */
private class MessageMeasurer(context: Context, fontSizeSp: Int) {
    private val paint = TextPaint(TextView(context).apply { textSize = fontSizeSp.toFloat() }.paint)

    /** The screen's width, not the list view's: `RowListView` fills the window horizontally, and
     *  its own measured width isn't known yet at the point row heights are asked for. */
    private val widthPx = (context.resources.displayMetrics.widthPixels - 2 * context.dp(ROW_SIDE_PADDING_DP)).coerceAtLeast(1)

    /** Height in px of [text] as it will actually wrap, clamped to [MAX_LINES]. */
    fun textHeightPx(text: String): Int {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx).build()
        val lines = layout.lineCount.coerceIn(1, MAX_LINES)
        return layout.getLineBottom(lines - 1)
    }
}

private var cachedMeasurer: Triple<Context, Int, MessageMeasurer>? = null

/** Keyed on the font size as well as the Context: changing the size in Settings doesn't
 *  necessarily produce a new Context, and a measurer cached at the old size would lay every row
 *  out to the wrong height. */
private fun measurer(context: Context, fontSizeSp: Int): MessageMeasurer {
    cachedMeasurer?.let { (cachedContext, cachedSize, measurer) ->
        if (cachedContext === context && cachedSize == fontSizeSp) return measurer
    }
    return MessageMeasurer(context, fontSizeSp).also { cachedMeasurer = Triple(context, fontSizeSp, it) }
}

/** Message rows are the one genuinely variable-height row in the suite: an SMS is anywhere
 *  between one line and a few hundred characters, and a fixed height would either clip the long
 *  ones or waste most of a small screen on the short ones (which are the common case). */
fun messageRowHeightDp(context: Context, row: MessageRow, fontSizeSp: Int): Int = when (row) {
    is MessageRow.DayHeader -> HEADER_ROW_HEIGHT_DP
    is MessageRow.Entry -> {
        val textDp = pxToDp(context, measurer(context, fontSizeSp).textHeightPx(row.body))
        textDp + MESSAGE_META_HEIGHT_DP + VERTICAL_SLACK_DP
    }
}

fun createMessageRowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
}

fun bindMessageRow(container: LinearLayout, row: MessageRow, selected: Boolean, fontSizeSp: Int) {
    container.removeAllViews()
    when (row) {
        is MessageRow.DayHeader -> bindDayHeader(container, row)
        is MessageRow.Entry -> bindMessage(container, row, selected, fontSizeSp)
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
private fun bindMessage(container: LinearLayout, row: MessageRow.Entry, selected: Boolean, fontSizeSp: Int) {
    val context = container.context
    val alignment = if (row.outgoing) Gravity.END else Gravity.START

    container.addView(
        TextView(context).apply {
            text = row.body
            textSize = fontSizeSp.toFloat()
            maxLines = MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = alignment
            // Brightness alone marks the selection here — no underline, unlike every single-line
            // row in the suite. A message is prose and can run to six lines; underlining all of
            // them draws a rule under every line of a paragraph, which fights the text instead of
            // pointing at it. White against GRAY_DIM is already an unmissable difference, and it
            // costs the reading nothing.
            setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
            paintFlags = paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            setPadding(context.dp(ROW_SIDE_PADDING_DP), 0, context.dp(ROW_SIDE_PADDING_DP), 0)
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
            setPadding(context.dp(ROW_SIDE_PADDING_DP), 0, context.dp(ROW_SIDE_PADDING_DP), context.dp(2))
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

/** The inverse of [dp] — row heights are declared in dp (RowListView converts them back), but
 *  text measurement answers in px. */
private fun pxToDp(context: Context, px: Int): Int =
    (px / context.resources.displayMetrics.density).toInt()
