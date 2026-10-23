package com.mthtamim.callshield.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.tile.TileUpdater
import com.mthtamim.callshield.widget.WidgetUpdater
import java.util.Calendar

/**
 * Fires at profile-schedule boundaries and at the daily summary time
 * (features 15 and 31). Decisions themselves are always computed live by
 * the screening service; this receiver only refreshes the tile/widget and
 * posts the summary, then re-arms the alarms.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ProfileScheduler.ACTION_SUMMARY -> postSummary(context)
        }
        // Both actions refresh the surfaces and re-arm.
        TileUpdater.requestRefresh(context)
        WidgetUpdater.updateAll(context)
        ProfileScheduler.scheduleAll(context)
    }

    private fun postSummary(context: Context) {
        val store = Store(context)
        if (!store.summaryEnabled()) return
        val now = System.currentTimeMillis()
        val dayStart = startOfToday(now)
        val blockedToday = store.blockedTodayCount(dayStart)
        val top = store.topBlocked(1, dayStart).firstOrNull()

        // Weekly roll-up rides along on the Sunday summary.
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        var blockedWeek: Int? = null
        var weeklyTop: String? = null
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            val weekStart = dayStart - 6 * 86_400_000L
            blockedWeek = store.blockedTodayCount(weekStart)
            weeklyTop = store.topBlocked(1, weekStart).firstOrNull()?.rawNumber
        }

        // Feature 58 (v1.1): blocks that silent mode kept quiet are folded
        // into this summary — they are counted separately because they
        // were deliberately never written as individual notifications.
        val (silentCount, silentLast) = store.takeSilentSummary()

        BlockActions.notifySummary(
            context,
            blockedToday + silentCount,
            top?.rawNumber ?: silentLast,
            top?.count ?: silentCount,
            blockedWeek,
            weeklyTop
        )
    }

    private fun startOfToday(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
