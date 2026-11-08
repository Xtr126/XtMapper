package xtr.keymapper.server

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class TouchJitter(
    @Volatile var isEnabled: Boolean = false,
    @Volatile var spread: Float = 15f
) {
    data class Point(val x: Float, val y: Float)

    @Volatile var screenWidth: Int = 0
    @Volatile var screenHeight: Int = 0

    private val activeOffsets = ConcurrentHashMap<Int, Point>()

    fun setScreenDimensions(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
    }

    fun updateConfig(enabled: Boolean, spread: Float) {
        this.isEnabled = enabled
        this.spread = spread
    }

    fun clear() {
        activeOffsets.clear()
    }

    fun wrapCoordinates(x: Float, y: Float, action: Int, pointerId: Int): Point {
        if (!isEnabled || spread <= 0f) {
            return Point(x, y)
        }

        val offset: Point = when (action) {
            InputService.DOWN -> {
                val newOffset = generateRandomSpread(spread)
                activeOffsets[pointerId] = newOffset
                newOffset
            }
            InputService.UP -> {
                activeOffsets.remove(pointerId) ?: generateRandomSpread(spread)
            }
            else -> {
                activeOffsets.getOrPut(pointerId) { generateRandomSpread(spread) }
            }
        }

        var jitteredX = x + offset.x
        var jitteredY = y + offset.y

        if (screenWidth > 0) {
            jitteredX = jitteredX.coerceIn(0f, (screenWidth - 1).toFloat())
        }
        if (screenHeight > 0) {
            jitteredY = jitteredY.coerceIn(0f, (screenHeight - 1).toFloat())
        }

        return Point(jitteredX, jitteredY)
    }

    fun generateRandomSpread(maxSpread: Float): Point {
        val angle = Random.nextDouble() * 2.0 * Math.PI
        // Square root of uniform random gives uniform areal distribution on a disk
        val r = Math.sqrt(Random.nextDouble()) * maxSpread
        val dx = (r * Math.cos(angle)).toFloat()
        val dy = (r * Math.sin(angle)).toFloat()
        return Point(dx, dy)
    }
}
