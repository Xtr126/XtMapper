package xtr.keymapper.log

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Name and size of one existing log file as seen by the store. */
data class LogFileInfo(
    val name: String,
    /** Size in bytes; -1 when unknown (never used for rotation thresholds). */
    val sizeBytes: Long,
)

/**
 * Storage abstraction behind the persistent log sink.
 *
 * [FileSystemLogStore] — direct `java.io.File` access, used by every execution
 * mode:
 *
 * - the standalone `app_process` server processes (shell uid 2000 / root uid 0,
 *   see `RemoteServiceShell`, `RootRemoteService`) which have no Android
 *   `Context` or `ContentResolver`;
 * - the ordinary application process, which may write to shared storage only
 *   after the user enabled All-files access (`MANAGE_EXTERNAL_STORAGE`);
 * - unit tests, which pass temporary directories.
 *
 * All implementations append additively: an existing daily file is never
 * truncated or overwritten when a new session starts.
 */
interface LogStore {
    /**
     * Snapshot of the log files visible to this store (display names of the log
     * directory). Empty when nothing exists yet or enumeration is unavailable.
     */
    fun listFiles(): List<LogFileInfo>

    /**
     * Opens an append-mode writer for [name], creating the file if missing.
     * Throwing signals a storage failure or a refusal: the file is already
     * coordinated by an active handler in this or another process (see
     * [FileSystemLogStore]). Refusing beats writing un-protected — an
     * un-protected writer no longer uses a lock retention can detect, so its
     * records could be stranded after the referenced pathname is deleted.
     * The persistent sink treats every throw as
     * unavailability and keeps the Logcat path functional.
     */
    fun openAppendWriter(name: String): LogFileWriter

    /**
     * Reads trailing bytes of [name] (oldest first) up to [maxLines] lines and a
     * store-defined byte budget (see [FileSystemLogStore.READ_TAIL_MAX_BYTES]);
     * used for previous-session detection. Returns an empty list when the file
     * is missing or unreadable; failures are swallowed.
     */
    fun readTail(name: String, maxLines: Int): List<String>

    /**
     * Deletes [name] when this store owns deletion rights. Returns false (never
     * throws) when unavailable or when the file is still actively locked by a
     * writer, in this or any other process (see [FileSystemLogStore] for the
     * verified/degraded platform behavior). Callers only pass
     * `LogFileNames.parse`-matched names, so deletion cannot escape the
     * canonical log directory.
     */
    fun delete(name: String): Boolean
}

/**
 * One open append-mode log file handle.
 *
 * [write] receives a complete, self-contained UTF-8 chunk (one or more finished
 * lines ending in `\n`) so each call maps to one append on the underlying
 * storage; the caller rotates by closing and opening the next part file.
 */
interface LogFileWriter : Closeable {
    /** Bytes accounted for this handle since creation (rotation threshold use). */
    val writtenBytes: Long

    /** Appends one pre-encoded UTF-8 chunk ending with `\n`. */
    fun write(chunk: ByteArray)
}

/**
 * Direct-filesystem implementation (shell/root roles and unit tests).
 *
 * File-lifecycle protocol — one exclusive advisory lock per log file, held by
 * exactly the handler that may currently write it (the same platform behavior
 * as the device probe on Android 16 / API 36 shared storage):
 *
 * - every writer locks its file for the handle's whole lifetime. A duplicate or
 *   cross-process open observes the held lock and REFUSES (throws) instead of
 *   continuing un-protected, so no writer can exist whose records retention
 *   does not know to protect;
 * - [delete] unlinks a pathname only while it holds the exclusive lock. A file
 *   held by an active writer — in this or any other process, even one that has
 *   written nothing for days — stays locked and is skipped (fail-closed).
 * - on a filesystem without lock support the activity state cannot be
 *   determined anywhere: writers proceed (logging keeps working) and deletion
 *   fails closed — files are kept, possibly over-retaining (reported by the
 *   pipeline), instead of deleting a possibly-active file.
 *
 * Consequence: a pathname is only ever unlinked while its exclusive lock is
 * held at that instant, so records written through this store never vanish
 * into a still-open, already-unlinked inode (POSIX deletes the name while
 * other file descriptors stay open — unlink(2) semantics).
 */
