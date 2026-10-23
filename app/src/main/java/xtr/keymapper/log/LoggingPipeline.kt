package xtr.keymapper.log

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution mode of the owning process. The role owns BOTH the persistent
 * file name suffix (`xtmapper[-<tag>]-<date>.log`) and the day-file separation
 * between concurrent processes.
 */
enum class Role(val tag: String) {
    /** Main application process (activities, foreground service, IME, system-app server). */
    APP("app"),

    /** Standalone `app_process` server launched by `xtMapper.sh` (adb/waydroid uid 2000, or root). */
    SHELL("shell"),

    /** Shizuku user-service process (`RemoteService` bound through Shizuku, uid 2000). */
    SHIZUKU("shizuku"),

    /** libsu `:root` remote service process (uid 0). */
    ROOT("root");

    /** File-name tag: the app-role file has no suffix (canonical default). */
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

/**
 * Owns one process's logging pipeline: bounded queue, single writer thread,
 * persistent daily-file sink, Logcat sink, session lifecycle, retention.
 *
 * Responsibilities are split along [LoggingQueue] (bounded storage), [SessionLogWriter]
 * (day/rotation/session markers over a [LogStore]), [AsyncLogWorker] (single owning
 * thread) and this facade (levels, thresholds, session state, retention).
 *
 * Design guarantees (see the phase-1 requirement document):
 * - Logcat and file sinks are independent: Logcat happens synchronously at call
 *   time, before the queue; a dead file sink cannot affect Logcat output.
 * - Storage/queue failures are reported once per session through [onFailure]
 *   (an independent channel, production wires it to Logcat) and counters; they
 *   can never re-enter the file sink (no recursive logging loops).
 * - All public methods are safe to call from any thread.
 */
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
    /** Renders a record to Logcat immediately; injected so tests can capture. */
    private val logcat: (LogRecord) -> Unit = {},
    /** Independent diagnostics channel for sink/queue failures. */
    private val onFailure: (String) -> Unit = {},
    /** Retention is applied by this pipeline on start when true (app role only). */
    private val pruneRetention: Boolean = false,
) {
    val sessionId: String = sessionId

    private val queue = LoggingQueue(config.queueCapacity)
    private val writer: SessionLogWriter
    private val started = AtomicBoolean(false)

    /** Counted once per session; more drops are visible via [droppedCount]. */
    private val dropReported = AtomicBoolean(false)

    /** Records currently visible as dropped by the queue (test/inspection use). */
    val droppedCount: Int get() = queue.droppedCount.get()

    /** Current queue backlog (test/inspection use). */
    val pendingRecords: Int get() = queue.size

    /** True while the persistent file sink can still accept writes. */
    val fileSinkAvailable: Boolean get() = !writer.unavailable

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

    /**
     * Completes session start: previous-session detection, START marker, worker,
     * and (optionally) retention. Safe to call once; later calls are no-ops.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (pruneRetention) runRetention()
        val previous = try {
            SessionState.previousDisposition(writer.store, role.fileNameTag)
        } catch (e: Exception) {
            SessionState.DISPOSITION_UNKNOWN
        }
        writer.configure(previous, metadata, startTime)
        // The marker is written on the caller thread BEFORE the worker runs, so
        // the marker is never interleaved behind other records.
        writer.writeSessionStartMarker()
        worker.start()
        infoInternal(
            "XtLogger",
            "logging started role=${role.tag} session=$sessionId previous=$previous fileSink=${fileSinkAvailable()}",
        )
    }

    private fun fileSinkAvailable(): Boolean = writer.fileSinkHealthyForWrite

    // ------------------------------------------------------------- public API

    fun verbose(tag: String, message: String) = log(Level.VERBOSE, tag, message)
    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)
    fun fatal(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.FATAL, tag, message, throwable)

    /**
     * Central log entry point: builds one [LogRecord] at call time (clock and
     * thread stampings happen on the caller thread), renders to Logcat when the
     * Logcat threshold allows, then enqueues for the persistent sink.
     */
    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val record = LogRecord.now(
            clock.instant(), level, tag, message, throwable, pid, role.tag, sessionId,
        )
        if (level.enabled(config.minLevelLogcat)) {
            // Logcat first: independent of the file sink's health, cheap, and the
            // caller thread keeps its exception stack live for app-side tools.
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

    /**
     * Bounded flush of everything already offered; never blocks indefinitely.
     * Returns false when the bound passed (partial result is fine by contract).
     */
    fun flush(timeoutMillis: Long = config.flushTimeoutMillis): Boolean =
        worker.flush(timeoutMillis)

    /**
     * Writes the session END marker (clean closes only), stops the worker and
     * releases the file sink. Idempotent. [status] appears in the marker's
     * `Status:` line after the queue is drained (no batch/end race). The
     * marker's `Dropped:` count is the session's total loss: queue-overflow
     * drops plus records the sink could not persist (day-limit and failed
     * batches), so a reader never mistakes lost records for persisted ones.
     */
    fun close(status: String = SessionStates.STATUS_CLEAN) {
        if (!started.get()) return
        started.set(false)
        // 1) bounded flush + stop the consumer thread (no pending races left)
        worker.stopDraining(config.flushTimeoutMillis)
        // 2) then write the END marker directly on the caller thread
        writer.endSession(
            clock.instant(), durationNanos(), status,
            queue.droppedCount.get() + writer.recordsDroppedByDayLimit.get(),
        )
        // 3) release the file handle
        worker.closeFile()
    }

    /**
     * Internal lifecycle `info` line; never user content.
     */
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

    /** Starts retention pruning (app-role pipelines only; fail-soft). */
    private fun runRetention() {
        try {
            applyRetention(writer.store, config, clock.instant().atZone(zone).toLocalDate())
        } catch (e: Exception) {
            onFailure("XtLogger: retention failed (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})")
        }
    }

    /** Exposed for tests through the store argument. */
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
        // store.delete fails closed on still-locked files (active writers in
        // any process) and on files gone in the meantime; one bounded report
        // per scan keeps the budget overshoot visible without per-file spam.
        val skipped = deletions.count { !store.delete(it) }
        if (skipped > 0) {
            onFailure(
                "XtLogger: retention skipped $skipped candidate files " +
                    "(actively locked or already removed; kept list may overshoot its budget)"
            )
        }
    }

    /** Names this process currently holds open; protected from pruning. */
    private fun activeWriterNames(): Set<String> {
        val current = writer.currentlyOpenName()
        return if (current == null) emptySet() else setOf(current)
    }
}

/** Status strings used in `SESSION END` blocks. */
object SessionStates {
    const val STATUS_CLEAN = "Clean shutdown"
    const val STATUS_CRASH_RELAYED = "Crash relayed; process exiting"
    const val STATUS_SERVICE_STOP = "Service stopped"
}
