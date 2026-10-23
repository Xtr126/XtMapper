package xtr.keymapper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xtr.keymapper.databinding.CursorBinding
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.editor.EditorService
import xtr.keymapper.editor.ShowKeymapService
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.profiles.ProfileSelector
import xtr.keymapper.server.RemoteServiceHelper
import xtr.keymapper.service.KeymapperTileService

class TouchPointer : Service() {
    private val binder: IBinder = TouchPointerBinder()
    var activityCallback: MainActivityCallback? = null
    var mService: IRemoteService? = null
    var selectedProfile: String? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private var activityRemoteCallback = false
    private var mWindowManager: WindowManager? = null
    private var displayId = 0
    private var displayListener: DisplayListener? = null
    private var lastForegroundPackage: String? = null


    interface MainActivityCallback {
        fun updateCmdView1(line: String?)
        fun stopPointer()
    }


    inner class TouchPointerBinder : Binder() {
        val service: TouchPointer
            get() =// Return this instance of TouchPointer so clients can call public methods
                this@TouchPointer
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null || ACTION_STOP == i.action) {
            stopKeymapper()
            return START_NOT_STICKY
        }

        // Launch default profile
        this.selectedProfile = i.getStringExtra(EditorActivity.PROFILE_NAME)
        if (this.selectedProfile == null) {
            this.selectedProfile = "Default"
        }



