package xtr.keymapper.log

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.floor

fun interface TelemetryCancellation {
    fun cancel()
}

fun interface TelemetryScheduler {
    fun schedule(initialMillis: Long, periodMillis: Long, task: Runnable): TelemetryCancellation
}

fun interface BatterySource {
    fun read(): BatteryReading?
}

fun interface ThermalSource {
    fun currentThermalStatus(): Int?
}

interface ProcSource {
    fun read(path: String): String?
    fun list(path: String): List<String>
}

data class BatteryReading(
    val levelPercent: Int?,
    val status: Int?,
    val charging: Boolean,
    val source: String?,
    val temperatureDeciC: Int?,
)

class DeviceTelemetry internal constructor(
    private val nanoTime: () -> Long,
    private val scheduler: TelemetryScheduler,
    private val batterySource: BatterySource,
    private val proc: ProcSource,
    private val thermalSource: ThermalSource,
    private val sdkInt: Int,
    private val emit: (String) -> Unit = { XtLogger.info(TAG, it) },
    // Milliseconds of whole-process (multicore, safe) CPU time; null means unavailable.
    private val selfCpuMillis: () -> Long? = { runCatching { Process.getElapsedCpuTime() }.getOrNull() },
    private val sessionBudget: Int = SESSION_BUDGET,
    private val captureMillis: Long = CAPTURE_MILLIS,
) {
    // Saturating: Long.MAX_VALUE * 1_000_000 would wrap negative and expire instantly.
    private val captureNanos =
        if (captureMillis >= Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else captureMillis * 1_000_000L
    private val lock = Any()
    private var running = false
    private var generation = 0L
    private var sessionRecords = 0
    private var dropped = 0
    private var budgetNoticeEmitted = false
    private var captureEnded = false
    private var captureReason: String? = null
    private val lastCanonical = mutableMapOf<String, String>()
    private var taskCancel: TelemetryCancellation? = null
    private var statTotal = -1L
    private var statIdle = -1L
    private var selfCpu = -1L
    private var systemSampleNanos = 0L
    private var selfSampleNanos = 0L
    private var captureStartNanos = 0L
    private var lastPct = -1
    private var lastChangePct = -1
    private var lastChangeNanos = 0L
    private var lastTrendState: Pair<Boolean, String?>? = null
    private var zones: List<Pair<String, String>> = emptyList()

    fun start(role: Role): Unit = synchronized(lock) {
        if (role == Role.APP || running) return
        running = true
        val session = ++generation
        sessionRecords = 0
        dropped = 0
        budgetNoticeEmitted = false
        captureEnded = false
        captureReason = null
        captureStartNanos = nanoTime()
        lastCanonical.clear()
        statTotal = -1
        statIdle = -1
        selfCpu = -1
        lastPct = -1
        lastChangePct = -1
        lastTrendState = null
        zones = discoverZones()
        budgeted("telemetry start role=${role.name} period_ms=$PERIOD_MILLIS budget=$sessionBudget")
        taskCancel = scheduler.schedule(0, PERIOD_MILLIS, Runnable { sample(session) })
    }

    fun stop(): Unit = synchronized(lock) {
        if (!running) return
        running = false
        try {
            taskCancel?.cancel()
        } finally {
            taskCancel = null
            // Budget-exempt so the final drop count and capture reason always surface.
            emit(
                "telemetry stop records=$sessionRecords dropped=$dropped" +
                    (captureReason?.let { " early=$it" } ?: ""),
            )
        }
    }

    private fun sample(session: Long): Unit = synchronized(lock) {
        // Both stop policies gate before any source read: expired capture and exhausted
        // budget leave this session with zero further collection cost.
        if (!running || session != generation || captureEnded) return
        if (nanoTime() - captureStartNanos >= captureNanos) {
            captureEnded = true
            captureReason = "capture_expired"
            return
        }
        batterySample()
        cpuSample()
        memSample()
        thermalSample()
        zoneSample()
    }

    private fun batterySample() {
        val reading = runCatching { batterySource.read() }.getOrNull()
        publish("battery", reading?.let(::encodeBattery) ?: "battery unavailable")
        updateTrend(reading)
    }

    private fun encodeBattery(reading: BatteryReading): String {
        val level = reading.levelPercent
        if (level == null || level !in 0..100) return "battery unavailable"
        val state = when (reading.status) {
            2 -> "CHARGING"
            3 -> "DISCHARGING"
            4 -> "NOT_CHARGING"
            5 -> "FULL"
            else -> if (reading.charging) "CHARGING" else "UNKNOWN"
        }
        val source = reading.source ?: "unavailable"
        val temp = reading.temperatureDeciC?.let { " battery_temp_c=${oneDecimal(it / 10.0)}" } ?: ""
        return "battery state=$state level=$level source=$source$temp"
    }

    private fun updateTrend(reading: BatteryReading?) {
        val level = reading?.levelPercent
        if (level == null || level !in 0..100) {
            lastPct = -1
            lastChangePct = -1
            lastTrendState = null
            return
        }
        val mode = reading.charging to reading.source
        if (mode != lastTrendState) {
            // Charging-state and power-source changes invalidate the previous rate window.
            lastTrendState = mode
            lastPct = level
            lastChangePct = -1
            lastChangeNanos = 0L
            return
        }
        val now = nanoTime()
        if (lastPct < 0) {
            lastPct = level
            return
        }
        if (level == lastPct) return
        if (lastChangePct < 0) {
            lastChangePct = level
            lastChangeNanos = now
            lastPct = level
            return
        }
        val deltaPct = level - lastChangePct
        val deltaNanos = now - lastChangeNanos
        if (deltaNanos > 0) {
            val rate = deltaPct * 3_600_000_000_000.0 / deltaNanos
            val direction = if (deltaPct < 0) "DISCHARGING" else "CHARGING"
            budgeted(
                "battery_trend direction=$direction rate_pct_per_hour=${oneDecimal(rate)} " +
                    "window_s=${deltaNanos / 1_000_000_000L}",
            )
        }
        lastChangePct = level
        lastChangeNanos = now
        lastPct = level
    }

    private fun cpuSample() {
        val now = nanoTime()
        // System and process CPU are measured independently: a restricted, malformed or
        // regressed system counter must not withhold the process-side measurement.
        val systemPart = systemCpuPart(now)
        val selfPart = selfCpuPart(now)
        if (systemPart == null && selfPart == null) return
        publish(
            "cpu",
            "cpu system_pct=${systemPart ?: "unavailable"} process_pct=${selfPart ?: "unavailable"}",
        )
    }

    // null: no new system measurement this tick (baseline pending or unusable interval).
    private fun systemCpuPart(now: Long): String? {
        val (total, idle) = systemCpuTotals() ?: run {
            statTotal = -1
            statIdle = -1
            return "unavailable"
        }
        if (statTotal < 0) {
            statTotal = total
            statIdle = idle
            systemSampleNanos = now
            return null
        }
        val elapsedNanos = now - systemSampleNanos
        if (elapsedNanos <= 0) return null
        val diffTotal = total - statTotal
        val diffIdle = idle - statIdle
        // A regressed or impossible counter stays unpublishable; the old interval is kept
        // so the measurement recovers with the next comparable sample.
        if (diffTotal <= 0 || diffIdle < 0 || diffTotal < diffIdle) return null
        statTotal = total
        statIdle = idle
        systemSampleNanos = now
        return oneDecimal(bucket(100.0 * (diffTotal - diffIdle) / diffTotal, 5))
    }

    private fun systemCpuTotals(): Pair<Long, Long>? {
        val stat = readProc("/proc/stat") ?: return null
        val cpuLine = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val fields = cpuLine.trim().split(WHITESPACE)
        if (fields.size < 9) return null
        val values = LongArray(8)
        for (i in 0 until 8) {
            values[i] = fields[i + 1].toLongOrNull() ?: return null
        }
        // The idle time also covers iowait, matching the classic utilization fraction.
        return values.sum() to (values[3] + values[4])
    }

    // null: no new process measurement this tick (baseline pending or unusable interval).
    private fun selfCpuPart(now: Long): String? {
        val self = selfCpuMillis()
        // getElapsedCpuTime is safe whole-process millisecond CPU time; the reading is
        // one-core-equivalent percent and multithreaded values above 100% are kept.
        if (self == null) {
            selfCpu = -1
            return "unavailable"
        }
        if (selfCpu < 0) {
            selfCpu = self
            selfSampleNanos = now
            return null
        }
        val elapsedNanos = now - selfSampleNanos
        val diffMs = self - selfCpu
        if (elapsedNanos <= 0 || diffMs < 0) return null
        selfCpu = self
        selfSampleNanos = now
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        return oneDecimal(bucket(100.0 * diffMs / (elapsedSeconds * 1000.0), 1))
    }

    private fun memSample() {
        var system = "unavailable"
        val meminfo = readProc("/proc/meminfo")
        if (meminfo != null) {
            val total = meminfoValue(meminfo, "MemTotal")
            val available = meminfoValue(meminfo, "MemAvailable")
            if (total != null && available != null && total > 0 && available in 0..total) {
                system = oneDecimal(bucket(100.0 * (total - available) / total, 1))
            }
        }
        val rss = readProc("/proc/self/status")?.lineSequence()
            ?.firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()?.split(WHITESPACE)?.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
            ?.let { bucketLong(it, 2048) }
        val pss = readProc("/proc/self/smaps_rollup")?.lineSequence()
            ?.firstOrNull { it.startsWith("Pss:") }
            ?.trim()?.split(WHITESPACE)?.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
            ?.let { bucketLong(it, 8192) }
        publish(
            "mem",
            "mem system_used_pct=$system process_rss_kb=${rss?.toString() ?: "unavailable"}" +
                (if (pss != null) " process_pss_kb=$pss" else ""),
        )
    }

    private fun meminfoValue(text: String, key: String): Long? =
        text.lineSequence().firstOrNull { it.startsWith("$key:") }
            ?.trim()?.split(WHITESPACE)?.getOrNull(1)?.toLongOrNull()

    private fun thermalSample() {
        val canonical = if (sdkInt < 29) {
            "thermal status=UNSUPPORTED"
        } else {
            when (val status = runCatching { thermalSource.currentThermalStatus() }.getOrNull()) {
                null -> "thermal status=unavailable"
                0 -> "thermal status=NONE"
                1 -> "thermal status=LIGHT"
                2 -> "thermal status=MODERATE"
                3 -> "thermal status=SEVERE"
                4 -> "thermal status=CRITICAL"
                5 -> "thermal status=EMERGENCY"
                6 -> "thermal status=SHUTDOWN"
                else -> "thermal status=UNKNOWN($status)"
            }
        }
        publish("thermal", canonical)
    }

    private fun zoneSample() {
        if (zones.isEmpty()) publish("zones", "zones unavailable")
        for ((dir, type) in zones) {
            val temp = readProc("/sys/class/thermal/$dir/temp")?.trim()?.toLongOrNull()
            val canonical = if (temp != null && temp in 10_000..120_000) {
                "zone $dir type=$type temp_c=${oneDecimal(temp / 1000.0)}"
            } else {
                "zone $dir type=$type unavailable"
            }
            publish("zone:$dir", canonical)
        }
    }

    private fun discoverZones(): List<Pair<String, String>> =
        runCatching { proc.list("/sys/class/thermal") }.getOrDefault(emptyList())
            .filter { it.startsWith("thermal_zone") }
            .sorted()
            .mapNotNull { dir ->
                val type = readProc("/sys/class/thermal/$dir/type")?.trim() ?: return@mapNotNull null
                val lower = type.lowercase(Locale.ROOT)
                if (lower.contains("cpu") || lower.contains("soc")) dir to type else null
            }
            .take(3)

    private fun readProc(path: String): String? = runCatching { proc.read(path) }.getOrNull()

    private fun publish(key: String, canonical: String) {
        if (lastCanonical[key] == canonical) return
        lastCanonical[key] = canonical
        budgeted(canonical)
    }

    private fun budgeted(record: String) {
        if (sessionRecords >= sessionBudget) {
            dropped++
            if (!budgetNoticeEmitted) {
                budgetNoticeEmitted = true
                emit("telemetry budget_exhausted dropped=$dropped")
            }
            // The first exhausted attempt ends the capture: no further source reads.
            captureEnded = true
            captureReason = "budget_exhausted"
            return
        }
        sessionRecords++
        emit(record)
    }

    private fun bucket(value: Double, step: Int): Double = floor(value / step) * step

    private fun bucketLong(value: Long, step: Long): Long = value / step * step

    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    companion object {
        const val TAG = "DeviceTelemetry"
        const val PERIOD_MILLIS = 15_000L
        const val SESSION_BUDGET = 1024

        // Explicit product decision: telemetry collects automatically for privileged shell
        // sessions (not opt-in). The record budget and this wall-clock deadline bound every
        // capture; a stop can happen earlier only through service teardown.
        const val CAPTURE_MILLIS = 60 * 60 * 1000L

        private val WHITESPACE = Regex("\\s+")

        private val instanceLock = Any()
        private var instance: DeviceTelemetry? = null

        fun start(context: Context) = start(XtLogger.currentRole, create = {
            DeviceTelemetry(
                nanoTime = System::nanoTime,
                scheduler = PlatformScheduler(),
                batterySource = PlatformBattery(context),
                proc = FileProcSource,
                thermalSource = PlatformThermal(context),
                sdkInt = Build.VERSION.SDK_INT,
            )
        })

        internal fun start(role: Role?, create: () -> DeviceTelemetry, beforeStart: () -> Unit = {}): Unit =
            synchronized(instanceLock) {
                if (role == null || role == Role.APP || instance != null) return
                try {
                    val telemetry = create()
                    instance = telemetry
                    beforeStart()
                    telemetry.start(role)
                } catch (t: Throwable) {
                    runCatching { instance?.stop() }
                    instance = null
                    XtLogger.error(TAG, "telemetry start failed", t)
                }
            }

        fun stop(): Unit = synchronized(instanceLock) {
            try {
                instance?.stop()
            } catch (t: Throwable) {
                XtLogger.error(TAG, "telemetry stop failed", t)
            } finally {
                instance = null
            }
        }
    }
}

internal class PlatformScheduler : TelemetryScheduler {
    override fun schedule(initialMillis: Long, periodMillis: Long, task: Runnable): TelemetryCancellation {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "xtlogger-telemetry").apply { isDaemon = true }
        }
        val future = try {
            // Fixed delay, not fixed rate: a late or slow sample must not trigger
            // catch-up collection of a backlogged queue.
            executor.scheduleWithFixedDelay(task, initialMillis, periodMillis, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            executor.shutdown()
            throw t
        }
        return TelemetryCancellation {
            future.cancel(false)
            executor.shutdown()
        }
    }
}

