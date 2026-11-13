package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.ActivityObserver;
import xtr.keymapper.IShellAuthenticationCallback;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

interface IRemoteService {
    const int ACTIVATION_MANUAL = 0;
    const int ACTIVATION_DISPLAY_CHANGE = 1;

    void destroy() = 16777114; // Destroy method defined by Shizuku server


    void startServer(in KeymapProfile profile, in KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight, int displayId, int activationReason) = 2;
    void stopServer() = 3;

    void registerOnKeyEventListener(OnKeyEventListener l) = 4;
    void unregisterOnKeyEventListener(OnKeyEventListener l) = 5;

    void registerActivityObserver(ActivityObserver callback) = 6;
    void unregisterActivityObserver(ActivityObserver callback) = 7;

    void resumeMouse() = 8;
    void pauseMouse() = 9;
    void reloadKeymap() = 10;
    boolean isActive() = 11;

    void authenticateShellClient(IShellAuthenticationCallback callback, long challenge) = 12;
}