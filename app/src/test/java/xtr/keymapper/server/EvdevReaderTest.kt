package xtr.keymapper.server

import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import xtr.keymapper.server.input.EvdevIo
import xtr.keymapper.server.input.EvdevReader

class EvdevReaderTest {
    @Test fun ownerClosesRemovedDevicesAndStopsWithoutClosingFromCaller() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val polls = AtomicInteger()
        var owner: Thread? = null
        var closer: Thread? = null
        val fd = FileDescriptor()
        val io = object : EvdevIo {
            override fun paths() = if (polls.get() == 0) setOf("/dev/input/event2") else emptySet()
            override fun open(path: String): FileDescriptor {
                owner = Thread.currentThread()
                opened.countDown()
                return fd
            }
            override fun poll(fds: List<FileDescriptor>, timeout: Int): IntArray {
                polls.incrementAndGet()
                return IntArray(fds.size)
            }
            override fun read(fd: FileDescriptor, bytes: ByteArray): Int = error("Nothing was readable")
            override fun close(fd: FileDescriptor) {
                closer = Thread.currentThread()
                closed.countDown()
            }
        }
        val tick = AtomicInteger()
        val reader = EvdevReader({ fail("Unexpected event") }, io, true) { tick.addAndGet(1001).toLong() }
        reader.start()
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertSame(owner, closer)
            assertNotSame(Thread.currentThread(), closer)
        } finally { reader.close() }
    }

    @Test fun shutdownClosesActiveDescriptorOnOwnerThread() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var opener: Thread? = null
        var closer: Thread? = null
        val io = object : EvdevIo {
            override fun paths() = setOf("/dev/input/event1")
            override fun open(path: String) = FileDescriptor().also {
                opener = Thread.currentThread()
                opened.countDown()
            }
            override fun poll(fds: List<FileDescriptor>, timeout: Int): IntArray {
                try { Thread.sleep(timeout.toLong()) } catch (_: InterruptedException) { }
                return IntArray(fds.size)
            }
            override fun read(fd: FileDescriptor, bytes: ByteArray): Int = error("No input")
            override fun close(fd: FileDescriptor) { closer = Thread.currentThread(); closed.countDown() }
        }
        val reader = EvdevReader({}, io, true)
        reader.start()
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        reader.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertSame(opener, closer)
        reader.close() // Idempotent stop request.
    }
}
