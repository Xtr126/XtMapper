package xtr.keymapper.log

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyMigrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "xtmapper-2026-10-01.log"
    private val header = DayProtocol.header().encodeToByteArray()
    private val next = FrameCodec.encode(1, "new", "after migration")

    private fun receipt(prefix: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(prefix).joinToString("") { "%02x".format(it) }
        return FrameCodec.encode(0, "migration", "M1|$name|${prefix.size}|$digest")
    }

    private fun seedReceipt(dir: File, prefix: ByteArray) =
        File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME).writeBytes(receipt(prefix))

    @Test fun `legacy prefix migrates once and remains byte-identical across reopened stores`() {
        val dir = tmp.newFolder()
        val file = File(dir, name)
        val prefix = "old session\nold telemetry 🐉\n".encodeToByteArray()
        file.writeBytes(prefix)
        CoordinatorStore(dir).append(name, listOf(next))
        assertArrayEquals(prefix + header + next, file.readBytes())
        val reopened = CoordinatorStore(dir)
        reopened.append(name, listOf(FrameCodec.encode(2, "new", "second")))
        assertEquals(listOf("after migration", "second"), reopened.readFrames(name).filter { it.sid == "new" }.map { it.payload })
        assertEquals(1, reopened.readFrames(name).count(FrameCodec::isDayHeader))
        assertEquals(setOf(name, FileSystemLogStore.LIFECYCLE_LOCK_NAME), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `unterminated binary legacy bytes are preserved with exactly one alignment newline`() {
        val dir = tmp.newFolder()
        val file = File(dir, name)
        val prefix = byteArrayOf(0, 0xff.toByte(), 0xe2.toByte(), '\\'.code.toByte(), 'x'.code.toByte())
        file.writeBytes(prefix)
        CoordinatorStore(dir).append(name, listOf(next))
        assertArrayEquals(prefix + byteArrayOf('\n'.code.toByte()) + header + next, file.readBytes())
        assertEquals("after migration", CoordinatorStore(dir).readFrames(name).last().payload)
    }

    @Test fun `durable receipt resumes every alignment and header interruption offset`() {
        for (terminated in listOf(false, true)) {
            val prefix = ("legacy 🐉 tail" + if (terminated) "\n" else "").encodeToByteArray()
            val alignment = if (terminated) byteArrayOf() else byteArrayOf('\n'.code.toByte())
            val boundary = alignment + header
            for (cut in 0..boundary.size) {
                val dir = tmp.newFolder()
                val file = File(dir, name)
                seedReceipt(dir, prefix)
                file.writeBytes(prefix + boundary.copyOf(cut))
                val store = CoordinatorStore(dir)
                store.append(name, listOf(next))
                assertArrayEquals("terminated=$terminated cut=$cut", prefix + boundary + next, file.readBytes())
                val recovery = CoordinatorStore(dir).readRecovery(name)
                assertEquals(0L, recovery.discardedBytes)
                assertEquals(listOf("P1", "after migration"), recovery.frames.map { it.payload })
                assertFalse(recovery.frames.any { it.sid == "recovery" })
            }
        }
    }

    @Test fun `identical boundary bytes without the owning receipt are refused`() {
        val dir = tmp.newFolder()
        val file = File(dir, name)
        val before = "legacy\n".encodeToByteArray() + header
        file.writeBytes(before)
        assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `valid frame regions anywhere inside legacy text block migration`() {
        for (frame in listOf(header, FrameCodec.encode(8, "old", "record").copyOfRange(0, FrameCodec.encode(8, "old", "record").size - 1))) {
            val dir = tmp.newFolder()
            val file = File(dir, name)
            val before = "legacy prefix ".encodeToByteArray() + frame + " trailing text\n".encodeToByteArray()
            file.writeBytes(before)
            assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
            assertArrayEquals(before, file.readBytes())
            assertEquals(listOf(name), dir.listFiles()!!.filter { it.name.endsWith(".log") }.map { it.name })
        }
    }

    @Test fun `legacy-shaped complete line after the migration boundary blocks without truncation`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        seedReceipt(dir, prefix)
        val file = File(dir, name)
        val before = prefix + header + next + "old writer still alive\n".encodeToByteArray()
        file.writeBytes(before)
        val store = CoordinatorStore(dir)
        val error = assertThrows(DayIntegrityBlockedException::class.java) { store.append(name, listOf(next)) }
        assertTrue(error.message!!.contains("legacy writer detected after migration marker"))
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf("P1", "after migration"), store.readFrames(name).map { it.payload })
        assertTrue(store.readRecovery(name).discardedBytes > 0)
    }

    @Test fun `changed original prefix invalidates its migration receipt`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        seedReceipt(dir, prefix)
        val file = File(dir, name)
        val before = "Legacy\n".encodeToByteArray() + header
        file.writeBytes(before)
        val error = assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
        assertTrue(error.message!!.contains("migration receipt"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `legacy bytes and alignment consume the shared daily cap`() {
        val dir = tmp.newFolder()
        val file = File(dir, name)
        val prefix = ByteArray(1_370) { 'x'.code.toByte() }
        file.writeBytes(prefix)
        val store = CoordinatorStore(dir, maxDailyBytes = 2_000L, reserveBytes = 600L)
        val verdict = store.append(name, listOf(next))
        assertEquals(1, verdict.recordsDropped)
        assertTrue(verdict.noticeWritten)
        assertTrue(file.length() <= 2_000L)
        assertArrayEquals(prefix, file.readBytes().copyOf(prefix.size))
        assertFalse(store.readFrames(name).any { it.sid == "new" })
        val before = file.readBytes()
        assertEquals(1, CoordinatorStore(dir, maxDailyBytes = 2_000L, reserveBytes = 600L).append(name, listOf(next)).recordsDropped)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `legacy file too large for a boundary remains untouched`() {
        val dir = tmp.newFolder()
        val file = File(dir, name)
        val before = ByteArray(2_000) { 'x'.code.toByte() }
        file.writeBytes(before)
        assertThrows(DayIntegrityBlockedException::class.java) {
            CoordinatorStore(dir, maxDailyBytes = 2_000L, reserveBytes = 600L).append(name, listOf(next))
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `corrupt complete receipt journal refuses migration byte-identical`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        val gate = File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME)
        gate.writeBytes(receipt(prefix) + "broken receipt\n".encodeToByteArray())
        val file = File(dir, name)
        file.writeBytes(prefix)
        assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
        assertArrayEquals(prefix, file.readBytes())
    }

    @Test fun `unforced torn receipt never authorizes an already-present migration boundary`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        val gate = File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME)
        gate.writeBytes(receipt(prefix).copyOf(receipt(prefix).size - 1))
        val file = File(dir, name)
        val before = prefix + header
        file.writeBytes(before)
        assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `retention removes receipt authority before the same pathname is reused`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        val file = File(dir, name)
        file.writeBytes(prefix)
        val store = CoordinatorStore(dir)
        store.append(name, listOf(next))
        assertTrue(store.delete(name))
        val before = prefix + header
        file.writeBytes(before)
        assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
        assertArrayEquals(before, file.readBytes())
    }
}