class FileSystemLogStore(private val rootDirectory: File) : LogStore {

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
        return files.filter { it.isFile }
            .map { LogFileInfo(it.name, it.length()) }
            .sortedBy { it.name }
    }

    override fun openAppendWriter(name: String): LogFileWriter {
        val file = File(rootDirectory, name)
        // The parent directory may not exist yet in tests and fresh installs.
        file.parentFile?.mkdirs()
        val stream: FileOutputStream = FileOutputStream(file, true)
        // Advisory cross-process coordination (verified on Android 16 shared
        // storage; see the class KDoc): an acquired lock guards this handle's
        // whole lifetime, so the retention gate in ANY process can recognize
        // an actively written file even after days without records. POSIX
        // locks are held while open, released on close, and released by the
        // kernel when the process dies — no stale markers.
        //
        // Per FileChannel.tryLock (Java SE contract): null means another
        // program holds the lock; OverlappingFileLockException means THIS JVM
        // already holds it (duplicate handle); any other exception means the
        // lock facility itself failed on this filesystem.
        var lock: FileLock? = null
        var lockUnsupported = false
        try {
            lock = stream.channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            // Coordinated by this JVM's duplicate handle: refuse below.
        } catch (e: IOException) {
            // shortcut: lock facility unavailable (e.g. ENOLCK-style "locks
            // not supported") — this writer proceeds un-protected instead of
            // losing all file logging on exotic storage; safety rests on
            // `delete` seeing the same exception and refusing every deletion
            // there, so retention can never unlink under this writer.
            lockUnsupported = true
        }
        if (lock == null && !lockUnsupported) {
            // Actively coordinated elsewhere (contending foreign process or
            // this JVM's duplicate handle). Proceeding un-protected would
            // route records into a file retention may unlink once that other
            // coordination disappears (POSIX: unlink succeeds while other fds
            // are open, stranding them). Fail this open instead; SessionLogWriter
            // treats the throw as sink unavailability, Logcat stays
            // up. Every production open path closes its previous handle
            // first (rotation, rollover, session end), so refusing breaks no
            // legitimate flow.
            stream.close()
            throw IOException("$name is already open for writing by an active handler")
        }
        return object : LogFileWriter {
            // The on-disk size realigns handles reopened after previous sessions;
            // rotation thresholds therefore see pre-existing content. The counter
            // must keep growing with writes or the rotation logic never fires.
            private val initialBytes = try { file.length() } catch (e: Exception) { 0L }
            private val tracked = java.util.concurrent.atomic.AtomicLong(initialBytes)

            override val writtenBytes: Long get() = tracked.get()

            override fun write(chunk: ByteArray) {
                stream.write(chunk)
                stream.flush()
                tracked.addAndGet(chunk.size.toLong())
            }

            override fun close() {
                try { lock?.release() } catch (e: Exception) {}
                stream.close()
            }
        }
    }

    override fun readTail(name: String, maxLines: Int): List<String> {
        val file = File(rootDirectory, name)
        if (!file.isFile) return emptyList()
        return try {
            val budget = READ_TAIL_MAX_BYTES
            val bytes = file.inputStream().use { input ->
                val size = file.length()
                if (size > budget) input.skip(size - budget)
                readUpTo(input, budget)
            }
            decodeTrailingLines(bytes, maxLines)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reads up to [maxBytes] bytes (fewer at EOF) — the bounded, compatible
     * equivalent of `InputStream#readNBytes(int)`, which is unavailable below
     * Android 13 (API 33) while `InputStream#read(byte[], int, int)` (API 1) is
     * not. A zero-length input yields an empty array.
     */
    private fun readUpTo(input: InputStream, maxBytes: Long): ByteArray {
        val buffer = ByteArray(maxBytes.toInt())
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    /**
     * Deletes [name] only when its active status can be reliably determined.
     * An exclusive advisory lock is attempted first: a file currently held by a
     * writer — in this or any other process, even one that has written nothing
     * for days — stays locked, so deletion is skipped (fail-closed). The lock
     * is held until after the unlink, shrinking the open-then-delete race to a
     * writer that started opening before we could lock.
     */
    override fun delete(name: String): Boolean = try {
        val file = File(rootDirectory, name)
        if (!file.isFile) false else RandomAccessFile(file, "rw").use { raf ->
            when (raf.channel.tryLock()) {
                null -> false // held elsewhere (or this JVM already locks it)
                else -> {     // acquired: nobody was writing at this instant
                    file.delete()
                }
            }
        }
    } catch (e: OverlappingFileLockException) {
        false // this JVM holds it already (active writer handle)
    } catch (e: Exception) {
        // Includes ENOLCK-style "locks unsupported" failures: activity cannot
        // be determined, so retention loses the file (budget may overshoot).
        false
    }

    companion object {
        /** Trailing-read budget ≥ one rotation part at default configuration. */
        const val READ_TAIL_MAX_BYTES = 4L * 1024 * 1024

        /**
         * Decodes the trailing [maxLines] lines of a UTF-8 byte buffer,
         * tolerating a truncated leading line (skipped without failing).
         */
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
