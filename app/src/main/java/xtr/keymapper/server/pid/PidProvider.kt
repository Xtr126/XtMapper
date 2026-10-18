package xtr.keymapper.server.pid

import androidx.collection.SimpleArrayMap

class PidProvider {
    private val pidList = SimpleArrayMap<String, Int>()

    // returns pointerIds on demand
    fun getPid(keycode: String): Int {
        if (!pidList.containsKey(keycode))
            pidList.put(keycode, pidList.size())
        return pidList.get(keycode)!!
    }

    fun releasePidFor(keycode: String) {
        pidList.remove(keycode)
    }
}
