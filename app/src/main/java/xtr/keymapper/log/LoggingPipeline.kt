package xtr.keymapper.log

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

enum class Role(val tag: String) {
    APP("app"),

    SHELL("shell"),

    SHIZUKU("shizuku"),

    ROOT("root");

    val fileNameTag: String? get() = when (this) {
        APP -> null
        SHELL -> "shell"
        SHIZUKU -> "shizuku"
        ROOT -> "root"
    }

    companion object {
        val byTag = entries.associateBy { it.tag }
    }
}

class LoggingPipeline(
    val role: Role,
    sessionId: String,
    store: LogStore,
    private val config: LoggingConfig,
    private val clock: Clock,
    private val zone: ZoneId,
    private val startTime: Instant,
    private val durationNanos: () -> Long,
    private val pid: Int? = null,
    private val metadata: SessionMetadata = SessionMetadata(),
    private val logcat: (LogRecord) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val pruneRetention: Boolean = false,
) {
    val sessionId: String = sessionId

    private val queue = LoggingQueue(config.queueCapacity)
    private val writer: SessionLogWriter
    private val started = AtomicBoolean(false)

    private val dropReported = AtomicBoolean(false)

    val droppedCount: Int get() = queue.droppedCount.get()

    val pendingRecords: Int get() = queue.size

    val fileSinkAvailable: Boolean get() = !writer.unavailable

    internal val sessionWriter: SessionLogWriter get() = writer

    private val worker: AsyncLogWorker

    init {
        writer = SessionLogWriter(
            store = store,
            role = role.fileNameTag,
            config = config,
            clock = clock,
            zone = zone,
            sessionId = sessionId,
            onEvent = onFailure,
        )
        worker = AsyncLogWorker(queue, config, writer, roleTag = role.tag)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (pruneRetention) runRetention()
        val previous = try {
            SessionState.previousDisposition(writer.store, role.fileNameTag)
        } catch (e: Exception) {
            SessionState.DISPOSITION_UNKNOWN
        }
        writer.configure(previous, metadata, startTime)
        writer.writeSessionStartMarker()
        worker.start()
        infoInternal(
            "XtLogger",
            "logging started role=${role.tag} session=$sessionId previous=$previous fileSink=$fileSinkAvailable",
        )
    }

    fun verbose(tag: String, message: String) = log(Level.VERBOSE, tag, message)
    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)
    fun fatal(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.FATAL, tag, message, throwable)

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val record = LogRecord.now(
            clock.instant(), level, tag, message, throwable, pid, role.tag, sessionId,
        )
        if (level.enabled(config.minLevelLogcat)) {
            try {
                logcat(record)
            } catch (e: Exception) {
                // Never let the diagnostic channel break the caller.
            }
        }
        if (started.get()) {
            if (level.enabled(config.minLevelFile)) queue.offer(record)
            reportFirstDropIfNeeded()
        }
    }

    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        worker.flush(timeoutMillis)

    fun close(status: String = SessionStates.STATUS_CLEAN) {
        if (!started.compareAndSet(true, false)) return
        if (worker.stopDraining(config.flushTimeoutMillis)) {
            finishClose(status)
            return
        }

        val abandoned = queue.drainUpTo(Int.MAX_VALUE)?.size ?: 0
        queue.markFailed(abandoned)
        reportFirstDropIfNeeded()

        val workerThread = worker.threadReference()
        Thread({
            var interrupted = false
            while (workerThread.isAlive) {
                try {
                    workerThread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            try {
                finishClose(status)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }, "xtlogger-session-close-" + role.tag).apply {
            isDaemon = true
            start()
        }
    }

    private fun finishClose(status: String) {
        try {
            writer.endSession(
                clock.instant(), durationNanos(), status,
                queue.droppedCount.get() + writer.recordsDroppedByDayLimit.get(),
            )
        } finally {
            worker.closeFile()
        }
    }

    private fun infoInternal(tag: String, message: String) {
        val record = LogRecord(
            time = clock.instant(),
            level = Level.INFO,
            tag = tag,
            message = message,
            throwable = null,
            pid = pid,
            role = role.tag,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            sessionId = sessionId,
        )
        if (Level.INFO.enabled(config.minLevelLogcat)) try { logcat(record) } catch (e: Exception) {}
        queue.offer(record)
        reportFirstDropIfNeeded()
    }

    private fun reportFirstDropIfNeeded() {
        if (queue.droppedCount.get() > 0 && dropReported.compareAndSet(false, true)) {
            onFailure(
                "XtLogger: records are being dropped (queue overflow or file-sink failure); " +
                    "the total is reported in the session END marker"
            )
        }
    }

    private fun runRetention() {
        try {
            applyRetention(writer.store, config, clock.instant().atZone(zone).toLocalDate())
        } catch (e: Exception) {
            onFailure("XtLogger: retention failed (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})")
        }
    }

    fun applyRetention(
        store: LogStore,
        config: LoggingConfig,
        today: java.time.LocalDate,
        activeNames: Set<String> = activeWriterNames(),
    ) {
        val infos = store.listFiles()
        val entries = infos.mapNotNull {
            LogFileNames.parse(it.name)?.to(it.sizeBytes)
        }
        if (entries.isEmpty()) return
        val deletions = Retention.candidatesForDeletion(
            entries.map { it.first },
            sizeBytesOf = { entry -> entries.firstOrNull { it.first == entry }?.second ?: 0L },
            config = config,
            today = today,
            activeNames = activeNames,
        )
        val skipped = deletions.count { !store.delete(it) }
        if (skipped > 0) {
            onFailure(
                "XtLogger: retention skipped $skipped candidate files " +
                    "(active, unavailable or already removed; kept list may overshoot its budget)"
            )
        }
    }

    private fun activeWriterNames(): Set<String> {
        val current = writer.currentlyOpenName()
        return if (current == null) emptySet() else setOf(current)
    }
}

object SessionStates {
    const val STATUS_CLEAN = "Clean shutdown"
    const val STATUS_CRASH_RELAYED = "Crash relayed; process exiting"
    const val STATUS_UNEXPECTED_EXIT = "Unexpected process exit"
    const val STATUS_STARTUP_FAILED = "Startup failed"
    const val STATUS_SERVICE_STOP = "Service stopped"
}
