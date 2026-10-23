package xtr.keymapper.log

/** Levels used as sink minimum thresholds. */
typealias MinLevel = Level

/**
 * Fixed configuration for one logging pipeline. All limits are conservative
 * defaults sized for XTMapper's workload (lifecycle/exception events only; no
 * per-input-event logging), evaluated against the 8 MiB / 14 days budget stated
 * for the phase-tentative numbers.
 *
 * - [queueCapacity]: the bounded queue's fixed capacity; overflowing uses
 *   drop-oldest. 1024 records is ample headroom for bursty crash storms without
 *   any measurable latency cost (`offer` is a short O(1) lock).
 * - [maxFileBytes]: per-daily-part rotation threshold, implies granularity of
 *   retention. 4 MiB keeps every file comfortably below file manager preview sizes
 *   and matches the trailing-read budget used for session detection.
 * - [maxPartsPerDay]: hard per-day ceiling of parts per role: worst-case growth
 *   = maxPartsPerDay x maxFileBytes - 4 x 8 MiB/day/role.
 * - [maxAgeDays]/[maxTotalBytes]/[maxFiles]: retention limits, applied by the
 *   app-process sink (it is the only writer with verified deletion rights over
 *   the whole canonical tree).
 * - [flushTimeoutMillis]: upper bound of any [flush] wait; flushes never block
 *   indefinitely.
 */
data class LoggingConfig(
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    val maxFileBytes: Long = 4L * 1024 * 1024,
    val maxPartsPerDay: Int = 8,
    val maxAgeDays: Int = 14,
    /** Global retention budget across all roles inside the canonical directory. */
    val maxTotalBytes: Long = 8L * 1024 * 1024,
    /** Fewest files retained regardless of age; keeps at least one session. */
    val maxFiles: Int = 16,
    val flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
    /** Queue-side batch size limit; the worker never writes more per loop. */
    val batchMaxRecords: Int = 256,
    /** Sink minimum level for persistent files. */
    val minLevelFile: MinLevel = Level.DEBUG,
    /** Sink minimum level for Logcat output. */
    val minLevelLogcat: MinLevel = Level.DEBUG,
) {
    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1024
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 5_000L

        /** Release build default: only notable records reach the user's logs. */
        val DEFAULT_RELEASE: LoggingConfig = LoggingConfig(
            minLevelFile = Level.INFO,
            minLevelLogcat = Level.INFO,
        )
    }
}
