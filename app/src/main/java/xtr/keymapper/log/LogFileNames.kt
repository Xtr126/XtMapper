package xtr.keymapper.log

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

object LogFileNames {

    const val BASE_PREFIX = "xtmapper"

    const val EXTENSION = ".log"

    val ROLE_TAGS = listOf("root", "shizuku", "shell")

    fun parse(name: String): LogFileEntry? {
        if (!name.endsWith(EXTENSION)) return null
        var core = name.removeSuffix(EXTENSION)
        if (!core.startsWith("$BASE_PREFIX-")) return null
        core = core.removePrefix("$BASE_PREFIX-") // [role-]date[-partNNN]
        var role: String? = null
        for (roleTag in ROLE_TAGS) {
            if (core.startsWith("$roleTag-")) {
                role = roleTag
                core = core.removePrefix("$roleTag-")
                break
            }
        }
        val date: String
        val part: Int
        val partIdx = core.indexOf("-part")
        if (partIdx >= 0) {
            date = core.substring(0, partIdx)
            val rest = core.substring(partIdx + 5) // NNN digits
            part = rest.toIntOrNull() ?: return null
            if (rest.length != 3 || part < 2) return null
        } else {
            date = core
            part = 1
        }
        if (date.length != 10) return null
        for ((idx, c) in date.withIndex()) {
            val expectedDash = idx == 4 || idx == 7
            if (c == '-') {
                if (!expectedDash) return null
            } else {
                if (expectedDash || !c.isDigit()) return null
            }
        }
        val year = date.substring(0, 4).toIntOrNull() ?: return null
        val month = date.substring(5, 7).toIntOrNull() ?: return null
        val day = date.substring(8, 10).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            return null
        }
        return LogFileEntry(role, year, month, day, part, name)
    }

    fun dailyName(date: String, role: String?): String =
        if (role == null) "$BASE_PREFIX-$date$EXTENSION"
        else "$BASE_PREFIX-$role-$date$EXTENSION"

    fun partName(date: String, role: String?, part: Int): String {
        if (part < 2) return dailyName(date, role)
        val base = dailyName(date, role).removeSuffix(EXTENSION)
        return "$base-part%03d$EXTENSION".format(Locale.ROOT, part)
    }
}

data class LogFileEntry(
    val role: String?,
    val year: Int,
    val month: Int,
    val day: Int,
    val part: Int,
    val baseName: String,
) : Comparable<LogFileEntry> {
    override fun compareTo(other: LogFileEntry): Int {
        compareValuesBy(this, other, { it.date }).let { if (it != 0) return it }
        compareValuesBy(this, other, { it.role ?: "" }).let { if (it != 0) return it }
        return compareValuesBy(this, other, { it.part })
    }

    val date: String get() = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)
}

object LogDirectories {
    const val SHARED_STORAGE_ROOT = "/storage/emulated/0"
    const val LOGS_FOLDER = "Logs"

    fun appFolder(debug: Boolean): String =
        if (debug) "XtMapper-Debug" else "XtMapper"
}
