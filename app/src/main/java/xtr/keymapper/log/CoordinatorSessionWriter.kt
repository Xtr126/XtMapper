package xtr.keymapper.log

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class CoordinatorSessionWriter(
    private val coordinator: CoordinatorStore,
    private val logDirectory: File,
    val role: String?,
    private val clock: Clock,
    private val zone: ZoneId,
    override val sessionId: String,
    private val onEvent: (String) -> Unit = {},
) : SessionWriter {

    override val recordsDroppedByDayLimit = AtomicInteger(0)

    @Volatile override var unavailable = false
        private set

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

        override fun delete(name: String, eligible: () -> Boolean): Boolean = coordinator.delete(name, eligible)
    }

    override fun currentlyOpenName(): String? = currentFileName

    private var sessionStart: Instant? = null
    private var sessionStartDay: String? = null
    private var previousDispositionText = SessionState.DISPOSITION_FIRST
    private var headerMetadata = SessionMetadata()

    private var currentDay: String? = null
    @Volatile private var currentFileName: String? = null
    private val continuedDays = HashSet<String>()
    private val blockedDays = HashSet<String>()

    private val seqByDay = HashMap<String, Long>()
    private val reportedDiscrepancies = HashSet<String>()

    private var startWritten = false
    private var sessionEnded = false
    private val reportedDays = HashSet<String>()
    private var oversizedReported = false
    private var closed = false

    override fun configure(previousDisposition: String, metadata: SessionMetadata, sessionStart: Instant) {
        previousDispositionText = previousDisposition
        headerMetadata = metadata
        this.sessionStart = sessionStart
        sessionStartDay = dayOf(sessionStart)
    }

    override fun writeSessionStartMarker() {
        if (closed || unavailable || sessionEnded || startWritten) return
        val day = sessionStartDay ?: return
        if (day in blockedDays) return
        try {
            commit(day) { listOf(startPayload()) }
        } catch (e: CoordinationTimeoutException) {
            reportRetry(day)
        } catch (e: DayIntegrityBlockedException) {
            blockDay(day, e)
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun write(records: List<LogRecord>): BatchWriteOutcome {
        if (closed || sessionEnded || unavailable) return BatchWriteOutcome.UNAVAILABLE
        val groups = LinkedHashMap<String, MutableList<Pair<Instant, String>>>()
        for (record in records) {
            val payload = RecordFormatter.format(record, zone)
            if (payload.encodeToByteArray().size > FrameCodec.MAX_PAYLOAD_BYTES) {
                recordsDroppedByDayLimit.incrementAndGet()
                if (!oversizedReported) {
                    oversizedReported = true
                    report("XtLogger: record exceeds the frame payload limit; dropped for session $sessionId")
                }
                continue
            }
            val day = dayOf(record.time)
            groups.getOrPut(day) { ArrayList() }.add(record.time to payload)
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
        val day = dayOf(endInstant)
        if (day in blockedDays) {
            report("XtLogger: session END for $day is unpersisted (day integrity blocked)")
            return
        }
        try {
            val payloads = ArrayList<String>(2)
            continuationFor(day)?.let { payloads.add(it) }
            payloads.add(
                SessionMarkers.block(
                    SessionMarkers.endLine(sessionId, endInstant, zone, durationNanos, status, droppedRecords, uncertainRecords),
                ),
            )
            commit(day) { payloads }
            sessionEnded = true
        } catch (e: CoordinationTimeoutException) {
            reportRetry(day)
        } catch (e: DayIntegrityBlockedException) {
            blockDay(day, e)
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun previousDisposition(): String =
        SessionFrames.previousDisposition(coordinator, logDirectory)

    override fun close() {
        closed = true
    }

    private fun commitGroups(groups: LinkedHashMap<String, MutableList<Pair<Instant, String>>>): BatchWriteOutcome {
        var committed = false
        for ((day, records) in groups) {
            try {
                val anchorDay = currentDay ?: sessionStartDay
                val fallbackDay = anchorDay?.takeIf { day < it }
                commit(day, fallbackDay) { writtenDay ->
                    if (writtenDay in blockedDays) {
                        throw DayIntegrityBlockedException("day integrity already blocked", LogFileNames.dailyName(writtenDay, null))
                    }
                    val payloads = ArrayList<String>()
                    if (writtenDay == sessionStartDay && !startWritten) {
                        payloads.add(startPayload())
                    } else {
                        continuationFor(writtenDay)?.let { payloads.add(it) }
                    }
                    for ((time, payload) in records) {
                        if (writtenDay != day) {
                            payloads.add(SessionMarkers.block(SessionMarkers.lateLine(sessionId, time, day, zone)))
                        }
                        payloads.add(payload)
                    }
                    payloads
                }
                committed = true
            } catch (e: CoordinationTimeoutException) {
                reportRetry(day)
                // A later group's timeout cannot turn an earlier committed member into definite loss.
                return if (committed) BatchWriteOutcome.UNCERTAIN else BatchWriteOutcome.UNAVAILABLE
            } catch (e: DayIntegrityBlockedException) {
                blockDay(day, e)
                recordsDroppedByDayLimit.addAndGet(records.size)
            } catch (e: Throwable) {
                fail(e)
                return BatchWriteOutcome.UNCERTAIN
            }
        }
        return BatchWriteOutcome.PROCESSED
    }

    private fun startPayload(): String = SessionMarkers.block(
        SessionMarkers.startLine(sessionId, sessionStart!!, zone, headerMetadata, previousDispositionText),
    )

    private fun continuationFor(day: String): String? {
        if (sessionEnded || sessionStartDay == null || sessionStart == null) return null
        if (day == sessionStartDay || day in continuedDays) return null
        return SessionMarkers.block(
            SessionMarkers.continuedLine(sessionId, sessionStart!!, clock.instant(), zone, headerMetadata),
        )
    }

    private fun commit(day: String, fallbackDay: String? = null, payloadsFor: (String) -> List<String>) {
        var seq = 0L
        var discrepancy: String? = null
        val (name, verdict) = coordinator.append(
            LogFileNames.dailyName(day, null),
            fallbackDay?.let { LogFileNames.dailyName(it, null) },
        ) { selected, persisted ->
            val writtenDay = LogFileNames.parse(selected)!!.date
            discrepancy = SessionFrames.sequenceDiscrepancy(persisted, sessionId, seqByDay[writtenDay] ?: 0L)
            seq = maxOf(seqByDay[writtenDay] ?: 0L, persisted.filter { it.sid == sessionId }.maxOfOrNull { it.seq } ?: 0L)
            payloadsFor(writtenDay).map { payload -> FrameCodec.encode(++seq, sessionId, payload) }
        }
        val writtenDay = LogFileNames.parse(name)!!.date
        // NOT_ATTEMPTED leaves the cursor and marker state unchanged.
        for (bytes in verdict.frames) {
            val frame = FrameCodec.decode(bytes.decodeToString().removeSuffix("\n"))!!
            if (frame.sid != sessionId) continue
            seqByDay[writtenDay] = maxOf(seqByDay[writtenDay] ?: 0L, frame.seq)
            currentDay = writtenDay
            currentFileName = name
            when (frame.payload.lineSequence().mapNotNull(SessionMarkers::markerTypeOf).firstOrNull()) {
                SessionMarkers.START -> startWritten = true
                SessionMarkers.CONTINUED -> continuedDays.add(writtenDay)
            }
        }
        discrepancy?.let { detail ->
            if (reportedDiscrepancies.add("$writtenDay:$detail")) {
                report("XtLogger: sequence discrepancy for session=$sessionId day=$writtenDay $detail; committed history is incomplete")
            }
        }
        recordsDroppedByDayLimit.addAndGet(verdict.recordsDropped)
        if (verdict.emergency && reportedDays.add(writtenDay)) {
            report("XtLogger: daily byte budget exhausted for $writtenDay; ordinary records drop until the next day")
        }
        if (verdict.endUnpersisted) {
            report("XtLogger: session END for $writtenDay could not be persisted (budget exhausted); reported unpersisted")
        }
    }

    private fun fail(e: Throwable) {
        unavailable = true
        report(
            "XtLogger: file sink unavailable for session $sessionId " +
                "(${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}); attempted batch is unconfirmed",
        )
    }

    private fun blockDay(day: String, e: DayIntegrityBlockedException) {
        val blockedDay = e.fileName?.let { LogFileNames.parse(it)?.date } ?: day
        if (blockedDays.add(blockedDay)) {
            report("XtLogger: file writes blocked for $blockedDay (${e.javaClass.simpleName}: ${e.message}); other days remain available")
        }
    }

    private fun reportRetry(day: String) =
        report("XtLogger: log coordination timed out for $day; not attempted (retryable) for session $sessionId")

    private fun report(message: String) { runCatching { onEvent(message) } }

    private fun dayOf(instant: Instant): String {
        val date = instant.atZone(zone).toLocalDate()
        return "%04d-%02d-%02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)
    }
}

object SessionFrames {
    const val DISPOSITION_OPEN = "No END observed"
    const val DISPOSITION_UNKNOWN = "Unknown (history pruned or inspection incomplete)"

    fun previousDisposition(coordinator: CoordinatorStore, directory: File): String {
        val history = try { coordinator.readHistory(directory) } catch (_: IOException) { return DISPOSITION_UNKNOWN }
        if (history.isEmpty()) return SessionState.DISPOSITION_FIRST
        var latestSession: String? = null
        var latestStart: Instant? = null
        var incomplete = history.any { it.discardedBytes != 0L }
        val starts = HashSet<String>()
        val metadata = HashMap<String, Frame>()
        val ended = HashMap<String, String?>()
        for (day in history) {
            if (day.frames.groupBy { it.sid }.any { (sid, frames) -> sequenceDiscrepancy(frames, sid) != null }) incomplete = true
            for (frame in day.frames) {
                val type = frame.payload.lineSequence().mapNotNull(SessionMarkers::markerTypeOf).firstOrNull() ?: continue
                when (type) {
                    SessionMarkers.START, SessionMarkers.CONTINUED -> {
                        if (type == SessionMarkers.START) {
                            starts.add(frame.sid)
                            metadata[frame.sid] = frame
                        } else metadata.putIfAbsent(frame.sid, frame)
                        val time = field(frame, if (type == SessionMarkers.START) "Start Time" else "Original Start Time")
                        val start = time?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        if (start == null) incomplete = true
                        else if (latestStart == null || start >= latestStart) {
                            latestStart = start
                            latestSession = frame.sid
                        }
                    }
                    SessionMarkers.END -> ended[frame.sid] = field(frame, "Status")
                }
            }
        }
        val sid = latestSession ?: return DISPOSITION_UNKNOWN
        val disposition = when {
            incomplete || sid !in starts -> DISPOSITION_UNKNOWN
            sid !in ended -> DISPOSITION_OPEN
            ended[sid] == null -> DISPOSITION_UNKNOWN
            ended[sid] == SessionStates.STATUS_CLEAN -> SessionState.DISPOSITION_CLOSED
            else -> SessionState.DISPOSITION_UNCLEAN
        }
        val meta = metadata[sid]
        return "$disposition (session=$sid role=${meta?.let { field(it, "Role") } ?: "unknown"} pid=${meta?.let { field(it, "PID") } ?: "unknown"})"
    }

    internal fun sequenceDiscrepancy(frames: List<Frame>, sid: String, committed: Long = 0L): String? {
        if (sid == "protocol" || sid == "recovery" || sid == DailyBudget.NOTICE_SID) return null
        var expected = 1L
        for (frame in frames.filter { it.sid == sid }) {
            if (frame.seq != expected) return "expected=$expected observed=${frame.seq}"
            expected++
        }
        return if (committed >= expected) "expected=$committed observed=missing" else null
    }

    private fun field(frame: Frame, name: String): String? = frame.payload.lineSequence()
        .firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")
}
