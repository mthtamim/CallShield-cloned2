package com.mthtamim.callshield.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mthtamim.callshield.R
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.widget.WidgetUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Feature 18: one tap starts a 30-minute Temporary Allow window (feature 7)
 * straight from Quick Settings; a second tap while it runs cancels it.
 */
class TempAllowTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val store = Store(this)
        val now = System.currentTimeMillis()
        if (store.tempAllowActive(now)) {
            store.cancelTempAllow()
        } else {
            store.startTempAllow(30, now)
        }
        refreshTile()
        WidgetUpdater.updateAll(this)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val store = Store(this)
        val now = System.currentTimeMillis()
        tile.label = getString(R.string.tile_temp_label)
        tile.subtitle = if (store.tempAllowActive(now)) {
            val until = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(store.tempAllowUntil()))
            getString(R.string.tile_temp_until, until)
        } else {
            getString(R.string.tile_temp_start)
        }
        tile.state = if (store.tempAllowActive(now)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, TempAllowTileService::class.java)
                )
            } catch (e: Exception) {
                // Tile not added to the panel: nothing to refresh.
            }
        }
    }
}
