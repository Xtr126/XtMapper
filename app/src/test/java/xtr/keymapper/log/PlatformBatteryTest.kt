package xtr.keymapper.log

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformBatteryTest {
    private class SupplyProc : ProcSource {
        val files = mutableMapOf<String, String?>()
        val names = mutableListOf<String>()
        var discoveries = 0
        override fun read(path: String): String? = files[path]
        override fun list(path: String): List<String> {
            discoveries++
            return names
        }
        fun supply(name: String, type: String, online: String?) {
            names += name
            files["/sys/class/power_supply/$name/type"] = type
            files["/sys/class/power_supply/$name/online"] = online
        }
    }

    private val context = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    private fun source(battery: PlatformBattery): String? =
        PlatformBattery::class.java.getDeclaredMethod("readSource").apply { isAccessible = true }
            .invoke(battery) as String?

    @Test
    fun `charger discovery excludes the battery and rereads online state without rediscovery`() {
        val proc = SupplyProc()
        proc.supply("battery", "Battery", "1")
        proc.supply("ac", "Mains", "0")
        proc.supply("usb", "USB", "1")
        proc.supply("wireless", "Wireless", "0")
        val battery = PlatformBattery(context, proc)
        assertEquals("USB", source(battery))
        proc.files["/sys/class/power_supply/ac/online"] = "1"
        assertEquals("AC", source(battery))
        proc.files["/sys/class/power_supply/ac/online"] = "0"
        proc.files["/sys/class/power_supply/usb/online"] = "0"
        proc.files["/sys/class/power_supply/wireless/online"] = "1"
        assertEquals("WIRELESS", source(battery))
        proc.files["/sys/class/power_supply/wireless/online"] = "0"
        assertEquals("UNPLUGGED", source(battery))
        assertEquals(1, proc.discoveries)
    }

    @Test
    fun `unknown charger online values never imply unplugged`() {
        val proc = SupplyProc()
        proc.supply("ac", "Mains", "0")
        proc.supply("usb", "USB", null)
        val battery = PlatformBattery(context, proc)
        for (value in listOf(null, "", "2", "-1", "not a number")) {
            proc.files["/sys/class/power_supply/usb/online"] = value
            assertNull("online=$value is not evidence of unplugged", source(battery))
        }
        proc.files["/sys/class/power_supply/ac/online"] = "1"
        assertEquals("AC", source(battery))
    }

    @Test
    fun `battery alone and undiscoverable supplies leave charging source unavailable`() {
        val proc = SupplyProc()
        assertNull(source(PlatformBattery(context, proc)))
        proc.supply("battery", "battery", "1")
        assertNull(source(PlatformBattery(context, proc)))
    }

    @Test
    fun `platform battery temperature validates decicelsius boundaries`() {
        val proc = SupplyProc()
        val battery = PlatformBattery(context, proc)
        val read = PlatformBattery::class.java.getDeclaredMethod("readBatteryTemp").apply { isAccessible = true }
        for (value in listOf(-100, 0, 600)) {
            proc.files["/sys/class/power_supply/battery/temp"] = value.toString()
            assertEquals(value, read.invoke(battery))
        }
        for (value in listOf(null, "-101", "601", "malformed")) {
            proc.files["/sys/class/power_supply/battery/temp"] = value
            assertNull(read.invoke(battery))
        }
    }
}