internal class PlatformBattery(context: Context, private val proc: ProcSource = FileProcSource) : BatterySource {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val supplies: List<Pair<String, String>> =
        proc.list("/sys/class/power_supply").mapNotNull { name ->
            val type = proc.read("/sys/class/power_supply/$name/type")?.trim()
                ?: return@mapNotNull null
            // the battery device itself reports online while present; it is not a charger
            if (type.equals("battery", ignoreCase = true)) return@mapNotNull null
            name to type.lowercase(Locale.ROOT)
        }

    override fun read(): BatteryReading? {
        val manager = batteryManager ?: return null
        return BatteryReading(
            levelPercent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 },
            // BATTERY_PROPERTY_STATUS requires API 26; below that status is honestly unknown
            status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS).takeIf { it in 1..5 }
            } else {
                null
            },
            charging = manager.isCharging,
            source = readSource(),
            temperatureDeciC = readBatteryTemp(),
        )
    }

    private fun readSource(): String? {
        if (supplies.isEmpty()) return null
        val states = supplies.map { (name, type) ->
            type to proc.read("/sys/class/power_supply/$name/online")?.trim()
        }
        val online = states.filter { it.second == "1" }.map { it.first }
        return when {
            online.any { it.contains("mains") } -> "AC"
            online.any { it.contains("usb") } -> "USB"
            online.any { it.contains("wireless") } -> "WIRELESS"
            online.isNotEmpty() -> "OTHER"
            states.any { it.second != "0" } -> null
            else -> "UNPLUGGED"
        }
    }

    private fun readBatteryTemp(): Int? =
        proc.read("/sys/class/power_supply/battery/temp")?.trim()?.toIntOrNull()
            ?.takeIf { it in -100..600 }
}

private object FileProcSource : ProcSource {
    override fun read(path: String): String? =
        runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun list(path: String): List<String> =
        runCatching { File(path).list()?.toList() }.getOrNull() ?: emptyList()
}

private class PlatformThermal(context: Context) : ThermalSource {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun currentThermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager?.currentThermalStatus
            } catch (e: RuntimeException) {
                null
            }
        } else {
            null
        }
}
