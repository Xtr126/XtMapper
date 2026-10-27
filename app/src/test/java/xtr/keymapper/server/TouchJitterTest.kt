package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

class TouchJitterTest {

    private lateinit var touchJitter: TouchJitter

    @Before
    fun setUp() {
        touchJitter = TouchJitter()
    }

    @Test
    fun testDisabledReturnsExactCoordinates() {
        touchJitter.isEnabled = false
        touchJitter.spread = 20f

        val pt = touchJitter.wrapCoordinates(500f, 600f, InputService.DOWN, 1)
        assertEquals(500f, pt.x, 0.001f)
        assertEquals(600f, pt.y, 0.001f)
    }

    @Test
    fun testZeroSpreadReturnsExactCoordinates() {
        touchJitter.isEnabled = true
        touchJitter.spread = 0f

        val pt = touchJitter.wrapCoordinates(500f, 600f, InputService.DOWN, 1)
        assertEquals(500f, pt.x, 0.001f)
        assertEquals(600f, pt.y, 0.001f)
    }

    @Test
    fun testEnabledAppliesJitterWithinSpreadRadius() {
        val spread = 15f
        touchJitter.isEnabled = true
        touchJitter.spread = spread

        val originX = 500f
        val originY = 500f

        for (i in 0 until 100) {
            touchJitter.clear()
            val pt = touchJitter.wrapCoordinates(originX, originY, InputService.DOWN, i)
            val dist = hypot((pt.x - originX).toDouble(), (pt.y - originY).toDouble())
            assertTrue("Distance $dist should be <= spread $spread", dist <= spread + 0.001)
        }
    }

    @Test
    fun testStrokeConsistencyDownMoveUp() {
        touchJitter.isEnabled = true
        touchJitter.spread = 25f

        val pointerId = 3
        val targetX = 400f
        val targetY = 300f

        // DOWN
        val downPt = touchJitter.wrapCoordinates(targetX, targetY, InputService.DOWN, pointerId)

        // MOVE 1
        val movePt1 = touchJitter.wrapCoordinates(targetX, targetY, InputService.MOVE, pointerId)
        assertEquals(downPt.x, movePt1.x, 0.0001f)
        assertEquals(downPt.y, movePt1.y, 0.0001f)

        // MOVE 2 with different target (e.g. dragging)
        val movedTargetX = 450f
        val movedTargetY = 350f
        val movePt2 = touchJitter.wrapCoordinates(movedTargetX, movedTargetY, InputService.MOVE, pointerId)
        val downOffsetX = downPt.x - targetX
        val downOffsetY = downPt.y - targetY
        assertEquals(movedTargetX + downOffsetX, movePt2.x, 0.0001f)
        assertEquals(movedTargetY + downOffsetY, movePt2.y, 0.0001f)

        // UP
        val upPt = touchJitter.wrapCoordinates(targetX, targetY, InputService.UP, pointerId)
        assertEquals(downPt.x, upPt.x, 0.0001f)
        assertEquals(downPt.y, upPt.y, 0.0001f)
    }

    @Test
    fun testConsecutiveTouchesReceiveDifferentOffsets() {
        touchJitter.isEnabled = true
        touchJitter.spread = 20f

        val pointerId = 0
        val targetX = 200f
        val targetY = 200f

        val coordinates = HashSet<String>()
        for (i in 0 until 20) {
            val down = touchJitter.wrapCoordinates(targetX, targetY, InputService.DOWN, pointerId)
            touchJitter.wrapCoordinates(targetX, targetY, InputService.UP, pointerId)
            coordinates.add("${down.x},${down.y}")
        }

        assertTrue("Expected distinct coordinates across touches", coordinates.size > 15)
    }

    @Test
    fun testIndependentPointerIds() {
        touchJitter.isEnabled = true
        touchJitter.spread = 20f

        val targetX = 100f
        val targetY = 100f

        val p0 = touchJitter.wrapCoordinates(targetX, targetY, InputService.DOWN, 0)
        val p1 = touchJitter.wrapCoordinates(targetX, targetY, InputService.DOWN, 1)

        assertNotEquals(p0.x, p1.x, 0.00001f)

        touchJitter.wrapCoordinates(targetX, targetY, InputService.UP, 0)
        val p1Move = touchJitter.wrapCoordinates(targetX, targetY, InputService.MOVE, 1)
        assertEquals(p1.x, p1Move.x, 0.0001f)
        assertEquals(p1.y, p1Move.y, 0.0001f)
    }

    @Test
    fun testScreenBoundaryClamping() {
        touchJitter.isEnabled = true
        touchJitter.spread = 30f
        touchJitter.setScreenDimensions(1920, 1080)

        // Near (0, 0)
        for (i in 0 until 50) {
            val pt = touchJitter.wrapCoordinates(0f, 0f, InputService.DOWN, i)
            assertTrue("X should not be negative: ${pt.x}", pt.x >= 0f)
            assertTrue("Y should not be negative: ${pt.y}", pt.y >= 0f)
            touchJitter.wrapCoordinates(0f, 0f, InputService.UP, i)
        }

        // Near (1920, 1080)
        for (i in 0 until 50) {
            val pt = touchJitter.wrapCoordinates(1920f, 1080f, InputService.DOWN, i)
            assertTrue("X should not exceed 1920: ${pt.x}", pt.x <= 1920f)
            assertTrue("Y should not exceed 1080: ${pt.y}", pt.y <= 1080f)
            touchJitter.wrapCoordinates(1920f, 1080f, InputService.UP, i)
        }
    }
}
