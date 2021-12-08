package com.xtr.keymapper;

import static com.xtr.keymapper.Input.SOURCE_KEY;
import static com.xtr.keymapper.Input.SOURCE_MOVEMENT;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.FileNotFoundException;

public class Main {
    static Looper looper;
    private MouseReader mouseReader;

    public static final int DEFAULT_PORT = 6543;
    Handler messageHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case SOURCE_MOVEMENT:
                    mouseReader.processEvent((MotionEvent) msg.obj);
                    break;
                case SOURCE_KEY:
                    mouseReader.processEvent((KeyEvent) msg.obj);
                    break;
            }
        }
    };

    private Server server;

    public Main() {
        System.loadLibrary("mouse_read");
        server = new Server(messageHandler);
        server.start();
    }

    public static void main(String[] args) {
        Looper.prepare();
        looper = Looper.myLooper();

        Main main = new Main();

        Looper.loop();
    }

}