package xtr.keymapper.log

import java.time.Instant

/**
 * One immutable log event.
 *
 * All values are captured when the record is created, not when it is written, so
 * timestamps and thread metadata stay correct even when the bounded queue delays
 * the actual write. Construction is cheap: formatting and encoding are deferred to
 * the logging worker thread.
 *
 * [Level] is intentionally part of the JVM-testable core of XtLogger: this class
 * must not import Android framework classes.
 */
class LogRecord(
    /** Event time; day rollover and ISO-8601 rendering both derive from this. */
    val time: Instant,
    val level: Level,
    /** Component name; developer chosen, stable, never user data. */
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    /** PID of the logging process (null only in unit tests where it is irrelevant). */
    val pid: Int?,
    /** Process role name: `app`, `shell`, `shizuku`, or `root`. */
    val role: String,
    /** Name of the thread that created this record. */
    val threadName: String,
    /** Id of the thread that created this record. */
    val threadId: Long = 0,
    /** Session this record belongs to; set by the pipeline just before writing. */
    val sessionId: String? = null,
) {
    companion object {
        /**
         * Captures the current thread's name/id; PID is fixed at pipeline start.
         */
        fun now(
            instant: Instant,
            level: Level,
            tag: String,
            message: String,
            throwable: Throwable? = null,
            pid: Int? = null,
            role: String = "unknown",
            sessionId: String? = null,
        ): LogRecord {
            val thread = Thread.currentThread()
            return LogRecord(
                time = instant,
                level = level,
                tag = tag,
                message = message,
                throwable = throwable,
                pid = pid,
                role = role,
                threadName = thread.name,
                threadId = thread.id,
                sessionId = sessionId,
            )
        }
    }
}
