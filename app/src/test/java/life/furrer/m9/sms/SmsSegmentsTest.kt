package life.furrer.m9.sms

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure, plain JUnit. These numbers are 3GPP TS 23.038's, not preferences — getting them wrong
 *  costs the user real money on a prepaid SIM, which is the whole reason the composer shows them. */
class SmsSegmentsTest {

    @Test
    fun `a plain body is GSM-7 and fits 160 characters in one message`() {
        val info = segmentsFor("a".repeat(160))

        assertEquals(SmsEncoding.GSM7, info.encoding)
        assertEquals(1, info.segments)
        assertEquals(0, info.charsRemaining)
    }

    @Test
    fun `one character past 160 becomes two segments of 153, not two of 160`() {
        // The user-data header that lets the receiver reassemble the parts eats seven septets
        // from every segment, which is why the second segment's allowance is 153.
        val info = segmentsFor("a".repeat(161))

        assertEquals(2, info.segments)
        assertEquals(2 * 153 - 161, info.charsRemaining)

        // And the two-segment window ends at 306, not 320.
        assertEquals(2, segmentsFor("a".repeat(306)).segments)
        assertEquals(3, segmentsFor("a".repeat(307)).segments)
    }

    @Test
    fun `a single non-GSM character re-encodes the whole message as UCS-2`() {
        // There's no partial encoding: one emoji costs the entire message more than half its
        // length. This is the case the composer's label exists to explain.
        val info = segmentsFor("a".repeat(100) + "🙂")

        assertEquals(SmsEncoding.UCS2, info.encoding)
        assertEquals(2, info.segments)
    }

    @Test
    fun `UCS-2 fits 70 characters in one message and 67 per part after that`() {
        val single = segmentsFor("‘".repeat(70))
        assertEquals(SmsEncoding.UCS2, single.encoding)
        assertEquals(1, single.segments)
        assertEquals(0, single.charsRemaining)

        val two = segmentsFor("‘".repeat(71))
        assertEquals(2, two.segments)
        assertEquals(2 * 67 - 71, two.charsRemaining)
    }

    @Test
    fun `German umlauts stay GSM-7 but a curly quote does not`() {
        // Umlauts are in the GSM-7 basic table — a Swiss or German message is not automatically
        // unicode, which would otherwise be a nasty surprise.
        assertEquals(SmsEncoding.GSM7, segmentsFor("Grüezi, schöne Grüsse äöü").encoding)
        // A typographic apostrophe is not, and phones' own keyboards produce them freely.
        assertEquals(SmsEncoding.UCS2, segmentsFor("It’s fine").encoding)
    }

    @Test
    fun `extension-table characters cost two septets each`() {
        // 80 square brackets is 160 septets — a full single message, from 80 typed characters.
        val info = segmentsFor("[".repeat(80))

        assertEquals(SmsEncoding.GSM7, info.encoding)
        assertEquals(1, info.segments)
        assertEquals(0, info.charsRemaining)

        assertEquals(2, segmentsFor("[".repeat(81)).segments)
    }

    @Test
    fun `an empty body is one empty segment, not zero`() {
        val info = segmentsFor("")

        assertEquals(1, info.segments)
        assertEquals(160, info.charsRemaining)
    }

    @Test
    fun `the label stays quiet for an ordinary message and speaks when it matters`() {
        assertEquals("", segmentLabel("See you at eight"))
        assertEquals("2 messages · 145 left", segmentLabel("a".repeat(161)))
        assertEquals("15 left", segmentLabel("a".repeat(145)))
    }

    @Test
    fun `the label names unicode, since that is the surprising case`() {
        assertEquals("unicode · 60 left", segmentLabel("‘".repeat(10)))
        assertEquals(SmsEncoding.UCS2, segmentsFor("‘".repeat(71)).encoding)
        assertEquals("2 messages · 63 left · unicode", segmentLabel("‘".repeat(71)))
    }
}
