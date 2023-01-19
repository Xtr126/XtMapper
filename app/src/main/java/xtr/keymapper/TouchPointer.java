package xtr.keymapper;

import static xtr.keymapper.InputEventCodes.BTN_MOUSE;
import static xtr.keymapper.InputEventCodes.BTN_RIGHT;
import static xtr.keymapper.InputEventCodes.REL_WHEEL;
import static xtr.keymapper.InputEventCodes.REL_X;
import static xtr.keymapper.InputEventCodes.REL_Y;
import static xtr.keymapper.TouchPointer.PointerId.dpad1pid;
import static xtr.keymapper.TouchPointer.PointerId.dpad2pid;
import static xtr.keymapper.TouchPointer.PointerId.pid1;
import static xtr.keymapper.server.InputService.DOWN;
import static xtr.keymapper.server.InputService.MOVE;
import static xtr.keymapper.server.InputService.UP;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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

import androidx.annotation.Nullable;

import java.io.IOException;

import xtr.keymapper.activity.InputDeviceSelector;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.aim.MouseAimHandler;
import xtr.keymapper.aim.MousePinchZoom;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.dpad.DpadHandler;
import xtr.keymapper.server.InputService;

public class TouchPointer extends Service {

    // declaring required variables
    private Context context;
    private View cursorView;
    private WindowManager mWindowManager;
    int x1 = 100, y1 = 100;
    private Float[] keysX, keysY;
    private int counter = 0;
    @Nullable
    private DpadHandler dpad1Handler, dpad2Handler;
    private MouseAimHandler mouseAimHandler;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final MouseEventHandler mouseEventHandler = new MouseEventHandler();
    private final KeyEventHandler keyEventHandler = new KeyEventHandler();
    private KeymapConfig keymapConfig;
    boolean pointer_down;
    public IRemoteService mService;
    public boolean connected = false;

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

    public void init(Context context){
        this.context= context;

        try {
            loadKeymap();
        } catch (IOException e) {
            ((MainActivity)context).server.updateCmdView1("warning: keymap not set");
        }
        startHandlers();
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
            if (cursorView.getParent() == null) {
                mWindowManager.addView(cursorView, mParams);
            }
        return super.onStartCommand(intent, flags, startId);
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
                mService.closeDevice();
                mService.unregisterCallback(mCallback);
                mService = null;
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if (cursorView != null) {
            mWindowManager.removeView(cursorView);
            cursorView.invalidate();
            cursorView = null;
        }
    }

    public void loadKeymap() throws IOException {
        keymapConfig = new KeymapConfig(context);
        keymapConfig.loadConfig();

        keysX = keymapConfig.getX();
        keysY = keymapConfig.getY();

        if (keymapConfig.dpad1 != null)
            dpad1Handler = new DpadHandler(context, keymapConfig.dpad1, dpad1pid.id);
        if (keymapConfig.dpad2 != null)
            dpad2Handler = new DpadHandler(context, keymapConfig.dpad2, dpad2pid.id);
        if (keymapConfig.mouseAimConfig != null)
            mouseAimHandler = new MouseAimHandler(keymapConfig.mouseAimConfig);

        mouseEventHandler.sensitivity = keymapConfig.getMouseSensitivity().intValue();
    }


    private void updateCmdView2(String s) {
        if(counter < Server.MAX_LINES) {
            ((MainActivity)context).c2.append(s).append("\n");
            counter++;
        } else {
            counter = 0;
            ((MainActivity)context).c2 = new StringBuilder();
        }
    }

    private void startHandlers() {
        StringBuilder c1 = ((MainActivity)context).c1;
        c1.append("\n connecting to server..");
        mHandler.post(new Runnable() {
            int counter = 5;
            @Override
            public void run() {
                c1.append(".");
                mService = InputService.getInstance();

                if (mService != null) {
                    keyEventHandler.init();
                    mouseEventHandler.init();
                    context.startActivity(new Intent(context, InputDeviceSelector.class));
                    connected = true;
                } else {
                    if (counter > 0) {
                        mHandler.postDelayed(this, 1000);
                        counter--;
                    } else {
                        mHandler.post(() -> ((MainActivity)context).stopPointer());
                        c1.append("\n connection timeout\n Please retry activation \n");
                    }
                }
            }
        });
    }

