package xtr.keymapper.server;

import static xtr.keymapper.server.RemoteServiceSocketClient.*;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class RemoteServiceSocketServer {
//    private final LocalSocket socket;
    private final RemoteService mService;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    private <T> T readTypedObject(android.os.Parcelable.Creator<T> c) {
        Parcel parcel = Parcel.obtain();
        try {
            int length = inputStream.read();
            byte[] bytes = new byte[length];
            System.out.println(length);
            inputStream.read(bytes);
            System.out.println(bytes.toString());
            parcel.unmarshall(bytes, 0, length);
            parcel.setDataPosition(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        T obj = c.createFromParcel(parcel);
        parcel.recycle();
        return obj;
    }

    public RemoteServiceSocketServer(RemoteService mService) {
        this.mService = mService;
        try {
            LocalServerSocket serverSocket = new LocalServerSocket("xtmapper-a3e11694");
            LocalSocket socket = serverSocket.accept();
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            while (true) {
                int code = inputStream.read();
                if (code == -1) continue;

                int length = inputStream.read();
                if (length == -1) continue;
                byte[] b = new byte[length];
                inputStream.read(b);

                onTransact(code);
            }
        } catch (IOException | RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void onTransact(int code) throws RemoteException {
        switch (code)
        {
            case TRANSACTION_isRoot:
            {
                boolean _result = mService.isRoot();
                writeNoException();
                writeInt(((_result)?(1):(0)));
                break;
            }
            case TRANSACTION_startServer:
            {
                xtr.keymapper.keymap.KeymapProfile _arg0;
                _arg0 = readTypedObject(xtr.keymapper.keymap.KeymapProfile.CREATOR);
                xtr.keymapper.keymap.KeymapConfig _arg1;
                _arg1 = readTypedObject(xtr.keymapper.keymap.KeymapConfig.CREATOR);
                xtr.keymapper.IRemoteServiceCallback _arg2;
                _arg2 = null;
                int _arg3;
                _arg3 = readInt();
                int _arg4;
                _arg4 = readInt();
                mService.startServer(_arg0, _arg1, _arg2, _arg3, _arg4);
                writeNoException();
                break;
            }
            case TRANSACTION_stopServer:
            {
                mService.stopServer();
                writeNoException();
                break;
            }
            case TRANSACTION_registerOnKeyEventListener:
            {
                xtr.keymapper.OnKeyEventListener _arg0;
                _arg0 = null;
                mService.registerOnKeyEventListener(_arg0);
                writeNoException();
                break;
            }
            case TRANSACTION_unregisterOnKeyEventListener:
            {
                xtr.keymapper.OnKeyEventListener _arg0;
                _arg0 = null;
                mService.unregisterOnKeyEventListener(_arg0);
                writeNoException();
                break;
            }
            case TRANSACTION_registerActivityObserver:
            {
                xtr.keymapper.ActivityObserver _arg0;
                _arg0 = null;
                mService.registerActivityObserver(_arg0);
                writeNoException();
                break;
            }
            case TRANSACTION_unregisterActivityObserver:
            {
                xtr.keymapper.ActivityObserver _arg0;
                _arg0 = null;
                mService.unregisterActivityObserver(_arg0);
                writeNoException();
                break;
            }
            case TRANSACTION_resumeMouse:
            {
                mService.resumeMouse();
                writeNoException();
                break;
            }
            case TRANSACTION_pauseMouse:
            {
                mService.pauseMouse();
                writeNoException();
                break;
            }
            case TRANSACTION_reloadKeymap:
            {
                mService.reloadKeymap();
                writeNoException();
                break;
            }
        }
    }

    public int readInt() {
        int i;
        try {
            int length = inputStream.read();
            byte[] b = new byte[length];
            inputStream.read(b);
            i = ByteBuffer.wrap(b).getInt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return i;
    }

    public void writeNoException() {

    }

    public void writeInt(int x) {
        byte[] bytes = ByteBuffer.allocate(4).putInt(x).array();
        try {
            outputStream.write(bytes.length);
            outputStream.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
