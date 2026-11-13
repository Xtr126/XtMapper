package xtr.keymapper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private var lastDisplayWidth = 0
    private var lastDisplayHeight = 0
    private var lastDisplayRotation = -1
    private var connectionGeneration = 0


    interface MainActivityCallback {
        fun updateCmdView1(line: String?)
        fun stopPointer()
    }


    inner class TouchPointerBinder : Binder() {
        val service: TouchPointer
            get() = this@TouchPointer
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Handles service start commands, ensuring null intents and stop actions
     * are terminated immediately to prevent zombie instances.
     */
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null || ACTION_STOP == i.action) {
            stopKeymapper()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        this.selectedProfile = i.getStringExtra(EditorActivity.PROFILE_NAME)
        if (this.selectedProfile == null) {
            this.selectedProfile = KeymapProfiles(this).getLastSelectedProfile()
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

        val targetProfile = selectedProfile ?: KeymapProfiles(this).getLastSelectedProfile()
        val keymapProfile = KeymapProfiles(this).getProfile(targetProfile, true)
        connectRemoteService(keymapProfile)

        if (displayListener == null) displayListener = object :
            DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
            }

            override fun onDisplayChanged(displayId: Int) {
                if (!isRunning || displayId != this@TouchPointer.displayId) return
                val displayManager = getSystemService(DisplayManager::class.java) ?: return
                val display = displayManager.getDisplay(displayId) ?: return
                // Only react to active displays; ignore sleep / lid-close / screen-off states
                if (display.state != Display.STATE_ON) return

                val size = Point()
                display.getRealSize(size)
                val rotation = display.rotation

                // Only notify remote service when device orientation or size actually changes
                if (size.x == lastDisplayWidth && size.y == lastDisplayHeight && rotation == lastDisplayRotation) {
                    return
                }

                /* We must notify remote service
                   when device orientation changes
                   keymap will be scaled in remote service */
                connectRemoteService(
                    KeymapProfiles(this@TouchPointer).getProfile(selectedProfile, true),
                    activationReason = IRemoteService.ACTIVATION_DISPLAY_CHANGE
                )
            }

            override fun onDisplayRemoved(displayId: Int) {
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(
            displayListener, Handler(Looper.getMainLooper())
        )


        return START_NOT_STICKY
    }

    /**
     * Builds the persistent foreground notification for the Keymapper service.
     */
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

    /**
     * Updates the persistent foreground notification with the latest profile information.
     */
    private fun updateNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(2, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    /**
     * Switches the active keymap profile and connects to the remote service.
     *
     * @param profileName The name of the profile to activate.
     */
    fun launchProfile(profileName: String?) {
        this.selectedProfile = profileName
        currentSelectedProfile = profileName
        KeymapProfiles(this).setLastSelectedProfile(profileName)
        KeymapperTileService.updateTile(this)
        updateNotification()
        val keymapProfile = KeymapProfiles(this).getProfile(selectedProfile, true)
        connectRemoteService(keymapProfile)
    }

    /**
     * Connects to the remote input service and initializes the server with the given profile.
     *
     * @param profile The keymap profile to start.
     * @param activationReason Reason for connecting (e.g. manual activation vs display change).
     * @param onConnected Optional callback invoked after successfully initiating the server.
     */
    private fun connectRemoteService(
        profile: KeymapProfile,
        activationReason: Int = IRemoteService.ACTIVATION_MANUAL,
        onConnected: (() -> Unit)? = null
    ) {
        Log.i("xtmapper-startup", "connectRemoteService: profile=$selectedProfile disabled=${profile.disabled} reason=$activationReason")
        if (!profile.disabled) activityCallback?.updateCmdView1("connecting to server..")
        val config = KeymapConfig(this)
        RemoteServiceHelper.useShizuku = config.isShizukuMode
        val generation = connectionGeneration
        RemoteServiceHelper.getInstance(
            this
        ) { service: IRemoteService? ->
            Log.i("xtmapper-startup", "connectRemoteService: callback servicePresent=${service != null} alive=${service?.asBinder()?.isBinderAlive}")
            if (generation != connectionGeneration || !isRunning || activeInstance !== this) {
                return@getInstance
            }
            if (service == null) {
                Log.w("TouchPointer", "Remote service is null, cannot start server")
                activityCallback?.updateCmdView1("Failed to connect to server (root/shizuku not available)")
                stopKeymapper()
                return@getInstance
            }
            mService = service
            val keymapConfig = KeymapConfig(this)
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(displayId) ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            display?.getRealSize(size)
            mWindowManager =
                this.displayContext.getSystemService(WindowManager::class.java)
            try {
                if (!activityRemoteCallback && !keymapConfig.disableAutoProfiling) {
                    Log.i("xtmapper-startup", "connectRemoteService: registering activity observer")
                    service.registerActivityObserver(mActivityObserverCallback)
                    activityRemoteCallback = true
                }
                if (!profile.disabled) {
                    Log.i("xtmapper-startup", "connectRemoteService: calling startServer display=$displayId reason=$activationReason")
                    service.startServer(
                        profile,
                        keymapConfig,
                        mCallback,
                        size.x,
                        size.y,
                        displayId,
                        activationReason
                    )
                }
                if (profile.disabled) {
                    Log.w("xtmapper-startup", "connectRemoteService: profile disabled; startServer skipped")
                    val profileName = selectedProfile.orEmpty()
                    mHandler.post {
                        Toast.makeText(
                            this,
                            getString(R.string.profile_disabled_activation, profileName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (keymapConfig.showControls) {
                    ShowKeymapService.start(this, selectedProfile)
                }
                lastDisplayWidth = size.x
                lastDisplayHeight = size.y
                lastDisplayRotation = display?.rotation ?: 0
                onConnected?.invoke()
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

    private var cursorView: View? = null

    /**
     * Safely detaches the cursor overlay view from the WindowManager,
     * accounting for both attached and pre-attach pending states.
     */
    private fun removeCursorView() {
        val view = cursorView ?: return
        cursorView = null
        val wm = mWindowManager ?: displayContext.getSystemService(WindowManager::class.java)
        try {
            if (view.isAttachedToWindow) {
                wm?.removeView(view)
            } else if (view.parent != null) {
                wm?.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            Log.w("TouchPointer", "Error removing cursorView", e)
        }
    }

    /**
     * Stops the keymapper service, releasing listeners, cursor overlays,
     * and tearing down active remote server connections.
     */
    fun stopKeymapper() {
        if (!isRunning && activeInstance == null) return
        connectionGeneration++
        isRunning = false
        activeInstance = null

        mHandler.post { removeCursorView() }

        lastDisplayWidth = 0
        lastDisplayHeight = 0
        lastDisplayRotation = -1

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
        removeCursorView()
        stopKeymapper()
        activityCallback = null
        super.onDestroy()
    }

    @Throws(RemoteException::class)
    private fun stopServer() {
        stopService(Intent(this, ShowKeymapService::class.java))
        mService!!.stopServer()
    }

    val mCallback: IRemoteServiceCallback = object : IRemoteServiceCallback.Stub() {
        override fun launchEditor() {
            val intent = Intent(this@TouchPointer, EditorService::class.java)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            startService(intent)
        }

        override fun alertMouseAimActivated() {
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
                        connectRemoteService(keymapProfiles.getProfile(profile, true))
                    },
                    application
                )
            }
        }

        /**
         * Inflates and attaches the pointer cursor overlay window
         * with an empty touchable region if the service is currently running.
         */
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
                if (cursorView == null && isRunning) {
                    val binding = CursorBinding.inflate(
                        LayoutInflater.from(
                            ContextThemeWrapper(displayContext, R.style.Theme_XtMapper)
                        )
                    )
                    val view = binding.root
                    cursorView = view

                    val mParams =
                        Utils.getPointerLayoutParams(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)

                    Utils.applyEmptyTouchableRegion(view)
                    val wm = mWindowManager ?: displayContext.getSystemService(WindowManager::class.java)
                    wm?.addView(view, mParams)
                }
            }
        }

        override fun disablePointer() {
            mHandler.post {
                removeCursorView()
            }
        }

        override fun setCursorX(x: Int) {
            mHandler.post {
                cursorView?.x = x.toFloat()
            }
        }

        override fun setCursorY(y: Int) {
            mHandler.post {
                cursorView?.y = y.toFloat()
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

    private val displayContext: Context
        get() {
            return try {
                val displayManager = getSystemService(DisplayManager::class.java)
                val display = displayManager?.getDisplay(displayId)
                    ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        createDisplayContext(display).createWindowContext(
                            display,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            null
                        )
                    } else {
                        createDisplayContext(display)
                    }
                } else {
                    this
                }
            } catch (e: Exception) {
                Log.w("TouchPointer", "Failed to create displayContext, using service context", e)
                this
            }
        }

    private fun isLauncherOrSystem(packageName: String): Boolean {
        if (packageName == "com.android.systemui" ||
            packageName == applicationContext.packageName ||
            packageName.startsWith("xtr.keymapper") ||
            packageName == "android"
        ) {
            return true
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName == packageName) {
                return true
            }
            val homeActivities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            homeActivities.any { it.activityInfo?.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    private val mActivityObserverCallback: ActivityObserver = object : ActivityObserver.Stub() {
        private var lastPackageName: String? = null

        override fun onForegroundActivitiesChanged(packageName: String) {
            if (isLauncherOrSystem(packageName)) {
                lastPackageName = null
                return
            }
            if (packageName == lastPackageName) return
            lastPackageName = packageName
            val context: Context = this@TouchPointer
            val keymapProfiles = KeymapProfiles(context)
            val designated = keymapProfiles.getDesignatedProfileForApp(packageName)

            if (designated != null) {
                val designatedProfileDisabled = keymapProfiles.getProfile(designated, false).disabled
                if (designated != selectedProfile || designatedProfileDisabled) {
                    mHandler.post {
                        if (designated != selectedProfile) {
                            launchProfile(designated)
                            Toast.makeText(this@TouchPointer, "Switched to profile: $designated", Toast.LENGTH_SHORT).show()
                        }
                        if (keymapProfiles.getProfile(designated, false).disabled) {
                            try {
                                mService?.pauseMouse()
                            } catch (_: RemoteException) {
                            }
                        }
                    }
                }
                return
            }

            if (!keymapProfiles.profileExistsWithPackageName(packageName)) {
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
                mHandler.post {
                    ProfileSelector.select(context, { profile: String? ->
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
