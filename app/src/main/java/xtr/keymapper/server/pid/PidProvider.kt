package xtr.keymapper.server.pid

class PidProvider {
    private val pidList = HashMap<String, Int>()
    // shortcut: ids grow without bound per process lifetime (int, ~2B triggers); switch to a free-list only if that ever matters
    private var nextId = 0

    // returns pointerIds on demand
    @Synchronized
    fun getPid(keycode: String): Int = pidList.getOrPut(keycode) { nextId++ }

    @Synchronized
    fun releasePidFor(keycode: String) {
        pidList.remove(keycode)
    }
}
