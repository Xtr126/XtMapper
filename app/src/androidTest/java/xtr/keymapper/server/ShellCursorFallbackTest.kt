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
import org.junit.Assert.assertFalse
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
    private companion object {
        const val OVERLAY_APP_OP = "SYSTEM_ALERT_WINDOW"
        const val APP_OP_SUCCESS_MARKER = "__XT_APP_OP_SET__"
        val validAppOpModes = setOf("allow", "ignore", "deny", "default", "foreground")
    }

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
        val priorAutoProfilingMode = settings.all["disable_auto_profile"] as? Boolean
        val priorOverlayBypass = runShell("settings get system alert_window_bypass_low_ram")
        val priorOverlayMode = packageAppOpMode(
            runShell("appops get ${context.packageName} $OVERLAY_APP_OP")
        )
        val originalOverlays = cursorOverlays()
        var daemonPid: Int? = null
        var testPointer: TouchPointer? = null

        try {
            /*
            * Disable automatic profiling for this test and restore its setting.
            * When automatic profiling is enabled, ActivityObserverService can select
            *  another profiled app from recent tasks, even while XtMapper is foreground.
            * TouchPointer.launchProfile() then replaces Default and writes the last-selected profile.
            *  This can fail the profile assertion and leave the saved selection changed after cleanup.
            */
            assertTrue(settings.edit().putBoolean("disable_auto_profile", true).commit())
            assertTrue(settings.edit().putInt("pointer_mode", KeymapConfig.POINTER_OVERLAY).commit())
            assertTrue(profiles.edit().putStringSet(defaultName, setOf("ENABLED")).commit())
            val denyOverlayOutput = runShell(
                "appops set ${context.packageName} $OVERLAY_APP_OP ignore && echo $APP_OP_SUCCESS_MARKER"
            )
            assertTrue("Failed to deny the overlay app-op for the startup check", denyOverlayOutput.contains(APP_OP_SUCCESS_MARKER))
            assertFalse("Overlay access should be denied before shell startup", Settings.canDrawOverlays(context))

            val app = context.applicationInfo
            val launchOutput = runShell(
                "app_process -Djava.library.path='${app.nativeLibraryDir}' " +
                    "-Djava.class.path='${app.publicSourceDir}' / ${RemoteServiceShell::class.java.name} " +
                    "--no-logcat </dev/null >/dev/null 2>&1 & echo \$!"
            )
            daemonPid = checkNotNull(launchOutput.toIntOrNull()) { "Expected shell daemon PID, got: $launchOutput" }

            await("Expected shell startup to grant overlay access") {
                Settings.canDrawOverlays(context)
            }
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
            val restoreOverlayMode = runCatching {
                val restoreOutput = runShell(
                    "appops set ${context.packageName} $OVERLAY_APP_OP $priorOverlayMode && echo $APP_OP_SUCCESS_MARKER"
                )
                check(restoreOutput.contains(APP_OP_SUCCESS_MARKER)) {
                    "appops did not confirm restoring the package mode: $restoreOutput"
                }
                packageAppOpMode(runShell("appops get ${context.packageName} $OVERLAY_APP_OP"))
            }
            if (priorOverlayBypass == "null") {
                runShell("settings delete system alert_window_bypass_low_ram")
            } else {
                runShell("settings put system alert_window_bypass_low_ram $priorOverlayBypass")
            }
            val restoreSettings = settings.edit()

            if (priorPointerMode == null) restoreSettings.remove("pointer_mode")
            else restoreSettings.putInt("pointer_mode", priorPointerMode)

            if (priorAutoProfilingMode == null) restoreSettings.remove("disable_auto_profile")
            else restoreSettings.putBoolean("disable_auto_profile", priorAutoProfilingMode)

            restoreSettings.commit()
            profiles.edit().putStringSet(defaultName, priorProfile).commit()
            assertTrue(
                "Failed to restore the package overlay app-op: ${restoreOverlayMode.exceptionOrNull()?.message}",
                restoreOverlayMode.isSuccess
            )
            assertEquals("Package overlay app-op was not restored", priorOverlayMode, restoreOverlayMode.getOrNull())
        }
    }

    private fun cursorOverlays(): List<View> {
        var overlays = emptyList<View>()
        instrumentation.runOnMainSync {
            overlays = WindowInspector.getGlobalWindowViews().filter { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams
                view.isAttachedToWindow &&
                    params?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                    view.getTag(R.id.tag_pointer_cursor_overlay) == true &&
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

    private fun packageAppOpMode(output: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val packageModePattern = Regex("^package\\s+mode\\s*:", RegexOption.IGNORE_CASE)
        val packageEntries = lines.filter(packageModePattern::containsMatchIn)
        val mode = if (packageEntries.isNotEmpty()) {
            assertEquals("Expected exactly one package-mode entry in appops output: $output", 1, packageEntries.size)
            packageEntries.single().substringAfter(":").trim().split(Regex("[\\s;]+"), limit = 2).first()
        } else {
            val hasUidEntry = lines.any {
                Regex("^uid\\s+mode\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            assertFalse("Appops output has a UID mode but no package mode: $output", hasUidEntry)
            val operationEntries = lines.filter {
                it.startsWith("$OVERLAY_APP_OP:", ignoreCase = true)
            }
            assertEquals("Expected one package-scoped $OVERLAY_APP_OP entry: $output", 1, operationEntries.size)
            operationEntries.single().substringAfter(":").trim().split(Regex("[\\s;]+"), limit = 2).first()
        }
        assertTrue("Unexpected package app-op mode '$mode' in: $output", mode in validAppOpModes)
        return mode
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
