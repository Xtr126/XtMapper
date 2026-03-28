package xtr.keymapper.server;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.server.event.KeyEventHandler;
import xtr.keymapper.server.event.MouseEventHandler;

public interface IInputInterface {
    void injectEvent(float x, float y, int action, int pointerId);
    void injectHoverEvent(float x, float y, int pointerId);

    void injectRightClickEvent(float x, float y, int pointerId, boolean press);

    void injectScroll(float x, float y, float value);
    void pauseResumeKeymap();
    KeymapConfig getKeymapConfig();
    KeyEventHandler getKeyEventHandler();
    MouseEventHandler getMouseEventHandler();
    KeymapProfile getKeymapProfile();
    IRemoteServiceCallback getCallback();
    void moveCursorX(int x);
    void moveCursorY(int y);
    void hideCursor();
    void showCursor();
}
