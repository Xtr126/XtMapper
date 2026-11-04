package xtr.keymapper.log

import java.io.File
import java.io.RandomAccessFile

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
        var armHook = false
        var probeSeq = 0
        val coordinator = args.getOrNull(1)?.takeIf { it == "coordinator-hook" }?.let {
            CoordinatorStore(File(args[0])) {
                if (armHook) {
                    armHook = false
                    println("HOOK_REACHED")
                    while (true) {
                        val pauseLine = input.readLine() ?: break
                        when (pauseLine.substringBefore(' ')) {
                            "CONTINUE" -> break
                            "READCLOSE" -> {
                                RandomAccessFile(File(args[0], pauseLine.substringAfter(' ')), "r").use { }
                                println("READCLOSED")
                            }
                        }
                    }
                }
            }
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
                        "ARM" -> { armHook = true; println("ARMED") }
                        "TRYAPPEND" -> {
                            probeSeq++
                            checkNotNull(coordinator).append(
                                value, listOf(FrameCodec.encode(probeSeq.toLong(), "probe-${ProcessHandle.current().pid()}", "probe")),
                            )
                            println("WROTE")
                        }
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
