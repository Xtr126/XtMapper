package xtr.keymapper.editor

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner

/** Lifecycle and saved-state owners for Compose content hosted by a Service overlay. */
abstract class ComposeLifecycleService : LifecycleService() {
    protected fun createComposeView(context: Context): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@ComposeLifecycleService)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
}
