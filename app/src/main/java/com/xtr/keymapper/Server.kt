package com.xtr.keymapper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import android.widget.TextView
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

class Server(private val context: Context) {
    private val scriptName = "/data/local/tmp/xtMapper.sh\n"
    val cmdView: TextView = (context as MainActivity).findViewById(R.id.cmdview)
    val cmdView2: TextView = (context as MainActivity).findViewById(R.id.cmdview2)
    private var c1: StringBuilder
    private var c2: StringBuilder
    private var counter1 = 0
    private var counter2 = 0
    private fun textViewUpdaterTask(context: MainActivity) {
        val outputUpdater = Handler()
        outputUpdater.post(object : Runnable {
            override fun run() {
                context.runOnUiThread { cmdView.text = c1 }
                outputUpdater.postDelayed(this, REFRESH_INTERVAL)
            }
        })
        outputUpdater.post(object : Runnable {
            override fun run() {
                context.runOnUiThread { cmdView2.text = c2 }
                outputUpdater.postDelayed(this, REFRESH_INTERVAL)
            }
        })
    }

    private val deviceName: String?
        get() {
            val sharedPref = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
            return sharedPref.getString("device", null)
        }

    @Throws(IOException::class, InterruptedException::class)
    private fun writeScript(
        packageName: String,
        ai: ApplicationInfo,
        apk: String,
        out: DataOutputStream
    ) {
        out.writeBytes("cat > $scriptName") // Write contents to file through pipe
        out.writeBytes("#!/system/bin/sh\n")
        out.writeBytes("pkill -f $packageName.Input\n")
        out.writeBytes(
            """LD_LIBRARY_PATH="${ai.nativeLibraryDir}" CLASSPATH="$apk" /system/bin/app_process /system/bin $packageName.Input $deviceName
"""
        ) // input device node as argument
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun setExecPermission(out: DataOutputStream) {
        out.writeBytes("touch $scriptName")
        out.writeBytes("chmod 777 $scriptName")
    }

    fun setupServer() {
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val ai = pm.getApplicationInfo(packageName, 0)
            val apk = ai.publicSourceDir // Absolute path to apk in /data/app
            val sh = Utils.getRootAccess()
            val out = DataOutputStream(sh.outputStream)
            setExecPermission(out)
            writeScript(packageName, ai, apk, out)
            out.close()
            sh.waitFor()
            // Notify user
            updateCmdView1("run $scriptName")
        } catch (e: IOException) {
            Log.e("Server", e.toString())
        } catch (e: InterruptedException) {
            Log.e("Server", e.toString())
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("Server", e.toString())
        }
    }

    fun startServer() {
        if (deviceName != null) {
            updateCmdView1("starting server")
            try {
                val sh = Utils.getRootAccess()
                val outputStream = DataOutputStream(sh.outputStream)
                outputStream.writeBytes(scriptName)
                outputStream.close()
                val stdout = BufferedReader(InputStreamReader(sh.inputStream))
                var line: String
                while (stdout.readLine().also { line = it } != null) {
                    updateCmdView2("stdout: $line")
                }
                sh.waitFor()
            } catch (e: IOException) {
                Log.e("Server", e.toString())
            } catch (e: InterruptedException) {
                Log.e("Server", e.toString())
            }
        } else {
            updateCmdView1("Please select input device")
        }
    }

    fun updateCmdView1(s: String?) {
        if (counter1 < MAX_LINES_2) {
            c1.append(s).append("\n")
            counter1++
        } else {
            counter1 = 0
            c1 = StringBuilder()
        }
    }

    private fun updateCmdView2(s: String?) {
        if (counter2 < MAX_LINES_1) {
            c2.append(s).append("\n")
            counter2++
        } else {
            counter2 = 0
            c2 = StringBuilder()
        }
    }

    companion object {
        const val MAX_LINES_1 = 16
        const val MAX_LINES_2 = 32
        const val REFRESH_INTERVAL: Long = 200
        @JvmStatic
        fun killServer(packageName: String) {
            try {
                val sh = Utils.getRootAccess()
                val outputStream = DataOutputStream(sh.outputStream)
                outputStream.writeBytes("pkill -f $packageName.Input\n")
                outputStream.writeBytes("pkill -f libgetevent.so\n")
                outputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    init {
        c1 = StringBuilder()
        c2 = StringBuilder()
        textViewUpdaterTask(context as MainActivity)
    }
}