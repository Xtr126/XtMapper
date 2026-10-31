package xtr.keymapper.log

import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes formatted records into the correct daily file for one process role,
 * owning day rollover, part rotation and session marker bookkeeping.
 *
 * Invariants:
 * - Existing content is never truncated or overwritten: opens are append-mode and
 *   every new part uses the next free index taken from the store listing.
 * - A session keeps its id across midnight: the first write into a new day inside
 *   the same session emits a `SESSION CONTINUED` block into the next daily file.
 *   This includes the session's own end-write: [endSession] rolls the day over
 *   while the session is still open, so a session that crosses midnight without
 *   an intermediate event still gets its continuation marker before `SESSION END`.
 * - Rotation happens inside the canonical directory only; part files follow
 *   `xtmapper[-role-]day-partNNN.log` and keep `.log` as their final extension.
 * - After one storage failure the sink stops (`unavailable`) and reports once via
 *   [onEvent]; storage failures never escape upwards, never recursively.
 * - When a day has exhausted its part budget, further records for that day are
 *   dropped (counted in [recordsDroppedByDayLimit]), never unbounded growth.
 *   The bounded SESSION START/END markers may use the day's final part (no new
 *   part is opened and the rotation budget is not broken), so a full daily
 *   budget alone never makes the file sink appear unavailable.
 */
