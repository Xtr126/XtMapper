package xtr.keymapper.keymap

import xtr.keymapper.keymap.element.Camera
import xtr.keymapper.keymap.element.Dpad
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.keymap.element.SwipeKey
import xtr.keymapper.macro.MacroIdUtils

internal class ProfileKeyMigration(
    val sourceLines: Set<String>,
    private val shortcuts: ProfileShortcuts
) {
    enum class Kind {
        KEY, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, SWIPE_START, SWIPE_END,
        SHIFT_START, SHIFT_END, MACRO, CAMERA, SHORTCUT
    }

    data class Binding(val kind: Kind, val original: String, val shortcut: ProfileShortcut? = null)

    private data class Slot(val line: String, val token: Int, val binding: Binding)

    private val nonSpace = Regex("\\S+")
    private val slots = sourceLines.sorted().flatMap(::slotsInLine).filterNot {
        it.binding.kind == Kind.CAMERA && it.binding.original.isMouseShortcut()
    }
    private val shortcutBindings = ProfileShortcut.entries.mapNotNull { shortcut ->
        shortcuts[shortcut]?.takeUnless { shortcut == ProfileShortcut.AIM && it.isMouseShortcut() }
            ?.let { Binding(Kind.SHORTCUT, it, shortcut) }
    }
    val bindings: List<Binding> = slots.map(Slot::binding) + shortcutBindings
    private val replacements = mutableMapOf<Int, String>()

    fun replacement(index: Int): String? = replacements[index]

    fun bind(index: Int, key: String): Boolean {
        if (index !in bindings.indices || !ProfileKeySemantics.isPhysicalKey(key)) return false
        replacements[index] = key
        return true
    }

    fun buildCopy(): Set<String>? {
        if (replacements.size != bindings.size) return null
        val edits = slots.withIndex().groupBy { it.value.line }
        val copy = linkedSetOf<String>()
        for (line in sourceLines) {
            if (line.startsWith(ProfileKeySemantics.TAG) || line.startsWith("${ProfileShortcuts.TAG} ")) continue
            val tokens = nonSpace.findAll(line).toList()
            var rewritten = line
            for ((index, slot) in edits[line].orEmpty().sortedByDescending { it.value.token }) {
                val key = replacements.getValue(index)
                val value = if (slot.binding.kind.usesKeySuffix()) key.removePrefix("KEY_") else key
                val range = tokens.getOrNull(slot.token)?.range
                rewritten = if (range == null) "$rewritten $value" else rewritten.replaceRange(range, value)
            }
            // Two otherwise identical records may collapse after rebinding. Ask for distinct keys.
            if (!copy.add(rewritten)) return null
        }
        val keys = ProfileShortcut.entries.associateWith { shortcuts[it] }.toMutableMap()
        shortcutBindings.forEachIndexed { index, binding ->
            keys[binding.shortcut!!] = replacements.getValue(slots.size + index)
        }
        copy.add(ProfileKeySemantics.PHYSICAL_EVDEV.line!!)
        copy.addAll(ProfileShortcuts(keys).lines)
        return copy
    }

    private fun slotsInLine(line: String): List<Slot> {
        val tokens = nonSpace.findAll(line).map(MatchResult::value).toList()
        fun slot(index: Int, kind: Kind, fallback: String? = null): Slot? =
            (tokens.getOrNull(index) ?: fallback)?.let { original ->
                val code = when (kind) {
                    Kind.CAMERA -> original.replace(Regex("^(KEY_)+"), "KEY_")
                    else -> if (kind.usesKeySuffix()) "KEY_$original" else original
                }
                Slot(line, index, Binding(kind, code))
            }
        return when (tokens.firstOrNull()) {
            Dpad.TAG, Dpad.TAG_ARROW_KEYS -> listOfNotNull(
                slot(8, Kind.DPAD_UP), slot(9, Kind.DPAD_DOWN),
                slot(10, Kind.DPAD_LEFT), slot(11, Kind.DPAD_RIGHT)
            )
            SwipeKey.TAG -> listOfNotNull(slot(1, Kind.SWIPE_START), slot(4, Kind.SWIPE_END))
            ShiftKey.TAG -> listOfNotNull(slot(1, Kind.SHIFT_START), slot(4, Kind.SHIFT_END))
            MacroIdUtils.TAG -> listOfNotNull(slot(2, Kind.MACRO)?.takeUnless {
                tokens[2] == "null"
            })
            Camera.TAG -> if (tokens.size >= 6) listOfNotNull(slot(6, Kind.CAMERA, "KEY_ALT")) else emptyList()
            else -> if (tokens.size == 4 && tokens[0].startsWith("KEY_")) {
                listOfNotNull(slot(0, Kind.KEY))
            } else {
                emptyList()
            }
        }
    }

    private fun Kind.usesKeySuffix() = when (this) {
        Kind.MACRO, Kind.SWIPE_START, Kind.SWIPE_END, Kind.SHIFT_START, Kind.SHIFT_END -> true
        else -> false
    }
}
