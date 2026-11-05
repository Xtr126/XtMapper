package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

// design-single-file.md §2 transaction shape: bounded per-process mutex RELEASED between
// OS attempts, one gate tryLock per attempt, the data file opened/closed INSIDE the gate,
// corruption classified before any byte change (pure unterminated suffix is the only
// repairable region), and Deadline-enforced bounded acquisition. The shared daily budget
// (§2 step 5) is accounted under the same lock; outcomes/retention land with their batches.

// §8 outcome classes: an acquisition timeout is a retryable NOT-ATTEMPTED failure (no bytes
// touched, sink recovers on the next batch); a day-integrity refusal is a real sink-down
// class for the offending day. Both are IOExceptions, so IOException catchers are unaffected.
class CoordinationTimeoutException(message: String) : IOException(message)

class DayIntegrityBlockedException(message: String, val fileName: String? = null) : IOException(message)

class CoordinatorStore(
    private val rootDirectory: File,
    private val onCoordinatorLock: (() -> Unit)? = null,
    private val coordinationDeadlineNanos: Long = COORDINATION_DEADLINE_NANOS,
    private val clock: () -> Long = { System.nanoTime() },
    private val maxDailyBytes: Long = DailyBudget.DEFAULT_DAILY_CAP_BYTES,
    private val reserveBytes: Long = DailyBudget.RESERVE_BYTES,
) {

    init { rootDirectory.mkdirs() }

    private val budget = DailyBudget(maxDailyBytes, reserveBytes)

    internal var writeChannel: (FileChannel, ByteBuffer) -> Int = { channel, buffer -> channel.write(buffer) }
    internal var forceChannel: (FileChannel) -> Unit = { it.force(true) }

    fun append(name: String, frames: List<ByteArray>): DailyBudget.Verdict {
        validateFrames(frames)
        return append(name, null) { _, _ -> frames }.second
    }

    internal fun append(
        name: String,
        fallbackName: String?,
        framesFor: (String, List<Frame>) -> List<ByteArray>,
    ): Pair<String, DailyBudget.Verdict> = coordinated { gate ->
        onCoordinatorLock?.invoke()
        val selected = if (fallbackName != null && !target(name).isFile) fallbackName else name
        val receipt = migrationReceipts(gate, selected)[selected]
        val file = target(selected)
        if (receipt != null && !file.isFile) {
            throw DayIntegrityBlockedException("migration receipt has no original file ($selected)", selected)
        }
        RandomAccessFile(file, "rw").use { raf ->
            selected to transact(raf, selected, gate, receipt) { persisted ->
                framesFor(selected, persisted).also(::validateFrames)
            }
        }
    }

    private fun validateFrames(frames: List<ByteArray>) {
        frames.forEach { frame ->
            val parsed = FrameCodec.decode(frame.decodeToString().removeSuffix("\n"))
                ?: throw IllegalArgumentException("frame violates the envelope grammar")
            require(frame.contentEquals(FrameCodec.encode(parsed.seq, parsed.sid, parsed.payload))) {
                "frame is not canonically encoded"
            }
        }
    }

    fun readFrames(name: String): List<Frame> = readRecovery(name).frames

    fun readRecovery(name: String): FrameRecovery = coordinated { gate ->
        readRecoveryLocked(target(name), migrationReceipts(gate, name)[name])
    }

    internal fun readHistory(directory: File): List<FrameRecovery> = coordinated { gate ->
        require(directory.canonicalFile == rootDirectory.canonicalFile)
        val receipts = migrationReceipts(gate)
        val files = directory.listFiles() ?: throw IOException("log history could not be enumerated")
        files.filter { it.isFile && LogFileNames.parse(it.name) != null }.sortedBy { it.name }
            .map { readRecoveryLocked(target(it.name), receipts[it.name]) }
    }

    private fun readRecoveryLocked(file: File, receipt: MigrationReceipt?): FrameRecovery =
        if (!file.isFile) {
            if (receipt != null) throw DayIntegrityBlockedException("migration receipt has no original file (${file.name})", file.name)
            FrameRecovery(emptyList(), 0)
        } else {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = readBytes(raf)
                val offset = receipt?.let { migrationOffset(bytes, it, file.name) } ?: 0
                FrameCodec.scanRecovery(bytes.copyOfRange(minOf(offset, bytes.size), bytes.size))
            }
        }

    fun delete(name: String, eligible: () -> Boolean = { true }): Boolean = coordinated { gate ->
        if (!eligible()) false else {
            if (migrationReceipts(gate, name).containsKey(name)) writeReceipt(gate, "D1|$name")
            target(name).delete()
        }
    }

    private fun transact(
        raf: RandomAccessFile,
        name: String,
        gate: RandomAccessFile,
        receipt: MigrationReceipt?,
        framesFor: (List<Frame>) -> List<ByteArray>,
    ): DailyBudget.Verdict {
        val effective = ArrayList<ByteArray>()
        val header = DayProtocol.header().encodeToByteArray()
        val bytes = readBytes(raf)
        var prefixBytes = 0
        var originalLength = 0
        var newReceipt: String? = null
        var rebuildHeader = false
        val protocolBytes = when {
            receipt != null -> {
                prefixBytes = migrationOffset(bytes, receipt, name)
                originalLength = receipt.length
                rebuildHeader = bytes.size.toLong() < prefixBytes.toLong() + header.size
                if (rebuildHeader) byteArrayOf() else bytes.copyOfRange(prefixBytes, bytes.size)
            }
            bytes.isEmpty() || (bytes.size < header.size && bytes.contentEquals(header.copyOf(bytes.size))) -> {
                rebuildHeader = true
                byteArrayOf()
            }
            hasDayHeader(bytes) -> bytes
            else -> {
                if (containsFrameRegion(bytes)) {
                    throw DayIntegrityBlockedException("preexisting frame region blocks legacy migration ($name)", name)
                }
                val day = LogFileNames.parse(name)
                if (day == null || name != LogFileNames.dailyName(day.date, null)) {
                    throw DayIntegrityBlockedException("legacy migration requires a daily filename ($name)", name)
                }
                originalLength = bytes.size
                prefixBytes = originalLength + if (bytes.last() == '\n'.code.toByte()) 0 else 1
                newReceipt = "M1|$name|$originalLength|${prefixHash(bytes, originalLength)}"
                rebuildHeader = true
                byteArrayOf()
            }
        }
        if (rebuildHeader && prefixBytes.toLong() + header.size > maxDailyBytes) {
            throw DayIntegrityBlockedException("daily cap cannot hold the legacy migration boundary ($name)", name)
        }
        val scan = FrameCodec.scanRecovery(protocolBytes)
        val persisted = scan.frames
        var tailDiscard = 0L
        if (rebuildHeader) effective += header else {
            if (persisted.firstOrNull()?.let(FrameCodec::isDayHeader) != true) {
                throw DayIntegrityBlockedException("protocol mismatch: day files must start with the P1 header ($name)", name)
            }
            if (scan.discardedBytes > 0) {
                val validPrefixEnd = protocolBytes.size.toLong() - scan.discardedBytes
                val finalNl = protocolBytes.lastIndexOf('\n'.code.toByte())
                val suffixBytes = protocolBytes.size.toLong() - (finalNl + 1L)
                if (validPrefixEnd != finalNl + 1L) {
                    if (receipt != null && !String(protocolBytes, validPrefixEnd.toInt(), minOf(3, scan.discardedBytes.toInt()), Charsets.UTF_8).startsWith("X1 ")) {
                        throw DayIntegrityBlockedException("legacy writer detected after migration marker ($name)", name)
                    }
                    throw DayIntegrityBlockedException(
                        "day file $name holds unrecoverable corruption after a checksum-failing line; " +
                            "writes are blocked for this day (retention prunes it by age), the valid prefix stays readable",
                        name,
                    )
                }
                if (suffixBytes >= BOUNDARY_SEARCH) {
                    throw DayIntegrityBlockedException("corrupt tail: no frame boundary inside the search window; refusing repair", name)
                }
                tailDiscard = scan.discardedBytes
                effective.add(0, FrameCodec.encode(0, "recovery", "discarded=$suffixBytes"))
            }
        }
        effective.addAll(framesFor(persisted))
        val entries = effective.map { bytes ->
            // Candidates were contract-validated on entry (or synthesized above), so decode cannot fail.
            DailyBudget.Entry(DailyBudget.kindOf(FrameCodec.decode(bytes.decodeToString().removeSuffix("\n"))!!), bytes)
        }
        // Selection happens before truncation: a rejected recovery frame must keep the torn
        // suffix intact — appending after an untruncated tail merges into an undecodable line.
        val verdict = budget.charge(persisted, entries, prefixBytes.toLong())
        if (verdict.recoveryUnpersisted) return verdict
        if (newReceipt != null) writeReceipt(gate, newReceipt)
        else if (receipt != null && rebuildHeader) forceChannel(gate.channel)
        val keepBytes = if (rebuildHeader) originalLength.toLong() else bytes.size - tailDiscard
        if (keepBytes != raf.length()) raf.setLength(keepBytes)
        raf.seek(raf.length())
        if (rebuildHeader && prefixBytes > originalLength) writeBytes(raf, byteArrayOf('\n'.code.toByte()))
        verdict.frames.forEach { writeBytes(raf, it) }
        return verdict
    }

    private data class MigrationReceipt(val length: Int, val hash: String)

    private fun migrationReceipts(gate: RandomAccessFile, name: String? = null): Map<String, MigrationReceipt?> {
        val scan = FrameCodec.scanRecovery(readBytes(gate))
        fun invalid(): Nothing = throw DayIntegrityBlockedException("invalid migration receipt journal", name)
        if (scan.discardedBytes != 0L) invalid()
        val receipts = HashMap<String, MigrationReceipt?>()
        for (frame in scan.frames) {
            val fields = frame.payload.split('|')
            val fileName = fields.getOrNull(1) ?: invalid()
            val day = LogFileNames.parse(fileName) ?: invalid()
            if (frame.sid != "migration" || frame.seq != 0L || fileName != LogFileNames.dailyName(day.date, null)) invalid()
            when (fields[0]) {
                "M1" -> {
                    val length = fields.getOrNull(2)?.toIntOrNull() ?: invalid()
                    val hash = fields.getOrNull(3) ?: invalid()
                    if (fields.size != 4 || length <= 0 || !hash.matches(Regex("[0-9a-f]{64}")) || receipts[fileName] != null) invalid()
                    receipts[fileName] = MigrationReceipt(length, hash)
                }
                "D1" -> {
                    if (fields.size != 2) invalid()
                    receipts[fileName] = null
                }
                else -> invalid()
            }
        }
        return receipts
    }

    private fun migrationOffset(bytes: ByteArray, receipt: MigrationReceipt, name: String): Int {
        if (bytes.size < receipt.length || prefixHash(bytes, receipt.length) != receipt.hash) {
            throw DayIntegrityBlockedException("migration receipt original prefix mismatch ($name)", name)
        }
        val alignment = if (bytes[receipt.length - 1] == '\n'.code.toByte()) byteArrayOf() else byteArrayOf('\n'.code.toByte())
        val boundary = alignment + DayProtocol.header().encodeToByteArray()
        val present = minOf(boundary.size, bytes.size - receipt.length)
        if (!bytes.copyOfRange(receipt.length, receipt.length + present).contentEquals(boundary.copyOf(present))) {
            throw DayIntegrityBlockedException("migration receipt boundary mismatch ($name)", name)
        }
        return receipt.length + alignment.size
    }

    private fun writeReceipt(gate: RandomAccessFile, payload: String) {
        gate.seek(gate.length())
        writeBytes(gate, FrameCodec.encode(0, "migration", payload))
        forceChannel(gate.channel)
    }

    private fun writeBytes(raf: RandomAccessFile, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (writeChannel(raf.channel, buffer) <= 0) throw IOException("log write made no progress")
        }
    }

    private fun readBytes(raf: RandomAccessFile): ByteArray {
        // shortcut: scans whole files under the gate; stream when per-append scan cost becomes material.
        if (raf.length() > Int.MAX_VALUE) throw IOException("log file is too large to inspect")
        val bytes = ByteArray(raf.length().toInt())
        raf.seek(0)
        raf.readFully(bytes)
        return bytes
    }

    private fun prefixHash(bytes: ByteArray, length: Int): String =
        MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, length) }.digest().joinToString("") { "%02x".format(it) }

    private fun hasDayHeader(bytes: ByteArray): Boolean {
        val eol = bytes.indexOf('\n'.code.toByte())
        return eol in 0 until 64 && FrameCodec.decode(String(bytes, 0, eol, Charsets.UTF_8))?.let(FrameCodec::isDayHeader) == true
    }

    private fun containsFrameRegion(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.ISO_8859_1)
        val envelopes = Regex("X1 [0-9]+ [0-9A-Za-z-]{1,40} ([0-9]+) [0-9a-f]{8} ")
        for (match in envelopes.findAll(text)) {
            val length = match.groupValues[1].toIntOrNull() ?: continue
            if (length !in 0..FrameCodec.MAX_PAYLOAD_BYTES) continue
            var end = match.range.last + 1
            var rawBytes = 0
            while (rawBytes < length && end < bytes.size && bytes[end] != '\n'.code.toByte()) {
                if (bytes[end] == '\\'.code.toByte()) {
                    if (bytes.getOrNull(end + 1)?.toInt()?.toChar() !in listOf('\\', 'n', 'r')) break
                    end++
                }
                end++
                rawBytes++
            }
            if (rawBytes == length && FrameCodec.decode(String(bytes, match.range.first, end - match.range.first, Charsets.UTF_8)) != null) return true
        }
        return false
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
    // Gate ownership: the descriptor is opened once per root by the process-wide owner
    // and never closed here, so a failed attempt can never free a same-process holder's
    // lock via close-release.
    private fun <T> coordinated(work: (RandomAccessFile) -> T): T {
        val deadline = clock() + coordinationDeadlineNanos
        var attempt = 0
        while (true) {
            val remainingNanos = deadline - clock()
            if (remainingNanos <= 0) throw CoordinationTimeoutException("log coordination timed out")
            var verdict: T? = null
            var entered = false
            val localWaitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtMost(LOCAL_ATTEMPT_MILLIS)
            acquireLocal(localWaitMillis) {
                // Expired past the mutex: this attempt touches no gate I/O at all.
                if (clock() >= deadline) return@acquireLocal
                // Process-wide owner, lazily opened inside the mutex, never closed.
                val gate = gateFor(rootDirectory)
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
                            verdict = work(gate)
                        }
                    } finally { lock.release() }
                }
            }
            if (entered) return verdict as T
            val waitNanos = deadline - clock()
            if (waitNanos <= 0) throw CoordinationTimeoutException("log coordination timed out")
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
        // Process-wide gate owner keyed by canonical root (review round 14): ONE
        // descriptor per root per process, opened inside the coordination mutex and
        // NEVER closed — closing any descriptor for the inode can free this process's
        // POSIX-record locks on it (stripping a sibling holder's, e.g. legacy store's,
        // exclusion), so no coordinator close may ever happen. Deliberately leaked:
        // one fd per root, process lifetime, bounded — refcount+close is not a safe
        // alternative because a close at zero can still strip a sibling's held lock.
        private val GATES = ConcurrentHashMap<String, RandomAccessFile>()

        private fun gateFor(root: File): RandomAccessFile =
            GATES.computeIfAbsent(root.canonicalFile.path) {
                RandomAccessFile(File(it, FileSystemLogStore.LIFECYCLE_LOCK_NAME), "rw")
            }
        private const val BOUNDARY_SEARCH = 4L * FrameCodec.MAX_FRAME_BYTES
        private const val COORDINATION_DEADLINE_NANOS = 2_000_000_000L
        private const val LOCAL_ATTEMPT_MILLIS = 500L
    }
}
