package com.mthtamim.callshield.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.engine.WindowMath
import java.util.Calendar

/**
 * Keeps two alarms fresh (features 15 and 31):
 *  - the next profile-schedule boundary, so the QS tile, the widget and a
 *    "profile changed" state stay honest;
 *  - the next daily summary time.
 *
 * Honest precision note: call *decisions* never depend on these alarms —
 * the screening service evaluates schedules live on every call. The alarms
 * only refresh surfaces and deliver the summary. When the exact-alarm
 * permission is missing (Android 12+), a regular while-idle alarm is used
 * instead and delivery can drift by a few minutes; the Permissions screen
 * says so and offers the grant.
 */
object ProfileScheduler {

    const val ACTION_TICK = "com.mthtamim.callshield.SCHEDULE_TICK"
    const val ACTION_SUMMARY = "com.mthtamim.callshield.SUMMARY"

    private const val REQ_TICK = 9101
    private const val REQ_SUMMARY = 9102

    fun scheduleAll(context: Context) {
        val store = Store(context)
        val now = System.currentTimeMillis()
        scheduleOne(context, REQ_TICK, ACTION_TICK, nextBoundary(store, now))
        val summaryAt = if (store.summaryEnabled()) nextSummary(store, now) else null
        scheduleOne(context, REQ_SUMMARY, ACTION_SUMMARY, summaryAt)
    }

    private fun scheduleOne(context: Context, requestCode: Int, action: String, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ScheduleReceiver::class.java)
        intent.action = action
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) {
            am.cancel(pi)
            return
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // Exact alarms were revoked between the check and the call:
            // fall back to an inexact alarm rather than losing the tick.
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e2: SecurityException) {
                // Nothing more to do; live evaluation still decides calls.
            }
        }
    }

    /** The next instant any enabled schedule starts or ends, or null. */
    fun nextBoundary(store: Store, now: Long): Long? {
        var best: Long? = null
        for (row in store.schedules()) {
            if (!row.enabled || row.daysMask == 0) continue
            var dayOffset = 0
            while (dayOffset < 9) {
                val cal = Calendar.getInstance()
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_YEAR, dayOffset)
                val idx = WindowMath.dayIndex(cal.get(Calendar.DAY_OF_WEEK))
                if ((row.daysMask and WindowMath.dayBit(idx)) != 0) {
                    for (minOfDay in intArrayOf(row.startMin, row.endMin)) {
                        val t = atMinute(cal, minOfDay)
                        // An end time after midnight belongs to the next day.
                        val candidate =
                            if (minOfDay == row.endMin && row.endMin < row.startMin) {
                                t + 86_400_000L
                            } else {
                                t
                            }
                        if (candidate > now && (best == null || candidate < best!!)) {
                            best = candidate
                        }
                    }
                }
                dayOffset++
            }
        }
        return best
    }

    /** Today at the summary hour if still ahead, otherwise tomorrow. */
    fun nextSummary(store: Store, now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, store.summaryHour())
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun atMinute(day: Calendar, minuteOfDay: Int): Long {
        val cal = day.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        cal.set(Calendar.MINUTE, minuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
