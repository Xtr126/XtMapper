package xtr.keymapper;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

interface IRemoteServiceCallback {
    void loadKeymap();
    void launchEditor();
    void alertMouseAimActivated();
    void cursorSetX(int x);
    void cursorSetY(int y);
    KeymapProfile requestKeymapProfile();
    KeymapConfig requestKeymapConfig();
}