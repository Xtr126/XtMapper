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
            SessionMarkers.continuedLine(sessionId, sessionStart!!, clock.instant(), zone),
        )
    }

    private fun commit(day: String, fallbackDay: String? = null, payloadsFor: (String) -> List<String>) {
        var seq = 0L
        val (name, verdict) = coordinator.append(
            LogFileNames.dailyName(day, null),
            fallbackDay?.let { LogFileNames.dailyName(it, null) },
        ) { selected, persisted ->
            val writtenDay = LogFileNames.parse(selected)!!.date
            seq = maxOf(seqByDay[writtenDay] ?: 0L, persisted.filter { it.sid == sessionId }.maxOfOrNull { it.seq } ?: 0L)
            payloadsFor(writtenDay).map { payload -> FrameCodec.encode(++seq, sessionId, payload) }
        }
        val writtenDay = LogFileNames.parse(name)!!.date
        // NOT_ATTEMPTED leaves the cursor and marker state unchanged.
        seqByDay[writtenDay] = seq
        for (bytes in verdict.frames) {
            val frame = FrameCodec.decode(bytes.decodeToString().removeSuffix("\n"))!!
            if (frame.sid != sessionId) continue
            currentDay = writtenDay
            currentFileName = name
            when (frame.payload.lineSequence().mapNotNull(SessionMarkers::markerTypeOf).firstOrNull()) {
                SessionMarkers.START -> startWritten = true
                SessionMarkers.CONTINUED -> continuedDays.add(writtenDay)
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

    fun previousDisposition(coordinator: CoordinatorStore, directory: File): String {
        val files = directory.listFiles()
            ?.mapNotNull { info -> LogFileNames.parse(info.name)?.let { it.date to it.baseName } }
            ?.sortedBy { it.first }
            ?: emptyList()
        var latestSession: String? = null
        val ended = HashMap<String, String?>()
        for ((_, name) in files) {
            for (frame in coordinator.readRecovery(name).frames) {
                val type = frame.payload.lineSequence().mapNotNull(SessionMarkers::markerTypeOf).firstOrNull() ?: continue
                if (type == SessionMarkers.START) latestSession = frame.sid
                if (type == SessionMarkers.END) {
                    ended[frame.sid] = frame.payload.lineSequence()
                        .firstOrNull { it.startsWith("Status: ") }
                        ?.removePrefix("Status: ")
                }
            }
        }
        val sid = latestSession ?: return if (files.isEmpty()) SessionState.DISPOSITION_FIRST else SessionState.DISPOSITION_UNKNOWN
        return if (sid !in ended) DISPOSITION_OPEN
        else if (ended[sid] == SessionStates.STATUS_CLEAN) SessionState.DISPOSITION_CLOSED
        else SessionState.DISPOSITION_UNCLEAN
    }
}
