package xtr.keymapper.touchpointer;

public enum PointerId {
    // pointer id 0-35 reserved for keyboard events

    pid1(36), // pointer id 36, 37 and 38 reserved for mouse events
    pid2(37),
    pid3(38),
    dpadpid1(39);

    PointerId(int i) {
        id = i;
    }

    public final int id;
}
