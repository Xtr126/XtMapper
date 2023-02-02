package xtr.keymapper;

import static xtr.keymapper.InputEventCodes.*;
import static xtr.keymapper.TouchPointer.PointerId.*;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;

import xtr.keymapper.activity.InputDeviceSelector;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.mouse.MouseAimHandler;
import xtr.keymapper.mouse.MousePinchZoom;
import xtr.keymapper.mouse.MouseWheelZoom;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.dpad.DpadHandler;
import xtr.keymapper.server.InputService;

public class TouchPointer extends Service {

    private View cursorView;
    private WindowManager mWindowManager;
    int x1 = 100, y1 = 100;
    private Float[] keysX, keysY;
    private DpadHandler dpad1Handler, dpad2Handler;
    private MouseAimHandler mouseAimHandler;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final MouseEventHandler mouseEventHandler = new MouseEventHandler();
    private final KeyEventHandler keyEventHandler = new KeyEventHandler();
    private KeymapConfig keymapConfig;
    boolean pointer_down;
    public IRemoteService mService;
    public boolean connected = false;
    public MainActivity.Callback activityCallback;
    int width; int height;

    private final IBinder binder = new TouchPointerBinder();

    public class TouchPointerBinder extends Binder {
        public TouchPointer getService() {
            // Return this instance of TouchPointer so clients can call public methods
            return TouchPointer.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void init(){
        loadKeymap();
        getDisplayMetrics();

        activityCallback.updateCmdView1("\n connecting to server..");
        
        mHandler.post(new Runnable() {
            int counter = 5;
            @Override
            public void run() {
                activityCallback.updateCmdView1(".");
                mService = InputService.getInstance();

                if (mService != null) {
                    keyEventHandler.init();
                    mouseEventHandler.init();
                    startInputDeviceSelector();
                    connected = true;
                } else {
                    if (counter > 0) {
                        mHandler.postDelayed(this, 1000);
                        counter--;
                    } else {
                        mHandler.post(() -> stopPointer());
                        activityCallback.updateCmdView1("\n connection timeout\n Please retry activation \n");
                    }
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startNotification();
        if (cursorView != null) mWindowManager.removeView(cursorView);
        LayoutInflater layoutInflater = getSystemService(LayoutInflater.class);
        mWindowManager = getSystemService(WindowManager.class);
        // Inflate the layout for the cursor
        cursorView = CursorBinding.inflate(layoutInflater).getRoot();

        // set the layout parameters of the cursor
        WindowManager.LayoutParams mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Don't let the cursor grab the input focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                // Make the underlying application window visible
                // through the cursor
                PixelFormat.TRANSLUCENT);

        if(cursorView.getWindowToken()==null)
            if (cursorView.getParent() == null)
                mWindowManager.addView(cursorView, mParams);
        return super.onStartCommand(intent, flags, startId);
    }

    public void stopPointer() {
        if (activityCallback != null) {
            activityCallback.stopPointer();
        } else {
            hideCursor();
            stopSelf();
        }
    }

    private void startNotification() {
        String CHANNEL_ID = "pointer_service";
        String name = "Overlay";
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
        
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        Intent intent = new Intent(this, EditorService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        Notification notification = builder.setOngoing(true)
                .setContentTitle("Keymapper service running")
                .setContentText("Touch to launch editor")
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(2, notification);
    }

    public void hideCursor() {
        connected = false;

        if (mService != null) {
            try {
                mService.removeCallback(mCallback);
                mService.removeOnMouseEventListener(mOnMouseEventListener);
                mService.unregisterOnKeyEventListener(mOnKeyEventListener);
                mService = null;
            } catch (RemoteException ignored) {
            }
        }
        if (cursorView != null) {
            mWindowManager.removeView(cursorView);
            cursorView.invalidate();
            cursorView = null;
        }
    }

    public void loadKeymap() {
        keymapConfig = new KeymapConfig(this).loadSharedPrefs();
        try {
            keymapConfig.loadConfig();
        } catch (IOException e) {
            Log.e("loadKeymap", e.toString());
        }

        keysX = keymapConfig.getX();
        keysY = keymapConfig.getY();

        if (keymapConfig.dpad1 != null)
            dpad1Handler = new DpadHandler(this, keymapConfig.dpad1, dpad1pid.id);
        if (keymapConfig.dpad2 != null)
            dpad2Handler = new DpadHandler(this, keymapConfig.dpad2, dpad2pid.id);
        if (keymapConfig.mouseAimConfig != null)
            mouseAimHandler = new MouseAimHandler(keymapConfig.mouseAimConfig);

        mouseEventHandler.sensitivity = keymapConfig.mouseSensitivity.intValue();
        mouseEventHandler.scroll_speed_multiplier = keymapConfig.scrollSpeed.intValue();
        mouseEventHandler.ctrl_mouse_wheel_zoom = keymapConfig.ctrlMouseWheelZoom;
        mouseEventHandler.ctrl_mouse_drag_gesture = keymapConfig.ctrlDragMouseGesture;

        keyEventHandler.stop_service = keymapConfig.stopServiceShortcutKey;
        keyEventHandler.launch_editor = keymapConfig.launchEditorShortcutKey;
    }

    public void sendSettingstoServer() throws RemoteException {
        checkRootAccess();
        int result = mService.tryOpenDevice(keymapConfig.device);
        if ( result < 0 ) {
            startInputDeviceSelector();
        } else {
            mService.setScreenSize(width, height);
            mService.startServer();
            mService.setCallback(mCallback);
            mService.setOnMouseEventListener(mOnMouseEventListener);
            mService.registerOnKeyEventListener(mOnKeyEventListener);
        }
    }

    private void checkRootAccess() throws RemoteException {
        if (mService.isRoot()) mHandler.post(() -> {
            mWindowManager.removeView(cursorView);
            cursorView.invalidate();
            cursorView = null;
        });
    }

    private void startInputDeviceSelector() {
        Intent intent = new Intent(TouchPointer.this, InputDeviceSelector.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        startActivity(intent);
    }

    public enum PointerId {
        // pointer id 0-35 reserved for keyboard events

        pid1 (36), // pointer id 36 and 37 reserved for mouse events
        pid2 (37),
        dpad1pid (38),
        dpad2pid (39);

        PointerId(int i) {
            id = i;
        }
        public final int id;
    }

    private final IRemoteServiceCallback mCallback = new IRemoteServiceCallback.Stub() {
        /* calling back from remote service to reload keymap */
        public void loadKeymap() {
            TouchPointer.this.loadKeymap();
            keyEventHandler.init();
            mouseEventHandler.init();
        }
    };

    private final OnKeyEventListener mOnKeyEventListener = new OnKeyEventListener.Stub() {
        @Override
        public void onKeyEvent(String event) throws RemoteException {
            keyEventHandler.handleEvent(event);
        }
    };

    private final OnMouseEventListener mOnMouseEventListener = new OnMouseEventListener.Stub() {
        @Override
        public void onMouseEvent(int code, int value) throws RemoteException {
            mouseEventHandler.handleEvent(code, value);
        }
    };

    private void getDisplayMetrics() {
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size); // TODO: getRealSize() deprecated in API level 31
        width = size.x;
        height = size.y;
    }

    private class KeyEventHandler {
        boolean ctrlKeyPressed = false;
        int stop_service, launch_editor;

        private void init() {
            if (dpad1Handler != null) dpad1Handler.setInterface(mService);
            if (dpad2Handler != null) dpad2Handler.setInterface(mService);
        }

        private class KeyEvent {
            String label;
            int action;
        }

        private void handleEvent(String line) throws RemoteException {
            // line: /dev/input/event3 EV_KEY KEY_X DOWN
            String[] input_event = line.split("\\s+");
            if(!input_event[1].equals("EV_KEY")) return;

            if (activityCallback != null) activityCallback.updateCmdView2(line + "\n");

            KeyEvent event = new KeyEvent();
            event.label = input_event[2];

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

            int i = Utils.obtainIndex(event.label); // Strips off KEY_ from KEY_X and return the index of X in alphabet
            if (i >= 0 && i <= 35) { // A-Z and 0-9 only in this range
                if (keysX != null && keysX[i] != null) { // null if keymap not set
                    mService.injectEvent(keysX[i], keysY[i], event.action, i);
                } else if (dpad2Handler != null) { // Dpad with WASD keys
                    dpad2Handler.handleEvent(event.label, event.action);
                }
                // Keyboard shortcuts
                if (event.action == DOWN) {
                    if (i == stop_service) stopPointer();
                    if (i == launch_editor) startService(new Intent(TouchPointer.this, EditorService.class));
                }
            } else {
                switch (event.label) {
                    default:
                        if (dpad1Handler != null)  // Dpad with arrow keys
                            dpad1Handler.handleEvent(event.label, event.action);
                    break;
                    case "KEY_GRAVE":
                        if (event.action == DOWN) mouseEventHandler.triggerMouseAim();
                    break;
                    case "KEY_LEFTCTRL":
                        ctrlKeyPressed = event.action == DOWN;
                    break;
                }
            }
        }
    }

    private class MouseEventHandler {
        int sensitivity = 1;
        int scroll_speed_multiplier = 1;
        boolean ctrl_mouse_wheel_zoom, ctrl_mouse_drag_gesture;
        private MousePinchZoom pinchZoom;
        private MouseWheelZoom scrollZoomHandler;
        private final int pointerId = pid1.id;

        private void triggerMouseAim() throws RemoteException {
            if (mouseAimHandler != null) {
                mouseAimHandler.active = !mouseAimHandler.active;
                if (mouseAimHandler.active) {
                    mouseAimHandler.resetPointer();
                    // Notifying user that shooting mode was activated
                    mHandler.post(() -> Toast.makeText(TouchPointer.this, R.string.mouse_aim_activated, Toast.LENGTH_LONG).show());
                }
            }
        }

        private void init() {
            if (mouseAimHandler != null) {
                mouseAimHandler.setInterface(mService);
                mouseAimHandler.setDimensions(width, height);
            }
            if (ctrl_mouse_wheel_zoom) scrollZoomHandler = new MouseWheelZoom(mService);
        }

        private void movePointer() { mHandler.post(() -> {
            if (cursorView != null) {
                cursorView.setX(x1);
                cursorView.setY(y1);
            }
        });}

        private void handleEvent(int code, int value) throws RemoteException {
            if (mouseAimHandler != null && mouseAimHandler.active) {
                mouseAimHandler.handleEvent(code, value);
                return;
            }
            if (keyEventHandler.ctrlKeyPressed && pointer_down && ctrl_mouse_drag_gesture) {
                pointer_down = pinchZoom.handleEvent(code, value);
                return;
            }

            switch (code) {
                case REL_X: {
                    if (value == 0) break;
                    value *= sensitivity;
                    x1 += value;
                    if (x1 > width || x1 < 0) x1 -= value;
                    if (pointer_down) mService.injectEvent(x1, y1, MOVE, pointerId);
                    else mService.moveCursorX(x1);
                    break;
                }
                case REL_Y: {
                    if (value == 0) break;
                    value *= sensitivity;
                    y1 += value;
                    if (y1 > height || y1 < 0) y1 -= value;
                    if (pointer_down) mService.injectEvent(x1, y1, MOVE, pointerId);
                    else mService.moveCursorY(y1);
                    break;
                }
                case BTN_MOUSE:
                    pointer_down = value == 1;
                    if (keyEventHandler.ctrlKeyPressed && ctrl_mouse_drag_gesture) {
                        pinchZoom = new MousePinchZoom(mService, x1, y1);
                        pinchZoom.handleEvent(code, value);
                    } else mService.injectEvent(x1, y1, value, pointerId);
                    break;

                case BTN_RIGHT:
                    if (value == 1) triggerMouseAim();
                    break;

                case REL_WHEEL:
                    if (keyEventHandler.ctrlKeyPressed && ctrl_mouse_wheel_zoom)
                        scrollZoomHandler.onScrollEvent(value, x1, y1);
                    else
                        mService.injectScroll(x1, y1, value * scroll_speed_multiplier);
                    break;
            }
            if (code == REL_X || code == REL_Y) movePointer();
        }
    }
}
