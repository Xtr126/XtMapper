package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

// design-single-file.md §2 transaction shape: bounded per-process mutex RELEASED between
// OS attempts, one gate tryLock per attempt, the data file opened/closed INSIDE the gate,
// tail repaired only at a provable frame boundary, and corruption blocks writes honestly
// instead of being silently re-scanned. Budget/outcomes/retention land with their batches.
class CoordinatorStore(private val rootDirectory: File, private val onCoordinatorLock: (() -> Unit)? = null) {

    init { rootDirectory.mkdirs() }

    fun append(name: String, frames: List<ByteArray>) = coordinated {
        onCoordinatorLock?.invoke()
        RandomAccessFile(target(name), "rw").use { transact(it, name, frames) }
    }

    fun readFrames(name: String): List<Frame> = readRecovery(name).frames

    fun readRecovery(name: String): FrameRecovery = coordinated {
        val file = target(name)
        if (!file.isFile) {
            FrameRecovery(emptyList(), 0)
        } else {
            // shortcut: reads the whole file into memory; ceiling = memory on multi-MB day files,
            // upgrade = streaming scan under the same gate.
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(raf.length().toInt())
                if (bytes.isNotEmpty()) {
                    raf.seek(0)
                    raf.readFully(bytes)
                }
                FrameCodec.scanRecovery(bytes)
            }
        }
    }

    fun delete(name: String): Boolean = coordinated { target(name).delete() }

    private fun transact(raf: RandomAccessFile, name: String, frames: List<ByteArray>) {
        val effective = ArrayList(frames)
        if (raf.length() == 0L) {
            effective.add(0, DayProtocol.header().toByteArray(Charsets.UTF_8))
        } else {
            validateHeader(raf, name)
            val discarded = repairTail(raf)
            if (discarded > 0) effective.add(0, FrameCodec.encode(0, "recovery", "discarded=$discarded"))
            if (corruptRegionExists(raf)) {
                throw IOException(
                    "day file $name holds unrecoverable corruption after a checksum-failing line; " +
                        "writes are blocked for this day (retention prunes it by age), the valid prefix stays readable",
                )
            }
        }
        raf.seek(raf.length())
        effective.forEach { frame ->
            val buffer = ByteBuffer.wrap(frame)
            while (buffer.hasRemaining()) raf.channel.write(buffer)
        }
    }

    private fun validateHeader(raf: RandomAccessFile, name: String) {
        raf.seek(0)
        val head = ByteArray(minOf(raf.length(), 64L).toInt())
        raf.readFully(head)
        val eol = head.indexOf('\n'.code.toByte())
        val line = String(head, 0, if (eol >= 0) eol else head.size, Charsets.UTF_8)
        val first = FrameCodec.decode(line)
        if (first == null || !FrameCodec.isDayHeader(first)) {
            throw IOException("protocol mismatch: day files must start with the P1 header ($name)")
        }
    }

    // shortcut: full-file scan per transaction; ceiling = bytes-per-append cost,
    // upgrade = incremental scan windows with a persisted validation cursor.
    private fun corruptRegionExists(raf: RandomAccessFile): Boolean {
        val bytes = ByteArray(raf.length().toInt())
        if (bytes.isEmpty()) return false
        raf.seek(0)
        raf.readFully(bytes)
        return FrameCodec.scanRecovery(bytes).discardedBytes > 0
    }

    // Bytes after the final newline can never form a complete frame, so truncating them is
    // the demonstrably invalid-suffix rule; complete frames always survive. The boundary
    // search reaches 4 × MAX_FRAME_BYTES; a tail WITHOUT any boundary inside it is refused
    // without modifying a single byte (never cut what the protocol cannot interpret).
    private fun repairTail(raf: RandomAccessFile): Long {
        val size = raf.length()
        if (size == 0L) return 0
        val windowStart = (size - BOUNDARY_SEARCH).coerceAtLeast(0)
        val window = ByteArray((size - windowStart).toInt())
        raf.seek(windowStart)
        raf.readFully(window)
        val lastNl = window.indexOfLast { it == '\n'.code.toByte() }
        if (lastNl < 0) {
            throw IOException("corrupt tail: no frame boundary inside the search window; refusing repair")
        }
        val keep = windowStart + lastNl + 1
        if (keep == size) return 0
        raf.setLength(keep)
        return size - keep
    }

    private fun target(name: String): File {
        require(name != FileSystemLogStore.LIFECYCLE_LOCK_NAME && File(name).name == name)
        return File(rootDirectory, name).canonicalFile
    }

    // Bounded acquisition with the LOCAL MUTEX RELEASED BETWEEN ATTEMPTS: each attempt
    // under a short local tryLock performs ONE gate tryLock and releases everything —
    // failures sleep outside the local mutex; a caller never parks inside coordination.
    private fun <T> coordinated(work: () -> T): T {
        val deadline = System.nanoTime() + COORDINATION_DEADLINE_NANOS
        var attempt = 0
        while (true) {
            var verdict: T? = null
            var entered = false
            acquireLocal {
                RandomAccessFile(File(rootDirectory, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw").use { gate ->
                    val lock = gate.channel.tryLock()
                    if (lock != null) {
                        entered = true
                        try { verdict = work() } finally { lock.release() }
                    }
                }
            }
            if (entered) return verdict as T
            if (System.nanoTime() >= deadline) throw IOException("log coordination timed out")
            try {
                Thread.sleep(minOf(50, 5L shl minOf(attempt++, 4)) + Random.nextLong(0, 5))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("log coordination interrupted", e)
            }
        }
    }

    // Local-busy is a failed attempt (no park-drift): body simply never runs this round.
    private fun acquireLocal(body: () -> Unit) {
        val locked = try {
            LOCAL_LOCK.tryLock(LOCAL_ATTEMPT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("log coordination interrupted", e)
        }
        if (locked) {
            try { body() } finally { LOCAL_LOCK.unlock() }
        }
    }

    companion object {
        private val LOCAL_LOCK = ReentrantLock()
        private const val BOUNDARY_SEARCH = 4L * FrameCodec.MAX_FRAME_BYTES
        private const val COORDINATION_DEADLINE_NANOS = 2_000_000_000L
        private const val LOCAL_ATTEMPT_MILLIS = 500L
    }
}
