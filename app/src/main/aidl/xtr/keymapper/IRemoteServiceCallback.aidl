package xtr.keymapper;

// Declare any non-default types here with import statements

interface IRemoteServiceCallback {
    void loadKeymap();
    void launchEditor();
    void alertMouseAimActivated();
    void cursorSetX(int x);
    void cursorSetY(int y);
    void reloadKeymap();
}