package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
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

    @Test fun `migration forces the actual receipt on the stable gate before changing legacy bytes`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        val file = File(dir, name).apply { writeBytes(prefix) }
        val gate = File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME).apply { createNewFile() }
        val inode = Files.getAttribute(gate.toPath(), "unix:ino")
        var forced = 0
        val store = CoordinatorStore(dir).apply {
            forceChannel = { channel ->
                assertArrayEquals("legacy stays unchanged until receipt force", prefix, file.readBytes())
                val bytes = ByteBuffer.allocate(channel.size().toInt())
                while (bytes.hasRemaining()) assertTrue(channel.read(bytes, bytes.position().toLong()) > 0)
                assertArrayEquals(receipt(prefix), bytes.array())
                channel.force(true)
                forced++
            }
        }
        store.append(name, listOf(next))
        assertEquals(1, forced)
        assertEquals(inode, Files.getAttribute(gate.toPath(), "unix:ino"))
        assertArrayEquals(prefix + header + next, file.readBytes())
    }

    @Test fun `receipt force failure leaves every legacy byte untouched and retry forces before recovery`() {
        val dir = tmp.newFolder()
        val prefix = "legacy without newline".encodeToByteArray()
        val file = File(dir, name).apply { writeBytes(prefix) }
        var attemptedForce = false
        val store = CoordinatorStore(dir).apply {
            forceChannel = { attemptedForce = true; throw IOException("receipt force failed") }
        }
        val error = assertThrows(IOException::class.java) { store.append(name, listOf(next)) }
        assertTrue(attemptedForce)
        assertEquals("receipt force failed", error.message)
        assertArrayEquals(prefix, file.readBytes())
        var retryForced = false
        val retry = CoordinatorStore(dir).apply {
            forceChannel = { channel ->
                assertArrayEquals(prefix, file.readBytes())
                channel.force(true)
                retryForced = true
            }
        }
        retry.append(name, listOf(next))
        assertTrue(retryForced)
        assertArrayEquals(prefix + byteArrayOf('\n'.code.toByte()) + header + next, file.readBytes())
    }

    @Test fun `partial receipt write failure cannot authorize data mutation`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        val file = File(dir, name).apply { writeBytes(prefix) }
        var writes = 0
        val store = CoordinatorStore(dir).apply {
            writeChannel = { channel, buffer ->
                if (writes++ > 0) throw IOException("receipt disk full")
                val limit = buffer.limit()
                buffer.limit(buffer.position() + minOf(7, buffer.remaining()))
                try { channel.write(buffer) } finally { buffer.limit(limit) }
            }
        }
        val error = assertThrows(IOException::class.java) { store.append(name, listOf(next)) }
        assertEquals("receipt disk full", error.message)
        assertEquals(2, writes)
        assertArrayEquals(prefix, file.readBytes())
        assertThrows(DayIntegrityBlockedException::class.java) { CoordinatorStore(dir).append(name, listOf(next)) }
        assertArrayEquals(prefix, file.readBytes())
    }

    @Test fun `prepared receipt is forced again before resuming an interrupted boundary`() {
        val dir = tmp.newFolder()
        val prefix = "legacy tail".encodeToByteArray()
        seedReceipt(dir, prefix)
        val before = prefix + byteArrayOf('\n'.code.toByte()) + header.copyOf(header.size / 2)
        val file = File(dir, name).apply { writeBytes(before) }
        var forced = false
        val store = CoordinatorStore(dir).apply {
            forceChannel = { channel ->
                assertArrayEquals(before, file.readBytes())
                channel.force(true)
                forced = true
            }
        }
        store.append(name, listOf(next))
        assertTrue(forced)
        assertArrayEquals(prefix + byteArrayOf('\n'.code.toByte()) + header + next, file.readBytes())
    }

    @Test fun `deletion forces receipt revocation before unlink on the same gate inode`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        seedReceipt(dir, prefix)
        val before = prefix + header + next
        val file = File(dir, name).apply { writeBytes(before) }
        val gate = File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME)
        val inode = Files.getAttribute(gate.toPath(), "unix:ino")
        var forced = false
        val store = CoordinatorStore(dir).apply {
            forceChannel = { channel ->
                assertArrayEquals(before, file.readBytes())
                val bytes = ByteBuffer.allocate(channel.size().toInt())
                while (bytes.hasRemaining()) assertTrue(channel.read(bytes, bytes.position().toLong()) > 0)
                assertEquals("D1|$name", FrameCodec.scanRecovery(bytes.array()).frames.last().payload)
                channel.force(true)
                forced = true
            }
        }
        assertTrue(store.delete(name))
        assertTrue(forced)
        assertFalse(file.exists())
        assertEquals(inode, Files.getAttribute(gate.toPath(), "unix:ino"))
    }

    @Test fun `failed revocation force never deletes data and a retry re-forces its tombstone`() {
        val dir = tmp.newFolder()
        val prefix = "legacy\n".encodeToByteArray()
        seedReceipt(dir, prefix)
        val before = prefix + header + next
        val file = File(dir, name).apply { writeBytes(before) }
        var attemptedForce = false
        val store = CoordinatorStore(dir).apply {
            forceChannel = { attemptedForce = true; throw IOException("revocation force failed") }
        }
        assertThrows(IOException::class.java) { store.delete(name) }
        assertTrue(attemptedForce)
        assertArrayEquals(before, file.readBytes())
        var retryForced = false
        val retry = CoordinatorStore(dir).apply {
            forceChannel = { channel ->
                assertArrayEquals(before, file.readBytes())
                channel.force(true)
                retryForced = true
            }
        }
        assertTrue(retry.delete(name))
        assertTrue(retryForced)
        assertFalse(file.exists())
    }
}
