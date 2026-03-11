package xtr.keymapper.editor.component

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.R
import xtr.keymapper.databinding.CrosshairBinding
import xtr.keymapper.databinding.MouseAimConfigBinding
import xtr.keymapper.databinding.ResizableBinding
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.editor.EditorUiComponent
import xtr.keymapper.editor.EditorUiComponentCallback
import xtr.keymapper.floatingkeys.MovableFloatingActionKey
import xtr.keymapper.floatingkeys.MovableFrameLayout
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.element.MouseAimConfig

class Crosshair(callback: EditorUiComponentCallback, context: Context, x: Float, y: Float) :
    EditorUiComponent(callback, context, x, y) {

    private val crosshairView: MovableFrameLayout
    private val leftClick: MovableFloatingActionKey

    // SoufianoDev: Direct Access To Profile Via Extension Property
    private val profile: KeymapProfile
        get() = callback.profile

    // SoufianoDev: Secondary Constructor For Convenience
    constructor(mCallback: EditorUiComponentCallback, context: Context, mouseAimConfig: MouseAimConfig) :
            this(mCallback, context, mouseAimConfig.xCenter, mouseAimConfig.yCenter)

    init {
        // SoufianoDev: Ensure MouseAimConfig Is Initialized In Profile
        if (profile.mouseAimConfig == null) {
            profile.mouseAimConfig = MouseAimConfig()
        }

        val binding = CrosshairBinding.inflate(layoutInflater, callback.keysContainerView, true)
        crosshairView = binding.root

        // SoufianoDev: Setup Action Listeners Using 'with' For Clean Binding Access
        with(binding) {
            expandButton.setOnClickListener { view -> onExpandButtonClicked(view) }
            editButton.setOnClickListener { showMouseAimSettingsDialog() }
            closeButton.setOnClickListener {
                callback.removeComponent(this@Crosshair, crosshairView, leftClick.frameView)
            }
        }

        // SoufianoDev: Animate Crosshair To Target Position
        crosshairView.animate()
            .x(x)
            .y(y)
            .setDuration(500)
            .start()

        // SoufianoDev: Initialize And Add Left Click Floating Button
        leftClick = addLeftClick(profile.mouseAimConfig.xleftClick, profile.mouseAimConfig.yleftClick)
    }

    override fun getDataLine(): String {
        // SoufianoDev: Update Configuration Data With Current View Coordinates
        return profile.mouseAimConfig.apply {
            setCenterXY(crosshairView)
            setLeftClickXY(leftClick)
        }.data
    }

    private fun onExpandButtonClicked(view: View) {
        val options = arrayOf<CharSequence>("Limit to specified area", "Allow moving pointer out of screen")

        MaterialAlertDialogBuilder(context)
            .setTitle("Adjust bounds")
            .setItems(options) { _, which ->
                profile.mouseAimConfig.apply {
                    width = 0f
                    height = 0f
                    if (which == 0) {
                        limitedBounds = true
                        ResizableArea() // SoufianoDev: Trigger Resizable Overlay
                    } else {
                        limitedBounds = false
                    }
                }
            }
            .create()
            .apply {
                setupOverlayWindowType()
                show()
            }
    }

    private fun addLeftClick(x: Float, y: Float): MovableFloatingActionKey {
        // SoufianoDev: Create And Configure The Left Click Action Button
        return MovableFloatingActionKey(context, { }, callback.keysContainerView).apply {
            frameView.setBackgroundResource(R.drawable.ic_baseline_mouse_36)
            setText(R.string.left_click)
            frameView.animate()
                .x(x)
                .y(y)
                .setDuration(500)
                .start()
        }
    }

    fun showMouseAimSettingsDialog() {
        val keymapConfig = KeymapConfig(context)
        val binding = MouseAimConfigBinding.inflate(layoutInflater, null, false)

        // SoufianoDev: Map Saved Preferences To UI Components
        with(binding) {
            rightClickCheckbox.isChecked = keymapConfig.rightClickMouseAim
            graveKeyCheckbox.isChecked = keymapConfig.keyGraveMouseAim
            applyNonLinearScalingCheckbox.isChecked = profile.mouseAimConfig.applyNonLinearScaling
            sliderXSensitivity.value = profile.mouseAimConfig.xSensitivity
            sliderYSensitivity.value = profile.mouseAimConfig.ySensitivity
        }

        AlertDialog.Builder(context)
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                // SoufianoDev: Persist Updated Settings Back To Config And Profile
                keymapConfig.apply {
                    rightClickMouseAim = binding.rightClickCheckbox.isChecked
                    keyGraveMouseAim = binding.graveKeyCheckbox.isChecked
                    applySharedPrefs()
                }

                profile.mouseAimConfig.apply {
                    applyNonLinearScaling = binding.applyNonLinearScalingCheckbox.isChecked
                    xSensitivity = binding.sliderXSensitivity.value
                    ySensitivity = binding.sliderYSensitivity.value
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .apply {
                setupOverlayWindowType()
                show()
            }
    }

    // SoufianoDev: Extension Helper To Set Window Type Safely
    private fun AlertDialog.setupOverlayWindowType() {
        if (callback.isOverlayOpen) {
            @Suppress("DEPRECATION")
            window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            )
        }
    }

    fun animateLeftClick(x: Float, y: Float) {
        leftClick.frameView.animate()
            .x(x)
            .y(y)
            .setDuration(500)
            .start()
    }

    // SoufianoDev: Inner Class To Handle Interactive Area Resizing
    inner class ResizableArea : View.OnTouchListener, View.OnClickListener {
        private val rootView: ViewGroup
        private var defaultPivotX = 0f
        private var defaultPivotY = 0f

        init {
            val resizableBinding = ResizableBinding.inflate(layoutInflater, callback.keysContainerView, true)
            rootView = resizableBinding.root
            resizableBinding.dragHandle.setOnTouchListener(this)
            resizableBinding.saveButton.setOnClickListener(this)
            moveView()
        }

        private fun updatePivotAndAdjustPosition() {
            // SoufianoDev: Keep View Centered During Resizing Actions
            if (defaultPivotX > 0) {
                val deltaX = rootView.pivotX - defaultPivotX
                val deltaY = rootView.pivotY - defaultPivotY
                rootView.x -= deltaX
                rootView.y -= deltaY
            }
            defaultPivotX = rootView.pivotX
            defaultPivotY = rootView.pivotY
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_MOVE) {
                EditorUI.resizeView(rootView, event.x.toInt(), event.y.toInt())
                updatePivotAndAdjustPosition()
            } else {
                v.performClick()
            }
            return true
        }

        override fun onClick(v: View) {
            // SoufianoDev: Finalize Bounds And Update Crosshair Position
            val centerX = rootView.x + rootView.pivotX
            val centerY = rootView.y + rootView.pivotY

            crosshairView.apply {
                x = centerX
                y = centerY
            }

            profile.mouseAimConfig.apply {
                width = rootView.pivotX
                height = rootView.pivotY
            }

            callback.keysContainerView.removeView(rootView)
            rootView.invalidate()
        }

        private fun moveView() {
            rootView.x = crosshairView.x - crosshairView.width
            rootView.y = crosshairView.y - crosshairView.height
        }
    }
}