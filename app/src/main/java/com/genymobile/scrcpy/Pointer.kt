package com.genymobile.scrcpy

class Pointer(val id: Long, val localId: Int) {
    var point: Point? = null
    var pressure = 0f
    var isUp = false
}
