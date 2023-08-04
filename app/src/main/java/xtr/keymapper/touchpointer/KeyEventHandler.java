package xtr.keymapper.touchpointer;

import static xtr.keymapper.keymap.KeymapConfig.KEY_ALT;
import static xtr.keymapper.keymap.KeymapConfig.KEY_CTRL;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.UP;
import static xtr.keymapper.touchpointer.PointerId.dpad1pid;
import static xtr.keymapper.touchpointer.PointerId.dpad2pid;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;

import java.util.ArrayList;

import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.Utils;
import xtr.keymapper.dpad.DpadHandler;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.KeymapProfileKey;
import xtr.keymapper.server.IInputInterface;
import xtr.keymapper.server.RemoteService;
import xtr.keymapper.swipekey.SwipeKey;
import xtr.keymapper.swipekey.SwipeKeyHandler;

public class KeyEventHandler {
    boolean ctrlKeyPressed = false;
    boolean altKeyPressed = false;
    private DpadHandler dpad1Handler, dpad2Handler;
    private final ArrayList<SwipeKeyHandler> swipeKeyHandlers = new ArrayList<>();
    private final PidProvider pidProvider = new PidProvider();
    private Handler eventHandler;
    private final IInputInterface mInput;

    public KeyEventHandler(IInputInterface mInput) {
        this.mInput = mInput;
    }

    public void init(){
        HandlerThread mHandlerThread = new HandlerThread("events");
        mHandlerThread.start();
        eventHandler = new Handler(mHandlerThread.getLooper());

        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        dpad1Handler = new DpadHandler(keymapConfig.dpadRadiusMultiplier, mInput.getKeymapProfile().dpadUdlr, dpad1pid.id, eventHandler, keymapConfig.swipeDelayMs);
        dpad2Handler = new DpadHandler(keymapConfig.dpadRadiusMultiplier, mInput.getKeymapProfile().dpadWasd, dpad2pid.id, eventHandler, keymapConfig.swipeDelayMs);

        if (dpad1Handler != null) dpad1Handler.setInterface(mInput);
        if (dpad2Handler != null) dpad2Handler.setInterface(mInput);

        KeymapProfile profile = mInput.getKeymapProfile();
        // Correction of x and y deviation from center
        for (KeymapProfileKey key: profile.keys) {
            key.x += key.offset;
            key.y += key.offset;
        }

        for (SwipeKey key : profile.swipeKeys) {
            swipeKeyHandlers.add(new SwipeKeyHandler(key));
        }
    }

    public static class KeyEvent {
        public String code;
        public int action;
    }

    public void handleEvent(String line) throws RemoteException {
        // line: /dev/input/event3: EV_KEY KEY_X DOWN
        String[] input_event = line.split("\\s+");
        if (!input_event[1].equals("EV_KEY")) return;

        KeyEvent event = new KeyEvent();
        event.code = input_event[2];
        if (!event.code.contains("KEY_")) return;

        KeymapConfig keymapConfig = mInput.getKeymapConfig();

        switch (input_event[3]) {
            case "UP":
                event.action = UP;
                break;
            case "DOWN":
                event.action = DOWN;
                break;
            default:
                return;
        }

        int i = Utils.obtainIndex(event.code);
        if (i > 0) { // A-Z and 0-9 keys
            if (event.action == DOWN) handleKeyboardShortcuts(i);
            handleMouseAim(i, event.action);

            if (dpad2Handler != null) // Dpad with WASD keys
                dpad2Handler.handleEvent(event.code, event.action);

        } else { // CTRL, ALT, Arrow keys
            if (dpad1Handler != null)  // Dpad with arrow keys
                dpad1Handler.handleEvent(event.code, event.action);

            if (event.code.equals("KEY_GRAVE") && event.action == DOWN)
                if (keymapConfig.keyGraveMouseAim)
                    mInput.getMouseEventHandler().triggerMouseAim();
        }
        if (event.code.contains("CTRL")) ctrlKeyPressed = event.action == DOWN;
        if (event.code.contains("ALT")) altKeyPressed = event.action == DOWN;

        for (KeymapProfileKey key : mInput.getKeymapProfile().keys)
            if (event.code.equals(key.code))
                mInput.injectEvent(key.x, key.y, event.action, pidProvider.getPid(key.code));

        for (SwipeKeyHandler swipeKeyHandler : swipeKeyHandlers)
            swipeKeyHandler.handleEvent(event, mInput, eventHandler, pidProvider, keymapConfig.swipeDelayMs);
    }

    private void handleKeyboardShortcuts(int keycode) throws RemoteException {
        final String modifier = ctrlKeyPressed ? KEY_CTRL : KEY_ALT;
        KeymapConfig keymapConfig = mInput.getKeymapConfig();

        if (keymapConfig.launchEditorShortcutKeyModifier.equals(modifier))
            if (keycode == keymapConfig.launchEditorShortcutKey)
                mInput.getCallback().launchEditor();

        if (keymapConfig.pauseResumeShortcutKeyModifier.equals(modifier))
            if (keycode == keymapConfig.pauseResumeShortcutKey)
                RemoteService.pauseKeymap();

        if (keymapConfig.switchProfileShortcutKeyModifier.equals(modifier))
            if (keycode == keymapConfig.switchProfileShortcutKey)
                mInput.reloadKeymap();
    }

    private void handleMouseAim(int keycode, int action) {
        KeymapConfig keymapConfig = mInput.getKeymapConfig();
        if (keycode == keymapConfig.mouseAimShortcutKey)
            if (action == DOWN && keymapConfig.mouseAimToggle) mInput.getMouseEventHandler().triggerMouseAim();
            else mInput.getMouseEventHandler().triggerMouseAim();
    }
}
