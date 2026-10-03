package life.furrer.m9.sms

import android.content.Intent
import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The incoming notification's Mark as read action and its dismissal: the first clears READ and
 *  SEEN, the second only SEEN, and neither touches another thread. */
@RunWith(RobolectricTestRunner::class)
class ThreadNotificationReceiverTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FakeSmsProvider.reset()
        Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms")
        FakeSmsProvider.putMessage(1L, threadId = 1L, address = "079123", body = "a", date = 1L, read = false, seen = false)
        FakeSmsProvider.putMessage(2L, threadId = 2L, address = "079999", body = "b", date = 2L, read = false, seen = false)
    }

    private fun deliver(action: String, threadId: Long? = 1L) {
        val intent = Intent(context, ThreadNotificationReceiver::class.java).apply {
            this.action = action
            if (threadId != null) putExtra(ThreadNotificationReceiver.EXTRA_THREAD_ID, threadId)
        }
        ThreadNotificationReceiver.handle(context, intent)
    }

    private fun flags(threadId: Long) = FakeSmsProvider.rowsOf(threadId).single().let {
        it.getAsInteger(Telephony.Sms.READ) to it.getAsInteger(Telephony.Sms.SEEN)
    }

    @Test
    fun `mark as read sets READ and SEEN on that thread only`() {
        deliver(ThreadNotificationReceiver.ACTION_MARK_READ)

        assertEquals(1 to 1, flags(1L))
        assertEquals(0 to 0, flags(2L))
    }

    @Test
    fun `dismissing marks the thread seen but leaves it unread`() {
        deliver(ThreadNotificationReceiver.ACTION_DISMISSED)

        assertEquals(0 to 1, flags(1L))
        assertEquals(0 to 0, flags(2L))
    }

    @Test
    fun `no thread id changes nothing`() {
        deliver(ThreadNotificationReceiver.ACTION_MARK_READ, threadId = null)

        assertEquals(0 to 0, flags(1L))
        assertEquals(0 to 0, flags(2L))
    }
}
