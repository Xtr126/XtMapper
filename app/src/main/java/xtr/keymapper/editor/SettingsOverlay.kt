package xtr.keymapper.editor

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuItemCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.R
import xtr.keymapper.databinding.KeymapEditorItemBinding
import xtr.keymapper.databinding.KeymapEditorLayoutBinding
import xtr.keymapper.databinding.SettingsBinding
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.server.RemoteServiceHelper

class SettingsOverlay(private val context: Context, private val startMode: Int) {

    private val keymapConfig = KeymapConfig(context)

    // Java nulled this in onDestroyView; lateinit cannot, and callers drop the
    // instance immediately anyway — ceiling: binding stays readable after destroy
    internal lateinit var binding: KeymapEditorLayoutBinding

    private lateinit var pointerModeMap: Map<String, Int>
    private lateinit var touchpadInputModeMap: Map<String, Int>
    private var onCardItemSelectedListener: OnCardItemSelectedListener? = null
    internal var overlayWindow = false
    private var keyInFocus: View? = null

    fun createView(inflater: LayoutInflater): ViewGroup {
        // Inflate the layout for this fragment
        binding = KeymapEditorLayoutBinding.inflate(inflater)
        init()
        return binding.root
    }

    private val ON_BUTTON_CHECKED_LISTENER =
        MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
            if (checkedId == R.id.button_sliders) {
                binding.sliders.visibility = if (isChecked) View.VISIBLE else View.GONE
            } else if (checkedId == R.id.button_shortcuts) {
                binding.shortcuts.visibility = if (isChecked) View.VISIBLE else View.GONE
            } else if (checkedId == R.id.button_misc) {
                binding.misc.visibility = if (isChecked) View.VISIBLE else View.GONE
            } else if (checkedId == R.id.button_add) {
                if (EditorUI.START_SETTINGS == startMode)
                    onCardItemSelected(R.id.save)
                else binding.catalog.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

    private fun init() {
        binding.sliderMouse.setValue(keymapConfig.mouseSensitivity!!)
        binding.sliderScrollSpeed.setValue(keymapConfig.scrollSpeed!!)
        binding.sliderSwipeDelay.setValue(keymapConfig.swipeDelayMs.toFloat())
        binding.sliderFloatingKeysSize.setValue(keymapConfig.floatingKeysSize)

        binding.swipeDelayText.setText(
            context.getString(R.string.swipe_delay_ms, keymapConfig.swipeDelayMs)
        )
        binding.sliderSwipeDelay.addOnChangeListener { _, value, _ ->
            binding.swipeDelayText.setText(context.getString(R.string.swipe_delay_ms, value.toInt()))
        }

        binding.sliderShiftTransition.setValue(keymapConfig.shiftTransitionMs.toFloat())
        binding.shiftTransitionText.setText(
            context.getString(R.string.shift_transition_ms, keymapConfig.shiftTransitionMs)
        )
        binding.sliderShiftTransition.addOnChangeListener { _, value, _ ->
            binding.shiftTransitionText.setText(context.getString(R.string.shift_transition_ms, value.toInt()))
        }

        val jitterSpread = if (keymapConfig.touchJitter) keymapConfig.touchJitterSpread else 0f
        binding.sliderTouchJitter.value = jitterSpread
        binding.touchJitterText.text = if (keymapConfig.touchJitter) {
            context.getString(R.string.touch_jitter_spread_px, jitterSpread.toInt())
        } else {
            context.getString(R.string.touch_jitter_spread)
        }
        binding.sliderTouchJitter.addOnChangeListener { _, value, _ ->
            if (value > 0f) {
                binding.touchJitterText.text = context.getString(R.string.touch_jitter_spread_px, value.toInt())
            } else {
                binding.touchJitterText.text = context.getString(R.string.touch_jitter_spread)
            }
        }

        binding.mouseDragToggle.isChecked = keymapConfig.ctrlDragMouseGesture
        binding.mouseWheelToggle.isChecked = keymapConfig.ctrlMouseWheelZoom

        binding.mouseAimKeyGrave.isChecked = keymapConfig.keyGraveMouseAim
        binding.mouseAimRightClick.isChecked = keymapConfig.rightClickMouseAim

        loadKeyboardShortcuts()
        binding.launchEditor.setOnKeyListener(::onKeyboardKeyPress)
        binding.pauseResume.setOnKeyListener(::onKeyboardKeyPress)
        binding.switchProfile.setOnKeyListener(::onKeyboardKeyPress)
        binding.mouseAimKey.setOnKeyListener(::onKeyboardKeyPress)
        binding.blockKeyboard.setOnKeyListener(::onKeyboardKeyPress)
        binding.blockKeyboardRow.visibility = if (keymapConfig.isRootMode) View.VISIBLE else View.GONE

        binding.mouseAimKey.setOnGenericMotionListener(::onMouseClick)

        mouseAimActions()
        loadTouchpadInputSettings()

        val pointerModeCodes =
            intArrayOf(KeymapConfig.POINTER_COMBINED, KeymapConfig.POINTER_OVERLAY, KeymapConfig.POINTER_SYSTEM)
        val pointerModeNames = context.resources.getStringArray(R.array.pointer_modes)
        pointerModeMap =
            pointerModeNames.indices.associate { pointerModeNames[it] to pointerModeCodes[it] }

        for ((name, code) in pointerModeMap) {
            if (code == keymapConfig.pointerMode) {
                binding.pointerMode.setText(name)
            }
        }
        binding.pointerMode.setSimpleItems(pointerModeNames)

        binding.toggleButtonGroup.addOnButtonCheckedListener(ON_BUTTON_CHECKED_LISTENER)

        if (startMode == EditorUI.START_EDITOR) {
            binding.sliders.visibility = View.GONE
            binding.shortcuts.visibility = View.GONE
            binding.misc.visibility = View.GONE
            binding.catalog.visibility = View.VISIBLE
        }
        binding.buttonMisc.isChecked = binding.misc.visibility == View.VISIBLE
        binding.buttonShortcuts.isChecked = binding.shortcuts.visibility == View.VISIBLE
        binding.buttonSliders.isChecked = binding.sliders.visibility == View.VISIBLE
        binding.buttonAdd.isChecked = binding.catalog.visibility == View.VISIBLE

        binding.advanced.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {

        val settingsBinding = SettingsBinding.inflate(LayoutInflater.from(context))

        settingsBinding.autoProfileSwitch.isChecked = keymapConfig.disableAutoProfiling
        settingsBinding.useShizuku.isChecked = keymapConfig.isShizukuMode
        settingsBinding.editorOverlay.isChecked = keymapConfig.editorOverlay

        val isRoot = keymapConfig.isRootMode
        settingsBinding.blockKeyboardInput.visibility = if (isRoot) View.VISIBLE else View.GONE
        settingsBinding.blockKeyboardInput.isChecked = isRoot && keymapConfig.blockKeyboardInput

        settingsBinding.useShizuku.setOnCheckedChangeListener { _, isChecked ->
            settingsBinding.blockKeyboardInput.visibility = if (isChecked) View.GONE else View.VISIBLE
            if (isChecked) {
                settingsBinding.blockKeyboardInput.isChecked = false
            }
        }

        settingsBinding.touchJitterSwitch.isChecked = keymapConfig.touchJitter
        settingsBinding.touchJitterSpread.value = keymapConfig.touchJitterSpread
        settingsBinding.touchJitterSpreadText.text = context.getString(R.string.touch_jitter_spread_px, keymapConfig.touchJitterSpread.toInt())
        settingsBinding.touchJitterSpread.addOnChangeListener { _, value, _ ->
            settingsBinding.touchJitterSpreadText.text = context.getString(R.string.touch_jitter_spread_px, value.toInt())
        }

        settingsBinding.showControls.isChecked = keymapConfig.showControls
        settingsBinding.showControlsOpacity.setValue(keymapConfig.showControlsOpacity)

        val saveSettings = {
            keymapConfig.disableAutoProfiling = settingsBinding.autoProfileSwitch.isChecked
            val shizuku = settingsBinding.useShizuku.isChecked
            keymapConfig.useShizuku = shizuku
            keymapConfig.inputMode = if (shizuku) KeymapConfig.MODE_SHIZUKU else KeymapConfig.MODE_ROOT
            keymapConfig.blockKeyboardInput = !shizuku && settingsBinding.blockKeyboardInput.isChecked
            keymapConfig.editorOverlay = settingsBinding.editorOverlay.isChecked
            keymapConfig.touchJitter = settingsBinding.touchJitterSwitch.isChecked
            keymapConfig.touchJitterSpread = settingsBinding.touchJitterSpread.value
            keymapConfig.showControls = settingsBinding.showControls.isChecked
            keymapConfig.showControlsOpacity = settingsBinding.showControlsOpacity.value
            keymapConfig.applySharedPrefs()
            RemoteServiceHelper.reloadKeymap(context)
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(context, R.style.MaterialAlertDialog_Centered)
            .setView(settingsBinding.root)
            .setTitle(R.string.advanced)
            .setIcon(R.drawable.ic_baseline_settings_24)
            .setCancelable(true)
            .setOnDismissListener {
                saveSettings()
            }
            .create()
        if (overlayWindow) dialog.window!!.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        )
        dialog.show()
    }

    private fun loadKeyboardShortcuts() {
        // Strips KEY_ from start
        val removekeyPrefix = { key: String? -> if (key!!.length > 4) key.substring(4) else " " }

        binding.pauseResume.setText(removekeyPrefix(keymapConfig.pauseResumeShortcutKey))
        binding.launchEditor.setText(removekeyPrefix(keymapConfig.launchEditorShortcutKey))
        binding.switchProfile.setText(removekeyPrefix(keymapConfig.switchProfileShortcutKey))
        binding.mouseAimKey.setText(removekeyPrefix(keymapConfig.mouseAimShortcutKey))
        binding.blockKeyboard.setText(removekeyPrefix(keymapConfig.blockKeyboardShortcutKey))

        loadModifierKeys()
    }

    private fun loadModifierKeys() {
        binding.launchEditorModifier.setText(keymapConfig.launchEditorShortcutKeyModifier)
        binding.pauseResumeModifier.setText(keymapConfig.pauseResumeShortcutKeyModifier)
        binding.switchProfileModifier.setText(keymapConfig.switchProfileShortcutKeyModifier)
        binding.blockKeyboardModifier.setText(keymapConfig.blockKeyboardShortcutKeyModifier)

        val modifierKeys = arrayOf(KeymapConfig.KEY_CTRL, KeymapConfig.KEY_ALT)
        binding.launchEditorModifier.setSimpleItems(modifierKeys)
        binding.pauseResumeModifier.setSimpleItems(modifierKeys)
        binding.switchProfileModifier.setSimpleItems(modifierKeys)
        binding.blockKeyboardModifier.setSimpleItems(modifierKeys)
    }

    private fun mouseAimActions() {
        if (keymapConfig.mouseAimToggle) binding.mouseAimAction.setText(R.string.toggle)
        else binding.mouseAimAction.setText(R.string.hold)

        val mouseAimActionNames = context.resources.getStringArray(R.array.mouse_aim_actions)
        binding.mouseAimAction.setSimpleItems(mouseAimActionNames)
    }

    private fun loadTouchpadInputSettings() {
        val touchpadInputModeCodes =
            intArrayOf(KeymapConfig.TOUCHPAD_DIRECT, KeymapConfig.TOUCHPAD_RELATIVE, KeymapConfig.TOUCHPAD_DISABLED)
        val touchpadInputModeNames = context.resources.getStringArray(R.array.touchpad_input_modes)
        touchpadInputModeMap =
            touchpadInputModeNames.indices.associate { touchpadInputModeNames[it] to touchpadInputModeCodes[it] }

        for ((name, code) in touchpadInputModeMap) {
            if (code == keymapConfig.touchpadInputMode) {
                binding.touchpadInputMode.setText(name)
            }
        }
        binding.touchpadInputMode.setSimpleItems(touchpadInputModeNames)
    }

    private fun saveKeyboardShortcuts() {
        keymapConfig.launchEditorShortcutKey = keyCodePrefix(binding.launchEditor)
        keymapConfig.pauseResumeShortcutKey = keyCodePrefix(binding.pauseResume)
        keymapConfig.switchProfileShortcutKey = keyCodePrefix(binding.switchProfile)
        keymapConfig.mouseAimShortcutKey = keyCodePrefix(binding.mouseAimKey)
        keymapConfig.blockKeyboardShortcutKey = keyCodePrefix(binding.blockKeyboard)

        keymapConfig.launchEditorShortcutKeyModifier = binding.launchEditorModifier.text.toString()
        keymapConfig.pauseResumeShortcutKeyModifier = binding.pauseResumeModifier.text.toString()
        keymapConfig.switchProfileShortcutKeyModifier = binding.switchProfileModifier.text.toString()
        keymapConfig.blockKeyboardShortcutKeyModifier = binding.blockKeyboardModifier.text.toString()
    }

    fun onDestroyView() {
        saveKeyboardShortcuts()
        keymapConfig.mouseAimToggle =
            binding.mouseAimAction.text.toString() == context.resources.getString(R.string.toggle)
        keymapConfig.touchpadInputMode = touchpadInputModeMap[binding.touchpadInputMode.text.toString()]!!
        keymapConfig.pointerMode = pointerModeMap[binding.pointerMode.text.toString()]!!

        keymapConfig.mouseSensitivity = binding.sliderMouse.value
        keymapConfig.scrollSpeed = binding.sliderScrollSpeed.value
        keymapConfig.swipeDelayMs = binding.sliderSwipeDelay.value.toInt()
        keymapConfig.shiftTransitionMs = binding.sliderShiftTransition.value.toInt()
        keymapConfig.floatingKeysSize = binding.sliderFloatingKeysSize.value

        keymapConfig.ctrlMouseWheelZoom = binding.mouseWheelToggle.isChecked
        keymapConfig.ctrlDragMouseGesture = binding.mouseDragToggle.isChecked

        keymapConfig.rightClickMouseAim = binding.mouseAimRightClick.isChecked
        keymapConfig.keyGraveMouseAim = binding.mouseAimKeyGrave.isChecked

        val jitterVal = binding.sliderTouchJitter.value
        if (jitterVal > 0f) {
            keymapConfig.touchJitter = true
            keymapConfig.touchJitterSpread = jitterVal
        } else {
            keymapConfig.touchJitter = false
        }

        keymapConfig.applySharedPrefs()
    }

    fun inflateMenuResource(startMode: Int, layoutInflater: LayoutInflater) {
        val popupMenu = PopupMenu(context, View(context))
        popupMenu.inflate(R.menu.keymap_editor_menu)
        val menu: Menu = popupMenu.menu

        if (startMode == EditorUI.START_EDITOR) for (n in 1..3) { // n = { 1,2,3 } For dividing between three columns
            for (i in menu.size() * (n - 1) / 3 until menu.size() * n / 3) { // i = { 0,1,2.. }
                val menuItem = menu.getItem(i)

                val parentView: LinearLayout = when (n) {
                    1 -> binding.L1
                    2 -> binding.L2
                    else -> binding.L3
                }

                val itemBinding = KeymapEditorItemBinding.inflate(layoutInflater, parentView, true)
                val button: MaterialButton = itemBinding.root
                button.icon = menuItem.icon
                button.text = menuItem.title
                button.contentDescription = MenuItemCompat.getContentDescription(menuItem)
                button.setOnClickListener { onCardItemSelected(menuItem.itemId) }
            }
        } else if (startMode == EditorUI.START_SETTINGS) {
            binding.buttonAdd.setIconResource(R.drawable.ic_baseline_done_36)
            binding.catalog.visibility = View.GONE
        }
    }

    internal fun hideButtons() {
        binding.L3.visibility = View.GONE
        binding.L2.visibility = View.GONE
        binding.L1.visibility = View.GONE
    }

    internal fun unHideButtons() {
        binding.L3.visibility = View.VISIBLE
        binding.L2.visibility = View.VISIBLE
        binding.L1.visibility = View.VISIBLE
    }

    private fun onCardItemSelected(itemId: Int) {
        onCardItemSelectedListener?.onActionSelected(itemId)
    }

    fun setOnActionSelectedListener(l: OnCardItemSelectedListener) {
        this.onCardItemSelectedListener = l
    }

    fun onRegisterKeyEventListener() {
        binding.launchEditor.setOnKeyListener(this::focusKey)
        binding.pauseResume.setOnKeyListener(this::focusKey)
        binding.switchProfile.setOnKeyListener(this::focusKey)
        binding.mouseAimKey.setOnKeyListener(this::focusKey)
    }

    fun onUnRegisterKeyEventListener() {
        // Accept key events from View
        binding.launchEditor.setOnKeyListener(::onKeyboardKeyPress)
        binding.pauseResume.setOnKeyListener(::onKeyboardKeyPress)
        binding.switchProfile.setOnKeyListener(::onKeyboardKeyPress)
        binding.mouseAimKey.setOnKeyListener(::onKeyboardKeyPress)
    }

    fun onKey(key: String?) {
        (keyInFocus as EditText?)?.setText(key)
    }

    private fun focusKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        keyInFocus = v
        return true
    }

    fun interface OnCardItemSelectedListener {
        /**
         * Called when a CardView has been clicked.
         *
         * @param menuItemId the relevant id of the menu item for the card.
         */
        fun onActionSelected(menuItemId: Int)
    }
}
