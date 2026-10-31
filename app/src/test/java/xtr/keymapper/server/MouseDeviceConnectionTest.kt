package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseDeviceConnectionTest {
    @Test
    fun discoveryBeforeStartupOpensOnceWhenConnected() {
        val connection = MouseDeviceConnection()
        val opened = mutableListOf<String>()
        connection.discover("/dev/input/event2")
        assertFalse(connection.isOpen)
        connection.connect { opened.add(it); true }
        connection.discover("/dev/input/event2")
        assertEquals(listOf("/dev/input/event2"), opened)
        assertTrue(connection.isOpen)
    }

    @Test
    fun startupBeforeDiscoveryWaitsForADevice() {
        val connection = MouseDeviceConnection()
        val opened = mutableListOf<String>()
        connection.connect { opened.add(it); true }
        assertEquals(emptyList<String>(), opened)
        connection.discover("/dev/input/event3")
        assertEquals(listOf("/dev/input/event3"), opened)
    }

    @Test
    fun failedOpenRetriesOnNextEvent() {
        val connection = MouseDeviceConnection()
        var attempts = 0
        connection.connect { ++attempts > 1 }
        connection.discover("/dev/input/event2")
        assertFalse(connection.isOpen)
        connection.discover("/dev/input/event2")
        assertTrue(connection.isOpen)
        assertEquals(2, attempts)
    }

    @Test
    fun restartReopensLastDeviceOnNewService() {
        val connection = MouseDeviceConnection()
        var oldCalls = 0
        val opened = mutableListOf<String>()
        connection.connect { oldCalls++; true }
        connection.discover("/dev/input/event2")
        connection.disconnect()
        connection.discover("/dev/input/event3")
        assertFalse(connection.isOpen)
        connection.connect { opened.add(it); true }
        assertEquals(1, oldCalls)
        assertEquals(listOf("/dev/input/event3"), opened)
    }

    @Test
    fun disconnectDuringOpenCannotMarkStoppedServiceAsOpen() {
        val connection = MouseDeviceConnection()
        connection.connect { connection.disconnect(); true }
        connection.discover("/dev/input/event2")
        assertFalse(connection.isOpen)
    }

    @Test
    fun oldOpenResultCannotReplaceANewServicesFailedResult() {
        val connection = MouseDeviceConnection()
        connection.connect {
            connection.connect { false }
            true
        }
        connection.discover("/dev/input/event2")
        assertFalse(connection.isOpen)
    }

    @Test
    fun coalescedDiscoveryRetriesFailedOpenButKeepsSuccessfulDeviceOpen() {
        val connection = MouseDeviceConnection()
        val pending = ArrayDeque<() -> Unit>()
        var attempts = 0
        connection.connect {
            attempts++
            attempts > 1
        }
        val coalescer = MouseDeviceCoalescer(
            schedule = { pending.addLast(it) },
            process = { connection.discover(it) }
        )
        repeat(100) { coalescer.request("/dev/input/event2") }
        assertEquals(1, pending.size)
        pending.removeFirst().invoke()
        assertEquals(1, attempts)
        assertFalse(connection.isOpen)
        coalescer.request("/dev/input/event2")
        pending.removeFirst().invoke()
        assertEquals(2, attempts)
        assertTrue(connection.isOpen)
        coalescer.request("/dev/input/event2")
        pending.removeFirst().invoke()
        assertEquals(2, attempts)
    }

    @Test
    fun delayedClientDeathUsesTheConnectionsServiceGeneration() {
        val connection = MouseDeviceConnection()
        connection.connect { true }
        val sessionAtDeath = connection.currentServiceGeneration()
        connection.disconnect()
        connection.connect { true }

        assertTrue(connection.isStaleClientDeath(sessionAtDeath))
        assertFalse(connection.isStaleClientDeath(connection.currentServiceGeneration()))
        assertFalse(connection.isStaleClientDeath(null))
    }
}
