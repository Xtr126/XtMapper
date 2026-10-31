package xtr.keymapper.log

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Retention decision logic. Pure and unit-tested without touching real storage:
 * the caller supplies parsed entries with their sizes, applies the returned
 * deletion set through its own store, and nothing here can escape the canonical
 * log directory (only `LogFileNames.parse`-matched names can ever appear here).
 *
 * Policy:
 * - Deletion candidates are selected here; the store's `delete` then applies a
 *   fail-closed advisory-lock gate, so a candidate is removed only when no
 *   process (any role, idle writers included) still holds the file open. That
 *   lock gate is the primary active-file guarantee.
 * - What remains here is the temporal filter: the newest calendar day and the
 *   day before it are always kept regardless of locks. This grace day is a
 *   recent-history policy, not proof that any file is inactive, and its budget overshoot
 *   (holding up to an extra day of files) is accepted by design.
 * - Files older than [LoggingConfig.maxAgeDays] days are dropped first.
 * - Then the oldest survivors are dropped until the kept-file count and kept
 *   total size fit [LoggingConfig.maxFiles] / [LoggingConfig.maxTotalBytes].
 *   At least one file is always retained.
 */
object Retention {

    fun candidatesForDeletion(
        entries: List<LogFileEntry>,
        sizeBytesOf: (LogFileEntry) -> Long,
        config: LoggingConfig,
        today: LocalDate,
        activeNames: Set<String> = emptySet(),
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        fun dateKey(e: LogFileEntry): Long = e.year * 10_000L + e.month * 100L + e.day
        fun dateKeyOf(date: LocalDate): Long =
            date.year * 10_000L + date.monthValue * 100L + date.dayOfMonth

        // One full calendar day of grace: the newest day's protection already
        // covers same-day writers, but a cross-midnight session of ANY role can
        // still be appending to (or just have rotated within) its previous-day
        // file, and filenames cannot reveal which process still holds a file
        // open. Fingerprints of open files are not available, so the grace day
        // is treated as active too.
        val newestKey = entries.maxOf { dateKey(it) }
        val newestDate = LocalDate.of(
            (newestKey / 10_000L).toInt(),
            ((newestKey / 100L) % 100L).toInt(),
            (newestKey % 100L).toInt(),
        )
        val protectKey = dateKeyOf(newestDate.minusDays(1))
        val deletable = entries
            .filter { dateKey(it) < protectKey && it.baseName !in activeNames }
            .sorted() // ascending: oldest first
        val deletions = LinkedHashSet<String>()

        for (e in deletable) {
            val age = ChronoUnit.DAYS.between(
                LocalDate.of(e.year, e.month, e.day), today,
            )
            if (age > config.maxAgeDays) deletions.add(e.baseName)
        }

        var kept = entries.filter { it.baseName !in deletions }
        var keptBytes = kept.sumOf { sizeBytesOf(it) }
        for (e in deletable) {
            if (e.baseName in deletions) continue
            if (kept.size <= 1) break
            if (kept.size <= config.maxFiles && keptBytes <= config.maxTotalBytes) break
            deletions.add(e.baseName)
            kept = kept.filter { it.baseName != e.baseName }
            keptBytes -= sizeBytesOf(e)
        }
        return deletions.toList()
    }
}
