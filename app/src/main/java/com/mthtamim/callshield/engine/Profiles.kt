package com.mthtamim.callshield.engine

/**
 * Feature 14: named profiles. Each profile carries its own block mode and
 * hidden-number rule. NORMAL is special: it is backed by the original
 * Part 1 settings keys, so a user who never opens the Profiles screen sees
 * exactly the Part 1 behaviour, and the Part 1 Settings screen keeps
 * editing the same values (they are the Normal profile values).
 */
enum class Profile(
    val defaultMode: BlockMode,
    val defaultHidden: HiddenRule
) {
    NORMAL(BlockMode.CONTACTS, HiddenRule.BLOCK),
    STRICT(BlockMode.FAVORITES, HiddenRule.BLOCK),
    FOCUS(BlockMode.CONTACTS, HiddenRule.BLOCK),
    NIGHT(BlockMode.FAVORITES, HiddenRule.BLOCK),
    OFF(BlockMode.CONTACTS, HiddenRule.ALLOW);

    companion object {
        /** Cycle order used by the QS tile and the widget. */
        val CYCLE: List<Profile> = listOf(NORMAL, STRICT, FOCUS, NIGHT, OFF)

        fun parse(name: String?): Profile =
            values().firstOrNull { it.name == name } ?: NORMAL

        fun next(p: Profile): Profile {
            val i = CYCLE.indexOf(p)
            return CYCLE[(i + 1) % CYCLE.size]
        }
    }
}

/** A profile schedule row (feature 15). daysMask: bit 0 = Monday .. bit 6 = Sunday. */
data class ScheduleEntry(
    val id: Long,
    val profile: Profile,
    val startMin: Int,
    val endMin: Int,
    val daysMask: Int,
    val enabled: Boolean
)

/**
 * Pure time-window maths shared by schedules (feature 15) and per-number
 * time windows (feature 24). Windows may run past midnight (end < start);
 * after midnight the window still belongs to the day it started on.
 */
object WindowMath {

    /** Calendar.DAY_OF_WEEK (Sunday=1..Saturday=7) to mask index (Monday=0..Sunday=6). */
    fun dayIndex(calendarDayOfWeek: Int): Int = (calendarDayOfWeek + 5) % 7

    fun dayBit(dayIndex: Int): Int = 1 shl dayIndex

    fun inWindow(
        minuteOfDay: Int,
        todayIndex: Int,
        startMin: Int,
        endMin: Int,
        daysMask: Int
    ): Boolean {
        if (daysMask == 0) return false
        if (startMin == endMin) return false
        return if (startMin < endMin) {
            minuteOfDay >= startMin && minuteOfDay < endMin &&
                (daysMask and dayBit(todayIndex)) != 0
        } else {
            // Overnight window: the part before midnight belongs to today,
            // the part after midnight belongs to yesterday.
            if (minuteOfDay >= startMin) {
                (daysMask and dayBit(todayIndex)) != 0
            } else if (minuteOfDay < endMin) {
                val yesterday = (todayIndex + 6) % 7
                (daysMask and dayBit(yesterday)) != 0
            } else {
                false
            }
        }
    }
}
