package xtr.keymapper.log

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import xtr.keymapper.BuildConfig
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Public logging facade of the XTMapper logging subsystem.
 *
 * Usage:
 * ```kotlin
 * XtLogger.i("Server", "starting…")
 * XtLogger.e(TAG, "unexpected failure", e)
 * XtLogger.f(TAG, "uncaught on main thread", e)   // performs a bounded flush
 * XtLogger.endSession()                            // clean session close (exit paths)
 * ```
 *
 * ## Initialization map (one pipeline — one writer — per process)
 *
 * | Execution mode                                      | Entry point                       | Role   | Storage backend |
 * |-----------------------------------------------------|-----------------------------------|--------|-----------------|
 * | app process (activities, FGS, IME, system-app server)| `App.onCreate`                    | APP    | direct filesystem write; needs `MANAGE_EXTERNAL_STORAGE` (All files access), absent permission → Logcat-only |
 * | `xtMapper.sh` server (`app_process`, uid 2000/0)    | `RemoteServiceShell.main`         | SHELL  | direct filesystem write to the canonical directory |
 * | Shizuku user service process (uid 2000)             | `RemoteService.init` fallback     | SHIZUKU| direct filesystem write |
 * | libsu `:root` process (uid 0)                       | `RootRemoteService.init`          | ROOT   | direct filesystem write |
 *
 * Initialization is idempotent and thread-safe: later calls are no-ops; the
 * first role wins and conflicting later attempts are reported once. Storage
 * initialization happens on the caller's thread but off any latency-sensitive
 * input path, and repeated init doesn't rebuild writers.
 *
 * Failure policy: no logging call can throw; when the persistent store is
 * unavailable the pipeline reports it once (single logcat line) and keeps the
 * Logcat route working. There is no hidden private-storage fallback — the only
 * persistent output is the canonical user-visible directory.
 */
object XtLogger {

    /** Per-build log directory: `XtMapper-Debug/Logs` in debug, `XtMapper/Logs` otherwise. */
    private val CANONICAL_LOGS_DIR =
        File(LogDirectories.SHARED_STORAGE_ROOT, LogDirectories.appFolder(BuildConfig.DEBUG))
            .let { File(it, LogDirectories.LOGS_FOLDER) }

    private const val TAG = "XtLogger"

    private val systemClock: Clock = Clock.systemDefaultZone()

    private val initLock = Any()

    @Volatile
    private var pipeline: LoggingPipeline? = null

    @Volatile
    private var initializationRole: Role? = null

    @Volatile
    private var monotonicallyStartedNanos = 0L

    /** True once a pipeline owns this process's logging. */
    val isInitialized: Boolean get() = pipeline != null

    val currentRole: Role? get() = initializationRole

    /** Safe inspection hook: file sink health of the current pipeline. */
    val isFileSinkAvailable: Boolean
        get() = pipeline?.fileSinkAvailable == true

    @Volatile
    private var fatalEventRecorded = false

    /** Used by shutdown paths to avoid marking a fatal process exit as a closed session. */
    val hasFatalEvent: Boolean
        get() = fatalEventRecorded

    /// ------------------------------------------------------------- public API

