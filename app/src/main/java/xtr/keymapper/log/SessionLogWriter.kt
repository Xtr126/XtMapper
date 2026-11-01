package xtr.keymapper.log

import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SessionLogWriter(
    val store: LogStore,
    val role: String?,
    private val config: LoggingConfig,
    private val clock: Clock,
    private val zone: ZoneId,
    val sessionId: String,
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

    @Volatile
    var unavailable = false
        private set

    fun currentlyOpenName(): String? = currentFileName

    val recordsDroppedByDayLimit = AtomicInteger(0)

    private var sessionEnded = false
    private var dayLimitReported = false

    fun configure(previousDisposition: String, metadata: SessionMetadata, sessionStart: Instant) {
        this.previousDisposition = previousDisposition
        this.headerMetadata = metadata
        this.sessionStart = sessionStart
        this.sessionStartDay = dayOf(sessionStart)
    }

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
            if (exhaustedDay == day) closeCurrent()
        } catch (e: Exception) {
            sinkDown(e)
        }
    }

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

    fun endSession(endInstant: Instant, durationNanos: Long, status: String, droppedRecords: Int) {
        if (unavailable || sessionEnded) return
        try {
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

    private fun writeOne(record: LogRecord) {
        val bytes = chunk(RecordFormatter.format(record, zone))
        writerFor(dayOf(record.time), record.time, requiredBytes = bytes.size)
        val writer = currentWriter ?: run {
            recordsDroppedByDayLimit.incrementAndGet()
            return
        }
        writer.write(bytes)
    }

    private fun writerFor(
        day: String,
        atInstant: Instant,
        allowFullPart: Boolean = false,
        requiredBytes: Int = 0,
    ) {
        val isDayChange = currentDay != day
        val continuedBody: String? =
            if (isDayChange && sessionStartDay != null && !sessionEnded &&
                day != sessionStartDay && day !in continuedDays
            ) {
                SessionMarkers.block(
                    SessionMarkers.continuedLine(sessionId, requireNotNull(sessionStart), atInstant, zone)
                )
            } else {
                null
            }
        val continuationBytes = continuedBody?.let { chunk(it).size } ?: 0
        val writer: LogFileWriter?
        if (!isDayChange) {
            if (exhaustedDay == day && !allowFullPart) return
            val active = currentWriter
            if (active != null && active.writtenBytes + requiredBytes <= config.maxFileBytes) return
            closeCurrent()
            writer = activeWriterFor(day, allowFullPart, requiredBytes)
        } else {
            closeCurrent()
            currentDay = day
            exhaustedDay = null
            writer = activeWriterFor(day, allowFullPart, requiredBytes + continuationBytes)
        }
        currentWriter = writer
        if (writer != null && continuedBody != null) {
            continuedDays.add(day)
            try { writer.write(chunk(continuedBody)) } catch (e: Exception) { sinkDown(e) }
        }
    }

    private val continuedDays = HashSet<String>()

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

object SessionState {

    const val DISPOSITION_FIRST = "First session"
    const val DISPOSITION_CLOSED = "Closed cleanly"
    const val DISPOSITION_UNCLEAN = "Did not close cleanly"
    const val DISPOSITION_UNKNOWN = "Unknown (log files could not be inspected)"

    const val TAIL_LINES = Int.MAX_VALUE

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
