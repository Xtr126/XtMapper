package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// RED batch 1 for design-single-file.md v2 (CodeRabbit start list): fails until the
// single-daily-file protocol lands; V7/V5/V8.. follow in batch 2 once framing exists.
class SingleDailyFileProtocolTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "xtmapper-2026-10-01.log"

    @Test fun `V1 all roles append one unified daily file`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        val factory = TestPipelineFactory(store, MutableClock.at("2026-10-01T12:00:00Z"))
        Role.entries.map { role -> factory.start(role).also { it.info("Probe", "role record from $role") } }
            .forEach { it.close() }
        assertEquals(listOf(name), store.listFiles().map { it.name })
        val contents = LogFiles.readAll(store, name)
        Role.entries.forEach { role ->
            assertTrue("record from $role must survive in the unified file: $contents", contents.contains("role record from $role"))
        }
    }

    @Test fun `V2 new session persists while previous writer stays alive and idle`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        val factory = TestPipelineFactory(store, MutableClock.at("2026-10-01T12:00:00Z"))
        val idle = factory.start().also { it.info("Idle", "held-forever") }
        try {
            assertTrue("idle writer A must open its sink", awaitSink(idle))
            val active = factory.start()
            try {
                assertTrue("live idle writer must not starve the new session", awaitSink(active))
                active.info("Active", "from-b")
                assertTrue(active.flush(2_000))
                val contents = LogFiles.readAll(store, name)
                assertTrue("new session record must reach the daily file", contents.contains("from-b"))
            } finally { active.close() }
        } finally { idle.close() }
    }

    @Test fun `V3 second process writes while first process idles between transactions`() {
        val dir = tmp.newFolder()
        LogProbeChild(dir).use { holder ->
            assertEquals("OPENED", holder.command("OPEN $name"))
            var parent: LogFileWriter? = null
            var failure: Exception? = null
            try {
                parent = FileSystemLogStore(dir).openAppendWriter(name)
                parent.write("parent\n".toByteArray())
            } catch (e: Exception) {
                failure = e
            }
            parent?.close()
            try {
                assertNull("idle holder must not exclude a fresh writer: $failure", failure)
            } finally {
                assertEquals("CLOSED", holder.command("CLOSE"))
            }
        }
        val contents = LogFiles.readAll(FileSystemLogStore(dir), name)
        assertTrue("parent record must land as its own line, got: \"$contents\"", contents.lines().contains("parent"))
    }

    @Test fun `V4 lifetime-writer descriptor hazard kept as evidence with coordinator acceptance`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        val writer = store.openAppendWriter(name).also { it.write("first\n".toByteArray()) }
        // Evidence (not acceptance): POSIX drops this process's fcntl locks on the inode when
        // any fd closes, so the legacy lifetime writer cannot hold exclusion across fd churn.
        RandomAccessFile(File(dir, name), "r").use { it.read() }
        LogProbeChild(dir).use { foreign ->
            assertEquals(
                "legacy descriptor hazard: idle lifetime writer lost exclusion",
                "OPENED",
                foreign.command("OPEN $name"),
            )
        }
        writer.close()
        // Acceptance per review: coordinator exclusion must hold during an ACTIVE transaction.
        // Fresh day file — legacy-to-P1 migration of the poisoned day file is its own batch.
        val accept = "xtmapper-2026-10-02.log"
        CoordinatorStore(dir).append(accept, listOf(FrameCodec.encode(0, "seed", "seed")))
        LogProbeChild(dir, "coordinator-hook").use { holder ->
            assertEquals("ARMED", holder.command("ARM"))
            assertEquals("HOOK_REACHED", holder.command("TRYAPPEND $accept"))
            assertThrows(IOException::class.java) {
                CoordinatorStore(dir).append(accept, listOf(FrameCodec.encode(1, "s2", "denied")))
            }
            assertEquals("WROTE", holder.command("CONTINUE"))
        }
    }

    private fun awaitSink(pipeline: LoggingPipeline): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (pipeline.fileSinkAvailable) return true
            Thread.sleep(20)
        }
        return pipeline.fileSinkAvailable
    }
}
