package com.xtr.keymapper

import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.nambimobile.widgets.efab.ExpandableFabLayout
import com.nambimobile.widgets.efab.FabOption
import com.xtr.keymapper.Layout.FloatingActionKey
import com.xtr.keymapper.Layout.MovableFrameLayout
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter

class EditorUI : AppCompatActivity() {
    private lateinit var keymapView: View
    private var mParams: WindowManager.LayoutParams? = null
    private var mWindowManager: WindowManager? = null
    private var mainView: ExpandableFabLayout? = null
    private var keyInFocus: FloatingActionKey? = null
    private val keyX = ArrayList<FloatingActionKey>()
    private var dpad1: MovableFrameLayout? = null
    private var dpad2: MovableFrameLayout? = null
    private val defaultX = 200f
    private val defaultY = 200f
    private var i = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        keymapView = layoutInflater.inflate(R.layout.keymap, ExpandableFabLayout(this), false)
        mainView = keymapView.findViewById(R.id.MainView)
        initFab()
        mParams!!.gravity = Gravity.CENTER
        mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        open()
    }

    private fun open() {
        try {
            if (keymapView.windowToken == null) {
                if (keymapView.parent == null) {
                    mWindowManager!!.addView(mainView, mParams)
                    loadKeymap()
                }
            }
        } catch (e: Exception) {
            Log.d("Error1", e.toString())
        }
    }

    private fun hideView() {
        try {
            saveKeymap()
            finish()
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(keymapView)
            keymapView.invalidate()
            // remove all views
            (keymapView.parent as ViewGroup).removeAllViews()
            finish()
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (e: Exception) {
            Log.d("Error2", e.toString())
        }
    }

    @Throws(IOException::class)
    private fun loadKeymap() {
        val keymapConfig = KeymapConfig(this)
        keymapConfig.loadConfig()
        val keys = keymapConfig.keys
        val keysX = keymapConfig.x
        val keysY = keymapConfig.y
        for (n in 0..35) {
            if (keys[n] != null) {
                addKey(keys[n], keysX[n], keysY[n])
            }
        }
        val dpad1 = keys[36]
        val dpad2 = keys[37]
        if (dpad1 != null) {
            addDpad1(keysX[36], keysY[36])
        }
        if (dpad2 != null) {
            addDpad2(keysX[37], keysY[37])
        }
    }

    @Throws(IOException::class)
    private fun saveKeymap() {
        val linesToWrite = StringBuilder()
        for (i in keyX.indices) {
            if (keyX[i].key != null) {
                linesToWrite.append(keyX[i].data)
            }
        }
        if (dpad1 != null) {
            val xOfPivot = dpad1!!.x + dpad1!!.pivotX
            val yOfPivot = dpad1!!.y + dpad1!!.pivotY
            linesToWrite.append("UDLR_DPAD ")
                .append(dpad1!!.x).append(" ")
                .append(dpad1!!.y).append(" ")
                .append(dpad1!!.height).append(" ")
                .append(xOfPivot).append(" ")
                .append(yOfPivot).append("\n")
        }
        if (dpad2 != null) {
            val xOfPivot = dpad2!!.x + dpad2!!.pivotX
            val yOfPivot = dpad2!!.y + dpad2!!.pivotY
            linesToWrite.append("WASD_DPAD ")
                .append(dpad2!!.x).append(" ")
                .append(dpad2!!.y).append(" ")
                .append(dpad2!!.height).append(" ")
                .append(xOfPivot).append(" ")
                .append(yOfPivot).append("\n")
        }
        val fileWriter = FileWriter(KeymapConfig.getConfigPath(this))
        val printWriter = PrintWriter(fileWriter)
        printWriter.print(linesToWrite)
        printWriter.close()
    }

    private fun initFab() {
        val saveButton = mainView!!.findViewById<FabOption>(R.id.save_button)
        val addKey = mainView!!.findViewById<FabOption>(R.id.add_button)
        val dPad = mainView!!.findViewById<FabOption>(R.id.d_pad)
        val crossHair = mainView!!.findViewById<FabOption>(R.id.cross_hair)
        saveButton.setOnClickListener { hideView() }
        addKey.setOnClickListener { addKey("A", defaultX, defaultY) }
        dPad.setOnClickListener(object : View.OnClickListener {
            var x = 0
            override fun onClick(v: View) {
                x = if (x == 0) {
                    addDpad1(defaultX, defaultY)
                    1
                } else {
                    addDpad2(defaultX, defaultY)
                    0
                }
            }
        })
        dPad.scaleType = ImageView.ScaleType.CENTER_INSIDE
        crossHair.scaleType = ImageView.ScaleType.CENTER_INSIDE
        saveButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        addKey.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private fun addDpad1(x: Float, y: Float) {
        if (dpad1 == null) {
            dpad1 = layoutInflater.inflate(R.layout.d_pad_1, mainView, true)
                .findViewById(R.id.dpad1)
            dpad1!!.findViewById<View>(R.id.closeButton)
                .setOnClickListener {
                    mainView!!.removeView(dpad1)
                    dpad1 = null
                }
        }
        dpad1!!.animate().x(x).y(y)
            .setDuration(500)
            .start()
    }

    private fun addDpad2(x: Float, y: Float) {
        if (dpad2 == null) {
            dpad2 = layoutInflater.inflate(R.layout.d_pad_2, mainView, true)
                .findViewById(R.id.dpad2)
            dpad2!!.findViewById<View>(R.id.closeButton)
                .setOnClickListener {
                    mainView!!.removeView(dpad2)
                    dpad2 = null
                }
        }
        dpad2!!.animate().x(x).y(y)
            .setDuration(500)
            .start()
    }

    private fun addKey(key: String?, x: Float, y: Float) {
        keyX.add(i, FloatingActionKey(this))
        mainView!!.addView(keyX[i])
        keyX[i].setText(key)
        keyX[i].animate()
            .x(x)
            .y(y)
            .setDuration(1000)
            .start()
        keyX[i].setOnClickListener { view: View -> setKeyInFocus(view) }
        i++
    }

    private fun setKeyInFocus(view: View) {
        keyInFocus = view as FloatingActionKey
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyInFocus != null) {
            val key = event.displayLabel.toString()
            if (key.matches(Regex("[a-zA-Z0-9]+"))) {
                keyInFocus!!.setText(key)
            }
        }
        return true
    }
}