package xtr.keymapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.editor.EditorService;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.KeymapProfiles;
import xtr.keymapper.profiles.ProfileSelector;
import xtr.keymapper.server.RemoteService;


public class TouchPointer extends Service {
    private final IBinder binder = new TouchPointerBinder();
    public MainActivity.Callback activityCallback;
    private WindowManager mWindowManager;
    public View cursorView;
    private IRemoteService mService;
    private String selectedProfile = null;

    public class TouchPointerBinder extends Binder {
        public TouchPointer getService() {
            // Return this instance of TouchPointer so clients can call public methods
            return TouchPointer.this;
        }
    }

    private void showCursor() {
        super.onCreate();
        LayoutInflater layoutInflater = getSystemService(LayoutInflater.class);
        mWindowManager = getSystemService(WindowManager.class);
        // Inflate the layout for the cursor
        cursorView = CursorBinding.inflate(layoutInflater).getRoot();

        // set the layout parameters of the cursor
        WindowManager.LayoutParams mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Don't let the cursor grab the input focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                // Make the underlying application window visible
                // through the cursor
                PixelFormat.TRANSLUCENT);

        if(cursorView.getWindowToken()==null)
            if (cursorView.getParent() == null)
                mWindowManager.addView(cursorView, mParams);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String CHANNEL_ID = "pointer_service";
        String name = "Overlay";
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        Intent intent = new Intent(this, EditorService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        Notification notification = builder.setOngoing(true)
                .setContentTitle("Keymapper service running")
                .setContentText("Touch to launch editor")
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(2, notification);

        if (cursorView == null) showCursor();
        if (mService == null) ProfileSelector.select(this, profile -> {
            this.selectedProfile = profile;
            connectRemoteService();
        });
        return super.onStartCommand(i, flags, startId);
    }

    private void connectRemoteService() {
        if (activityCallback != null) activityCallback.updateCmdView1("\n connecting to server..");
        mService = RemoteService.getInstance();
        if (mService == null) {
            if (activityCallback != null) {
                activityCallback.updateCmdView1("\n connection failed\n Please retry activation \n");
                activityCallback.stopPointer();
            } else stopSelf();
            return;
        }
        KeymapProfile profile = new KeymapProfiles(this).getProfile(selectedProfile);
        KeymapConfig keymapConfig = new KeymapConfig(this);
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size); // TODO: getRealSize() deprecated in API level 31
        try {
            mService.startServer(profile, keymapConfig, mCallback, size.x, size.y);
            mService.registerActivityObserver(mActivityObserverCallback);
        } catch (Exception e) {
            activityCallback.updateCmdView1(e.toString());
        }
    }


    @Override
    public void onDestroy() {
        if (cursorView != null) {
            mWindowManager.removeView(cursorView);
            cursorView.invalidate();
        }
        if (mService != null) try {
            mService.unregisterActivityObserver(mActivityObserverCallback);
            mService.stopServer();
        } catch (Exception ignored) {
        }
        cursorView = null;
        mService = null;
        activityCallback = null;
        super.onDestroy();
    }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private final IRemoteServiceCallback mCallback = new IRemoteServiceCallback.Stub() {

        private final Handler mHandler = new Handler(Looper.getMainLooper());

        @Override
        public void launchEditor() {
            startService(new Intent(TouchPointer.this, EditorService.class));
        }

        @Override
        public void alertMouseAimActivated() {
            // Notifying user that shooting mode was activated
            mHandler.post(() -> Toast.makeText(TouchPointer.this, R.string.mouse_aim_activated, Toast.LENGTH_LONG).show());
        }

        @Override
        public void cursorSetX(int x) {
            mHandler.post(() -> cursorView.setX(x));
        }

        @Override
        public void cursorSetY(int y) {
            mHandler.post(() -> cursorView.setY(y));
        }

        @Override
        public KeymapProfile requestKeymapProfile() {
            return new KeymapProfiles(TouchPointer.this).getProfile(selectedProfile);
        }

        @Override
        public KeymapConfig requestKeymapConfig() {
            return new KeymapConfig(TouchPointer.this);
        }
    };


    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private final ActivityObserver mActivityObserverCallback = new ActivityObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(String packageName) {
            TouchPointer.this.selectedProfile = packageName;
            Context context = TouchPointer.this;
            KeymapProfiles keymapProfiles = new KeymapProfiles(context);
            if (!keymapProfiles.profileExistsWithPackageName(packageName))
                ProfileSelector.showEnableProfileDialog(context, packageName, enabled ->
                        ProfileSelector.createNewProfileWithPackageName(context, packageName, enabled, profile -> {
                            try {
                                mService.stopServer();
                                connectRemoteService();
                            } catch (RemoteException ignored) {
                            }
                        }));
        }
    };
}
