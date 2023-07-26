package xtr.keymapper.server;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.IOException;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.OnMouseEventListener;
import xtr.keymapper.Utils;

public class InputService extends Service {
    private static final Input input = new Input();
    public static final int UP = 0, DOWN = 1, MOVE = 2;
    private final int supportsUinput;
    private String currentDevice = "";

    final RemoteCallbackList<OnKeyEventListener> mOnKeyEventListeners = new RemoteCallbackList<>();
    private IRemoteServiceCallback mCallback;
    private OnMouseEventListener mOnMouseEventListener;

    public static void main(String[] args) {
        Looper.prepare();
        new InputService();
        Looper.loop();
    }

    public InputService() {
        super();
        Log.i("XtMapper", "starting server...");
        supportsUinput = initMouseCursor(1280, 720);
        try {
            ServiceManager.addService("xtmapper", binder);
            System.out.println("Waiting for overlay...");
            start_getevent();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void start_getevent() {
        new Thread(() -> {
            try {
                BufferedReader getevent = Utils.geteventStream();
                String line;

                while ((line = getevent.readLine()) != null) {
                    addNewDevices(line);
                    int i = mOnKeyEventListeners.beginBroadcast();
                    while (i > 0) {
                        i--;
                        try {
                            mOnKeyEventListeners.getBroadcastItem(i).onKeyEvent(line);
                        } catch (RemoteException e) {
                            // The RemoteCallbackList will take care of removing
                            // the dead object for us.
                        }
                    }
                    mOnKeyEventListeners.finishBroadcast();
                }
            } catch (IOException e) {
                e.printStackTrace(System.out);
            }
        }).start();
    }

    private void addNewDevices(String line) {
        String[] input_event, data;
        String evdev;
        data = line.split(":"); // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
        evdev = data[0];
        input_event = data[1].split("\\s+");

        if( !currentDevice.equals(evdev) )
            if (input_event[1].equals("EV_REL")) {
                System.out.println("add device: " + evdev);
                currentDevice = evdev;
            }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public native int openDevice(String device);
    public native void stopMouse();

    // mouse cursor created with uinput in MouseCursor.cpp
    private native int initMouseCursor(int width, int height);
    private native void destroyUinputDev();
    private native void cursorSetX(int x);
    private native void cursorSetY(int y);

    private final IRemoteService.Stub binder = new IRemoteService.Stub() {
        public void injectEvent(float x, float y, int action, int pointerId) {
            switch (action) {
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

        public void moveCursorX(int x) {
            cursorSetX(x);
        }

        public void moveCursorY(int y) {
            cursorSetY(y);
        }

        public boolean isRoot() {
            return supportsUinput > 0;
        }

        @Override
        public void startMouse() {
            openDevice(currentDevice);
        }

        @Override
        public void setCallback(IRemoteServiceCallback cb) {
            mCallback = cb;
        }

        @Override
        public void removeCallback(IRemoteServiceCallback cb) {
            mCallback = null;
        }

        @Override
        public void registerOnKeyEventListener(OnKeyEventListener l)  {
            mOnKeyEventListeners.register(l);
        }

        @Override
        public void unregisterOnKeyEventListener(OnKeyEventListener l)  {
            mOnKeyEventListeners.unregister(l);
        }

        @Override
        public void setOnMouseEventListener(OnMouseEventListener l) throws RemoteException {
            l.asBinder().linkToDeath(mDeathRecipient, 0);
            mOnMouseEventListener = l;
        }

        @Override
        public void removeOnMouseEventListener(OnMouseEventListener l)  {
            l.asBinder().unlinkToDeath(mDeathRecipient, 0);
            stopMouse();
            mOnMouseEventListener = null;
        }

        public void setScreenSize(int width, int height){
            destroyUinputDev();
            initMouseCursor(width, height);
        }


        public void reloadKeymap() throws RemoteException {
            if (mCallback != null) mCallback.loadKeymap();
        }
    };

    private final IBinder.DeathRecipient mDeathRecipient = this::stopMouse;

    /*
     * Called from native code to send mouse event to client
     */
    private void sendMouseEvent(int code, int value) {
        try {
            if (mOnMouseEventListener != null)
                mOnMouseEventListener.onMouseEvent(code, value);
            else stopMouse();
        } catch (RemoteException ex) {
            stopMouse();
        }
    }

    public static IRemoteService getInstance(){
        return IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"));
    }

    public static void reloadKeymap(){
        IRemoteService mService = getInstance();
        if (mService != null) {
            try {
                mService.reloadKeymap();
            } catch (RemoteException e) {
                Log.i("RemoteService", e.toString());
            }
        }
    }

    static {
        System.loadLibrary("mouse_read");
        System.loadLibrary("mouse_cursor");
    }
}