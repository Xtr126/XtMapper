package xtr.keymapper.server

import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.log.XtLogger

internal object TouchpadStartCodes {
    const val STARTED = 0
    const val NO_DEVICE = 1
    const val BUSY = 2
    const val SCAN_ERROR = 3
    const val ACCESS_ERROR = 4
    const val ACQUISITION_FAILED = 5
    const val OWNER_REF_FAILED = 6
    const val THREAD_FAILED = 7
    const val CANCELLED = 8
    const val INTERNAL_ERROR = 9
    const val START_INCOMPLETE = 10
}

internal object TouchpadStopCodes {
    const val COMPLETED = 0
    const val ALREADY_STOPPED = 1
    const val NOT_OWNER = 2
    const val INCOMPLETE = 3
}

internal sealed class TouchpadStartOutcome {
    object Started : TouchpadStartOutcome()
    object NoDevice : TouchpadStartOutcome()
    object Blocked : TouchpadStartOutcome()
    data class Failed(val code: Int) : TouchpadStartOutcome()
    object StartIncomplete : TouchpadStartOutcome()
}

internal enum class TouchpadStopOutcome { STOPPED, INCOMPLETE }

internal interface TouchpadNativeOps<T : Any> {
    fun start(owner: T, mode: Int): Int
    fun stop(owner: T): Int
}

internal object RealTouchpadNativeOps : TouchpadNativeOps<InputService> {
    override fun start(owner: InputService, mode: Int): Int = owner.startNativeTouchpad(mode)
    override fun stop(owner: InputService): Int = owner.stopNativeTouchpad()
}

class TouchpadSessionRegistry<T : Any> internal constructor(
    private val native: TouchpadNativeOps<T>
) {
    private class Obligation<T : Any>(val owner: T, val mode: Int)

    private var activeOwner: T? = null
    private var activeMode: Int = KeymapConfig.TOUCHPAD_DISABLED
    private val obligations = ArrayList<Obligation<T>>()

    @Synchronized
    internal fun start(owner: T, mode: Int): TouchpadStartOutcome {
        if (mode == KeymapConfig.TOUCHPAD_DISABLED) return TouchpadStartOutcome.Blocked

        val active = activeOwner
        if (active != null) {
            return if (active === owner && activeMode == mode) TouchpadStartOutcome.Started
            else TouchpadStartOutcome.Blocked
        }

        retryObligations()
        if (obligations.isNotEmpty()) return TouchpadStartOutcome.Blocked

        return when (val code = native.start(owner, mode)) {
            TouchpadStartCodes.STARTED -> {
                activeOwner = owner
                activeMode = mode
                TouchpadStartOutcome.Started
            }
            TouchpadStartCodes.NO_DEVICE -> TouchpadStartOutcome.NoDevice
            TouchpadStartCodes.START_INCOMPLETE -> {
                obligations.add(Obligation(owner, mode))
                TouchpadStartOutcome.StartIncomplete
            }
            TouchpadStartCodes.BUSY -> TouchpadStartOutcome.Blocked
            else -> TouchpadStartOutcome.Failed(code)
        }
    }

    @Synchronized
    internal fun stop(owner: T): TouchpadStopOutcome {
        retryObligations()

        if (activeOwner === owner) {
            val mode = activeMode
            val code = try {
                native.stop(owner)
            } catch (e: Exception) {
                XtLogger.error(TAG, "touchpad stop failed", e)
                TouchpadStopCodes.INCOMPLETE
            }
            activeOwner = null
            activeMode = KeymapConfig.TOUCHPAD_DISABLED
            return when (code) {
                TouchpadStopCodes.COMPLETED, TouchpadStopCodes.ALREADY_STOPPED ->
                    TouchpadStopOutcome.STOPPED
                else -> {
                    obligations.add(Obligation(owner, mode))
                    TouchpadStopOutcome.INCOMPLETE
                }
            }
        }

        return if (obligations.any { it.owner === owner }) TouchpadStopOutcome.INCOMPLETE
        else TouchpadStopOutcome.STOPPED
    }

    private fun retryObligations() {
        val iterator = obligations.iterator()
        while (iterator.hasNext()) {
            val obligation = iterator.next()
            val code = try {
                native.stop(obligation.owner)
            } catch (e: Exception) {
                XtLogger.error(TAG, "touchpad cleanup retry failed", e)
                continue
            }
            if (code == TouchpadStopCodes.COMPLETED || code == TouchpadStopCodes.ALREADY_STOPPED)
                iterator.remove()
        }
    }

    companion object {
        private const val TAG = "xtmapper-server"
    }
}
