package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.OnMouseEventListener;

interface IRemoteService {
    void injectEvent(float x, float y, int action, int pointerId);
    void injectScroll(float x, float y, int value);

    void moveCursorX(int x);
    void moveCursorY(int y);

    boolean isRoot();

    void startMouse();
    void reloadKeymap();
    void setScreenSize(int width, int height);

    void setCallback(IRemoteServiceCallback cb);
    void removeCallback(IRemoteServiceCallback cb);

    void registerOnKeyEventListener(OnKeyEventListener l);
    void unregisterOnKeyEventListener(OnKeyEventListener l);

    void setOnMouseEventListener(OnMouseEventListener l);
    void removeOnMouseEventListener(OnMouseEventListener l);
}