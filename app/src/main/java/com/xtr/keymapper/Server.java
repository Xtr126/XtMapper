package com.xtr.keymapper;

import android.os.Handler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {
    private ServerSocket serverSocket;
    private Handler handler;
    private Thread serverThread;

    public Server(Handler handler) {
        this.handler = handler;
    }

    public void start() {
        serverThread = new Thread(new ServerThread());
        serverThread.start();
    }

    class ServerThread implements Runnable {

        public void run() {
            Socket socket = null;
            try {
                serverSocket = new ServerSocket(Main.DEFAULT_PORT);

                while (!Thread.currentThread()
                        .isInterrupted()) {
                    socket = serverSocket.accept();
                    InputReceiver inputReceiver = new InputReceiver(socket, handler);
                    inputReceiver.start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
