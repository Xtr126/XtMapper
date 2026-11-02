package xtr.keymapper.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class DeviceTelemetryTest {
    private val clock = MutableClock.at("2026-11-01T12:00:00Z")
    private val logcatRecords = CopyOnWriteArrayList<LogRecord>()

    private class FakeScheduler : TelemetryScheduler {
        val tasks = mutableListOf<Triple<Long, Long, Runnable>>()
        var cancels = 0
        override fun schedule(initialMillis: Long, periodMillis: Long, task: Runnable): TelemetryCancellation {
            tasks += Triple(initialMillis, periodMillis, task)
            return TelemetryCancellation { cancels++ }
        }
        fun runNow() = tasks.last().third.run()
    }

    private class FakeBatterySource : BatterySource {
        var reading: BatteryReading? = null
        var failure: RuntimeException? = null
        var calls = 0
        override fun read(): BatteryReading? {
            calls++
            failure?.let { throw it }
            return reading
        }
    }

    private class FakeThermalSource : ThermalSource {
        var status: Int? = null
        var calls = 0
        var failure: RuntimeException? = null
        override fun currentThermalStatus(): Int? {
            calls++
            failure?.let { throw it }
            return status
        }
    }

    private class MapProcSource : ProcSource {
        val files = mutableMapOf<String, String?>()
        val dirs = mutableMapOf<String, List<String>>()
        val unreadable = mutableSetOf<String>()
        var listFailure = false
        override fun read(path: String): String? {
            if (path in unreadable) throw SecurityException("denied")
            return files[path]
        }
        override fun list(path: String): List<String> {
            if (listFailure) throw SecurityException("denied")
            return dirs[path] ?: emptyList()
        }
    }

    private inner class Harness(
        sdk: Int = 34,
        sessionBudget: Int = DeviceTelemetry.SESSION_BUDGET,
        captureMillis: Long = DeviceTelemetry.CAPTURE_MILLIS,
    ) {
        val emitted = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val battery = FakeBatterySource()
        val thermal = FakeThermalSource()
        val proc = MapProcSource()
        var nanos = 0L

        // Process-CPU seam in milliseconds; null means the counter is unavailable.
        var selfCpuMillis: Long? = null

        val telemetry = DeviceTelemetry(
            nanoTime = { nanos },
            scheduler = scheduler,
            batterySource = battery,
            proc = proc,
            thermalSource = thermal,
            sdkInt = sdk,
            emit = emitted::add,
            selfCpuMillis = { selfCpuMillis },
            sessionBudget = sessionBudget,
            captureMillis = captureMillis,
        )

        fun advance(seconds: Long) {
            nanos += seconds * 1_000_000_000L
        }

        fun tick() = scheduler.runNow()

        fun start(role: Role = Role.SHELL) = telemetry.start(role)

        fun batteryTick(reading: BatteryReading?) {
            battery.reading = reading
            tick()
        }

        fun batteryRecords() = emitted.filter { it.startsWith("battery ") }

        fun trendRecords() = emitted.filter { it.startsWith("battery_trend ") }

        fun cpuRecords() = emitted.filter { it.startsWith("cpu ") }

        fun memRecords() = emitted.filter { it.startsWith("mem ") }

        fun thermalRecords() = emitted.filter { it.startsWith("thermal ") }

        fun zoneRecords() = emitted.filter { it.startsWith("zone ") }
    }

    private fun procStat(user: Long, system: Long, idle: Long): String = buildString {
        append("cpu  $user 0 $system $idle 0 0 0 0\n")
        append("cpu0 ${user / 2} 0 ${system / 2} ${idle / 2} 0 0 0 0\n")
        append("cpu1 ${user / 2} 0 ${system / 2} ${idle / 2} 0 0 0 0")
    }

    private val meminfo = "MemTotal:  1000000 kB\nMemFree:  100000 kB\nMemAvailable:  600000 kB\n"
    private val procStatus = "Name:\tx\nVmRSS:  512000 kB\n"
    private val smapsRollup = "Pss:  734000 kB\n"

    private fun validBattery(level: Int? = 78) = BatteryReading(
        levelPercent = level,
        status = 2,
        charging = true,
        source = "AC",
        temperatureDeciC = 312,
    )

    @Test
    fun `battery tick encodes state level source and temperature`() {
        val h = Harness()
        h.start()
        h.batteryTick(BatteryReading(78, 2, true, "AC", 312))
        assertEquals("battery state=CHARGING level=78 source=AC battery_temp_c=31.2", h.batteryRecords().single())
        h.batteryTick(BatteryReading(50, 3, false, "UNPLUGGED", -50))
        h.batteryTick(BatteryReading(60, 5, false, "USB", null))
        h.batteryTick(BatteryReading(40, 4, false, "WIRELESS", 250))
        h.batteryTick(BatteryReading(40, null, false, null, null))
        h.batteryTick(BatteryReading(40, 1, true, "OTHER", null))
        assertEquals(
            listOf(
                "battery state=CHARGING level=78 source=AC battery_temp_c=31.2",
                "battery state=DISCHARGING level=50 source=UNPLUGGED battery_temp_c=-5.0",
                "battery state=FULL level=60 source=USB",
                "battery state=NOT_CHARGING level=40 source=WIRELESS battery_temp_c=25.0",
                "battery state=UNKNOWN level=40 source=unavailable",
                "battery state=CHARGING level=40 source=OTHER",
            ),
            h.batteryRecords(),
        )
    }

    @Test
    fun `invalid battery level is unavailable and never zero filled`() {
        val h = Harness()
        h.start()
        h.batteryTick(validBattery(level = 101))
        h.batteryTick(validBattery(level = 50))
        h.batteryTick(validBattery(level = null))
        h.batteryTick(validBattery(level = -1))
        h.batteryTick(validBattery(level = 0))
        assertEquals(
            listOf(
                "battery unavailable",
                "battery state=CHARGING level=50 source=AC battery_temp_c=31.2",
                "battery unavailable",
                "battery state=CHARGING level=0 source=AC battery_temp_c=31.2",
            ),
            h.batteryRecords(),
        )
    }

    @Test
    fun `suppression compares canonical values including unavailable transitions`() {
        val h = Harness()
        h.start()
        h.batteryTick(validBattery(level = 50))
        h.batteryTick(validBattery(level = 50))
        h.batteryTick(validBattery(level = null))
        h.batteryTick(validBattery(level = null))
        h.batteryTick(validBattery(level = 50))
        assertEquals(
            listOf(
                "battery state=CHARGING level=50 source=AC battery_temp_c=31.2",
                "battery unavailable",
                "battery state=CHARGING level=50 source=AC battery_temp_c=31.2",
            ),
            h.batteryRecords(),
        )
    }

    @Test
    fun `battery trend needs two consecutive valid percent changes`() {
        val h = Harness()
        h.start()
        h.batteryTick(validBattery(level = 50))
        h.advance(300)
        h.batteryTick(validBattery(level = 49))
        assertTrue(h.trendRecords().isEmpty())
        h.advance(360)
        h.batteryTick(validBattery(level = 48))
        assertEquals(
            listOf("battery_trend direction=DISCHARGING rate_pct_per_hour=-10.0 window_s=360"),
            h.trendRecords(),
        )
    }

    @Test
    fun `invalid battery reading breaks the trend chain`() {
        val h = Harness()
        h.start()
        h.batteryTick(validBattery(level = 50))
        h.advance(300)
        h.batteryTick(validBattery(level = null))
        h.advance(300)
        h.batteryTick(validBattery(level = 48))
        h.advance(300)
        h.batteryTick(validBattery(level = 47))
        assertTrue(h.trendRecords().isEmpty())
        h.advance(360)
        h.batteryTick(validBattery(level = 46))
        assertEquals(1, h.trendRecords().size)
        assertTrue(h.trendRecords().single().contains("window_s=360"))
    }

    @Test
    fun `charging mode change restarts the battery trend rate window`() {
        val h = Harness()
        h.start()
        h.batteryTick(BatteryReading(40, 3, false, "BATTERY", 300))
        h.advance(60)
        h.batteryTick(BatteryReading(39, 3, false, "BATTERY", 300))
        h.advance(600)
        h.batteryTick(BatteryReading(38, 3, false, "BATTERY", 300))
        assertEquals(
            listOf("battery_trend direction=DISCHARGING rate_pct_per_hour=-6.0 window_s=600"),
            h.trendRecords(),
        )

        // Connecting power restarts the window; a rate must never span the transition.
        h.advance(60)
        h.batteryTick(BatteryReading(38, 2, true, "AC", 300))
        h.advance(60)
        h.batteryTick(BatteryReading(39, 2, true, "AC", 300))
        h.advance(60)
        h.batteryTick(BatteryReading(40, 2, true, "AC", 300))
        assertEquals(
            listOf(
                "battery_trend direction=DISCHARGING rate_pct_per_hour=-6.0 window_s=600",
                "battery_trend direction=CHARGING rate_pct_per_hour=60.0 window_s=60",
            ),
            h.trendRecords(),
        )
    }

    @Test
    fun `cpu first tick is a baseline and second tick publishes system and process pct`() {
        val h = Harness()
        h.proc.files["/proc/stat"] = procStat(user = 100, system = 100, idle = 800)
        h.selfCpuMillis = 2_000L
        h.start()
        h.advance(15)
        h.tick()
        assertTrue(h.cpuRecords().isEmpty())
        h.proc.files["/proc/stat"] = procStat(user = 200, system = 200, idle = 1400)
        h.selfCpuMillis = 2_500L
        h.advance(15)
        h.tick()
        assertEquals("cpu system_pct=25.0 process_pct=3.0", h.cpuRecords().single())
    }

    @Test
    fun `missing and malformed system cpu readings emit a suppressed unavailable value`() {
        val h = Harness()
        h.start()
        h.tick()
        h.proc.files["/proc/stat"] = "not a cpu line"
        h.tick()
        h.proc.files["/proc/stat"] = "cpu  1 2 3"
        h.tick()
        h.proc.files["/proc/stat"] = "cpu  1 0 broken 2 0 0 0 0"
        h.tick()
        assertEquals(listOf("cpu system_pct=unavailable process_pct=unavailable"), h.cpuRecords())
    }

    @Test
    fun `cpu availability recovers through a fresh baseline after missing stat`() {
        val h = Harness()
        h.proc.files["/proc/stat"] = procStat(100, 100, 800)
        h.selfCpuMillis = 2_000L
        h.start()
        h.tick()
        h.proc.files["/proc/stat"] = procStat(200, 200, 1400)
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] = null
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] = procStat(300, 300, 2000)
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] = procStat(400, 400, 2600)
        h.advance(15)
        h.tick()
        assertEquals(
            listOf(
                "cpu system_pct=25.0 process_pct=0.0",
                "cpu system_pct=unavailable process_pct=0.0",
                "cpu system_pct=25.0 process_pct=0.0",
            ),
            h.cpuRecords(),
        )
    }

    @Test
    fun `process cpu cannot combine an old counter with a newer interval timestamp`() {
        val h = Harness()
        h.proc.files["/proc/stat"] = procStat(100, 100, 800)
        h.selfCpuMillis = 2_000L
        h.start()
        h.tick()
        h.proc.files["/proc/stat"] = procStat(200, 200, 1400)
        h.selfCpuMillis = null
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] = procStat(300, 300, 2000)
        h.selfCpuMillis = 3_000L
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] = procStat(400, 400, 2600)
        h.selfCpuMillis = 3_150L
        h.advance(15)
        h.tick()
        assertEquals(
            listOf("cpu system_pct=25.0 process_pct=unavailable", "cpu system_pct=25.0 process_pct=1.0"),
            h.cpuRecords(),
        )
    }

    @Test
    fun `battery and thermal failures do not escape a tick or starve other metrics`() {
        val h = Harness()
        h.proc.files["/proc/meminfo"] = meminfo
        h.start()
        h.battery.failure = SecurityException("binder unavailable")
        h.thermal.failure = IllegalStateException("binder unavailable")
        h.tick()
        assertEquals(listOf("battery unavailable"), h.batteryRecords())
        assertEquals(listOf("thermal status=unavailable"), h.thermalRecords())
        assertEquals(1, h.memRecords().size)
        h.battery.failure = null
        h.thermal.failure = null
        h.thermal.status = 2
        h.batteryTick(validBattery())
        assertEquals(2, h.batteryRecords().size)
        assertEquals("thermal status=MODERATE", h.thermalRecords().last())
    }

    @Test
    fun `proc read failures become unavailable and later ticks recover`() {
        val h = Harness()
        h.start()
        h.proc.unreadable += listOf("/proc/stat", "/proc/meminfo", "/proc/self/status", "/proc/self/smaps_rollup")
        h.tick()
        h.tick()
        assertEquals(listOf("cpu system_pct=unavailable process_pct=unavailable"), h.cpuRecords())
        assertEquals(listOf("mem system_used_pct=unavailable process_rss_kb=unavailable"), h.memRecords())
        h.proc.unreadable.clear()
        h.proc.files["/proc/meminfo"] = meminfo
        h.tick()
        assertEquals("mem system_used_pct=40.0 process_rss_kb=unavailable", h.memRecords().last())
    }

    @Test
    fun `failed thermal discovery still starts a cancellable collector`() {
        val h = Harness()
        h.proc.listFailure = true
        h.start()
        h.tick()
        assertEquals(1, h.scheduler.tasks.size)
        assertTrue(h.emitted.contains("zones unavailable"))
        h.telemetry.stop()
        assertEquals(1, h.scheduler.cancels)
    }

    @Test
    fun `cpu zero intervals and counter regressions only suppress the affected side`() {
        val h = Harness()
        h.proc.files["/proc/stat"] = procStat(user = 100, system = 100, idle = 800)
        h.selfCpuMillis = 2_000L
        h.start()
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] = procStat(user = 200, system = 200, idle = 1400)
        h.tick()
        assertTrue("zero interval must not publish", h.cpuRecords().isEmpty())
        h.advance(15)
        h.proc.files["/proc/stat"] = procStat(user = 50, system = 50, idle = 400)
        h.selfCpuMillis = 2_600L
        h.tick()
        assertEquals(
            "counter regression on the system side must not withhold the process reading",
            "cpu system_pct=unavailable process_pct=4.0",
            h.cpuRecords().single(),
        )
        h.advance(15)
        h.proc.files["/proc/stat"] = procStat(user = 200, system = 200, idle = 1400)
        h.selfCpuMillis = 2_700L
        h.tick()
        assertEquals(
            "regressed counters rebase so the next tick measures a fresh interval",
            "cpu system_pct=20.0 process_pct=0.0",
            h.cpuRecords().last(),
        )
    }

    @Test
    fun `cpu utilization beyond the old one-core plausibility caps is published`() {
        val h = Harness()
        h.proc.files["/proc/stat"] = procStat(user = 100, system = 100, idle = 800)
        h.selfCpuMillis = 1_000L
        h.start()
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] =
            "cpu  375000000 0 375000000 240000000 0 0 0 0\ncpu0 0 0 0 0 0 0 0 0\ncpu1 0 0 0 0 0 0 0 0"
        h.selfCpuMillis = 2_500_000L
        h.advance(15)
        h.tick()
        assertEquals("cpu system_pct=75.0 process_pct=16660.0", h.cpuRecords().single())
    }

    @Test
    fun `cpu and memory values are quantized to stable buckets`() {
        val h = Harness()
        h.proc.files["/proc/stat"] =
            "cpu  273 0 200 1527 0 0 0 0\ncpu0 100 0 100 800 0 0 0 0\ncpu1 173 0 100 727 0 0 0 0"
        h.selfCpuMillis = 2_000L
        h.proc.files["/proc/meminfo"] = meminfo
        h.proc.files["/proc/self/status"] = "Name:\tx\nVmRSS:  513000 kB\n"
        h.proc.files["/proc/self/smaps_rollup"] = smapsRollup
        h.start()
        h.advance(15)
        h.tick()
        h.proc.files["/proc/stat"] =
            "cpu  473 0 300 2327 0 0 0 0\ncpu0 100 0 100 800 0 0 0 0\ncpu1 373 0 200 1527 0 0 0 0"
        h.selfCpuMillis = 2_200L
        h.advance(15)
        h.tick()
        assertEquals("cpu system_pct=25.0 process_pct=1.0", h.cpuRecords().single())
        assertEquals(
            "mem system_used_pct=40.0 process_rss_kb=512000 process_pss_kb=729088",
            h.memRecords().single(),
        )
    }

    @Test
    fun `mem record carries distinct system rss and pss values`() {
        val h = Harness()
        h.proc.files["/proc/meminfo"] = meminfo
        h.proc.files["/proc/self/status"] = procStatus
        h.proc.files["/proc/self/smaps_rollup"] = smapsRollup
        h.start()
        h.tick()
        assertEquals(
            "mem system_used_pct=40.0 process_rss_kb=512000 process_pss_kb=729088",
            h.memRecords().single(),
        )
    }

    @Test
    fun `mem unavailable sources are explicit and pss key disappears when unreadable`() {
        val h = Harness()
        h.proc.files["/proc/meminfo"] = meminfo
        h.proc.files["/proc/self/status"] = "Name:\tx\n"
        h.proc.files["/proc/self/smaps_rollup"] = null
        h.start()
        h.tick()
        assertEquals("mem system_used_pct=40.0 process_rss_kb=unavailable", h.memRecords().single())
        h.proc.files["/proc/meminfo"] = "MemTotal:  0 kB\nMemAvailable:  0 kB\n"
        h.proc.files["/proc/self/status"] = procStatus
        h.advance(15)
        h.tick()
        assertEquals("mem system_used_pct=unavailable process_rss_kb=512000", h.memRecords()[1])
        h.proc.files["/proc/meminfo"] = "MemTotal:  1000000 kB\nMemAvailable:  1000001 kB\n"
        h.proc.files["/proc/self/status"] = "Name:\tx\n"
        h.advance(15)
        h.tick()
        assertEquals("mem system_used_pct=unavailable process_rss_kb=unavailable", h.memRecords()[2])
        h.advance(15)
        h.tick()
        assertEquals("unchanged unavailable mem must stay suppressed", 3, h.memRecords().size)
    }

    @Test
    fun `thermal status below api 29 is unsupported without consulting the source`() {
        val h = Harness(sdk = 28)
        h.start()
        h.tick()
        assertEquals("thermal status=UNSUPPORTED", h.thermalRecords().single())
        assertEquals(0, h.thermal.calls)
    }

    @Test
    fun `thermal status maps to labels unknown values and never contains a temperature`() {
        val h = Harness(sdk = 34)
        h.thermal.status = 2
        h.start()
        h.tick()
        h.thermal.status = 42
        h.advance(15)
        h.tick()
        h.thermal.status = null
        h.advance(15)
        h.tick()
        assertEquals(
            listOf("thermal status=MODERATE", "thermal status=UNKNOWN(42)", "thermal status=unavailable"),
            h.thermalRecords(),
        )
        h.emitted.filter { it.startsWith("thermal") }.forEach {
            assertFalse("thermal records must not carry a temperature: $it", it.contains("temp"))
        }
    }

    @Test
    fun `zone discovery keeps at most three cpu or soc zones in deterministic order`() {
        val h = Harness()
        h.proc.dirs["/sys/class/thermal"] = (0..9).map { "thermal_zone$it" }
        val types = mapOf(
            "thermal_zone0" to "cpu0-thermal",
            "thermal_zone1" to "cpu1-thermal",
            "thermal_zone2" to "soc-thermal",
            "thermal_zone3" to "cpu-thermal",
            "thermal_zone4" to "skin-therm",
            "thermal_zone5" to "battery",
            "thermal_zone6" to "gpu-therm",
            "thermal_zone7" to "modem-therm",
            "thermal_zone8" to "board-therm",
            "thermal_zone9" to "flash-therm",
        )
        types.forEach { (dir, type) -> h.proc.files["/sys/class/thermal/$dir/type"] = type }
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = "45200"
        h.proc.files["/sys/class/thermal/thermal_zone1/temp"] = "50100"
        h.proc.files["/sys/class/thermal/thermal_zone2/temp"] = "38000"
        h.start()
        h.tick()
        assertEquals(
            listOf(
                "zone thermal_zone0 type=cpu0-thermal temp_c=45.2",
                "zone thermal_zone1 type=cpu1-thermal temp_c=50.1",
                "zone thermal_zone2 type=soc-thermal temp_c=38.0",
            ),
            h.zoneRecords(),
        )
    }

    @Test
    fun `zone temps validate range inclusively and mark unreadable zones unavailable`() {
        val h = Harness()
        h.proc.dirs["/sys/class/thermal"] = listOf("thermal_zone0")
        h.proc.files["/sys/class/thermal/thermal_zone0/type"] = "cpu-thermal"
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = "10000"
        h.start()
        h.tick()
        assertEquals("zone thermal_zone0 type=cpu-thermal temp_c=10.0", h.zoneRecords().single())
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = "120000"
        h.advance(15)
        h.tick()
        assertEquals("zone thermal_zone0 type=cpu-thermal temp_c=120.0", h.zoneRecords()[1])
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = "120001"
        h.advance(15)
        h.tick()
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = "9999"
        h.advance(15)
        h.tick()
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = null
        h.advance(15)
        h.tick()
        assertEquals(
            "unreadable and implausible temps must collapse to one unavailable canonical",
            listOf(
                "zone thermal_zone0 type=cpu-thermal temp_c=10.0",
                "zone thermal_zone0 type=cpu-thermal temp_c=120.0",
                "zone thermal_zone0 type=cpu-thermal unavailable",
            ),
            h.zoneRecords(),
        )
        h.proc.files["/sys/class/thermal/thermal_zone0/temp"] = "45200"
        h.advance(15)
        h.tick()
        assertEquals(4, h.zoneRecords().size)
    }

    @Test
    fun `unreadable thermal directory explicitly reports unavailable once`() {
        val h = Harness()
        h.proc.dirs["/sys/class/thermal"] = emptyList()
        h.start()
        h.tick()
        h.advance(15)
        h.tick()
        assertTrue(h.zoneRecords().isEmpty())
        assertEquals(1, h.emitted.count { it == "zones unavailable" })
    }

    @Test
    fun `session budget caps total records and reports drops in the stop record`() {
        val h = Harness(captureMillis = Long.MAX_VALUE)
        h.start()
        var i = 0
        while (h.emitted.size < DeviceTelemetry.SESSION_BUDGET && i < DeviceTelemetry.SESSION_BUDGET * 2) {
            i++
            h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = i))
            h.advance(15)
        }
        assertEquals(DeviceTelemetry.SESSION_BUDGET, h.emitted.size)
        // The first post-budget tick still reads the source, hits the budget and is dropped.
        val readsAtBudget = h.battery.calls
        h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = i + 1))
        assertEquals(readsAtBudget + 1, h.battery.calls)
        h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = i + 2))
        h.telemetry.stop()
        assertEquals(1, h.emitted.count { it.startsWith("telemetry budget_exhausted ") })
        assertEquals("telemetry budget_exhausted dropped=1", h.emitted[DeviceTelemetry.SESSION_BUDGET])
        assertTrue(h.emitted.last().startsWith("telemetry stop records="))
        assertEquals("source reads stop once the budget is exhausted", readsAtBudget + 1, h.battery.calls)
        assertTrue(h.emitted.last().contains("dropped=1"))
        assertTrue(h.emitted.last().contains("early=budget_exhausted"))
    }

    @Test
    fun `budget exhaustion emits its one notice then stops reading the sources`() {
        val h = Harness(sessionBudget = 8, captureMillis = Long.MAX_VALUE)
        h.start()
        h.tick() // first unavailable sweep: start plus five source records
        h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = 1))
        h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = 2))
        h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = 3))
        assertEquals(9, h.emitted.size)
        assertEquals("telemetry budget_exhausted dropped=1", h.emitted[8])
        val reads = h.battery.calls
        h.batteryTick(validBattery(level = 50).copy(temperatureDeciC = 4))
        assertEquals("no source is read after the exhausted budget", reads, h.battery.calls)
        h.telemetry.stop()
        assertTrue(h.emitted.last().contains("telemetry stop records=8 dropped=1"))
        assertTrue(h.emitted.last().contains("early=budget_exhausted"))
        assertEquals(1, h.emitted.count { it.startsWith("telemetry budget_exhausted ") })
    }

    @Test
    fun `capture expiry stops sampling and is named in the stop record`() {
        val h = Harness(captureMillis = 1_000)
        h.start()
        h.tick()
        assertEquals(6, h.emitted.size)
        assertEquals(1, h.battery.calls)
        h.advance(15)
        h.tick()
        assertEquals("no source is read after the capture expires", 1, h.battery.calls)
        assertEquals("no record is emitted after the capture expires", 6, h.emitted.size)
        h.telemetry.stop()
        assertTrue(h.emitted.last().contains("telemetry stop records=6 dropped=0"))
        assertTrue(h.emitted.last().contains("early=capture_expired"))
    }

    @Test
    fun `process cpu is still measured while the system counters are unavailable`() {
        val h = Harness()
        h.start()
        h.tick()
        assertEquals("cpu system_pct=unavailable process_pct=unavailable", h.cpuRecords().single())
        h.selfCpuMillis = 2_000L
        h.tick()
        h.advance(15)
        h.selfCpuMillis = 2_600L
        h.tick()
        assertEquals(
            listOf(
                "cpu system_pct=unavailable process_pct=unavailable",
                "cpu system_pct=unavailable process_pct=4.0",
            ),
            h.cpuRecords(),
        )
    }

    @Test
    fun `start is idempotent and uses the pinned period`() {
        val h = Harness()
        h.start()
        h.start()
        assertEquals(1, h.scheduler.tasks.size)
        assertEquals(DeviceTelemetry.PERIOD_MILLIS, h.scheduler.tasks[0].second)
        assertEquals(0L, h.scheduler.tasks[0].first)
        assertEquals("telemetry start role=SHELL period_ms=15000 budget=1024", h.emitted.first())
    }

    @Test
    fun `stop is idempotent cancels once`() {
        val h = Harness()
        h.start()
        h.telemetry.stop()
        h.telemetry.stop()
        assertEquals(1, h.scheduler.cancels)
        assertTrue(h.emitted.last().startsWith("telemetry stop records="))
        assertFalse(h.emitted.last().contains("early="))
    }

    @Test
    fun `stale ticks after stop emit nothing`() {
        val h = Harness()
        h.start()
        h.tick()
        val before = h.emitted.size
        h.telemetry.stop()
        h.battery.reading = validBattery()
        h.tick()
        assertEquals(before + 1, h.emitted.size)
    }

    @Test
    fun `restart after stop registers a fresh scheduler task and emits again`() {
        val h = Harness()
        h.start()
        h.batteryTick(validBattery(level = 50))
        h.telemetry.stop()
        h.start()
        assertEquals(2, h.scheduler.tasks.size)
        h.batteryTick(validBattery(level = 50))
        assertEquals(2, h.batteryRecords().size)
        h.tick()
        h.telemetry.stop()
        assertEquals(2, h.scheduler.cancels)
    }

    @Test
    fun `a previous session scheduler cannot sample a restarted collector`() {
        val h = Harness()
        h.start()
        val stale = h.scheduler.tasks.single().third
        h.telemetry.stop()
        h.start()
        val before = h.emitted.size
        h.battery.reading = validBattery()
        stale.run()
        assertEquals("a cancelled task stays stale across restart", before, h.emitted.size)
        h.tick()
        assertEquals(1, h.batteryRecords().size)
    }

    @Test
    fun `app role never starts collection`() {
        val h = Harness()
        h.start(Role.APP)
        assertTrue(h.scheduler.tasks.isEmpty())
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun `platform scheduler spaces runs by fixed delay so a slow sample cannot catch up`() {
        val starts = CopyOnWriteArrayList<Long>()
        val cancel = PlatformScheduler().schedule(0, 50, {
            starts.add(System.nanoTime())
            Thread.sleep(80)
        })
        try {
            Thread.sleep(1_500)
        } finally {
            cancel.cancel()
        }
        assertTrue("at least three samples ran", starts.size >= 3)
        for (i in 1 until starts.size) {
            val gapMillis = TimeUnit.NANOSECONDS.toMillis(starts[i] - starts[i - 1])
            // 80ms of work plus the 50ms fixed delay leaves a >=130ms gap; fixed rate would not.
            assertTrue("fixed delay must space runs beyond work+gap: $gapMillis", gapMillis >= 120)
        }
    }

    private class RecordingStore : LogStore {
        val chunks = CopyOnWriteArrayList<String>()
        override fun listFiles(): List<LogFileInfo> = emptyList()
        override fun openAppendWriter(name: String): LogFileWriter =
            object : LogFileWriter {
                override val writtenBytes: Long get() = 0L
                override fun write(chunk: ByteArray) { chunks.add(chunk.toString(Charsets.UTF_8)) }
                override fun close() {}
            }
        override fun readTail(name: String, maxLines: Int): List<String> = emptyList()
        override fun delete(name: String): Boolean = false
    }

    private fun stateField(name: String): Field =
        XtLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    @After
    fun tearDown() {
        runCatching {
            (stateField("pipeline").get(XtLogger) as? LoggingPipeline)?.close("Teardown")
        }
        for (name in listOf("pipeline", "initializationRole", "retiringOwner")) {
            runCatching { stateField(name).set(XtLogger, null) }
        }
        runCatching { stateField("fatalEventRecorded").set(XtLogger, false) }
        logcatRecords.clear()
    }

    @Test
    fun `telemetry records survive the release config pipeline`() {
        val store = RecordingStore()
        val pipeline = LoggingPipeline(
            role = Role.SHELL,
            sessionId = "telemetry-e2e",
            store = store,
            config = LoggingConfig.DEFAULT_RELEASE,
            clock = clock,
            zone = clock.zoneId,
            startTime = clock.instant(),
            durationNanos = { 0 },
            logcat = { logcatRecords.add(it) },
            pruneRetention = false,
        )
        pipeline.start()
        stateField("pipeline").set(XtLogger, pipeline)
        stateField("initializationRole").set(XtLogger, Role.SHELL)
        try {
            val h = Harness()
            val direct = DeviceTelemetry(
                nanoTime = { h.nanos },
                scheduler = h.scheduler,
                batterySource = h.battery,
                proc = h.proc,
                thermalSource = h.thermal,
                sdkInt = 34,
            )
            direct.start(Role.SHELL)
            h.batteryTick(validBattery())
            direct.stop()
            val telemetryRecords = logcatRecords.filter {
                it.tag == DeviceTelemetry.TAG && it.level == Level.INFO
            }
            assertTrue(telemetryRecords.isNotEmpty())
            assertTrue(telemetryRecords.first().message.startsWith("telemetry start role=SHELL"))
            assertTrue(telemetryRecords.any { it.message.startsWith("battery state=CHARGING") })
            assertTrue(pipeline.flush(2_000))
            val persisted = store.chunks.joinToString("")
            assertTrue(persisted.contains("telemetry start role=SHELL period_ms=15000 budget=1024"))
            assertTrue(persisted.contains("battery state=CHARGING level=78 source=AC"))
            assertTrue(persisted.contains("telemetry stop records="))
        } finally {
            pipeline.close("E2E teardown")
            stateField("pipeline").set(XtLogger, null)
        }
    }
}
