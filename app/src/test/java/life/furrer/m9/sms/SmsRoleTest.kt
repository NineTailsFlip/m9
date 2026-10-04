package life.furrer.m9.sms

import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.prefs.NinePrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SmsRoleTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Nine.init(NineApp(id = "smsrole-test", name = "Test", tagline = "", surfaces = listOf(NineSurface("", "", "", emptyList())), settingsActivity = Any::class.java))
    }

    @Test
    fun `hasAskedBefore persists under the key existing installs already store`() {
        assertFalse(SmsRole.hasAskedBefore(context))
        SmsRole.markAsked(context)
        assertTrue(SmsRole.hasAskedBefore(context))
        // Renaming the key would ask every existing install again.
        assertTrue(NinePrefs.of(context).getBoolean("asked_sms_role", false))
    }
}
