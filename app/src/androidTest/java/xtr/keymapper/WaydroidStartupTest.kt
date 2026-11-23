package xtr.keymapper

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xtr.keymapper.keymap.KeymapConfig
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WaydroidStartupTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatesPrivateLauncherForTheInstalledPackageAndReplacesStaleContents() {
        val directory = File(targetContext.filesDir, "waydroid-startup-test-${UUID.randomUUID()}")
        try {
            val context = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = directory
            }
            val command = Server.prepareWaydroidCommand(context)
            val script = File(directory, "start-waydroid.sh")
            assertEquals("sudo waydroid shell -- sh '${script.absolutePath}'", command)
            val first = script.readText()
            assertTrue(first.contains("pkg='${targetContext.packageName}'"))
            assertTrue(first.contains("xtr.keymapper.wayland.WaylandLauncher"))
            script.writeText("old launch script")
            assertEquals(command, Server.prepareWaydroidCommand(context))
            assertEquals(first, script.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun doesNotReturnACommandIfTheLauncherCannotBeWritten() {
        val blocked = File.createTempFile("waydroid-startup-", ".test", targetContext.filesDir)
        try {
            val context = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = blocked
            }
            assertThrows(IOException::class.java) { Server.prepareWaydroidCommand(context) }
        } finally {
            blocked.delete()
        }
    }

    @Test
    fun waydroidModePersistsWithoutBecomingRootOrShizuku() {
        val preferenceName = "waydroid-mode-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                targetContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        }
        try {
            val config = KeymapConfig(context)
            config.mouseSensitivity = 2f
            config.applySharedPrefs()
            config.mouseSensitivity = 3f
            config.selectInputMode(KeymapConfig.MODE_WAYDROID)

            val savedConfig = KeymapConfig(context)
            assertTrue(savedConfig.isWaydroidMode)
            assertFalse(savedConfig.isRootMode)
            assertFalse(savedConfig.isShizukuMode)
            assertEquals(2f, savedConfig.mouseSensitivity)
        } finally {
            targetContext.deleteSharedPreferences(preferenceName)
        }
    }
}
