package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;

parcelable KeymapConfig;
parcelable profiles.KeymapProfile;

interface IRemoteService {
    boolean isRoot();

    void startMouse(in profiles.KeymapProfile keymapProfile, in KeymapConfig keymapConfig);
    void setScreenSize(int width, int height);

    void setCallback(IRemoteServiceCallback cb);
    void removeCallback(IRemoteServiceCallback cb);

    void registerOnKeyEventListener(OnKeyEventListener l);
    void unregisterOnKeyEventListener(OnKeyEventListener l);

    void resumeMouse();
    void pauseMouse();
}