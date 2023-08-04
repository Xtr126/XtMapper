package xtr.keymapper.server;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.touchpointer.KeyEventHandler;
import xtr.keymapper.touchpointer.MouseEventHandler;

public interface IInputInterface {
    void injectEvent(float x, float y, int action, int pointerId);
    void injectScroll(float x, float y, int value);
    KeymapConfig getKeymapConfig();
    KeyEventHandler getKeyEventHandler();
    MouseEventHandler getMouseEventHandler();
    KeymapProfile getKeymapProfile();
    IRemoteServiceCallback getCallback();
    void moveCursorX(float x);
    void moveCursorY(float y);
    void reloadKeymap();
}
