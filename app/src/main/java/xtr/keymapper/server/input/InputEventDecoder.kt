package xtr.keymapper.server.input

import java.nio.ByteBuffer
import java.nio.ByteOrder
import xtr.keymapper.InputEventCodes
import xtr.keymapper.InputEventLabels

/** Linux input_event uses two native longs followed by u16 type/code and s32 value. */
internal class InputEventDecoder(is64Bit: Boolean, private val order: ByteOrder = ByteOrder.nativeOrder()) {
    private val timeSize = if (is64Bit) 16 else 8
    private val record = ByteArray(timeSize + 8)
    private var filled = 0

    fun accept(bytes: ByteArray, count: Int, emit: (Int, Int, Int) -> Unit) {
        require(count in 0..bytes.size)
        var offset = 0
        while (offset < count) {
            val size = minOf(record.size - filled, count - offset)
            bytes.copyInto(record, filled, offset, offset + size)
            offset += size
            filled += size
            if (filled == record.size) {
                val buffer = ByteBuffer.wrap(record).order(order)
                filled = 0
                emit(buffer.getShort(timeSize).toInt() and 0xffff,
                    buffer.getShort(timeSize + 2).toInt() and 0xffff,
                    buffer.getInt(timeSize + 4))
            }
        }
    }
}

/** Preserve the existing Binder/editor protocol, with labels supplied by our NDK. */
internal object InputEventFormatter {
    fun line(device: String, type: Int, code: Int, value: Int): String {
        val typeName = InputEventLabels.evLabels[type] ?: hex(type)
        val codeName = InputEventLabels.label(type, code) ?: hex(code)
        val action = if (type == InputEventCodes.EV_KEY) when (value) {
            0 -> "UP"
            1 -> "DOWN"
            2 -> "REPEAT"
            else -> hex(value)
        } else hex(value)
        return "$device: $typeName $codeName $action"
    }

    private fun hex(value: Int) = value.toUInt().toString(16).padStart(8, '0')
}
