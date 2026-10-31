package xtr.keymapper.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders [LogRecord]s and session marker blocks into the persistent, human
 * readable UTF-8 format.
 *
 * ## Persistent record grammar
 *
 * Every event is one physical line (plus continuation lines for a stack trace):
 *
 * ```
 * <timestamp> [<LEVEL>] pid=<pid> role=<role> thread="<name>" tid=<tid> session=<id> tag="<tag>" message="<escaped>"
 * ```
 *
 * - `timestamp` is ISO-8601 with millisecond precision and an explicit UTC offset
 *   (`2026-10-22T09:15:32.510+01:00`) so times remain unambiguous across DST and
 *   local-time changes.
 * - quoted values (`thread=`, `tag=`, `message=`) are escaped: `\`, `"`, newline,
 *   carriage return and tab use backslash escapes (`\\`, `\"`, `\n`, `\r`, `\t`);
 *   other control characters are emitted as `\uXXXX`. A record therefore always
 *   stays a single unambiguous line, and no free-form field can introduce a line
 *   that imitates a structural session marker.
 *
 * A `Throwable` is rendered after its event line as additional indentation-prefixed
 * continuation lines. `Caused by:` and `Suppressed:` sections follow the standard
 * `printStackTrace` order; cyclic, redundant or deeper-than-bound chains are
 * truncated with an explicit note, and all frames share one bounded budget:
 *
 * ```
 *     exception=<class[: escaped message]>
 *     | <stack frame 0>
 *     | <stack frame n>
 *     | Suppressed: <class[: escaped message]>
 *     | <frames…>
 *     | Caused by: <class[: escaped message]>
 *     | <frames…>
 *     | …(+ more sections or frames omitted)
 * ```
 *
 * Session markers ([SessionMarkers]) use full-width `=` separator lines and are
 * never emitted inside ordinary event records.
 */
object RecordFormatter {

    /** Locale-independent ISO-8601 with millis and explicit offset. */
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    /** Longest rendered message; anything longer is truncated with a marker. */
    const val MAX_MESSAGE_CHARS = 8_000

    /** Longest stack trace rendered per record (shared across cause sections). */
    const val MAX_STACK_FRAMES = 120

    /** Deepest `Caused by:` / `Suppressed:` nesting rendered (1-based root). */
    const val MAX_CAUSE_DEPTH = 8

    /** Suppressed exceptions rendered per throwable; the rest is counted. */
    const val MAX_SUPPRESSED_PER_THROWABLE = 4

    /** Indentation used for exception continuation lines. */
    const val EXCEPTION_INDENT = "    "

    val SEPARATOR_80: String = "=".repeat(80)

    fun timestamp(instant: Instant, zone: ZoneId): String =
        timestampFormatter.format(instant.atZone(zone))

    /**
     * Renders one complete event record (lines joined with `\n`, no trailing
     * newline; the caller owns line termination and encoding).
     */
    fun format(record: LogRecord, zone: ZoneId): String {
        val sb = StringBuilder(256)
        sb.append(timestamp(record.time, zone))
            .append(" [").append(record.level.name).append(']')
            .append(" pid=").append(record.pid ?: 0)
            .append(" role=").append(record.role)
            .append(" thread=\"").append(escapeInline(record.threadName)).append('"')
            .append(" tid=").append(record.threadId)
        record.sessionId?.let { sb.append(" session=").append(it) }
        sb.append(" tag=\"").append(escapeInline(record.tag)).append('"')
        sb.append(" message=\"")
            .append(escapeInline(clip(record.message, MAX_MESSAGE_CHARS))).append('"')
        record.throwable?.let {
            sb.append('\n').append(EXCEPTION_INDENT).append("exception=").append(throwableBlock(it))
        }
        return sb.toString()
    }

    /**
     * `Throwable` content for one record: the exception class (+ escaped message)
     * on the first line, then stack frames each on its own `| `-prefixed line,
     * followed by `Suppressed:` and `Caused by:` sections in `printStackTrace`
     * order. All frames share one budget of [MAX_STACK_FRAMES]; chains are
     * bounded by [MAX_CAUSE_DEPTH] levels and [MAX_SUPPRESSED_PER_THROWABLE]
     * siblings per level, and self- or cross-referencing causes are truncated
     * with an explicit note instead of looping.
     */
    fun throwableBlock(throwable: Throwable): String {
        val framesBudget = intArrayOf(MAX_STACK_FRAMES)
        val seen: MutableSet<Throwable> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        return StringBuilder(256).apply {
            renderThrowable(throwable, this, framesBudget, seen, 0)
        }.toString()
    }

