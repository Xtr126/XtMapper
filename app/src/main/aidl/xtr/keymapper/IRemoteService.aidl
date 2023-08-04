package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;

parcelable KeymapConfig;
parcelable KeymapProfile;

interface IRemoteService {
    boolean isRoot();

    void startServer(in KeymapProfile profile, in KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight);
    void stopServer();

    void registerOnKeyEventListener(OnKeyEventListener l);
    void unregisterOnKeyEventListener(OnKeyEventListener l);

    void resumeMouse();
    void pauseMouse();
}