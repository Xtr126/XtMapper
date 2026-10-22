package xtr.keymapper.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import xtr.keymapper.R
import xtr.keymapper.TouchPointer
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.keymap.KeymapConfig

class KeymapperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val running = TouchPointer.isRunning

        if (running) {
            TouchPointer.activeInstance?.stopKeymapper()
            val stopIntent = Intent(this, TouchPointer::class.java).apply {
                action = TouchPointer.ACTION_STOP
            }
            startService(stopIntent)
            TouchPointer.isRunning = false
            updateTileState(false)
            Toast.makeText(this, "XtMapper stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(intent)
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                return
            }

            val selectedProfile = TouchPointer.currentSelectedProfile ?: "Default"
            val startIntent = Intent(this, TouchPointer::class.java).apply {
                putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            }
            ContextCompat.startForegroundService(this, startIntent)
            TouchPointer.isRunning = true
            updateTileState(true, selectedProfile)
            Toast.makeText(this, "XtMapper started ($selectedProfile)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchIntent(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState(
        isActive: Boolean = TouchPointer.isRunning,
        profileName: String? = TouchPointer.currentSelectedProfile
    ) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick_settings_tile_name)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) {
                if (!profileName.isNullOrEmpty()) profileName else "Active"
            } else {
                "Off"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, KeymapperTileService::class.java))
            } catch (e: Exception) {
                Log.w("KeymapperTileService", "Failed to request listening state: ${e.message}")
            }
        }
    }
}
