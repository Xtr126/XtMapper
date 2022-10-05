package com.xtr.keymapper

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var pointerOverlay: TouchPointer? = null
    @JvmField
    var server: Server? = null
    private var startOverlayButton: Button? = null
    private var startServerButton: Button? = null
    private var startInTerminal: Button? = null
    private var keymap: Button? = null
    private var configureButton: Button? = null
    private var infoButton: Button? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        server = Server(this)
        pointerOverlay = TouchPointer(this)
        Thread { pointerOverlay!!.startSocket() }.start()
        initButtons()
        setupButtons()
    }

    private fun setupButtons() {
        startServerButton!!.setOnClickListener { startServer(true) }
        startInTerminal!!.setOnClickListener { startServer(false) }
        startOverlayButton!!.setOnClickListener { startService() }
        keymap!!.setOnClickListener { startEditor() }
        configureButton!!.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    InputDeviceSelector::class.java
                )
            )
        }
        infoButton!!.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    InfoActivity::class.java
                )
            )
        }
    }

    private fun initButtons() {
        startOverlayButton = findViewById(R.id.startPointer)
        startServerButton = findViewById(R.id.startServer)
        startInTerminal = findViewById(R.id.startServerM)
        keymap = findViewById(R.id.start_editor)
        configureButton = findViewById(R.id.config_pointer)
        infoButton = findViewById(R.id.about_button)
    }

    private fun startService() {
        checkOverlayPermission()
        if (Settings.canDrawOverlays(this)) {
            pointerOverlay!!.open()
        }
    }

    fun setButtonActive(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.purple_700))
    }

    fun setButtonInactive(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.grey))
    }

    private fun startEditor() {
        checkOverlayPermission()
        if (Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, EditorUI::class.java))
        }
    }

    private fun startServer(autorun: Boolean) {
        checkOverlayPermission()
        if (Settings.canDrawOverlays(this)) {
            server!!.setupServer()
            if (autorun) {
                Thread { server!!.startServer() }.start()
            }
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // send user to the device settings
            val myIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(myIntent)
        }
    }

    companion object {
        const val DEFAULT_PORT = 6234
        const val DEFAULT_PORT_2 = 6345
    }
}