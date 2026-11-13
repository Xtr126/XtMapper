package xtr.keymapper.log

import java.io.Closeable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

// design-single-file.md §2 writer wiring: the session state machine surface (start/end
// markers, day routing, drop accounting, outcomes) both engines implement — the legacy
// lifetime-writer engine (SessionLogWriter) and the coordinator-protocol engine
// (CoordinatorSessionWriter) behind LoggingPipeline.
interface SessionWriter : Closeable {
    val sessionId: String
    val store: LogStore
    val unavailable: Boolean
    val recordsDroppedByDayLimit: AtomicInteger

    fun configure(previousDisposition: String, metadata: SessionMetadata, sessionStart: Instant)
    fun writeSessionStartMarker()
    fun write(records: List<LogRecord>): BatchWriteOutcome
    fun endSession(
        endInstant: Instant,
        durationNanos: Long,
        status: String,
        droppedRecords: Int,
        uncertainRecords: Int = 0,
    )
    fun currentlyOpenName(): String?
    fun previousDisposition(): String
}
