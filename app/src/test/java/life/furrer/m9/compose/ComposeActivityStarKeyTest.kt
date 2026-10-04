package life.furrer.m9.compose

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import java.time.Duration
import life.furrer.m9.settings.SettingsActivity
import life.furrer.nine.core.ui.MenuSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** `*` on the compose screen: tap opens the menu, hold opens Settings (core's LongPressKey). */
@RunWith(RobolectricTestRunner::class)
class ComposeActivityStarKeyTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun launch(): ComposeActivity =
        Robolectric.buildActivity(ComposeActivity::class.java, ComposeActivity.intentFor(context)).setup().get()

    private fun menuSheetOf(activity: ComposeActivity): MenuSheet =
        findMenuSheet(activity.findViewById(android.R.id.content))!!

    private fun findMenuSheet(v: View): MenuSheet? {
        if (v is MenuSheet) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findMenuSheet(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun down(activity: ComposeActivity, repeatCount: Int = 0): KeyEvent {
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR, repeatCount)
        activity.onKeyDown(KeyEvent.KEYCODE_STAR, event)
        return event
    }

    private fun up(activity: ComposeActivity) {
        activity.onKeyUp(KeyEvent.KEYCODE_STAR, KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STAR, 0))
    }

    private fun holdPastLongPressTimeout() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50L))
    }

    @Test
    fun `tapping star opens the menu`() {
        val activity = launch()
        down(activity)
        up(activity)
        assertTrue(menuSheetOf(activity).isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `holding star opens Settings once, not the menu, however long the repeats run`() {
        val activity = launch()
        down(activity)
        holdPastLongPressTimeout()
        down(activity, repeatCount = 1)
        holdPastLongPressTimeout()
        up(activity)

        assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(menuSheetOf(activity).isShowing)
    }

    @Test
    fun `a hold cut short by leaving the screen leaves the next tap working`() {
        val controller = Robolectric.buildActivity(ComposeActivity::class.java, ComposeActivity.intentFor(context)).setup()
        val activity = controller.get()
        down(activity)
        controller.pause()
        holdPastLongPressTimeout()
        controller.resume()
        up(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(menuSheetOf(activity).isShowing)

        down(activity)
        up(activity)
        assertTrue(menuSheetOf(activity).isShowing)
    }
}
