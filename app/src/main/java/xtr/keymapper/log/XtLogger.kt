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

    // Bounded close can time out; until that owner actually retires, no replacement may start.
    @Volatile
    private var retiringOwner: LoggingPipeline? = null

    // Startup request parked while the retiring owner above finishes its close on its own.
    private class DeferredInit(val role: Role, val store: LogStore, val behind: LoggingPipeline)

    @Volatile
    private var deferredStart: DeferredInit? = null

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
        val active = pipeline ?: return directLog(level, tag, message, throwable)
        try {
            active.log(level, tag, message, throwable)
        } catch (e: Exception) {
            onPipelineFailure("logging call rejected: ${e.message}")
        }
    }

    private fun directLog(level: Level, tag: String, message: String, throwable: Throwable?) {
        runCatching {
            val early = LogRecord.now(
                systemClock.instant(), level, tag, message, throwable,
                pid = null, role = initializationRole?.tag ?: "preinit",
            )
            logcatSink(early)
        }
    }

    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        fatalEventRecorded = true
        val owner = pipeline
        if (owner == null) {
            directLog(Level.FATAL, tag, message, throwable)
            return
        }
        try {
            owner.log(Level.FATAL, tag, message, throwable)
        } catch (e: Exception) {
            onPipelineFailure("logging call rejected: ${e.message}")
        }
        try {
            owner.flush(DEFAULT_FATAL_FLUSH_MILLIS)
        } catch (e: Exception) {
            // flush never throws here by contract.
        }
    }

    fun flush(timeoutMillis: Long = LoggingConfig.DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean =
        pipeline?.flush(timeoutMillis) ?: true

    // Native JNI entry: Android log priority 2..6, source 0=Mouse/1=Keyboard; ACCEPTED means queue admission only.
    @JvmStatic
    fun logNative(priority: Int, source: Int, message: String?): Int {
        val level = when (priority) {
            NATIVE_PRIORITY_VERBOSE -> Level.VERBOSE
            NATIVE_PRIORITY_DEBUG -> Level.DEBUG
            NATIVE_PRIORITY_INFO -> Level.INFO
            NATIVE_PRIORITY_WARN -> Level.WARN
            NATIVE_PRIORITY_ERROR -> Level.ERROR
            else -> null
        }
        val tag = when (source) {
            NATIVE_SOURCE_MOUSE -> NATIVE_MOUSE_TAG
            NATIVE_SOURCE_KEYBOARD -> NATIVE_KEYBOARD_TAG
            else -> null
        }
        if (level == null || tag == null || message.isNullOrEmpty() ||
            message.length > NATIVE_MAX_MESSAGE_BYTES ||
            message.toByteArray(Charsets.UTF_8).size > NATIVE_MAX_MESSAGE_BYTES
        ) {
            return LogSubmission.INVALID.nativeCode
        }
        val active = pipeline ?: return LogSubmission.CLOSED.nativeCode
        return try {
            active.log(level, tag, message).nativeCode
        } catch (e: Exception) {
            onPipelineFailure("native diagnostic rejected: ${e.message}")
            LogSubmission.UNAVAILABLE.nativeCode
        }
    }

    fun endSession(status: String = SessionStates.STATUS_CLEAN) {
        synchronized(initLock) {
            deferredStart = null
            val current = pipeline ?: return
            pipeline = null
            initializationRole = null
            val finished = try {
                current.close(status) // bounded internally
            } catch (e: Exception) {
                Log.e(TAG, "endSession failure ignored", e)
                false
            }
            if (!finished) retiringOwner = current
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
                val finished = try {
                    current.close("Suspended after log-storage change")
                } catch (e: Exception) {
                    Log.w(TAG, "previous file owner close failed; replacement deferred", e)
                    false
                }
                if (!finished) retiringOwner = current
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
        val retiring = retiringOwner
        if (retiring != null) {
            // Publish before the isClosed check so the owner's afterClose hook cannot read null.
            deferredStart = DeferredInit(role, store, retiring)
            if (!retiring.isClosed) {
                Log.w(TAG, "previous file owner still closing; logger initialization deferred")
                return
            }
            deferredStart = null
            retiringOwner = null
        }
        val sessionStartInstant = systemClock.instant()
        val sessionStartNanos = System.nanoTime()
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
            durationNanos = { System.nanoTime() - sessionStartNanos },
            pid = try {
                Process.myPid()
            } catch (e: Throwable) {
                null
            },
            metadata = metadata,
            logcat = ::logcatSink,
            onFailure = ::onPipelineFailure,
            pruneRetention = role == Role.APP,
            afterClose = ::retryDeferredInit,
        )
        try {
            newPipeline.start()
            pipeline = newPipeline
            initializationRole = role
        } catch (e: Throwable) {
            Log.e(TAG, "XtLogger failed to start: ${e.message}", e)
        }
    }

    // Runs from the retiring owner's close; restart so file logging does not stay disabled until the next init call.
    private fun retryDeferredInit() {
        // A close() caller holds initLock while joining this worker; an idle hook entering the monitor stalls that join.
        if (deferredStart == null) return
        synchronized(initLock) {
            val deferred = deferredStart ?: return
            deferredStart = null
            if (pipeline != null || retiringOwner !== deferred.behind || !deferred.behind.isClosed) {
                deferredStart = deferred
                return
            }
            retiringOwner = null
            internalsStart(deferred.role, deferred.store)
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

    private const val NATIVE_PRIORITY_VERBOSE = 2 // Native FATAL has no mapping by contract
    private const val NATIVE_PRIORITY_DEBUG = 3
    private const val NATIVE_PRIORITY_INFO = 4
    private const val NATIVE_PRIORITY_WARN = 5
    private const val NATIVE_PRIORITY_ERROR = 6

    private const val NATIVE_SOURCE_MOUSE = 0
    private const val NATIVE_SOURCE_KEYBOARD = 1

    private const val NATIVE_MOUSE_TAG = "Mouse"
    private const val NATIVE_KEYBOARD_TAG = "Keyboard"

    private const val NATIVE_MAX_MESSAGE_BYTES = 512
}
