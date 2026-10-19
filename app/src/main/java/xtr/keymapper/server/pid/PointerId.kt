package xtr.keymapper.server.pid

enum class PointerId(i: Int) {
    // pointer id 0-35 reserved for keyboard events

    pid1(36), // pointer id 36, 37 and 38 reserved for mouse events
    pid2(37),
    pid3(38),
    dpadpid1(39);

    @JvmField
    val id: Int = i
}
