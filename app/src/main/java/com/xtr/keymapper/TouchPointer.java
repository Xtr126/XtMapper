package com.xtr.keymapper;

import static android.content.Context.WINDOW_SERVICE;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
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
    String[] keys; Float[] keys_x; Float[] keys_y;
    public final TextView cmdView3;
    final Button startButton;
    private StringBuilder c3;
    boolean pointer_down = false;
    private final Handler textViewUpdater = new Handler();
    private int counter = 0;

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
            Server.killServer(context.getPackageName());
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
            DataOutputStream x_out = new DataOutputStream(socket.getOutputStream());
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
                    if (i >= 0 && i <= 35) { // Make sure valid
                        if (keys_x != null) { // Avoid null array exception in case user has not set keymap already
                            if (keys_x[i] != null) {
                                x_out.writeBytes(keys_x[i] + " " + keys_y[i] + " " + xy[3] + " " + i + "\n");
                                // Send coordinates to remote server to simulate touch
                            }
                        }
                    }
                }
                movePointer();
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
        keys = keymapConfig.getKeys();
        keys_x = keymapConfig.getX();
        keys_y = keymapConfig.getY();
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
                tryStopSocket();
            }
            updateCmdView("waiting for server...");
    }

    private void handleMouseEvents(Socket clientSocket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        updateCmdView("initialized: listening for events through socket");
        Socket socket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT);
        DataOutputStream x_out = new DataOutputStream(socket.getOutputStream());
        pointerGrab(x_out);
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
                            x_out.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + "MOVE" + " 36" + "\n");
                        break;
                    }
                    case "REL_Y": {
                        y1 += Integer.parseInt(xy2[1]);
                        if ( y1 < 0 ) y1 -= Integer.parseInt(xy2[1]);
                        if ( y1 > height ) y1 -= Integer.parseInt(xy2[1]);
                        if (pointer_down)
                        x_out.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + "MOVE" + " 36" + "\n");
                        break;
                    }
                    case "BTN_MOUSE": {
                        pointer_down = xy2[1].equals("1");
                        x_out.writeBytes(Integer.sum(x1, x2) + " " + Integer.sum(y1, y2) + " " + xy2[1] + " 36" + "\n");
                        break;
                    }
                }
                movePointer();

        }
        in.close();
        clientSocket.close();
        socket.close();
    }

    public void tryStopSocket(){
        try {
            DataOutputStream x_out = new DataOutputStream(new Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2).getOutputStream());
            x_out.writeBytes(null + "\n");
            x_out.flush(); x_out.close();
        } catch (IOException e) {
            Log.e("I/O error", e.toString());
        }
    }

    public void movePointer(){
        ((MainActivity)context).runOnUiThread(() -> cursorView.setY(y1));
        ((MainActivity)context).runOnUiThread(() -> cursorView.setX(x1));
    }

    private void pointerGrab(DataOutputStream x_out) throws IOException {
        x_out.writeBytes( "_ " + true + " ioctl" + " 0\n");
        // Tell remote server running as root to ioctl to gain exclusive access to input device
    }

}
