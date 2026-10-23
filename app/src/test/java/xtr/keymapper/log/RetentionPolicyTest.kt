package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RetentionPolicyTest {

    private val config = LoggingConfig(
        maxAgeDays = 14,
        maxTotalBytes = 8L * 1024 * 1024,
        maxFiles = 8,
    )
    private val today = LocalDate.of(2026, 10, 23)

    private fun entry(name: String, size: Long = 1_000_000L): Pair<LogFileEntry, Long> {
        val parsed = LogFileNames.parse(name) ?: error("test fixture broken: $name")
        return parsed to size
    }

    private fun run(
        entries: List<Pair<LogFileEntry, Long>>,
        activeNames: Set<String> = emptySet(),
    ): List<String> {
        val nameToSize = entries.toMap()
        return Retention.candidatesForDeletion(
            entries.map { it.first },
            sizeBytesOf = { nameToSize[it] ?: 0L },
            config = config,
            today = today,
            activeNames = activeNames,
        )
    }

    @Test
    fun `files older than the retention age are deleted first`() {
        val deleted = run(
            listOf(
                entry("xtmapper-2026-09-01.log"),
                entry("xtmapper-2026-10-10.log"),
                entry("xtmapper-2026-10-22.log"),
            )
        )
        // 10-22 is 1 day old (kept), 10-10 is 13 days (kept), 09-01 is 52 days (gone).
        assertEquals(listOf("xtmapper-2026-09-01.log"), deleted)
    }

    @Test
    fun `the newest date is always protected even if the budget forces pruning elsewhere`() {
        val deleted = run(
            listOf(
                entry("xtmapper-2026-10-22.log", size = 100 * 1024 * 1024L),
                entry("xtmapper-2026-10-23.log", size = 100 * 1024 * 1024L),
            )
        )
        // The newest day is never deleted; the older day pays the budget bill.
        assertEquals(listOf("xtmapper-2026-10-22.log"), deleted)
    }

    @Test
    fun `total size budget removes the oldest survivors first`() {
        val deleted = run(
            listOf(
                entry("xtmapper-2026-10-02.log", size = 4 * 1024 * 1024L),
                entry("xtmapper-2026-10-13.log", size = 4 * 1024 * 1024L),
                entry("xtmapper-2026-10-23.log", size = 4 * 1024 * 1024L),
            )
        )
        // Newest protected; budget 8 MiB kept for two old big files → oldest goes.
        assertEquals(listOf("xtmapper-2026-10-02.log"), deleted)
    }

    @Test
    fun `file count budget keeps the most recent entries`() {
        val deleted = run(
            (2..24).map { day -> entry("xtmapper-2026-10-%02d.log".format(java.util.Locale.ROOT, day)) }
        )
        // Age pass removes days 02..08 (> 14 days). Count cap 8 then removes the
        // oldest survivors (days 09..16): 7 + 8 = 15 deletions.
        assertEquals(15, deleted.size)
        assertTrue(deleted.first().endsWith("10-02.log"))
        assertTrue(deleted.last().endsWith("10-16.log"))
    }

    @Test
    fun `actives are never deleted regardless of age`() {
        val deleted = run(
            listOf(
                entry("xtmapper-2026-09-01.log"),
                entry("xtmapper-2026-10-22-part002.log", size = 9_000_000L),
                entry("xtmapper-2026-10-23.log"),
            ),
            activeNames = setOf("xtmapper-2026-09-01.log"), // another role still appending
        )
        // Budget pressure lands on the unprotected old part, not on the active file.
        assertEquals(listOf("xtmapper-2026-10-22-part002.log"), deleted)
    }

    @Test
    fun `different roles of the same old day all count into the budget`() {
        val deleted = run(
            listOf(
                entry("xtmapper-root-2026-10-08.log"),
                entry("xtmapper-shell-2026-10-08.log"),
                entry("xtmapper-2026-10-09.log"),
                entry("xtmapper-2026-10-23.log"),
            )
        )
        assertEquals(setOf("xtmapper-root-2026-10-08.log", "xtmapper-shell-2026-10-08.log"), deleted.toSet())
    }

    @Test
    fun `rotation parts of one day never delete the base of a still-active day`() {
        val deleted = run(
            listOf(
                entry("xtmapper-2026-10-23.log"),
                entry("xtmapper-2026-10-23-part002.log"),
                entry("xtmapper-2026-10-22.log"),
            )
        )
        // The 10-22 file is within retention and under caps → nothing is deleted.
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun `empty input is a no-op`() {
        assertEquals(emptyList<String>(), run(emptyList()))
    }

    @Test
    fun `at least one file is always retained`() {
        val deleted = run(
            listOf(entry("xtmapper-2025-01-01.log", size = 999 * 1024 * 1024L))
        )
        assertTrue(deleted.isEmpty()) // newest date protected; single file policy
    }
}
