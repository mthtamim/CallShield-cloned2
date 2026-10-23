package com.mthtamim.callshield.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mthtamim.callshield.R
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.engine.Profile
import com.mthtamim.callshield.tile.TileUpdater
import com.mthtamim.callshield.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Feature 18: home-screen widget showing the effective profile and the
 * Temporary Allow state, with two buttons: cycle the profile, and start or
 * cancel a 30-minute Temporary Allow window.
 */
class CallShieldWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    companion object {
        fun buildViews(context: Context): RemoteViews {
            val store = Store(context)
            val now = System.currentTimeMillis()
            val effective = store.effectiveProfile(now)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setTextViewText(
                R.id.widgetProfile,
                context.getString(R.string.widget_profile, profileLabel(context, effective))
            )
            views.setTextViewText(
                R.id.widgetTemp,
                if (store.tempAllowActive(now)) {
                    val until = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(store.tempAllowUntil()))
                    context.getString(R.string.temp_active_until, until)
                } else {
                    context.getString(R.string.temp_inactive)
                }
            )
            // Feature 56 (v1.1): blocked-today count and the most recent
            // blocked number, from the internal log.
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val blockedToday = store.blockedTodayCount(cal.timeInMillis)
            val lastBlocked = store.logEntries(100)
                .firstOrNull { !it.allowed && it.time >= cal.timeInMillis }?.rawNumber
            views.setTextViewText(
                R.id.widgetBlocked,
                if (lastBlocked != null) {
                    context.getString(R.string.widget_blocked_last, blockedToday, lastBlocked)
                } else {
                    context.getString(R.string.widget_blocked_today, blockedToday)
                }
            )
            views.setOnClickPendingIntent(
                R.id.widgetBtnProfile,
                actionIntent(context, WidgetActionReceiver.ACTION_CYCLE, 1)
            )
            views.setOnClickPendingIntent(
                R.id.widgetBtnTemp,
                actionIntent(context, WidgetActionReceiver.ACTION_TEMP, 2)
            )
            val open = Intent(context, MainActivity::class.java)
            open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context, 3, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }

        private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java)
            intent.action = action
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun profileLabel(context: Context, profile: Profile): String = when (profile) {
            Profile.NORMAL -> context.getString(R.string.profile_normal)
            Profile.STRICT -> context.getString(R.string.profile_strict)
            Profile.FOCUS -> context.getString(R.string.profile_focus)
            Profile.NIGHT -> context.getString(R.string.profile_night)
            Profile.OFF -> context.getString(R.string.profile_off)
        }
    }
}

/** Handles the two widget buttons. */
class WidgetActionReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val store = Store(context)
        when (intent?.action) {
            ACTION_CYCLE -> store.setActiveProfile(Profile.next(store.activeProfile()))
            ACTION_TEMP -> {
                val now = System.currentTimeMillis()
                if (store.tempAllowActive(now)) {
                    store.cancelTempAllow()
                } else {
                    store.startTempAllow(30, now)
                }
            }
            else -> return
        }
        WidgetUpdater.updateAll(context)
        TileUpdater.requestRefresh(context)
    }

    companion object {
        const val ACTION_CYCLE = "com.mthtamim.callshield.WIDGET_CYCLE"
        const val ACTION_TEMP = "com.mthtamim.callshield.WIDGET_TEMP"
    }
}

/** Pushes fresh content to every placed widget instance. */
object WidgetUpdater {

    fun updateAll(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CallShieldWidget::class.java)
            )
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, CallShieldWidget.buildViews(context))
        } catch (e: Exception) {
            // No widget placed, or the launcher refused: the widget also
            // refreshes itself on the system update period.
        }
    }
}
