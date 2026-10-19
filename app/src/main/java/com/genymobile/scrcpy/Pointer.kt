package com.genymobile.scrcpy

/**
 * Pointer id as received from the client.
 *
 * Local pointer id, using the lowest possible values to fill the [android.view.MotionEvent.PointerProperties].
 */
class Pointer(val id: Long, val localId: Int) {
    var point: Point? = null
    var pressure = 0f
    var isUp = false
}
