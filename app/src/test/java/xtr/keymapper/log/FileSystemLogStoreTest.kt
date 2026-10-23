package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSystemLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `append mode never truncates existing content across handles`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        store.openAppendWriter("xtmapper-2026-10-22.log").use { it.write("one\n".toByteArray()) }
        // A second open with a brand new handle appends after the first (this is
        // the property that makes same-day multi-session files safe).
        store.openAppendWriter("xtmapper-2026-10-22.log").use { it.write("two\n".toByteArray()) }
        val content = java.io.File(dir, "xtmapper-2026-10-22.log").readText()
        assertEquals("one\ntwo\n", content)
    }

    @Test
    fun `writtenBytes starts at the pre-existing size so rotation sees old content`() {
        val store = FileSystemLogStore(tmp.newFolder())
        val name = "xtmapper-2026-10-22.log"
        store.openAppendWriter(name).use { it.write(ByteArray(100) { 1 }) }
        store.openAppendWriter(name).use { writer -> assertEquals(100L, writer.writtenBytes) }
    }

    @Test
    fun `lists file names with sizes and sorted names`() {
        val store = FileSystemLogStore(tmp.newFolder())
        store.openAppendWriter("b.log").use { it.write("x".toByteArray()) }
        store.openAppendWriter("a.log").use { it.write("yy".toByteArray()) }
        assertEquals(listOf(LogFileInfo("a.log", 2), LogFileInfo("b.log", 1)), store.listFiles())
    }

    @Test
    fun `readTail returns trailing lines oldest first including marker lines`() {
        val store = FileSystemLogStore(tmp.newFolder())
        val name = "xtmapper-2026-10-22.log"
        store.openAppendWriter(name).use {
            it.write(("line1\nline2\n" + SessionMarkers.block("SESSION START\nSession ID: s") + "\nline3\n").toByteArray())
        }
        val tail = store.readTail(name, 10)
        // The marker block is surrounded by its `=` separator lines.
        assertEquals(
            listOf("line1", "line2", RecordFormatter.SEPARATOR_80, "SESSION START", "Session ID: s"),
            tail.take(5),
        )
        assertTrue(tail.drop(5).contains("line3"))
    }

    @Test
    fun `readTail of missing or unreadable files is empty list`() {
        val store = FileSystemLogStore(tmp.newFolder())
        assertTrue(store.readTail("nope.log", 10).isEmpty())
    }

    @Test
    fun `delete removes only the named file`() {
        val store = FileSystemLogStore(tmp.newFolder())
        val keep = "xtmapper-2026-10-22.log"
        val drop = "xtmapper-2026-10-21.log"
        store.openAppendWriter(keep).close()
        store.openAppendWriter(drop).close()
        assertTrue(store.delete(drop))
        assertFalse(store.delete("not-there.log"))
        assertEquals(listOf(keep), store.listFiles().map { it.name })
    }

    @Test
    fun `decodeTrailingLines tolerates a truncated leading line`() {
        val partial = "…xx20:15:32.510+01:00 [INFO] message=\"late\"\n".toByteArray()
        // Simulated truncated leading line (leading bytes cut off).
        val cut = partial.copyOfRange(3, partial.size)
        val lines = FileSystemLogStore.decodeTrailingLines(cut, 8)
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("message=\"late\""))
    }

    @Test
    fun `writer writes and closes without throwing on implicit flush`() {
        val store = FileSystemLogStore(tmp.newFolder())
        val writer = store.openAppendWriter("x.log")
        writer.write("chunk\n".toByteArray())
        writer.close()
        writer.close() // idempotent close is safe
        // The assertion reads back the same directory the store was built on.
        assertEquals("chunk\n", store.readTail("x.log", 8).joinToString("\n").let { it + "\n" })
    }

    @Test
    fun `utf-8 encoding stays intact end to end`() {
        val dir = tmp.newFolder()
        val store = FileSystemLogStore(dir)
        val text = " André — ключ — 🔥\n"
        store.openAppendWriter("u.log").use { it.write(text.toByteArray(Charsets.UTF_8)) }
        assertEquals(text, java.io.File(dir, "u.log").readText(Charsets.UTF_8))
    }
}
