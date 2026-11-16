package xtr.keymapper.server.input

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvdevReaderDeviceTest {
    @Test
    fun shellReaderDeliversEventsAcrossHotplugAndRestart() {
        assumeTrue(
            "Enable with -e evdevDeviceTest true on a device with shell uinput access",
            InstrumentationRegistry.getArguments().getString("evdevDeviceTest") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val classpath = listOf(instrumentation.context, instrumentation.targetContext)
            .joinToString(":") { it.applicationInfo.sourceDir }
        val command = "env CLASSPATH=$classpath timeout 45s app_process /system/bin " +
            ShellEvdevScenario::class.java.name
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        assertTrue(
            "Shell evdev scenario did not report success. Output:\n$output",
            output.lineSequence().any { it == ShellEvdevScenario.PASSED },
        )
    }
}
