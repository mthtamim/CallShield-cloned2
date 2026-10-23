package com.mthtamim.callshield.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mthtamim.callshield.R
import com.mthtamim.callshield.engine.Reason
import com.mthtamim.callshield.ui.MainActivity

/** Posts the notifications the blocking flow needs (features 3, 12 and 13). */
object BlockActions {

    const val CHANNEL_ID = "blocked_calls"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.notif_channel_desc)
            nm.createNotificationChannel(channel)
        }
    }

    fun reasonLabelRes(reason: Reason): Int = when (reason) {
        Reason.DISABLED -> R.string.reason_disabled
        Reason.HIDDEN_BLOCKED -> R.string.reason_hidden_blocked
        Reason.HIDDEN_ALLOWED -> R.string.reason_hidden_allowed
        Reason.HIDDEN_WARN -> R.string.reason_hidden_warn
        Reason.BLACKLIST -> R.string.reason_blacklist
        Reason.WHITELIST -> R.string.reason_whitelist
        Reason.PREFIX_BLOCK -> R.string.reason_prefix_block
        Reason.PREFIX_ALLOW -> R.string.reason_prefix_allow
        Reason.CONTACT -> R.string.reason_contact
        Reason.FAVORITE -> R.string.reason_favorite
        Reason.EXPECTED_CALL -> R.string.reason_expected
        Reason.TEMP_ALLOW -> R.string.reason_temp_allow
        Reason.CALLBACK -> R.string.reason_callback
        Reason.REPEAT_CALLER -> R.string.reason_repeat
        Reason.ALLOW_NEXT -> R.string.reason_allow_next
        Reason.NOT_IN_CONTACTS -> R.string.reason_not_in_contacts
        Reason.NOT_FAVORITE -> R.string.reason_not_favorite
        Reason.EMERGENCY -> R.string.reason_emergency
        Reason.HOTLINE -> R.string.reason_hotline
        Reason.WILDCARD_BLOCK -> R.string.reason_wildcard_block
        Reason.WILDCARD_ALLOW -> R.string.reason_wildcard_allow
        Reason.INTERNATIONAL_BLOCK -> R.string.reason_international
        Reason.GROUP_ALLOW -> R.string.reason_group
        Reason.TIME_WINDOW_ALLOW -> R.string.reason_window_allow
        Reason.TIME_WINDOW_BLOCK -> R.string.reason_window_block
        Reason.PROFILE_OFF -> R.string.reason_profile_off
        Reason.SMS_CORRESPONDENT -> R.string.reason_sms_correspondent
        Reason.WANGIRI -> R.string.reason_wangiri
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Feature 12: after a call is blocked, the user can whitelist the number
     * (or confirm the block) straight from the notification.
     */
    fun notifyBlocked(
        context: Context,
        rawNumber: String?,
        displayName: String?,
        reason: Reason,
        // Feature 48 (v1.1): a silenced call gets its own title.
        titleRes: Int = R.string.notif_blocked_title
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val who = displayName ?: rawNumber ?: context.getString(R.string.hidden_number_label)
        val notifId = (rawNumber ?: "hidden").hashCode()

        val whitelistIntent = Intent(context, BlockActionReceiver::class.java)
        whitelistIntent.action = BlockActionReceiver.ACTION_WHITELIST
        whitelistIntent.putExtra(BlockActionReceiver.EXTRA_RAW, rawNumber)
        whitelistIntent.putExtra(BlockActionReceiver.EXTRA_NOTIF_ID, notifId)
        val whitelistPi = PendingIntent.getBroadcast(
            context, notifId, whitelistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val keepIntent = Intent(context, BlockActionReceiver::class.java)
        keepIntent.action = BlockActionReceiver.ACTION_KEEP
        keepIntent.putExtra(BlockActionReceiver.EXTRA_NOTIF_ID, notifId)
        val keepPi = PendingIntent.getBroadcast(
            context, notifId + 1, keepIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(titleRes, who))
            .setContentText(context.getString(reasonLabelRes(reason)))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
        if (rawNumber != null) {
            builder.addAction(
                android.R.drawable.ic_input_add,
                context.getString(R.string.notif_whitelist_action),
                whitelistPi
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.notif_keep_action),
            keepPi
        )
        try {
            nm.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the block still happened and is
            // in the log; only the heads-up is missing.
        }
    }

    /**
     * Feature 13: one summary when the same blocked number keeps retrying, so
     * a genuinely urgent caller is not silently lost.
     */
    fun notifyEscalation(context: Context, rawNumber: String?, attemptsLastHour: Int) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val who = rawNumber ?: context.getString(R.string.hidden_number_label)
        val notifId = ("esc_" + (rawNumber ?: "hidden")).hashCode()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_esc_title, who))
            .setContentText(context.getString(R.string.notif_esc_body, attemptsLastHour))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            // See notifyBlocked: the log still holds the full record.
        }
    }

    /** Feature 3, WARN choice: the call rings, but the user is told why to be careful. */
    fun notifyWarning(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_warn_title))
            .setContentText(context.getString(R.string.notif_warn_body))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_WARN_ID, notification)
        } catch (e: SecurityException) {
            // The call itself was allowed; only the warning is missing.
        }
    }

    /**
     * Feature 34: a caller whose behaviour looks like hammering gets a
     * warning even when a rule let this particular call through.
     */
    fun notifyHeuristic(context: Context, rawNumber: String?, attemptsToday: Int) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val who = rawNumber ?: context.getString(R.string.hidden_number_label)
        val notifId = ("heur_" + (rawNumber ?: "hidden")).hashCode()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_heur_title, who))
            .setContentText(context.getString(R.string.notif_heur_body, attemptsToday))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            // The call itself was allowed; only the warning is missing.
        }
    }

    /** Feature 31: the daily (and on Sundays weekly) blocked-call summary. */
    fun notifySummary(
        context: Context,
        blockedToday: Int,
        topNumber: String?,
        topCount: Int,
        blockedWeek: Int?,
        weeklyTop: String?
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val body = StringBuilder()
        body.append(context.getString(R.string.notif_summary_body, blockedToday))
        if (topNumber != null && topCount > 0) {
            body.append(" ")
            body.append(context.getString(R.string.notif_summary_top, topNumber, topCount))
        }
        if (blockedWeek != null) {
            body.append(" ")
            body.append(context.getString(R.string.notif_summary_week, blockedWeek))
            if (weeklyTop != null) {
                body.append(" ")
                body.append(context.getString(R.string.notif_summary_week_top, weeklyTop))
            }
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_summary_title))
            .setContentText(body.toString())
            .setStyle(Notification.BigTextStyle().bigText(body.toString()))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_SUMMARY_ID, notification)
        } catch (e: SecurityException) {
            // Summary is informational only; the stats screen has the same data.
        }
    }

    /**
     * Feature 48 (v1.1), WARN_ONLY: the call was let through on purpose —
     * this says a rule would have blocked it, and why.
     */
    fun notifyWarnOnly(context: Context, rawNumber: String?, reason: Reason) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val who = rawNumber ?: context.getString(R.string.hidden_number_label)
        val notifId = ("warnonly_" + (rawNumber ?: "hidden")).hashCode()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_warnonly_title, who))
            .setContentText(context.getString(reasonLabelRes(reason)))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            // The call itself was allowed; only the note is missing.
        }
    }

    /** Feature 53 (v1.1): distinct warning for a one-ring (wangiri) suspect. */
    fun notifyWangiri(context: Context, rawNumber: String?) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val who = rawNumber ?: context.getString(R.string.hidden_number_label)
        val notifId = ("wangiri_" + (rawNumber ?: "hidden")).hashCode()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_wangiri_title, who))
            .setContentText(context.getString(R.string.notif_wangiri_body))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            // Warning only; nothing else depends on it.
        }
    }

    /**
     * Feature 51 (v1.1): after an allowed unknown call ends unanswered, one
     * quick decision — whitelist it, block it, or dismiss.
     */
    fun notifyPostCall(context: Context, rawNumber: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notifId = ("postcall_" + rawNumber).hashCode()

        val whitelistIntent = Intent(context, BlockActionReceiver::class.java)
        whitelistIntent.action = BlockActionReceiver.ACTION_WHITELIST
        whitelistIntent.putExtra(BlockActionReceiver.EXTRA_RAW, rawNumber)
        whitelistIntent.putExtra(BlockActionReceiver.EXTRA_NOTIF_ID, notifId)
        val whitelistPi = PendingIntent.getBroadcast(
            context, notifId, whitelistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val blockIntent = Intent(context, BlockActionReceiver::class.java)
        blockIntent.action = BlockActionReceiver.ACTION_BLOCK
        blockIntent.putExtra(BlockActionReceiver.EXTRA_RAW, rawNumber)
        blockIntent.putExtra(BlockActionReceiver.EXTRA_NOTIF_ID, notifId)
        val blockPi = PendingIntent.getBroadcast(
            context, notifId + 2, blockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, BlockActionReceiver::class.java)
        dismissIntent.action = BlockActionReceiver.ACTION_KEEP
        dismissIntent.putExtra(BlockActionReceiver.EXTRA_NOTIF_ID, notifId)
        val dismissPi = PendingIntent.getBroadcast(
            context, notifId + 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_postcall_title, rawNumber))
            .setContentText(context.getString(R.string.notif_postcall_body))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_input_add,
                context.getString(R.string.notif_whitelist_action),
                whitelistPi
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notif_block_action),
                blockPi
            )
            .addAction(
                android.R.drawable.ic_menu_revert,
                context.getString(R.string.notif_dismiss_action),
                dismissPi
            )
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            // The decision can still be made from the log screen.
        }
    }

    /**
     * Feature 55 (v1.1): a repeat-caller who got through the bypass and was
     * missed may be urgent — one reminder, about 15 minutes later.
     */
    fun notifyUrgentReminder(context: Context, rawNumber: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notifId = ("urgent_" + rawNumber).hashCode()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_urgent_title, rawNumber))
            .setContentText(context.getString(R.string.notif_urgent_body))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            // Reminder only; the log still holds the call.
        }
    }

    private const val NOTIF_WARN_ID = 7712
    private const val NOTIF_SUMMARY_ID = 7713
}