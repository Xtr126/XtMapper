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

object XtLogger {

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

    val isInitialized: Boolean get() = pipeline != null

    val currentRole: Role? get() = initializationRole

    val isFileSinkAvailable: Boolean
        get() = pipeline?.fileSinkAvailable == true

    @Volatile
    private var fatalEventRecorded = false

    val hasFatalEvent: Boolean
        get() = fatalEventRecorded

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

    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        fatalEventRecorded = true
        log(Level.FATAL, tag, message, throwable)
        try {
            pipeline?.flush(DEFAULT_FATAL_FLUSH_MILLIS)
        } catch (e: Exception) {
            // flush never throws here by contract.
        }
    }

    fun flush(timeoutMillis: Long = LoggingConfig.DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean =
        pipeline?.flush(timeoutMillis) ?: true

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

    @JvmStatic
    fun init(context: Context) = initInternal(Role.APP, storeForAppProcess(context))

    @JvmStatic
    fun initStandalone(role: Role) = initInternal(role, directFileStore())

    internal fun canonicalDirectory(): File = CANONICAL_LOGS_DIR

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

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()

    private fun storeForAppProcess(context: Context): LogStore? {
        if (!hasAllFilesAccess()) return null
        return FileSystemLogStore(CANONICAL_LOGS_DIR)
    }

    private fun directFileStore(): LogStore = FileSystemLogStore(CANONICAL_LOGS_DIR)

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

    private fun onPipelineFailure(event: String) {
        Log.w(TAG, event)
    }

    private const val DEFAULT_FATAL_FLUSH_MILLIS = 2_000L
}
