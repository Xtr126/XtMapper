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
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.UiThread;

import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.editor.EditorActivity;
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
    public String selectedProfile = null;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean activityRemoteCallback = false;

    public class TouchPointerBinder extends Binder {
        public TouchPointer getService() {
            // Return this instance of TouchPointer so clients can call public methods
            return TouchPointer.this;
        }
    }

    // set the layout parameters of the cursor
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams(
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

    private void showCursor() {
        super.onCreate();
        LayoutInflater layoutInflater = getSystemService(LayoutInflater.class);
        mWindowManager = getSystemService(WindowManager.class);
        // Inflate the layout for the cursor
        cursorView = CursorBinding.inflate(layoutInflater).getRoot();

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

        Intent intent = new Intent(this, EditorActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

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

        KeymapConfig keymapConfig = new KeymapConfig(this);

        if(keymapConfig.disableAutoProfiling) {
            // Select app
            ProfileSelector.select(this, profile -> {
                this.selectedProfile = profile;
                KeymapProfile keymapProfile = new KeymapProfiles(this).getProfile(profile);
                connectRemoteService(keymapProfile);
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(keymapProfile.packageName);
                if (launchIntent != null) startActivity(launchIntent);
            });
        } else {
            // Launch default profile for current app
            ProfileSelector.select(this, profile -> {
                this.selectedProfile = profile;
                KeymapProfile keymapProfile = new KeymapProfiles(this).getProfile(profile);
                connectRemoteService(keymapProfile);
            }, getPackageName());
        }
        return super.onStartCommand(i, flags, startId);
    }

    private void connectRemoteService(KeymapProfile profile) {
        if (activityCallback != null) activityCallback.updateCmdView1("\n connecting to server..");
        mService = RemoteService.getInstance();
        if (mService == null) {
            if (activityCallback != null) {
                activityCallback.updateCmdView1("\n connection failed\n Please retry activation \n");
                activityCallback.stopPointer();
            } else {
                onDestroy();
                stopSelf();
            }
            return;
        }
        KeymapConfig keymapConfig = new KeymapConfig(this);
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size); // TODO: getRealSize() deprecated in API level 31
        try {
            if (keymapConfig.disableAutoProfiling) {
                mService.startServer(profile, keymapConfig, mCallback, size.x, size.y);
            } else {
                if (!activityRemoteCallback) {
                    mService.registerActivityObserver(mActivityObserverCallback);
                    activityRemoteCallback = true;
                } else {
                    if (!profile.disabled)
                        mService.startServer(profile, keymapConfig, mCallback, size.x, size.y);
                }
            }
        } catch (Exception e) {
            if(activityCallback != null) {
                activityCallback.updateCmdView1(e.toString());
                activityCallback.stopPointer();
            } else {
                onDestroy();
                stopSelf();
            }
            Log.e("startServer", e.toString(), e);
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
        } catch (Exception e) {
            Log.e("stopServer", e.toString(), e);
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

        @Override
        public void launchEditor() {
            Intent intent = new Intent(TouchPointer.this, EditorActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            startActivity(intent);
        }

        @Override
        public void alertMouseAimActivated() {
            // Notifying user that shooting mode was activated
            mHandler.post(() -> Toast.makeText(TouchPointer.this, R.string.mouse_aim_activated, Toast.LENGTH_SHORT
            ).show());
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

        @UiThread
        @Override
        public void switchProfiles() { mHandler.post(() -> {

            KeymapProfiles keymapProfiles = new KeymapProfiles(TouchPointer.this);
            KeymapProfile keymapProfile = keymapProfiles.getProfile(selectedProfile);
            String application = keymapProfile.packageName;

            if (keymapProfiles.getAllProfilesForApp(application).size() == 1) {
                Toast.makeText(TouchPointer.this, "Only one profile saved for " + application, Toast.LENGTH_SHORT).show();
                return;
            }

            ProfileSelector.select(TouchPointer.this, profile -> {
                TouchPointer.this.selectedProfile = profile;
                // Reloading profile
                connectRemoteService(keymapProfiles.getProfile(profile));
            }, application);
        });
        }
    };

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private final ActivityObserver mActivityObserverCallback = new ActivityObserver.Stub() {
        private void reloadKeymap() {
            try {
                mService.resumeMouse();
                mService.reloadKeymap();
            } catch (RemoteException ignored) {
            }
        }
        private String lastPackageName = null;

        @Override
        public void onForegroundActivitiesChanged(String packageName) {
            if (packageName.equals(lastPackageName) || cursorView == null) return;
            lastPackageName = packageName;
            mWindowManager.removeView(cursorView);
            Context context = TouchPointer.this;
            KeymapProfiles keymapProfiles = new KeymapProfiles(context);
            if (!keymapProfiles.profileExistsWithPackageName(packageName)) {
                // No profile found, prompt user to create a new profile
                mHandler.post(() -> {
                    cursorView.setVisibility(View.VISIBLE);
                    ProfileSelector.showEnableProfileDialog(context, packageName, enabled ->
                            ProfileSelector.createNewProfileForApp(context, packageName, enabled, profile -> {
                                TouchPointer.this.selectedProfile = profile;
                                reloadKeymap();
                            }));
                    mWindowManager.addView(cursorView, mParams);
                });
            } else {
                // App specific profiles selection dialog
                mHandler.post(() -> {
                    cursorView.setVisibility(View.VISIBLE);
                    ProfileSelector.select(context, profile -> {
                        // Reloading profile
                        TouchPointer.this.selectedProfile = profile;
                        KeymapProfile keymapProfile = keymapProfiles.getProfile(profile);
                        if (!keymapProfile.disabled) {
                            connectRemoteService(keymapProfile);
                            Toast.makeText(TouchPointer.this, "Keymapping enabled for " + packageName, Toast.LENGTH_SHORT).show();
                        } else {
                            try {
                                mService.pauseMouse();
                                cursorView.setVisibility(View.GONE);
                            } catch (RemoteException ignored) {
                            }
                            Toast.makeText(TouchPointer.this, "Keymapping disabled for " + packageName, Toast.LENGTH_SHORT).show();
                        }
                    }, packageName);
                    mWindowManager.addView(cursorView, mParams);
                });
            }
        }
    };
}
