package com.xtr.keymapper;

import static com.xtr.keymapper.Input.SOURCE_KEY;
import static com.xtr.keymapper.Input.SOURCE_MOVEMENT;

import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;

public class InputReceiver {
    private Socket socket;
    private Handler handler;
    private Thread communicationThread;

    InputReceiver(Socket socket, Handler handler) {
        this.socket = socket;
        this.handler = handler;
    }

    void start() {
        communicationThread = new Thread(new CommunicationThread(socket));
        communicationThread.start();
    }

    class CommunicationThread implements Runnable {
        private Socket clientSocket;
        private DataInputStream input;

        public CommunicationThread(Socket clientSocket) {
            this.clientSocket = clientSocket;

            try {
                this.input = new DataInputStream(this.clientSocket.getInputStream());

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                int len = input.readInt();
                byte[] data = new byte[len];
                if (len > 0) {
                    input.readFully(data);
                }

                Parcel parcel = unmarshall(data);

                Message message = new Message();

                int type = parcel.readInt();
                parcel.setDataPosition(0);

                if (type == SOURCE_MOVEMENT) {
                    MotionEvent event = MotionEvent.CREATOR.createFromParcel(parcel);

                    message.what = SOURCE_MOVEMENT;
                    message.obj = event;
                } else if (type == SOURCE_KEY) {
                    KeyEvent event = KeyEvent.CREATOR.createFromParcel(parcel);

                    message.what = SOURCE_KEY;
                    message.obj = event;
                }

                handler.sendMessage(message);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private Parcel unmarshall(byte[] bytes) {
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);
        return parcel;
    }
}