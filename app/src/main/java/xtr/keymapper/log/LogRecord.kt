package xtr.keymapper.log

import java.time.Instant

class LogRecord(
    val time: Instant,
    val level: Level,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val pid: Int?,
    val role: String,
    val threadName: String,
    val threadId: Long = 0,
    val sessionId: String? = null,
) {
    companion object {
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
