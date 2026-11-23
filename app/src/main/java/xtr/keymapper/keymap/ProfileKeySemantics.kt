package xtr.keymapper.keymap

import xtr.keymapper.InputEventLabels

enum class ProfileKeySemantics(internal val line: String?) {
    UNKNOWN(null),
    LEGACY_CAGE("KEY_SEMANTICS 1 LEGACY_CAGE"),
    PHYSICAL_EVDEV("KEY_SEMANTICS 1 PHYSICAL_EVDEV");

    companion object {
        internal const val TAG = "KEY_SEMANTICS "

        internal fun parse(line: String?) = entries.firstOrNull { it.line == line } ?: UNKNOWN

        internal fun physicalKey(scanCode: Int): String? =
            InputEventLabels.keyLabels[scanCode]?.takeIf { scanCode > 0 && it.startsWith("KEY_") }

        internal fun isPhysicalKey(key: String): Boolean =
            key.startsWith("KEY_") && key != "KEY_RESERVED" && key in InputEventLabels.keyLabels.values
    }
}

internal enum class ProfileShortcut {
    PAUSE, EDITOR, SWITCH, BLOCK, AIM, GRAVE_AIM
}

internal class ProfileShortcuts(private val keys: Map<ProfileShortcut, String?>) {
    val lines: List<String>
        get() = ProfileShortcut.entries.map { "$TAG ${it.name} ${keys[it] ?: DISABLED}" }

    operator fun get(shortcut: ProfileShortcut): String? = keys[shortcut]

    fun applyTo(config: KeymapConfig) {
        config.usePhysicalWaylandShortcuts = true
        config.pauseResumeShortcutKey = keys[ProfileShortcut.PAUSE].orEmpty()
        config.launchEditorShortcutKey = keys[ProfileShortcut.EDITOR].orEmpty()
        config.switchProfileShortcutKey = keys[ProfileShortcut.SWITCH].orEmpty()
        config.blockKeyboardShortcutKey = keys[ProfileShortcut.BLOCK].orEmpty()
        config.mouseAimShortcutKey = keys[ProfileShortcut.AIM]
        config.keyGraveMouseAim = keys[ProfileShortcut.GRAVE_AIM] != null
        config.graveMouseAimShortcutKey = keys[ProfileShortcut.GRAVE_AIM].orEmpty()
    }

    companion object {
        const val TAG = "WAYDROID_SHORTCUT"
        private const val DISABLED = "-"

        fun parse(lines: Collection<String>): ProfileShortcuts? {
            val entries = lines.filter { it.startsWith("$TAG ") }
            if (entries.size != ProfileShortcut.entries.size) return null
            val keys = mutableMapOf<ProfileShortcut, String?>()
            for (line in entries) {
                val tokens = line.split(' ')
                if (tokens.size != 3) return null
                val shortcut = ProfileShortcut.entries.find { it.name == tokens[1] } ?: return null
                if (shortcut in keys) return null
                val key = tokens[2]
                if (key != DISABLED && !matches(shortcut, key)) return null
                keys[shortcut] = key.takeUnless { it == DISABLED }
            }
            return ProfileShortcuts(keys)
        }

        fun matches(shortcut: ProfileShortcut, key: String) =
            ProfileKeySemantics.isPhysicalKey(key) || shortcut == ProfileShortcut.AIM && key.isMouseShortcut()

        fun fromSettings(config: KeymapConfig) = ProfileShortcuts(
            mapOf(
                ProfileShortcut.PAUSE to config.pauseResumeShortcutKey,
                ProfileShortcut.EDITOR to config.launchEditorShortcutKey,
                ProfileShortcut.SWITCH to config.switchProfileShortcutKey,
                ProfileShortcut.BLOCK to config.blockKeyboardShortcutKey,
                ProfileShortcut.AIM to config.mouseAimShortcutKey?.takeIf { it.length > 4 },
                ProfileShortcut.GRAVE_AIM to "KEY_GRAVE".takeIf { config.keyGraveMouseAim }
            )
        )
    }
}

internal fun String.isMouseShortcut() = this == "KEY_MMB" || this == "KEY_RMB"
