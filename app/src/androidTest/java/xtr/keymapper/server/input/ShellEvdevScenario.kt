package xtr.keymapper.server.input

import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object ShellEvdevScenario {
    const val PASSED = "Evdev shell scenario passed"

    @JvmStatic
    fun main(args: Array<String>) {
        check(Process.myUid() == 2000) { "Expected the shell UID" }
        Monitor().use { first ->
            val removed = VirtualKeyboard("XtMapper evdev first").use { keyboard ->
                first.awaitKeyPair(keyboard)
            }
            first.awaitClose(removed)

            VirtualKeyboard("XtMapper evdev reconnected").use { keyboard ->
                val reconnected = first.awaitKeyPair(keyboard)
                first.close()
                first.awaitClose(reconnected)

                Monitor().use { restarted ->
                    restarted.awaitKeyPair(keyboard)
                    restarted.close()
                    restarted.awaitClose(reconnected)
                }
            }
        }
        println(PASSED)
    }

    private class Monitor : Closeable {
        private val lines = LinkedBlockingQueue<String>()
        private val openedPaths = LinkedBlockingQueue<String>()
        private val closedPaths = LinkedBlockingQueue<String>()
        private val io = object : EvdevIo by AndroidEvdevIo {
            private val paths = mutableMapOf<FileDescriptor, String>()

            override fun open(path: String): FileDescriptor =
                AndroidEvdevIo.open(path).also {
                    paths[it] = path
                    openedPaths.add(path)
                }

            override fun close(fd: FileDescriptor) {
                AndroidEvdevIo.close(fd)
                closedPaths.add(paths.remove(fd) ?: error("Closing an untracked descriptor"))
            }
        }
        private val reader = EvdevReader({ line ->
            if (line.contains(": EV_KEY KEY_F24 ")) lines.add(line)
        }, io).also { it.start() }

        fun awaitKeyPair(keyboard: VirtualKeyboard): String {
            val path = keyboard.path
            awaitPath(openedPaths, path, "open")
            keyboard.tap()
            val down = lines.poll(2, TimeUnit.SECONDS)
            check(down == "$path: EV_KEY KEY_F24 DOWN") { "Expected key press, got $down" }
            val up = lines.poll(2, TimeUnit.SECONDS)
            check(up == "$path: EV_KEY KEY_F24 UP") { "Expected key release, got $up" }
            return path
        }

        fun awaitClose(path: String) = awaitPath(closedPaths, path, "close")

        private fun awaitPath(paths: LinkedBlockingQueue<String>, path: String, action: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                val observed = paths.poll(
                    (deadline - System.nanoTime()).coerceAtLeast(0),
                    TimeUnit.NANOSECONDS,
                ) ?: error("Reader did not $action $path")
                if (observed == path) return
            } while (System.nanoTime() < deadline)
            error("Reader did not $action $path")
        }

        override fun close() = reader.close()
    }

    private class VirtualKeyboard(name: String) : Closeable {
        private val process = ProcessBuilder("/system/bin/uinput", "-")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
        private val commands = process.outputStream.bufferedWriter()

        val path: String

        init {
            try {
                send(
                    """{"id":1,"command":"register","name":"$name","vid":6353,"pid":1,"bus":"usb","configuration":[{"type":"UI_SET_EVBIT","data":["EV_KEY"]},{"type":"UI_SET_KEYBIT","data":["KEY_F24"]}]}""",
                )
                path = awaitDevicePath(name)
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        private fun awaitDevicePath(name: String): String {
            val device = Regex(
                """(?m)^ +[0-9]+: ${Regex.escape(name)}\n +Classes: [^\n]+\n +Path: (/dev/input/event[0-9]+)$""",
            )
            val deadline = SystemClock.elapsedRealtime() + 5_000
            do {
                check(process.isAlive) { "uinput exited before registering $name" }
                val dump = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "input")
                    .redirectErrorStream(true)
                    .start()
                val output = dump.inputStream.bufferedReader().use { it.readText() }
                dump.waitFor()
                val path = device.find(output)?.groupValues?.get(1)
                if (path != null) return path
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Android did not report an evdev path for $name")
        }

        fun tap() {
            check(process.isAlive) { "uinput exited before input could be injected" }
            send(
                """{"id":1,"command":"inject","events":["EV_KEY","KEY_F24",1,"EV_SYN","SYN_REPORT",0,"EV_KEY","KEY_F24",0,"EV_SYN","SYN_REPORT",0]}""",
            )
        }

        private fun send(command: String) {
            commands.write(command)
            commands.newLine()
            commands.flush()
        }

        override fun close() {
            try {
                commands.close()
            } finally {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(2, TimeUnit.SECONDS)) { "uinput did not stop" }
                }
            }
        }
    }
}
