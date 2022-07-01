package com.xtr.keymapper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;


import static android.content.Context.WINDOW_SERVICE;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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

    public TouchPointer(Context context){
        this.context=context;
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
    try {
        if(cursorView.getWindowToken()==null) {
            if(cursorView.getParent()==null) {
                mWindowManager.addView(cursorView, mParams);
                loadKeymap();
            }
        }
    } catch (Exception e) {
        Log.d("Error1",e.toString());
    }

    new Thread(() -> {
        try{
            Socket socket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT);
            DataOutputStream Xout = new DataOutputStream(socket.getOutputStream());
            Process sh = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(sh.getInputStream()));

            outputStream.writeBytes("//data/data/com.termux/files/usr/bin/stdbuf -oL getevent -ql"+"\n");
            outputStream.writeBytes("exit\n");

            outputStream.flush();
            String line;
            boolean pointer_down = false;
            while ((line = stdInput.readLine()) != null) {
                String[] xy = line.split("\\s+");
                // keyboard input be like: /dev/input/event3: EV_KEY KEY_X DOWN
                // mouse input be like: /dev/input/event2: EV_REL REL_X ffffffff
                switch (xy[2]) {
                    case "REL_X": {
                        x2 += (int) Utils.hexToDec(xy[3]);
                        /*if (pointer_down)
                            Xout.writeBytes(x1 + " " + y1 + " " + "MOVE " + x2 + " " + y2 + "\n");*/
                        x1 = x2;
                        break;
                    }
                    case "REL_Y": {
                        y2 += (int) Utils.hexToDec(xy[3]);
                        /*if (pointer_down)
                            Xout.writeBytes(x1 + " " + y1 + " " + "MOVE " + x2 + " " + y2 + "\n");*/
                        y1 = y2;
                        break;
                    }
                    case "BTN_MOUSE": {
                       //pointer_down = xy[3].equals("DOWN");
                       //Xout.writeBytes(x1 + " " + y1 + " " + xy[3] + "\n");
                        break;
                    }

                }
                int i = Utils.obtainIndex(xy[2]);
               /* if (i >= 0 && i <= 35) {
                    if (x[i] != null) {
                        Xout.writeBytes(x[i] + " " + y[i] + " " + xy[3] + "\n");
                    }
                }  */
                cursorView.setX(x1);
                cursorView.setY(y1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }).start();
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
}