    fun verbose(tag: String, message: String) = log(Level.VERBOSE, tag, message)
    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val active = pipeline
        if (active == null) {
            // Not initialized yet: early records log to Logcat only (this also
            // covers the startup window before App.onCreate ran in any process).
            runCatching {
                val early = LogRecord.now(
                    systemClock.instant(), level, tag, message, throwable,
                    pid = null, role = initializationRole?.tag ?: "preinit",
                )
                logcatSink(early)
            }
            return
        }
        try {
            active.log(level, tag, message, throwable)
        } catch (e: Exception) {
            onPipelineFailure("logging call rejected: ${e.message}")
        }
    }

    /**
     * Fatal record for the process's uncaught-exception path (or any terminal
     * event before a hard failure). Performs a bounded synchronous flush after
     * enqueueing so the persistent record should outlive a following exit(1),
     * while never hanging the dying process.
     */
    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        fatalEventRecorded = true
        log(Level.FATAL, tag, message, throwable)
        try {
            pipeline?.flush(DEFAULT_FATAL_FLUSH_MILLIS)
        } catch (e: Exception) {
            // flush never throws here by contract.
        }
    }

    /**
     * Bounded flush of pending work. Returns false on timing out.
     */
    fun flush(timeoutMillis: Long = LoggingConfig.DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean =
        pipeline?.flush(timeoutMillis) ?: true

    /** Realizes a clean session close; no further records are logged. */
    fun endSession(status: String = SessionStates.STATUS_CLEAN) {
        val active = pipeline ?: return
        synchronized(initLock) {
            val current = pipeline
            if (current === active) {
                pipeline = null
                try {
                    current.close(status) // bounded internally
                } catch (e: Exception) {
                    Log.e(TAG, "endSession failure ignored", e)
                }
            }
        }
    }

    /// ------------------------------------------------------- initialization

    /**
     * Application-process init (called from `App.onCreate`). Uses the canonical
     * log directory once All-files access is granted; otherwise file logging
     * stays disabled and only Logcat flows, reported once.
     */
    @JvmStatic
    fun init(context: Context) = initInternal(Role.APP, storeForAppProcess(context))

    /** Standalone `app_process` init (shell/root/shizuku roles). */
    @JvmStatic
    fun initStandalone(role: Role) = initInternal(role, directFileStore())

    /** Canonical log directory, shown next to the logs entry in the UI. */
    internal fun canonicalDirectory(): File = CANONICAL_LOGS_DIR

    /**
     * Turns file logging on after the All-files permission was granted (called
     * from `MainActivity.onResume` in the app process); no-op when the
     * permission is still missing or the sink already runs.
     */
    @JvmStatic
    fun enableFileLogging() {
        if (!hasAllFilesAccess()) return
        if (isFileSinkAvailable) return
        rebuildAppPipeline(FileSystemLogStore(CANONICAL_LOGS_DIR))
    }

    private fun rebuildAppPipeline(store: LogStore) {
        synchronized(initLock) {
            pipeline?.let { current ->
                pipeline = null
                runCatching { current.close("Suspended after log-storage change") }
            }
            internalsStart(Role.APP, store)
        }
    }

    // ------------------------------------------------------------ internals

    /**
     * All-files access exists only since Android 11 (the method needs API 30
     * and the appops grant is an API 30 concept). Below Android 11 the call
     * must not be made at all: a targetSdk-34 app cannot obtain this grant on
     * API 29 (scoped storage without MANAGE_EXTERNAL_STORAGE), and API ≤ 28
     * would need the legacy `WRITE_EXTERNAL_STORAGE` runtime permission this
     * manifest does not declare. On these runtimes persistent logging stays
     * Logcat-only — a documented platform limitation, never a startup crash.
     */
    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()

    private fun storeForAppProcess(context: Context): LogStore? {
        if (!hasAllFilesAccess()) return null
        // The log directory is fixed: per-build `XtMapper[-Debug]/Logs` on
        // shared storage, never a user-editable location.
        return FileSystemLogStore(CANONICAL_LOGS_DIR)
    }

    private fun directFileStore(): LogStore = FileSystemLogStore(CANONICAL_LOGS_DIR)

    /**
     * Codec-free failure store used when no public storage grant exists: the
     * first file write fails, the sink marks itself unavailable once, and
     * Logcat remains the functioning route. Nothing is written privately.
     */
    private object NoStore : LogStore {
        override fun listFiles(): List<LogFileInfo> = emptyList()
        override fun openAppendWriter(name: String): LogFileWriter =
            throw IOException("persistent file logging not enabled yet")
        override fun readTail(name: String, maxLines: Int): List<String> = emptyList()
        override fun delete(name: String): Boolean = false
    }

    private fun config(): LoggingConfig =
        if (BuildConfig.DEBUG) LoggingConfig() else LoggingConfig.DEFAULT_RELEASE

    private fun initInternal(role: Role, store: LogStore?) {
        synchronized(initLock) {
            if (pipeline != null) {
                reportRoleConflict(role)
                return
            }
            internalsStart(role, store ?: NoStore)
        }
    }

    private fun internalsStart(role: Role, store: LogStore) {
        val sessionStartInstant = systemClock.instant()
        monotonicallyStartedNanos = System.nanoTime()
        val metadata = SessionMetadata(
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidVersion = android.os.Build.VERSION.RELEASE,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            deviceModel = android.os.Build.MODEL,
            environment = xtr.keymapper.Environment.name,
        )
        val newPipeline = LoggingPipeline(
            role = role,
            sessionId = SessionIds.generate(systemClock),
            store = store,
            config = config(),
            clock = systemClock,
            zone = ZoneId.systemDefault(),
            startTime = sessionStartInstant,
            durationNanos = { System.nanoTime() - monotonicallyStartedNanos },
            pid = try {
                Process.myPid()
            } catch (e: Throwable) {
                null
            },
            metadata = metadata,
            logcat = ::logcatSink,
            onFailure = ::onPipelineFailure,
            pruneRetention = role == Role.APP,
        )
        try {
            newPipeline.start()
            pipeline = newPipeline
            initializationRole = role
        } catch (e: Throwable) {
            // Logging subsystem startup failed: never blocks the app. Logcat
            // remains available through direct calls below.
            Log.e(TAG, "XtLogger failed to start: ${e.message}", e)
        }
    }

    private var roleConflictReported = false

    private fun reportRoleConflict(requested: Role) {
        if (roleConflictReported) return
        roleConflictReported = true
        val active = initializationRole?.tag ?: "unknown"
        Log.w(TAG, "XtLogger already initialized with role $active; request for ${requested.tag} ignored")
    }

    /**
     * Logcat rendering of a record; synchronous, independent from file sinks.
     * FATAL records additionally carry a `-FATAL` suffix in their tag so the
     * terminal path is not confused with ordinary errors.
     */
    private fun logcatSink(record: LogRecord) {
        val priority = when (record.level) {
            Level.VERBOSE -> Log.VERBOSE
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR, Level.FATAL -> Log.ERROR
        }
        val tag = if (record.level == Level.FATAL) "XtMapper-${record.tag}-FATAL" else "XtMapper-${record.tag}"
        val message = buildString {
            append(record.message)
            record.throwable?.let {
                append("\n")
                append(Log.getStackTraceString(it))
            }
        }
        Log.println(priority, tag, message)
    }

    /** Independent failure channel (logcat only; never re-enters the sink). */
    private fun onPipelineFailure(event: String) {
        Log.w(TAG, event)
    }

    private const val DEFAULT_FATAL_FLUSH_MILLIS = 2_000L
}
