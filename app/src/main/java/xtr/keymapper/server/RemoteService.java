package xtr.keymapper.server;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;

import java.io.BufferedReader;

import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.Utils;

public class RemoteService extends Service {
    private String currentDevice = "";
    private InputService inputService;
    private OnKeyEventListener mOnKeyEventListener;

    public static void main(String[] args) {
        Looper.prepare();
        new RemoteService();
        Looper.loop();
    }

    public RemoteService() {
        super();
        Log.i("XtMapper", "starting server...");
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
                boolean stopEvents = false;
                while ((line = getevent.readLine()) != null) {
                    addNewDevices(line);
                    if (!stopEvents) {
                        if (inputService != null) inputService.getKeyEventHandler().handleEvent(line);
                        if (mOnKeyEventListener != null) mOnKeyEventListener.onKeyEvent(line);
                    }
                }
            } catch (Exception e){
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

    private final IRemoteService.Stub binder = new IRemoteService.Stub() {
        public boolean isRoot() {
            return inputService.supportsUinput > 0;
        }

        @Override
        public void startServer(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight) {
            inputService = new InputService(profile, keymapConfig, cb, screenWidth, screenHeight);
            inputService.setMouseLock(true);
            inputService.openDevice(currentDevice);
        }

        @Override
        public void stopServer() {
            inputService.stopEvents = true;
            inputService.stop();
            inputService.stopMouse();
            inputService.destroyUinputDev();
            inputService = null;
        }

        @Override
        public void registerOnKeyEventListener(OnKeyEventListener l)  {
            mOnKeyEventListener = l;
        }

        @Override
        public void unregisterOnKeyEventListener(OnKeyEventListener l)  {
            mOnKeyEventListener = null;
        }

        public void pauseMouse(){
            if (inputService != null) {
                inputService.setMouseLock(false);
                inputService.stopEvents = true;
            }
        }

        @Override
        public void reloadKeymap() {
            if (inputService != null) inputService.reloadKeymap();
        }

        public void resumeMouse(){
            if (inputService != null) {
                inputService.setMouseLock(true);
                inputService.stopEvents = false;
            }
        }
    };

    static {
        System.loadLibrary("mouse_read");
        System.loadLibrary("mouse_cursor");
    }

    public static IRemoteService getInstance(){
        return IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"));
    }

}