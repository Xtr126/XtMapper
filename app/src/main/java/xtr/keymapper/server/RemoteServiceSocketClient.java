package xtr.keymapper.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.Parcel;

import java.io.IOException;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

public class RemoteServiceSocketClient implements IRemoteService {

    private LocalSocket socket;

    static private <T extends android.os.Parcelable> void writeTypedObject(
            android.os.Parcel parcel, T value, int parcelableFlags) {
        if (value != null) {
            parcel.writeInt(1);
            value.writeToParcel(parcel, parcelableFlags);
        } else {
            parcel.writeInt(0);
        }
    }

    private boolean transactRemote(int code, Parcel data, Parcel reply, int flags) {
        try {
            byte[] b = data.marshall();
            socket.getOutputStream().write(b.length);
            socket.getOutputStream().write(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }
    public void init() throws IOException {
        socket = new LocalSocket();
        socket.connect(new LocalSocketAddress("xtmapper-a3e11694"));
    }

    @Override public boolean isRoot()
    {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            boolean _status = transactRemote(TRANSACTION_isRoot, _data, _reply, 0);
            _reply.readException();
            _result = (0!=_reply.readInt());
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
        return _result;
    }

    @Override public void startServer(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight) {
        Parcel _data = Parcel.obtain();
        Parcel _reply = Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            writeTypedObject(_data, profile, 0);
            writeTypedObject(_data, keymapConfig, 0);
            _data.writeStrongInterface(cb);
            _data.writeInt(screenWidth);
            _data.writeInt(screenHeight);
            boolean _status = transactRemote(TRANSACTION_startServer, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void stopServer() {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            boolean _status = transactRemote(TRANSACTION_stopServer, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void registerOnKeyEventListener(xtr.keymapper.OnKeyEventListener l) {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            _data.writeStrongInterface(l);
            boolean _status = transactRemote(TRANSACTION_registerOnKeyEventListener, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void unregisterOnKeyEventListener(xtr.keymapper.OnKeyEventListener l) {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            _data.writeStrongInterface(l);
            boolean _status = transactRemote(TRANSACTION_unregisterOnKeyEventListener, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void registerActivityObserver(xtr.keymapper.ActivityObserver callback) {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            _data.writeStrongInterface(callback);
            boolean _status = transactRemote(TRANSACTION_registerActivityObserver, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void unregisterActivityObserver(xtr.keymapper.ActivityObserver callback) {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            _data.writeStrongInterface(callback);
            boolean _status = transactRemote(TRANSACTION_unregisterActivityObserver, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void resumeMouse() {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            boolean _status = transactRemote(TRANSACTION_resumeMouse, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void pauseMouse() {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            boolean _status = transactRemote(TRANSACTION_pauseMouse, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }
    @Override public void reloadKeymap() {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
            _data.writeInterfaceToken(DESCRIPTOR);
            boolean _status = transactRemote(TRANSACTION_reloadKeymap, _data, _reply, 0);
            _reply.readException();
        }
        finally {
            _reply.recycle();
            _data.recycle();
        }
    }

    static final int TRANSACTION_isRoot = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_startServer = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_stopServer = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_registerOnKeyEventListener = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_unregisterOnKeyEventListener = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_registerActivityObserver = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_unregisterActivityObserver = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_resumeMouse = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_pauseMouse = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_reloadKeymap = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);

    @Override
    public IBinder asBinder() {
        return null;
    }
}
