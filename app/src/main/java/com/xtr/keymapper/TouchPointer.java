package com.xtr.keymapper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import static android.content.Context.WINDOW_SERVICE;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class TouchPointer {

    // declaring required variables
    private final Context context;
    private final View cursorView;
    private final WindowManager.LayoutParams mParams;
    private final WindowManager mWindowManager;
    int x = 0;
    int y = 0;
    public TouchPointer(Context context){
        this.context=context;

        // set the layout parameters of the window
        mParams = new WindowManager.LayoutParams(
                // Shrink the window to wrap the content rather
                // than filling the screen
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                // Display it on top of other application windows
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Don't let it grab the input focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                // Make the underlying application window visible
                // through any transparent parts
                PixelFormat.TRANSLUCENT);
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        cursorView = layoutInflater.inflate(R.layout.cursor, null);
        mParams.gravity = Gravity.CENTER;
        mWindowManager = (WindowManager)context.getSystemService(WINDOW_SERVICE);
    }
    public void open() {
        try {
            if(cursorView.getWindowToken()==null) {
                if(cursorView.getParent()==null) {
                    mWindowManager.addView(cursorView, mParams);
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

                outputStream.writeBytes("getevent -ql"+"\n");
                outputStream.flush();

                outputStream.writeBytes("exit\n");
                outputStream.flush();
                String line;
                while ((line = stdInput.readLine()) != null) {
                    String []xy = line.split("\\s+");
                    switch (xy[2]) {
                        case "REL_X": {
                            x += (int) MainActivity.hexToDec(xy[3]);
                            break;
                        }
                        case "REL_Y": {
                            y += (int) MainActivity.hexToDec(xy[3]);
                            break;
                        }
                        case "BTN_MOUSE": {
                            Xout.writeBytes(x + " " + y + "\n");
                            break;
                        }
                    }
                    cursorView.setX(x);
                    cursorView.setY(y);

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
}
