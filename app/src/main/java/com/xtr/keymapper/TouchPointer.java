package com.xtr.keymapper;

import static android.content.Context.WINDOW_SERVICE;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xtr.keymapper.activity.InputDeviceSelector;
import com.xtr.keymapper.activity.MainActivity;
import com.xtr.keymapper.dpad.Dpad1Handler;
import com.xtr.keymapper.dpad.Dpad2Handler;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class TouchPointer {

    // declaring required variables
    private final Context context;
    private final View cursorView;
    private final WindowManager.LayoutParams mParams;
    private final WindowManager mWindowManager;
    int x1 = 100;
    int y1 = 100;
    private Float[] keysX;
    private Float[] keysY;
    public final TextView cmdView3;
    final Button startButton;
    private StringBuilder c3;
    boolean pointer_down = false;
    private int counter = 0;
    private Dpad1Handler dpad1Handler;
    private Dpad2Handler dpad2Handler;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Handler connectionHandler;
    private final MouseEventHandler mouseEventHandler = new MouseEventHandler();
    private final KeyEventHandler keyEventHandler = new KeyEventHandler();
    private HandlerThread handlerThread;
    private boolean connected = false;

    public TouchPointer(Context context){
        this.context= context;
        cmdView3 = ((MainActivity)context).findViewById(R.id.cmdview3);
        startButton  = ((MainActivity)context).findViewById(R.id.startPointer);
        // set the layout parameters of the cursor
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Don't let the cursor grab the input focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                // Make the underlying application window visible
                // through the cursor
                PixelFormat.TRANSLUCENT);

        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        cursorView = layoutInflater.inflate(R.layout.cursor, new LinearLayout(context),false);
        mParams.gravity = Gravity.CENTER;
        mWindowManager = (WindowManager)context.getSystemService(WINDOW_SERVICE);

        mHandler.post(new Runnable() {
            public void run() {
                cmdView3.setText(c3);
                mHandler.postDelayed(this, Server.REFRESH_INTERVAL);
            }
        });
    }

    public void open() {
        c3 = new StringBuilder();
        ((MainActivity)context).setButtonActive(startButton);
        startButton.setOnClickListener(v -> hideCursor());

        if(cursorView.getWindowToken()==null)
           if (cursorView.getParent() == null) {
               mWindowManager.addView(cursorView, mParams);
               handlerThread = new HandlerThread("connect");
               handlerThread.start();
               connectionHandler = new Handler(handlerThread.getLooper());
               ((MainActivity)context).server.c1 = new StringBuilder();
                try {
                    loadKeymap();
                } catch (IOException e) {
                    updateCmdView("warning: keymap not set");
                }
               startHandlers();
           }

    }

    public void hideCursor() {
        ((MainActivity)context).setButtonInactive(startButton);
        startButton.setOnClickListener(view -> open());

        Server.killServer().start();
        ((WindowManager)context.getSystemService(WINDOW_SERVICE)).removeView(cursorView);
        handlerThread.quit();
        cursorView.invalidate();
    }

    public void loadKeymap() throws IOException {
        KeymapConfig keymapConfig = new KeymapConfig(context);
        keymapConfig.loadConfig();

        keysX = keymapConfig.getX();
        keysY = keymapConfig.getY();

        if (keymapConfig.dpad1 != null)
            dpad1Handler = new Dpad1Handler(keymapConfig.dpad1);
        if (keymapConfig.dpad2 != null)
            dpad2Handler = new Dpad2Handler(keymapConfig.dpad2);
    }

    public void updateCmdView3(String s){
        if(counter < Server.MAX_LINES) {
            c3.append(s).append("\n");
            counter++;
        } else {
            counter = 0;
            c3 = new StringBuilder();
        }
    }

    private void updateCmdView(String s) {
        ((MainActivity)context).server.updateCmdView1(s);
    }

    private void startHandlers() {
        Server server = ((MainActivity)context).server;
        server.c1.append("\n connecting to server..");
        connectionHandler.post(new Runnable() {
            int counter = 5;
            @Override
            public void run() {
                server.c1.append(".");
                try {
                    keyEventHandler.connect();
                    mouseEventHandler.connect();
                } catch (IOException ignored) {
                }
                if (connected) {
                    new Thread(keyEventHandler::start).start();
                    new Thread(mouseEventHandler::start).start();
                } else {
                    if (counter > 0) {
                        connectionHandler.postDelayed(this, 1000);
                        counter--;
                    } else {
                        mHandler.post(() -> hideCursor());
                        server.c1.append("\n connection timeout\n Please retry activation \n");
                    }
                }
            }
        });
    }

    private class KeyEventHandler {
        private Socket evSocket;
        private DataOutputStream xOut;
        private BufferedReader getevent;
        private PrintWriter pOut;

        private void connect() throws IOException {
            evSocket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2);
            pOut = new PrintWriter(evSocket.getOutputStream());
            pOut.println("getevent"); pOut.flush();

            Socket socket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT);
            xOut = new DataOutputStream(socket.getOutputStream());
        }

        private void stop() throws IOException {
            pOut.close();
            xOut.close();
            getevent.close();
        }

        private void start()  {
            try {
                if (dpad1Handler != null) dpad1Handler.setOutputStream(xOut);
                if (dpad2Handler != null) dpad2Handler.setOutputStream(xOut);

                getevent = new BufferedReader(new InputStreamReader(evSocket.getInputStream()));
                String event;
                while ((event = getevent.readLine()) != null) { //read events
                    String[] input_event = event.split("\\s+"); // Keyboard input: /dev/input/event3 EV_KEY KEY_X DOWN
                    if (input_event.length < 3) break; // Avoid ArrayIndexOutOfBoundsException
                    TouchPointer.this.updateCmdView(event);
                    if (input_event[3].equals("DOWN") || input_event[3].equals("UP")) {
                        int i = Utils.obtainIndex(input_event[2]); // Strips off KEY_ from KEY_X and return the index of X in alphabet
                        if (i >= 0 && i <= 35) { // A-Z and 0-9 only in this range
                            if (keysX != null && keysX[i] != null) { // null if keymap not set
                                xOut.writeBytes(keysX[i] + " " + keysY[i] + " " + input_event[3] + " " + i + "\n"); // Send coordinates to remote server to simulate touch
                            } else if (dpad2Handler != null) {
                                dpad2Handler.sendEvent(input_event[2], input_event[3]);
                            }
                        } else if (dpad1Handler != null) {
                            dpad1Handler.sendEvent(input_event[2], input_event[3]);
                        }
                    }
                }
                stop();
            } catch (IOException e) {
                updateCmdView(e.toString());
            }
        }
    }

    private class MouseEventHandler {

        private Socket mouseSocket;
        private Socket xOutSocket;
        private DataOutputStream xOut;
        private BufferedReader in;
        private PrintWriter out;
        int width; int height;
        int x2; int  y2;
        String line; String[] input_event;

        private void connect() throws IOException {
            mouseSocket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2);
            out = new PrintWriter(mouseSocket.getOutputStream());
            out.println("mouse_read"); out.flush();

            xOutSocket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT);
            xOut = new DataOutputStream(xOutSocket.getOutputStream());
            connected = true;
        }

        private void start() {
            getDimensions();
            try {
                pointerGrab();
                handleEvents();
                stop();
            } catch (IOException e) {
                updateCmdView(e.toString());
            }
        }

        private void stop() throws IOException {
            in.close(); out.close();
            mouseSocket.close(); xOutSocket.close();
            connected = false;
        }

        private void getDimensions() {
            Display display = mWindowManager.getDefaultDisplay();
            Point size = new Point();
            display.getRealSize(size); // TODO: getRealSize() deprecated in API level 31
            width = size.x;
            height = size.y;
            x2 = width / 80;
            y2 = height / 100;
        }

        private void pointerGrab() throws IOException {
            xOut.writeBytes( "_ " + true + " ioctl" + " 0\n");
        }

        private void movePointer() {
            mHandler.post(() -> {
                cursorView.setX(x1);
                cursorView.setY(y1);
            });
        }

        private void handleEvents() throws IOException {
            in = new BufferedReader(new InputStreamReader(mouseSocket.getInputStream()));
            while ((line = in.readLine()) != null) {
                updateCmdView3("socket: " + line);
                input_event = line.split("\\s+");
                switch (input_event[0]) {
                    case "REL_X": {
                        x1 += Integer.parseInt(input_event[1]);
                        if ( x1 < 0 ) x1 -= Integer.parseInt(input_event[1]);
                        if ( x1 > width ) x1 -= Integer.parseInt(input_event[1]);
                        if (pointer_down)
                            xOut.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + "MOVE" + " 36" + "\n");
                        break;
                    }
                    case "REL_Y": {
                        y1 += Integer.parseInt(input_event[1]);
                        if ( y1 < 0 ) y1 -= Integer.parseInt(input_event[1]);
                        if ( y1 > height ) y1 -= Integer.parseInt(input_event[1]);
                        if (pointer_down) {
                            xOut.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + "MOVE" + " 36" + "\n");
                        }
                        break;
                    }
                    case "BTN_MOUSE": {
                        pointer_down = input_event[1].equals("1");
                        xOut.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + input_event[1] + " 36" + "\n");
                        break;
                    }
                    case "error": {
                        context.startActivity(new Intent(context, InputDeviceSelector.class));
                        break;
                    }
                    case "restart": {
                        //in.close();
                        stop();
                        connect();
                        start();
                        break;
                    }
                }
                movePointer();
            }
        }
    }
}
