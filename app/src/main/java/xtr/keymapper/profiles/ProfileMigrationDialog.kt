package xtr.keymapper.profiles

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.R
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.keymap.ProfileKeyMigration
import xtr.keymapper.keymap.ProfileKeySemantics
import xtr.keymapper.keymap.ProfileShortcut
import xtr.keymapper.keymap.ProfileShortcuts

internal object ProfileMigrationDialog {
    fun show(context: Context, profileName: String, onCopied: (String) -> Unit) {
        val profiles = KeymapProfiles(context)
        val source = profiles.sharedPref.getStringSet(profileName, null)?.toSet() ?: return
        val profile = KeymapProfiles.parse(source)
        val shortcuts = profile.waydroidShortcuts ?: ProfileShortcuts.fromSettings(KeymapConfig(context))
        val migration = ProfileKeyMigration(source, shortcuts)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, padding)
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            isFocusableInTouchMode = true
        }
        val scroll = ScrollView(context).apply { addView(content) }
        val name = EditText(context).apply {
            setSingleLine()
            setHint(R.string.profile_name)
            setText(context.getString(R.string.waydroid_profile_copy_name, profileName))
        }
        val status = TextView(context).apply { setText(R.string.waydroid_profile_copy_instructions) }
        content.addView(name)
        content.addView(status)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.waydroid_profile_copy_title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.waydroid_profile_save_copy, null)
            .create()
        var activeIndex: Int? = null
        var finishBinding: (() -> Unit)? = null
        migration.bindings.forEachIndexed { index, binding ->
            Button(context).apply {
                isAllCaps = false
                val label = context.getString(binding.title())
                fun showBinding() {
                    text = context.getString(
                        R.string.waydroid_profile_binding, label, binding.original,
                        migration.replacement(index) ?: context.getString(R.string.waydroid_profile_unresolved)
                    )
                }
                showBinding()
                setOnClickListener {
                    finishBinding?.invoke()
                    finishBinding = ::showBinding
                    activeIndex = index
                    requestFocus()
                    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
                    text = context.getString(R.string.waydroid_profile_press_key, label, binding.original)
                }
                content.addView(this)
            }
        }
        dialog.setOnKeyListener { _, _, event ->
            val index = activeIndex ?: return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
            val key = ProfileKeySemantics.physicalKey(event.scanCode)
            if (key == null || !migration.bind(index, key)) {
                Toast.makeText(context, R.string.waydroid_profile_no_scan_code, Toast.LENGTH_SHORT).show()
                return@setOnKeyListener true
            }
            finishBinding?.invoke()
            finishBinding = null
            activeIndex = null
            true
        }
        dialog.setOnShowListener {
            content.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val copy = migration.buildCopy()
                if (copy == null) {
                    Toast.makeText(context, R.string.waydroid_profile_incomplete, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val copyName = name.text.toString().trim()
                if (!profiles.saveMigrationCopy(profileName, source, copyName, copy)) {
                    Toast.makeText(context, R.string.waydroid_profile_copy_failed, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onCopied(copyName)
            }
        }
        dialog.show()
    }

    private fun ProfileKeyMigration.Binding.title(): Int {
        return when (kind) {
            ProfileKeyMigration.Kind.KEY -> R.string.waydroid_profile_key
            ProfileKeyMigration.Kind.DPAD_UP -> R.string.waydroid_profile_dpad_up
            ProfileKeyMigration.Kind.DPAD_DOWN -> R.string.waydroid_profile_dpad_down
            ProfileKeyMigration.Kind.DPAD_LEFT -> R.string.waydroid_profile_dpad_left
            ProfileKeyMigration.Kind.DPAD_RIGHT -> R.string.waydroid_profile_dpad_right
            ProfileKeyMigration.Kind.SWIPE_START -> R.string.waydroid_profile_swipe_start
            ProfileKeyMigration.Kind.SWIPE_END -> R.string.waydroid_profile_swipe_end
            ProfileKeyMigration.Kind.SHIFT_START -> R.string.waydroid_profile_shift_start
            ProfileKeyMigration.Kind.SHIFT_END -> R.string.waydroid_profile_shift_end
            ProfileKeyMigration.Kind.MACRO -> R.string.waydroid_profile_macro
            ProfileKeyMigration.Kind.CAMERA -> R.string.waydroid_profile_camera
            ProfileKeyMigration.Kind.SHORTCUT -> when (checkNotNull(shortcut)) {
                ProfileShortcut.PAUSE -> R.string.waydroid_profile_pause_shortcut
                ProfileShortcut.EDITOR -> R.string.waydroid_profile_editor_shortcut
                ProfileShortcut.SWITCH -> R.string.waydroid_profile_switch_shortcut
                ProfileShortcut.BLOCK -> R.string.waydroid_profile_block_shortcut
                ProfileShortcut.AIM -> R.string.waydroid_profile_aim_shortcut
                ProfileShortcut.GRAVE_AIM -> R.string.waydroid_profile_grave_shortcut
            }
        }
    }
}
