package xtr.keymapper.log

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

data class LogFileInfo(
    val name: String,
    val sizeBytes: Long,
)

// Synthetic stores have no coordinator filesystem root.
interface LogStore {
    val directory: File? get() = null

    fun listFiles(): List<LogFileInfo>

    fun openAppendWriter(name: String): LogFileWriter

    fun readTail(name: String, maxLines: Int): List<String>

    fun delete(name: String): Boolean

    fun delete(name: String, eligible: () -> Boolean): Boolean = eligible() && delete(name)
}

interface LogFileWriter : Closeable {
    val writtenBytes: Long

    fun write(chunk: ByteArray)
}

class FileSystemLogStore internal constructor(
    private val rootDirectory: File,
    private val tryFileLock: (FileChannel) -> FileLock?,
) : LogStore {

    constructor(rootDirectory: File) : this(rootDirectory, { it.tryLock() })

    override val directory: File get() = rootDirectory

    init {
        // mkdirs() is idempotent; failure surfaces later at openAppendWriter.
        try {
            rootDirectory.mkdirs()
        } catch (e: Exception) {
            // Treated later by openAppendWriter's failure handling.
        }
    }

    override fun listFiles(): List<LogFileInfo> {
        val files = rootDirectory.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name != LIFECYCLE_LOCK_NAME }
            .map { LogFileInfo(it.name, it.length()) }
            .sortedBy { it.name }
    }

    override fun openAppendWriter(name: String): LogFileWriter {
        var opened: LogFileWriter? = null
        try {
            return withLifecycleLock {
                val file = target(name)
                if (file in activeFiles) throw IOException("$name is already open for writing")
                val handle = RandomAccessFile(file, "rw")
                try {
                    acquire(handle.channel) ?: throw IOException("$name is already locked")
                    val initialBytes = handle.length()
                    handle.seek(initialBytes)
                    val writer = object : LogFileWriter {
                        private val tracked = java.util.concurrent.atomic.AtomicLong(initialBytes)
                        private var closed = false
                        override val writtenBytes: Long get() = tracked.get()

                        override fun write(chunk: ByteArray) = synchronized(handle) {
                            if (closed) throw IOException("$name is closed")
                            handle.write(chunk)
                            tracked.addAndGet(chunk.size.toLong())
                            Unit
                        }

                        override fun close() = synchronized(lifecycleMonitor) {
                            synchronized(handle) {
                                if (!closed) {
                                    closed = true
                                    try { handle.close() } finally { activeFiles.remove(file) }
                                }
                            }
                        }
                    }
                    activeFiles[file] = handle
                    opened = writer
                    writer
                } catch (e: Exception) {
                    try { handle.close() } catch (close: Exception) { e.addSuppressed(close) }
                    throw e
                }
            }
        } catch (e: Exception) {
            // Also close the writer if releasing the directory gate failed.
            try { opened?.close() } catch (close: Exception) { e.addSuppressed(close) }
            throw e
        }
    }

    override fun readTail(name: String, maxLines: Int): List<String> = try {
        synchronized(lifecycleMonitor) {
            val file = target(name)
            val active = activeFiles[file]
            val bytes = if (active != null) {
                synchronized(active) { readTailBytes(active.channel) }
            } else {
                RandomAccessFile(file, "r").use { readTailBytes(it.channel) }
            }
            decodeTrailingLines(bytes, maxLines)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun readTailBytes(channel: FileChannel): ByteArray {
        val size = channel.size()
        val buffer = ByteBuffer.allocate(minOf(size, READ_TAIL_MAX_BYTES).toInt())
        val start = (size - buffer.capacity()).coerceAtLeast(0)
        while (buffer.hasRemaining()) {
            // Positional reads leave the writer's append position untouched.
            if (channel.read(buffer, start + buffer.position()) <= 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }

    override fun delete(name: String): Boolean = delete(name) { true }

    override fun delete(name: String, eligible: () -> Boolean): Boolean = try {
        withLifecycleLock {
            val file = target(name)
            if (!eligible() || file in activeFiles || !file.isFile) false else RandomAccessFile(file, "rw").use { handle ->
                acquire(handle.channel)?.use { file.delete() } ?: false
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun target(name: String): File {
        require(name != LIFECYCLE_LOCK_NAME && name != "." && name != ".." && File(name).name == name)
        return File(rootDirectory, name).canonicalFile
    }

    private fun acquire(channel: FileChannel): FileLock? = try {
        tryFileLock(channel)
    } catch (e: OverlappingFileLockException) {
        throw IOException("log coordination already held in this JVM", e)
    }

    private fun <T> withLifecycleLock(action: () -> T): T = synchronized(lifecycleMonitor) {
        rootDirectory.mkdirs()
        // Permanent, one per directory: never unlink or rotate this inode.
        RandomAccessFile(File(rootDirectory, LIFECYCLE_LOCK_NAME), "rw").use { gate ->
            val deadline = System.nanoTime() + 250_000_000L
            var lock = acquire(gate.channel)
            while (lock == null && System.nanoTime() < deadline) {
                try { Thread.sleep(5) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("log coordination interrupted", e)
                }
                lock = acquire(gate.channel)
            }
            // shortcut: contention beyond 250ms fails the sink; add retry on explicit re-enable if needed.
            (lock ?: throw IOException("log directory coordination timed out")).use { action() }
        }
    }

    companion object {
        internal const val LIFECYCLE_LOCK_NAME = ".xtlogger.lock"
        private val lifecycleMonitor = Any()
        private val activeFiles = HashMap<File, RandomAccessFile>()

        const val READ_TAIL_MAX_BYTES = 4L * 1024 * 1024

        fun decodeTrailingLines(bytes: ByteArray, maxLines: Int): List<String> {
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.split('\n')
            val lastIdx = lines.lastIndex
            val trailing = ArrayList<String>(maxLines.coerceAtMost(4096))
            var i = lastIdx
            while (i >= 0 && trailing.size < maxLines) {
                val line = lines[i]
                // Skip the (normally empty) trailing chunk after the final \n.
                if (i == lastIdx && line.isEmpty()) { i--; continue }
                trailing.add(line)
                i--
            }
            trailing.reverse()
            return trailing
        }
    }
}
