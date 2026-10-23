package com.mthtamim.callshield.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.mthtamim.callshield.ui.Perms

data class CalendarInfo(val id: Long, val name: String)

/**
 * Feature 54 (v1.1): calendar-based Focus. Reads only the device's own
 * calendars through the system provider — nothing leaves the phone, and
 * everything here is a no-op until the user enables the feature and grants
 * READ_CALENDAR (requested lazily, only at that moment).
 */
object CalendarHelper {

    /** The user's visible calendars, for the picker. Empty without permission. */
    fun calendars(context: Context): List<CalendarInfo> {
        if (!Perms.hasCalendar(context)) return emptyList()
        val out = ArrayList<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                ),
                CalendarContract.Calendars.VISIBLE + "=1",
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out.add(CalendarInfo(c.getLong(0), name))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    /**
     * True when an event on [calendarId] is in progress at [now]. Uses the
     * Instances table so recurring events count too. False without the
     * permission, without a chosen calendar, or on any provider error —
     * the feature simply stays inactive.
     */
    fun hasOngoingEvent(context: Context, calendarId: Long, now: Long): Boolean {
        if (calendarId < 0L || !Perms.hasCalendar(context)) return false
        // A small look-back catches an event that started just before now;
        // the window end is now itself, so only ongoing events match.
        val begin = now - 60_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, begin)
            ContentUris.appendId(this, now)
        }.build()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.EVENT_ID),
                CalendarContract.Instances.CALENDAR_ID + "=?",
                arrayOf(calendarId.toString()),
                null
            )?.use { c ->
                return c.moveToFirst()
            }
        } catch (e: SecurityException) {
            return false
        }
        return false
    }
}
