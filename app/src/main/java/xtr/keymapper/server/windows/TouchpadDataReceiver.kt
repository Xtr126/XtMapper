package xtr.keymapper.server.windows

import android.os.Process
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

class TouchpadDataReceiver {
    var verbose: Boolean
        get() = injector.verbose
        set(value) { injector.verbose = value }

    val injector = TouchpadInputInjector()
    val inputProcessor = TouchpadInputProcessor()

    fun startSystemIn() {
        var prevContacts: List<TouchpadContact> = emptyList()
        while (true) {
            val contacts = inputProcessor.readFrame(System.`in`) ?: break
            if (verbose) println("Received frame (${contacts.size} contacts):")
            injector.inject(prevContacts, contacts)
            prevContacts = contacts
        }
    }

    fun startUdp(port: Int) {
        val socket = DatagramSocket(port)
        val buffer = ByteArray(294) // 4 + 29 * 10

        println("Listening on UDP port $port...")

        var prevContacts: List<TouchpadContact> = emptyList()

        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val contacts = inputProcessor.parseContacts(packet.data, packet.length)
            if (verbose) println("Received frame (${contacts.size} contacts):")
            injector.inject(prevContacts, contacts)
            prevContacts = contacts
        }
    }

    fun startTcp(port: Int) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(port)
            println("TCP Touchpad receiver started on port $port")

            while (true) {
                try {
                    val clientSocket = serverSocket.accept()
                    println("Client connected: ${clientSocket.inetAddress.hostAddress}")
                    handleTcpClient(clientSocket)
                } catch (e: IOException) {
                    println("Error accepting client connection: ${e.message}")
                }
            }
        } catch (e: IOException) {
            println("Failed to start TCP server on port $port: ${e.message}")
        } finally {
            serverSocket?.close()
            println("TCP Touchpad receiver stopped")
        }
    }

    private fun handleTcpClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 5000 // 5 second read timeout
            val inputStream = clientSocket.getInputStream()

            var prevContacts: List<TouchpadContact> = emptyList()

            while (true) {
                val contacts = inputProcessor.readFrame(inputStream) ?: break
                if (verbose) println("Received frame (${contacts.size} contacts):")
                injector.inject(prevContacts, contacts)
                prevContacts = contacts
            }

        } catch (e: IOException) {
            println("Client connection error: ${e.message}")
        } finally {
            try {
                clientSocket.close()
                println("Client disconnected")
            } catch (e: IOException) {
                println("Error closing client socket: ${e.message}")
            }
        }
    }


}

fun start(args: Array<String>) {
    val iterator = args.iterator()

    fun portNotSpecified(): () -> Unit {
        println("Error: Port not specified")
        return { exitProcess(1) }
    }

    fun getPort(function: (Int) -> Unit): () -> Unit {
        if (iterator.hasNext()) {
            val port = iterator.next()
            return { port.toIntOrNull()?.let { function.invoke(it) } ?: portNotSpecified() }
        } else {
            return portNotSpecified()
        }
    }

    val taskQueue: MutableList<Runnable> = mutableListOf()

    val touchpadDataReceiver = TouchpadDataReceiver()

    while (iterator.hasNext()) {
        when (val arg = iterator.next()) {
            "--touchpad-input-udp-port" -> taskQueue.add(getPort(touchpadDataReceiver::startUdp))
            "--touchpad-input-tcp-port" -> taskQueue.add(getPort(touchpadDataReceiver::startTcp))
            "--touchpad-input-stdin" -> taskQueue.add(touchpadDataReceiver::startSystemIn)
            "--logcat" -> ProcessBuilder("logcat", "-v", "color", "--pid=" + Process.myPid()).inheritIO().start()
            "--verbose" -> touchpadDataReceiver.verbose = true
            else -> println("Invalid argument: $arg")
        }
    }

    // Iterate if needed and run the last task in the current thread
    taskQueue.singleOrNull()?.run() ?: taskQueue.iterator().also {
        while (it.hasNext()) {
            if (it.hasNext()) Thread { it.next().run() }.start()
            else it.next().run()
        }
    }
}

