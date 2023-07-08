package xtr.keymapper.service;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;

import xtr.keymapper.server.InputService;

public class InputListenerService extends InputMethodService {
    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        InputService.reloadKeymap();
        super.onStartInput(attribute, restarting);
    }
}
