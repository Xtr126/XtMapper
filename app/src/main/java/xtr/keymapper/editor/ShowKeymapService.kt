package xtr.keymapper.editor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.appcompat.view.ContextThemeWrapper
import xtr.keymapper.R
import xtr.keymapper.keymap.KeymapConfig

class ShowKeymapService : Service() {
    private var editorUi: EditorUI? = null

    override fun onDestroy() {
        editorUi!!.closeEditor()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        editorUi?.closeEditor()

        var selectedProfile = intent!!.getStringExtra(EditorActivity.PROFILE_NAME) ?: "Default"

        val keymapConfig = KeymapConfig(this)
        val context = ContextThemeWrapper(this, R.style.Theme_XtMapper)
        editorUi = EditorUI(context, editorCallback, selectedProfile, EditorUI.SHOW_KEYMAP_ONLY, null)
        editorUi!!.loadKeymapAndObserveSizeChanges()
        editorUi!!.showControls(keymapConfig.showControlsOpacity)
        return super.onStartCommand(intent, flags, startId)
    }

    private val editorCallback = EditorCallback {
        editorUi = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        fun start(context: Context, selectedProfile: String?) {
            val intent = Intent(context, ShowKeymapService::class.java)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            context.startService(intent)
        }
    }
}
