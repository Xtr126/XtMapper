package xtr.keymapper.log

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * Storage Access Framework implementation of [LogStore] for the ordinary
 * application process.
 *
 * Background (verified against the Android 11 storage-updates and SAF
 * documentation): scoped storage on Android 11+ denies direct `File` API writes
 * to a custom shared-storage root such as `/storage/emulated/0/XtMapper`, and
 * `MediaStore` cannot place files outside the standard collections. The
 * supported, least-privileged mechanism that keeps the canonical path intact is
 * a user-granted, persisted document tree (`ACTION_OPEN_DOCUMENT_TREE` +
 * `takePersistableUriPermission`) covering `XtMapper/Logs`. The required user
 * interaction is handled by `MainActivity` (a compose top-bar action), never by
 * logger initialization, which stays non-interactive.
 *
 * File manager visibility: documents created through a persisted
 * `com.android.externalstorage.documents` tree are real files inside
 * `/storage/emulated/0/XtMapper/Logs/` — discoverable by ordinary file managers,
 * surviving process exit and application restart, and remaining after app
 * uninstall (documented SAF behavior).
 */
class SafLogStore(context: Context, private val treeUri: Uri) : LogStore {

    private val resolver = context.applicationContext.contentResolver

    /** Document id/URI of the `Logs` directory inside the granted tree. */
    private val logsDirDocumentId: String
    private val logsDirUri: Uri

    /** Byte budget for trailing reads (matches [FileSystemLogStore] semantics). */
    private val readTailBudget = FileSystemLogStore.READ_TAIL_MAX_BYTES

    init {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val existingLogs = findChild(rootDocumentUri, LogDirectories.LOGS_FOLDER)
        logsDirDocumentId = existingLogs?.first ?: run {
            val created = DocumentsContract.createDocument(
                resolver, rootDocumentUri, DocumentsContract.Document.MIME_TYPE_DIR,
                LogDirectories.LOGS_FOLDER,
            ) ?: throw java.io.IOException(
                "could not create ${LogDirectories.LOGS_FOLDER} inside the granted tree"
            )
            DocumentsContract.getDocumentId(created)
        }
        logsDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, logsDirDocumentId)
    }

    private fun findChild(parentDocumentUri: Uri, displayName: String): Pair<String, Uri>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parentDocumentUri),
        )
        runCatching {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == displayName) {
                        val docId = cursor.getString(0)
                        return docId to DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        return null
    }

    private data class ChildEntry(val documentId: String, val displayName: String, val size: Long)

    private fun children(): List<ChildEntry> {
        val out = ArrayList<ChildEntry>()
        runCatching {
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, logsDirDocumentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(ChildEntry(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                }
            }
        }
        return out
    }

    override fun listFiles(): List<LogFileInfo> =
        children()
            .map { LogFileInfo(it.displayName, it.size) }
            .sortedBy { it.name }

    /**
     * Resolves (and creates when missing) the document for [name]. The listing is
     * consulted first because `createDocument` would otherwise duplicate the name.
     */
    private fun resolveOrCreate(name: String): Pair<Uri, Long> {
        val existing = children().firstOrNull { it.displayName == name }
        if (existing != null) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId)
            return uri to existing.size
        }
        val created = DocumentsContract.createDocument(resolver, logsDirUri, MIME_TEXT_PLAIN, name)
            ?: throw java.io.IOException("could not create log file $name")
        return created to 0L
    }

    override fun openAppendWriter(name: String): LogFileWriter {
        val (uri, size) = resolveOrCreate(name)
        // "wa" keeps existing content; append-only, never truncation.
        val stream = resolver.openOutputStream(uri, "wa")
            ?: throw java.io.IOException("could not open log file $name for append")
        return SafLogFileWriter(stream, size)
    }

    private inner class SafLogFileWriter(
        private val stream: java.io.OutputStream,
        private var sizeSoFar: Long,
    ) : LogFileWriter {
        override val writtenBytes: Long get() = sizeSoFar
        override fun write(chunk: ByteArray) {
            stream.write(chunk)
            stream.flush()
            sizeSoFar += chunk.size
        }
        override fun close() {
            stream.close()
        }
    }

    override fun readTail(name: String, maxLines: Int): List<String> {
        return try {
            val entry = children().firstOrNull { it.displayName == name }
                ?: return emptyList()
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
            resolver.openInputStream(uri)?.use { stream ->
                readTailBytes(stream, entry.size, maxLines)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readTailBytes(stream: InputStream, size: Long, maxLines: Int): List<String> {
        val bytes = if (size > readTailBudget) {
            stream.skip(size - readTailBudget)
            stream.readNBytes(readTailBudget.toInt())
        } else {
            stream.readNBytes(readTailBudget.toInt())
        }
        return FileSystemLogStore.decodeTrailingLines(bytes, maxLines)
    }

    override fun delete(name: String): Boolean = runCatching {
        val entry = children().firstOrNull { it.displayName == name } ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)

    companion object {
        private const val MIME_TEXT_PLAIN = "text/plain"
    }
}
