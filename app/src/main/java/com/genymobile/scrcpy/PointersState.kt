package com.genymobile.scrcpy

import android.view.MotionEvent

class PointersState {

    private val pointers = ArrayList<Pointer>()

    private fun indexOf(id: Long) = pointers.indexOfFirst { it.id == id }

    private fun isLocalIdAvailable(localId: Int) = pointers.none { it.localId == localId }

    private fun nextUnusedLocalId() = (0 until MAX_POINTERS).firstOrNull { isLocalIdAvailable(it) } ?: -1

    operator fun get(index: Int): Pointer = pointers[index]

    fun getPointerIndex(id: Long): Int {
        val index = indexOf(id)
        if (index != -1) {
            // already exists, return it
            return index
        }
        if (pointers.size >= MAX_POINTERS) {
            // it's full
            return -1
        }
        // id 0 is reserved for mouse events
        val localId = nextUnusedLocalId()
        if (localId == -1) {
            throw AssertionError("pointers.size() < maxFingers implies that a local id is available")
        }
        pointers.add(Pointer(id, localId))
        // return the index of the pointer
        return pointers.size - 1
    }

    /**
     * Initialize the motion event parameters.
     *
     * @return The number of items initialized (the number of pointers).
     */
    fun update(
        props: Array<MotionEvent.PointerProperties?>,
        coords: Array<MotionEvent.PointerCoords?>
    ): Int {
        val count = pointers.size
        for (i in 0 until count) {
            val pointer = pointers[i]

            // id 0 is reserved for mouse events
            props[i]!!.id = pointer.localId

            val point = pointer.point!!
            coords[i]!!.x = point.x
            coords[i]!!.y = point.y
            coords[i]!!.pressure = pointer.pressure
        }
        cleanUp()
        return count
    }

    /**
     * Remove all pointers which are UP.
     */
    private fun cleanUp() {
        pointers.removeAll { it.isUp }
    }

    companion object {
        const val MAX_POINTERS = 10
    }
}
