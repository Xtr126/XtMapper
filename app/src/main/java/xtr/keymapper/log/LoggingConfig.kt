package xtr.keymapper.log

typealias MinLevel = Level

data class LoggingConfig(
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    val maxFileBytes: Long = 4L * 1024 * 1024,
    val maxPartsPerDay: Int = 8,
    val maxAgeDays: Int = 14,
    val maxTotalBytes: Long = 8L * 1024 * 1024,
    val maxFiles: Int = 16,
    val flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
    val batchMaxRecords: Int = 256,
    val minLevelFile: MinLevel = Level.DEBUG,
    val minLevelLogcat: MinLevel = Level.DEBUG,
) {
    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1024
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 5_000L

        val DEFAULT_RELEASE: LoggingConfig = LoggingConfig(
            minLevelFile = Level.INFO,
            minLevelLogcat = Level.INFO,
        )
    }
}
