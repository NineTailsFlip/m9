package life.furrer.m9.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, plain JUnit.
 *
 *  Every case below is the *shape* of a message from a real Swiss message store — the phrasings,
 *  languages and layouts are verbatim, the digits are not (there is no reason to commit somebody's
 *  actual one-time codes to a repository, even expired ones). */
class OtpCodesTest {

    @Test
    fun `a labelled code on its own line`() {
        assertEquals("04157724", OtpCodes.find("Code: 04157724\nSwissID"))
    }

    @Test
    fun `a short code`() {
        // Five digits — real, and below the six most senders use.
        assertEquals("31080", OtpCodes.find("Code: 31080"))
    }

    @Test
    fun `a code with a letter prefix`() {
        assertEquals("S-1256", OtpCodes.find("Supercard Login Code: S-1256"))
    }

    @Test
    fun `a German code word, in a body full of other numbers`() {
        // The case the whole design is anchored on: an amount, a date, a time and a masked card
        // all come before the code, and every one of them would beat it to a naive "first digits"
        // match.
        val body = """
            Digitec - Galaxus Online
            CHF 55.00
            02.08.2026 / 09:25
            Mastercard XXXX 5464
            Ihr Bestätigungscode für die Zahlung: 211145
        """.trimIndent()

        assertEquals("211145", OtpCodes.find(body))
    }

    @Test
    fun `the code before its code word`() {
        assertEquals("047725", OtpCodes.find("047725 is your Shop verification code"))
    }

    @Test
    fun `a message with no code at all is not guessed at`() {
        // A real TWINT security notice: prose, no code, and nothing here should be offered for
        // copying. A false positive is worse than no feature — it puts a wrong string on the
        // clipboard and the user finds out at the login screen.
        val body = "You used a new device for a TWINT registration. If you are using a new mobile " +
            "phone, you may disregard this message. If you are not aware of a new device, please " +
            "contact the support immediately."

        assertNull(OtpCodes.find(body))
        assertFalse(OtpCodes.has(body))
    }

    @Test
    fun `an ordinary message with numbers in it is not a code message`() {
        assertNull(OtpCodes.find("See you at 1830 outside the station"))
        assertNull(OtpCodes.find("Das kostet 55.00 und kommt am 02.08.2026"))
    }

    @Test
    fun `a code word never reaches across a digit, even when that loses a real code`() {
        // A human reads 998877 here, and this declines to. That's the deliberate side of the
        // trade: the digit ban is what keeps the payment-confirmation case above correct, and a
        // wrong code on the clipboard is worse than no code — you find out at the login screen,
        // after the real one has already been used up or expired. No message in the store this
        // was built against actually looks like this.
        assertNull(OtpCodes.find("Code für CHF 55.00: 998877"))
    }

    @Test
    fun `a code word never reaches across a line break`() {
        // The number belongs to the next message/paragraph, not to this code word.
        assertNull(OtpCodes.find("Your code did not arrive\n55123 is our support line"))
    }

    @Test
    fun `too short and too long runs are not codes`() {
        assertNull(OtpCodes.find("Code: 123"))
        assertNull(OtpCodes.find("Code: 123456789012"))
    }

    @Test
    fun `a French or Italian code word works without being listed`() {
        // Matching any word ending in "code" is what makes this multilingual for free — the same
        // sender writes in whichever language it feels like on a Swiss SIM.
        assertEquals("445566", OtpCodes.find("Votre code de vérification: 445566"))
        assertEquals("778899", OtpCodes.find("Il tuo codice: 778899"))
    }

    @Test
    fun `a password is never offered`() {
        // Deliberately not matched: a message containing a real password should not be one
        // keypress from the clipboard.
        assertNull(OtpCodes.find("Your temporary password is 837462"))
    }

    @Test
    fun `has agrees with find`() {
        assertTrue(OtpCodes.has("Code: 31080"))
        assertFalse(OtpCodes.has("nothing to see here"))
    }
}
