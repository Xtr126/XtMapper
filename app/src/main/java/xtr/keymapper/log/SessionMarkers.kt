package xtr.keymapper.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object SessionMarkers {

    const val START = "SESSION START"
    const val CONTINUED = "SESSION CONTINUED"
    const val END = "SESSION END"
    const val LATE = "SESSION LATE"

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    val allMarkerTypes = listOf(START, CONTINUED, END)

    fun startLine(id: String, started: Instant, zone: ZoneId, meta: SessionMetadata, previous: String): String {
        val sb = StringBuilder(256)
        sb.append(START).append('\n')
            .append("Session ID: ").append(id).append('\n')
            .append("Start Time: ").append(timestampFormatter.format(started.atZone(zone))).append('\n')
        meta.renderInto(sb)
        sb.append("Previous Session: ").append(previous)
        return sb.toString()
    }

    fun continuedLine(id: String, originalStart: Instant, continuationTime: Instant, zone: ZoneId, meta: SessionMetadata = SessionMetadata()): String {
        return buildString {
            append("$CONTINUED\nSession ID: $id\n")
            append("Continuation Time: ${timestampFormatter.format(continuationTime.atZone(zone))}\n")
            append("Original Start Time: ${timestampFormatter.format(originalStart.atZone(zone))}\n")
            meta.renderInto(this)
        }.trimEnd()
    }

    // Delayed-record routing proof: a record whose day file was pruned is rerouted into the
    // current day behind this marker, never silently dropped and never resurrecting a day.
    fun lateLine(id: String, recordTime: Instant, intendedDay: String, zone: ZoneId): String {
        return "$LATE\n" +
                "Session ID: $id\n" +
                "Record Time: ${timestampFormatter.format(recordTime.atZone(zone))}\n" +
                "Intended Day: $intendedDay"
    }

    fun endLine(
        id: String,
        end: Instant,
        zone: ZoneId,
        durationNanos: Long,
        status: String,
        droppedRecords: Int,
        uncertainRecords: Int = 0,
    ): String {
        val sb = StringBuilder(160)
            .append(END).append('\n')
            .append("Session ID: ").append(id).append('\n')
            .append("End Time: ").append(timestampFormatter.format(end.atZone(zone))).append('\n')
            .append("Duration: ").append(formatDurationNanos(durationNanos)).append('\n')
            .append("Status: ").append(status)
        if (droppedRecords > 0) sb.append("\nDropped: ").append(droppedRecords)
        if (uncertainRecords > 0) sb.append("\nUncertain: ").append(uncertainRecords)
        return sb.toString()
    }

    fun block(body: String): String =
        "${RecordFormatter.SEPARATOR_80}\n$body\n${RecordFormatter.SEPARATOR_80}"

    fun formatDurationNanos(nanos: Long): String {
        val total = kotlin.math.abs(nanos)
        val ms = total % 1_000_000_000L / 1_000_000L
        val s = (total / 1_000_000_000L) % 60
        val m = (total / 1_000_000_000L / 60) % 60
        val h = total / 1_000_000_000L / 3600
        return "%02d:%02d:%02d.%03d".format(Locale.ROOT, h, m, s, ms)
    }

    fun markerTypeOf(line: String): String? =
        if (line.startsWith(START)) START
        else if (line.startsWith(CONTINUED)) CONTINUED
        else if (line.startsWith(END)) END
        else null
}

data class SessionMetadata(
    val appVersion: String? = null,
    val buildType: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
    val deviceModel: String? = null,
    val environment: String? = null,
    val role: String? = null,
    val pid: Int? = null,
) {
    internal fun renderInto(sb: StringBuilder) {
        role?.let { sb.append("Role: ").append(it).append('\n') }
        pid?.let { sb.append("PID: ").append(it).append('\n') }
        appVersion?.let { sb.append("App Version: ").append(it).append('\n') }
        buildType?.let { sb.append("Build Type: ").append(it).append('\n') }
        if (androidVersion != null) {
            sb.append("Android Version: ").append(androidVersion)
            sdkInt?.let { sb.append(" (sdk ").append(it).append(')') }
            sb.append('\n')
        }
        deviceModel?.let { sb.append("Device: ").append(it).append('\n') }
        environment?.let { sb.append("Environment: ").append(it).append('\n') }
    }
}

object SessionIds {
    // Keep the clock parameter for callers; identity must not depend on clock or PID reuse.
    fun generate(clock: java.time.Clock): String = UUID.randomUUID().toString()
}
