package xtr.keymapper.server.input

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor

internal interface EvdevIo {
    fun paths(): Set<String>
    fun open(path: String): FileDescriptor
    fun poll(fds: List<FileDescriptor>, timeout: Int): IntArray
    fun read(fd: FileDescriptor, bytes: ByteArray): Int
    fun close(fd: FileDescriptor)
}

internal object AndroidEvdevIo : EvdevIo {
    override fun paths() = File("/dev/input").listFiles()
        ?.filter { it.name.matches(Regex("event[0-9]+")) }
        ?.map { it.absolutePath }?.toSet().orEmpty()
    override fun open(path: String): FileDescriptor =
        Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
    override fun poll(fds: List<FileDescriptor>, timeout: Int): IntArray {
        val polls = fds.map { descriptor -> StructPollfd().apply {
            fd = descriptor
            events = OsConstants.POLLIN.toShort()
        } }.toTypedArray()
        Os.poll(polls, timeout)
        return polls.map { it.revents.toInt() }.toIntArray()
    }
    override fun read(fd: FileDescriptor, bytes: ByteArray) = Os.read(fd, bytes, 0, bytes.size)
    override fun close(fd: FileDescriptor) = Os.close(fd)
}

/**
 * Privileged, non-grabbing event monitor. One worker owns every descriptor.
 * Nonblocking reads + bounded poll allow shutdown without closing a descriptor
 * from another thread. Periodic rescans handle device addition/removal and retry
 * inaccessible devices. Native mouse/grab readers retain their existing roles.
 */
internal class EvdevReader(
    private val onLine: (String) -> Unit,
    private val io: EvdevIo = AndroidEvdevIo,
    private val is64Bit: Boolean = Process.is64Bit(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : Closeable {
    private class Device(val fd: FileDescriptor, is64Bit: Boolean) {
        val decoder = InputEventDecoder(is64Bit)
    }

    @Volatile private var running = true
    private val worker = Thread({ run() }, "xtmapper-evdev")

    /**
     * Starts asynchronous device monitoring, delivering labeled lines to [onLine] on the worker.
     *
     * @throws IllegalThreadStateException if the worker has already been started.
     */
    fun start() = worker.start()

    /**
     * Requests shutdown without waiting for the worker to finish or closing descriptors here.
     * The worker closes its devices when it exits; repeated calls are allowed.
     */
    override fun close() {
        running = false
        worker.interrupt()
        // Descriptor cleanup completes on the owner thread within the poll timeout.
    }

    /**
     * Monitors devices until shutdown, retrying inaccessible nodes on later scans.
     * Open failures and transient read/poll interruptions are handled locally; other Exceptions
     * reaching the outer handler stop monitoring. Owned devices are closed on exit.
     */
    private fun run() {
        val devices = linkedMapOf<String, Device>()
        val failed = mutableSetOf<String>()
        val bytes = ByteArray(24 * 64)
        var nextScan = Long.MIN_VALUE
        var emptyDirectory: Boolean? = null
        try {
            while (running) {
                val now = clock()
                if (now >= nextScan) {
                    val paths = io.paths()
                    if (paths.isEmpty() && emptyDirectory != true) {
                        Log.w(TAG, "no input event nodes found; check /dev/input access")
                    }
                    emptyDirectory = paths.isEmpty()
                    for (path in devices.keys.toList()) if (path !in paths) {
                        closeDevice(devices.remove(path)!!)
                        Log.i(TAG, "removed device=$path")
                    }
                    failed.retainAll(paths)
                    for (path in paths) if (path !in devices) {
                        try {
                            val fd = io.open(path)
                            devices[path] = Device(fd, is64Bit)
                            failed.remove(path)
                            Log.i(TAG, "opened device=$path")
                        } catch (e: ErrnoException) {
                            if (failed.add(path)) Log.w(TAG, "cannot open device=$path errno=${e.errno}")
                        }
                    }
                    nextScan = now + 1000
                }
                if (devices.isEmpty()) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { }
                    continue
                }
                val entries = devices.entries.toList()
                val flagsByDevice = try {
                    io.poll(entries.map { it.value.fd }, 250)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                for (i in flagsByDevice.indices) {
                    if (!running) break
                    val (path, device) = entries[i]
                    val flags = flagsByDevice[i]
                    var dead = flags and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0
                    if (!dead && flags and OsConstants.POLLIN != 0) {
                        try {
                            // One bounded batch per device keeps busy mice from starving keyboards.
                            val count = io.read(device.fd, bytes)
                            if (count == 0) dead = true
                            else device.decoder.accept(bytes, count) { type, code, value ->
                                if (running) onLine(InputEventFormatter.line(path, type, code, value))
                            }
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) {
                                Log.w(TAG, "read failed device=$path errno=${e.errno}")
                                dead = true
                            }
                        }
                    }
                    if (dead) {
                        devices.remove(path)
                        closeDevice(device)
                        Log.i(TAG, "disconnected device=$path")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "event monitor stopped", e)
        } finally {
            devices.values.forEach(::closeDevice)
            Log.i(TAG, "event monitor closed")
        }
    }

    private fun closeDevice(device: Device) {
        try { io.close(device.fd) } catch (e: ErrnoException) {
            Log.w(TAG, "close failed errno=${e.errno}")
        }
    }

    companion object { private const val TAG = "xtmapper-evdev" }
}
