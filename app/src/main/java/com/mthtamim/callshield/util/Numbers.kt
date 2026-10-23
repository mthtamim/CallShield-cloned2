package com.mthtamim.callshield.util

/**
 * Phone-number normalisation helpers.
 *
 * Two forms are used across the app:
 *  - [key]: the last 10 digits — the identity used for list matching and log
 *    grouping, so "+880 1712-345678", "01712345678" and "8801712345678" are
 *    all the same caller.
 *  - [localForm]: digits in local dialling form (a leading 880 country code
 *    is turned back into the trunk 0), used for prefix-rule matching so a
 *    rule written as "096" also matches "+88096..." callers.
 */
object Numbers {

    fun digits(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder()
        for (c in raw) {
            if (c.isDigit()) sb.append(c)
        }
        return sb.toString()
    }

    fun key(raw: String?): String {
        val d = digits(raw)
        return if (d.length > 10) d.substring(d.length - 10) else d
    }

    fun localForm(raw: String?): String {
        var d = digits(raw)
        if (d.length == 13 && d.startsWith("880")) {
            d = "0" + d.substring(3)
        }
        return d
    }

    /** True when [number] starts with [prefix] after both are normalised. */
    fun startsWithPrefix(number: String, prefix: String): Boolean {
        val n = localForm(number)
        val p = localForm(prefix)
        if (p.isEmpty()) return false
        return n.startsWith(p)
    }

    /**
     * True for numbers that are clearly not Bangladeshi local numbers:
     * an explicit "+" country code other than 880, a "00" international
     * prefix, or a long digit string that does not start with 880.
     * Short codes (6 digits or fewer) are never international.
     */
    fun isInternational(raw: String?): Boolean {
        val d = digits(raw)
        if (d.length <= 6) return false
        if (d.startsWith("00")) return true
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.startsWith("+")) return !d.startsWith("880")
        if (d.startsWith("880")) return false
        return d.length >= 12
    }

    /**
     * Wildcard match (feature 20): the pattern is digits plus "*" wildcards,
     * matched against the local form of the number. "*" swallows any run of
     * digits (including none). Returns the specificity (number of literal
     * digits) when it matches, or -1 when it does not, so the most specific
     * matching rule can win.
     */
    fun wildcardSpecificity(number: String, pattern: String): Int {
        val n = localForm(number)
        val pat = pattern.filter { it.isDigit() || it == '*' }
        if (pat.isEmpty()) return -1
        if (!pat.contains('*')) {
            return if (n.startsWith(pat)) pat.length else -1
        }
        val head = pat.substringBefore('*')
        val tail = pat.substringAfterLast('*')
        if (!n.startsWith(head)) return -1
        if (tail.isNotEmpty() && !n.endsWith(tail)) return -1
        if (n.length < head.length + tail.length) return -1
        return pat.count { it.isDigit() }
    }
}
