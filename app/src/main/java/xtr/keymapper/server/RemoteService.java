package xtr.keymapper.server;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import xtr.keymapper.ActivityObserver;
import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.R;
import xtr.keymapper.Utils;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.touchpointer.KeyEventHandler;

public class RemoteService extends IRemoteService.Stub {
    private String currentDevice = "";
    private InputService inputService;
    private OnKeyEventListener mOnKeyEventListener;
    boolean isWaylandClient = false;
    private ActivityObserverService activityObserverService;
    String nativeLibraryDir = System.getProperty("java.library.path");
    private View cursorView;
    private Context context = null;
    private int TYPE_SECURE_SYSTEM_OVERLAY;
    Handler mHandler = new Handler(Looper.getMainLooper());

    public RemoteService() {

    }

    /* For Shizuku UserService */
    public RemoteService(Context context) {
        loadLibraries();
        init(context);
    }

    public RemoteService init(Context context) {
        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            nativeLibraryDir = ai.nativeLibraryDir;
            start_getevent();
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        this.context = context;
        return this;
    }

    @Override
    public void destroy() {
        stopServer();
        System.exit(0);
    }

    private void addCursorView() {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_SECURE_SYSTEM_OVERLAY,
                // Don't let the cursor grab the input focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                // Make the underlying application window visible
                // through the cursor
                PixelFormat.TRANSLUCENT);

        mHandler.post(() -> windowManager.addView(cursorView, params));
    }

    private void removeCursorView() {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        mHandler.post(() -> windowManager.removeView(cursorView));
    }

    public void prepareCursorOverlayWindow() throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException {
        LayoutInflater layoutInflater = context.getSystemService(LayoutInflater.class);
        context.setTheme(R.style.Theme_XtMapper);
        cursorView = CursorBinding.inflate(layoutInflater).getRoot();
        TYPE_SECURE_SYSTEM_OVERLAY = WindowManager.LayoutParams.class.getField("TYPE_SECURE_SYSTEM_OVERLAY").getInt(null);
        Binder sWindowToken = new Binder();
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        Method setDefaultTokenMethod = windowManager.getClass().getMethod("setDefaultToken", IBinder.class);
        setDefaultTokenMethod.invoke(windowManager, sWindowToken);
    }

    public static void loadLibraries() {
        System.loadLibrary("mouse_read");
        System.loadLibrary("mouse_cursor");
        System.loadLibrary("touchpad_direct");
        System.loadLibrary("touchpad_relative");
    }

    void start_getevent() {
        new Thread(() -> {
            try {
                final BufferedReader getevent;
                if (isWaylandClient) {
                    getevent = new BufferedReader(new InputStreamReader(System.in));
                } else {
                    getevent = Utils.geteventStream(nativeLibraryDir);
                }
                String line;
                while ((line = getevent.readLine()) != null) {
                    String[] data = line.split(":"); // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
                    if (addNewDevices(data)) {
                        if (inputService != null) {
                            KeyEventHandler k = inputService.getKeyEventHandler();
                            if (!inputService.stopEvents) {
                                if (isWaylandClient && data[0].contains("wl_pointer"))
                                    inputService.sendWaylandMouseEvent(data[1]);
                                else
                                    k.handleEvent(data[1]);
                            } else {
                                k.handleKeyboardShortcutEvent(data[1]);
                            }
                        }
                        if (mOnKeyEventListener != null) mOnKeyEventListener.onKeyEvent(line);
                    }
                }
            } catch (Exception e){
                e.printStackTrace(System.out);
            }
        }).start();
    }

    private boolean addNewDevices(String[] data) {
        String[] input_event;
        if (data.length != 2) return false;
        String evdev = data[0];

        input_event = data[1].split("\\s+");
        if (isWaylandClient) return true;
        if( !currentDevice.equals(evdev) )
            if (input_event[1].equals("EV_REL")) {
                System.out.println("add mouse device: " + evdev);
                if (inputService != null) inputService.openDevice(evdev);
                currentDevice = evdev;
            }
        return true;
    }

    @Override
    public void startServer(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight) throws RemoteException {
        if (inputService != null) stopServer();
        cb.asBinder().linkToDeath(this::stopServer, 0);
        if (!keymapConfig.pointerMode.equals(KeymapConfig.POINTER_SYSTEM)) {
            try {
                prepareCursorOverlayWindow();
            } catch (Exception e) {
                Log.e("overlayWindow", e.getMessage(), e);
            }
            if (cursorView != null) addCursorView();
        } else {
            cursorView = null;
        }
        inputService = new InputService(profile, keymapConfig, cb, screenWidth, screenHeight, cursorView, isWaylandClient);
        if (!isWaylandClient) {
            inputService.setMouseLock(true);
            inputService.openDevice(currentDevice);
        }
    }

    @Override
    public void stopServer() {
        if (inputService != null && !isWaylandClient) {
            inputService.stopEvents = true;
            inputService.stop();
            inputService.stopMouse();
            inputService.stopTouchpad();
            inputService.destroyUinputDev();
            inputService = null;
        }
        if (cursorView != null) removeCursorView();
    }

    private final DeathRecipient mDeathRecipient = () -> mOnKeyEventListener = null;

    @Override
    public void registerOnKeyEventListener(OnKeyEventListener l) throws RemoteException {
        l.asBinder().linkToDeath(mDeathRecipient, 0);
        mOnKeyEventListener = l;
    }

    @Override
    public void unregisterOnKeyEventListener(OnKeyEventListener l)  {
        if (l != null) l.asBinder().unlinkToDeath(mDeathRecipient, 0);
        mOnKeyEventListener = null;
    }

    @Override
    public void registerActivityObserver(ActivityObserver callback) {
        if (activityObserverService != null)
            activityObserverService.stop();
        activityObserverService = new ActivityObserverService(callback);
    }

    @Override
    public void unregisterActivityObserver(ActivityObserver callback) {
        if (activityObserverService != null)
            activityObserverService.stop();
        activityObserverService = null;
    }

    @Override
    public void pauseMouse(){
        if (inputService != null)
            if (!inputService.stopEvents) inputService.pauseResumeKeymap();
    }

    @Override
    public void resumeMouse(){
        if (inputService != null)
            if (inputService.stopEvents) inputService.pauseResumeKeymap();
    }

    @Override
    public void reloadKeymap() {
        if (inputService != null) inputService.reloadKeymap();
    }

}
