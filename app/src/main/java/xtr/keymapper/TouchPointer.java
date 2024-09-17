package xtr.keymapper;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.UiThread;

import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.databinding.CursorBinding;
import xtr.keymapper.editor.EditorActivity;
import xtr.keymapper.editor.EditorService;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.keymap.KeymapProfile;
import xtr.keymapper.keymap.KeymapProfiles;
import xtr.keymapper.profiles.ProfileSelector;
import xtr.keymapper.server.RemoteServiceHelper;


public class TouchPointer extends Service {
    private final IBinder binder = new TouchPointerBinder();
    public MainActivity.Callback activityCallback;
    public IRemoteService mService;
    public String selectedProfile = null;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean activityRemoteCallback = false;
    private WindowManager mWindowManager;


    public class TouchPointerBinder extends Binder {
        public TouchPointer getService() {
            // Return this instance of TouchPointer so clients can call public methods
            return TouchPointer.this;
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(2, notification);
        }

        // Launch default profile

        this.selectedProfile = i.getStringExtra(EditorActivity.PROFILE_NAME);
        if (this.selectedProfile == null) {
            this.selectedProfile = "Default";
        }

        KeymapProfile keymapProfile = new KeymapProfiles(this).getProfile(selectedProfile);
        connectRemoteService(keymapProfile);

        return super.onStartCommand(i, flags, startId);
    }

    public void launchProfile(String profileName) {
        this.selectedProfile = profileName;
        KeymapProfile keymapProfile = new KeymapProfiles(this).getProfile(selectedProfile);
        connectRemoteService(keymapProfile);
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(keymapProfile.packageName);
        if (launchIntent != null) startActivity(launchIntent);
    }

    private void connectRemoteService(KeymapProfile profile) {
        if (activityCallback != null) activityCallback.updateCmdView1("\n connecting to server..");
        RemoteServiceHelper.getInstance(this, service -> {
            mService = service;
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
            mWindowManager = getSystemService(WindowManager.class);
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

        });
    }

    @Override
    public void onDestroy() {
        if (mService != null) try {
            mService.unregisterActivityObserver(mActivityObserverCallback);
            mService.stopServer();
        } catch (Exception e) {
            Log.e("stopServer", e.toString(), e);
        }
        mService = null;
        activityCallback = null;
        super.onDestroy();
    }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    public final IRemoteServiceCallback mCallback = new IRemoteServiceCallback.Stub() {

        private View cursorView = null;

        @Override
        public void launchEditor() {
            Intent intent = new Intent(TouchPointer.this, EditorService.class);
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);
            startService(intent);
        }

        @Override
        public void alertMouseAimActivated() {
            // Notifying user that shooting mode was activated
            mHandler.post(() -> Toast.makeText(TouchPointer.this, R.string.mouse_aim_activated, Toast.LENGTH_SHORT
            ).show());
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

        @Override
        public void enablePointer()  {
            mHandler.post(() -> {
                if(cursorView == null) {
                    cursorView = CursorBinding.inflate(LayoutInflater.from(
                            new ContextThemeWrapper(TouchPointer.this, R.style.Theme_XtMapper)
                    )).getRoot();
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
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
                    mWindowManager.addView(cursorView, params);
                }
                cursorView.setVisibility(View.VISIBLE);
            });
        }

        @Override
        public void disablePointer()  {
            mHandler.post(() -> {
                if (cursorView != null) cursorView.setVisibility(View.GONE);
            });
        }

        @Override
        public void setCursorX(int x)  {
            mHandler.post(() -> {
                if (cursorView != null) cursorView.setX(x);
            });
        }

        @Override
        public void setCursorY(int y)  {
            mHandler.post(() -> {
                if (cursorView != null) cursorView.setY(y);
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
            if (packageName.equals(lastPackageName)) return;
            lastPackageName = packageName;
            Context context = TouchPointer.this;
            KeymapProfiles keymapProfiles = new KeymapProfiles(context);
            if (!keymapProfiles.profileExistsWithPackageName(packageName)) {
                // No profile found, prompt user to create a new profile
                mHandler.post(() -> {
                    ProfileSelector.showEnableProfileDialog(context, packageName, enabled ->
                            ProfileSelector.createNewProfileForApp(context, packageName, enabled, profile -> {
                                TouchPointer.this.selectedProfile = profile;
                                reloadKeymap();
                            }));
                });
            } else {
                // App specific profiles selection dialog
                mHandler.post(() -> {
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
                            } catch (RemoteException ignored) {
                            }
                            Toast.makeText(TouchPointer.this, "Keymapping disabled for " + packageName, Toast.LENGTH_SHORT).show();
                        }
                    }, packageName);
                });
            }
        }
    };
}
