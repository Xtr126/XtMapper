package xtr.keymapper;

import xtr.keymapper.IRemoteServiceCallback;

interface IRemoteService {
    void injectEvent(float x, float y, int type, int pointerId);
    void injectScroll(float x, float y, int value);
    void moveCursorX(int x);
    void moveCursorY(int y);
    void startServer();
    int tryOpenDevice(String device);
    void reloadKeymap();
    void closeDevice();
    void registerCallback(IRemoteServiceCallback cb);
    void unregisterCallback(IRemoteServiceCallback cb);
}