package xtr.keymapper.server

import android.util.Log
import xtr.keymapper.log.XtLogger

/** Main-thread state linking getevent discovery to the lifetime of an input service. */
internal class MouseDeviceConnection {
    private val lifecycle = MouseDeviceLifecycle()
    private var openDevice: ((String) -> Boolean)? = null
    private var lastWaitReason: String? = null

    val isOpen: Boolean get() = lifecycle.isOpen

    fun currentServiceGeneration(): Long? = lifecycle.currentServiceGeneration()

    fun isStaleClientDeath(sessionGeneration: Long?): Boolean =
        lifecycle.isStaleClientDeath(sessionGeneration)

    fun discover(device: String) {
        if (device != lifecycle.device) {
            Log.i(TAG, "discover: device=$device previous=${lifecycle.device} opened=$isOpen connected=${openDevice != null} thread=${Thread.currentThread().name}")
            XtLogger.info(TAG, "mouse device discovered path=$device inputServiceReady=${openDevice != null}")
        }
        openDiscoveredDevice(lifecycle.onDeviceDiscovered(device))
    }

    fun connect(open: (String) -> Boolean) {
        Log.i(TAG, "connect: discovered=${lifecycle.device} opened=$isOpen previouslyConnected=${openDevice != null} thread=${Thread.currentThread().name}")
        lastWaitReason = null
        openDevice = open
        openDiscoveredDevice(lifecycle.onInputServiceReady())
    }

    fun disconnect() {
        Log.i(TAG, "disconnect: discovered=${lifecycle.device} opened=$isOpen connected=${openDevice != null} thread=${Thread.currentThread().name}")
        lastWaitReason = null
        openDevice = null
        lifecycle.onInputServiceStopped()
    }

    private fun openDiscoveredDevice(request: MouseDeviceLifecycle.OpenRequest?) {
        val device = lifecycle.device
        val open = openDevice
        val waitReason = when {
            device == null -> "waiting for device discovery"
            open == null -> "waiting for input service: $device"
            isOpen && request == null -> "device already open: $device"
            request == null -> "waiting for open result: $device"
            else -> null
        }
        if (waitReason != null) {
            // Discovery runs for every relative event; log each state transition only once.
            if (waitReason != lastWaitReason) Log.d(TAG, "openDiscoveredDevice: $waitReason")
            lastWaitReason = waitReason
            return
        }
        lastWaitReason = null
        val currentRequest = request ?: return
        Log.i(TAG, "openDiscoveredDevice: attempting device=$device thread=${Thread.currentThread().name}")
        val opened = try {
            open!!(currentRequest.device)
        } catch (e: Exception) {
            lifecycle.onOpenResult(currentRequest, success = false)
            Log.e(TAG, "openDiscoveredDevice: exception opening device=$device", e)
            throw e
        }
        val accepted = lifecycle.onOpenResult(currentRequest, success = opened)
        if (!accepted) {
            XtLogger.warn(TAG, "ignored stale mouse open result path=$device generation=${currentRequest.serviceGeneration}")
        } else if (opened) {
            Log.i(TAG, "openDiscoveredDevice: success device=$device")
            XtLogger.info(TAG, "mouse device opened path=$device generation=${currentRequest.serviceGeneration}")
        } else {
            Log.w(TAG, "openDiscoveredDevice: failed device=$device; will retry on next discovery")
            XtLogger.warn(TAG, "mouse device open failed path=$device generation=${currentRequest.serviceGeneration}")
        }
    }

    private companion object {
        const val TAG = "xtmapper-mouse-lifecycle"
    }
}
