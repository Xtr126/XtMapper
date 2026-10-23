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
                "session=20261022-091532-A81C tag=Test message=\"Application initialized\"",
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

    private class FakeException : IOException("synthetic") {
        override fun fillInStackTrace(): Throwable = this
    }
}
