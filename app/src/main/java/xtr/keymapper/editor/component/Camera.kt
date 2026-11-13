package xtr.keymapper.editor.component

import android.content.Context
import android.os.Build
import android.view.WindowManager
import xtr.keymapper.floatingkeys.MovableFrameLayout
import androidx.appcompat.app.AlertDialog
import xtr.keymapper.R
import xtr.keymapper.databinding.CameraBinding
import xtr.keymapper.databinding.CameraConfigBinding
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.editor.onKeyboardKeyPress
import xtr.keymapper.editor.onMouseClick
import xtr.keymapper.editor.keyCodePrefix

class Camera(callback: EditorUiComponentCallback, context: Context, x: Float, y: Float) :
    EditorUiComponent(callback, context, x, y) {

    val cameraView: MovableFrameLayout

    private val cameraElement: xtr.keymapper.keymap.element.Camera
        get() = callback.profile.camera ?: xtr.keymapper.keymap.element.Camera().also {
            callback.profile.camera = it
        }

    init {
        val binding = CameraBinding.inflate(layoutInflater, callback.keysContainerView, true)
        cameraView = binding.root

        binding.editButton.setOnClickListener { showSettingsDialog() }

        cameraView.animateToPosition(x, y, 500L)

        binding.closeButton.setOnClickListener {
            callback.removeComponent(this, cameraView)
        }

        if (callback.profile.camera == null) {
            callback.profile.camera = xtr.keymapper.keymap.element.Camera()
        }
    }

    override fun toConfigLine(): String {
        cameraView.finishPendingPositionAnimation()
        return cameraElement.apply {
            x = cameraView.x
            y = cameraView.y
        }.data
    }

    fun showSettingsDialog() {
        val binding = CameraConfigBinding.inflate(layoutInflater, null, false)
        val currentCamera = cameraElement

        with(binding) {
            key.setText(currentCamera.triggerKeyCode.toString())
            toggleSwitch.isChecked = currentCamera.toggle
            sliderXSensitivity.value = currentCamera.xSensitivity
            sliderYSensitivity.value = currentCamera.ySensitivity
            key.setOnKeyListener(::onKeyboardKeyPress)
            key.setOnGenericMotionListener(::onMouseClick)
        }

        AlertDialog.Builder(context).apply {
            setView(binding.root)
            setPositiveButton(R.string.ok) { _, _ ->
                // Empty key text falls back to a space so keyCodePrefix never indexes text[0] on an empty string.
                currentCamera.apply {
                    triggerKeyCode = keyCodePrefix(binding.key)
                    toggle = binding.toggleSwitch.isChecked
                    xSensitivity = binding.sliderXSensitivity.value
                    ySensitivity = binding.sliderYSensitivity.value
                }
            }
            setNegativeButton(R.string.cancel, null)
        }.create().apply {
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
