package xtr.keymapper.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.Parcel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;

public class RemoteServiceSocketClient implements IRemoteService {

    // Socket should stay alive
    public static LocalSocket socket;

    public RemoteServiceSocketClient() throws IOException {
        if (socket == null) {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress("xtmapper-a3e11694"));
        }
    }
    public static class ParcelableByteArray {
        final byte[][] data;
        int i = 0;

        public ParcelableByteArray(int size) {
            data = new byte[size][];
        }

        public void foreach(Consumer<byte []> action) {
            for (byte[] bytes : data) action.accept(bytes);
        }

        private <T extends android.os.Parcelable> void writeTypedObject(T value) {
            Parcel parcel = Parcel.obtain();
            value.writeToParcel(parcel, 0);
            data[i] = parcel.marshall();
            parcel.recycle();
            i++;
        }

        public void writeStrongInterface(Object o) {
            data[i] = new byte[0];
            i++;
        }

        public void writeInt(int x) {
            data[i] = ByteBuffer.allocate(4).putInt(x).array();
            i++;
        }
    }

    public int readInt() {
        int i;
        try {
            InputStream inputStream = socket.getInputStream();
            int length = inputStream.read();
            byte[] bytes = new byte[length];
            inputStream.read(bytes);
            i = ByteBuffer.wrap(bytes).getInt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return i;
    }

    private boolean transactRemote(int code, ParcelableByteArray data) {
        try {
            socket.getOutputStream().write(code);

            data.foreach(bytes -> {
                try {
                    socket.getOutputStream().write(bytes.length);
                    socket.getOutputStream().write(bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override public boolean isRoot()
    {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        boolean _status = transactRemote(TRANSACTION_isRoot, _data);

        boolean _result = (0!=readInt());

        return _result;
    }

    @Override public void startServer(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight) {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        _data.writeTypedObject(profile);
        _data.writeTypedObject(keymapConfig);
        _data.writeStrongInterface(null);
        _data.writeInt(screenWidth);
        _data.writeInt(screenHeight);
        boolean _status = transactRemote(TRANSACTION_startServer, _data);
    }
    @Override public void stopServer() {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        boolean _status = transactRemote(TRANSACTION_stopServer, _data);
    }
    @Override public void registerOnKeyEventListener(xtr.keymapper.OnKeyEventListener l) {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        _data.writeStrongInterface(null);
        boolean _status = transactRemote(TRANSACTION_registerOnKeyEventListener, _data);

    }
    @Override public void unregisterOnKeyEventListener(xtr.keymapper.OnKeyEventListener l) {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        _data.writeStrongInterface(null);
        boolean _status = transactRemote(TRANSACTION_unregisterOnKeyEventListener, _data);
    }
    @Override public void registerActivityObserver(xtr.keymapper.ActivityObserver callback) {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        _data.writeStrongInterface(null);
        boolean _status = transactRemote(TRANSACTION_registerActivityObserver, _data);
    }
    @Override public void unregisterActivityObserver(xtr.keymapper.ActivityObserver callback) {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        _data.writeStrongInterface(null);
        boolean _status = transactRemote(TRANSACTION_unregisterActivityObserver, _data);
    }
    @Override public void resumeMouse() {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        boolean _status = transactRemote(TRANSACTION_resumeMouse, _data);
    }
    @Override public void pauseMouse() {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        boolean _status = transactRemote(TRANSACTION_pauseMouse, _data);
    }
    @Override public void reloadKeymap() {
        ParcelableByteArray _data = new ParcelableByteArray(5);
        boolean _status = transactRemote(TRANSACTION_reloadKeymap, _data);
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
