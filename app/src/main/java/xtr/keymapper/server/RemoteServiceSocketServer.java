package xtr.keymapper.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.IOException;

public class RemoteServiceSocketServer {
    private LocalSocket socket;

    public void init() {
        try {
            LocalServerSocket serverSocket = new LocalServerSocket("xtmapper-a3e11694");
            socket = serverSocket.accept();
            int length = socket.getInputStream().read();
            byte[] b = new byte[length];
            socket.getInputStream().read(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
