package xtr.keymapper.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputServiceKeyboardRoutingTest {
    @Test
    fun routesMappedEventsOnlyWhileUnpausedAndBlocked() {
        assertTrue(shouldHandleMappedKeyboardEvent(stopEvents = false, isKeyboardBlocked = true))
        assertFalse(shouldHandleMappedKeyboardEvent(stopEvents = false, isKeyboardBlocked = false))
        assertFalse(shouldHandleMappedKeyboardEvent(stopEvents = true, isKeyboardBlocked = true))
        assertFalse(shouldHandleMappedKeyboardEvent(stopEvents = true, isKeyboardBlocked = false))
    }
}
