package xtr.keymapper.server

internal class MouseDeviceLifecycle {
    internal data class OpenRequest(
        val device: String,
        val serviceGeneration: Long
    )

    private var discoveredDevice: String? = null
    private var nextServiceGeneration = 0L
    private var activeServiceGeneration: Long? = null
    private var attemptedDevice: String? = null
    private var openedDevice: String? = null
    private var inFlightRequest: OpenRequest? = null

    @get:Synchronized
    val device: String? get() = discoveredDevice

    @get:Synchronized
    val isOpen: Boolean get() = openedDevice != null

    @Synchronized
    fun onDeviceDiscovered(device: String): OpenRequest? {
        if (device.isBlank()) return null

        if (discoveredDevice != device) {
            discoveredDevice = device
            attemptedDevice = null
            openedDevice = null
            inFlightRequest = null
        }
        return nextOpenRequest()
    }

    @Synchronized
    fun onInputServiceReady(): OpenRequest? {
        nextServiceGeneration += 1
        activeServiceGeneration = nextServiceGeneration
        attemptedDevice = null
        openedDevice = null
        inFlightRequest = null
        return nextOpenRequest()
    }

    @Synchronized
    fun onInputServiceStopped() {
        activeServiceGeneration = null
        attemptedDevice = null
        openedDevice = null
        inFlightRequest = null
    }

    @Synchronized
    fun onOpenResult(request: OpenRequest, success: Boolean): Boolean {
        if (activeServiceGeneration != request.serviceGeneration ||
            discoveredDevice != request.device ||
            inFlightRequest != request
        ) {
            return false
        }

        inFlightRequest = null
        attemptedDevice = if (success) request.device else null
        if (success) openedDevice = request.device
        return true
    }

    private fun nextOpenRequest(): OpenRequest? {
        val generation = activeServiceGeneration ?: return null
        val device = discoveredDevice ?: return null
        if (openedDevice == device || attemptedDevice == device || inFlightRequest != null) {
            return null
        }

        return OpenRequest(device, generation).also { inFlightRequest = it }
    }
}
