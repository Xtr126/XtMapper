package xtr.keymapper.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import xtr.keymapper.R
import xtr.keymapper.databinding.ActivityImportExportBinding
import xtr.keymapper.databinding.ProfileRowItem2Binding
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ImportExportActivity : AppCompatActivity() {
    private var byteArrayOutputStream: ByteArrayOutputStream? = null
    private lateinit var binding: ActivityImportExportBinding

    // SoufianoDev: Modern Activity Result Launchers For API 30+
    private lateinit var createDocumentLauncher: ActivityResultLauncher<Intent>
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SoufianoDev: Initialize Launchers Conditionally Based On OS Version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            initModernLaunchers()
        }

        setupRecyclerView(false)

        binding.selectAllButton.setOnClickListener { setupRecyclerView(true) }
        binding.importButton.setOnClickListener { openZipFile() }
    }

    private fun initModernLaunchers() {
        // SoufianoDev: Register Callback For Saving Documents (Export)
        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                val bytes = byteArrayOutputStream?.toByteArray()
                // SoufianoDev: Smart Cast With Null Check
                if (uri != null && bytes != null) writeBytesToUri(uri, bytes)
            }
        }

        // SoufianoDev: Register Callback For Picking Documents (Import)
        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { importProfiles(it) }
            }
        }
    }

    private fun setupRecyclerView(allChecked: Boolean) {
        binding.profiles.adapter = ProfilesViewAdapter(this, allChecked)
    }

    private fun openZipFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        // SoufianoDev: Version-Based Branching For Activity Results
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openDocumentLauncher.launch(intent)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, READ_REQUEST_CODE)
        }
    }

    private fun exportProfiles(profileNames: List<String>) {
        if (profileNames.isEmpty()) return
        val keymapProfiles = KeymapProfiles(this)

        try {
            val baos = ByteArrayOutputStream()
            // SoufianoDev: Use Function For Auto Close
            ZipOutputStream(baos).use { z : ZipOutputStream ->
                profileNames.forEach { profileName ->
                    val zipEntry = ZipEntry(profileName)
                    z.putNextEntry(zipEntry)
                    keymapProfiles.sharedPref.getStringSet(profileName, emptySet())?.forEach { s ->
                        z.write(s.toByteArray())
                        z.write("\n".toByteArray())
                    }
                    z.closeEntry()
                }
            }
            byteArrayOutputStream = baos

            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                // SoufianoDev: Use Localized Default Filename From Strings.xml
                putExtra(Intent.EXTRA_TITLE, getString(R.string.default_backup_name))
                type = "application/zip"
                addCategory(Intent.CATEGORY_OPENABLE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createDocumentLauncher.launch(intent)
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, WRITE_REQUEST_CODE)
            }

        } catch (e: IOException) {
            Log.e("ImportExport", "Export process failed", e)
            // SoufianoDev: Snackbar With Localized Strings (Retry Action)
            Snackbar.make(binding.root, getString(R.string.error_export_failed), Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.action_retry)) { exportProfiles(profileNames) }
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        // SoufianoDev: Legacy Handler For Devices Below Android 11
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && resultCode == RESULT_OK && data != null) {
            when (requestCode) {
                WRITE_REQUEST_CODE -> {
                    val uri = data.data
                    val bytes = byteArrayOutputStream?.toByteArray()
                    // SoufianoDev: Smart Cast With Null Check
                    if (uri != null && bytes != null) writeBytesToUri(uri, bytes)
                }
                READ_REQUEST_CODE -> {
                    data.data?.let { importProfiles(it) }
                }
            }
        }
    }

    // SoufianoDev: Helper Method For Atomic Write
    private fun writeBytesToUri(uri: Uri, bytes: ByteArray) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
            }
            byteArrayOutputStream = null
            // SoufianoDev: Localized Success Message
            Snackbar.make(binding.root, getString(R.string.msg_backup_saved), Snackbar.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("ImportExport", "Failed to write bytes to URI", e)
            // SoufianoDev: Retry Writing From SnackBar Action (Localized UI)
            Snackbar.make(binding.root, getString(R.string.error_save_failed), Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.action_retry)) { writeBytesToUri(uri, bytes) }
                .show()
        }
    }

    @SuppressLint("UseKtx")
    private fun importProfiles(dataUri: Uri) {
        try {
            contentResolver.openInputStream(dataUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var zipEntry: ZipEntry? = zis.nextEntry
                    val profilesManager = KeymapProfiles(this)

                    while (zipEntry != null) {
                        // SoufianoDev: Safe Property Access Instead Of Non-Null Assertion
                        val profileName = zipEntry.name
                        val stringSet = mutableSetOf<String>()
                        // SoufianoDev: Reader Without Closing Inner Stream
                        val reader = BufferedReader(InputStreamReader(zis))

                        var line: String? = reader.readLine()
                        while (line != null) {
                            stringSet.add(line)
                            line = reader.readLine()
                        }

                        // SoufianoDev: Use KTX Extension For Cleaner SharedPreferences Editing
                        profilesManager.sharedPref.edit {
                            putStringSet(profileName, stringSet)
                        }

                        zis.closeEntry()
                        zipEntry = zis.nextEntry
                    }
                }
            }
            // SoufianoDev: Localized Import Success Notification
            Snackbar.make(binding.root, getString(R.string.msg_profiles_imported), Snackbar.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("ImportExport", "Import failed from URI", e)
            // SoufianoDev: Localized Fail/Retry Logic
            Snackbar.make(binding.root, getString(R.string.error_import_failed), Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.action_retry)) { importProfiles(dataUri) }
                .show()
        }
        setupRecyclerView(false)
    }

    internal inner class ProfilesViewAdapter(context: Context, private val allCardsChecked: Boolean) :
        RecyclerView.Adapter<ProfilesViewAdapter.ViewHolder>() {

        private val items = mutableListOf<RecyclerData>()
        private val selectedProfileNames = mutableListOf<String>()

        inner class ViewHolder(val itemBinding: ProfileRowItem2Binding) : RecyclerView.ViewHolder(itemBinding.root)

        init {
            val profilesManager = KeymapProfiles(context)
            profilesManager.allProfiles.forEach { (name, profile) ->
                // SoufianoDev: Safe Unwrapping Of Profile Name
                if (name != null) {
                    items.add(RecyclerData(profile, context, name))
                    if (allCardsChecked) selectedProfileNames.add(name)
                } else {
                    profilesManager.deleteProfile(null)
                }
            }
            binding.exportButton.setOnClickListener { exportProfiles(selectedProfileNames) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ViewHolder(ProfileRowItem2Binding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val data = items[position]
            // SoufianoDev: Scope Function To Group
            with(holder.itemBinding) {
                appIcon.setImageDrawable(data.icon)
                profileName.text = data.name
                profileText.text = data.description
                card.isChecked = allCardsChecked || selectedProfileNames.contains(data.name)

                card.setOnClickListener {
                    card.isChecked = !card.isChecked
                    if (card.isChecked) {
                        if (!selectedProfileNames.contains(data.name)) selectedProfileNames.add(data.name)
                    } else {
                        selectedProfileNames.remove(data.name)
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        private inner class RecyclerData(profile: KeymapProfile, context: Context, val name: String) {
            val description: String = KeymapProfiles(context).sharedPref
                .getStringSet(name, emptySet()).toString()

            // SoufianoDev: Modern Resource Access Pattern
            val icon: Drawable? = try {
                context.packageManager.getApplicationIcon(profile.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                AppCompatResources.getDrawable(context, R.mipmap.ic_launcher_foreground)
            }
        }
    }

    companion object {
        private const val WRITE_REQUEST_CODE = 101
        private const val READ_REQUEST_CODE = 102
    }
}