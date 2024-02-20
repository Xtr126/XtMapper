package xtr.keymapper.service;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;

import xtr.keymapper.server.RemoteServiceHelper;

public class InputListenerService extends InputMethodService {
    @Override
    public void onDestroy() {
        super.onDestroy();
        RemoteServiceHelper.resumeKeymap(getApplicationContext());
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        RemoteServiceHelper.resumeKeymap(getApplicationContext());
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        RemoteServiceHelper.pauseKeymap(getApplicationContext());
    }

}
