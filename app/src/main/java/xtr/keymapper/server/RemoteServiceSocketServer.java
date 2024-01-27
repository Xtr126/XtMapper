package xtr.keymapper.server;

import static android.os.IBinder.INTERFACE_TRANSACTION;
import static xtr.keymapper.server.RemoteServiceSocketClient.*;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.IOException;
import java.io.InputStream;

public class RemoteServiceSocketServer {
    private final LocalSocket socket;
    private final RemoteService mService;

    static private <T> T readTypedObject(
            android.os.Parcel parcel,
            android.os.Parcelable.Creator<T> c) {
        if (parcel.readInt() != 0) {
            return c.createFromParcel(parcel);
        } else {
            return null;
        }
    }

    public RemoteServiceSocketServer(RemoteService mService) {
        this.mService = mService;
        try {
            LocalServerSocket serverSocket = new LocalServerSocket("xtmapper-a3e11694");
            socket = serverSocket.accept();
            while (true) {
                InputStream inputStream = socket.getInputStream();
                int code = inputStream.read();

                int length = inputStream.read();
                byte[] b = new byte[length];
                inputStream.read(b);
                Parcel data = Parcel.obtain();
                data.unmarshall(b, 0, length);
                data.setDataPosition(0);

                length = inputStream.read();
                byte[] r = new byte[length];
                inputStream.read(r);
                Parcel reply = Parcel.obtain();
                reply.unmarshall(r, 0, length);
                reply.setDataPosition(0);

                onTransact(code, data, reply);
                r = reply.marshall();
                socket.getOutputStream().write(r.length);
                socket.getOutputStream().write(r);
            }
        } catch (IOException | RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply) throws RemoteException {
        java.lang.String descriptor = DESCRIPTOR;
        if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
            data.enforceInterface(descriptor);
        }
        switch (code)
        {
            case INTERFACE_TRANSACTION:
            {
                reply.writeString(descriptor);
                return true;
            }
        }
        switch (code)
        {
            case TRANSACTION_isRoot:
            {
                boolean _result = mService.isRoot();
                reply.writeNoException();
                reply.writeInt(((_result)?(1):(0)));
                break;
            }
            case TRANSACTION_startServer:
            {
                xtr.keymapper.keymap.KeymapProfile _arg0;
                _arg0 = readTypedObject(data, xtr.keymapper.keymap.KeymapProfile.CREATOR);
                xtr.keymapper.keymap.KeymapConfig _arg1;
                _arg1 = readTypedObject(data, xtr.keymapper.keymap.KeymapConfig.CREATOR);
                xtr.keymapper.IRemoteServiceCallback _arg2;
                _arg2 = xtr.keymapper.IRemoteServiceCallback.Stub.asInterface(data.readStrongBinder());
                int _arg3;
                _arg3 = data.readInt();
                int _arg4;
                _arg4 = data.readInt();
                mService.startServer(_arg0, _arg1, _arg2, _arg3, _arg4);
                reply.writeNoException();
                break;
            }
            case TRANSACTION_stopServer:
            {
                mService.stopServer();
                reply.writeNoException();
                break;
            }
            case TRANSACTION_registerOnKeyEventListener:
            {
                xtr.keymapper.OnKeyEventListener _arg0;
                _arg0 = xtr.keymapper.OnKeyEventListener.Stub.asInterface(data.readStrongBinder());
                mService.registerOnKeyEventListener(_arg0);
                reply.writeNoException();
                break;
            }
            case TRANSACTION_unregisterOnKeyEventListener:
            {
                xtr.keymapper.OnKeyEventListener _arg0;
                _arg0 = xtr.keymapper.OnKeyEventListener.Stub.asInterface(data.readStrongBinder());
                mService.unregisterOnKeyEventListener(_arg0);
                reply.writeNoException();
                break;
            }
            case TRANSACTION_registerActivityObserver:
            {
                xtr.keymapper.ActivityObserver _arg0;
                _arg0 = xtr.keymapper.ActivityObserver.Stub.asInterface(data.readStrongBinder());
                mService.registerActivityObserver(_arg0);
                reply.writeNoException();
                break;
            }
            case TRANSACTION_unregisterActivityObserver:
            {
                xtr.keymapper.ActivityObserver _arg0;
                _arg0 = xtr.keymapper.ActivityObserver.Stub.asInterface(data.readStrongBinder());
                mService.unregisterActivityObserver(_arg0);
                reply.writeNoException();
                break;
            }
            case TRANSACTION_resumeMouse:
            {
                mService.resumeMouse();
                reply.writeNoException();
                break;
            }
            case TRANSACTION_pauseMouse:
            {
                mService.pauseMouse();
                reply.writeNoException();
                break;
            }
            case TRANSACTION_reloadKeymap:
            {
                mService.reloadKeymap();
                reply.writeNoException();
                break;
            }
        }
        return true;
    }
}
