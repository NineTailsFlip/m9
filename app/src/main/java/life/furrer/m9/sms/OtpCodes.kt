package life.furrer.m9.sms

/** Finds the one-time code in a message, when there is one.
 *
 *  This is the "Copy code" chip other messaging apps show on a verification SMS. It needs no
 *  machine learning: a code message says what it is, in words, right next to the code. What it
 *  does need is to be anchored on those words rather than on "the first run of digits", because
 *  a real code message is usually *full* of other numbers. The payment confirmation this was
 *  built against carries an amount, a date, a time and the last four digits of a card before it
 *  ever reaches the code:
 *
 *      Digitec - Galaxus Online
 *      CHF 55.00
 *      02.08.2026 / 09:25
 *      Mastercard XXXX 5464
 *      Ihr Bestätigungscode für die Zahlung: 211145
 *
 *  So both patterns below require a code word, and neither is allowed to step over a digit to
 *  reach its candidate — which is what keeps `55.00`, `02.08.2026`, `09:25` and `5464` out of the
 *  answer while `211145` comes back.
 *
 *  Multilingual by suffix rather than by dictionary: matching `…code` catches German
 *  `Bestätigungscode`/`Verifizierungscode`/`Sicherheitscode` and French `code` without listing
 *  them, which matters on a Swiss SIM where the same sender writes in whichever language it
 *  feels like. */
object OtpCodes {

    /** A code word: any word ending in "code" (Bestätigungscode, verification code, code), or one
     *  of the short forms that don't. Deliberately not "password" — a message containing a real
     *  password is not a thing to make one keypress away from the clipboard. */
    private const val CODE_WORD = """(?:[\p{L}]*code|[\p{L}]*codice|otp|tan)"""

    /** 4 to 10 characters: digits, optionally with a single-letter prefix and a hyphen, which is
     *  what a `S-1256`-style code looks like. Bounded at both ends so a longer number can't have
     *  a valid-looking code carved out of its middle. */
    private const val CANDIDATE = """(?:\p{Lu}-)?\d{4,10}"""

    /** Between the code word and the code: words and punctuation, but **never a digit and never a
     *  line break**. The digit ban is what stops "Bestätigungscode für die Zahlung" from reaching
     *  past an amount; the newline ban keeps a code word on one line from claiming a number three
     *  lines away. */
    private const val GAP = """[^\n\d]{0,40}?"""

    /** The separator between a code word and its code — a colon or plain spacing, but
     *  **explicitly not a newline**. Found by test: with `\s` here, "Your code did not arrive" on
     *  one line would reach over the line break and claim the first number on the next one. A code
     *  sits on the same line as the words announcing it. */
    private const val SEPARATOR = """[:\t ][\t ]*"""

    /** "Code: 123456" — the code word first, then the code. */
    private val LABEL_FIRST = Regex("""\b$CODE_WORD\b$GAP$SEPARATOR($CANDIDATE)\b""", RegexOption.IGNORE_CASE)

    /** "047725 is your Shop verification code" — the code first, then the code word. */
    private val CODE_FIRST = Regex("""\b($CANDIDATE)\b$GAP\b$CODE_WORD\b""", RegexOption.IGNORE_CASE)

    /** The code in [body], or null if it doesn't look like a code message at all.
     *
     *  [LABEL_FIRST] is tried first because it's the stronger signal: a message that labels its
     *  code is telling you exactly which number matters, whereas the code-first form has to infer
     *  it from proximity. */
    fun find(body: String): String? =
        LABEL_FIRST.find(body)?.groupValues?.get(1)
            ?: CODE_FIRST.find(body)?.groupValues?.get(1)

    fun has(body: String): Boolean = find(body) != null
}