class SessionLogWriter(
    /** Backing store; exposed for session-state detection and retention. */
    val store: LogStore,
    /** Role tag inside file names; null for the app process role. */
    val role: String?,
    private val config: LoggingConfig,
    private val clock: Clock,
    private val zone: ZoneId,
    val sessionId: String,
    /** Sink-side diagnostics hook (independent channel, never re-enters here). */
    private val onEvent: (String) -> Unit = {},
) : Closeable {

    private var sessionStart: Instant? = null
    private var sessionStartDay: String? = null
    private var previousDisposition = SessionState.DISPOSITION_FIRST
    private var headerMetadata = SessionMetadata()

    private var currentDay: String? = null
    private var currentWriter: LogFileWriter? = null
    private var currentFileName: String? = null
    private var exhaustedDay: String? = null

    /** True after the first storage failure; all further file writes are skipped. */
    @Volatile
    var unavailable = false
        private set

    /** Name currently open for append this session (retention protection). */
    fun currentlyOpenName(): String? = currentFileName

    /** Records silently dropped because the day's part budget was exhausted. */
    val recordsDroppedByDayLimit = AtomicInteger(0)

    private var sessionEnded = false
    private var dayLimitReported = false

    /**
     * Supplies the session-start context; must be called before the first write.
     * [SessionState.previousDisposition] decides the header's `Previous Session`.
     */
    fun configure(previousDisposition: String, metadata: SessionMetadata, sessionStart: Instant) {
        this.previousDisposition = previousDisposition
        this.headerMetadata = metadata
        this.sessionStart = sessionStart
        this.sessionStartDay = dayOf(sessionStart)
    }

    /** Writes the `SESSION START` block into the session-start day's daily file. */
    fun writeSessionStartMarker() {
        if (unavailable || sessionEnded || sessionStartDay == null) return
        try {
            val day = sessionStartDay!!
            // START is a bounded session marker, so it may use the day's final part.
            writerFor(day, requireNotNull(sessionStart), allowFullPart = true)
            writeBlock(
                SessionMarkers.block(
                    SessionMarkers.startLine(sessionId, sessionStart!!, zone, headerMetadata, previousDisposition)
                )
            )
            // Do not keep the final part open after the budget is exhausted;
            // ordinary records for this day must continue to be dropped.
            if (exhaustedDay == day) closeCurrent()
        } catch (e: Exception) {
            sinkDown(e)
        }
    }

    /** One record per store write so a partial failure can only lose that record. */
    fun write(records: List<LogRecord>) {
        if (unavailable) return
        for (record in records) {
            try {
                writeOne(record)
            } catch (e: Exception) {
                sinkDown(e)
                return
            }
        }
    }

    /** Best-effort `SESSION END` block into the end-day file; idempotent. */
    fun endSession(endInstant: Instant, durationNanos: Long, status: String, droppedRecords: Int) {
        if (unavailable || sessionEnded) return
        try {
            // Day rollover first, while the session is still open: a session
            // that crosses midnight without a post-midnight event must still
            // emit its SESSION CONTINUED block into the new day's file before
            // the END marker. Setting sessionEnded before this call would
            // suppress the continuation marker (the writer chooses markers
            // based on sessionEnded).
            writerFor(dayOf(endInstant), endInstant, allowFullPart = true)
            sessionEnded = true
            writeBlock(
                SessionMarkers.block(
                    SessionMarkers.endLine(sessionId, endInstant, zone, durationNanos, status, droppedRecords)
                )
            )
        } catch (e: Exception) {
            sinkDown(e)
        } finally {
            closeCurrent()
        }
    }

    override fun close() {
        closeCurrent()
    }

    // ---------------------------------------------------------------- internals

    private fun writeOne(record: LogRecord) {
        // Size the record before selecting a part: a part may only be reused when
        // the record still fits, so no ordinary record can push it past the byte
        // budget that readTail relies on to find the session markers.
        val bytes = chunk(RecordFormatter.format(record, zone))
        writerFor(dayOf(record.time), record.time, requiredBytes = bytes.size)
        val writer = currentWriter ?: run {
            recordsDroppedByDayLimit.incrementAndGet()
            return
        }
        writer.write(bytes)
    }

    /**
     * Prepares the writer for [day]. On a day change inside the same session the
     * continuously same session id is marked with a `SESSION CONTINUED` block
     * before the file receives new events. A part is reused only when the next
     * write ([requiredBytes] for ordinary records, 0 for markers) still fits
     * inside its byte budget, so a part never grows past the size readTail
     * scans. [allowFullPart] is used only by the session-boundary paths: they
     * may reopen the day's final part for a bounded START or END marker when
     * the part budget is exhausted instead of losing it.
     */
    private fun writerFor(
        day: String,
        atInstant: Instant,
        allowFullPart: Boolean = false,
        requiredBytes: Int = 0,
    ) {
        val writer: LogFileWriter?
        val isDayChange = currentDay != day
        if (!isDayChange) {
            // A full daily part budget is terminal for ordinary records. Do not
            // reopen and re-stat the same final part for every dropped record.
            if (exhaustedDay == day && !allowFullPart) return
            val active = currentWriter
            if (active != null && active.writtenBytes + requiredBytes <= config.maxFileBytes) return
            closeCurrent()
            writer = activeWriterFor(day, allowFullPart, requiredBytes)
        } else {
            closeCurrent()
            currentDay = day
            exhaustedDay = null
            writer = activeWriterFor(day, allowFullPart, requiredBytes)
        }
        currentWriter = writer
        if (writer != null && isDayChange) {
            // Same session continuing after midnight: keep the id, note rollover.
            val startDay = sessionStartDay
            if (startDay != null && !sessionEnded && day != startDay && continuedDays.add(day)) {
                val body = SessionMarkers.block(
                    SessionMarkers.continuedLine(sessionId, requireNotNull(sessionStart), atInstant, zone)
                )
                // Marker write failure must not abort the rollover; the record
                // write below is the one guarded by writeOne.
                try { writer.write(chunk(body)) } catch (e: Exception) { sinkDown(e) }
            }
        }
    }

    /** Days that already received their `SESSION CONTINUED` marker this session. */
    private val continuedDays = HashSet<String>()

    /**
     * The active writer for the day: the highest existing part in which the next
     * write still fits below the rotation threshold (chronological: only this
     * one is considered), otherwise
     * the next part index from the store listing (never an index already in use,
     * never below 2), otherwise the base file when the day has no files yet.
     * Returns null only when the day's part budget is exhausted — except with
     * [allowFullPart], which then reopens the final part so a bounded session
     * START/END marker can still be appended without creating a part beyond the limit.
     */
    private fun activeWriterFor(
        day: String,
        allowFullPart: Boolean = false,
        requiredBytes: Int = 0,
    ): LogFileWriter? {
        val maxExistingPart = store.listFiles()
            .mapNotNull { LogFileNames.parse(it.name) }
            .filter { it.date == day && it.role == role }
            .maxOfOrNull { it.part }
        if (maxExistingPart == null) {
            val name = LogFileNames.dailyName(day, role)
            currentFileName = name
            return store.openAppendWriter(name)
        }
        // partName(part == 1) resolves to the base daily name.
        val highestName = LogFileNames.partName(day, role, maxExistingPart)
        val highest = store.openAppendWriter(highestName)
        if (highest.writtenBytes + requiredBytes <= config.maxFileBytes) {
            currentFileName = highestName
            return highest
        }
        highest.close()
        val nextPart = maxExistingPart + 1
        if (nextPart > config.maxPartsPerDay) {
            if (!dayLimitReported) {
                dayLimitReported = true
                onEvent(
                    "XtLogger: daily part limit reached for $day (${config.maxPartsPerDay} x " +
                        "${config.maxFileBytes / (1 shl 20)}MiB); further records dropped today"
                )
            }
            exhaustedDay = day
            if (!allowFullPart) return null
            // Session boundary only: append the bounded START/END marker to the
            // final part rather than losing it or flagging the sink down.
            currentFileName = highestName
            return store.openAppendWriter(highestName)
        }
        val nextName = LogFileNames.partName(day, role, nextPart)
        currentFileName = nextName
        return store.openAppendWriter(nextName)
    }

    private fun writeBlock(body: String) {
        val writer = currentWriter ?: throw IllegalStateException("no active writer")
        writer.write(chunk(body))
    }

    private fun chunk(text: String): ByteArray = (text + "\n").toByteArray(Charsets.UTF_8)

    private fun closeCurrent() {
        try { currentWriter?.close() } catch (e: Exception) { /* best effort */ }
        currentWriter = null
    }

    private fun sinkDown(e: Exception) {
        unavailable = true
        onEvent(
            "XtLogger: file sink unavailable for session $sessionId " +
                "(${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})"
        )
        closeCurrent()
    }

    private fun dayOf(instant: Instant): String {
        val date = instant.atZone(zone).toLocalDate()
        return "%04d-%02d-%02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)
    }
}

