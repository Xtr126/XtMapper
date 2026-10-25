package xtr.keymapper.activity

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import xtr.keymapper.BuildConfig
import xtr.keymapper.Environment
import xtr.keymapper.GameKeyMapperBridge
import xtr.keymapper.IRemoteService
import xtr.keymapper.R
import xtr.keymapper.Server
import xtr.keymapper.TouchPointer
import xtr.keymapper.databinding.TextFieldBinding
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.log.XtLogger
import xtr.keymapper.profiles.ProfileSelector
import xtr.keymapper.profiles.ProfilesApps
import xtr.keymapper.server.RemoteServiceHelper

class MainActivity : AppCompatActivity() {

    var pointerOverlay: TouchPointer? = null
    private var selectedProfileName: String? = null
    private var composeServiceActive = false
    private var isServiceBound = false
    private var displaySelector: DisplaySelector? = null
    private var startedFromShell: Boolean? = null
    private var shellInitStartupPending = false

    companion object {
        const val SHELL_INIT = "shell"
        const val SHELL_SERVICE_BINDER = "xtr.keymapper.extra.SHELL_SERVICE_BINDER"

        init {
            // Set settings before the main shell can be created
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }

        fun checkOverlayPermission(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launch-context signal: applied only when the extra is present so the
        // server-crash relay (data= only) never reclassifies the process.
        if (intent.hasExtra(Environment.INTENT_EXTRA)) {
            Environment.launchSignal = intent.getBooleanExtra(Environment.INTENT_EXTRA, false)
            XtLogger.info("MainActivity", "launch signal applied: environment=${Environment.name}")
        }
        Log.i("xtmapper-startup", "MainActivity.onCreate: shellMarker=${intent.getStringExtra("data") == SHELL_INIT} binderPresent=${intent.extras?.getBinder(SHELL_SERVICE_BINDER) != null}")
        intent.extras?.getBinder(SHELL_SERVICE_BINDER)?.let {
            RemoteServiceHelper.setShellServiceBinder(it)
        }
        if (startedFromShell == null) startedFromShell = isStartedWithShell()

        GameKeyMapperBridge.installGameKeyMapperContent(this)

        val keymapConfig = KeymapConfig(this)
        val profiles = KeymapProfiles(this)
        val isShellInit = isShellInitialization()
        shellInitStartupPending = isShellInit
        selectedProfileName = profiles.getStartupProfileName(isShellInit)
        Log.i("xtmapper-startup", "MainActivity: shellInit=$isShellInit profile=$selectedProfileName shizuku=${keymapConfig.isShizukuMode}")
        TouchPointer.currentSelectedProfile = selectedProfileName

        /*
         * If user has enabled "Use Shizuku" but this activity was started from shell (waydroid or adb)
         * Then reset "Use Shizuku" setting to false since it crashes the app
         */
        if (startedFromShell == true && keymapConfig.isShizukuMode) {
            keymapConfig.useShizuku = false
            keymapConfig.inputMode = KeymapConfig.MODE_ROOT
            keymapConfig.applySharedPrefs()
        }

        RemoteServiceHelper.useShizuku = keymapConfig.isShizukuMode
        Server.setupServer(this, mCallback)

        if (startedFromShell == false) handleActivationFlow(keymapConfig)

        displaySelector = DisplaySelector(this).register { displayId -> startPointer(displayId) }
        RemoteServiceHelper.runIfActive(this) {
            runOnUiThread { composeServiceActive = true }
        }

        if (isShellInit) {
            startPointer()
        }
    }

    private fun handleActivationFlow(keymapConfig: KeymapConfig) {
        if (!RemoteServiceHelper.useShizuku) {
            Shell.getShell {
                // Ask user to enable shizuku if shizuku app detected
                if (Shizuku.pingBinder() || packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null) {
                    showAlertDialog(R.string.detected_shizuku, R.string.use_shizuku_for_activation, { _, _ ->
                        RemoteServiceHelper.useShizuku = true
                        keymapConfig.useShizuku = true
                        keymapConfig.applySharedPrefs()
                        alertShizukuNotAuthorized()
                    }, R.string.ok)
                } else if (Shell.isAppGrantedRoot() == false) {
                    alertRootAccessNotFound()
                }
            }
        } else {
            when {
                !isShizukuInstalled() -> alertShizukuNotInstalled()
                !Shizuku.pingBinder() -> alertShizukuNotRunning()
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> alertShizukuNotAuthorized()
            }
        }
    }

    /**
     * Check if this activity was started with am shell command
     * Also handles crash report from server and shows a dialog with crash log
     * @return true if this activity was started with am shell command
     */
    private fun isStartedWithShell(): Boolean {
        val data = intent.getStringExtra("data") ?: return false
        if (data != SHELL_INIT) {
            // Crash report
            MaterialAlertDialogBuilder(this).setTitle("Server crashed")
                .setMessage(data)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        return true
    }

    private fun isShellInitialization(): Boolean {
        return startedFromShell == true && intent.getStringExtra("data") == SHELL_INIT
    }

    private fun launchSettings() {
        val context: Context = this
        val remoteServiceCallback = object : RemoteServiceHelper.RootRemoteServiceCallback {
            var editorUi: EditorUI? = EditorUI(context, { editorUi = null }, null, EditorUI.START_SETTINGS, null)

            override fun onConnection(service: IRemoteService?) {
                if (service != null && editorUi != null) try {
                    editorUi!!.registerOnKeyEventListener(service)
                } catch (e: RemoteException) {
                    Log.e("MainActivity", e.message, e)
                    editorUi!!.unregisterOnKeyEventListener()
                }
            }
        }
        remoteServiceCallback.editorUi?.openSettings()
        RemoteServiceHelper.getInstance(context, remoteServiceCallback)
    }

    private fun activateSelectedProfile() {
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok)
        } else {
            if (isServiceBound) pointerOverlay?.launchProfile(selectedProfileName)
            else startPointer()
        }
    }