    /**
     * Renders one throwable (header + frames) into [out], then its suppressed
     * children and cause — each child level recurses with [depth] + 1. Text
     * always stays inline-safe: the header's message is escaped so an embedded
     * newline or marker-like string can never fabricate a structural line.
     */
    private fun renderThrowable(
        throwable: Throwable,
        out: StringBuilder,
        framesBudget: IntArray,
        seen: MutableSet<Throwable>,
        depth: Int,
    ) {
        if (!seen.add(throwable)) {
            // Self-cause (t.cause === t) or an already-rendered chain member;
            // the enclosing `Caused by:`/`Suppressed:` prefix plus this note
            // keeps the block syntactically complete without recursing forever.
            out.append(throwable.javaClass.name).append(" (already rendered — cyclic chain truncated)")
            return
        }
        out.append(throwable.javaClass.name)
        val message = throwable.message
        if (!message.isNullOrBlank()) {
            out.append(": ").append(escapeInline(clip(message, MAX_MESSAGE_CHARS)))
        }
        appendFrames(throwable.stackTrace, out, framesBudget)
        if (depth + 1 > MAX_CAUSE_DEPTH) {
            val more = throwable.suppressed.size + (if (throwable.cause != null) 1 else 0)
            if (more > 0) {
                out.append('\n').append(EXCEPTION_INDENT).append("| …(").append(more)
                    .append(" suppressed/cause sections beyond depth ").append(MAX_CAUSE_DEPTH)
                    .append(" omitted)")
            }
            return
        }
        var suppressedShown = 0
        for (suppressed in throwable.suppressed) {
            if (suppressedShown >= MAX_SUPPRESSED_PER_THROWABLE) {
                out.append('\n').append(EXCEPTION_INDENT).append("| …(+")
                    .append(throwable.suppressed.size - suppressedShown)
                    .append(" more suppressed omitted)")
                break
            }
            out.append('\n').append(EXCEPTION_INDENT).append("| Suppressed: ")
            renderThrowable(suppressed, out, framesBudget, seen, depth + 1)
            suppressedShown++
        }
        val cause = throwable.cause
        if (cause != null) {
            out.append('\n').append(EXCEPTION_INDENT).append("| Caused by: ")
            renderThrowable(cause, out, framesBudget, seen, depth + 1)
        }
    }

    private fun appendFrames(
        frames: Array<java.lang.StackTraceElement>,
        out: StringBuilder,
        budget: IntArray,
    ) {
        if (frames.isEmpty()) {
            out.append('\n').append(EXCEPTION_INDENT).append("| (no stack trace)")
            return
        }
        var printed = 0
        for (frame in frames) {
            if (budget[0] <= 0) break
            out.append('\n').append(EXCEPTION_INDENT).append("| ").append(frame.toString())
            budget[0]--
            printed++
        }
        if (printed < frames.size) {
            out.append('\n').append(EXCEPTION_INDENT).append("| …(+")
                .append(frames.size - printed).append(" more frames beyond the trace budget omitted)")
        }
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…(truncated ${text.length - max} chars)"

    /**
     * Escapes a value destined for a quoted `message="…"` or `thread="…"` field so
     * the record stays a single unambiguous line.
     */
    fun escapeInline(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) when {
            c == '\\' -> sb.append("\\\\")
            c == '"' -> sb.append("\\\"")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c.code == 0x7F -> sb.append("\\u%04X".format(Locale.ROOT, c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** Decodes escaped characters back to raw text (used by tests). */
    fun unescapeInline(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        var ok = i + 5 < text.length
                        var parsed = '?'
                        if (ok) try {
                            parsed = text.substring(i + 2, i + 6).toInt(16).toChar()
                        } catch (e: NumberFormatException) { ok = false }
                        if (ok) { sb.append(parsed); i += 6 }
                        else { sb.append(n); i += 2 }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i += 1 }
        }
        return sb.toString()
    }
}
