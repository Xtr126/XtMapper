package com.xtr.keymapper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;


import static android.content.Context.WINDOW_SERVICE;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class TouchPointer {

    // declaring required variables
    private final Context context;
    private final View cursorView;
    private final WindowManager.LayoutParams mParams;
    private final WindowManager mWindowManager;
    int x1 = 100;
    int x2 = 100;
    int y1 = 100;
    int y2 = 100;
    String[] key; Float[] x; Float[] y;
    public TextView cmdView3;
    boolean pointer_down = false;
    private String[] xy;
    private DataOutputStream x_out;
    boolean pointer_visible = false;

    public TouchPointer(Context context){
        this.context= context;
        cmdView3 = ((MainActivity)context).findViewById(R.id.cmdview3);

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
    }

    public void open() {
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
            x_out = new DataOutputStream(socket.getOutputStream());
            pointer_visible = true;
            pointerGrab(true);
            String line;
            BufferedReader getevent = Utils.geteventStream(context);
            while ((line = getevent.readLine()) != null) { //read events
                xy = line.split("\\s+");
                if(!pointer_visible) {
                    pointerGrab(false);
                    break;
                }
                handleKeyboardEvents();
                movePointer();
            }
        } catch (IOException e) {
            updateCmdView("Unable to start overlay: server not started");
            hideCursor();
            Log.d("Error1",e.toString());
        }
    }

    public void hideCursor() {
        try {
            ((WindowManager)context.getSystemService(WINDOW_SERVICE)).removeView(cursorView);
            cursorView.invalidate();
            // remove all views
            ((ViewGroup) cursorView.getParent()).removeAllViews();
            pointer_visible = false;
            // the above steps are necessary when you are adding and removing
            // the view simultaneously, it might give some exceptions
        } catch (Exception e) {
            Log.d("Error2",e.toString());
        }
    }

    public void loadKeymap() throws IOException {
        KeymapConfig keymapConfig =  new KeymapConfig(context);
        List<String> stream = Files.readAllLines(Paths.get(KeymapConfig.configPath));
        stream.forEach(keymapConfig::loadConfig);
        key = keymapConfig.getKey();
        x = keymapConfig.getX();
        y = keymapConfig.getY();
    }

    public void updateCmdView3(String s){
        ((MainActivity)context).runOnUiThread(() -> cmdView3.append(s + "\n"));
    }

    private void updateCmdView(String s) {
        ((MainActivity)context).server.updateCmdView(s);
    }

    public void handleMouseEvents() {
        try {
            ServerSocket serverSocket = new ServerSocket(MainActivity.DEFAULT_PORT_2);
            updateCmdView("waiting for server...");
            Socket clientSocket = serverSocket.accept();
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            updateCmdView("initialized: listening for events through socket");

            String line;
            while ((line = in.readLine()) != null) {
                updateCmdView3("socket: " + line);
                if (pointer_visible) {
                    xy = line.split("\\s+");
                    switch (xy[0]) {
                        case "REL_X": {
                            x2 += Integer.parseInt(xy[1]);
                            if (pointer_down)
                                x_out.writeBytes(x1 + " " + y1 + " " + "MOVE " + x2 + " " + y2 + "\n");
                            x1 = x2;
                            break;
                        }
                        case "REL_Y": {
                            y2 += Integer.parseInt(xy[1]);
                            if (pointer_down)
                                x_out.writeBytes(x1 + " " + y1 + " " + "MOVE " + x2 + " " + y2 + "\n");
                            y1 = y2;
                            break;
                        }
                        case "BTN_MOUSE": {
                            pointer_down = xy[1].equals("1");
                            x_out.writeBytes(x1 + " " + y1 + " " + xy[1] + "\n");
                            break;
                        }
                    }
                    movePointer();
                }
            }
            in.close();
            clientSocket.close();
            serverSocket.close();
        } catch (IOException e) {
            Log.d("Error2", e.toString());
            tryStopSocket();
            updateCmdView("app side listener crashed: please restart app");
        }
    }

    public void tryStopSocket(){
        try {
            DataOutputStream x_out = new DataOutputStream(new Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2).getOutputStream());
            x_out.writeBytes(null + "\n");
            x_out.flush(); x_out.close();
        } catch (IOException e) {
            Log.d("Error2", e.toString());
        }
    }

    private void handleKeyboardEvents() throws IOException {
        // Keyboard input be like: /dev/input/event3 EV_KEY KEY_X DOWN
        // Mouse input be like: /dev/input/event2 EV_REL REL_X ffffffff
        int i = Utils.obtainIndex(xy[2]);
        // Strips off KEY_ from KEY_X and return the index of X in alphabet
        if (i >= 0 && i <= 35) { // Make sure valid
            if (x != null) { // Avoid null array exception in case user has not set keymap already
                if (x[i] != null) {
                    x_out.writeBytes(x[i] + " " + y[i] + " " + xy[3] + "\n");
                    // Send coordinates to remote server to simulate touch
                }
            }
        }
    }

    public void movePointer(){
        ((MainActivity)context).runOnUiThread(() -> cursorView.setY(y1));
        ((MainActivity)context).runOnUiThread(() -> cursorView.setX(x1));
    }

    private void pointerGrab(boolean ioctl) throws IOException {
        x_out.writeBytes( "_ " + ioctl + " ioctl\n");
        // Tell remote server running as root to ioctl to gain exclusive access to input device
    }

}