    fun startPointer() {
        val displayManager = getSystemService(DisplayManager::class.java)

        if (displayManager.displays.size > 1) {
            displaySelector?.launch()
        } else {
            startPointer(null)
        }
    }

    private fun startPointer(displayId: Int?) {
        val keymapConfig = KeymapConfig(this)
        if (keymapConfig.isShizukuMode) {
            when {
                !isShizukuInstalled() -> {
                    alertShizukuNotInstalled()
                    return
                }
                !Shizuku.pingBinder() -> {
                    alertShizukuNotRunning()
                    return
                }
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> {
                    alertShizukuNotAuthorized()
                    return
                }
            }
        } else if (startedFromShell == false && Shell.isAppGrantedRoot() == false) {
            alertRootAccessAndExit()
            return
        }

        checkOverlayPermission(this)
        val profiles = KeymapProfiles(this)
        if (selectedProfileName == null) {
            selectedProfileName = profiles.getLastSelectedProfile()
        }
        TouchPointer.currentSelectedProfile = selectedProfileName
        // Start service with selected profile if display on top permission is granted
        if (Settings.canDrawOverlays(this)) {
            profiles.recordStartupProfileSelection(selectedProfileName, shellInitStartupPending)
            val intent = Intent(this, TouchPointer::class.java).apply {
                putExtra(EditorActivity.PROFILE_NAME, selectedProfileName)
                if (displayId != null) putExtra(TouchPointer.DISPLAY_ID, displayId)
            }
            isServiceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            ContextCompat.startForegroundService(this, intent)
            shellInitStartupPending = false
            setButtonState(false)
            requestNotificationPermission()
        }
    }

    private fun setButtonState(start: Boolean) {
        composeServiceActive = !start
    }

    override fun onResume() {
        super.onResume()
        composeServiceActive = TouchPointer.isRunning
    }

    fun stopPointer() {
        TouchPointer.activeInstance?.stopKeymapper()
        unbindTouchPointer()
        val intent = Intent(this, TouchPointer::class.java).apply {
            action = TouchPointer.ACTION_STOP
        }
        stopService(intent)
        setButtonState(true)
    }

    private fun unbindTouchPointer() {
        pointerOverlay?.activityCallback = null
        pointerOverlay = null
        if (isServiceBound) {
            unbindService(connection)
            isServiceBound = false
        }
    }

