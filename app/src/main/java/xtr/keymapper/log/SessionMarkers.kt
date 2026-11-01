package xtr.keymapper.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.SecureRandom
import java.time.LocalDateTime

object SessionMarkers {

    const val START = "SESSION START"
    const val CONTINUED = "SESSION CONTINUED"
    const val END = "SESSION END"

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

    fun continuedLine(id: String, originalStart: Instant, continuationTime: Instant, zone: ZoneId): String {
        return "$CONTINUED\n" +
                "Session ID: $id\n" +
                "Continuation Time: ${timestampFormatter.format(continuationTime.atZone(zone))}\n" +
                "Original Start Time: ${timestampFormatter.format(originalStart.atZone(zone))}"
    }

    fun endLine(
        id: String,
        end: Instant,
        zone: ZoneId,
        durationNanos: Long,
        status: String,
        droppedRecords: Int,
    ): String {
        val sb = StringBuilder(160)
            .append(END).append('\n')
            .append("Session ID: ").append(id).append('\n')
            .append("End Time: ").append(timestampFormatter.format(end.atZone(zone))).append('\n')
            .append("Duration: ").append(formatDurationNanos(durationNanos)).append('\n')
            .append("Status: ").append(status)
        if (droppedRecords > 0) sb.append("\nDropped: ").append(droppedRecords)
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

class SessionMetadata(
    val appVersion: String? = null,
    val buildType: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
    val deviceModel: String? = null,
    val environment: String? = null,
) {
    internal fun renderInto(sb: StringBuilder) {
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
    private val random = SecureRandom()

    fun generate(clock: java.time.Clock): String {
        val now = LocalDateTime.now(clock)
        val suffix = "%04X".format(Locale.ROOT, random.nextInt(0x10000))
        return "%04d%02d%02d-%02d%02d%02d-%s".format(
            Locale.ROOT, now.year, now.monthValue, now.dayOfMonth,
            now.hour, now.minute, now.second, suffix,
        )
    }
}
