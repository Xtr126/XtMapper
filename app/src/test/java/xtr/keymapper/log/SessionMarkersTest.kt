package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SessionMarkersTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun `separator lines surround every marker block`() {
        val body = SessionMarkers.startLine(
            id = "20261023-171000-8F3A",
            started = Instant.parse("2026-10-23T17:10:00.000Z"),
            zone = zone,
            meta = SessionMetadata(appVersion = "2.5.0", buildType = "Debug", androidVersion = "15", sdkInt = 35, environment = "waydroid"),
            previous = SessionState.DISPOSITION_FIRST,
        )
        val block = SessionMarkers.block(body)
        val lines = block.split('\n')
        assertEquals(RecordFormatter.SEPARATOR_80, lines.first())
        assertEquals(RecordFormatter.SEPARATOR_80, lines.last())
        assertEquals(SessionMarkers.START, lines[1])
        assertTrue(lines.any { it == "Session ID: 20261023-171000-8F3A" })
        // The UTC zone renders as Z (explicit zero-offset ISO-8601 form).
        assertTrue(lines.any { it == "Start Time: 2026-10-23T17:10:00.000Z" })
        assertTrue(lines.any { it == "App Version: 2.5.0" })
        assertTrue(lines.any { it == "Build Type: Debug" })
        assertTrue(lines.any { it == "Android Version: 15 (sdk 35)" })
        assertTrue(lines.any { it == "Environment: waydroid" })
        assertTrue(lines.any { it == "Previous Session: First session" })
    }

    @Test
    fun `start time keeps an explicit utc offset in every locale`() {
        val body = SessionMarkers.startLine(
            id = "id-1",
            started = Instant.parse("2026-01-01T00:30:00.250Z"),
            zone = ZoneId.of("Europe/Berlin"),
            meta = SessionMetadata(),
            previous = SessionState.DISPOSITION_CLOSED,
        )
        // Europe/Berlin in January is +01:00, so the wall time must read 01:30.
        assertTrue(body.contains("Start Time: 2026-01-01T01:30:00.250+01:00"))
        assertTrue(body.contains("Previous Session: Closed cleanly"))
    }

    @Test
    fun `unknown metadata values are omitted rather than fabricated`() {
        val body = SessionMarkers.startLine(
            id = "id-2", started = Instant.EPOCH, zone = zone,
            meta = SessionMetadata(), previous = "x",
        )
        assertFalse(body.contains("App Version"))
        assertFalse(body.contains("Build Type"))
        assertFalse(body.contains("Android Version"))
        assertFalse(body.contains("Device"))
        assertFalse(body.contains("Environment"))
    }

    @Test
    fun `continued marker keeps the session id and original start`() {
        val block = SessionMarkers.block(
            SessionMarkers.continuedLine(
                id = "20261022-231500-C74A",
                originalStart = Instant.parse("2026-10-22T23:15:00.000Z"),
                continuationTime = Instant.parse("2026-10-23T00:00:00.500Z"),
                zone = zone,
            )
        )
        assertTrue(block.contains(SessionMarkers.CONTINUED))
        assertTrue(block.contains("Session ID: 20261022-231500-C74A"))
        assertTrue(block.contains("Continuation Time: 2026-10-23T00:00:00.500Z"))
        assertTrue(block.contains("Original Start Time: 2026-10-22T23:15:00.000Z"))
    }

    @Test
    fun `end marker has duration over a monotonic source and drop count only when positive`() {
        val clean = SessionMarkers.block(
            SessionMarkers.endLine("s1", Instant.EPOCH, zone, (29 * 60 + 40) * 1_000_000_000L + 331_000_000L, "Clean shutdown", 0)
        )
        assertTrue(clean.contains(SessionMarkers.END))
        assertTrue(clean.contains("Duration: 00:29:40.331"))
        assertFalse(clean.contains("Dropped:"))

        val withDrops = SessionMarkers.block(
            SessionMarkers.endLine("s1", Instant.EPOCH, zone, 0, "Clean shutdown", 17)
        )
        assertTrue(withDrops.contains("Dropped: 17"))
    }

    @Test
    fun `duration format pads every component`() {
        assertEquals("00:00:00.000", SessionMarkers.formatDurationNanos(0))
        assertEquals("00:00:01.500", SessionMarkers.formatDurationNanos(1_500_000_000))
        // Exactly one hour plus a single nanosecond: the millisecond part is 000.
        assertEquals("01:00:00.000", SessionMarkers.formatDurationNanos(3_600_000_000_001))
        // Exactly one hour plus one millisecond: the millisecond part renders.
        assertEquals("01:00:00.001", SessionMarkers.formatDurationNanos(3_600_001_000_000))
        val five = SessionMarkers.formatDurationNanos((2 * 3600 + 3 * 60 + 4) * 1_000_000_000L + 5_000_000)
        assertEquals("02:03:04.005", five)
    }

    @Test
    fun `marker titles are recognized exactly`() {
        assertEquals(SessionMarkers.START, SessionMarkers.markerTypeOf("SESSION START"))
        assertEquals(SessionMarkers.CONTINUED, SessionMarkers.markerTypeOf("SESSION CONTINUED "))
        assertEquals(SessionMarkers.END, SessionMarkers.markerTypeOf("SESSION END"))
        assertTrue(SessionMarkers.markerTypeOf("2026-10-22T10:15:32.510+01:00 [INFO] message=\"x\"") == null)
        assertTrue(SessionMarkers.markerTypeOf("") == null)
    }

    @Test
    fun `session ids include a random suffix and are not unique by timestamp alone`() {
        val clock = java.time.Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), zone)
        val ids = (1..64).map { SessionIds.generate(clock) }
        assertTrue(ids.all { it.startsWith("20261023-100000-") })
        assertTrue("random suffix distinguishes ids", ids.toSet().size > 1)
        assertNotEquals(ids[0], ids[1].takeIf { it != ids[0] } ?: ids[2])
    }
}
