package xtr.keymapper.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * Session marker blocks that are appended to the persistent log files to mark
 * process-session boundaries.
 *
 * A *session* is one process execution that initialized XtLogger. Each daily file
 * may contain several sessions, separated by the markers below. Marker blocks are
 * surrounded by the full-width `=` separator line so they are easy to locate while
 * scrolling through a large file; ordinary events never use full-width separators.
 *
 * ## Grammar
 *
 * ```
 * ===...=== (80 x '=')
 * SESSION START
 * Session ID: <id>
 * Start Time: <iso ts>
 * <optional metadata lines>
 * Previous Session: <disposition>
 * ===...===
 * ```
 *
 * ```
 * ===...===
 * SESSION CONTINUED
 * Session ID: <id>
 * Continuation Time: <iso ts>
 * Original Start Time: <iso ts>
 * ===...===
 * ```
 *
 * ```
 * ===...===
 * SESSION END
 * Session ID: <id>
 * End Time: <iso ts>
 * Duration: <hh:mm:ss.SSS monotonic>
 * Status: <status text>
 * Dropped: <count>               (only when > 0)
 * ===...===
 * ```
 *
 * Recognized marker title lines (used for previous-session detection):
 * `SESSION START`, `SESSION CONTINUED`, `SESSION END`.
 *
 * ## Dispositions
 *
 * `Previous Session` is deliberately conservative: absence of a `SESSION END`
 * record only proves that the prior session did not close cleanly — the cause
 * (crash, process kill, force-stop, power loss, or a failed final write) cannot
 * be established from the log alone, so it is *not* labelled `Crashed`:
 *
 * - `First session` — no previous session of this process role exists.
 * - `Closed cleanly` — previous session wrote a `SESSION END`.
 * - `Did not close cleanly` — the last session marker was a START/CONTINUED with
 *   no END after it.
 * - `Unknown` — previous state could not be examined (storage failure).
 */
object SessionMarkers {

    const val START = "SESSION START"
    const val CONTINUED = "SESSION CONTINUED"
    const val END = "SESSION END"

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    val allMarkerTypes = listOf(START, CONTINUED, END)

    /**
     * First line of a session header.
     */
    fun startLine(id: String, started: Instant, zone: ZoneId, meta: SessionMetadata, previous: String): String {
        val sb = StringBuilder(256)
        sb.append(START).append('\n')
            .append("Session ID: ").append(id).append('\n')
            .append("Start Time: ").append(timestampFormatter.format(started.atZone(zone))).append('\n')
        meta.renderInto(sb)
        sb.append("Previous Session: ").append(previous)
        return sb.toString()
    }

    /** Continuation written to the next daily file when a session crosses midnight. */
    fun continuedLine(id: String, originalStart: Instant, continuationTime: Instant, zone: ZoneId): String {
        return "$CONTINUED\n" +
                "Session ID: $id\n" +
                "Continuation Time: ${timestampFormatter.format(continuationTime.atZone(zone))}\n" +
                "Original Start Time: ${timestampFormatter.format(originalStart.atZone(zone))}"
    }

    /** Session-ending record; written last, best effort, with a bounded flush. */
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

    /** Wraps a marker block body in the `=` separator lines. */
    fun block(body: String): String =
        "${RecordFormatter.SEPARATOR_80}\n$body\n${RecordFormatter.SEPARATOR_80}"

    /** Renders a monotonic duration as `hh:mm:ss.SSS`. */
    fun formatDurationNanos(nanos: Long): String {
        val total = kotlin.math.abs(nanos)
        val ms = total % 1_000_000_000L / 1_000_000L
        val s = (total / 1_000_000_000L) % 60
        val m = (total / 1_000_000_000L / 60) % 60
        val h = total / 1_000_000_000L / 3600
        return "%02d:%02d:%02d.%03d".format(Locale.ROOT, h, m, s, ms)
    }

    /**
     * Returns the marker type of a line, or null for an ordinary line.
     */
    fun markerTypeOf(line: String): String? =
        if (line.startsWith(START)) START
        else if (line.startsWith(CONTINUED)) CONTINUED
        else if (line.startsWith(END)) END
        else null
}

/**
 * Format-preserving container for the metadata included in a session header.
 * Values are rendered only if known; unknown values are omitted, not fabricated.
 */
class SessionMetadata(
    /** User-facing application version name, e.g. `2.5.0`. */
    val appVersion: String? = null,
    val buildType: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
    val deviceModel: String? = null,
    /** `waydroid` or `android`; omitted when the environment is unknown. */
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

/**
 * A session id: timestamp prefix plus a random suffix, so concurrent processes
 * can't collide and the id is stable across midnight continuation markers.
 */
object SessionIds {
    private val random = SecureRandom()

    /** `${yyyyMMdd-HHmmss}-${4 hex}` generated off the system clock. */
    fun generate(clock: java.time.Clock): String {
        val now = LocalDateTime.now(clock)
        val suffix = "%04X".format(Locale.ROOT, random.nextInt(0x10000))
        return "%04d%02d%02d-%02d%02d%02d-%s".format(
            Locale.ROOT, now.year, now.monthValue, now.dayOfMonth,
            now.hour, now.minute, now.second, suffix,
        )
    }
}
