package xtr.keymapper

import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

object Utils {
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

    /**
     * Configures the provided overlay view with an empty touchable region,
     * preventing Android 12+ InputDispatcher from dropping untrusted touches.
     *
     * @param view The overlay View to configure.
     */
    @JvmStatic
    fun applyEmptyTouchableRegion(view: android.view.View?) {
        if (view == null) return
        if (view.getTag(R.id.tag_empty_touchable_region) == true) return
        view.setTag(R.id.tag_empty_touchable_region, true)

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

    private class InsetsHelper private constructor(
        val listenerClass: Class<*>,
        val touchableRegionField: java.lang.reflect.Field,
        val setTouchableInsetsMethod: java.lang.reflect.Method,
        val touchableInsetsRegion: Int,
        val addListenerMethod: java.lang.reflect.Method
    ) {
        companion object {
            val instance: InsetsHelper? by lazy {
                try {
                    val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                    val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
                    val touchableRegionField = infoClass.getField("touchableRegion")
                    val setTouchableInsetsMethod = infoClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                    val touchableInsetsRegionField = infoClass.getField("TOUCHABLE_INSETS_REGION")
                    val touchableInsetsRegion = touchableInsetsRegionField.getInt(null)
                    val addListenerMethod = android.view.ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerClass)
                    InsetsHelper(listenerClass, touchableRegionField, setTouchableInsetsMethod, touchableInsetsRegion, addListenerMethod)
                } catch (t: Throwable) {
                    android.util.Log.w("Utils", "Failed to initialize InsetsHelper", t)
                    null
                }
            }
        }
    }

    /**
     * Reflectively registers an OnComputeInternalInsetsListener on the view's ViewTreeObserver
     * to empty the touchableRegion and set TOUCHABLE_INSETS_REGION.
     */
    private fun registerEmptyTouchableRegion(view: android.view.View) {
        val observer = view.viewTreeObserver
        if (observer == null || !observer.isAlive) return
        val helper = InsetsHelper.instance ?: return
        try {
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                helper.listenerClass.classLoader,
                arrayOf(helper.listenerClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val info = args?.getOrNull(0)
                        if (info != null) {
                            val region = helper.touchableRegionField.get(info) as? android.graphics.Region
                            region?.setEmpty()
                            helper.setTouchableInsetsMethod.invoke(info, helper.touchableInsetsRegion)
                        }
                        null
                    }
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "EmptyTouchableRegionListener"
                    else -> null
                }
            }

            helper.addListenerMethod.invoke(observer, listener)
            view.post { view.requestLayout() }
        } catch (t: Throwable) {
            android.util.Log.w("Utils", "Failed to register empty touchable region", t)
        }
    }
}
