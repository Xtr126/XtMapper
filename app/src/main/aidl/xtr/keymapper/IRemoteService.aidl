package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.ActivityObserver;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

interface IRemoteService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server

    boolean isRoot() = 1;

    void startServer(in KeymapProfile profile, in KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight) = 2;
    void stopServer() = 3;

    void registerOnKeyEventListener(OnKeyEventListener l) = 4;
    void unregisterOnKeyEventListener(OnKeyEventListener l) = 5;

    void registerActivityObserver(ActivityObserver callback) = 6;
    void unregisterActivityObserver(ActivityObserver callback) = 7;

    void resumeMouse() = 8;
    void pauseMouse() = 9;
    void reloadKeymap() = 10;

}