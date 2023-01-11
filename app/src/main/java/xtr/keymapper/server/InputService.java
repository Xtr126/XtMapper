package xtr.keymapper.server;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.IOException;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.Utils;


public class InputService extends Service {
    private static final Input input = new Input();
    private IRemoteServiceCallback mCallback;
    public static final int UP = 0, DOWN = 1, MOVE = 2;

    public static void main(String[] args) {
        Looper.prepare();
        new InputService();
        Looper.loop();
    }

    public InputService() {
        super();
        Log.i("XtMapper", "starting server...");
        ServiceManager.addService("xtmapper", binder);
        System.out.println("Waiting for overlay...");
        new Thread(() -> {
            try (BufferedReader in = Utils.geteventStream()) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (mCallback != null)
                        mCallback.receiveEvent(line);
                }
            } catch (IOException | RemoteException e) {
                e.printStackTrace(System.out);
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public native void startMouse(int sensitivity);
    public static native int openDevice(String device);
    public native void stopMouse();

    private final IRemoteService.Stub binder = new IRemoteService.Stub() {
        public void injectEvent(float x, float y, int type, int pointerId) {
            switch (type) {
                case UP:
                    input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, x, y);
                    break;
                case DOWN:
                    input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, x, y);
                    break;
                case MOVE:
                    input.injectTouch(MotionEvent.ACTION_MOVE, pointerId, 1.0f, x, y);
                    break;
            }
        }
        public void injectScroll(float x, float y, int value) {
            input.onScrollEvent(x, y, value);
        }

        public void startServer(float sensitivity) {
            startMouse((int) sensitivity);
        }

        public int tryOpenDevice(String device) {
             return openDevice(device);
        }

        public void closeDevice() {
            stopMouse();
        }

        public void registerCallback(IRemoteServiceCallback cb) {
            mCallback = cb;
        }

        public void unregisterCallback(IRemoteServiceCallback cb) {
            mCallback = null;
        }
    };

    /*
     * Called from native code to send mouse event to client
     */
    private void sendMouseEvent(int code, int value) throws RemoteException {
        if (mCallback != null)
            mCallback.onMouseEvent(code, value);
    }

    public static IRemoteService getInstance(){
        return IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"));
    }
    static {
        System.loadLibrary("mouse_read");
    }
}