package com.neilluo.scrolllock.surface

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.MainActivity
import com.neilluo.scrolllock.data.Gate
import com.neilluo.scrolllock.data.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: shows locked or open while the shade is down. Tapping opens the app. */
class StatusTile : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onStartListening() {
        AppGraph.init(this)
        scope.launch {
            val gate = AppGraph.state.gateNow(AppGraph.settings.current())
            qsTile?.apply {
                state = if (gate.isOpen) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = "Instagram"
                subtitle = when (gate) {
                    is Gate.Open -> "Open · ${TimeFormat.elapsed(gate.remainingMs)}"
                    is Gate.Bypass -> "Bypass · ${TimeFormat.elapsed(gate.remainingMs)}"
                    is Gate.Closed -> "Locked"
                }
                updateTile()
            }
        }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
