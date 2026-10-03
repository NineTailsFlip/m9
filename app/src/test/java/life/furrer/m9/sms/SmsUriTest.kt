package life.furrer.m9.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure, plain JUnit. Inputs are what `Uri.encodedSchemeSpecificPart` hands over: still encoded. */
class SmsUriTest {

    @Test
    fun `a bare number`() {
        assertEquals(SmsAddressing(listOf("+41791234567"), null), SmsUri.parse("+41791234567"))
    }

    @Test
    fun `the body parameter is not part of the recipient`() {
        assertEquals(SmsAddressing(listOf("+41791234567"), "Hi"), SmsUri.parse("+41791234567?body=Hi"))
    }

    @Test
    fun `the body is percent-decoded but keeps a plus`() {
        assertEquals("Hi there & 1+1", SmsUri.parse("123?body=Hi%20there%20%26%201+1").body)
    }

    @Test
    fun `an encoded plus in the number decodes`() {
        assertEquals(listOf("+41791234567"), SmsUri.parse("%2B41791234567").recipients)
    }

    @Test
    fun `several recipients split on comma and semicolon`() {
        assertEquals(listOf("111", "222", "333"), SmsUri.parse("111,222;333").recipients)
    }

    @Test
    fun `blank and repeated recipients are dropped`() {
        assertEquals(listOf("111", "222"), SmsUri.parse("111,,%20,222,111").recipients)
    }

    @Test
    fun `body only, no recipient`() {
        assertEquals(SmsAddressing(emptyList(), "Hello"), SmsUri.parse("?body=Hello"))
    }

    @Test
    fun `other parameters are ignored and body is found case-insensitively`() {
        assertEquals("Yo", SmsUri.parse("123?foo=bar&BODY=Yo").body)
    }

    @Test
    fun `an empty body is no body`() {
        assertNull(SmsUri.parse("123?body=").body)
    }

    @Test
    fun `nothing at all`() {
        assertEquals(SmsAddressing(emptyList(), null), SmsUri.parse(null))
    }

    @Test
    fun `a malformed escape is kept as typed`() {
        assertEquals("50%", SmsUri.parse("123?body=50%").body)
    }
}
