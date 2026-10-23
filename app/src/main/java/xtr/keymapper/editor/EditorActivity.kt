package xtr.keymapper.editor

import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.IRemoteService
import xtr.keymapper.R
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.server.RemoteServiceHelper

class EditorActivity : ComponentActivity() {
    private var editor: EditorUI? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedProfile = intent.getStringExtra(PROFILE_NAME)
        if (selectedProfile == null) {
            finish()
            return
        }

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        if (editor != null) editor!!.closeEditor()

        setTheme(R.style.Theme_XtMapper)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EditorActivity)
            setViewTreeSavedStateRegistryOwner(this@EditorActivity)
            setViewTreeViewModelStoreOwner(this@EditorActivity)
        }

        editor = EditorUI(
            this,
            this::editorCallback,
            selectedProfile,
            EditorUI.START_EDITOR,
            composeView
        )

        val keymapConfig = KeymapConfig(this)
        if (Settings.canDrawOverlays(this)) {
            editor!!.open(keymapConfig.editorOverlay)
        } else {
            editor!!.open(false)
            MainActivity.checkOverlayPermission(this)
        }

        RemoteServiceHelper.getInstance(this, this::onConnection)
    }

    private fun editorCallback() {
        editor = null
        finish()
    }

    private fun onConnection(service: IRemoteService?) {
        if (editor != null) {
            val keymapConfig = KeymapConfig(this)

            if (service != null) {
                try {
                    editor!!.registerOnKeyEventListener(service)
                } catch (e: RemoteException) {
                    Log.e("editorActivity", e.message, e)
                }
            } else if (!xtr.keymapper.TouchPointer.isRunning) {
                val builder = MaterialAlertDialogBuilder(this)

                builder.setMessage(R.string.dialog_alert_editor)
                    .setPositiveButton(R.string.ok, null)
                    .setTitle(R.string.dialog_alert_editor_title)
                val dialog = builder.create()
                if (keymapConfig.editorOverlay) dialog.window!!
                    .setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
                dialog.show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (editor != null) editor!!.closeEditor()
        editor = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (editor?.onKey(null, event.keyCode, event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val PROFILE_NAME: String = "profile"
    }
}
