package xtr.keymapper;

interface IRemoteService {
    void injectEvent(float x, float y, int type, int pointerId);
    void injectScroll(float x, float y, int value);
}