        val name = "Overlay"
        val channel =
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
                .setName(name).build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannel(channel)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }

        this.displayId = i.getIntExtra(DISPLAY_ID, Display.DEFAULT_DISPLAY)

        isRunning = true
        currentSelectedProfile = this.selectedProfile
        activeInstance = this
        KeymapperTileService.updateTile(this)

        val targetProfile = selectedProfile ?: KeymapProfiles(this).getAllProfiles().keys.firstOrNull()
        val keymapProfile = KeymapProfiles(this).getProfile(targetProfile, true)
        connectRemoteService(keymapProfile)

        if (displayListener == null) displayListener = object :
            DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == this@TouchPointer.displayId) {
                    Point().also {
                        getSystemService(DisplayManager::class.java).getDisplay(
                            displayId
                        ).getRealSize(it)
                    }.let {
                        /* We must notify remote service
                           when device orientation changes
                           keymap will be scaled in remote service */

                        // Get new instance of remote service to avoid DeadObjectException
                        connectRemoteService(
                            KeymapProfiles(this@TouchPointer).getProfile(selectedProfile, true)
                        )
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(
            displayListener, Handler(Looper.getMainLooper())
        )


        return super.onStartCommand(i, flags, startId)
    }

    private fun buildNotification(): android.app.Notification {
        val keymapConfig = KeymapConfig(this)
        val pendingIntent: PendingIntent?

        if (keymapConfig.editorOverlay) {
            val intent = Intent(this, EditorService::class.java).apply {
                putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            }
            pendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(this, EditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            }
            pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        return builder.setOngoing(true)
            .setContentTitle("Keymapper service running")
            .setContentText("Touch to launch editor ($selectedProfile)")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(2, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    fun launchProfile(profileName: String?) {
        this.selectedProfile = profileName
        currentSelectedProfile = profileName
        KeymapperTileService.updateTile(this)
        updateNotification()
        val keymapProfile = KeymapProfiles(this).getProfile(selectedProfile, true)
        connectRemoteService(keymapProfile)
    }

    private fun connectRemoteService(profile: KeymapProfile) {
        if (activityCallback != null) activityCallback!!.updateCmdView1("connecting to server..")
        val config = KeymapConfig(this)
        RemoteServiceHelper.useShizuku = config.isShizukuMode
        RemoteServiceHelper.getInstance(
            this
        ) { service: IRemoteService? ->
            if (service == null) {
                Log.w("TouchPointer", "Remote service is null, cannot start server")
                activityCallback?.updateCmdView1("Failed to connect to server (root/shizuku not available)")
                return@getInstance
            }
            mService = service
            val keymapConfig = KeymapConfig(this)
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(displayId) ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            display?.getRealSize(size)
            mWindowManager =
                this.displayContext?.getSystemService(WindowManager::class.java)
            try {
                if (keymapConfig.disableAutoProfiling) {
                    service.startServer(
                        profile,
                        keymapConfig,
                        mCallback,
                        size.x,
                        size.y,
                        displayId
                    )
                } else {
                    if (!activityRemoteCallback) {
                        service.registerActivityObserver(mActivityObserverCallback)
                        activityRemoteCallback = true
                    } else if (!profile.disabled) {
                        service.startServer(
                            profile,
                            keymapConfig,
                            mCallback,
                            size.x,
                            size.y,
                            displayId
                        )
                    }
                }
                if (keymapConfig.showControls) {
                    ShowKeymapService.start(this, selectedProfile)
                }
            } catch (e: Exception) {
                if (activityCallback != null) {
                    activityCallback!!.updateCmdView1(e.toString())
                    activityCallback!!.stopPointer()
                } else {
                    onDestroy()
                    stopSelf()
                }
                Log.e("startServer", e.toString(), e)
            }
        }
    }

    fun stopKeymapper() {
        if (!isRunning && activeInstance == null) return
        isRunning = false
        activeInstance = null

        displayListener?.let {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(it)
        }
        displayListener = null

        if (mService != null) try {
            mService!!.unregisterActivityObserver(mActivityObserverCallback)
            stopServer()
        } catch (e: Exception) {
            Log.e("stopServer", e.toString(), e)
        }
        mService = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        activityCallback?.stopPointer()
        KeymapperTileService.updateTile(this)
        stopSelf()
    }

    override fun onDestroy() {
        stopKeymapper()
        activityCallback = null
        super.onDestroy()
    }

    @Throws(RemoteException::class)
    private fun stopServer() {
        stopService(Intent(this, ShowKeymapService::class.java))
        mService!!.stopServer()
    }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    val mCallback: IRemoteServiceCallback = object : IRemoteServiceCallback.Stub() {
        private var cursorView: View? = null

        override fun launchEditor() {
            val intent = Intent(this@TouchPointer, EditorService::class.java)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            startService(intent)
        }

        override fun alertMouseAimActivated() {
            // Notifying user that shooting mode was activated
            mHandler.post {
                Toast.makeText(
                    this@TouchPointer, R.string.mouse_aim_activated, Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun requestKeymapProfile(): KeymapProfile {
            return KeymapProfiles(this@TouchPointer).getProfile(selectedProfile, true)
        }

        override fun requestKeymapConfig(): KeymapConfig {
            return KeymapConfig(this@TouchPointer)
        }

        @UiThread
        override fun switchProfiles() {
            mHandler.post {
                val keymapProfiles = KeymapProfiles(this@TouchPointer)
                val keymapProfile = keymapProfiles.getProfile(selectedProfile, false)
                val application = keymapProfile.packageName

                if (keymapProfiles.getAllProfilesForApp(application).size == 1) {
                    Toast.makeText(
                        this@TouchPointer,
                        "Only one profile saved for $application",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@post
                }
                ProfileSelector.select(
                    this@TouchPointer,
                    { profile: String? ->
                        this@TouchPointer.selectedProfile = profile
                        // Reloading profile
                        connectRemoteService(keymapProfiles.getProfile(profile, true))
                    },
                    application
                )
            }
        }

        override fun enablePointer() {
            val keymapConfig = requestKeymapConfig()

            // Set combined pointer mode automatically for 14 QPR3 and above
            if (keymapConfig.pointerMode == KeymapConfig.POINTER_OVERLAY) {
                keymapConfig.pointerMode = KeymapConfig.POINTER_COMBINED
                keymapConfig.applySharedPrefs()
                activityCallback?.stopPointer()
                try {
                    stopServer()
                } catch (_: RemoteException) {
                }
                return
            }

            mHandler.post {
                if (cursorView == null) {
                    cursorView = CursorBinding.inflate(
                        LayoutInflater.from(
                            ContextThemeWrapper(displayContext, R.style.Theme_XtMapper)
                        )
                    ).getRoot()

                    val mParams =
                        Utils.getPointerLayoutParams(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)

                    mWindowManager!!.addView(cursorView, mParams)
                }
            }
        }

        override fun disablePointer() {
            mHandler.post {
                if (cursorView != null) {
                    mWindowManager!!.removeView(cursorView)
                    cursorView = null
                }
            }
        }

        override fun setCursorX(x: Int) {
            mHandler.post {
                if (cursorView != null) cursorView!!.x = x.toFloat()
            }
        }

        override fun setCursorY(y: Int) {
            mHandler.post {
                if (cursorView != null) cursorView!!.y = y.toFloat()
            }
        }

        override fun alertKeyboardBlocked(blocked: Boolean) {
            mHandler.post {
                Toast.makeText(
                    this@TouchPointer,
                    if (blocked) R.string.keyboard_blocked else R.string.keyboard_unblocked,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val displayContext: Context?
        get() {
            val displayManager =
                getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(displayId)
            val context: Context? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                createDisplayContext(display).createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            } else {
                createDisplayContext(display)
            }
            return context
        }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private val mActivityObserverCallback: ActivityObserver = object : ActivityObserver.Stub() {
        private var lastPackageName: String? = null

        override fun onForegroundActivitiesChanged(packageName: String) {
            if (packageName == lastPackageName) return
            if (packageName == applicationContext.packageName || packageName.startsWith("xtr.keymapper")) return
            lastPackageName = packageName
            val context: Context = this@TouchPointer
            val keymapProfiles = KeymapProfiles(context)
            val designated = keymapProfiles.getDesignatedProfileForApp(packageName)

            if (designated != null) {
                if (designated != selectedProfile) {
                    mHandler.post {
                        launchProfile(designated)
                        Toast.makeText(this@TouchPointer, "Switched to profile: $designated", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }

            if (!keymapProfiles.profileExistsWithPackageName(packageName)) {
                // No profile found, prompt user to create a new profile
                mHandler.post {
                    ProfileSelector.showEnableProfileDialog(
                        context,
                        packageName
                    ) { enabled: Boolean ->
                        ProfileSelector.createNewProfileForApp(
                            context,
                            packageName,
                            enabled
                        ) { profile: String? ->
                            launchProfile(profile)
                        }
                    }
                }
            } else {
                // App specific profiles selection dialog
                mHandler.post {
                    ProfileSelector.select(context, { profile: String? ->
                        // Reloading profile
                        this@TouchPointer.selectedProfile = profile
                        val keymapProfile = keymapProfiles.getProfile(profile, true)
                        if (!keymapProfile.disabled) {
                            connectRemoteService(keymapProfile)
                            Toast.makeText(
                                this@TouchPointer,
                                "Keymapping enabled for $packageName",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            try {
                                mService!!.pauseMouse()
                            } catch (_: RemoteException) {
                            }
                            Toast.makeText(
                                this@TouchPointer,
                                "Keymapping disabled for $packageName",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }, packageName)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID: String = "pointer_service"
        const val DISPLAY_ID: String = "display_id"
        const val ACTION_STOP: String = "xtr.keymapper.action.STOP"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var currentSelectedProfile: String? = null

        @Volatile
        var activeInstance: TouchPointer? = null
    }
}
