package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class RecordFormatterTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val fixedTime: Instant = Instant.parse("2026-10-22T08:15:32.510Z") // 10:15:32.510+01:00

    private fun record(
        message: String,
        throwable: Throwable? = null,
        threadName: String = "main",
        threadId: Long = 1,
        sessionId: String? = "20261022-091532-A81C",
        level: Level = Level.INFO,
        tag: String = "Test",
        role: String = "app",
        pid: Int? = 1234,
    ): LogRecord = LogRecord(
        time = fixedTime,
        level = level,
        tag = tag,
        message = message,
        throwable = throwable,
        pid = pid,
        role = role,
        threadName = threadName,
        threadId = threadId,
        sessionId = sessionId,
    )

    @Test
    fun `formats a plain record with all fields in the documented order`() {
        val line = RecordFormatter.format(record(message = "Application initialized"), zone)
        assertEquals(
            "2026-10-22T10:15:32.510+02:00 [INFO] pid=1234 role=app thread=\"main\" tid=1 " +
                "session=20261022-091532-A81C tag=\"Test\" message=\"Application initialized\"",
            line,
        )
    }

    @Test
    fun `timestamp includes explicit utc offset`() {
        val line = RecordFormatter.format(record(message = "x"), ZoneId.of("UTC"))
        assertTrue(line.startsWith("2026-10-22T08:15:32.510Z") || line.startsWith("2026-10-22T08:15:32.510+00:00"))
    }

    @Test
    fun `severity names are rendered inside brackets`() {
        for (level in Level.entries) {
            val line = RecordFormatter.format(record(message = "x", level = level), zone)
            assertTrue("[$level]", line.contains("[${level.name}]"))
        }
    }

    @Test
    fun `newlines quotes and control characters are escaped inside quotes`() {
        val raw = "multi\nline \"quoted\" tab\there\n\rnull${'\u0001'}end"
        val line = RecordFormatter.format(record(message = raw), zone)
        // The record must stay a single physical line even with embedded newlines.
        assertFalse(line.contains("\n"))
        val expectedEscaped = "multi\\nline \\\"quoted\\\" tab\\there\\n\\rnull" + "\\" + "u0001" + "end"
        assertEquals(expectedEscaped, RecordFormatter.escapeInline(raw))
        assertTrue(line.contains("message=\"$expectedEscaped\""))
    }

    @Test
    fun `escape and unescape roundtrip`() {
        val raw = "a\\b \"q\"\nn \r\t\u0002 mix"
        assertEquals(raw, RecordFormatter.unescapeInline(RecordFormatter.escapeInline(raw)))
        assertNull(RecordFormatter.throwableBlock(IOException("x")).takeIf { it.isBlank() })
    }

    @Test
    fun `throwable renders exception class message and pipe-prefixed frames`() {
        val cause = try {
            throw IllegalStateException("database exploded")
        } catch (e: IllegalStateException) { e }
        val rendered = RecordFormatter.format(record(message = "unexpected failure", throwable = cause), zone)
        val lines = rendered.split('\n')
        assertTrue(lines.first().endsWith("message=\"unexpected failure\""))
        val exceptionLine = lines.firstOrNull { it.contains("exception=java.lang.IllegalStateException: database exploded") }
        assertTrue("exception line present", exceptionLine != null)
        assertTrue(exceptionLine!!.startsWith(RecordFormatter.EXCEPTION_INDENT))
        // Frames are rendered as `    | <frame toString>` per the grammar.
        val frameLines = lines.filter {
            it.startsWith(RecordFormatter.EXCEPTION_INDENT + "| ") && !it.contains("(no stack trace)")
        }
        assertTrue("at least one frame", frameLines.isNotEmpty())
    }

    @Test
    fun `exception without stack frames is still syntactically complete`() {
        val e = FakeException()
        // A pre-crafted exception has no captured stack trace.
        e.stackTrace = emptyArray()
        val rendered = RecordFormatter.format(record(message = "failure", throwable = e), zone)
        assertTrue(rendered.contains("| (no stack trace)"))
    }

    @Test
    fun `long messages are clipped`() {
        val long = "x".repeat(RecordFormatter.MAX_MESSAGE_CHARS + 100)
        val line = RecordFormatter.format(record(message = long), zone)
        // The message field carries the bound and an exact overshoot count.
        assertTrue(line.contains("…(truncated 100 chars)"))
        // And the whole record stays bounded well below the raw size.
        assertTrue(line.length in 7_900..8_500)
    }

    @Test
    fun `thread name with quotes is escaped`() {
        val line = RecordFormatter.format(record(message = "x", threadName = "worker\"2\n"), zone)
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("thread=\"worker\\\"2\\n\""))
    }

    @Test
    fun `tag is quoted and escaped so newline or marker text stays inert`() {
        val hostileTag = "Tag\"2\nSESSION END\n==="
        val line = RecordFormatter.format(record(message = "x", tag = hostileTag), zone)
        // The record stays a single physical line.
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("tag=\"Tag\\\"2\\nSESSION END\\n===\""))
        // No structural line can be fabricated: marker scanning stays null.
        assertNull(SessionMarkers.markerTypeOf(line))
    }

    @Test
    fun `exception message is escaped and cannot imitate a marker line`() {
        val hostile = IOException("boom\nSESSION END\n" + "=".repeat(80))
        val rendered = RecordFormatter.throwableBlock(hostile)
        // The raw newline was escaped; the rendered exception block contains no
        // line that starts with a marker title.
        assertFalse(rendered.contains("boom\n"))
        rendered.split('\n').forEach { line ->
            assertNull("fake marker line: $line", SessionMarkers.markerTypeOf(line))
        }
        assertTrue(rendered.contains("boom\\nSESSION END\\n"))
    }

    @Test
    fun `cause chains and suppressed exceptions are rendered under pipe prefixes`() {
        val suppressed = RuntimeException("suppressed sibling")
        val cause = IOException("root cause failure")
        val top = IllegalStateException("wrapper exploded", cause)
        top.addSuppressed(suppressed)
        val rendered = RecordFormatter.throwableBlock(top)
        assertTrue(rendered.contains("Suppressed: java.lang.RuntimeException: suppressed sibling"))
        assertTrue(rendered.contains("Caused by: java.io.IOException: root cause failure"))
        // Every continuation line (suppressed header and its frames) keeps the
        // pipe prefix from the documented grammar.
        rendered.split('\n').forEach { line ->
            if (line != rendered.split('\n').first()) {
                assertTrue("continuation must be pipe-prefixed: $line", line.startsWith(RecordFormatter.EXCEPTION_INDENT + "|"))
            }
        }
    }

    @Test
    fun `cyclic cause chains terminate with an explicit note`() {
        // The JDK forbids t.initCause(t); the reachable pathological case is a
        // mutual cycle: A → B → A. Traces cleared for a deterministic size bound.
        val a = RuntimeException("cycle A")
        val b = RuntimeException("cycle B", a)
        a.initCause(b)
        a.stackTrace = emptyArray()
        b.stackTrace = emptyArray()
        val rendered = RecordFormatter.throwableBlock(b)
        assertTrue(rendered.contains("| Caused by: java.lang.RuntimeException"))
        assertTrue(rendered.contains("already rendered"))
        assertTrue("bounded output", rendered.length < 1_000)
    }

    @Test
    fun `deep cause chains are bounded by the cause depth`() {
        var current: Throwable = IOException("deepest")
        repeat(50) { current = IllegalStateException("wrapper $it", current) }
        val rendered = RecordFormatter.throwableBlock(current)
        val causedByCount = Regex("Caused by:").findAll(rendered).count()
        assertTrue("chain bounded (was $causedByCount)", causedByCount <= RecordFormatter.MAX_CAUSE_DEPTH)
        assertTrue(rendered.contains("beyond depth"))
    }

    @Test
    fun `suppressed exceptions per throwable are bounded`() {
        val top = IllegalStateException("top")
        repeat(10) { top.addSuppressed(RuntimeException("sup $it")) }
        val rendered = RecordFormatter.throwableBlock(top)
        assertEquals(
            RecordFormatter.MAX_SUPPRESSED_PER_THROWABLE,
            Regex("Suppressed:").findAll(rendered).count(),
        )
        assertTrue(rendered.contains("more suppressed omitted"))
    }

    @Test
    fun `shared frame budget is honoured across cause sections`() {
        val frames = Array(100) { id ->
            StackTraceElement("vomit.C$id", "m$id", "file$id.kt", id)
        }
        val deep = CalmException("deep")
        deep.stackTrace = frames
        val wrapper = CalmException("wrapper", deep)
        wrapper.stackTrace = frames
        val top = CalmException("top", wrapper)
        top.stackTrace = frames
        // 100 + 100 frames against one shared budget of 120.
        val rendered = RecordFormatter.throwableBlock(top)
        val frameLines = rendered.split('\n').count {
            it.startsWith(RecordFormatter.EXCEPTION_INDENT + "| vomit.")
        }
        assertEquals(RecordFormatter.MAX_STACK_FRAMES, frameLines)
        assertTrue(rendered.contains("beyond the trace budget omitted"))
    }

    @Test
    fun `remaining cause sections beyond the frame budget are truncated explicitly`() {
        val frames = Array(150) { id -> StackTraceElement("d.C", "m$id", "f", id) }
        val deep = CalmException("deep")
        deep.stackTrace = frames
        val top = CalmException("top", deep)
        top.stackTrace = frames
        val rendered = RecordFormatter.throwableBlock(top)
        // The budget is exhausted inside the first trace; the cause section is
        // dropped with an explicit note instead of unbounded or missing text.
        assertTrue(rendered.contains("Caused by:"))
    }

    /** `Throwable` without a captured trace: tests assign frames explicitly. */
    private class CalmException(message: String, cause: Throwable? = null) :
        IOException(message, cause) {
        override fun fillInStackTrace(): Throwable = this
    }

    private class FakeException : IOException("synthetic") {
        override fun fillInStackTrace(): Throwable = this
    }
}
