package life.furrer.m9.compose

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import life.furrer.m9.core.Prefs
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** The message field both writing screens put at the bottom — `ConversationActivity`'s reply row
 *  and [ComposeActivity]'s New message — and the composing on/off that goes with it. What each
 *  screen does around it (its keys, its hint text, what happens once composing stops) stays with
 *  the screen.
 *
 *  [onEdited] runs on every edit, so the screen's segment counter keeps up with the typing. */
class ComposeField(
    context: Context,
    hint: String,
    bottomPaddingDp: Int,
    onEdited: () -> Unit,
) {
    val view: EditText = EditText(context).apply {
        this.hint = hint
        // Same size as the messages in a thread: what you are writing should look like what you
        // are reading.
        textSize = Prefs.messageFontSize(context).toFloat()
        // Multi-line: an SMS wraps, and a compose box that scrolled sideways on one line would
        // hide what you already typed.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(Palette.WHITE)
        setHintTextColor(Palette.GRAY_FAINT)
        setBackgroundColor(Palette.BLACK)
        gravity = Gravity.START
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(bottomPaddingDp))
        // Focusable only while composing, so the key that enters it is a deliberate one rather
        // than the framework handing it focus on open and swallowing every key.
        isFocusable = false
        isFocusableInTouchMode = false
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onEdited()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /** True while the field has focus and is taking typed characters. */
    var isComposing = false
        private set

    val text: Editable get() = view.text

    /** Makes the field focusable and focuses it. False when it was already composing. */
    fun start(): Boolean {
        if (isComposing) return false
        isComposing = true
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
        return true
    }

    /** Drops focus and makes the field unfocusable again. False when it wasn't composing. Where
     *  focus goes next, and whether the window needs more than this, is the screen's call. */
    fun stop(): Boolean {
        if (!isComposing) return false
        isComposing = false
        view.clearFocus()
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        return true
    }
}
