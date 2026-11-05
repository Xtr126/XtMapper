package xtr.keymapper.server

import android.util.Log

/** Main-thread state linking getevent discovery to the lifetime of an input service. */
internal class MouseDeviceConnection {
    private var discoveredDevice: String? = null
    private var openedDevice: String? = null
    private var openDevice: ((String) -> Boolean)? = null
    private var lastWaitReason: String? = null

    val isOpen: Boolean get() = openedDevice != null

    fun discover(device: String) {
        if (device != discoveredDevice) {
            Log.i(TAG, "discover: device=$device previous=$discoveredDevice opened=$openedDevice connected=${openDevice != null} thread=${Thread.currentThread().name}")
        }
        discoveredDevice = device
        openDiscoveredDevice()
    }

    fun connect(open: (String) -> Boolean) {
        Log.i(TAG, "connect: discovered=$discoveredDevice opened=$openedDevice previouslyConnected=${openDevice != null} thread=${Thread.currentThread().name}")
        lastWaitReason = null
        openedDevice = null
        openDevice = open
        openDiscoveredDevice()
    }

    fun disconnect() {
        Log.i(TAG, "disconnect: discovered=$discoveredDevice opened=$openedDevice connected=${openDevice != null} thread=${Thread.currentThread().name}")
        lastWaitReason = null
        openDevice = null
        openedDevice = null
    }

    private fun openDiscoveredDevice() {
        val device = discoveredDevice
        val open = openDevice
        val waitReason = when {
            device == null -> "waiting for device discovery"
            device == openedDevice -> "device already open: $device"
            open == null -> "waiting for input service: $device"
            else -> null
        }
        if (waitReason != null) {
            // Discovery runs for every relative event; log each state transition only once.
            if (waitReason != lastWaitReason) Log.d(TAG, "openDiscoveredDevice: $waitReason")
            lastWaitReason = waitReason
            return
        }
        lastWaitReason = null
        Log.i(TAG, "openDiscoveredDevice: attempting device=$device thread=${Thread.currentThread().name}")
        val opened = try {
            open!!(device!!)
        } catch (e: Exception) {
            Log.e(TAG, "openDiscoveredDevice: exception opening device=$device", e)
            throw e
        }
        if (opened) {
            openedDevice = device
            Log.i(TAG, "openDiscoveredDevice: success device=$device")
        } else {
            Log.w(TAG, "openDiscoveredDevice: failed device=$device; will retry on next discovery")
        }
    }

    private companion object {
        const val TAG = "xtmapper-mouse-lifecycle"
    }
}
