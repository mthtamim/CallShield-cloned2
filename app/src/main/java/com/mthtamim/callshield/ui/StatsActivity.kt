package com.mthtamim.callshield.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mthtamim.callshield.R
import com.mthtamim.callshield.notify.ProfileScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Features 31, 32 and 35: the daily/weekly summary settings, the
 * statistics dashboard (totals, 7-day trend, top blocked numbers), and
 * silence tracking — blocked numbers that have stopped trying.
 */
class StatsActivity : BaseActivity() {

    private lateinit var textTotals: TextView
    private lateinit var textTrend: TextView
    private lateinit var topRows: LinearLayout
    private lateinit var quietRows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        textTotals = findViewById(R.id.textTotals)
        textTrend = findViewById(R.id.textTrend)
        topRows = findViewById(R.id.topRows)
        quietRows = findViewById(R.id.quietRows)

        val switchSummary = findViewById<Switch>(R.id.switchSummary)
        val editHour = findViewById<EditText>(R.id.editSummaryHour)
        switchSummary.isChecked = store().summaryEnabled()
        editHour.setText(store().summaryHour().toString())
        switchSummary.setOnCheckedChangeListener { _, checked ->
            store().setSummaryEnabled(checked)
            ProfileScheduler.scheduleAll(this)
        }
        findViewById<Button>(R.id.btnSaveSummary).setOnClickListener {
            val hour = editHour.text.toString().trim().toIntOrNull()
            if (hour == null || hour !in 0..23) {
                Toast.makeText(this, R.string.summary_hour_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store().setSummaryHour(hour)
            ProfileScheduler.scheduleAll(this)
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val store = store()
        val now = System.currentTimeMillis()
        val dayStart = startOfToday(now)
        val weekStart = dayStart - 6 * 86_400_000L
        val monthStart = dayStart - 29 * 86_400_000L

        var blockedToday = 0
        var allowedToday = 0
        var blockedWeek = 0
        var blockedMonth = 0
        for (day in store.dailyCounts(30, now)) {
            if (day.dayStart >= dayStart) {
                blockedToday += day.blocked
                allowedToday += day.allowed
            }
            if (day.dayStart >= weekStart) blockedWeek += day.blocked
            if (day.dayStart >= monthStart) blockedMonth += day.blocked
        }
        textTotals.text = getString(
            R.string.stats_totals, blockedToday, allowedToday, blockedWeek, blockedMonth
        )

        // 7-day trend as a simple text bar (feature 32).
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val trend = StringBuilder()
        val days = store.dailyCounts(7, now)
        val maxBlocked = days.maxOfOrNull { it.blocked } ?: 0
        for (day in days) {
            val bars = if (maxBlocked == 0) {
                ""
            } else {
                "█".repeat((day.blocked * 12 / maxBlocked).coerceAtLeast(if (day.blocked > 0) 1 else 0))
            }
            trend.append(dayFmt.format(Date(day.dayStart)))
            trend.append("  ")
            trend.append(day.blocked)
            if (bars.isNotEmpty()) {
                trend.append("  ")
                trend.append(bars)
            }
            trend.append("\n")
        }
        textTrend.text = trend.toString().trimEnd()

        // Top blocked numbers (feature 32).
        topRows.removeAllViews()
        val top = store.topBlocked(5, monthStart)
        if (top.isEmpty()) {
            topRows.addView(captionRow(getString(R.string.empty_list)))
        } else {
            val timeFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            for (row in top) {
                topRows.addView(
                    bodyRow(
                        getString(
                            R.string.top_row,
                            row.rawNumber ?: row.numKey,
                            row.count,
                            timeFmt.format(Date(row.lastTime))
                        )
                    )
                )
            }
        }

        // Silence tracking (feature 35): no attempt at all in the last 7 days.
        quietRows.removeAllViews()
        val quiet = store.quietNumbers(5, now - 7 * 86_400_000L, now - 90 * 86_400_000L)
        if (quiet.isEmpty()) {
            quietRows.addView(captionRow(getString(R.string.quiet_none)))
        } else {
            val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
            for (row in quiet) {
                quietRows.addView(
                    bodyRow(
                        getString(
                            R.string.quiet_row,
                            row.rawNumber ?: row.numKey,
                            row.count,
                            dateFmt.format(Date(row.lastTime))
                        )
                    )
                )
            }
        }
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

    private fun bodyRow(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setPadding(0, 10, 0, 10)
        return tv
    }

    private fun captionRow(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setPadding(0, 6, 0, 6)
        return tv
    }
}
