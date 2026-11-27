package xtr.keymapper

import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.server.RemoteService
import xtr.keymapper.server.RemoteServiceHelper
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun appProcessCannotAuthenticateAsTheWaydroidShellService() {
        assumeFalse("Stop the test shell session first", RemoteServiceHelper.hasLiveShellService())
        assumeFalse(RemoteServiceHelper.isShellServiceAuthenticationPending())
        val answered = CountDownLatch(1)
        val versionRead = AtomicBoolean()
        val verified = AtomicBoolean()
        class FakeDaemon : IRemoteService.Default() {
            private val binder = Binder().apply {
                attachInterface(this@FakeDaemon, "xtr.keymapper.IRemoteService")
            }

            override fun asBinder(): IBinder = binder

            override fun authenticateShellClient(callback: IShellAuthenticationCallback, challenge: Long) {
                callback.onShellServiceAuthenticated(challenge)
                answered.countDown()
            }

            override fun getVersion(): Int {
                versionRead.set(true)
                return RemoteService.PROTOCOL_VERSION
            }

            override fun isNativeWaylandBackend() = true

            override fun isNativeWaylandCaptureEnabled() = true
        }
        RemoteServiceHelper.authenticateShellServiceBinder(FakeDaemon().asBinder(), true) {
            verified.set(true)
        }
        assertTrue("Fake daemon should receive the challenge", answered.await(5, TimeUnit.SECONDS))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (RemoteServiceHelper.isShellServiceAuthenticationPending() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(10)
        }
        assertFalse(RemoteServiceHelper.isShellServiceAuthenticationPending())
        assertFalse("App UID must be rejected before protocol negotiation", versionRead.get())
        assertFalse(verified.get())
        assertFalse(RemoteServiceHelper.isWaydroidShellServiceReady)
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
