package xtr.keymapper.editor.component

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import xtr.keymapper.R
import xtr.keymapper.databinding.CameraBinding
import xtr.keymapper.databinding.CameraConfigBinding
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.editor.SettingsOverlay

class Camera(callback: EditorUiComponentCallback, context: Context, x: Float, y: Float) :
    EditorUiComponent(callback, context, x, y) {

    val cameraView: View

    // SoufianoDev: Helper Property To Safely Access Or Initialize Camera Element In Profile
    private val cameraElement: xtr.keymapper.keymap.element.Camera
        get() = callback.profile.camera ?: xtr.keymapper.keymap.element.Camera().also {
            callback.profile.camera = it
        }

    init {
        // SoufianoDev: Inflate Layout And Attach To Keys Container
        val binding = CameraBinding.inflate(layoutInflater, callback.keysContainerView, true)
        cameraView = binding.root

        
        binding.editButton.setOnClickListener { showSettingsDialog() }

        // SoufianoDev: Animate Component To Initial Position
        cameraView.animate()
            .x(x)
            .y(y)
            .setDuration(500)
            .start()

        // SoufianoDev: Handle Component Removal And View Release
        binding.closeButton.setOnClickListener {
            callback.removeComponent(this, cameraView)
        }

        // SoufianoDev: Ensure Camera Configuration Exists In The Selected Profile
        if (callback.profile.camera == null) {
            callback.profile.camera = xtr.keymapper.keymap.element.Camera()
        }
    }

    override fun getDataLine(): String {
        // SoufianoDev: Update Coordinates And Return Serialized Data String
        return cameraElement.apply {
            x = cameraView.x
            y = cameraView.y
        }.data
    }

    fun showSettingsDialog() {
        val binding = CameraConfigBinding.inflate(layoutInflater, null, false)
        val currentCamera = cameraElement

        // SoufianoDev: Load Current Values Into Configuration UI
        with(binding) {
            key.setText(currentCamera.triggerKeyCode.toString())
            toggleSwitch.isChecked = currentCamera.toggle
            sliderXSensitivity.value = currentCamera.xSensitivity
            sliderYSensitivity.value = currentCamera.ySensitivity
            key.setOnKeyListener { v, keyCode, event -> SettingsOverlay.onKey(v, keyCode, event) }
        }

        // SoufianoDev: Build And Configure Settings Dialog
        AlertDialog.Builder(context).apply {
            setView(binding.root)
            setPositiveButton(R.string.ok) { _, _ ->
                val keyText = binding.key.text.toString()

                // SoufianoDev: Save Updated Values Back To Camera Element
                /* Note: The Conditional Check 'If (keyText.isEmpty())'
                   Is A Proactive Safety Measure To Prevent An 'IndexOutOfBoundsException'.
                   Directly Accessing 'text[0]' On An Empty String Would Crash The App.
                   By Providing A Fallback Space Character We Ensure Runtime Stability
                   And Robust User Input Handling.
                */
                currentCamera.apply {
                    triggerKeyCode = if (keyText.isEmpty()) ' ' else keyText[0]
                    toggle = binding.toggleSwitch.isChecked
                    xSensitivity = binding.sliderXSensitivity.value
                    ySensitivity = binding.sliderYSensitivity.value
                }
            }
            setNegativeButton(R.string.cancel, null)
        }.create().apply {
            // SoufianoDev: Handle Window Type For Overlay Display Mode
            if (android.provider.Settings.canDrawOverlays(context)) {
                window?.setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                )
            }
            show()
        }
    }
}
