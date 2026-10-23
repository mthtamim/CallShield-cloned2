package com.mthtamim.callshield.tile

import android.content.ComponentName
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mthtamim.callshield.R
import com.mthtamim.callshield.data.Store
import com.mthtamim.callshield.engine.Profile
import com.mthtamim.callshield.widget.WidgetUpdater

/**
 * Feature 18: Quick Settings tile for the active profile. A tap cycles the
 * manually chosen profile (Normal, Strict, Focus, Night, Off). When a
 * schedule is in charge the tile shows the scheduled profile; cycling then
 * changes the manual choice, which applies again once the schedule ends.
 */
class ModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val store = Store(this)
        store.setActiveProfile(Profile.next(store.activeProfile()))
        refreshTile()
        TempAllowTileService.refresh(this)
        WidgetUpdater.updateAll(this)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val store = Store(this)
        val now = System.currentTimeMillis()
        val effective = store.effectiveProfile(now)
        val scheduled = store.scheduledProfile(now) != null
        tile.label = getString(R.string.tile_profile_label)
        tile.subtitle = profileLabel(effective) +
            if (scheduled) getString(R.string.tile_scheduled_suffix) else ""
        tile.state = if (store.masterEnabled() && effective != Profile.OFF) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun profileLabel(profile: Profile): String = when (profile) {
        Profile.NORMAL -> getString(R.string.profile_normal)
        Profile.STRICT -> getString(R.string.profile_strict)
        Profile.FOCUS -> getString(R.string.profile_focus)
        Profile.NIGHT -> getString(R.string.profile_night)
        Profile.OFF -> getString(R.string.profile_off)
    }
}

/** Asks Android to refresh both QS tiles (used after state changes elsewhere). */
object TileUpdater {

    fun requestRefresh(context: android.content.Context) {
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, ModeTileService::class.java)
            )
            TileService.requestListeningState(
                context,
                ComponentName(context, TempAllowTileService::class.java)
            )
        } catch (e: Exception) {
            // Tiles that were never added to the QS panel cannot be
            // refreshed; the tile updates itself when it is next shown.
        }
    }
}
