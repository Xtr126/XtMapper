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
    private var closed = false

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

    @Synchronized
    fun close() {
        closed = true
        quit()
        interrupt()
    }

    @Synchronized
    private fun whileOpen(action: () -> Unit): Boolean {
        if (closed) return false
        action()
        return true
    }

    fun onScrollEvent(value: Int, x: Int, y: Int) {
        mHandler.post {
            var x1 = x + pixels
            var x2 = x - pixels
            try {
                if (!whileOpen { initPointers(x1, x2, y) }) return@post
                repeat(5) {
                    x1 += pixels / 5 * value
                    x2 -= pixels / 5 * value
                    if (!whileOpen {
                            service.injectEvent(x1.toFloat(), y.toFloat(), InputService.MOVE, MousePinchZoom.pointerId1)
                        }) return@post
                    sleep(10)
                    if (!whileOpen {
                            service.injectEvent(x2.toFloat(), y.toFloat(), InputService.MOVE, MousePinchZoom.pointerId2)
                        }) return@post
                    sleep(10)
                }
                whileOpen { releasePointers(x, y) }
            } catch (e: RemoteException) {
                Log.e(RemoteService.TAG, e.message, e)
            } catch (e: InterruptedException) {
                whileOpen { Log.e(RemoteService.TAG, e.message, e) }
            }
        }
    }

    companion object {
        private const val pixels = 50
    }
}
