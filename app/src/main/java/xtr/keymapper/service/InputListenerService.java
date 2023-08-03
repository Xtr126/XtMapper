package xtr.keymapper.service;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;

import xtr.keymapper.server.RemoteService;

public class InputListenerService extends InputMethodService {
    @Override
    public void onDestroy() {
        super.onDestroy();
        RemoteService.resumeKeymap();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        RemoteService.resumeKeymap();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        RemoteService.pauseKeymap();
    }

}
