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
 * - The newest calendar day present is always protected: any role's session may
 *   still be appending to that day's file; [activeNames] additionally protects
 *   files the caller currently holds open.
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

        val newestKey = entries.maxOf { dateKey(it) }
        val deletable = entries
            .filter { dateKey(it) < newestKey && it.baseName !in activeNames }
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
