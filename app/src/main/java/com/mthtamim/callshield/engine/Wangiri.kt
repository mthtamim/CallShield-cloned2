package com.mthtamim.callshield.engine

/**
 * Feature 53 (v1.1): one-ring ("wangiri") scam detection, from data the app
 * genuinely has — no external database.
 *
 * Honest basis: Android does not give a screening app a reliable "ring
 * duration" for calls it did not answer, and this app never sees answered
 * calls at all. What the app *does* know is in its own internal log: how
 * often a number tried, and how close together the attempts were. A
 * wangiri caller rings once and hangs up, hoping for a paid callback —
 * the observable pattern is repeated attempts at short intervals that
 * never turn into a real conversation. So the detector requires:
 *
 *  - at least [MIN_ATTEMPTS] attempts from the number in the last 24 h in
 *    the internal log, and
 *  - at least one pair of attempts less than [SHORT_GAP_MS] apart
 *    (burst re-dials are the hang-up-and-retry rhythm), and
 *  - no evidence of a real relationship: no answered incoming call of
 *    5+ seconds and no outgoing call to the number in the call log
 *    (checked only when the call-log permission is present; without it
 *    this guard is skipped and the log pattern alone decides — that is
 *    stated in the manual).
 *
 * Pure thresholds, like [Heuristics]: the caller gathers the inputs.
 */
object Wangiri {

    const val MIN_ATTEMPTS = 3
    const val SHORT_GAP_MS = 10 * 60_000L
    const val WINDOW_MS = 24 * 3_600_000L

    /**
     * @param attemptTimes the number's attempt timestamps (ms) inside the
     * window, any order
     * @param hasRealContact true when the call log shows an answered call
     * of 5+ seconds or an outgoing call to this number; such a number is
     * never a wangiri suspect
     */
    fun isSuspect(attemptTimes: List<Long>, hasRealContact: Boolean): Boolean {
        if (hasRealContact) return false
        if (attemptTimes.size < MIN_ATTEMPTS) return false
        val sorted = attemptTimes.sorted()
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] < SHORT_GAP_MS) return true
        }
        return false
    }
}
