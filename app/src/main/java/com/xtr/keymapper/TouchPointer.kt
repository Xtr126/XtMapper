package com.xtr.keymapper

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.xtr.keymapper.Server.Companion.killServer
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class TouchPointer(  // declaring required variables
    private val context: Context
) {
    private val cursorView: View
    private val mParams: WindowManager.LayoutParams
    private val mWindowManager: WindowManager
    private var x1 = 100
    private var y1 = 100
    private lateinit var keys: Array<String>
    private lateinit var keyX: Array<Float>
    private lateinit var keyY: Array<Float>
    val cmdView3: TextView = (context as MainActivity).findViewById(R.id.cmdview3)
    private val startButton: Button
    private var c3: StringBuilder
    private var pointerDown = false
    private val textViewUpdater = Handler()
    private var counter = 0
    private var dpad1: String? = null
    private var dpad2: String? = null
    fun open() {
        (context as MainActivity).setButtonActive(startButton)
        startButton.setOnClickListener {
            killServer(context.getPackageName())
            hideCursor()
            context.setButtonInactive(startButton)
            startButton.setOnClickListener { open() }
        }
        if (cursorView.windowToken == null) if (cursorView.parent == null) {
            mWindowManager.addView(cursorView, mParams)
            try {
                loadKeymap()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            Thread { eventHandler() }.start()
        }
    }

    private fun hideCursor() {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(
                cursorView
            )
            cursorView.invalidate()
            // remove all views
            (cursorView.parent as ViewGroup).removeAllViews()
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (e: Exception) {
            Log.e("Error2", e.toString())
        }
    }

    @Throws(IOException::class)
    fun loadKeymap() {
        val keymapConfig = KeymapConfig(context)
        keymapConfig.loadConfig()
        keys = keymapConfig.keys
        keyX = keymapConfig.x
        keyY = keymapConfig.y
        dpad1 = keys[36]
        dpad2 = keys[37]
    }

    private fun updateCmdView(s: String) {
        (context as MainActivity).server!!.updateCmdView1(s)
    }

    private fun updateCmdView3(s: String?) {
        if (counter < Server.MAX_LINES_1) {
            c3.append(s)
                .append("\n")
            counter++
        } else {
            counter = 0
            c3 = StringBuilder()
        }
    }

    fun startSocket() {
        try {
            val serverSocket = ServerSocket(MainActivity.DEFAULT_PORT_2)
            while (true) {
                try {
                    val socket = serverSocket.accept()
                    (context as MainActivity).runOnUiThread { open() }
                    Thread {
                        try {
                            handleMouseEvents(socket)
                        } catch (e: IOException) {
                            updateCmdView(e.toString())
                        }
                    }.start()
                } catch (e: IOException) {
                    updateCmdView(e.toString())
                }
            }
        } catch (e: IOException) {
            Log.e("I/O Error", e.toString())
            tryStopSocket()
        }
        updateCmdView("waiting for server...")
    }

    @Throws(IOException::class)
    private fun handleMouseEvents(clientSocket: Socket) {
        val `in` = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
        updateCmdView("initialized: listening for events through socket")
        val socket = Socket("127.0.0.1", MainActivity.DEFAULT_PORT)
        val xOut = DataOutputStream(socket.getOutputStream())
        pointerGrab(xOut)
        val display = mWindowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        val width = size.x
        val height = size.y
        val x2 = width / 100
        val y2 = height / 100
        var line: String
        while (`in`.readLine().also { line = it } != null) {
            updateCmdView3("socket: $line")
            val xy = line.split("\\s+").toTypedArray()
            when (xy[0]) {
                "REL_X" -> {
                    x1 += xy[1].toInt()
                    if (x1 < 0) x1 -= xy[1].toInt()
                    if (x1 > width) x1 -= xy[1].toInt()
                    if (pointerDown) xOut.writeBytes(
                        """${Integer.sum(x1, x2)} ${Integer.sum(y1, y2)} MOVE 36
"""
                    ) // pointerId 36 is reserved for mouse events
                }
                "REL_Y" -> {
                    y1 += xy[1].toInt()
                    if (y1 < 0) y1 -= xy[1].toInt()
                    if (y1 > height) y1 -= xy[1].toInt()
                    if (pointerDown) xOut.writeBytes(
                        """${Integer.sum(x1, x2)} ${Integer.sum(y1, y2)} MOVE 36
"""
                    )
                }
                "BTN_MOUSE" -> {
                    pointerDown = xy[1] == "1"
                    xOut.writeBytes(
                        """${Integer.sum(x1, x2)} ${Integer.sum(y1, y2)} ${xy[1]} 36
"""
                    )
                }
            }
            movePointer()
        }
        `in`.close()
        clientSocket.close()
        socket.close()
    }

    private fun tryStopSocket() {
        try {
            val xOut =
                DataOutputStream(Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2).getOutputStream())
            xOut.writeBytes(
                """
    ${null.toString()}
    
    """.trimIndent()
            )
            xOut.flush()
            xOut.close()
        } catch (e: IOException) {
            Log.e("I/O error", e.toString())
        }
    }

    private fun eventHandler() {
        try {
            val socket = Socket("127.0.0.1", MainActivity.DEFAULT_PORT)
            val xOut = DataOutputStream(socket.getOutputStream())
            var line: String
            val getevent = Utils.geteventStream(context)
            while (getevent.readLine().also { line = it } != null) { //read events
                val xy = line.split("\\s+").toTypedArray()
                // Keyboard input be like: /dev/input/event3 EV_KEY KEY_X DOWN
                // Mouse input be like: /dev/input/event2 EV_REL REL_X ffffffff
                updateCmdView(line)
                if (xy[3] == "DOWN" || xy[3] == "UP") { // Ignore mouse/touchpad events here
                    val i =
                        Utils.obtainIndex(xy[2]) // Strips off KEY_ from KEY_X and return the index of X in alphabet
                    if (i in 0..35) { // Make sure valid (This step omits unrelated events such as KEY_UP, KEY_$, int i = -1 in that case)
                        xOut.writeBytes("${keyX[i]} ${keyY[i]} ${xy[3]} $i")
                    } // else {
                    //   if (dpad1 != null) handleDpad1(x_out, xy[2], xy[3].equals("DOWN"));
                    // }
                }
                movePointer()
            }
        } catch (e: IOException) {
            updateCmdView("Unable to start overlay: server not started")
            hideCursor()
            Log.d("I/O Error", e.toString())
        }
    }

    private fun movePointer() {
        (context as MainActivity).runOnUiThread {
            cursorView.x = x1.toFloat()
            cursorView.y = y1.toFloat()
        }
    }

    @Throws(IOException::class)
    private fun pointerGrab(x_out: DataOutputStream) {
        x_out.writeBytes(
            """
    _ true ioctl 0
    
    """.trimIndent()
        ) // Tell remote server running as root to ioctl to gain exclusive access to input device
    }

    init {
        c3 = StringBuilder()
        startButton = (context as MainActivity).findViewById(R.id.startPointer)
        // set the layout parameters of the cursor
        mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,  // Don't let the cursor grab the input focus
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,  // Make the underlying application window visible
            // through the cursor
            PixelFormat.TRANSLUCENT
        )
        val layoutInflater =
            context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        cursorView = layoutInflater.inflate(R.layout.cursor, LinearLayout(context), false)
        mParams.gravity = Gravity.CENTER
        mWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        textViewUpdater.post(object : Runnable {
            override fun run() {
                context.runOnUiThread { cmdView3.text = c3 }
                textViewUpdater.postDelayed(this, Server.REFRESH_INTERVAL)
            }
        })
    }
}