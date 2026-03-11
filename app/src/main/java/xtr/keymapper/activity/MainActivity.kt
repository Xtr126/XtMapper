package xtr.keymapper.activity

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.ColorStateList
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import xtr.keymapper.BuildConfig
import xtr.keymapper.IRemoteService
import xtr.keymapper.R
import xtr.keymapper.Server
import xtr.keymapper.TouchPointer
import xtr.keymapper.databinding.ActivityMainBinding
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.profiles.ProfilesViewAdapter
import xtr.keymapper.server.RemoteServiceHelper

class MainActivity : AppCompatActivity(), ProfilesViewAdapter.ProfileSelectedCallback {

    lateinit var binding: ActivityMainBinding
    var pointerOverlay: TouchPointer? = null
    private var defaultTint: ColorStateList? = null
    private var selectedProfileName: String? = null
    private var isServiceBound = false
    private var displaySelector: DisplaySelector? = null
    private var startedFromShell: Boolean? = null

    companion object {
        const val SHELL_INIT = "shell"

        init {
            // SoufianoDev: Set Settings Before Main Shell Creation
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                    Shell.Builder.create()
                            .setFlags(Shell.FLAG_REDIRECT_STDERR)
                            .setTimeout(10)
            )
        }

        // SoufianoDev: fix checkOverlayPermission(android.content.@org.jetbrains.annotations.NotNull Context)' has private access in 'xtr.keymapper.activity.MainActivity'
        @JvmStatic
        fun checkOverlayPermission(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                // SoufianoDev: Redirect User To Device Overlay Settings
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SoufianoDev: Handle Intent Data For Shell Start
        if (startedFromShell == null) startedFromShell = isStartedWithShell()

        val keymapConfig = KeymapConfig(this)

        // SoufianoDev: Reset Shizuku If Started From Shell To Prevent Crashes
        if (startedFromShell == true && keymapConfig.useShizuku) {
            keymapConfig.useShizuku = false
            keymapConfig.applySharedPrefs()
        }

        RemoteServiceHelper.useShizuku = keymapConfig.useShizuku
        Server.setupServer(this, mCallback)

        // SoufianoDev: Conditional Activation Prompts
        if (startedFromShell == false) {
            handleActivationFlow(keymapConfig)
        }

        displaySelector = DisplaySelector(this).register { startPointer() }
        setupButtons()

        if (startedFromShell == true && intent.getStringExtra("data") == SHELL_INIT) {
            startPointer()
        }
    }

    private fun handleActivationFlow(keymapConfig: KeymapConfig) {
        if (!RemoteServiceHelper.useShizuku) {
            Shell.getShell {
                // SoufianoDev: Prompt To Enable Shizuku If App Detected
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
                !Shizuku.pingBinder() -> alertShizukuNotRunning()
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> alertShizukuNotAuthorized()
            }
        }
    }

    private fun isStartedWithShell(): Boolean {
        val data = intent.getStringExtra("data") ?: return false
        if (data != SHELL_INIT) {
            // SoufianoDev: Display Crash Report From Server
            MaterialAlertDialogBuilder(this)
                    .setTitle("Server crashed")
                    .setMessage(data)
                    .setPositiveButton(R.string.ok, null)
                    .show()
        }
        return true
    }

    private fun setupButtons() {
        with(binding.controls) {
            defaultTint = launchApp.backgroundTintList
            launchApp.setOnClickListener { launchApp() }
            startPointer.setOnClickListener { startPointer() }
            startEditor.setOnClickListener { startEditor() }
            configButton.setOnClickListener { launchSettings() }
            aboutButton.setOnClickListener {
                startActivity(Intent(this@MainActivity, InfoActivity::class.java))
            }
            importExportButton.setOnClickListener {
                startActivity(Intent(this@MainActivity, ImportExportActivity::class.java))
            }
        }

        RemoteServiceHelper.runIfActive(this) {
            runOnUiThread { setButtonState(false) }
        }
    }

