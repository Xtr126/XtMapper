package xtr.keymapper.log

import java.io.File

/** Interactive child used by JVM regressions and the on-device app_process probe. */
object LogStoreProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = System.`in`.bufferedReader()
        var pauseTarget = false
        var calls = 0
        val store = FileSystemLogStore(File(args[0])) { channel ->
            if (++calls == 2 && pauseTarget) {
                println("TARGET_OPEN")
                check(input.readLine() == "CONTINUE")
            }
            channel.tryLock()
        }
        var writer: LogFileWriter? = null
        println("READY")
        try {
            while (true) {
                val line = input.readLine() ?: break
                val command = line.substringBefore(' ')
                val value = line.substringAfter(' ', "")
                calls = 0
                try {
                    when (command) {
                        "PAUSE" -> { pauseTarget = true; println("OK") }
                        "OPEN" -> { writer = store.openAppendWriter(value); println("OPENED") }
                        "DELETE" -> println("DELETED ${store.delete(value)}")
                        "WRITE" -> { checkNotNull(writer).write((value + "\n").toByteArray()); println("WROTE") }
                        "TAIL" -> println("TAIL " + store.readTail(value, 10).joinToString("|"))
                        "CLOSE" -> { writer?.close(); writer = null; println("CLOSED") }
                        "EXIT" -> break
                        else -> error("Unknown command $command")
                    }
                } catch (e: Exception) {
                    println("ERROR ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } finally {
            writer?.close()
        }
    }
}
