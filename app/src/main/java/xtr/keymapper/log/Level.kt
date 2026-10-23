package xtr.keymapper.log

/**
 * Severity levels for XtLogger. Ordered from least to most severe; the declaration
 * order doubles as the comparison used for minimum-level filtering.
 *
 * This enum is part of the JVM-testable core: it must not import Android classes.
 */
enum class Level(val isTerminal: Boolean = false) {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    /**
     * A fatal failure that owns the process's uncaught-exception path. [isTerminal]
     * records that the enqueueing thread will not continue after this record; the
     * facade performs a bounded synchronous flush after enqueueing a FATAL record.
     */
    FATAL(isTerminal = true);

    /** True when this record should be written for the given minimum level. */
    fun enabled(minLevel: Level): Boolean = ordinal >= minLevel.ordinal
}