    private fun launchSettings() {
        val remoteServiceCallback = object : RemoteServiceHelper.RootRemoteServiceCallback {
            private var editorUi: EditorUI? = EditorUI(this@MainActivity, { editorUi = null }, null, EditorUI.START_SETTINGS)

            override fun onConnection(remoteService: IRemoteService?) {
                remoteService?.let { service ->
                        editorUi?.let { ui ->
                    try {
                        ui.registerOnKeyEventListener(service)
                    } catch (e: RemoteException) {
                        Log.e("MainActivity", e.message ?: "Remote Error", e)
                        ui.unregisterOnKeyEventListener()
                    }
                }
                }
            }

            init {
                editorUi?.openSettings()
            }
        }
        RemoteServiceHelper.getInstance(this, remoteServiceCallback)
    }

    private fun launchApp() {
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok)
        } else {
            if (isServiceBound) pointerOverlay?.launchProfile(selectedProfileName!!)
            else startPointer()
        }
    }

    fun startPointer() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        if (displayManager.displays.size > 1) {
            displaySelector?.launch()
        } else {
            startPointer(null)
        }
    }

    private fun startPointer(displayId: Int?): Any? {
            checkOverlayPermission(this)

    // SoufianoDev: Start Service If Draw Overlay Permission Granted
    if (Settings.canDrawOverlays(this)) {
        val intent = Intent(this, TouchPointer::class.java).apply {
            putExtra(EditorActivity.PROFILE_NAME, selectedProfileName)
            displayId?.let { putExtra(TouchPointer.DISPLAY_ID, it) }
        }
        isServiceBound = bindService(intent, connection, BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, intent)
        setButtonState(false)
        requestNotificationPermission()
    }

    if (RemoteServiceHelper.useShizuku) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PERMISSION_GRANTED)
            alertShizukuNotAuthorized()
    } else if (Shell.isAppGrantedRoot() == false && startedFromShell == false) {
        alertRootAccessAndExit()
    }

    return null
    }

    private fun setButtonState(start: Boolean) {
        binding.controls.startPointer.apply {
            if (start) {
                setText(R.string.start)
                setOnClickListener { startPointer() }
                backgroundTintList = defaultTint
            } else {
                setText(R.string.stop)
                setOnClickListener { stopPointer() }
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.purple_700))
            }
        }
    }

    fun stopPointer() {
        unbindTouchPointer()
        stopService(Intent(this, TouchPointer::class.java))
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
            val intent = Intent(this, EditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(EditorActivity.PROFILE_NAME, selectedProfileName)
            }
            startActivity(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
                requestPermissions(arrayOf(POST_NOTIFICATIONS), 0)
            }
        }
    }

    fun alertRootAccessNotFound() {
        showAlertDialog(R.string.root_not_found_title, R.string.root_not_found_message, { _, _ ->
                packageManager.getLaunchIntentForPackage("me.weishu.kernelsu")?.let {
                startActivity(it)
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
        if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
        showAlertDialog(R.string.shizuku_not_authorized_title, R.string.shizuku_not_authorized_message, { _, _ -> launchShizukuAndExit() }, R.string.ok)
    }

    private fun alertShizukuNotRunning() {
        showAlertDialog(R.string.shizuku_not_running_title, R.string.shizuku_not_running_message, { _, _ -> launchShizukuAndExit() }, R.string.start)
    }

    private fun launchShizukuAndExit() {
        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
            startActivity(it)
            System.exit(0)
        }
    }

    private fun showAlertDialog(@StringRes titleId: Int, @StringRes messageId: Int, listener: DialogInterface.OnClickListener?, @StringRes ok: Int) {
        val builder = MaterialAlertDialogBuilder(this)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(ok, listener)
                .setNegativeButton(R.string.cancel, null)
        runOnUiThread { builder.show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindTouchPointer()
    }

    override fun onProfileSelected(profileName: String) {
        this.selectedProfileName = profileName
    }

    interface Callback {
        fun updateCmdView1(line: String)
        fun stopPointer()
    }

    private val mCallback = object : Callback {
        override fun updateCmdView1(line: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, line, Toast.LENGTH_SHORT).show() }
        }

        override fun stopPointer() {
            this@MainActivity.stopPointer()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            isServiceBound = true
            // SoufianoDev: Bind To Service And Cast IBinder To Access TouchPointer
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
