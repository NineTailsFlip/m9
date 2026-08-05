package life.furrer.m9.sms

/** Which alphabet a message body can be encoded in, and therefore how much of it fits per SMS. */
enum class SmsEncoding {
    /** The GSM 7-bit default alphabet: 160 characters in a single message. */
    GSM7,

    /** UCS-2, used the moment one character falls outside GSM-7 — and it costs more than half the
     *  message: 70 characters instead of 160. A single emoji, curly quote, or non-Latin letter
     *  does it. */
    UCS2,
}

/** How a body will actually go out. [charsRemaining] counts down to the end of the *current*
 *  segment, which is what a composer should show — "15 left" is only useful if it means "before
 *  this becomes a second message". */
data class SmsSegmentInfo(
    val encoding: SmsEncoding,
    val segments: Int,
    val charsRemaining: Int,
)

// The GSM 7-bit default alphabet (3GPP TS 23.038). Each of these costs one septet.
private const val GSM7_BASIC =
    "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

/** The extension table: still GSM-7, but each costs *two* septets because it's sent as an escape
 *  followed by the character. Worth counting properly — a message of mostly square brackets runs
 *  out of room at half the length someone would expect. */
private const val GSM7_EXTENDED = "^{}\\[~]|€"

private const val GSM7_SINGLE_LIMIT = 160
private const val GSM7_MULTIPART_LIMIT = 153
private const val UCS2_SINGLE_LIMIT = 70
private const val UCS2_MULTIPART_LIMIT = 67

/** Counts how a body splits into SMS segments.
 *
 *  Pure, and separate from [SmsSender] on purpose: the compose screen shows this live while
 *  typing, long before anything is sent. It matters on a prepaid SIM — three segments is three
 *  messages' worth of money — and it's the only way to explain why a message that looked short
 *  suddenly costs double, which is what a single non-GSM character does.
 *
 *  Multipart segments hold fewer characters than a single message does (153 instead of 160, 67
 *  instead of 70): the user-data header that tells the receiving phone how to reassemble the
 *  parts takes the difference. That's why the count can jump from one segment to two at 161
 *  characters and stay at two only until 306, not 320. */
fun segmentsFor(body: String): SmsSegmentInfo {
    val encoding = encodingFor(body)
    val units = if (encoding == SmsEncoding.GSM7) gsm7Septets(body) else body.length

    val singleLimit = if (encoding == SmsEncoding.GSM7) GSM7_SINGLE_LIMIT else UCS2_SINGLE_LIMIT
    val multipartLimit = if (encoding == SmsEncoding.GSM7) GSM7_MULTIPART_LIMIT else UCS2_MULTIPART_LIMIT

    if (units <= singleLimit) {
        return SmsSegmentInfo(encoding, segments = 1, charsRemaining = singleLimit - units)
    }
    val segments = (units + multipartLimit - 1) / multipartLimit
    return SmsSegmentInfo(encoding, segments, charsRemaining = segments * multipartLimit - units)
}

/** UCS-2 the moment a single character isn't in either GSM-7 table — there is no partial
 *  encoding, so one stray character re-encodes the entire message. */
private fun encodingFor(body: String): SmsEncoding =
    if (body.all { it in GSM7_BASIC || it in GSM7_EXTENDED }) SmsEncoding.GSM7 else SmsEncoding.UCS2

private fun gsm7Septets(body: String): Int =
    body.fold(0) { total, char -> total + if (char in GSM7_EXTENDED) 2 else 1 }

/** The composer's trailing label — deliberately silent for a body that still fits in one message,
 *  since "1 message · 148 left" is noise while writing a normal text. It starts speaking when the
 *  answer stops being obvious: near the end of the first segment, or once there's more than one.
 *  Also names the encoding when it's UCS-2, because that's the surprising case and the reason
 *  the allowance suddenly looks wrong. */
fun segmentLabel(body: String): String {
    val info = segmentsFor(body)
    return when {
        info.segments > 1 && info.encoding == SmsEncoding.UCS2 -> "${info.segments} messages · ${info.charsRemaining} left · unicode"
        info.segments > 1 -> "${info.segments} messages · ${info.charsRemaining} left"
        info.encoding == SmsEncoding.UCS2 -> "unicode · ${info.charsRemaining} left"
        info.charsRemaining <= SEGMENT_WARNING_THRESHOLD -> "${info.charsRemaining} left"
        else -> ""
    }
}

/** Where the first segment's remaining-character count starts being worth showing. */
private const val SEGMENT_WARNING_THRESHOLD = 20
