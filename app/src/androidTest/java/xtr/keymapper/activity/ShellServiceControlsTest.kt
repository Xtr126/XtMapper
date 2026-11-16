package xtr.keymapper.activity

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import xtr.keymapper.R
import xtr.keymapper.TouchPointer
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.server.RemoteServiceShell

@RunWith(AndroidJUnit4::class)
class ShellServiceControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test
    fun shellStartsDefaultAndNotificationStopsService() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assumeTrue("Grant XtMapper overlay access", Settings.canDrawOverlays(context))
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val verifyScreen = !keyguard.isKeyguardLocked
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Allow XtMapper notifications", notifications.areNotificationsEnabled())
        var idle = false
        instrumentation.runOnMainSync { idle = isTouchPointerIdle() }
        assumeTrue("Stop XtMapper before running this test", idle)

        val profiles = KeymapProfiles(context)
        val prefs = profiles.sharedPref
        val defaultName = KeymapProfiles.DEFAULT_PROFILE_NAME
        val priorDefault = prefs.getStringSet(defaultName, null)?.toSet()
        val priorSelection = prefs.getString(KeymapProfiles.KEY_LAST_SELECTED_PROFILE, null)
        val otherProfile = "ShellServiceControlsTest"
        assumeTrue(!prefs.contains(otherProfile))
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val priorPointerMode = settings.all["pointer_mode"] as? Int
        val priorOverlayBypass = shell("settings get system alert_window_bypass_low_ram")
        val accessibility = automation.serviceInfo
        val priorAccessibilityFlags = accessibility.flags
        val activityCreations = AtomicInteger()
        val lifecycleMonitor = ActivityLifecycleMonitorRegistry.getInstance()
        val recreateBeforeVerification = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.PRE_ON_CREATE &&
                activityCreations.incrementAndGet() == 1
            ) {
                activity.recreate()
            }
        }
        var daemonPid: Int? = null
        var testService: TouchPointer? = null
        try {
            accessibility.flags = priorAccessibilityFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = accessibility
            assertTrue(settings.edit().putInt("pointer_mode", KeymapConfig.POINTER_COMBINED).commit())
            assertTrue(prefs.edit()
                .putStringSet(defaultName, setOf("ENABLED"))
                .putStringSet(otherProfile, emptySet())
                .putString(KeymapProfiles.KEY_LAST_SELECTED_PROFILE, otherProfile)
                .commit())

            instrumentation.runOnMainSync {
                lifecycleMonitor.addLifecycleCallback(recreateBeforeVerification)
            }
            val app = context.applicationInfo
            val (stdout, stdin) = automation.executeShellCommandRw("sh")
            ParcelFileDescriptor.AutoCloseOutputStream(stdin).bufferedWriter().use { script ->
                script.write(
                    "app_process -Djava.library.path='${app.nativeLibraryDir}' " +
                        "-Djava.class.path='${app.publicSourceDir}' / " +
                        "${RemoteServiceShell::class.java.name} </dev/null >/dev/null 2>&1 & echo \$!\n"
                )
            }
            val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(stdout)
                .bufferedReader().use { it.readText().trim() }
            daemonPid = checkNotNull(launchOutput.toIntOrNull()) {
                "Expected the shell daemon PID, got: $launchOutput"
            }
            await("The shell launch should start the service") {
                testService = TouchPointer.activeInstance
                TouchPointer.isRunning && testService != null && activityCreations.get() >= 2
            }
            if (verifyScreen) {
                await("The screen should show that the service started") {
                    screenContains(context.getString(R.string.service_active))
                }
            }
            assertEquals(defaultName, TouchPointer.currentSelectedProfile)
            assertEquals(otherProfile, profiles.getLastSelectedProfile())

            await("The foreground service should post the editor notification") {
                notifications.activeNotifications.any {
                    it.notification.channelId == TouchPointer.CHANNEL_ID
                }
            }
            val notification = notifications.activeNotifications
                .first { it.notification.channelId == TouchPointer.CHANNEL_ID }.notification
            assertNotNull(notification.contentIntent)
            notification.actions.single { it.title.toString() == context.getString(R.string.stop_service) }
                .actionIntent.send()

            await("The notification action should stop the foreground service") {
                !TouchPointer.isRunning &&
                    notifications.activeNotifications.none {
                        it.notification.channelId == TouchPointer.CHANNEL_ID
                    }
            }
            if (verifyScreen) {
                await("The screen should show that the service stopped") {
                    screenContains(context.getString(R.string.service_inactive))
                }
            }
        } finally {
            accessibility.flags = priorAccessibilityFlags
            automation.serviceInfo = accessibility
            instrumentation.runOnMainSync {
                lifecycleMonitor.removeLifecycleCallback(recreateBeforeVerification)
                testService?.takeIf { it === TouchPointer.activeInstance }?.stopKeymapper()
            }
            daemonPid?.let { shell("kill $it") }
            if (priorOverlayBypass == "null") {
                shell("settings delete system alert_window_bypass_low_ram")
            } else {
                shell("settings put system alert_window_bypass_low_ram $priorOverlayBypass")
            }
            val restoreSettings = settings.edit()
            if (priorPointerMode == null) restoreSettings.remove("pointer_mode")
            else restoreSettings.putInt("pointer_mode", priorPointerMode)
            restoreSettings.commit()
            prefs.edit()
                .putStringSet(defaultName, priorDefault)
                .remove(otherProfile)
                .putString(KeymapProfiles.KEY_LAST_SELECTED_PROFILE, priorSelection)
                .commit()
        }
    }

    @Suppress("DEPRECATION") // Android still reports this app's own running services.
    private fun isTouchPointerIdle(): Boolean =
        !TouchPointer.isRunning && TouchPointer.activeInstance == null &&
            context.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .none { it.service.className == TouchPointer::class.java.name }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun screenContains(label: String): Boolean =
        automation.windows.any { window ->
            val root = window.root
            root?.packageName == context.packageName && nodeContains(root, label)
        }

    private fun nodeContains(node: AccessibilityNodeInfo, label: String): Boolean {
        if (!node.refresh()) return false
        if (node.text?.toString() == label) return true
        return (0 until node.childCount).any { index ->
            node.getChild(index)?.let { nodeContains(it, label) } == true
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }
}
