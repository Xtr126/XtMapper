package xtr.keymapper.server.windows

data class TouchpadContact(
    val contactId: Int,
    var x: Int,
    var y: Int,
    val xMin: Int,
    val xMax: Int,
    val yMin: Int,
    val yMax: Int,
    val tip: Boolean
)

