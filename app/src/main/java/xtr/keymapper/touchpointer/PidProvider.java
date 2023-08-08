package xtr.keymapper.touchpointer;

import androidx.collection.SimpleArrayMap;

public final class PidProvider {
    private final SimpleArrayMap<String, Integer> pidList = new SimpleArrayMap<>();

    public Integer getPid(String keycode) {
        if (!pidList.containsKey(keycode))
            pidList.put(keycode, pidList.size());
        return pidList.get(keycode);
    }
}