/**
 * Session boundary detection shared by all execution modes.
 *
 * The previous session's disposition is derived from the log files themselves:
 * scanning the role's files newest-first, the last recognized session marker
 * decides. No separate metadata state file is kept — the log's own markers are
 * the single source of truth, so no second persistent mechanism is needed.
 * Detection is role-scoped, so one process never mis-classifies another
 * process's still-running session as an unclean shutdown.
 */
object SessionState {

    const val DISPOSITION_FIRST = "First session"
    const val DISPOSITION_CLOSED = "Closed cleanly"
    const val DISPOSITION_UNCLEAN = "Did not close cleanly"
    const val DISPOSITION_UNKNOWN = "Unknown (log files could not be inspected)"

    /**
     * Max lines returned per [LogStore.readTail] call for marker detection. The
     * effective scan depth is bounded by the store's trailing-read byte budget,
     * which must cover a whole rotation part so the newest session's START marker
     * is never missed behind busy event lines.
     */
    const val TAIL_LINES = Int.MAX_VALUE

    /**
     * Scans [store] newest-file-first for [role]'s files and classifies the
     * previous session. The marker closest to the end of the newest file that
     * still contains a marker is the last marker chronologically (per-role files
     * make the chronological assumption safe); its type decides:
     * - `SESSION END` → previous session closed cleanly.
     * - `SESSION START`/`SESSION CONTINUED` with no later END → did not close
     *   cleanly (cause unknown by design: crash, kill, force-stop, power loss or
     *   I/O failure are not distinguishable here).
     * - no marker anywhere → no inspectable session (first session).
     */
    fun previousDisposition(store: LogStore, role: String?): String {
        val files = store.listFiles()
            .mapNotNull { LogFileNames.parse(it.name) }
            .filter { it.role == role }
            .sortedDescending()
        var sawFile = false
        for (file in files) {
            sawFile = true
            val tail = try {
                store.readTail(file.baseName, TAIL_LINES)
            } catch (e: Exception) {
                continue
            }
            val marker = tail.lastOrNull { SessionMarkers.markerTypeOf(it) != null } ?: continue
            return when (SessionMarkers.markerTypeOf(marker)) {
                SessionMarkers.END -> DISPOSITION_CLOSED
                else -> DISPOSITION_UNCLEAN
            }
        }
        return if (sawFile) DISPOSITION_UNKNOWN else DISPOSITION_FIRST
    }
}
