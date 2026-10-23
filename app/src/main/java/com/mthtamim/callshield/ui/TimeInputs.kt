package com.mthtamim.callshield.ui

/** Parsing and formatting for "HH:MM" time inputs used by schedules and time windows. */
object TimeInputs {

    /** "23:30" -> 1410. Returns null when the text is not a valid time. */
    fun parse(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 1410 -> "23:30". */
    fun format(minutes: Int): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return "%02d:%02d".format(h, m)
    }
}
