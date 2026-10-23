package com.mthtamim.callshield.engine

/**
 * Feature 34: behaviour heuristics without any database. A caller who keeps
 * hammering the line earns a warning even when a rule let the call through
 * (for example during a Temporary Allow window). Pure thresholds on counts
 * the caller of this object reads from the internal log.
 */
object Heuristics {

    /** Attempts inside the rapid window that count as hammering. */
    const val RAPID_THRESHOLD = 5
    const val RAPID_WINDOW_MS = 10 * 60_000L

    /** Attempts inside a day that count as persistent. */
    const val PERSISTENT_THRESHOLD = 10
    const val DAY_MS = 24 * 3_600_000L

    /** True when the recent behaviour of this number deserves a warning. */
    fun isSuspicious(attemptsInRapidWindow: Int, attemptsToday: Int): Boolean {
        return attemptsInRapidWindow >= RAPID_THRESHOLD ||
            attemptsToday >= PERSISTENT_THRESHOLD
    }
}
