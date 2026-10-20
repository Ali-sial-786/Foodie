package app.nouri.core.barcode

/**
 * GS1 Global Trade Item Numbers as printed on retail packages: EAN-8, UPC-A (12 digits),
 * EAN-13 and GTIN-14, plus expansion of zero-suppressed UPC-E codes.
 *
 * The canonical form used throughout the app (and by Open Food Facts) is 13 digits for UPC-A and
 * EAN-13, 8 digits for EAN-8 and 14 digits for a genuine GTIN-14.
 */
object Gtin {

    private val lengths = setOf(8, 12, 13, 14)

    /** True when [code] is 8, 12, 13 or 14 digits with a correct GS1 check digit. */
    fun isValid(code: String): Boolean {
        if (code.length !in lengths || code.any { it !in '0'..'9' }) return false
        return checkDigit(code.dropLast(1)) == code.last() - '0'
    }

    /**
     * GS1 mod-10 check digit for [payload] (all digits except the check digit): weights 3 and 1
     * alternate starting with 3 at the rightmost payload digit, so the same rule serves every
     * GTIN length.
     */
    fun checkDigit(payload: String): Int {
        require(payload.isNotEmpty() && payload.all { it in '0'..'9' }) { "GTIN payload must be digits" }
        var sum = 0
        for ((i, c) in payload.reversed().withIndex()) {
            val digit = c - '0'
            sum += if (i % 2 == 0) digit * 3 else digit
        }
        return (10 - sum % 10) % 10
    }

    /**
     * Strips spaces and dashes, validates and returns the canonical form, or null when the code
     * is not a valid GTIN:
     * - UPC-A (12) → 13 digits with a leading 0; EAN-13 unchanged;
     * - EAN-8 unchanged; an 8-digit code that fails the EAN-8 check but is a valid UPC-E
     *   (as some scanners report it) is expanded to UPC-A and then to 13 digits;
     * - GTIN-14 unchanged, except that a leading 0 is dropped because "0" + EAN-13 is the same item.
     */
    fun normalize(raw: String): String? {
        val digits = raw.filterNot { it == '-' || it.isWhitespace() }
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        return when (digits.length) {
            8 -> if (isValid(digits)) digits else expandUpcE(digits)?.let { "0$it" }
            12 -> if (isValid(digits)) "0$digits" else null
            13 -> if (isValid(digits)) digits else null
            14 -> if (!isValid(digits)) null else if (digits.startsWith('0')) digits.drop(1) else digits
            else -> null
        }
    }

    /**
     * Expands a UPC-E code to its 12-digit UPC-A equivalent, or null when it is malformed.
     * Accepts 8 digits (number system 0/1, six data digits, check digit — the check digit is
     * verified against the expanded code), 7 digits (no check digit) or the 6 data digits alone
     * (number system 0 assumed). Standard rules, keyed on the last data digit d6:
     * - 0–2: manufacturer d1 d2 d6 00, product 00 d3 d4 d5
     * - 3: manufacturer d1 d2 d3 00, product 000 d4 d5
     * - 4: manufacturer d1 d2 d3 d4 0, product 0000 d5
     * - 5–9: manufacturer d1 d2 d3 d4 d5, product 0000 d6
     */
    fun expandUpcE(upcE: String): String? {
        val code = upcE.filterNot { it == '-' || it.isWhitespace() }
        if (code.any { it !in '0'..'9' }) return null
        val numberSystem: Char
        val d: String
        val check: Int?
        when (code.length) {
            6 -> { numberSystem = '0'; d = code; check = null }
            7 -> { numberSystem = code[0]; d = code.substring(1); check = null }
            8 -> { numberSystem = code[0]; d = code.substring(1, 7); check = code[7] - '0' }
            else -> return null
        }
        if (numberSystem != '0' && numberSystem != '1') return null
        val body = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        val payload = "$numberSystem$body"
        val computed = checkDigit(payload)
        if (check != null && check != computed) return null
        return "$payload$computed"
    }
}
