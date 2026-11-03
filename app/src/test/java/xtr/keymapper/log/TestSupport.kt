package xtr.keymapper.log

import java.io.Closeable
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MutableClock(
    initial: Instant,
    val zoneId: ZoneId = ZoneId.of("Europe/Berlin"),
) : Clock() {
    @Volatile var current: Instant = initial

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

class TestPipelineFactory(
    private val store: LogStore,
    private val clock: MutableClock,
    private val config: LoggingConfig =
        LoggingConfig(queueCapacity = 64, maxFileBytes = 4L * 1024 * 1024),
    private val pruneRetention: Boolean = false,
) {
    val failureEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
    val logcatRecords = java.util.concurrent.CopyOnWriteArrayList<LogRecord>()

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

object LogFiles {
    fun readAll(store: LogStore, name: String): String =
        store.readTail(name, Int.MAX_VALUE).joinToString("\n")
}

class LogProbeChild(dir: File) : Closeable {
    private val classpath = listOf(LogStoreProcessProbe::class.java, FileSystemLogStore::class.java, Unit::class.java)
        .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
    private val process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp", classpath, LogStoreProcessProbe::class.java.name, dir.path,
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = process.inputStream.bufferedReader()
    private val output = process.outputStream.bufferedWriter()
    private val reader = Executors.newSingleThreadExecutor()

    init { check("READY" == response()) { "LogStoreProcessProbe did not reach READY" } }
    private fun response(): String = reader.submit<String> { input.readLine() ?: error("LogStoreProcessProbe exited") }
        .get(10, TimeUnit.SECONDS)
    fun command(line: String): String {
        output.write(line + "\n")
        output.flush()
        return response()
    }
    override fun close() {
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
        reader.shutdownNow()
        input.close()
        output.close()
    }
}
