package xtr.keymapper.server

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import xtr.keymapper.InputEventCodes.EV_KEY
import xtr.keymapper.InputEventCodes.EV_REL
import xtr.keymapper.InputEventCodes.KEY_W
import xtr.keymapper.InputEventCodes.REL_X
import xtr.keymapper.InputEventCodes.BTN_LEFT
import xtr.keymapper.server.input.InputEventDecoder
import xtr.keymapper.server.input.InputEventFormatter

class InputEventDecoderTest {
    private fun record(is64Bit: Boolean, order: ByteOrder, type: Int, code: Int, value: Int): ByteArray {
        val timeSize = if (is64Bit) 16 else 8
        return ByteBuffer.allocate(timeSize + 8).order(order).apply {
            position(timeSize)
            putShort(type.toShort()); putShort(code.toShort()); putInt(value)
        }.array()
    }

    @Test fun decodesBothAbisByteOrdersAndFragmentedBatches() {
        for (is64Bit in listOf(false, true)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val events = listOf(Triple(EV_KEY, KEY_W, 1), Triple(EV_REL, REL_X, -7),
                Triple(EV_KEY, KEY_W, 2), Triple(EV_KEY, KEY_W, 0), Triple(65535, 65534, Int.MIN_VALUE))
            val bytes = events.map { (type, code, value) -> record(is64Bit, order, type, code, value) }
                .reduce { a, b -> a + b }
            // Exercise partial records, exact boundaries, and batches of several records.
            for (chunkSize in listOf(1, 7, 16, 24, 51, bytes.size)) {
                val decoded = mutableListOf<Triple<Int, Int, Int>>()
                val decoder = InputEventDecoder(is64Bit, order)
                bytes.toList().chunked(chunkSize).forEach { chunk ->
                    decoder.accept(chunk.toByteArray(), chunk.size) { t, c, v -> decoded.add(Triple(t, c, v)) }
                }
                assertEquals(events, decoded)
            }
        }
    }

    @Test fun incompleteRecordIsNotDelivered() {
        val decoder = InputEventDecoder(true)
        var delivered = false
        decoder.accept(ByteArray(23), 23) { _, _, _ -> delivered = true }
        assertFalse(delivered)
    }

    @Test fun preservesMappingAndEditorWireFormat() {
        assertEquals("/dev/input/event2: EV_KEY KEY_W DOWN", InputEventFormatter.line("/dev/input/event2", EV_KEY, KEY_W, 1))
        assertEquals("kbd: EV_KEY KEY_W UP", InputEventFormatter.line("kbd", EV_KEY, KEY_W, 0))
        assertEquals("kbd: EV_KEY KEY_W REPEAT", InputEventFormatter.line("kbd", EV_KEY, KEY_W, 2))
        assertEquals("mouse: EV_REL REL_X ffffffff", InputEventFormatter.line("mouse", EV_REL, REL_X, -1))
        assertEquals("mouse: EV_KEY BTN_MOUSE DOWN", InputEventFormatter.line("mouse", EV_KEY, BTN_LEFT, 1))
        assertEquals("unknown: 0000ffff 0000ffff 00000003", InputEventFormatter.line("unknown", 65535, 65535, 3))
    }
}
