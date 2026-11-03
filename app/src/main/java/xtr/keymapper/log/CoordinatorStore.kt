package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

// design-single-file.md §2 transaction shape: bounded per-process mutex, then the OS
// coordinator gate, data file opened/closed INSIDE, tail repaired, header re-validated.
// Daily budget/exhaustion, outcomes and retention land with their own test batches.
class CoordinatorStore(private val rootDirectory: File, private val onCoordinatorLock: (() -> Unit)? = null) {

    init { rootDirectory.mkdirs() }

    fun append(name: String, frames: List<ByteArray>) = withLocalLock {
        withGate {
            onCoordinatorLock?.invoke()
            RandomAccessFile(target(name), "rw").use { transact(it, frames) }
        }
    }

    fun readFrames(name: String): List<Frame> = withLocalLock {
        withGate {
            val file = target(name)
            if (!file.isFile) {
                emptyList()
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    // shortcut: reads the whole file into memory; ceiling = memory on multi-MB day files,
                    // upgrade path = streaming scan under the same gate.
                    val bytes = ByteArray(raf.length().toInt())
                    if (bytes.isNotEmpty()) {
                        raf.seek(0)
                        raf.readFully(bytes)
                    }
                    FrameCodec.scanRecovery(bytes).frames
                }
            }
        }
    }

    fun delete(name: String): Boolean = withLocalLock {
        withGate { target(name).delete() }
    }

    private fun transact(raf: RandomAccessFile, frames: List<ByteArray>) {
        val effective = ArrayList(frames)
        if (raf.length() == 0L) {
            effective.add(0, DayProtocol.header().toByteArray(Charsets.UTF_8))
        } else {
            validateHeader(raf)
            val discarded = repairTail(raf)
            if (discarded > 0) effective.add(0, FrameCodec.encode(0, "recovery", "discarded=$discarded"))
        }
        raf.seek(raf.length())
        effective.forEach { frame ->
            val buffer = ByteBuffer.wrap(frame)
            while (buffer.hasRemaining()) raf.channel.write(buffer)
        }
    }

    private fun validateHeader(raf: RandomAccessFile) {
        raf.seek(0)
        val head = ByteArray(minOf(raf.length(), 64L).toInt())
        raf.readFully(head)
        val eol = head.indexOf('\n'.code.toByte())
        val line = String(head, 0, if (eol >= 0) eol else head.size, Charsets.UTF_8)
        val first = FrameCodec.decode(line)
        if (first == null || !FrameCodec.isDayHeader(first)) {
            throw IOException("protocol mismatch: day files must start with the P1 header")
        }
    }

    // Bytes after the final newline can never form a complete frame, so truncating them
    // is the demonstrably invalid-suffix rule; earlier complete frames always survive.
    // shortcut: only the last 64 KiB are inspected; a torn line longer than that is left
    // in place and read-time scanRecovery handles it. Upgrade: per-frame length index.
    private fun repairTail(raf: RandomAccessFile): Long {
        val size = raf.length()
        if (size == 0L) return 0
        val windowStart = (size - TAIL_REPAIR_WINDOW).coerceAtLeast(0)
        val window = ByteArray((size - windowStart).toInt())
        raf.seek(windowStart)
        raf.readFully(window)
        if (window.last() == '\n'.code.toByte()) return 0
        val lastNl = window.indexOfLast { it == '\n'.code.toByte() }
        val keep = if (lastNl >= 0) windowStart + lastNl + 1 else windowStart
        if (keep == size) return 0
        raf.setLength(keep)
        return size - keep
    }

    private fun target(name: String): File {
        require(name != FileSystemLogStore.LIFECYCLE_LOCK_NAME && File(name).name == name)
        return File(rootDirectory, name).canonicalFile
    }

    private fun <T> withLocalLock(action: () -> T): T {
        val locked = try {
            LOCAL_LOCK.tryLock(2_000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("log coordination interrupted", e)
        }
        if (!locked) throw IOException("local log coordination timed out")
        return try { action() } finally { LOCAL_LOCK.unlock() }
    }

    private fun <T> withGate(action: () -> T): T {
        rootDirectory.mkdirs()
        return RandomAccessFile(File(rootDirectory, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw").use { gate ->
            var lock = gate.channel.tryLock()
            var attempt = 0
            val deadline = System.nanoTime() + 2_000_000_000L
            while (lock == null && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(minOf(50, 5L shl minOf(attempt++, 4)) + Random.nextLong(0, 5))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("log coordination interrupted", e)
                }
                lock = gate.channel.tryLock()
            }
            val acquired = lock ?: throw IOException("log coordination timed out")
            try { action() } finally { acquired.release() }
        }
    }

    companion object {
        private val LOCAL_LOCK = ReentrantLock()
        private const val TAIL_REPAIR_WINDOW = 64L * 1024
    }
}
