package xtr.keymapper.macro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.annotation.UiContext
import xtr.keymapper.databinding.MacroStatusLayoutBinding
import kotlin.math.max

/**
 * For displaying elapsed time
 */
class MacroStatus(@UiContext context: Context, container: ViewGroup) {

    private val binding: MacroStatusLayoutBinding =
        MacroStatusLayoutBinding.inflate(LayoutInflater.from(context), container, true)
    private var initThreadTimeMillis: Long = 0
    private val mAccessibilityManager: AccessibilityManager =
        context.getSystemService(AccessibilityManager::class.java)
    private val mHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val timeUpdateRunnable = TimeUpdateRunnable()
    private val container: ViewGroup = container

    fun start() {
        initThreadTimeMillis = SystemClock.elapsedRealtime()
        isRunning = true
        timeUpdateRunnable.run()
    }

    fun stop() {
        container.removeView(binding.root)
        binding.root.invalidate()
        isRunning = false
    }

    private fun updateTime() {
        val totalTimeMillis = SystemClock.elapsedRealtime() - initThreadTimeMillis

        val totalTimeSeconds = (totalTimeMillis / 1000).toInt()

        val totalTimeMinutes = totalTimeSeconds / 60

        // For stopwatch
        val millis = totalTimeSeconds * 1000L
        val seconds = totalTimeSeconds - (totalTimeMinutes * 60)

        binding.textClockMinutes.text = totalTimeMinutes.toString()
        binding.textClockSeconds.text = seconds.toString()
        binding.textClockMilliSeconds.text = (totalTimeMillis - millis).div(10).toString()
    }

    private inner class TimeUpdateRunnable : Runnable {
        override fun run() {
            val startTime = SystemClock.elapsedRealtime()

            updateTime()

            if (isRunning) {
                // The stopwatch is still running so execute this runnable again after a delay.
                val talkBackOn = mAccessibilityManager.isTouchExplorationEnabled

                // Grant longer time between redraws when talk-back is on to let it catch up.
                val period = if (talkBackOn) 500 else 25

                // Try to maintain a consistent period of time between redraws.
                val endTime = SystemClock.elapsedRealtime()
                val delay = max(0, startTime + period - endTime)

                mHandler.postDelayed(this, delay)
            }
        }
    }
}
