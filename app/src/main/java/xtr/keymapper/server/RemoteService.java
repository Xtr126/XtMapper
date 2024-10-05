package xtr.keymapper.server;

import android.app.ApplicationErrorReport;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import xtr.keymapper.ActivityObserver;
import xtr.keymapper.BuildConfig;
import xtr.keymapper.IRemoteService;
import xtr.keymapper.IRemoteServiceCallback;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.R;
import xtr.keymapper.Utils;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.touchpointer.KeyEventHandler;

public class RemoteService extends IRemoteService.Stub {
    private String currentDevice = "";
    InputService inputService;
    private OnKeyEventListener mOnKeyEventListener;
    boolean isWaylandClient = false;
    private ActivityObserverService activityObserverService;
    String nativeLibraryDir = System.getProperty("java.library.path");
    private View cursorView;
    private int TYPE_SECURE_SYSTEM_OVERLAY;
    Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    final Context context;
    public static final String TAG = "xtmapper-server";
    boolean startedFromShell = false;

    public RemoteService(Context context) {
        loadLibraries();
        this.context = context;

        windowManager = context.getSystemService(WindowManager.class);
        LayoutInflater layoutInflater = context.getSystemService(LayoutInflater.class);
        context.setTheme(R.style.Theme_XtMapper);
        cursorView = CursorBinding.inflate(layoutInflater).getRoot();
        try {
            prepareCursorOverlayWindow();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
        init();
    }


    public void init() {
        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            nativeLibraryDir = ai.nativeLibraryDir;
            if(!isWaylandClient) start_getevent();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }

        Looper.getMainLooper().getThread().setUncaughtExceptionHandler((t, e) -> {
            try {
                ApplicationErrorReport.CrashInfo crashInfo = new ApplicationErrorReport.CrashInfo(e);

                new ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                        new ComponentName(context, MainActivity.class).flattenToString(),
                        "--es", "data",
                        crashInfo.exceptionMessage + "\n" +
                                crashInfo.exceptionClassName + "\n" +
                                crashInfo.stackTrace + "\n" +
                                crashInfo.throwClassName + "\n" +
                                crashInfo.throwFileName + "\n" +
                                crashInfo.throwLineNumber + "\n" +
                                crashInfo.throwMethodName).inheritIO().start();

            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                Log.e(TAG, e.getMessage(), e);
            }
            System.exit(1);
        });
    }

    @Override
    public void destroy() {
        stopServer();
        System.exit(0);
    }

    private void addCursorView() {
        if (cursorView == null) return;

        if(cursorView.isAttachedToWindow()) {
            cursorView.setVisibility(View.VISIBLE);
        } else {
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
            try {
                windowManager.addView(cursorView, params);
            } catch (IllegalStateException e) { // A14 QPR3 issue https://gist.github.com/RikkaW/be3fe4178903702c54ec73b2fc1187fe
                cursorView = null;
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

    public void prepareCursorOverlayWindow() throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException {
        TYPE_SECURE_SYSTEM_OVERLAY = WindowManager.LayoutParams.class.getField("TYPE_SECURE_SYSTEM_OVERLAY").getInt(null);
        Binder sWindowToken = new Binder();
        Method setDefaultTokenMethod = windowManager.getClass().getMethod("setDefaultToken", IBinder.class);
        setDefaultTokenMethod.invoke(windowManager, sWindowToken);
    }

    public static void loadLibraries() {
        System.loadLibrary("mouse_read");
        System.loadLibrary("mouse_cursor");
        System.loadLibrary("touchpad_direct");
        System.loadLibrary("touchpad_relative");
    }

    /**
     * Executes getevent command and processes the output
     */
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
                            if (isWaylandClient && data[0].contains("wl_pointer"))
                                inputService.sendWaylandMouseEvent(data[1]);

                            KeyEventHandler k = inputService.getKeyEventHandler();
                            if (!inputService.stopEvents) {
                                k.handleEvent(data[1]);
                            } else {
                                k.handleKeyboardShortcutEvent(data[1]);
                            }
                        }
                        if (mOnKeyEventListener != null) mOnKeyEventListener.onKeyEvent(line);
                    }
                }
            } catch (Exception e){
                Log.e(TAG, e.getMessage(), e);
            }
        }).start();
    }

    /**
     * @param data split output of getevent command
     * @return true if output is valid for processing
     */
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

    /**
     * Called by client to start the remote server.
     *
     * @param profile  The keymap profile
     * @param keymapConfig Some configurations
     * @param cb  The instance used to callback to remote service
     * @param screenHeight Screen resolution (vertical)
     * @param screenWidth  Screen resolution (horizontal)
     */
    @Override
    public void startServer(KeymapProfile profile, KeymapConfig keymapConfig, IRemoteServiceCallback cb, int screenWidth, int screenHeight) throws RemoteException {
        if (inputService != null) stopServer(false);
        if (cb != null) cb.asBinder().linkToDeath(this::stopServer, 0);
        mHandler.post(() -> {
            if (keymapConfig.pointerMode != KeymapConfig.POINTER_SYSTEM) {
                addCursorView();
            } else {
                cursorView = null;
            }
            try {
                inputService = new InputService(profile, keymapConfig, cb, screenWidth, screenHeight, cursorView, isWaylandClient);
                if (!isWaylandClient) {
                    inputService.setMouseLock(true);
                    inputService.openDevice(currentDevice);
                }
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
            // Launch app/game
            if (!profile.packageName.equals(BuildConfig.APPLICATION_ID)) {
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(profile.packageName);
                if (launchIntent != null && launchIntent.getComponent() != null) try {
                    new ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                            launchIntent.getComponent().flattenToString()).inheritIO().start();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public void stopServer() {
        stopServer(true);
    }

    private void stopServer(boolean exitProcess) {
        if (inputService != null) try {
            inputService.getCallback().disablePointer();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        if (!startedFromShell && exitProcess) {
            System.exit(0);
        } else if (inputService != null && !isWaylandClient) {
            inputService.stopEvents = true;
            inputService.hideCursor();
            inputService.stop();
            inputService.stopMouse();
            inputService.stopTouchpad();
            inputService.destroyUinputDev();
            inputService = null;
        }
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

    /**
     * Used to temporary stop the keymapping.
     */
    @Override
    public void pauseMouse(){
        if (inputService != null)
            if (!inputService.stopEvents) inputService.pauseResumeKeymap();
    }

    @Override
    public void resumeMouse(){
        if (inputService != null)
            if (inputService.stopEvents)
                inputService.pauseResumeKeymap();
    }

    /**
     * Used to refresh by requesting new keymap from the user app
     */
    @Override
    public void reloadKeymap() {
        if (inputService != null) inputService.reloadKeymap();
    }

}
