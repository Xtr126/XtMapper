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

enum class LogSubmission(val nativeCode: Int) {
    ACCEPTED(0), CLOSED(1), UNAVAILABLE(2), INVALID(3), FILTERED(4);
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
    private val beforeAdmission: () -> Unit = {},
    private val afterClose: () -> Unit = {},
    private val coordinator: CoordinatorStore? = null,
) {
    val sessionId: String = sessionId

    private val queue = LoggingQueue(config.queueCapacity)
    private val writer: SessionWriter
    private val started = AtomicBoolean(false)

    private val dropReported = AtomicBoolean(false)

    val droppedCount: Int get() = queue.droppedCount.get()

    val pendingRecords: Int get() = queue.size
    val inFlightRecords: Int get() = queue.inFlightRecords
    val uncertainCount: Int get() = queue.uncertainCount.get()
    val isClosed: Boolean get() = worker.isClosed

    val fileSinkAvailable: Boolean get() = started.get() && !queue.isClosed && !writer.unavailable

    private val worker: AsyncLogWorker

    init {
        writer = if (coordinator != null) {
            val dir = requireNotNull(store.directory) {
                "coordinator logging requires a directory-backed LogStore"
            }
            CoordinatorSessionWriter(
                coordinator = coordinator,
                logDirectory = dir,
                role = role.fileNameTag,
                clock = clock,
                zone = zone,
                sessionId = sessionId,
                onEvent = onFailure,
            )
        } else {
            SessionLogWriter(
                store = store,
                role = role.fileNameTag,
                config = config,
                clock = clock,
                zone = zone,
                sessionId = sessionId,
                onEvent = onFailure,
            )
        }
        worker = AsyncLogWorker(
            queue, config, writer, roleTag = role.tag,
            onStart = ::startSession, onStop = ::finishClose, afterClose = afterClose,
        )
    }

    fun start() {
        if (queue.isClosed || !started.compareAndSet(false, true)) return
        worker.start()
    }

    private fun startSession() {
        if (pruneRetention) runRetention()
        val previous = try {
            writer.previousDisposition()
        } catch (e: Exception) {
            SessionState.DISPOSITION_UNKNOWN
        }
        writer.configure(previous, metadata.copy(role = role.tag, pid = pid), startTime)
        writer.writeSessionStartMarker()
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

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null): LogSubmission {
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
        if (!started.get()) return LogSubmission.CLOSED
        if (!level.enabled(config.minLevelFile)) return LogSubmission.FILTERED
        beforeAdmission()
        val accepted = queue.offer(record)
        reportFirstDropIfNeeded()
        return if (accepted) LogSubmission.ACCEPTED else LogSubmission.CLOSED
    }

    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        worker.flush(timeoutMillis)

    fun close(status: String = SessionStates.STATUS_CLEAN): Boolean =
        worker.close(config.flushTimeoutMillis, status).also { reportFirstDropIfNeeded() }

    private fun finishClose(status: String) {
        writer.endSession(
            clock.instant(), durationNanos(), status,
            queue.droppedCount.get() + writer.recordsDroppedByDayLimit.get(), queue.uncertainCount.get(),
        )
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
            runCatching {
                onFailure(
                    "XtLogger: records are being dropped (queue overflow, shutdown discard or unavailable sink); " +
                        "the total is reported in the session END marker when the sink remains available"
                )
            }
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
        activeNames: () -> Set<String> = { activeWriterNames() },
    ) {
        fun candidates(): List<String> {
            val entries = store.listFiles().mapNotNull {
                LogFileNames.parse(it.name)?.to(it.sizeBytes)
            }
            return Retention.candidatesForDeletion(
                entries.map { it.first },
                sizeBytesOf = { entry -> entries.firstOrNull { it.first == entry }?.second ?: 0L },
                config = config,
                today = today,
                activeNames = activeNames(),
            )
        }
        val bounded = candidates().take(8)
        val skipped = bounded.count { name -> !store.delete(name) { name in candidates() } }
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
