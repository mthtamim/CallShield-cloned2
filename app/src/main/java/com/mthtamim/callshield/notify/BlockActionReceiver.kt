package com.mthtamim.callshield.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mthtamim.callshield.R
import com.mthtamim.callshield.data.DbHelper
import com.mthtamim.callshield.data.Store

/**
 * Handles the actions on the blocked-call notification (feature 12) and on
 * the post-call quick-decision notification (feature 51, v1.1).
 */
class BlockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val nm = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            ACTION_WHITELIST -> {
                val raw = intent.getStringExtra(EXTRA_RAW)
                if (raw != null) {
                    Store(context).addToList(raw, null, DbHelper.TYPE_WHITELIST, "notification")
                    Toast.makeText(context, R.string.added_white, Toast.LENGTH_SHORT).show()
                }
                nm?.cancel(notifId)
            }
            ACTION_BLOCK -> {
                val raw = intent.getStringExtra(EXTRA_RAW)
                if (raw != null) {
                    Store(context).addToList(raw, null, DbHelper.TYPE_BLACKLIST, "notification")
                    Toast.makeText(context, R.string.added_black, Toast.LENGTH_SHORT).show()
                }
                nm?.cancel(notifId)
            }
            ACTION_KEEP -> {
                nm?.cancel(notifId)
            }
        }
    }

    companion object {
        const val ACTION_WHITELIST = "com.mthtamim.callshield.action.WHITELIST"
        const val ACTION_KEEP = "com.mthtamim.callshield.action.KEEP"
        const val ACTION_BLOCK = "com.mthtamim.callshield.action.BLOCK"
        const val EXTRA_RAW = "extra_raw"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
    }
}
