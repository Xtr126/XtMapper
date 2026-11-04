package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

// design-single-file.md §2 transaction shape: bounded per-process mutex RELEASED between
// OS attempts, one gate tryLock per attempt, the data file opened/closed INSIDE the gate,
// corruption classified before any byte change (pure unterminated suffix is the only
// repairable region), and Deadline-enforced bounded acquisition. Budget/outcomes/retention
// land with their batches.
class CoordinatorStore(
    private val rootDirectory: File,
    private val onCoordinatorLock: (() -> Unit)? = null,
    private val coordinationDeadlineNanos: Long = COORDINATION_DEADLINE_NANOS,
    private val clock: () -> Long = { System.nanoTime() },
) {

    init { rootDirectory.mkdirs() }

    // Opened once per instance and NEVER closed by the coordinator: closing any
    // descriptor for the inode frees this process's POSIX-record locks on it, stripping
    // a same-process gate holder's (e.g. legacy lifetime store's) exclusion.
    private val gate: RandomAccessFile by lazy {
        RandomAccessFile(File(rootDirectory, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw")
    }

    fun append(name: String, frames: List<ByteArray>) {
        // Contract validation happens BEFORE any coordination: violations never touch the file.
        frames.forEach { frame ->
            val parsed = FrameCodec.decode(frame.decodeToString().removeSuffix("\n"))
                ?: throw IllegalArgumentException("frame violates the envelope grammar")
            require(frame.contentEquals(FrameCodec.encode(parsed.seq, parsed.sid, parsed.payload))) {
                "frame is not canonically encoded"
            }
        }
        coordinated {
            onCoordinatorLock?.invoke()
            RandomAccessFile(target(name), "rw").use { transact(it, name, frames) }
        }
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
        val header = DayProtocol.header().toByteArray(Charsets.UTF_8)
        // Torn header write: an exact 1..size-1 prefix truncates here so the empty path rewrites it; other short content fails validation.
        if (raf.length() in 1L until header.size.toLong()) {
            val head = ByteArray(raf.length().toInt())
            raf.seek(0)
            raf.readFully(head)
            if (head.contentEquals(header.copyOfRange(0, head.size))) raf.setLength(0)
        }
        if (raf.length() == 0L) {
            effective.add(0, header)
        } else {
            validateHeader(raf, name)
            // Full-file classification happens BEFORE any byte change: only a PURE
            // unterminated suffix inside the search window is repairable (truncated
            // exactly at the last complete frame + recovery frame). Complete-line
            // corruption anywhere refuses the transaction byte-identical.
            // shortcut: full-file scan per transaction; ceiling = bytes-per-append cost,
            // upgrade = incremental scan windows with a persisted validation cursor.
            val bytes = ByteArray(raf.length().toInt())
            raf.seek(0)
            raf.readFully(bytes)
            val scan = FrameCodec.scanRecovery(bytes)
            if (scan.discardedBytes > 0) {
                val validPrefixEnd = bytes.size.toLong() - scan.discardedBytes
                val finalNl = bytes.lastIndexOf('\n'.code.toByte())
                val suffixBytes = bytes.size.toLong() - (finalNl + 1L)
                if (validPrefixEnd != finalNl + 1L) {
                    throw IOException(
                        "day file $name holds unrecoverable corruption after a checksum-failing line; " +
                            "writes are blocked for this day (retention prunes it by age), the valid prefix stays readable",
                    )
                }
                if (suffixBytes >= BOUNDARY_SEARCH) {
                    throw IOException("corrupt tail: no frame boundary inside the search window; refusing repair")
                }
                raf.setLength(validPrefixEnd)
                effective.add(0, FrameCodec.encode(0, "recovery", "discarded=$suffixBytes"))
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

    private fun target(name: String): File {
        require(name != FileSystemLogStore.LIFECYCLE_LOCK_NAME && File(name).name == name)
        return File(rootDirectory, name).canonicalFile
    }

    // Bounded acquisition with the LOCAL MUTEX RELEASED BETWEEN ATTEMPTS: per attempt a
    // short local tryLock (bounded by the REMAINING deadline) performs ONE gate tryLock
    // and releases everything. The deadline is re-checked after the local acquisition,
    // BEFORE any gate I/O, and both the local wait and the backoff sleep stay inside the
    // remainder — a caller never parks beyond its deadline inside coordination.
    // Gate ownership: see `gate` — the descriptor is never closed here, so a failed
    // attempt can never free a same-process holder's lock via close-release.
    private fun <T> coordinated(work: () -> T): T {
        val deadline = clock() + coordinationDeadlineNanos
        var attempt = 0
        while (true) {
            val remainingNanos = deadline - clock()
            if (remainingNanos <= 0) throw IOException("log coordination timed out")
            var verdict: T? = null
            var entered = false
            val localWaitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtMost(LOCAL_ATTEMPT_MILLIS)
            acquireLocal(localWaitMillis) {
                // Expired past the mutex: this attempt touches no gate I/O at all.
                if (clock() >= deadline) return@acquireLocal
                val lock = try {
                    gate.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    // Same-JVM holder (e.g. the lifetime store's channel): a failed attempt; no close follows, so the holder's lock survives.
                    null
                }
                if (lock != null) {
                    try {
                        // Post-acquisition deadline check: expired ⇒ release, no data work.
                        if (clock() < deadline) {
                            entered = true
                            verdict = work()
                        }
                    } finally { lock.release() }
                }
            }
            if (entered) return verdict as T
            val waitNanos = deadline - clock()
            if (waitNanos <= 0) throw IOException("log coordination timed out")
            val backoffMillis =
                (minOf(50, 5L shl minOf(attempt++, 4)) + Random.nextLong(0, 5))
                    .coerceAtLeast(1)
                    .coerceAtMost(TimeUnit.NANOSECONDS.toMillis(waitNanos) + 1)
            try {
                Thread.sleep(backoffMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("log coordination interrupted", e)
            }
        }
    }

    // Local-busy is a failed attempt (no park-drift): body simply never runs this round.
    private fun acquireLocal(waitMillis: Long, body: () -> Unit) {
        val locked = try {
            LOCAL_LOCK.tryLock(waitMillis, TimeUnit.MILLISECONDS)
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
