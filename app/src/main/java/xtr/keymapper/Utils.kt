package xtr.keymapper

import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

object Utils {
    /**
     * @param key input key code KEY_X
     * @return the index of X in the logical alphabet 0-9A-Z, or -1.
     * Multi-character suffixes are valid only as contiguous sequences
     * (e.g. "9A", "AB", "ABC" -> start index; "AZ", "A1" -> -1).
     */
    @JvmStatic
    fun obtainIndex(key: String): Int {
        val suffix = key.substring(4)
        if (suffix.isEmpty()) return 0
        var start = -1
        for (i in suffix.indices) {
            val idx = when (val c = suffix[i]) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> return -1
            }
            if (i == 0) start = idx else if (idx != start + i) return -1
        }
        return start
    }

    @JvmStatic
    @Throws(IOException::class)
    fun geteventStream(nativeLibraryDir: String): Process {
        val sh = Runtime.getRuntime().exec("sh")
        val outputStream = DataOutputStream(sh.outputStream)
        outputStream.writeBytes("exec env LD_PRELOAD=$nativeLibraryDir/libgetevent.so getevent -ql\n")
        outputStream.flush()
        return sh
    }

    @JvmStatic
    fun getPointerLayoutParams(type: Int): WindowManager.LayoutParams {
        val mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            // Make the underlying application window visible through the cursor
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return mParams
    }

    @JvmStatic
    fun applyEmptyTouchableRegion(view: android.view.View?) {
        if (view == null) return
        val listener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                registerEmptyTouchableRegion(v)
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {}
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) {
            registerEmptyTouchableRegion(view)
        }
    }

    private fun registerEmptyTouchableRegion(view: android.view.View) {
        try {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val touchableRegionField = infoClass.getField("touchableRegion")
            val setTouchableInsetsMethod = infoClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val touchableInsetsRegionField = infoClass.getField("TOUCHABLE_INSETS_REGION")
            val touchableInsetsRegion = touchableInsetsRegionField.getInt(null)

            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if ("onComputeInternalInsets" == method.name && args != null && args.isNotEmpty()) {
                    val info = args[0]
                    val region = touchableRegionField.get(info) as? android.graphics.Region
                    region?.setEmpty()
                    setTouchableInsetsMethod.invoke(info, touchableInsetsRegion)
                }
                null
            }

            val addListenerMethod = android.view.ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerClass)
            addListenerMethod.invoke(view.viewTreeObserver, listener)
        } catch (t: Throwable) {
            android.util.Log.w("Utils", "Failed to register empty touchable region", t)
        }
    }
}
