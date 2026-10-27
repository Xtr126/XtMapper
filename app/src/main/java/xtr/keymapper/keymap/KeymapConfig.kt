package xtr.keymapper.keymap

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.os.Parcel
import android.os.Parcelable

class KeymapConfig : Parcelable {

    @JvmField
    var showControls = false

    @JvmField
    var showControlsOpacity = 0f

    @JvmField
    var floatingKeysSize = 0f

    private var sharedPref: SharedPreferences? = null

    @JvmField
    var mouseSensitivity: Float? = 1f

    @JvmField
    var scrollSpeed: Float? = 1f

    @JvmField
    var ctrlMouseWheelZoom = false

    @JvmField
    var ctrlDragMouseGesture = false

    @JvmField
    var rightClickMouseAim = false

    @JvmField
    var keyGraveMouseAim = false

    @JvmField
    var disableAutoProfiling = false

    @JvmField
    var useShizuku = false

    @JvmField
    var editorOverlay = false

    @JvmField
    var pauseResumeShortcutKey = "KEY_P" // KEY_P

    @JvmField
    var launchEditorShortcutKey = "KEY_E" // KEY_E

    @JvmField
    var switchProfileShortcutKey = "KEY_S" // KEY_S

    @JvmField
    var blockKeyboardShortcutKey = "KEY_B" // KEY_B

    @JvmField
    var swipeDelayMs = 0

    @JvmField
    var shiftTransitionMs = 100

    @JvmField
    var pauseResumeShortcutKeyModifier: String? = null

    @JvmField
    var launchEditorShortcutKeyModifier: String? = null

    @JvmField
    var switchProfileShortcutKeyModifier: String? = null

    @JvmField
    var blockKeyboardShortcutKeyModifier: String? = null

    @JvmField
    var pointerMode = 0

    @JvmField
    var mouseAimShortcutKey: String? = null

    @JvmField
    var mouseAimToggle = false

    @JvmField
    var touchpadInputMode = TOUCHPAD_DISABLED

    @JvmField
    var touchJitter = false

    @JvmField
    var touchJitterSpread = 15f


    @JvmField
    var blockKeyboardInput = false

    @JvmField
    var inputMode = MODE_ROOT

    val isShizukuMode: Boolean
        get() = inputMode == MODE_SHIZUKU || useShizuku

    val isRootMode: Boolean
        get() = !isShizukuMode

