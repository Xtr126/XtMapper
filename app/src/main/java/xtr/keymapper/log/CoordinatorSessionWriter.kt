package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// design-single-file.md §2 writer wiring: the production session engine on the coordinator
// protocol. Exactly one daily file per date for ALL roles (correction A — no role tags, no
// part files); every batch is one coordinator transaction of frames; the shared DailyBudget
// verdict decides what lands, so budget exhaustion becomes counted record drops with the
// protocol notice instead of rotation, and the outcomes reach the pipeline's existing
// drop/uncertain accounting. A coordination timeout is a retryable NOT-ATTEMPTED outcome
// (the sink recovers on the next batch); only day-integrity refusals or frame contract
// violations latch the sink unavailable.
class CoordinatorSessionWriter(
    private val coordinator: CoordinatorStore,
    private val logDirectory: File,
    val role: String?,
    private val clock: Clock,
    private val zone: ZoneId,
    override val sessionId: String,
    private val onEvent: (String) -> Unit = {},
) : SessionWriter {

    private val drops = AtomicInteger(0)
    override val recordsDroppedByDayLimit: AtomicInteger get() = drops

    @Volatile override var unavailable = false
        private set

    // Retention and inspection ride the same coordinator gate: listing is directory-backed,
    // deletion is a coordinated transaction, and there is provably no lifetime writer.
    override val store: LogStore = object : LogStore {
        override fun listFiles(): List<LogFileInfo> {
            val files = logDirectory.listFiles() ?: return emptyList()
            return files.filter { it.isFile && it.name != FileSystemLogStore.LIFECYCLE_LOCK_NAME }
                .map { LogFileInfo(it.name, it.length()) }
                .sortedBy { it.name }
        }

        override fun openAppendWriter(name: String): LogFileWriter =
            throw IOException("the coordinator protocol opens no lifetime writer ($name)")

        override fun readTail(name: String, maxLines: Int): List<String> =
            coordinator.readRecovery(name).frames.takeLast(maxLines).flatMap { it.payload.split('\n') }

        override fun delete(name: String): Boolean = coordinator.delete(name)
    }

    override fun currentlyOpenName(): String? = currentFileName

    private var sessionStart: Instant? = null
    private var sessionStartDay: String? = null
    private var previousDispositionText = SessionState.DISPOSITION_FIRST
    private var headerMetadata = SessionMetadata()

    private var currentDay: String? = null
    @Volatile private var currentFileName: String? = null
    private val continuedDays = HashSet<String>()

    // Per-day sequence cursor of this session (§8): a restarted writer resumes from the
    // day's surviving frames, so a reattempted batch carries continuing sequence numbers.
    private val seqByDay = HashMap<String, Long>()

    private var sessionEnded = false
    private var dayLimitReported = false
    private var closed = false

    override fun configure(previousDisposition: String, metadata: SessionMetadata, sessionStart: Instant) {
        previousDispositionText = previousDisposition
        headerMetadata = metadata
        this.sessionStart = sessionStart
        sessionStartDay = dayOf(sessionStart)
    }

    override fun writeSessionStartMarker() {
        if (closed || unavailable || sessionEnded || sessionStartDay == null) return
        try {
            val day = sessionStartDay!!
            currentDay = day
            commit(
                day,
                listOf(
                    SessionMarkers.block(
                        SessionMarkers.startLine(sessionId, sessionStart!!, zone, headerMetadata, previousDispositionText),
                    ),
                ),
            )
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun write(records: List<LogRecord>): BatchWriteOutcome {
        if (closed || sessionEnded || unavailable) return BatchWriteOutcome.UNAVAILABLE
        // Group the batch by its per-record target day; each group is one transaction.
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (record in records) {
            val payload = RecordFormatter.format(record, zone)
            if (payload.encodeToByteArray().size > FrameCodec.MAX_PAYLOAD_BYTES) {
                // An oversized record cannot become a protocol frame; it is dropped and
                // reported instead of failing the batch or the sink.
                drops.incrementAndGet()
                if (!dayLimitReported) {
                    dayLimitReported = true
                    onEvent("XtLogger: record exceeds the frame payload limit; dropped for session $sessionId")
                }
                continue
            }
            val day = dayOf(record.time)
            val anchorDay = currentDay ?: sessionStartDay
            if (anchorDay != null && day < anchorDay && !dayFileExists(day)) {
                // Delayed records route by their own date while the day file exists; a
                // pruned/never-written older day never resurrects — the record lands in
                // the anchor day behind an explicit late marker (round-23 date policy).
                groups.getOrPut(anchorDay) { ArrayList() }.apply {
                    add(SessionMarkers.block(SessionMarkers.lateLine(sessionId, record.time, day, zone)))
                    add(payload)
                }
            } else {
                groups.getOrPut(day) { ArrayList() }.add(payload)
            }
        }
        if (groups.isEmpty()) return BatchWriteOutcome.PROCESSED
        return commitGroups(groups)
    }

    override fun endSession(
        endInstant: Instant,
        durationNanos: Long,
        status: String,
        droppedRecords: Int,
        uncertainRecords: Int,
    ) {
        if (closed || unavailable || sessionEnded) return
        try {
            val day = dayOf(endInstant)
            val payloads = ArrayList<String>(2)
            // Legacy parity: the END day may open with a continuation marker before the
            // open-session flag flips (session is still open for that decision).
            continuationFor(day)?.let { payload ->
                currentDay = day
                payloads.add(payload)
                continuedDays.add(day)
            }
            sessionEnded = true
            payloads.add(
                SessionMarkers.block(
                    SessionMarkers.endLine(sessionId, endInstant, zone, durationNanos, status, droppedRecords, uncertainRecords),
                ),
            )
            commit(day, payloads)
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun previousDisposition(): String =
        SessionFrames.previousDisposition(coordinator, logDirectory)

    override fun close() {
        closed = true
    }

    private fun commitGroups(groups: LinkedHashMap<String, MutableList<String>>): BatchWriteOutcome {
        try {
            for ((day, payloads) in groups) {
                if (currentDay != day) currentDay = day
                continuationFor(day)?.let { payload ->
                    payloads.add(0, payload)
                    continuedDays.add(day)
                }
                commit(day, payloads)
            }
        } catch (e: CoordinationTimeoutException) {
            // NOT-ATTEMPTED: no bytes were written for the failed group (earlier groups of
            // the batch keep their accounting); the sink stays up and the next batch retries.
            onEvent("XtLogger: log coordination timed out; batch not attempted (retryable) for session $sessionId")
            return BatchWriteOutcome.UNAVAILABLE
        } catch (e: Throwable) {
            fail(e)
            return BatchWriteOutcome.UNAVAILABLE
        }
        return BatchWriteOutcome.PROCESSED
    }

    private fun continuationFor(day: String): String? {
        if (sessionEnded || sessionStartDay == null || sessionStart == null) return null
        if (day == sessionStartDay || day in continuedDays) return null
        return SessionMarkers.block(
            SessionMarkers.continuedLine(sessionId, sessionStart!!, clock.instant(), zone),
        )
    }

    private fun commit(day: String, payloads: List<String>) {
        val name = LogFileNames.dailyName(day, null)
        currentFileName = name
        val verdict = coordinator.append(
            name,
            payloads.map { payload -> FrameCodec.encode(nextSeq(day, name), sessionId, payload) },
        )
        if (verdict.recordsDropped > 0) {
            drops.addAndGet(verdict.recordsDropped)
            if (!dayLimitReported) {
                dayLimitReported = true
                onEvent("XtLogger: daily byte budget exhausted for $day; further records drop until the next day")
            }
        }
        if (verdict.noticeWritten) {
            // One protocol notice per day: the reserve-exhausted diagnostic at the front of
            // the latching transaction (§2 step 5).
            dayLimitReported = true
            onEvent("XtLogger: reserve exhausted for $day; only session END and recovery frames remain")
        }
        if (verdict.endUnpersisted) {
            onEvent("XtLogger: session END for $day could not be persisted (budget exhausted); reported unpersisted")
        }
    }

    private fun nextSeq(day: String, name: String): Long {
        val last = seqByDay[day] ?: coordinator.readRecovery(name).frames
            .filter { it.sid == sessionId && !FrameCodec.isDayHeader(it) }
            .maxOfOrNull { it.seq } ?: 0L
        val next = last + 1
        seqByDay[day] = next
        return next
    }

    private fun dayFileExists(day: String): Boolean =
        store.listFiles().any {
            LogFileNames.parse(it.name)?.let { e -> e.date == day } ?: false
        }

    private fun fail(e: Throwable) {
        unavailable = true
        runCatching {
            onEvent(
                "XtLogger: file sink unavailable for session $sessionId " +
                    "(${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}); attempted batch is unconfirmed"
            )
        }
    }

    private fun dayOf(instant: Instant): String {
        val date = instant.atZone(zone).toLocalDate()
        return "%04d-%02d-%02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)
    }
}

// §4 correction F: dispositions are PER SESSION ID. Scanning newest-day-first, the last
// session marker seen in file + frame order identifies the latest known session of any
// role, and that session's own last marker decides the label. Days without session
// markers (legacy content, record-only days) contribute no session history.
object SessionFrames {
    const val DISPOSITION_OPEN = "No END observed"

    fun previousDisposition(coordinator: CoordinatorStore, directory: File): String {
        val files = directory.listFiles()
            ?.mapNotNull { info -> LogFileNames.parse(info.name)?.let { it.date to it.baseName } }
            ?.sortedByDescending { it.first }
            ?: emptyList()
        var sawFile = false
        for ((_, name) in files) {
            sawFile = true
            var latestKind: String? = null
            var latestStatus: String? = null
            for (frame in coordinator.readRecovery(name).frames) {
                val type = SessionMarkers.markerTypeOf(frame.payload.lineSequence().firstOrNull() ?: "") ?: continue
                latestKind = type
                if (type == SessionMarkers.END) {
                    latestStatus = frame.payload.lineSequence()
                        .firstOrNull { it.startsWith("Status: ") }
                        ?.removePrefix("Status: ")
                }
            }
            if (latestKind == null) continue // this day holds no session history
            return when (latestKind) {
                SessionMarkers.END ->
                    if (latestStatus == SessionStates.STATUS_CLEAN) SessionState.DISPOSITION_CLOSED
                    else SessionState.DISPOSITION_UNCLEAN
                else -> DISPOSITION_OPEN
            }
        }
        return if (sawFile) SessionState.DISPOSITION_UNKNOWN else SessionState.DISPOSITION_FIRST
    }
}
