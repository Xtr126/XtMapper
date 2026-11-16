package xtr.keymapper.server

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xtr.keymapper.R
import xtr.keymapper.TouchPointer
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfiles

@RunWith(AndroidJUnit4::class)
class ShellCursorFallbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test
    fun shellKeepsMappingWithAClientCursorWhenServerOverlayIsRejected() {
        assumeTrue(
            "Enable shellCursorFallbackTest on a device that rejects the shell cursor window",
            InstrumentationRegistry.getArguments().getString("shellCursorFallbackTest") == "true"
        )
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assumeTrue("Grant XtMapper overlay access", Settings.canDrawOverlays(context))
        assumeTrue("Unlock the device", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assumeTrue("Stop the running XtMapper session first", !TouchPointer.isRunning && TouchPointer.activeInstance == null)

        val profiles = KeymapProfiles(context).sharedPref
        val defaultName = KeymapProfiles.DEFAULT_PROFILE_NAME
        val priorProfile = profiles.getStringSet(defaultName, null)?.toSet()
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val priorPointerMode = settings.all["pointer_mode"] as? Int
        val priorOverlayBypass = runShell("settings get system alert_window_bypass_low_ram")
        val originalOverlays = cursorOverlays()
        var daemonPid: Int? = null
        var testPointer: TouchPointer? = null

        try {
            assertTrue(settings.edit().putInt("pointer_mode", KeymapConfig.POINTER_OVERLAY).commit())
            assertTrue(profiles.edit().putStringSet(defaultName, setOf("ENABLED")).commit())

            val app = context.applicationInfo
            val launchOutput = runShell(
                "app_process -Djava.library.path='${app.nativeLibraryDir}' " +
                    "-Djava.class.path='${app.publicSourceDir}' / ${RemoteServiceShell::class.java.name} " +
                    "--no-logcat </dev/null >/dev/null 2>&1 & echo \$!"
            )
            daemonPid = checkNotNull(launchOutput.toIntOrNull()) { "Expected shell daemon PID, got: $launchOutput" }

            await("Expected the shell input service and an app-owned cursor overlay") {
                val pointer = TouchPointer.activeInstance ?: return@await false
                testPointer = pointer
                TouchPointer.isRunning &&
                    pointer.mService?.isActive == true &&
                    cursorOverlays().any { it !in originalOverlays }
            }
            assertEquals(defaultName, TouchPointer.currentSelectedProfile)
            assertEquals(KeymapConfig.POINTER_OVERLAY, KeymapConfig(context).pointerMode)

            instrumentation.runOnMainSync { testPointer?.stopKeymapper() }
            await("Expected the app-owned cursor overlay to be removed at shutdown") {
                !TouchPointer.isRunning && cursorOverlays().none { it !in originalOverlays }
            }
        } finally {
            instrumentation.runOnMainSync {
                if (testPointer != null && TouchPointer.activeInstance === testPointer) {
                    testPointer?.stopKeymapper()
                }
            }
            daemonPid?.let { runShell("kill $it") }
            if (priorOverlayBypass == "null") {
                runShell("settings delete system alert_window_bypass_low_ram")
            } else {
                runShell("settings put system alert_window_bypass_low_ram $priorOverlayBypass")
            }
            val restoreSettings = settings.edit()
            if (priorPointerMode == null) restoreSettings.remove("pointer_mode")
            else restoreSettings.putInt("pointer_mode", priorPointerMode)
            restoreSettings.commit()
            profiles.edit().putStringSet(defaultName, priorProfile).commit()
        }
    }

    private fun cursorOverlays(): List<View> {
        var overlays = emptyList<View>()
        instrumentation.runOnMainSync {
            overlays = WindowInspector.getGlobalWindowViews().filter { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams
                view.isAttachedToWindow &&
                    params?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                    view.getTag(R.id.tag_empty_touchable_region) == true
            }
        }
        return overlays
    }

    private fun runShell(command: String): String {
        val (stdout, stdin) = automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).bufferedWriter().use { it.write("$command\n") }
        return ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().use { it.readText().trim() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }
}
