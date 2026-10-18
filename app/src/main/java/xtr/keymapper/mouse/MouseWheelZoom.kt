package xtr.keymapper.mouse

import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.util.Log
import xtr.keymapper.server.IInputInterface
import xtr.keymapper.server.InputService
import xtr.keymapper.server.RemoteService

class MouseWheelZoom(private val service: IInputInterface) : HandlerThread("mouse_wheel") {
    private val mHandler: Handler

    init {
        start()
        mHandler = Handler(looper)
    }

    private fun initPointers(x1: Int, x2: Int, y: Int) {
        service.injectEvent(x1.toFloat(), y.toFloat(), InputService.DOWN, MousePinchZoom.pointerId1)
        service.injectEvent(x2.toFloat(), y.toFloat(), InputService.DOWN, MousePinchZoom.pointerId2)
    }

    private fun releasePointers(x: Int, y: Int) {
        service.injectEvent(x.toFloat(), y.toFloat(), InputService.UP, MousePinchZoom.pointerId1)
        service.injectEvent(x.toFloat(), y.toFloat(), InputService.UP, MousePinchZoom.pointerId2)
    }

    fun onScrollEvent(value: Int, x: Int, y: Int) {
        mHandler.post {
            // Shift initial position of two pointers by 50 pixels
            var x1 = x + pixels
            var x2 = x - pixels
            try {
                initPointers(x1, x2, y)
                repeat(5) {
                    x1 += pixels / 5 * value
                    x2 -= pixels / 5 * value
                    service.injectEvent(x1.toFloat(), y.toFloat(), InputService.MOVE, MousePinchZoom.pointerId1)
                    Thread.sleep(10)
                    service.injectEvent(x2.toFloat(), y.toFloat(), InputService.MOVE, MousePinchZoom.pointerId2)
                    Thread.sleep(10)
                }
                releasePointers(x, y)
            } catch (e: RemoteException) {
                Log.e(RemoteService.TAG, e.message, e)
            } catch (e: InterruptedException) {
                Log.e(RemoteService.TAG, e.message, e)
            }
        }
    }

    companion object {
        private const val pixels = 50
    }
}
