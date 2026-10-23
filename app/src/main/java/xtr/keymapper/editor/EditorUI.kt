package xtr.keymapper.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import xtr.keymapper.IRemoteService
import xtr.keymapper.OnKeyEventListener
import xtr.keymapper.R
import xtr.keymapper.Utils
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.editor.component.Camera
import xtr.keymapper.editor.component.Crosshair
import xtr.keymapper.editor.component.MouseWalk
import xtr.keymapper.editor.component.RightClick
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.macro.MacroIdUtils
import xtr.keymapper.macro.MacroStatus
import xtr.keymapper.macro.MacroView
import xtr.keymapper.macro.MacroView.OnFinishListener
import xtr.keymapper.server.RemoteServiceHelper

class EditorUI(
    private val context: Context, // Callback when the editor UI is hidden/closed
    private val editorCallback: EditorCallback?, /* Name of the currently loaded keymap profile */
    private val profileName: String?, private val startMode: Int,// Compose Dialog Management
    private val importExportComposeView: ComposeView?
) : OnKeyEventListener.Stub() {

    // When a keyboard key is pressed,
    // Receives keyboard input while editing a key binding
    private var keyInFocus: KeyInFocusListener? = null

    private val mHandler = Handler(Looper.getMainLooper())

    /* The active keymap config and a backup copy */
    private var profile: KeymapProfile? = null
    private var profileBackup: KeymapProfile? = null

    private var overlayOpen = false

    // The settings/catalog UI (hidden in SHOW_KEYMAP_ONLY mode)
    private val settingsOverlay: SettingsOverlay?

    // The main editor view and the container holding all key buttons
    private val mainView: ViewGroup?
    private val keysContainerView: ViewGroup

    private var macroDialog: MacroDialog? = null

    private val editorUiComponents = EditorUiComponentList()

    // IPC connection to the remote service that captures raw input
    private var mService: IRemoteService? = null

    init {
        val layoutInflater = context.getSystemService(LayoutInflater::class.java)

        if (startMode != SHOW_KEYMAP_ONLY) {
            context.stopService(Intent(context, ShowKeymapService::class.java))
            settingsOverlay = SettingsOverlay(context, startMode)
            mainView = settingsOverlay.createView(layoutInflater)
            keysContainerView = settingsOverlay.binding.keyContainer

            settingsOverlay.inflateMenuResource(startMode, layoutInflater)
            settingsOverlay.setOnActionSelectedListener { id: Int ->
                this.onActionSelected(
                    id
                )
            }
        } else {
            keysContainerView = FrameLayout(context)
            keysContainerView.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            settingsOverlay = null
            mainView = null
        }
    }

    fun unregisterOnKeyEventListener() {
        settingsOverlay!!.onUnRegisterKeyEventListener()
        if (mService != null) {
            try {
                mService!!.unregisterOnKeyEventListener(this)
                mService!!.resumeMouse()
                mService!!.reloadKeymap()
            } catch (ignored: RemoteException) {
            }
        }
        mService = null
    }

    @Throws(RemoteException::class)
    fun registerOnKeyEventListener(service: IRemoteService?) {
        mService = service
        mService!!.registerOnKeyEventListener(this)
        settingsOverlay!!.onRegisterKeyEventListener()
        mService!!.pauseMouse()
    }

    fun interface KeyInFocusListener {
        fun setText(key: String?)
    }

    fun open(overlayWindow: Boolean) {
        settingsOverlay!!.overlayWindow = overlayWindow
        if (mainView!!.windowToken == null && mainView.parent == null) if (overlayWindow) openOverlayWindow()
        else {
            overlayOpen = false
            if (context is EditorActivity) (context as Activity).setContentView(mainView)
            else  // For MainActivity
                (context as Activity).addContentView(
                    mainView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
        }

        mainView.isFocusable = true
        mainView.isFocusableInTouchMode = true
        mainView.setOnKeyListener(View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
            this.onKey(
                v,
                keyCode,
                event!!
            )
        })

        loadKeymapAndObserveSizeChanges()
    }

    fun openSettings() {
        (context as MainActivity).addContentView(
            mainView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun showControls(alpha: Float) {
        openOverlayWindow(alpha)
    }

    private fun getRealScreenDimensions(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val display = wm.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun openOverlayWindow(alpha: Float = -1f) {
        val overlayView =
            (if (startMode == EditorUI.Companion.SHOW_KEYMAP_ONLY) keysContainerView else mainView)!!
        if (overlayOpen) {
            removeView(overlayView)
        }

        val mWindowManager = context.getSystemService(WindowManager::class.java)
        val mParams =
            if (startMode == SHOW_KEYMAP_ONLY) Utils.getPointerLayoutParams(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) else windowManagerLayoutParams()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        if (startMode == SHOW_KEYMAP_ONLY) {
            val targetAlpha = if (alpha > 0f && alpha <= 0.8f) alpha else 0.2f
            mParams.alpha = targetAlpha
            overlayView.setAlpha(1.0f)
            Utils.applyEmptyTouchableRegion(overlayView)
        } else {
            if (alpha < 1 && alpha > 0) overlayView.setAlpha(alpha)
        }

        mWindowManager.addView(overlayView, mParams)
        overlayOpen = true
        if (startMode != SHOW_KEYMAP_ONLY) hideSystemBars()
    }

    private fun windowManagerLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
        PixelFormat.TRANSLUCENT
    )

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val overlayView =
                (if (startMode == SHOW_KEYMAP_ONLY) keysContainerView else mainView)!!
            val windowInsetsController = overlayView.getWindowInsetsController()
            if (windowInsetsController != null) {
                windowInsetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.hide(WindowInsets.Type.systemBars())
            }
        }
    }

    /**
     * For events received by view
     * @return true if we consume the event
     */
    fun onKey(v: View?, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val key = KeyUtils.resolveKeyName(keyCode, event) ?: return false
        if (macroDialog != null) {
            macroDialog!!.onKey(key)
            return true
        } else if (keyInFocus != null) {
            keyInFocus!!.setText(key)
            return true
        }
        return false
    }

    /**
     * For key events received from getevent running in remote process
     * @param event A line of output from getevent -ql
     */
    override fun onKeyEvent(event: String) {
        // e.g.: /dev/input/event3: EV_KEY KEY_X DOWN
        val inputEvent =
            event.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val evIndex = inputEvent.indexOf("EV_KEY")
        if (evIndex == -1 || evIndex + 2 >= inputEvent.size) return
        val code = inputEvent[evIndex + 1]
        val action = inputEvent[evIndex + 2]

        // Ignore non-key events and releases (UP)
        if (!code.contains("KEY_")) return
        if (action != "DOWN" && action != "00000001") return

        val key = if (code.startsWith("KEY_")) code.substring(4) else code

        // Incoming calls are not guaranteed to be executed on the main thread
        mHandler.post {
            if (macroDialog != null) macroDialog!!.onKey(key)
            else if (keyInFocus != null) keyInFocus!!.setText(key)
            settingsOverlay!!.onKey(key)
        }
    }


    /**
     * Called when a button in catalog has been clicked.
     * 
     * @param id the relevant id of the menu item for the card.
     */
    fun onActionSelected(id: Int) {
        // X y coordinates of center of container/screen
        val (realWidth, realHeight) = getRealScreenDimensions()
        val defaultX = if (keysContainerView.width > 0) keysContainerView.width / 2f else realWidth / 2f
        val defaultY = if (keysContainerView.height > 0) keysContainerView.height / 2f else realHeight / 2f

        when (id) {
            R.id.save -> {
                closeEditor()
            }
            R.id.macro -> {
                showMacroDialog()
            }
            R.id.reset -> {
                profile = profileBackup
                reloadKeymap()
            }
            R.id.import_export -> {
                // Show Compose-based UI from ImportExportDialog.kt
                val configString = generateConfigString()
                showImportExportDialog(configString)
            }
            else -> {
                editorUiComponents.addMatchingComponentForId(
                    id,
                    mCallback,
                    context,
                    defaultX,
                    defaultY
                ) { editorUiComponents.add(it) }
            }
        }
    }

    private fun showMacroDialog() {
        macroDialog = MacroDialog(context, overlayOpen, profile!!)
        macroDialog!!.show(View.OnClickListener { v: View? ->
            if (v != null) addMacro()
            if (macroDialog != null) {
                macroDialog!!.dismiss()
                macroDialog = null
            }
        })
    }

    /**
     * MacroStatus for displaying elapsed time
     * Stop when receiving any keyboard input from User or stopwatch is clicked and show macro dialog finally
     * MacroView for visualization
     */
    private fun addMacro() {
        if (mService != null) {
            mainView!!.setFocusable(true)
        }
        val macroStatus = MacroStatus(context, settingsOverlay!!.binding.catalog)
        val macroView = MacroView(context, OnFinishListener { macroView_: MacroView? ->
            // Stop counting time in stopwatch
            macroStatus.stop()

            // Remove the macro view
            keysContainerView.removeView(macroView_)
            macroView_!!.invalidate()

            // Redirect keyboard input
            mainView!!.setOnKeyListener(View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
                this@EditorUI.onKey(
                    v,
                    keyCode,
                    event!!
                )
            })
            settingsOverlay.unHideButtons()
            if (mService == null) mainView.setFocusable(false)
            showMacroDialog()
        })
        // Hide existing buttons in catalog till macro finish
        settingsOverlay.hideButtons()
        macroStatus.start()

        keysContainerView.addView(macroView)
        // Redirect keyboard input
        mainView!!.setOnKeyListener(View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
            macroView.onKey(
                event!!
            )
        })

        settingsOverlay.binding.catalog.setOnClickListener(View.OnClickListener { v: View? -> macroView.clearCanvasAndFinish() })
    }

    /** saves, unregisters listeners, removes views, and triggers Activity or service cleanup. */
    fun closeEditor() {
        if (startMode == START_EDITOR) saveKeymap()
        if (startMode != SHOW_KEYMAP_ONLY) {
            unregisterOnKeyEventListener()
            settingsOverlay!!.onDestroyView()

            removeView(mainView!!)
            if (editorCallback != null) editorCallback.onEditorClosed()
            else RemoteServiceHelper.reloadKeymap(context)

            // Load keymap config after settingsOverlay wrote config
            val keymapConfig = KeymapConfig(context)
            // We stopped the service, so we must restart it
            RemoteServiceHelper.runIfActive(context, Runnable {
                if (keymapConfig.showControls) ShowKeymapService.start(context, profileName)
            })
        } else removeView(keysContainerView)
        
        // Cleanup Compose Dialog if it's open
        closeImportExportDialog()
    }

    private fun removeView(view: ViewGroup) {
        if (overlayOpen && view.isAttachedToWindow) context.getSystemService(
            WindowManager::class.java
        ).removeView(view)
        view.removeAllViews()
        view.invalidate()
    }

    /**
     * Loads the profile after attaching the editor view and saves/reloads it when the container
     * changes size. See docs/editor-lifecycle.md for the launch flow and possible interaction with
     * component position animations.
     * note: the view is NOT already attached on every call path.
     */
    fun loadKeymapAndObserveSizeChanges() {
        profileBackup = KeymapProfiles(context).getProfile(profileName, false)
        keysContainerView.addOnLayoutChangeListener { _, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            val newWidth = right - left
            val newHeight = bottom - top
            // Only reload if screen orientation genuinely flipped between portrait and landscape
            val orientationChanged = oldWidth > 0 && oldHeight > 0 && (oldWidth > oldHeight) != (newWidth > newHeight)
            if (orientationChanged && profile != null) {
                reloadKeymap()
            }
        }
        keysContainerView.post(Runnable { this.loadKeymap() })
    }

    private fun reloadKeymap() {
        keysContainerView.removeAllViews()
        editorUiComponents.clear()
        loadKeymap()
    }

    private fun loadKeymap() {
        profile = KeymapProfiles(context).getProfile(profileName, false)

        val (realWidth, realHeight) = getRealScreenDimensions()
        val targetWidth = if (keysContainerView.width > 0 && Math.abs(keysContainerView.width - realWidth) <= 200) realWidth.toFloat() else if (keysContainerView.width > 0) keysContainerView.width.toFloat() else realWidth.toFloat()
        val targetHeight = if (keysContainerView.height > 0 && Math.abs(keysContainerView.height - realHeight) <= 200) realHeight.toFloat() else if (keysContainerView.height > 0) keysContainerView.height.toFloat() else realHeight.toFloat()

        // Scale to current display size
        profile!!.scale(
            targetWidth,
            targetHeight
        )

        val editorUiComponentFactory = editorUiComponents.newFactory(mCallback, context)

        editorUiComponentFactory.addKeys(profile!!.keys)
        editorUiComponentFactory.addSwipeKeys(profile!!.swipeKeys)
        editorUiComponentFactory.addShiftKeys(profile!!.shiftKeys)
        editorUiComponentFactory.addDpads(profile!!.dpadArray)

        if (profile!!.mouseAimConfig != null) {
            editorUiComponents.add(Crosshair(mCallback, context, profile!!.mouseAimConfig!!))
        }
        if (profile!!.rightClick != null) {
            editorUiComponents.add(RightClick(mCallback, context, profile!!.rightClick))
        }
        if (profile!!.camera != null) {
            editorUiComponents.add(
                Camera(
                    mCallback,
                    context,
                    profile!!.camera!!.x,
                    profile!!.camera!!.y
                )
            )
        }
        if (profile!!.mouseWalk != null) {
            editorUiComponents.add(MouseWalk(mCallback, context, profile!!.mouseWalk))
        }
    }


    /**
     * Persists positions read from the live editor component views. See
     * docs/editor-lifecycle.md for the save call sites and the resize/animation timing risk.
     */
    private fun saveKeymap() {
        if (startMode == SHOW_KEYMAP_ONLY) return

        val linesToWrite = ArrayList<String?>()

        editorUiComponents.forEach { linesToWrite.add(it.toConfigLine()) }

        // Enabled macro ids
        MacroIdUtils.getLines(linesToWrite, profile!!)

        // Save Config
        val profiles = KeymapProfiles(context)
        val (realWidth, realHeight) = getRealScreenDimensions()

        val saveWidth = if (keysContainerView.width > 0 && Math.abs(keysContainerView.width - realWidth) <= 200) realWidth else if (keysContainerView.width > 0) keysContainerView.width else realWidth
        val saveHeight = if (keysContainerView.height > 0 && Math.abs(keysContainerView.height - realHeight) <= 200) realHeight else if (keysContainerView.height > 0) keysContainerView.height else realHeight

        profiles.saveProfile(
            profileName, linesToWrite, profile!!.packageName, !profile!!.disabled,
            saveWidth, saveHeight
        )
    }

    /**
     * Generates a configuration string exactly matching the save format, 
     * used for passing to the Compose Import/Export dialog.
     */
    private fun generateConfigString(): String {
        val linesToWrite = ArrayList<String?>()
        editorUiComponents.forEach { linesToWrite.add(it.toConfigLine()) }

        MacroIdUtils.getLines(linesToWrite, profile!!)
        return linesToWrite.joinToString("\n")
    }

    private fun handleImportAction() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clipData = clipboard?.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                profile = KeymapProfiles.parse(lines)
                reloadKeymap()
                android.widget.Toast.makeText(context, "Keymap imported from clipboard", android.widget.Toast.LENGTH_SHORT).show()
                closeImportExportDialog()
                return
            }
        }
        android.widget.Toast.makeText(context, "No valid config found on clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun handleExportAction() {
        val configString = generateConfigString()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("XtMapper Config", configString)
        clipboard?.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "Configuration copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showImportExportDialog(configCode: String) {

        val composeView = importExportComposeView ?: return

        // Reuse the host view tree's owners so Compose uses the Activity or Service lifecycle.
        val lifecycleOwner = (context as? LifecycleOwner)
            ?: (mainView?.findViewTreeLifecycleOwner())
            ?: error("Compose host must provide a LifecycleOwner")
        val savedStateOwner = (context as? SavedStateRegistryOwner)
            ?: (mainView?.findViewTreeSavedStateRegistryOwner())
            ?: error("Compose host must provide a SavedStateRegistryOwner")
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        if (context is ViewModelStoreOwner) {
            composeView.setViewTreeViewModelStoreOwner(context)
        } else if (mainView?.findViewTreeViewModelStoreOwner() != null) {
            composeView.setViewTreeViewModelStoreOwner(mainView.findViewTreeViewModelStoreOwner()!!)
        }

        composeView.setContent {
            // Kotlin's default parameters allow 4-arg Java signature
            ImportExportDialog(
                configCode,
                { closeImportExportDialog() },
                { handleImportAction() },
                { handleExportAction() }
            )
        }

        if (context is Activity && !overlayOpen) {
            context.addContentView(
                composeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } else {
            // The Activity and Service editor both own a full-screen overlay root. Add the dialog
            // to that same root instead of opening a second application-overlay window.
            val parent = mainView ?: error("Overlay editor has no root view")
            parent.addView(
                composeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        
    }

    private fun closeImportExportDialog() {
        val view = importExportComposeView ?: return

        (view.parent as? ViewGroup)?.removeView(view)

    }

    private val mCallback: EditorUiComponentCallback = object : EditorUiComponentCallback {
        override fun isOverlayOpen(): Boolean {
            return overlayOpen
        }

        override val profile: KeymapProfile get() = this@EditorUI.profile!!

        override val keysContainerView: ViewGroup get() = this@EditorUI.keysContainerView

        override fun removeComponent(component: EditorUiComponent?, vararg viewsToRemove: View?) {
            for (view in viewsToRemove) {
                keysContainerView.removeView(view)
            }
            editorUiComponents.remove(component)
        }

        override fun setOnKeyListener(keyInFocusListener: KeyInFocusListener?) {
            keyInFocus = keyInFocusListener
            if (keyInFocusListener != null) {
                mainView?.isFocusable = true
                mainView?.isFocusableInTouchMode = true
                mainView?.requestFocus()
            }
        }
    }

    companion object {
        private val TAG = "EditorUI"

        /* Start modes */
        const val START_SETTINGS: Int = 0 // Shows full editor with catalog/menu
        const val START_EDITOR: Int = 1 // Shows editor without catalog (save/reset available)
        const val SHOW_KEYMAP_ONLY: Int = 2 // Used to show an overlay with active keymapping
        fun resizeView(view: View, x: Int, y: Int) {
            val layoutParams = view.layoutParams
            layoutParams.width += x
            layoutParams.height += y
            view.requestLayout()
        }
    }
}
