package xtr.keymapper.log

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogStoreCoordinationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "xtmapper-2026-10-01.log"

    @Test fun `open-to-lock interval excludes cross-process deletion`() {
        val dir = tmp.newFolder()
        File(dir, name).writeText("old\n")
        Child(dir).use { opener ->
            assertEquals("OK", opener.command("PAUSE"))
            // Paused INSIDE production open, after opening the target, before tryLock.
            assertEquals("TARGET_OPEN", opener.command("OPEN $name"))
            assertFalse(FileSystemLogStore(dir).delete(name))
            assertEquals("old\n", File(dir, name).readText())
            assertEquals("OPENED", opener.command("CONTINUE"))
            assertEquals("WROTE", opener.command("WRITE new"))
            assertFalse(FileSystemLogStore(dir).delete(name))
            assertEquals("CLOSED", opener.command("CLOSE"))
        }
        assertEquals("old\nnew\n", File(dir, name).readText())
        assertTrue(FileSystemLogStore(dir).delete(name))
    }

    @Test fun `delete-first excludes opens until unlink completes then allows new pathname`() {
        val dir = tmp.newFolder()
        File(dir, name).writeText("old\n")
        Child(dir).use { deleter ->
            assertEquals("OK", deleter.command("PAUSE"))
            assertEquals("TARGET_OPEN", deleter.command("DELETE $name"))
            assertThrows(IOException::class.java) { FileSystemLogStore(dir).openAppendWriter(name) }
            assertEquals("DELETED true", deleter.command("CONTINUE"))
            assertFalse(File(dir, name).exists())
            FileSystemLogStore(dir).openAppendWriter(name).use { writer ->
                writer.write("replacement\n".toByteArray())
            }
        }
        assertEquals("replacement\n", File(dir, name).readText())
    }

    @Test fun `first closes during second open and second acquires its own protection`() {
        val dir = tmp.newFolder()
        Child(dir).use { first ->
            Child(dir).use { second ->
                assertEquals("OPENED", first.command("OPEN $name"))
                assertEquals("WROTE", first.command("WRITE first"))
                assertEquals("OK", second.command("PAUSE"))
                assertEquals("TARGET_OPEN", second.command("OPEN $name"))
                assertEquals("CLOSED", first.command("CLOSE"))
                assertEquals("OPENED", second.command("CONTINUE"))
                assertFalse(FileSystemLogStore(dir).delete(name))
                assertEquals("WROTE", second.command("WRITE second"))
                assertEquals("CLOSED", second.command("CLOSE"))
            }
        }
        assertEquals("first\nsecond\n", File(dir, name).readText())
        assertTrue(FileSystemLogStore(dir).delete(name))
    }

    @Test fun `real cross-process null lock refuses writer and closes both channels`() {
        val dir = tmp.newFolder()
        val channels = mutableListOf<FileChannel>()
        var sawNull = false
        val store = FileSystemLogStore(dir) {
            channels += it
            it.tryLock().also { lock -> if (lock == null) sawNull = true }
        }
        Child(dir).use { holder ->
            assertEquals("OPENED", holder.command("OPEN $name"))
            assertThrows(IOException::class.java) { store.openAppendWriter(name) }
            assertTrue(sawNull)
            assertEquals(2, channels.size)
            assertTrue(channels.none { it.isOpen })
            assertFalse(store.delete(name))
        }
        assertTrue(store.delete(name))
    }

    @Test fun `same-JVM duplicate delete and tail cannot release writer OS lock`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        store.openAppendWriter(name).use { writer ->
            writer.write("first\n".toByteArray())
            // Another store instance must share the registry, including canonical aliases.
            val other = FileSystemLogStore(File(dir, "."))
            assertThrows(IOException::class.java) { other.openAppendWriter(name) }
            assertFalse(other.delete(name))
            assertEquals(listOf("first"), other.readTail(name, 10))
            Child(dir).use { foreign ->
                assertEquals("DELETED false", foreign.command("DELETE $name"))
                assertTrue(foreign.command("OPEN $name").startsWith("ERROR IOException"))
            }
            writer.write("second\n".toByteArray())
        }
        assertEquals("first\nsecond\n", File(dir, name).readText())
        assertTrue(store.delete(name))
    }

    @Test fun `all roles can hold different daily files concurrently`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        val writers = Role.entries.map { role -> store.openAppendWriter(LogFileNames.dailyName("2026-10-01", role.fileNameTag)) }
        try {
            assertEquals(4, store.listFiles().size)
            Child(dir).use { foreign ->
                assertEquals("OPENED", foreign.command("OPEN xtmapper-2026-10-02.log"))
            }
            writers.forEach { it.write("ok\n".toByteArray()) }
        } finally { writers.forEach { it.close() } }
        assertFalse(store.listFiles().any { it.name == FileSystemLogStore.LIFECYCLE_LOCK_NAME })
        assertFalse(store.delete(FileSystemLogStore.LIFECYCLE_LOCK_NAME))
        assertTrue(File(dir, FileSystemLogStore.LIFECYCLE_LOCK_NAME).isFile)
    }

    @Test fun `unsupported and ambiguous IO failures at either lock close resources and fail closed`() {
        for (failureAt in 1..2) for (message in listOf("Operation not supported", "Input/output error")) {
            val dir = tmp.newFolder()
            File(dir, name).writeText("keep\n")
            val cause = IOException(message)
            val channels = mutableListOf<FileChannel>()
            val store = FileSystemLogStore(dir) {
                channels += it
                if (channels.size == failureAt) throw cause
                it.tryLock()
            }
            assertSame(cause, assertThrows(IOException::class.java) { store.openAppendWriter(name) })
            assertTrue(channels.none { it.isOpen })
            channels.clear()
            assertFalse(store.delete(name))
            assertTrue(channels.none { it.isOpen })
            assertEquals("keep\n", File(dir, name).readText())
            // A later healthy lock succeeding cannot invalidate an unprotected writer: none escaped.
            FileSystemLogStore(dir).openAppendWriter(name).use { it.write("recovered\n".toByteArray()) }
            assertTrue(FileSystemLogStore(dir).delete(name))
        }
    }

    @Test fun `real JVM overlap preserves cause and closes failed acquisition`() {
        val dir = tmp.newFolder()
        val channels = mutableListOf<FileChannel>()
        val store = FileSystemLogStore(dir) { channels += it; it.tryLock() }
        java.io.RandomAccessFile(File(dir, name), "rw").use { holder ->
            holder.channel.tryLock().use {
                val failure = assertThrows(IOException::class.java) { store.openAppendWriter(name) }
                assertTrue(failure.cause is OverlappingFileLockException)
                assertTrue(channels.none { it.isOpen })
                assertFalse(store.delete(name))
            }
        }
    }

    @Test fun `production lock failure disables session sink but leaves Logcat functional`() {
        val channels = mutableListOf<FileChannel>()
        val store = FileSystemLogStore(tmp.newFolder()) {
            channels += it
            if (channels.size == 2) throw IOException("ambiguous locking IO failure")
            it.tryLock()
        }
        val factory = TestPipelineFactory(store, MutableClock.at("2026-10-01T00:00:00Z"))
        val pipeline = factory.start()
        try {
            assertFalse(pipeline.fileSinkAvailable)
            assertTrue(pipeline.sessionWriter.unavailable)
            pipeline.info("Probe", "still on Logcat")
            assertTrue(factory.logcatRecords.any { it.message == "still on Logcat" })
            assertTrue(factory.logcatRecords.any { it.message.contains("fileSink=false") })
            assertEquals(1, factory.failureEvents.count { it.contains("file sink unavailable") })
            assertTrue(factory.failureEvents.any { it.contains("ambiguous locking IO failure") })
            assertTrue(channels.none { it.isOpen })
        } finally { pipeline.close() }
    }

    private class Child(dir: File) : Closeable {
        private val classpath = listOf(LogStoreProcessProbe::class.java, FileSystemLogStore::class.java, Unit::class.java)
            .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        private val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, LogStoreProcessProbe::class.java.name, dir.path,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = process.inputStream.bufferedReader()
        private val output = process.outputStream.bufferedWriter()
        private val reader = Executors.newSingleThreadExecutor()

        init { assertEquals("READY", response()) }
        private fun response(): String = reader.submit<String> { input.readLine() ?: error("Child exited") }
            .get(10, TimeUnit.SECONDS)
        fun command(line: String): String {
            output.write(line + "\n")
            output.flush()
            return response()
        }
        override fun close() {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            reader.shutdownNow()
            input.close()
            output.close()
        }
    }
}
