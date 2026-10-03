package life.furrer.m9.sms

import java.net.URLDecoder

/** Who an `sms:`/`smsto:`/`mms:`/`mmsto:` intent addresses, and the text it pre-fills. */
data class SmsAddressing(val recipients: List<String>, val body: String?)

/** Reads the scheme-specific part of an `sms:` URI the way RFC 5724 lays it out:
 *  `sms:+41791234567,+41797654321?body=Hi%20there`.
 *
 *  Taking `Uri.schemeSpecificPart` whole as the recipient (what M9 first did) turned
 *  `?body=Hi` into part of the phone number, creating a junk thread and a send that could only
 *  fail, and read `smsto:a,b` as one address. Pure Kotlin so it can be unit-tested without a
 *  device: callers pass `Uri.encodedSchemeSpecificPart`, still encoded, because decoding first
 *  would let a `%26` or `%3F` inside the body split it. */
object SmsUri {

    fun parse(encodedSchemeSpecificPart: String?): SmsAddressing {
        val raw = encodedSchemeSpecificPart.orEmpty()
        val queryStart = raw.indexOf('?')
        val addressPart = if (queryStart >= 0) raw.substring(0, queryStart) else raw
        val query = if (queryStart >= 0) raw.substring(queryStart + 1) else ""

        // Comma is RFC 5724's separator; semicolon is what Android's own messaging app has
        // always also accepted, and what some pages still generate.
        val recipients = addressPart.split(',', ';')
            .map { decode(it).trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val body = query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].equals("body", ignoreCase = true) }
            ?.let { decode(it[1]) }
            ?.takeIf { it.isNotEmpty() }

        return SmsAddressing(recipients, body)
    }

    /** Percent-decoding only: a `+` stays a `+`, since in a phone number it's the international
     *  prefix rather than form-encoding's space. A malformed escape leaves the text as it came. */
    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
}
