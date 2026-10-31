package xtr.keymapper.server

/**
 * Serializes the relationship between a discovered evdev mouse and an InputService generation.
 *
 * The getevent reader and the server-start handler run on different threads. Keep the state
 * behind synchronized methods, and let RemoteService perform open requests on its main Handler.
 */
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

    /**
     * Returns false for a result belonging to a stopped/replaced service or superseded device.
     */
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

    /**
     * Generation of the currently running InputService, or null when none is running.
     * Client-binder death notifications capture this at notification time so a queued
     * cleanup task can tell the dead client's own session from a replacement session.
     */
    @Synchronized
    fun currentServiceGeneration(): Long? = activeServiceGeneration

    /**
     * True only when a recorded session generation no longer matches the active service:
     * the dead client was replaced while its cleanup waited, and the replacement session
     * must not be stopped by it. A null generation (no session ran at notification time)
     * or a matching generation keeps the pre-existing cleanup semantics.
     */
    @Synchronized
    fun isStaleClientDeath(sessionGeneration: Long?): Boolean {
        return sessionGeneration != null &&
            activeServiceGeneration != null &&
            activeServiceGeneration != sessionGeneration
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
