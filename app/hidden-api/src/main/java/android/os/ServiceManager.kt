package android.os

object ServiceManager {
    @JvmStatic
    fun getService(name: String): IBinder? {
        throw RuntimeException("STUB")
    }

    @JvmStatic
    @Throws(Exception::class)
    fun addService(name: String, service: IBinder) {
        throw RuntimeException("STUB")
    }
}
