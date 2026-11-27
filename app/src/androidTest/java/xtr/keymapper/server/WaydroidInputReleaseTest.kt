package xtr.keymapper.server

import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile

class WaydroidInputReleaseTest {
    @Test
    fun releasingWaydroidInputStopsScrollWorkerAndAllowsFreshInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalThreads = Thread.getAllStackTraces().keys
        lateinit var service: InputService
        instrumentation.runOnMainSync {
            val config = KeymapConfig(null).apply {
                usePhysicalWaylandShortcuts = true
                pointerMode = KeymapConfig.POINTER_SYSTEM
            }
            service = InputService(
                KeymapProfile(), config, IRemoteServiceCallback.Default(), 800, 600,
                null, true, Display.DEFAULT_DISPLAY, TouchpadSessionRegistry(RealTouchpadNativeOps),
            )
        }
        try {
            repeat(2) {
                instrumentation.runOnMainSync {
                    // Queue an idle scroll so the Android worker starts without injecting input.
                    service.injectScroll(10f, 20f, 0f)
                }
                val worker = Thread.getAllStackTraces().keys.singleOrNull {
                    it.name == "xtmapper-scroll" && it !in originalThreads
                }
                assertNotNull("Each active input session should get a scroll worker on demand", worker)
                instrumentation.runOnMainSync { service.releaseWaylandEvents() }
                checkNotNull(worker).join(2_000)
                assertFalse("Input release should terminate the scroll worker", worker.isAlive)
            }
        } finally {
            instrumentation.runOnMainSync {
                service.releaseWaylandEvents()
                service.stop()
            }
        }
    }
}
