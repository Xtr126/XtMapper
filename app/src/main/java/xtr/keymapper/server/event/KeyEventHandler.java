package xtr.keymapper.server.event;

import static xtr.keymapper.keymap.KeymapConfig.KEY_ALT;
import static xtr.keymapper.keymap.KeymapConfig.KEY_CTRL;
import static xtr.keymapper.keymap.KeymapProfile.MAX_DPADS;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.UP;
import static xtr.keymapper.server.pid.PointerId.dpadpid1;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Map;

import xtr.keymapper.Utils;
import xtr.keymapper.dpad.DpadHandler;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.element.Camera;
import xtr.keymapper.keymap.element.Key;
import xtr.keymapper.macro.Macro;
import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.server.pid.PidProvider;
import xtr.keymapper.keymap.element.SwipeKey;
import xtr.keymapper.swipekey.SwipeKeyHandler;

public class KeyEventHandler {
    public boolean ctrlKeyPressed = false;
    public boolean altKeyPressed = false;
    private DpadHandler[] dpadHandlers;
    private ArrayList<SwipeKeyHandler> swipeKeyHandlers;
    private final PidProvider pidProvider = new PidProvider();
    private final IInputInterface mInput;
    private HandlerThread mHandlerThread;
    private Handler eventHandler;

    public KeyEventHandler(IInputInterface mInput) {
        this.mInput = mInput;
    }

    public void init(){
        mHandlerThread = new HandlerThread("events");
        mHandlerThread.start();
        eventHandler = new Handler(mHandlerThread.getLooper());

        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        KeymapProfile profile = mInput.getKeymapProfile();


        dpadHandlers = new DpadHandler[MAX_DPADS];
        for (int i = 0; i < dpadHandlers.length; i++) {
            int pid = dpadpid1.id + i;
            if (profile.dpadArray[i] != null) {
                dpadHandlers[i] = new DpadHandler(profile.dpadArray[i], pid, eventHandler, keymapConfig.swipeDelayMs);
                dpadHandlers[i].setInterface(mInput);
            }
        }


        // Correction of x and y deviation from center
        for (Key key: profile.keys) {
            key.x += key.offset;
            key.y += key.offset;
        }

        swipeKeyHandlers = new ArrayList<>();
        for (SwipeKey key : profile.swipeKeys) {
            swipeKeyHandlers.add(new SwipeKeyHandler(key));
        }
    }

    public void stop() {
        dpadHandlers = null;
        swipeKeyHandlers = null;
        if (mHandlerThread != null)
            mHandlerThread.quit();
        mHandlerThread = null;
        eventHandler = null;
    }

    public static class KeyEvent {
        public String code;
        public int action;
    }

    public void handleEvent(String line) throws RemoteException {
        // line: EV_KEY KEY_X DOWN

        KeyEvent event = getEvent(line);
        if(event == null) return;

        KeymapConfig keymapConfig = mInput.getKeymapConfig();

        detectCtrlAltKeys(event);
        int i = Utils.obtainIndex(event.code);
        if (i > 0) { // A-Z and 0-9 keys
            if (event.action == DOWN) if (handleKeyboardShortcuts(i)) return;
            handleMouseAimAndCamera(i, event.action);
        } else { // CTRL, ALT, Arrow keys
            if (event.code.equals("KEY_GRAVE") && event.action == DOWN)
                if (keymapConfig.keyGraveMouseAim) {
                    mInput.getMouseEventHandler().triggerMouseAim();
                    return;
                }
        }

        for (DpadHandler dpadHandler: dpadHandlers) {
            if (dpadHandler != null)
                dpadHandler.handleEvent(event.code, event.action);
        }

        ArrayList<Key> keyList = mInput.getKeymapProfile().keys;
        for (Key key : keyList)
            if (event.code.equals(key.code))
                mInput.injectEvent(key.x, key.y, event.action, keyList.indexOf(key));

        for (SwipeKeyHandler swipeKeyHandler : swipeKeyHandlers)
            swipeKeyHandler.handleEvent(event, mInput, pidProvider, eventHandler, keymapConfig.swipeDelayMs);

        Map<String, Macro> macroIdMap = mInput.getKeymapProfile().macroIdMap;
        if (!macroIdMap.isEmpty())
            macroIdMap.forEach((macroId, macro) -> {
                if (event.code.equals("KEY_" + macro.triggerKey)) new Thread(() -> {
                    macro.runMacro(mInput, pidProvider.getPid(macroId));
                    pidProvider.releasePidFor(macroId);
                }).start();
            });
    }

    private void detectCtrlAltKeys(KeyEvent event) {
        if (event.code.contains("CTRL")) ctrlKeyPressed = event.action == DOWN;
        if (event.code.contains("ALT")) altKeyPressed = event.action == DOWN;
    }

    private KeyEvent getEvent(String line){
        KeyEvent event = new KeyEvent();
        // line: EV_KEY KEY_X DOWN
        String[] input_event = line.split("\\s+");
        if (!input_event[1].equals("EV_KEY")) return null;
        event.code = input_event[2];
        if (!event.code.contains("KEY_")) return null;

        switch (input_event[3]) {
            case "UP":
                event.action = UP;
                break;
            case "DOWN":
                event.action = DOWN;
                break;
            default:
                return null;
        }
        return event;
    }

    private boolean handleKeyboardShortcuts(int keycode) throws RemoteException {
        if (!(altKeyPressed || ctrlKeyPressed)) return false;
        final String modifier = ctrlKeyPressed ? KEY_CTRL : KEY_ALT;
        KeymapConfig keymapConfig = mInput.getKeymapConfig();

        if (keymapConfig.launchEditorShortcutKeyModifier.equals(modifier))
            if (keycode == keymapConfig.launchEditorShortcutKey) {
                mInput.getCallback().launchEditor();
                return true;
            }

        if (keymapConfig.pauseResumeShortcutKeyModifier.equals(modifier))
            if (keycode == keymapConfig.pauseResumeShortcutKey) {
                mInput.pauseResumeKeymap();
                return true;
            }

        if (keymapConfig.switchProfileShortcutKeyModifier.equals(modifier))
            if (keycode == keymapConfig.switchProfileShortcutKey) {
                mInput.getCallback().switchProfiles();
                return true;
            }
        return false;
    }

    public void handleKeyboardShortcutEvent(String line) throws RemoteException {
        KeyEvent event = getEvent(line);
        if (event != null) {
            detectCtrlAltKeys(event);
            int i = Utils.obtainIndex(event.code);
            if (event.action == DOWN) handleKeyboardShortcuts(i);
        }
    }

    private void handleMouseAimAndCamera(int keycode, int action) {
        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (keycode == keymapConfig.mouseAimShortcutKey) {
            // if not toggle then hold down key to aim
            if (keymapConfig.mouseAimToggle && action == UP) return;
            mInput.getMouseEventHandler().triggerMouseAim();
        }
        else {
            Camera camera = mInput.getKeymapProfile().camera;
            if (camera != null) if (keycode == camera.triggerKeyCode) {
                // If not toggle then hold down key to move camera
                if (camera.toggle && action == UP) return;
                mInput.getMouseEventHandler().triggerCamera();
            }
        }
    }
}
