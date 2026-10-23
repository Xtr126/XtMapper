package xtr.keymapper.log

import java.util.Locale

/**
 * Naming policy for the persistent daily log files.
 *
 * ## Canonical destination
 *
 * All log files live in the user-visible shared-storage directory
 * `XtMapper/Logs/` (`XtMapper-Debug/Logs/` in debug builds; see
 * [LogDirectories]). The directory names derive from the user-facing
 * application label `XtMapper`, never from the application id `xtr.keymapper`.
 *
 * ## Daily names
 *
 * ```
 * xtmapper[-<role>]-<yyyy-MM-dd>[-part<NNN>].log
 * ```
 *
 * - role-less: `xtmapper-2026-10-22.log` — the main application process (`app`)
 * - role-suffixed: `xtmapper-shell-2026-10-22.log` (shell app_process servers),
 *   `xtmapper-shizuku-…` (Shizuku user service), `xtmapper-root-…` (root server) —
 *   separate processes write to their own daily file so concurrent roles never
 *   append to the same active file without a coordination mechanism.
 * - rotation continues inside the same day as `-part<NNN>` (3 digits, starting at
 *   `002`), so every active and rotated file keeps `.log` as its final extension
 *   (never `.log.1`, never `.zip`).
 *
 * Every name is deterministic and parseable: [parse] recovers role, date and part
 * index; entries sort chronologically by [LogFileEntry.compareTo].
 */
object LogFileNames {

    /** Name prefix; the retention scanner only ever touches matching files. */
    const val BASE_PREFIX = "xtmapper"

    const val EXTENSION = ".log"

    /** Role tags used inside file names. */
    val ROLE_TAGS = listOf("root", "shizuku", "shell")

    /**
     * Parses a log file name: returns null for any name the logger must not touch
     * (foreign files, `foo.log.1`, temporary files, wrong prefixes, malformed
     * dates).
     */
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
        return LogFileEntry(role, year, month, day, part, name)
    }

    /** Formats the base daily file name for [role] (role tag may be null for app). */
    fun dailyName(date: String, role: String?): String =
        if (role == null) "$BASE_PREFIX-$date$EXTENSION"
        else "$BASE_PREFIX-$role-$date$EXTENSION"

    /** Formats the N-th rotation part of a daily file (`part >= 2`). */
    fun partName(date: String, role: String?, part: Int): String {
        if (part < 2) return dailyName(date, role)
        val base = dailyName(date, role).removeSuffix(EXTENSION)
        return "$base-part%03d$EXTENSION".format(Locale.ROOT, part)
    }
}

/**
 * One parsed, immutable log file entry. [part] is 1 for the base daily file.
 */
data class LogFileEntry(
    /** Role tag parsed from the name; null for the app role. */
    val role: String?,
    val year: Int,
    val month: Int,
    val day: Int,
    /** Rotation index inside the day; 1 = base daily file. */
    val part: Int,
    /** The original file name this entry was parsed from. */
    val baseName: String,
) : Comparable<LogFileEntry> {
    /** Date, then role, then part: deterministic and lexicographically chronological. */
    override fun compareTo(other: LogFileEntry): Int {
        compareValuesBy(this, other, { it.date }).let { if (it != 0) return it }
        compareValuesBy(this, other, { it.role ?: "" }).let { if (it != 0) return it }
        return compareValuesBy(this, other, { it.part })
    }

    /** The `yyyy-MM-dd` date part of the name. */
    val date: String get() = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)
}

/**
 * Canonical log directory helpers shared by all execution modes.
 *
 * Debug builds log into their own folder so a debug install never mixes its
 * files into a release install's folder.
 */
object LogDirectories {
    /** Shared-storage root the log directory lives under. */
    const val SHARED_STORAGE_ROOT = "/storage/emulated/0"
    const val LOGS_FOLDER = "Logs"

    /** Debug and non-Debug builds must not share the same visible directory. */
    fun appFolder(debug: Boolean): String =
        if (debug) "XtMapper-Debug" else "XtMapper"
}
