package com.xtr.keymapper;

import static android.content.Context.WINDOW_SERVICE;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xtr.keymapper.activity.MainActivity;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
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
    private final Handler textViewUpdater = new Handler(Looper.getMainLooper());
    private Dpad1Handler dpad1Handler;

    public TouchPointer(Context context){
        this.context= context;
        cmdView3 = ((MainActivity)context).findViewById(R.id.cmdview3);
        c3 = new StringBuilder();
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

        textViewUpdater.post(new Runnable() {
            public void run() {
                cmdView3.setText(c3);
                textViewUpdater.postDelayed(this, Server.REFRESH_INTERVAL);
            }
        });
    }

    public void open() {
        ((MainActivity)context).setButtonActive(startButton);
        startButton.setOnClickListener(v -> {
            Server.killServer().start();
            hideCursor();
            ((MainActivity)context).setButtonInactive(startButton);
            startButton.setOnClickListener(view -> open());
        });

       if(cursorView.getWindowToken()==null)
           if (cursorView.getParent() == null) {
                mWindowManager.addView(cursorView, mParams);
                try {
                    loadKeymap();
                } catch (IOException e) {
                    e.printStackTrace();
                }
               new Thread(this::event_handler).start();
           }
    }

    private void event_handler() {
        try {
            Socket socket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT);
            DataOutputStream xOut = new DataOutputStream(socket.getOutputStream());
            if (dpad1Handler != null) dpad1Handler.setOutputStream(xOut);
            String line;
            BufferedReader getevent = Utils.geteventStream(context);
            while ((line = getevent.readLine()) != null) { //read events
                String[] xy = line.split("\\s+");
                // Keyboard input be like: /dev/input/event3 EV_KEY KEY_X DOWN
                // Mouse input be like: /dev/input/event2 EV_REL REL_X ffffffff
                updateCmdView(line);
                if (xy[3].equals("DOWN") || xy[3].equals("UP")) {
                    int i = Utils.obtainIndex(xy[2]);
                    // Strips off KEY_ from KEY_X and return the index of X in alphabet
                    if (i >= 0 && i <= 35) { // A-Z and 0-9 only in this range
                        if (keysX != null && keysX[i] != null) { // null if keymap not set
                            xOut.writeBytes(keysX[i] + " " + keysY[i] + " " + xy[3] + " " + i + "\n"); // Send coordinates to remote server to simulate touch
                        }
                    } else if (dpad1Handler != null) {
                        dpad1Handler.sendEvent(xy[2], xy[3]);
                    }
                }
                getevent.close();
            }
        } catch (IOException e) {
            updateCmdView("Unable to start overlay: server not started");
            hideCursor();
            Log.d("I/O Error",e.toString());
        }
    }

    public void hideCursor() {
        try {
            ((WindowManager)context.getSystemService(WINDOW_SERVICE)).removeView(cursorView);
            cursorView.invalidate();
            // remove all views
            ((ViewGroup) cursorView.getParent()).removeAllViews();
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (Exception e) {
            Log.e("Error2",e.toString());
        }
    }

    public void loadKeymap() throws IOException {
        KeymapConfig keymapConfig = new KeymapConfig(context);
        keymapConfig.loadConfig();

        keysX = keymapConfig.getX();
        keysY = keymapConfig.getY();

        if (keymapConfig.dpad1 != null)
            dpad1Handler = new Dpad1Handler(keymapConfig.dpad1);
    }

    public void updateCmdView3(String s){
        if(counter < Server.MAX_LINES_1) {
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

    public void startSocket() {
        try {
            ServerSocket serverSocket = new ServerSocket(MainActivity.DEFAULT_PORT_2);
            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    ((MainActivity)context).runOnUiThread(this::open);
                    new Thread(() -> {
                        try {
                            handleMouseEvents(socket);
                        } catch (IOException e) {
                            updateCmdView(e.toString());
                        }
                    }).start();
                } catch (IOException e) {
                    updateCmdView(e.toString());
                }
            }
        } catch (IOException e) {
            Log.e("I/O Error", e.toString());
        }
        updateCmdView("waiting for server...");
    }

    private void handleMouseEvents(Socket clientSocket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        updateCmdView("initialized: listening for events through socket");
        Socket socket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT);
        DataOutputStream xOut = new DataOutputStream(socket.getOutputStream());
        pointerGrab(xOut);
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        int width = size.x;
        int height = size.y;
        int x2 = width / 100;
        int y2 = height / 100;
        String line;
        while ((line = in.readLine()) != null) {
            updateCmdView3("socket: " + line);
                String []xy2 = line.split("\\s+");
                switch (xy2[0]) {
                    case "REL_X": {
                        x1 += Integer.parseInt(xy2[1]);
                        if ( x1 < 0 ) x1 -= Integer.parseInt(xy2[1]);
                        if ( x1 > width ) x1 -= Integer.parseInt(xy2[1]);
                        if (pointer_down)
                            xOut.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + "MOVE" + " 36" + "\n");
                        break;
                    }
                    case "REL_Y": {
                        y1 += Integer.parseInt(xy2[1]);
                        if ( y1 < 0 ) y1 -= Integer.parseInt(xy2[1]);
                        if ( y1 > height ) y1 -= Integer.parseInt(xy2[1]);
                        if (pointer_down) {
                            xOut.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + "MOVE" + " 36" + "\n");
                        }
                        break;
                    }
                    case "BTN_MOUSE": {
                        pointer_down = xy2[1].equals("1");
                        xOut.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + xy2[1] + " 36" + "\n");
                        break;
                    }
                }
                movePointer();

        }
        in.close();
        clientSocket.close();
        socket.close();
        ((MainActivity)context).runOnUiThread(this::hideCursor);
    }


    public void movePointer(){
        ((MainActivity)context).runOnUiThread(() -> {
            cursorView.setX(x1);
            cursorView.setY(y1);
        });
    }

    private void pointerGrab(DataOutputStream xOut) throws IOException {
        xOut.writeBytes( "_ " + true + " ioctl" + " 0\n");
    }

}
