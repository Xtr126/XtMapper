package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

interface IRemoteService {
    boolean isRoot();

    void startServer(in KeymapProfile profile, in KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight);
    void stopServer();

    void registerOnKeyEventListener(OnKeyEventListener l);
    void unregisterOnKeyEventListener(OnKeyEventListener l);

    void resumeMouse();
    void pauseMouse();
    void reloadKeymap();
}