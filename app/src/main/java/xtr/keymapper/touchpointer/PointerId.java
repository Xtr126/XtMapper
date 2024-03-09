package xtr.keymapper.touchpointer;

public enum PointerId {
    // pointer id 0-35 reserved for keyboard events

    pid1(36), // pointer id 36 and 37 reserved for mouse events
    pid2(37),
    dpad1pid(38),
    dpad2pid(39),
    pid3(40);

    PointerId(int i) {
        id = i;
    }

    public final int id;
}
