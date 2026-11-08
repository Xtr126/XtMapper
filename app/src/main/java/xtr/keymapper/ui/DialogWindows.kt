package xtr.keymapper.ui

import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

enum class DialogWindowMode {
    ACTIVITY,
    OVERLAY
}

@UiThread
fun showDialog(builder: MaterialAlertDialogBuilder, mode: DialogWindowMode): AlertDialog? {
    if (mode == DialogWindowMode.ACTIVITY) return builder.show()
    if (!Settings.canDrawOverlays(builder.context)) return null

    val dialog = builder.create()
    @Suppress("DEPRECATION")
    val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
    }
    requireNotNull(dialog.window).setType(windowType)
    try {
        dialog.show()
    } catch (_: WindowManager.BadTokenException) {
        return null
    } catch (_: SecurityException) {
        return null
    }
    return dialog
}