    public void sendSettingstoServer() throws RemoteException {
        String device = keymapConfig.getDevice();
        int result = mService.tryOpenDevice(device);
        if ( result < 0 ) {
            context.startActivity(new Intent(this, InputDeviceSelector.class));
        } else {
            mService.startServer();
            mService.registerCallback(mCallback);
        }
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

    public final IRemoteServiceCallback mCallback = new IRemoteServiceCallback.Stub() {
        @Override
        public void onMouseEvent(int code, int value) throws RemoteException {
            mouseEventHandler.handleEvent(code, value);
        }

        @Override
        public void receiveEvent(String event) throws RemoteException {
            keyEventHandler.handleEvent(event);
        }

        /* calling back from remote service to reload keymap */
        public void loadKeymap() {
            try {
                TouchPointer.this.loadKeymap();
                keyEventHandler.init();
                mouseEventHandler.init();
            } catch (IOException e) {
                Log.i("editor", e.toString());
            }
        }
    };

    private class KeyEventHandler {
        boolean ctrlKeyPressed = false;

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
            TouchPointer.this.updateCmdView2(line);
            if (cursorView == null) return;

            KeyEvent event = new KeyEvent();
            String[] input_event = line.split("\\s+");
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
            } else {
                switch (event.label) {
                    default:
                        if (dpad1Handler != null)  // Dpad with arrow keys
                            dpad1Handler.handleEvent(event.label, event.action);
                    break;
                    case "KEY_GRAVE":
                        if(event.action == DOWN) mouseEventHandler.triggerMouseAim();
                    break;
                    case "KEY_LEFTCTRL":
                        if (event.action == DOWN) {
                            mouseEventHandler.pinchZoom = new MousePinchZoom(mService, x1, y1);
                            ctrlKeyPressed = true;
                        } else {
                            ctrlKeyPressed = false;
                            mouseEventHandler.pinchZoom.releasePointers();
                            mouseEventHandler.pinchZoom = null;
                        }
                    break;
                }
            }
        }
    }

    private class MouseEventHandler {
        int width; int height;
        int sensitivity;
        private MousePinchZoom pinchZoom;

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
            if (mouseAimHandler != null) mouseAimHandler.setInterface(mService);
            getDimensions();
        }

        private void getDimensions() {
            Display display = mWindowManager.getDefaultDisplay();
            Point size = new Point();
            display.getRealSize(size); // TODO: getRealSize() deprecated in API level 31
            width = size.x;
            height = size.y;
            if (mouseAimHandler != null) mouseAimHandler.setDimensions(width, height);
        }

        private void movePointer() {
            mHandler.post(() -> {
                if (cursorView != null) {
                    cursorView.setX(x1);
                    cursorView.setY(y1);
                }
            });
        }

        private void handleEvent(int code, int value) throws RemoteException {
            final int pointerId = pid1.id;
            if (cursorView == null) return;
            if (mouseAimHandler != null && mouseAimHandler.active) {
                mouseAimHandler.handleEvent(code, value);
                return;
            }
            if (keyEventHandler.ctrlKeyPressed) {
                pinchZoom.handleEvent(code, value);
                return;
            }

            switch (code) {
                case REL_X: {
                    if (value == 0) break;
                    value *= sensitivity;
                    x1 += value;
                    if (x1 > width || x1 < 0) x1 -= value;
                    if (pointer_down) mService.injectEvent(x1, y1, MOVE, pointerId);
                    movePointer();
                    break;
                }
                case REL_Y: {
                    if (value == 0) break;
                    value *= sensitivity;
                    y1 += value;
                    if (y1 > height || y1 < 0) y1 -= value;
                    if (pointer_down) mService.injectEvent(x1, y1, MOVE, pointerId);
                    movePointer();
                    break;
                }
                case BTN_MOUSE:
                    pointer_down = value == 1;
                    mService.injectEvent(x1, y1, value, pointerId);
                    break;

                case BTN_RIGHT:
                    if (value == 1) triggerMouseAim();
                    break;

                case REL_WHEEL:
                    mService.injectScroll(x1, y1, value);
                    break;
            }
        }
    }
}
