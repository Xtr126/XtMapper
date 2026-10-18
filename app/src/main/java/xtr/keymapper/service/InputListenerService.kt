package xtr.keymapper.service

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import xtr.keymapper.server.RemoteServiceHelper

// Input method to detect when user is inputting text
class InputListenerService : InputMethodService() {
    override fun onDestroy() {
        super.onDestroy()
        RemoteServiceHelper.resumeKeymap(applicationContext)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        RemoteServiceHelper.resumeKeymap(applicationContext)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        RemoteServiceHelper.pauseKeymap(applicationContext)
    }
}
