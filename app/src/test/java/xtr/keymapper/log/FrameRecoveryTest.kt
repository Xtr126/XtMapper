package xtr.keymapper.log

import org.junit.Assert.*
import org.junit.Test

class FrameRecoveryTest {

    @Test fun `round trip keeps newlines raw backslashes CR and unicode`() {
        val payload = "line1\nline2\rpath\\x café 🙂"
        val frame = FrameCodec.decode(FrameCodec.encode(7, "session-1", payload).decodeToString().trimEnd('\n'))
        assertEquals(Frame(7, "session-1", payload), frame)
    }

    @Test fun `payload corruption fails the checksum gate`() {
        val line = FrameCodec.encode(1, "s", "banana").decodeToString().trimEnd('\n')
        val field = line.split(' ', limit = 6)[5]
        assertNull(FrameCodec.decode(line.replace(field, field.replace('a', 'b'))))
    }

    @Test fun `valid looking metadata tamper fails the checksum gate`() {
        val line = FrameCodec.encode(1, "s", "banana").decodeToString().trimEnd('\n')
        val f = line.split(' ', limit = 6)
        assertNull("seq swap with intact checksum must be caught", FrameCodec.decode("X1 2 ${f[2]} ${f[3]} ${f[4]} ${f[5]}"))
        assertNull("sid swap with intact checksum must be caught", FrameCodec.decode("X1 ${f[1]} t ${f[3]} ${f[4]} ${f[5]}"))
        assertNull("len swap with intact checksum must be caught", FrameCodec.decode("X1 ${f[1]} ${f[2]} 7 ${f[4]} ${f[5]}"))
    }

    @Test fun `corrupted envelope fails the grammar gate`() {
        assertNull(FrameCodec.decode("X1 1 bad|sid 1 00000000 x"))
        assertNull(FrameCodec.decode("X1 x s 1 00000000 x"))
        assertNull(FrameCodec.decode("X1 1 s 1 00000 x"))
        assertNull(FrameCodec.decode("X1 1 s 2 00000000 x"))
        assertNull(FrameCodec.decode(""))
    }

    @Test fun `day header is frame zero with the protocol payload`() {
        val line = DayProtocol.header().trimEnd('\n')
        assertTrue(FrameCodec.isDayHeader(FrameCodec.decode(line)!!))
        assertFalse(FrameCodec.isDayHeader(FrameCodec.decode(FrameCodec.encode(1, "sid-1", "x").decodeToString().trimEnd('\n'))!!))
    }

    @Test fun `scan preserves valid prefix and counts torn suffix bytes`() {
        val h = DayProtocol.header().encodeToByteArray()
        val a = FrameCodec.encode(1, "sid-1", "alpha")
        val b = FrameCodec.encode(2, "sid-1", "beta")
        val torn = "X1 9 sid-2 4 aaaa"
        val recovery = FrameCodec.scanRecovery(h + a + b + torn.toByteArray())
        assertEquals(3, recovery.frames.size)
        assertTrue(FrameCodec.isDayHeader(recovery.frames[0]))
        assertEquals("beta", recovery.frames[2].payload)
        assertEquals(torn.length.toLong(), recovery.discardedBytes)
    }

    @Test fun `scan stops at a corrupt mid-history frame`() {
        val h = DayProtocol.header().encodeToByteArray()
        val a = FrameCodec.encode(1, "sid-1", "alpha")
        val garbage = "not a frame\n"
        val b = FrameCodec.encode(2, "sid-1", "beta")
        val recovery = FrameCodec.scanRecovery(h + a + garbage.toByteArray() + b)
        assertEquals(2, recovery.frames.size)
        assertEquals((garbage.length + b.size).toLong(), recovery.discardedBytes)
    }

    @Test fun `scan clean file reports zero discarded`() {
        val h = DayProtocol.header().encodeToByteArray()
        val a = FrameCodec.encode(1, "sid-1", "alpha")
        val recovery = FrameCodec.scanRecovery(h + a)
        assertEquals(2, recovery.frames.size)
        assertEquals(0L, recovery.discardedBytes)
    }
}