    constructor(context: Context?) {
        if (context != null) {
            sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE)
            try {
                loadSharedPrefs()
            } catch (e: ClassCastException) {
                sharedPref!!.edit().clear().apply()
                loadSharedPrefs()
            }
        }
    }

    protected constructor(parcel: Parcel) {
        showControls = parcel.readByte().toInt() != 0
        showControlsOpacity = parcel.readFloat()
        mouseSensitivity = if (parcel.readByte().toInt() == 0) {
            null
        } else {
            parcel.readFloat()
        }
        scrollSpeed = if (parcel.readByte().toInt() == 0) {
            null
        } else {
            parcel.readFloat()
        }
        ctrlMouseWheelZoom = parcel.readByte().toInt() != 0
        ctrlDragMouseGesture = parcel.readByte().toInt() != 0
        rightClickMouseAim = parcel.readByte().toInt() != 0
        keyGraveMouseAim = parcel.readByte().toInt() != 0
        disableAutoProfiling = parcel.readByte().toInt() != 0
        useShizuku = parcel.readByte().toInt() != 0
        editorOverlay = parcel.readByte().toInt() != 0
        pauseResumeShortcutKey = parcel.readString()!!
        launchEditorShortcutKey = parcel.readString()!!
        switchProfileShortcutKey = parcel.readString()!!
        swipeDelayMs = parcel.readInt()
        pauseResumeShortcutKeyModifier = parcel.readString()
        launchEditorShortcutKeyModifier = parcel.readString()
        switchProfileShortcutKeyModifier = parcel.readString()
        pointerMode = parcel.readInt()
        mouseAimShortcutKey = parcel.readString()
        mouseAimToggle = parcel.readByte().toInt() != 0
        touchpadInputMode = parcel.readInt()
        floatingKeysSize = parcel.readFloat()
        touchJitter = parcel.readByte().toInt() != 0
        touchJitterSpread = parcel.readFloat()
        inputMode = parcel.readInt()
        shiftTransitionMs = if (parcel.dataAvail() > 0) parcel.readInt() else 100
        blockKeyboardInput = if (parcel.dataAvail() > 0) parcel.readByte().toInt() != 0 else false
        blockKeyboardShortcutKey = if (parcel.dataAvail() > 0) parcel.readString() ?: "KEY_B" else "KEY_B"
        blockKeyboardShortcutKeyModifier = if (parcel.dataAvail() > 0) parcel.readString() else KEY_CTRL
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByte((if (showControls) 1 else 0).toByte())
        dest.writeFloat(showControlsOpacity)
        if (mouseSensitivity == null) {
            dest.writeByte(0)
        } else {
            dest.writeByte(1)
            dest.writeFloat(mouseSensitivity!!)
        }
        if (scrollSpeed == null) {
            dest.writeByte(0)
        } else {
            dest.writeByte(1)
            dest.writeFloat(scrollSpeed!!)
        }
        dest.writeByte((if (ctrlMouseWheelZoom) 1 else 0).toByte())
        dest.writeByte((if (ctrlDragMouseGesture) 1 else 0).toByte())
        dest.writeByte((if (rightClickMouseAim) 1 else 0).toByte())
        dest.writeByte((if (keyGraveMouseAim) 1 else 0).toByte())
        dest.writeByte((if (disableAutoProfiling) 1 else 0).toByte())
        dest.writeByte((if (useShizuku) 1 else 0).toByte())
        dest.writeByte((if (editorOverlay) 1 else 0).toByte())
        dest.writeString(pauseResumeShortcutKey)
        dest.writeString(launchEditorShortcutKey)
        dest.writeString(switchProfileShortcutKey)
        dest.writeInt(swipeDelayMs)
        dest.writeString(pauseResumeShortcutKeyModifier)
        dest.writeString(launchEditorShortcutKeyModifier)
        dest.writeString(switchProfileShortcutKeyModifier)
        dest.writeInt(pointerMode)
        dest.writeString(mouseAimShortcutKey)
        dest.writeByte((if (mouseAimToggle) 1 else 0).toByte())
        dest.writeInt(touchpadInputMode)
        dest.writeFloat(floatingKeysSize)
        dest.writeByte((if (touchJitter) 1 else 0).toByte())
        dest.writeFloat(touchJitterSpread)
        dest.writeInt(inputMode)
        dest.writeInt(shiftTransitionMs)
        dest.writeByte((if (blockKeyboardInput) 1 else 0).toByte())
        dest.writeString(blockKeyboardShortcutKey)
        dest.writeString(blockKeyboardShortcutKeyModifier)
    }

    override fun describeContents(): Int {
        return 0
    }

    private fun loadSharedPrefs() {
        val sharedPref = this.sharedPref!!
        mouseSensitivity = sharedPref.getFloat("mouse_sensitivity_multiplier", 1f)
        scrollSpeed = sharedPref.getFloat("scroll_speed_multiplier", 1f)
        ctrlMouseWheelZoom = sharedPref.getBoolean("ctrl_mouse_wheel_zoom", false)
        ctrlDragMouseGesture = sharedPref.getBoolean("ctrl_drag_mouse_gesture", true)
        mouseAimToggle = sharedPref.getBoolean("mouse_aim_shortcut_toggle", true)
        disableAutoProfiling = sharedPref.getBoolean("disable_auto_profile", true)
        useShizuku = sharedPref.getBoolean("use_shizuku", false)
        editorOverlay = sharedPref.getBoolean("editor_overlay", false)

        showControls = sharedPref.getBoolean("show_controls", false)
        showControlsOpacity = sharedPref.getFloat("show_controls_opacity", 0.2f)

        launchEditorShortcutKey = sharedPref.getString("launch_editor_shortcut", launchEditorShortcutKey)!!
        pauseResumeShortcutKey = sharedPref.getString("pause_resume_shortcut", pauseResumeShortcutKey)!!
        switchProfileShortcutKey = sharedPref.getString("switch_profile_shortcut", switchProfileShortcutKey)!!
        blockKeyboardShortcutKey = sharedPref.getString("block_keyboard_shortcut", blockKeyboardShortcutKey)!!
        mouseAimShortcutKey = sharedPref.getString("mouse_aim_shortcut", "")

        launchEditorShortcutKeyModifier = sharedPref.getString("launch_editor_shortcut_modifier", KEY_CTRL)
        pauseResumeShortcutKeyModifier = sharedPref.getString("pause_resume_shortcut_modifier", KEY_CTRL)
        switchProfileShortcutKeyModifier = sharedPref.getString("switch_profile_shortcut_modifier", KEY_CTRL)
        blockKeyboardShortcutKeyModifier = sharedPref.getString("block_keyboard_shortcut_modifier", KEY_CTRL)

        keyGraveMouseAim = sharedPref.getBoolean("key_grave_mouse_aim", true)
        rightClickMouseAim = sharedPref.getBoolean("right_click_mouse_aim", true)

        swipeDelayMs = sharedPref.getInt("swipe_delay_ms", 10)

        touchpadInputMode = sharedPref.getInt("touchpad_input_mode", TOUCHPAD_DISABLED)
        pointerMode = sharedPref.getInt("pointer_mode", POINTER_OVERLAY)

        floatingKeysSize = sharedPref.getFloat("floating_keys_size", 1f)
        touchJitter = sharedPref.getBoolean("touch_jitter", false)
        touchJitterSpread = sharedPref.getFloat("touch_jitter_spread", 15f)
        shiftTransitionMs = sharedPref.getInt("shift_transition_ms", 100)
        blockKeyboardInput = sharedPref.getBoolean("block_keyboard_input", false)
        inputMode = sharedPref.getInt("input_mode", if (useShizuku) MODE_SHIZUKU else MODE_ROOT)
    }

    fun applySharedPrefs() {
        sharedPref!!.edit().putFloat("mouse_sensitivity_multiplier", mouseSensitivity!!)
            .putFloat("scroll_speed_multiplier", scrollSpeed!!)
            .putBoolean("ctrl_mouse_wheel_zoom", ctrlMouseWheelZoom)
            .putBoolean("ctrl_drag_mouse_gesture", ctrlDragMouseGesture)
            .putBoolean("key_grave_mouse_aim", keyGraveMouseAim)
            .putBoolean("right_click_mouse_aim", rightClickMouseAim)
            .putBoolean("mouse_aim_shortcut_toggle", mouseAimToggle)
            .putBoolean("disable_auto_profile", disableAutoProfiling)
            .putBoolean("use_shizuku", useShizuku)
            .putBoolean("editor_overlay", editorOverlay)
            .putBoolean("show_controls", showControls)
            .putFloat("show_controls_opacity", showControlsOpacity)
            .putString("pause_resume_shortcut", pauseResumeShortcutKey)
            .putString("launch_editor_shortcut", launchEditorShortcutKey)
            .putString("switch_profile_shortcut", switchProfileShortcutKey)
            .putString("block_keyboard_shortcut", blockKeyboardShortcutKey)
            .putString("mouse_aim_shortcut", mouseAimShortcutKey)
            .putString("pause_resume_shortcut_modifier", pauseResumeShortcutKeyModifier)
            .putString("launch_editor_shortcut_modifier", launchEditorShortcutKeyModifier)
            .putString("switch_profile_shortcut_modifier", switchProfileShortcutKeyModifier)
            .putString("block_keyboard_shortcut_modifier", blockKeyboardShortcutKeyModifier)
            .putInt("touchpad_input_mode", touchpadInputMode)
            .putInt("swipe_delay_ms", swipeDelayMs)
            .putInt("shift_transition_ms", shiftTransitionMs)
            .putBoolean("block_keyboard_input", blockKeyboardInput)
            .putInt("pointer_mode", pointerMode)
            .putFloat("floating_keys_size", floatingKeysSize)
            .putBoolean("touch_jitter", touchJitter)
            .putFloat("touch_jitter_spread", touchJitterSpread)
            .putInt("input_mode", inputMode)
            .apply()
    }

    companion object {
        const val MODE_ROOT = 0
        const val MODE_SHIZUKU = 1

        const val KEY_CTRL = "Ctrl"
        const val KEY_ALT = "Alt"
        const val TOUCHPAD_DIRECT = 3
        const val TOUCHPAD_RELATIVE = 4
        const val TOUCHPAD_DISABLED = 5

        const val POINTER_SYSTEM = 6
        const val POINTER_OVERLAY = 7
        const val POINTER_COMBINED = 8

        @JvmField
        val CREATOR: Parcelable.Creator<KeymapConfig> = object : Parcelable.Creator<KeymapConfig> {
            override fun createFromParcel(parcel: Parcel): KeymapConfig {
                return KeymapConfig(parcel)
            }

            override fun newArray(size: Int): Array<KeymapConfig?> {
                return arrayOfNulls(size)
            }
        }
    }
}
