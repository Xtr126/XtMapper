package xtr.keymapper.log

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Deterministic clock for tests. */
class MutableClock(
    initial: Instant,
    val zoneId: ZoneId = ZoneId.of("Europe/Berlin"),
) : Clock() {
    var current: Instant = initial

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    fun set(instant: Instant) {
        current = instant
    }

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }

    companion object {
        fun at(instant: String, zone: ZoneId = ZoneId.of("Europe/Berlin")) =
            MutableClock(Instant.parse(instant), zone)
    }
}

/** Collects diagnostics and builds test pipelines bound to a temp store. */
class TestPipelineFactory(
    private val store: LogStore,
    private val clock: MutableClock,
    private val config: LoggingConfig =
        LoggingConfig(queueCapacity = 64, maxFileBytes = 4L * 1024 * 1024),
    private val pruneRetention: Boolean = false,
) {
    val failureEvents: MutableList<String> = mutableListOf()
    val logcatRecords: MutableList<LogRecord> = mutableListOf()

    private var counter = 0

    fun start(role: Role = Role.APP, manualStart: Boolean = true): LoggingPipeline {
        val startInstant = clock.instant()
        counter += 1
        val pipeline = LoggingPipeline(
            role = role,
            sessionId = "test-session-%03d".format(java.util.Locale.ROOT, counter),
            store = store,
            config = config,
            clock = clock,
            zone = clock.zoneId,
            startTime = startInstant,
            durationNanos = { 600_000_000L },
            pid = 42,
            metadata = SessionMetadata(appVersion = "9.9.9", buildType = "Debug"),
            logcat = { logcatRecords.add(it) },
            onFailure = { failureEvents.add(it) },
            pruneRetention = pruneRetention,
        )
        if (manualStart) pipeline.start()
        return pipeline
    }
}

/** Little helpers for reading the produced text. */
object LogFiles {
    fun readAll(store: LogStore, name: String): String =
        store.readTail(name, Int.MAX_VALUE).joinToString("\n")
}
