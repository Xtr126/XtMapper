package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.OnMouseEventListener;

parcelable KeymapConfig;
parcelable KeymapProfile;

interface IRemoteService {
    boolean isRoot();

    void startMouse(KeymapProfile keymapProfile, KeymapConfig keymapConfig);
    void setScreenSize(int width, int height);

    void setCallback(IRemoteServiceCallback cb);
    void removeCallback(IRemoteServiceCallback cb);

    void registerOnKeyEventListener(OnKeyEventListener l);
    void unregisterOnKeyEventListener(OnKeyEventListener l);

    void resumeMouse();
    void pauseMouse();
}