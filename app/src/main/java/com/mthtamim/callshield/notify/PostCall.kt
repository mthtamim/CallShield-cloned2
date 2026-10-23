package com.mthtamim.callshield.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mthtamim.callshield.contacts.CallOutcome
import com.mthtamim.callshield.contacts.ContactsHelper
import com.mthtamim.callshield.data.Store

/**
 * Features 51 and 55 (v1.1): what happens after an allowed unknown call.
 *
 * Why polling: the screening service hands over the moment screening ends —
 * Android gives it no "the call just ended" callback, and a
 * TelephonyCallback / PhoneStateListener would need a component that stays
 * registered for the whole call (a foreground service the app deliberately
 * does not run). So the screening service leaves a marker
 * (Store.setPendingPostCall) and this scheduler polls the call log, which
 * the app may already read for the callback-window feature:
 *
 *  - first check ~90 s after screening, then again every ~90 s;
 *  - the call log says ANSWERED / MISSED / REJECTED for the number;
 *  - without READ_CALL_LOG the outcome is unknowable, so after a 10-minute
 *    timed fallback the call is treated as ended-unanswered;
 *  - a still-ringing call simply re-arms the next check.
 *
 * Feature 51: a missed/rejected unknown call earns one quick-decision
 * notification per number per day (Whitelist / Block / Dismiss).
 * Feature 55: when the allow came from the repeat-caller bypass and the
 * call was missed, an "urgent" reminder is scheduled ~15 minutes later
 * (once per number per day).
 */
object PostCallScheduler {

    const val ACTION_CHECK = "com.mthtamim.callshield.POSTCALL_CHECK"
    const val ACTION_URGENT = "com.mthtamim.callshield.POSTCALL_URGENT"

    const val EXTRA_RAW = "postcall_extra_raw"

    private const val REQ_CHECK = 9201
    private const val REQ_URGENT = 9202

    const val CHECK_DELAY_MS = 90_000L
    /** After this long with no readable outcome, assume the call ended. */
    const val FALLBACK_MS = 10 * 60_000L
    const val URGENT_DELAY_MS = 15 * 60_000L

    fun scheduleCheck(context: Context, at: Long) {
        val intent = Intent(context, PostCallReceiver::class.java)
        intent.action = ACTION_CHECK
        schedule(context, REQ_CHECK, intent, at)
    }

    fun scheduleUrgent(context: Context, rawNumber: String, at: Long) {
        val intent = Intent(context, PostCallReceiver::class.java)
        intent.action = ACTION_URGENT
        intent.putExtra(EXTRA_RAW, rawNumber)
        schedule(context, REQ_URGENT, intent, at)
    }

    fun cancelCheck(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PostCallReceiver::class.java)
        intent.action = ACTION_CHECK
        val pi = PendingIntent.getBroadcast(
            context, REQ_CHECK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun schedule(context: Context, requestCode: Int, intent: Intent, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e2: SecurityException) {
                // No alarm grant at all: the feature quietly does nothing.
            }
        }
    }
}

class PostCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            PostCallScheduler.ACTION_CHECK -> onCheck(context)
            PostCallScheduler.ACTION_URGENT -> {
                val raw = intent.getStringExtra(PostCallScheduler.EXTRA_RAW) ?: return
                BlockActions.notifyUrgentReminder(context, raw)
            }
        }
    }

    private fun onCheck(context: Context) {
        val store = Store(context)
        val marker = store.pendingPostCall() ?: return
        val (numKey, raw, repeatBypass) = marker
        val now = System.currentTimeMillis()
        val screenedAt = store.pendingPostCallAt()
        val outcome = ContactsHelper.lastCallOutcome(context, raw, screenedAt - 60_000L)
        when (outcome) {
            CallOutcome.ANSWERED -> store.clearPendingPostCall()
            CallOutcome.MISSED -> finish(context, store, numKey, raw, repeatBypass, urgent = true, now)
            CallOutcome.REJECTED -> finish(context, store, numKey, raw, repeatBypass, urgent = false, now)
            null -> {
                if (now - screenedAt >= PostCallScheduler.FALLBACK_MS) {
                    // Timed fallback: the call log cannot be read (no
                    // permission), so treat the call as ended unanswered.
                    finish(context, store, numKey, raw, repeatBypass, urgent = false, now)
                } else {
                    // Still ringing, or the log has not caught up yet.
                    PostCallScheduler.scheduleCheck(context, now + PostCallScheduler.CHECK_DELAY_MS)
                }
            }
        }
    }

    private fun finish(
        context: Context,
        store: Store,
        numKey: String,
        raw: String,
        repeatBypass: Boolean,
        urgent: Boolean,
        now: Long
    ) {
        store.clearPendingPostCall()
        // Feature 51: one quick-decision prompt per number per day.
        if (store.claimPostCallShown(numKey, now)) {
            BlockActions.notifyPostCall(context, raw)
        }
        // Feature 55: a missed repeat-bypass call may be genuinely urgent.
        if (urgent && repeatBypass && store.claimUrgentReminder(numKey, now)) {
            PostCallScheduler.scheduleUrgent(
                context, raw, now + PostCallScheduler.URGENT_DELAY_MS
            )
        }
    }
}
