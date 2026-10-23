package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFileNamesTest {

    @Test
    fun `parses the app daily name`() {
        val entry = LogFileNames.parse("xtmapper-2026-10-22.log")!!
        assertNull(entry.role)
        assertEquals(2026, entry.year)
        assertEquals(10, entry.month)
        assertEquals(22, entry.day)
        assertEquals(1, entry.part)
        assertEquals("2026-10-22", entry.date)
    }

    @Test
    fun `parses role daily names including rotated parts`() {
        assertEquals("root", LogFileNames.parse("xtmapper-root-2026-10-22.log")!!.role)
        assertEquals("shell", LogFileNames.parse("xtmapper-shell-2026-10-22.log")!!.role)
        assertEquals("shizuku", LogFileNames.parse("xtmapper-shizuku-2026-10-22.log")!!.role)

        val part = LogFileNames.parse("xtmapper-shell-2026-10-22-part002.log")!!
        assertEquals("shell", part.role)
        assertEquals(2, part.part)
        assertEquals(42, LogFileNames.parse("xtmapper-2026-10-22-part042.log")!!.part)
    }

    @Test
    fun `foreign and malformed names are rejected`() {
        // Never touches anything that is not logger-owned.
        assertNull(LogFileNames.parse("user-photo.jpg"))
        assertNull(LogFileNames.parse("xtmapper-2026-10-22.log.1"))
        assertNull(LogFileNames.parse("xtmapper-2026-10-22.log.gz"))
        assertNull(LogFileNames.parse("xtmapper.zip"))
        assertNull(LogFileNames.parse("XTMAPPER-2026-10-22.log"))
        // Wrong role tag / date shapes.
        assertNull(LogFileNames.parse("xtmapper-other-2026-10-22.log"))
        assertNull(LogFileNames.parse("xtmapper-2026-13-01.log"))
        assertNull(LogFileNames.parse("xtmapper-2026-10-32.log"))
        assertNull(LogFileNames.parse("xtmapper-20261022.log"))
        assertNull(LogFileNames.parse("xtmapper-2026-10-22-part001.log")) // part001 is reserved for the base name
        assertNull(LogFileNames.parse("xtmapper-2026-10-22-part01.log"))
        assertNull(LogFileNames.parse("xtmapper-2026-10-22-partx.log"))
        assertNull(LogFileNames.parse("xtrmapper-2026-10-22.log"))
        assertNull(LogFileNames.parse(".xtmapper-2026-10-22.log"))
    }

    @Test
    fun `format and parse roundtrip for base and parts`() {
        assertEquals("xtmapper-2026-10-22.log", LogFileNames.dailyName("2026-10-22", null))
        assertEquals("xtmapper-root-2026-10-22.log", LogFileNames.dailyName("2026-10-22", "root"))
        assertEquals("xtmapper-2026-10-22.log", LogFileNames.partName("2026-10-22", null, 1))
        assertEquals("xtmapper-shell-2026-10-22-part002.log", LogFileNames.partName("2026-10-22", "shell", 2))
        assertEquals("xtmapper-2026-10-22-part003.log", LogFileNames.partName("2026-10-22", null, 3))
        val parsed = LogFileNames.parse(LogFileNames.partName("2026-10-22", "shell", 12))!!
        assertEquals(12, parsed.part)
    }

    @Test
    fun `entries sort chronologically and never cross into other days`() {
        val sorted = listOf(
            LogFileNames.parse("xtmapper-2026-10-23-part002.log")!!,
            LogFileNames.parse("xtmapper-2026-10-22.log")!!,
            LogFileNames.parse("xtmapper-root-2026-10-23.log")!!,
            LogFileNames.parse("xtmapper-2026-10-23.log")!!,
            LogFileNames.parse("xtmapper-shell-2026-10-22.log")!!,
        ).sorted()
        // Documented order: date, then role (app first), then part index.
        assertEquals(
            listOf(
                "xtmapper-2026-10-22.log",
                "xtmapper-shell-2026-10-22.log",
                "xtmapper-2026-10-23.log",
                "xtmapper-2026-10-23-part002.log",
                "xtmapper-root-2026-10-23.log",
            ),
            sorted.map { it.baseName },
        )
    }

    @Test
    fun `canonical directory names contain no package id`() {
        assertFalse(LogDirectories.appFolder(false).contains("xtr"))
        assertFalse(LogDirectories.LOGS_FOLDER.contains("keymapper"))
        assertEquals("XtMapper", LogDirectories.appFolder(false))
        assertEquals("XtMapper-Debug", LogDirectories.appFolder(true))
        assertEquals("Logs", LogDirectories.LOGS_FOLDER)
        assertTrue("user-facing name", LogFileNames.BASE_PREFIX == "xtmapper")
    }

    @Test
    fun `custom log paths must stay under shared storage`() {
        assertNull(LogDirectories.sanitizeCustomPath("/sdcard/XtMapper"))
        assertNull(LogDirectories.sanitizeCustomPath("/storage/emulated/0/../data"))
        assertNull(LogDirectories.sanitizeCustomPath("/storage/emulated/0"))
        assertEquals(
            "/storage/emulated/0/MyLogs",
            LogDirectories.sanitizeCustomPath(" /storage/emulated/0/MyLogs/ "),
        )
    }
}
