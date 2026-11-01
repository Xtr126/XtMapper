package xtr.keymapper.log

enum class Level(val isTerminal: Boolean = false) {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL(isTerminal = true);

    fun enabled(minLevel: Level): Boolean = ordinal >= minLevel.ordinal
}
