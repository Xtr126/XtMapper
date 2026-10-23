package xtr.keymapper.log

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Name and size of one existing log file as seen by the store. */
data class LogFileInfo(
    val name: String,
    /** Size in bytes; -1 when unknown (never used for rotation thresholds). */
    val sizeBytes: Long,
)

/**
 * Storage abstraction behind the persistent log sink.
 *
 * Two implementations exist because XTMapper's execution modes do not share the
 * same rights on `/storage/emulated/0/XtMapper/Logs`:
 *
 * - [FileSystemLogStore] — direct `java.io.File` access. Used by the standalone
 *   `app_process` server processes (shell uid 2000 / root uid 0, see
 *   `RemoteServiceShell`, `RootRemoteService`) which have no Android `Context`
 *   or `ContentResolver`, and by unit tests. Root-created files are readable
 *   through normal file managers; uid-2000 and Waydroid behavior still require
 *   on-device verification (reported separately).
 * - [SafLogStore] — Storage Access Framework subtree grant. The only mechanism
 *   verified against Android's scoped-storage rules by which the ordinary
 *   application process (targetSdk 34, Android 11+ enforcement) can create and
 *   append files in a custom root directory such as `XtMapper/Logs` without
 *   asking for broad storage permissions; the grant is user-selected once and
 *   persisted via `takePersistableUriPermission`.
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
     * Throwing signals a storage failure; the persistent sink treats it as
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
     * throws) when unavailable. Callers only pass `LogFileNames.parse`-matched
     * names, so deletion cannot escape the canonical log directory.
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

/** Direct-filesystem implementation (shell/root roles and unit tests). */
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
        val stream: OutputStream = FileOutputStream(file, true)
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
                input.readNBytes(budget.toInt())
            }
            decodeTrailingLines(bytes, maxLines)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun delete(name: String): Boolean = try {
        val file = File(rootDirectory, name)
        file.isFile && file.delete()
    } catch (e: Exception) {
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
