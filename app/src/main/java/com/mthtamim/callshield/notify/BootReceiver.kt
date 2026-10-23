package com.mthtamim.callshield.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.tile.TileUpdater
import com.mthtamim.callshield.widget.WidgetUpdater

/** Re-arms the schedule and summary alarms after a reboot (features 15, 31). */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ProfileScheduler.scheduleAll(context)
            // Feature 51 (v1.1): a pending post-call check does not survive
            // a reboot on its own; re-arm it while its window is fresh.
            val store = Store(context)
            if (store.pendingPostCall() != null &&
                System.currentTimeMillis() - store.pendingPostCallAt() <
                PostCallScheduler.FALLBACK_MS
            ) {
                PostCallScheduler.scheduleCheck(
                    context, System.currentTimeMillis() + PostCallScheduler.CHECK_DELAY_MS
                )
            }
            TileUpdater.requestRefresh(context)
            WidgetUpdater.updateAll(context)
        }
    }
}
