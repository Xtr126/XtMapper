package xtr.keymapper.log

import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

        val newestKey = entries.maxOf { dateKey(it) }
        val newestDate = LocalDate.of(
            (newestKey / 10_000L).toInt(),
            ((newestKey / 100L) % 100L).toInt(),
            (newestKey % 100L).toInt(),
        )
        val protectKey = dateKeyOf(newestDate.minusDays(1))
        val deletable = entries
            .filter { dateKey(it) < protectKey && dateKey(it) < dateKeyOf(today) && it.baseName !in activeNames }
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
