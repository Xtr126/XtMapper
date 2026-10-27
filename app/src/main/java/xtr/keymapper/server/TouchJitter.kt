package xtr.keymapper.server

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Manages coordinate jitter / random spread for touch event dispatching.
 * Prevents identical coordinate dispatching by wrapping target X and Y with
 * a bounded random offset, maintaining stroke consistency between DOWN, MOVE, and UP.
 */
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

    /**
     * Wrap target X and Y with a random spread for touch dispatching.
     * Ensures DOWN, MOVE, and UP events for the same stroke share consistent offset
     * to prevent spurious drag/scroll detection.
     *
     * @param x target X coordinate
     * @param y target Y coordinate
     * @param action InputService action (UP=0, DOWN=1, MOVE=2)
     * @param pointerId pointer identifier
     * @return Point containing bounded, jittered coordinates
     */
    fun wrapCoordinates(x: Float, y: Float, action: Int, pointerId: Int): Point {
        if (!isEnabled || spread <= 0f) {
            return Point(x, y)
        }

        val offset: Point = when (action) {
            InputService.DOWN -> {
                // New touch stroke: generate new random error/spread
                val newOffset = generateRandomSpread(spread)
                activeOffsets[pointerId] = newOffset
                newOffset
            }
            InputService.UP -> {
                // Touch release: reuse active stroke offset if present, then clean up
                activeOffsets.remove(pointerId) ?: generateRandomSpread(spread)
            }
            else -> {
                // MOVE or other ongoing action: preserve existing offset
                activeOffsets.getOrPut(pointerId) { generateRandomSpread(spread) }
            }
        }

        var jitteredX = x + offset.x
        var jitteredY = y + offset.y

        // Wrap / clamp within screen bounds if screen dimensions are known
        if (screenWidth > 0) {
            jitteredX = jitteredX.coerceIn(0f, screenWidth.toFloat())
        }
        if (screenHeight > 0) {
            jitteredY = jitteredY.coerceIn(0f, screenHeight.toFloat())
        }

        return Point(jitteredX, jitteredY)
    }

    /**
     * Generate uniform random spread within a circular disk of radius maxSpread.
     *
     * @param maxSpread maximum offset radius in pixels
     * @return Point random offset (dx, dy)
     */
    fun generateRandomSpread(maxSpread: Float): Point {
        val angle = Random.nextDouble() * 2.0 * Math.PI
        // Square root of uniform random gives uniform areal distribution on a disk
        val r = Math.sqrt(Random.nextDouble()) * maxSpread
        val dx = (r * Math.cos(angle)).toFloat()
        val dy = (r * Math.sin(angle)).toFloat()
        return Point(dx, dy)
    }
}
