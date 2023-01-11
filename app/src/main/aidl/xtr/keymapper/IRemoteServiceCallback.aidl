// IRemoteServiceCallback.aidl
package xtr.keymapper;

// Declare any non-default types here with import statements

interface IRemoteServiceCallback {
    void onMouseEvent(int code, int value);
    void receiveEvent(String event);
}