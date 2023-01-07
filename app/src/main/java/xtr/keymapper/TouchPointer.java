package xtr.keymapper;

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
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import xtr.keymapper.activity.InputDeviceSelector;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.aim.MouseAimHandler;
import xtr.keymapper.aim.MouseAimHandler.MouseEvent;
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
    public StringBuilder c3, c1;
    private int counter = 0;
    private DpadHandler dpad1Handler, dpad2Handler;
    private MouseAimHandler mouseAimHandler;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Handler connectionHandler;
    private final MouseEventHandler mouseEventHandler = new MouseEventHandler();
    private final KeyEventHandler keyEventHandler = new KeyEventHandler();
    private HandlerThread handlerThread;
    private KeymapConfig keymapConfig;
    boolean pointer_down;
    public boolean connected = false;
    private IRemoteService input;

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
        this.c1 = ((MainActivity)context).c1;
        c3 = new StringBuilder();

        if (!connected) {
            try {
                loadKeymap();
            } catch (IOException e) {
                updateCmdView("warning: keymap not set");
            }
            handlerThread = new HandlerThread("connect");
            handlerThread.start();
            connectionHandler = new Handler(handlerThread.getLooper());
            startHandlers();
        }
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
        mParams.gravity = Gravity.CENTER;

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
        mWindowManager.removeView(cursorView);
        cursorView.invalidate();
        cursorView = null;
        try {
            keyEventHandler.stop();
            mouseEventHandler.stop();
            handlerThread.quit();
        } catch (IOException e) {
            Log.e("stop", e.toString());
        }
        new Thread(Server::stopServer).start();
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
    }

    public void updateCmdView3(String s){
        if(counter < Server.MAX_LINES) {
            c3.append(s).append("\n");
            counter++;
        } else {
            counter = 0;
            c3 = new StringBuilder();
        }
        ((MainActivity)context).c3 = this.c3;
    }

    private void updateCmdView(String s) {
        if(counter < Server.MAX_LINES) {
            c1.append(s).append("\n");
            counter++;
        } else {
            counter = 0;
            c1 = new StringBuilder();
        }
        ((MainActivity)context).c1 = this.c1;
    }

    private void startHandlers() {
        c1.append("\n connecting to server..");
        connectionHandler.post(new Runnable() {
            int counter = 5;
            @Override
            public void run() {
                c1.append(".");
                try {
                    keyEventHandler.connect();
                    mouseEventHandler.connect();
                } catch (IOException ignored) {
                }
                if (connected && (input = InputService.getInstance()) != null) {
                    new Thread(keyEventHandler::start).start();
                    new Thread(mouseEventHandler::start).start();
                } else {
                    if (counter > 0) {
                        connectionHandler.postDelayed(this, 1000);
                        counter--;
                    } else {
                        mHandler.post(() -> ((MainActivity)context).stopPointer());
                        c1.append("\n connection timeout\n Please retry activation \n");
                    }
                }
            }
        });
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

    private class KeyEventHandler {
        private Socket evSocket;
        private BufferedReader getevent;
        private PrintWriter pOut;

        private class KeyEvent {
            String label;
            int action;
            KeyEvent(String line){
                String[] input_event = line.split("\\s+");
                label = input_event[2];
                switch (input_event[3]) {
                    case "UP":
                        action = UP;
                        break;
                    case "DOWN":
                        action = DOWN;
                        break;
                }
            }
        }

        private void connect() throws IOException {
            evSocket = new Socket("127.0.0.1", Server.DEFAULT_PORT_2);
            pOut = new PrintWriter(evSocket.getOutputStream());
            pOut.println("getevent"); pOut.flush();
        }

        private void stop() throws IOException {
            if (evSocket != null) {
                pOut.close();
                evSocket.close();
                getevent.close();
            }
        }

        private void start()  {
            try {
                if (dpad1Handler != null) dpad1Handler.setInterface(input);
                if (dpad2Handler != null) dpad2Handler.setInterface(input);

                getevent = new BufferedReader(new InputStreamReader(evSocket.getInputStream()));
                String line;
                while ((line = getevent.readLine()) != null) { //read events
                    // line: /dev/input/event3 EV_KEY KEY_X DOWN
                    TouchPointer.this.updateCmdView(line);
                    if (cursorView == null) break;
                    KeyEvent event = new KeyEvent(line);
                    int i = Utils.obtainIndex(event.label); // Strips off KEY_ from KEY_X and return the index of X in alphabet
                    if (i >= 0 && i <= 35) { // A-Z and 0-9 only in this range
                        if (keysX != null && keysX[i] != null) { // null if keymap not set
                            input.injectEvent(keysX[i], keysY[i], event.action, i);
                        } else if (dpad2Handler != null) { // Dpad with WASD keys
                            dpad2Handler.handleEvent(event.label, event.action);
                        }
                    } else {
                        if (dpad1Handler != null)  // Dpad with arrow keys
                            dpad1Handler.handleEvent(event.label, event.action);

                        if (event.label.equals("KEY_GRAVE") && event.action == DOWN)
                            mouseEventHandler.triggerMouseAim();
                    }
                }
            } catch (IOException | RemoteException e) {
                updateCmdView(e.toString());
            }
        }
    }

    private class MouseEventHandler {

        private Socket mouseSocket;
        private BufferedReader in;
        private PrintWriter out;
        int width; int height;

        private void connect() throws IOException {
            sendSettingstoServer();
            mouseSocket = new Socket("127.0.0.1", Server.DEFAULT_PORT_2);
            out = new PrintWriter(mouseSocket.getOutputStream());
            out.println("mouse_read"); out.flush();
            connected = true;
        }

        private void sendSettingstoServer() throws IOException {
            String device = keymapConfig.getDevice();
            float sensitivity = keymapConfig.getMouseSensitivity();
            Server.changeDevice(device);
            Server.changeSensitivity(sensitivity);
        }

        private void triggerMouseAim(){
            if (mouseAimHandler != null) {
                mouseAimHandler.active = !mouseAimHandler.active;
            }
        }

        private void start() {
            getDimensions();
            try {
                handleEvents();
                stop();
            } catch (IOException | RemoteException e) {
                updateCmdView(e.toString());
            }
        }

        private void stop() throws IOException {
            if (mouseSocket != null) {
                in.close();
                out.close();
                mouseSocket.close();
                connected = false;
            }
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
            if (cursorView == null) return;
            mHandler.post(() -> {
                cursorView.setX(x1);
                cursorView.setY(y1);
            });
        }

        private void handleEvents() throws IOException, RemoteException {
            in = new BufferedReader(new InputStreamReader(mouseSocket.getInputStream()));
            final int pointerId = pid1.id;

            String line;
            while ((line = in.readLine()) != null) {
                updateCmdView3("socket: " + line);
                if (cursorView == null) break;
                if (mouseAimHandler != null && mouseAimHandler.active) {
                    mouseAimHandler.setInterface(input);
                    mouseAimHandler.start(in);
                }

                MouseEvent event = new MouseEvent(line);
                switch (event.code) {
                    case "REL_X": {
                        if (event.value == 0) break;
                        x1 += event.value;
                        if (x1 > width || x1 < 0) x1 -= event.value;
                        if (pointer_down)
                            input.injectEvent(x1, y1, MOVE, pointerId);
                        break;
                    }
                    case "REL_Y": {
                        if (event.value == 0) break;
                        y1 += event.value;
                        if (y1 > height || y1 < 0) y1 -= event.value;
                        if (pointer_down)
                            input.injectEvent(x1, y1, MOVE, pointerId);
                        break;
                    }
                    case "BTN_MOUSE":
                        pointer_down = event.value == 1;
                        input.injectEvent(x1, y1, event.value, pointerId);
                        break;

                    case "BTN_RIGHT":
                        if (event.value == 1) triggerMouseAim();
                        break;

                    case "REL_WHEEL":
                        input.injectScroll(x1, y1, event.value);
                        break;

                    case "error":
                        context.startActivity(new Intent(context, InputDeviceSelector.class));
                        break;

                    case "restart":
                        stop(); connect(); start();
                        break;
                }
                movePointer();
            }
        }
    }
}
