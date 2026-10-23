package xtr.keymapper.editor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import xtr.keymapper.R
import xtr.keymapper.databinding.MacroDialogLayoutBinding
import xtr.keymapper.databinding.MacroListItemBinding
import xtr.keymapper.databinding.TextFieldBinding
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.macro.Macro
import xtr.keymapper.macro.MacroSharedPreferences

class MacroDialog(
    private val context: Context,
    private val useOverlayFlag: Boolean,
    profile: KeymapProfile
) {

    private val macroSharedPreferences = MacroSharedPreferences(context)
    private val macroIdMap: HashMap<String, Macro> = profile.macroIdMap
    private var dialog: AlertDialog? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private var keyInFocus: TextView? = null

    fun show(l: View.OnClickListener) {
        // Inflate the custom layout using View Binding
        // View Binding for custom_dialog_layout
        val dialogBinding = MacroDialogLayoutBinding.inflate(LayoutInflater.from(context))

        // Set up RecyclerView
        val recyclerView = dialogBinding.recyclerView
        recyclerView.layoutManager = LinearLayoutManager(context)
        refreshRecyclerView(recyclerView)
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshRecyclerView(recyclerView) }
        macroSharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        // Set up FloatingActionButton
        val fab: FloatingActionButton = dialogBinding.fab
        fab.setOnClickListener(l)

        // Create the dialog
        val builder = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .setTitle(R.string.macro)
            .setOnDismissListener {
                macroSharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
                l.onClick(null)
            }

        dialog = builder.create()

        setOverlayFlagAndShowDialog(dialog!!)
    }

    private fun setOverlayFlagAndShowDialog(dialog: AlertDialog) {
        // Set window flags if required
        if (useOverlayFlag) {
            dialog.window!!.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            )
            dialog.window!!.setFormat(PixelFormat.TRANSLUCENT)
        }
        dialog.show()
    }

    private fun refreshRecyclerView(recyclerView: RecyclerView) {
        val dataSet = macroSharedPreferences.getMacroIds()
        val dataList = ArrayList(dataSet)
        val adapter = RecyclerViewAdapter(dataList)
        recyclerView.adapter = adapter
    }

    fun dismiss() {
        if (dialog != null && dialog!!.isShowing) {
            dialog!!.dismiss()
        }
    }

    fun onKey(key: String?) {
        // Incoming calls are not guaranteed to be executed on the main thread
        mHandler.post {
            keyInFocus?.text = key
        }
    }

    // RecyclerView Adapter with View Binding
    // Kotlin forbids nested classes inside inner classes, so ViewHolder is a sibling
    private inner class RecyclerViewAdapter(private val dataList: List<String>) :
        RecyclerView.Adapter<ViewHolder>() {

        // Inflate the custom layout using View Binding
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = MacroListItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // Bind data to the TextView
            val macroId = dataList[position]
            holder.binding.macroId.text = macroId
            holder.binding.editButton.setOnClickListener {
                val binding = TextFieldBinding.inflate(LayoutInflater.from(context))
                binding.root.setHint(R.string.macro)
                binding.editText.setText(macroId)

                val builder = MaterialAlertDialogBuilder(context)
                val dialog = builder.setPositiveButton(R.string.ok) { _, _ ->
                    macroSharedPreferences.renameMacro(macroId, binding.editText.text.toString())
                }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .setView(binding.root)
                    .create()
                setOverlayFlagAndShowDialog(dialog)
            }
            holder.binding.deleteButton.setOnClickListener { macroSharedPreferences.removeMacro(macroId) }

            holder.binding.toggle.setOnCheckedChangeListener { _, checked ->
                // Enable text field if checked
                if (checked) {
                    holder.binding.key.visibility = View.VISIBLE
                    macroIdMap[macroId] = Macro()
                } else {
                    holder.binding.key.visibility = View.GONE
                    macroIdMap.remove(macroId)
                }
            }
            val isMacroEnabled = macroIdMap.containsKey(macroId)
            holder.binding.toggle.isChecked = isMacroEnabled

            holder.binding.key.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val macro = macroIdMap[macroId]
                    if (macro != null) {
                        val key = KeyUtils.resolveKeyName(keyCode, event)
                        if (key != null) {
                            macro.triggerKey = key
                            (view as EditText).setText(key)
                        } else (view as EditText).text.clear()
                    }
                }
                true
            }

            holder.binding.key.setOnClickListener { v -> keyInFocus = v as TextView }
            val macro = macroIdMap[macroId]
            if (macro != null) holder.binding.key.setText(macro.triggerKey)
        }

        override fun getItemCount(): Int {
            return dataList.size
        }
    }

    private class ViewHolder(val binding: MacroListItemBinding) :
        RecyclerView.ViewHolder(binding.root)
}