    private fun startEditor() {
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok)
        } else {
            val intent = Intent(this, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfileName)
            startActivity(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED)
                requestPermissions(arrayOf(POST_NOTIFICATIONS), 0)
        }
    }

    fun alertRootAccessNotFound() {
        showAlertDialog(R.string.root_not_found_title, R.string.root_not_found_message, { _, _ ->
            val launchIntent = packageManager.getLaunchIntentForPackage("me.weishu.kernelsu")
            if (launchIntent != null) {
                startActivity(launchIntent)
                System.exit(0)
            }
        }, R.string.ok)
    }

    fun alertRootAccessAndExit() {
        showAlertDialog(R.string.root_no_privileges_title, R.string.root_no_privileges_message, { _, _ ->
            finishAffinity()
            System.exit(0)
        }, R.string.ok)
    }

    private fun alertShizukuNotAuthorized() {
        if (!isShizukuInstalled()) {
            alertShizukuNotInstalled()
            return
        }
        if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
        showAlertDialog(R.string.shizuku_not_authorized_title, R.string.shizuku_not_authorized_message, { _, _ -> launchShizukuAndExit() }, R.string.ok)
    }

    private fun alertShizukuNotRunning() {
        if (!isShizukuInstalled()) {
            alertShizukuNotInstalled()
            return
        }
        showAlertDialog(R.string.shizuku_not_running_title, R.string.shizuku_not_running_message, { _, _ -> launchShizukuAndExit() }, R.string.start)
    }

    private fun alertShizukuNotInstalled() {
        showAlertDialog(R.string.shizuku_not_installed_title, R.string.shizuku_not_installed_message, null, R.string.ok)
    }

    fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun launchShizukuAndExit() {
        val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launchIntent != null) {
            startActivity(launchIntent)
            System.exit(0)
        } else {
            alertShizukuNotInstalled()
        }
    }

    private fun showAlertDialog(@StringRes titleId: Int, @StringRes messageId: Int, listener: DialogInterface.OnClickListener?, @StringRes ok: Int) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(titleId)
            .setMessage(messageId)
            .setPositiveButton(ok, listener)
            .setNegativeButton(R.string.cancel, null)
        runOnUiThread { builder.create().show() }
    }

    // ============ COMPOSE UI BRIDGE ============

    fun isComposeServiceActive(): Boolean = composeServiceActive || TouchPointer.isRunning

    fun selectedProfileNameForCompose(): String? = selectedProfileName

    fun toggleServiceFromCompose() {
        if (isComposeServiceActive()) stopPointer() else startPointer()
    }

    fun fixPermissionFromCompose() {
        val config = KeymapConfig(this)
        if (config.isShizukuMode && !isShizukuInstalled()) {
            alertShizukuNotInstalled()
        } else if (config.isShizukuMode && (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PERMISSION_GRANTED)) {
            if (Shizuku.pingBinder()) alertShizukuNotAuthorized()
            else alertShizukuNotRunning()
        } else if (!Settings.canDrawOverlays(this)) {
            checkOverlayPermission(this)
        }
    }

    fun toggleInputModeFromCompose() {
        val config = KeymapConfig(this)
        val nextMode = if (config.inputMode == KeymapConfig.MODE_ROOT) {
            KeymapConfig.MODE_SHIZUKU
        } else {
            KeymapConfig.MODE_ROOT
        }
        config.inputMode = nextMode
        config.useShizuku = (nextMode == KeymapConfig.MODE_SHIZUKU)
        RemoteServiceHelper.useShizuku = config.useShizuku
        config.applySharedPrefs()
        val modeStr = if (nextMode == KeymapConfig.MODE_SHIZUKU) "Shizuku (ADB)" else "Root"
        Toast.makeText(this, getString(R.string.input_mode_changed, modeStr), Toast.LENGTH_SHORT).show()
        if (config.isShizukuMode) {
            if (!isShizukuInstalled()) alertShizukuNotInstalled()
            else if (!Shizuku.pingBinder()) alertShizukuNotRunning()
            else if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) alertShizukuNotAuthorized()
        }
        if (isServiceBound && pointerOverlay != null) {
            pointerOverlay?.launchProfile(selectedProfileName)
        }
    }

    fun testInputFromCompose() {
        val config = KeymapConfig(this)
        if (config.isShizukuMode) {
            when {
                !isShizukuInstalled() -> {
                    alertShizukuNotInstalled()
                    return
                }
                !Shizuku.pingBinder() -> {
                    alertShizukuNotRunning()
                    return
                }
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> {
                    alertShizukuNotAuthorized()
                    return
                }
            }
        } else if (Shell.isAppGrantedRoot() == false) {
            Toast.makeText(this, R.string.test_input_root_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        if (!isComposeServiceActive()) {
            Toast.makeText(this, R.string.test_input_service_inactive, Toast.LENGTH_LONG).show()
            return
        }
        val modeName = if (config.isShizukuMode) "Shizuku (ADB)" else "Root"
        Toast.makeText(this, getString(R.string.test_input_ready, modeName), Toast.LENGTH_SHORT).show()
    }

    fun selectProfileFromCompose(profileName: String) {
        onProfileSelected(profileName)
    }

    fun activateSelectedProfileFromCompose() {
        activateSelectedProfile()
    }

    fun startEditorFromCompose() {
        startEditor()
    }

    fun renameProfileFromCompose(profileName: String, onComplete: Runnable?) {
        val context: Context = this
        val field = TextFieldBinding.inflate(layoutInflater)
        field.root.hint = getString(R.string.profile_name)
        field.editText.setText(profileName)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_alert_add_profile)
            .setView(field.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val renamed = field.editText.text.toString()
                val profiles = KeymapProfiles(context)
                profiles.renameProfile(profileName, renamed)
                if (selectedProfileName == profileName) {
                    selectedProfileName = renamed
                    TouchPointer.currentSelectedProfile = renamed
                }
                if (onComplete != null) runOnUiThread(onComplete)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun changeProfileAppFromCompose(profileName: String, onComplete: Runnable?) {
        val context: Context = this
        val builder = MaterialAlertDialogBuilder(context)
        ProfilesApps.asyncLoadAppsAndThen(context, builder) { p, adapter, loadingDialog ->
            p.binding.appsGrid.adapter = adapter
            loadingDialog.dismiss()

            val dialog = builder.setView(p.appsView).show()
            p.setListener { packageName ->
                KeymapProfiles(context).setProfilePackageName(profileName, packageName)
                p.onDestroyView()
                dialog.dismiss()
                if (onComplete != null) runOnUiThread(onComplete)
            }
        }
    }

    fun deleteProfileFromCompose(profileName: String?) {
        val profiles = KeymapProfiles(this)
        profiles.deleteProfile(profileName)
        if (profileName != null && profileName == selectedProfileName) {
            selectedProfileName = profiles.getLastSelectedProfile()
            TouchPointer.currentSelectedProfile = selectedProfileName
        }
    }

    fun stopPointerFromCompose() {
        stopPointer()
    }

    fun openHelpFromCompose() {
        startActivity(Intent(this, InfoActivity::class.java))
    }

    fun createNewProfileFromCompose(onComplete: Runnable?) {
        ProfileSelector.createNewProfile(this) { profileName ->
            selectedProfileName = profileName
            val profiles = KeymapProfiles(this)
            profiles.setLastSelectedProfile(profileName)
            TouchPointer.currentSelectedProfile = profileName
            if (onComplete != null) runOnUiThread(onComplete)
        }
    }

    fun openExportImportFromCompose() {
        startActivity(Intent(this, ImportExportActivity::class.java))
    }

    fun toggleOverlayFromCompose() {
        if (!Settings.canDrawOverlays(this)) checkOverlayPermission(this)
    }

    fun openSettingsFromCompose() {
        launchSettings()
    }

    fun openLogsFromCompose() {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val needsAccess = supported && !android.os.Environment.isExternalStorageManager()
        val message = when {
            !supported -> R.string.logs_unsupported
            needsAccess -> R.string.logs_access_needed
            XtLogger.isFileSinkAvailable -> R.string.logs_active
            else -> R.string.logs_unavailable
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs)
            .setMessage(getString(message) + "\n\n" + XtLogger.canonicalDirectory().absolutePath)
            .setNegativeButton(R.string.ok, null)
            .apply {
                if (needsAccess) setPositiveButton(R.string.logs_grant_access) { _, _ ->
                    openLogAccessSettings()
                }
            }
            .show()
    }

    private fun openLogAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Some OEMs only expose the general All-files access page.
            } catch (_: SecurityException) {
                // Try the general page if the app-specific page is restricted.
            }
        }
        Toast.makeText(this, R.string.logs_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted All-files access in Settings just now;
        // finish the pending file-logging setup without a restart. Guarded:
        // the check is an API 30 call (crashes every resume below Android 11).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()
        ) XtLogger.enableFileLogging()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindTouchPointer()
    }

    fun onProfileSelected(profileName: String) {
        selectedProfileName = profileName
        val profiles = KeymapProfiles(this)
        profiles.setLastSelectedProfile(profileName)
        TouchPointer.currentSelectedProfile = profileName
        val p = profiles.getProfile(profileName, false)
        if (!p.packageName.isNullOrEmpty()) {
            profiles.setDesignatedProfileForApp(p.packageName, profileName)
        }
    }

    private val mCallback = object : TouchPointer.MainActivityCallback {
        override fun updateCmdView1(line: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, line, Toast.LENGTH_SHORT).show() }
        }

        override fun stopPointer() {
            this@MainActivity.stopPointer()
        }
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            isServiceBound = true
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            val binder = service as TouchPointer.TouchPointerBinder
            pointerOverlay = binder.service.apply {
                activityCallback = this@MainActivity.mCallback
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
        }
    }
}
