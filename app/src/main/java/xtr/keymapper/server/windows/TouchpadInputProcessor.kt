package xtr.keymapper.server.windows

import android.graphics.Point
import android.view.Display
import android.view.WindowManagerGlobal
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchpadInputProcessor {
    val displayWidth: Int
    val displayHeight: Int

    init {
        val windowManagerService = WindowManagerGlobal
            .getWindowManagerService()

        val baseSize = Point()

        windowManagerService.getBaseDisplaySize(Display.DEFAULT_DISPLAY, baseSize)

        displayWidth = maxOf(baseSize.x, baseSize.y)
        displayHeight = minOf(baseSize.x, baseSize.y)

        println("Display size: ${displayWidth}x${displayHeight}")
    }

    fun TouchpadContact.scale() = this.apply {
        x = (x * displayWidth) / xMax
        y = (y * displayHeight) / yMax
    }

    fun readFully(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buf, read, size - read)
            if (n < 0) throw RuntimeException("Unexpected EOF")
            read += n
        }
        return buf
    }

    fun readFrame(input: InputStream): List<TouchpadContact>? {
        val lenBytes = ByteArray(4)
        val readLen = input.read(lenBytes)
        if (readLen < 0) return null // EOF
        if (readLen < 4) throw RuntimeException("Truncated frame length")

        val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        val frameData = readFully(input, length)
        val bb = ByteBuffer.wrap(frameData).order(ByteOrder.LITTLE_ENDIAN)

        val count = bb.int
        val contacts = ArrayList<TouchpadContact>(count)
        repeat(count) {
            contacts.add(
                TouchpadContact(
                    bb.int, bb.int, bb.int,
                    bb.int, bb.int, bb.int, bb.int,
                    bb.get().toInt() != 0
                ).scale()
            )
        }
        return contacts
    }

    fun parseContacts(bytes: ByteArray, length: Int): List<TouchpadContact> {
        val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int
        val contacts = ArrayList<TouchpadContact>(count)
        repeat(count) {
            val contactId = bb.int
            val x = bb.int
            val y = bb.int
            val xMin = bb.int
            val xMax = bb.int
            val yMin = bb.int
            val yMax = bb.int
            val tip = bb.get().toInt() != 0
            contacts.add(TouchpadContact(contactId, x, y, xMin, xMax, yMin, yMax, tip).scale())
        }
        return contacts
    }


}