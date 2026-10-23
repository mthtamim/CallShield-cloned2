package com.mthtamim.callshield.engine

/** Why an SMS notification was (or would be) muted (features 25-28). */
enum class SmsMuteReason { NOT_MUTED, UNKNOWN_SENDER, KEYWORD }

/**
 * Pure decision logic for SMS notification muting (features 25-28).
 *
 * Feature 26 is the hard rule that sits above everything else: OTP and
 * transactional messages are NEVER muted, no matter what the other rules
 * say. Android cannot truly block an SMS anyway; the app only silences the
 * notification of the default SMS app and keeps a record in the silent
 * inbox, so nothing is lost.
 */
object SmsFilter {

    /**
     * Marker words that make a message transactional / OTP-like. Matching is
     * case-insensitive substring matching on sender id + body.
     */
    private val TRANSACTIONAL_MARKERS = listOf(
        "otp", "one-time", "one time", "verification code", "verify code",
        "security code", "auth code", "passcode", "2fa",
        "transaction", "debited", "credited", "payment", "paid", "balance",
        "bkash", "nagad", "rocket", "upay", "taka", "bdt",
        "ওটিপি", "কোড", "যাচাই", "লেনদেন", "টাকা"
    )

    fun looksTransactional(senderId: String?, body: String?): Boolean {
        val text = ((senderId ?: "") + " " + (body ?: "")).lowercase()
        for (marker in TRANSACTIONAL_MARKERS) {
            if (text.contains(marker)) return true
        }
        return false
    }

    /**
     * @param muteUnknown whether feature 25 (mute non-contact senders) is on
     * @param senderInContacts sender resolved against the phone Contacts
     * @param keywords the user's keyword mute rules (feature 27)
     */
    fun decide(
        muteUnknown: Boolean,
        senderInContacts: Boolean,
        senderId: String?,
        body: String?,
        keywords: List<String>
    ): SmsMuteReason {
        // Contacts always get through with sound.
        if (senderInContacts) return SmsMuteReason.NOT_MUTED
        // Feature 26: OTP / transactional is never muted.
        if (looksTransactional(senderId, body)) return SmsMuteReason.NOT_MUTED
        // Feature 27: keyword rules.
        val text = ((senderId ?: "") + " " + (body ?: "")).lowercase()
        for (kw in keywords) {
            val k = kw.trim().lowercase()
            if (k.isNotEmpty() && text.contains(k)) return SmsMuteReason.KEYWORD
        }
        // Feature 25: unknown senders are muted when the master SMS rule is on.
        if (muteUnknown) return SmsMuteReason.UNKNOWN_SENDER
        return SmsMuteReason.NOT_MUTED
    }
}
