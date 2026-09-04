package xtr.keymapper.activity;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.topjohnwu.superuser.Shell;

import kotlin.Unit;
import rikka.shizuku.Shizuku;
import xtr.keymapper.BuildConfig;
import xtr.keymapper.IRemoteService;
import xtr.keymapper.R;
import xtr.keymapper.Server;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.editor.EditorActivity;
import xtr.keymapper.editor.EditorUI;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.server.RemoteServiceHelper;
import xtr.keymapper.GameKeyMapperBridge;

public class MainActivity extends AppCompatActivity {
    public static final String SHELL_INIT = "shell";
    public TouchPointer pointerOverlay;

    private String selectedProfileName = null;
    private boolean composeServiceActive = false;

    private boolean isServiceBound = false;

    static {
        // Set settings before the main shell can be created
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        );
    }

    private DisplaySelector displaySelector;
    private Boolean startedFromShell;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (startedFromShell == null) startedFromShell = isStartedWithShell();

        GameKeyMapperBridge.installGameKeyMapperContent(this);

        KeymapConfig keymapConfig = new KeymapConfig(this);

        /*
         * If user has enabled "Use Shizuku" but this activity was started from shell (waydroid or adb)
         * Then reset "Use Shizuku" setting to false since it crashes the app
         */
        if (startedFromShell && keymapConfig.useShizuku) {
            keymapConfig.useShizuku = false;
            keymapConfig.applySharedPrefs();
        }

        RemoteServiceHelper.useShizuku = keymapConfig.useShizuku;
        Server.setupServer(this, mCallback);


        if (!startedFromShell) {
            /*
             * If user has not enabled "Use Shizuku" from settings
             * Then Check for root access
             *   - if root access is granted then auto-start
             *   - if root access is not granted check if shizuku app is installed and prompt user to enable shizuku
             * Or if user has enabled shizuku then check shizuku permission
             */
            if(!RemoteServiceHelper.useShizuku) {
                Shell.getShell(shell -> {
                    // Ask user to enable shizuku if shizuku app detected
                    if (Shizuku.pingBinder() || getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api") != null) {
                        showAlertDialog(R.string.detected_shizuku, R.string.use_shizuku_for_activation, (dialog, which) -> {
                            RemoteServiceHelper.useShizuku = keymapConfig.useShizuku = true;
                            keymapConfig.applySharedPrefs();
                            alertShizukuNotAuthorized();
                        }, R.string.ok);
                    } else if (Boolean.FALSE.equals(Shell.isAppGrantedRoot())) {
                        alertRootAccessNotFound();
                    }
                });
            } else if (!Shizuku.pingBinder()) {
                alertShizukuNotRunning();
            } else if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
                alertShizukuNotAuthorized();
            }
        }

        displaySelector = new DisplaySelector(this).register(this::startPointer);
        RemoteServiceHelper.runIfActive(this, () -> runOnUiThread(() -> composeServiceActive = true));

        if (startedFromShell) {
            if (SHELL_INIT.equals(getIntent().getStringExtra("data")))
                startPointer();
        }
    }

    /**
     * Check if this activity was started with am shell command
     * Also handles crash report from server and shows a dialog with crash log
     * @return true if this activity was started with am shell command
     */
    private boolean isStartedWithShell() {
        String data = getIntent().getStringExtra("data");
        if (data != null) {
            if (!SHELL_INIT.equals(data)) {
                // Crash report
                new MaterialAlertDialogBuilder(MainActivity.this).setTitle("Server crashed")
                        .setMessage(data)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
            return true;
        }
        return false;
    }

    private void launchSettings() {
        final Context context = MainActivity.this;
        var remoteServiceCallback = new RemoteServiceHelper.RootRemoteServiceCallback() {

            private EditorUI editorUi = new EditorUI(context, this::editorCallback, null, EditorUI.START_SETTINGS);

            private void editorCallback() { editorUi = null; }

            @Override
            public void onConnection(IRemoteService remoteService) {
                if (remoteService != null && editorUi != null) try {
                    editorUi.registerOnKeyEventListener(remoteService);
                } catch (RemoteException e) {
                    Log.e("MainActivity", e.getMessage(), e);
                    editorUi.unregisterOnKeyEventListener();
                }
            }
        };
        remoteServiceCallback.editorUi.openSettings();
        RemoteServiceHelper.getInstance(context, remoteServiceCallback);

    }

    private void launchApp() {
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok);
        } else {
            if (isServiceBound) pointerOverlay.launchProfile(selectedProfileName);
            else startPointer();
        }
    }

    public void startPointer() {
        DisplayManager displayManager = getSystemService(DisplayManager.class);

        if (displayManager.getDisplays().length > 1) {
            displaySelector.launch();
        } else {
            startPointer(null);
        }
    }

    private Unit startPointer(Integer displayId) {
        checkOverlayPermission(this);
        // Start service with selected profile if display on top permission is granted
        if(Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(this, TouchPointer.class);
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfileName);
            if (displayId != null) intent.putExtra(TouchPointer.DISPLAY_ID, displayId.intValue());
            isServiceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            ContextCompat.startForegroundService(this, intent);
            setButtonState(false);
            requestNotificationPermission();
        }
        if (RemoteServiceHelper.useShizuku) {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PERMISSION_GRANTED)
                alertShizukuNotAuthorized();
        } else if (Boolean.FALSE.equals(Shell.isAppGrantedRoot())) {
            // No need to alert about root access if started from shell
            if (!startedFromShell) alertRootAccessAndExit();
        }

        return null;
    }

    private void setButtonState(boolean start) {
        composeServiceActive = !start;
    }

    public void stopPointer(){
        unbindTouchPointer();
        Intent intent = new Intent(this, TouchPointer.class);
        stopService(intent);
        setButtonState(true);
    }

    private void unbindTouchPointer() {
        if (pointerOverlay != null) {
            pointerOverlay.setActivityCallback(null);
            pointerOverlay = null;
        }
        if (isServiceBound) {
            unbindService(connection);
            isServiceBound = false;
        }
    }

    private void startEditor(){
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok);
        } else {
            Intent intent = new Intent(this, EditorActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfileName);
            startActivity(intent);
        }
    }

    public static void checkOverlayPermission(Context context){
        if (!Settings.canDrawOverlays(context)) {
            // Send user to the device settings
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            context.startActivity(intent);
        }
    }

    private void requestNotificationPermission(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!(checkSelfPermission(POST_NOTIFICATIONS) ==
                    PERMISSION_GRANTED)) requestPermissions(new String[]{POST_NOTIFICATIONS}, 0);
        }
    }

    public void alertRootAccessNotFound() {
        showAlertDialog(R.string.root_not_found_title, R.string.root_not_found_message, (dialog, which) -> {
            Intent launchIntent = MainActivity.this.getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
            if (launchIntent != null) {
                startActivity(launchIntent);
                System.exit(0);
            }
        }, R.string.ok);
    }

    public void alertRootAccessAndExit() {
        showAlertDialog(R.string.root_no_privileges_title, R.string.root_no_privileges_message, (dialog, which) -> {
            finishAffinity();
            System.exit(0);
        }, R.string.ok);
    }

    private void alertShizukuNotAuthorized() {
        if(Shizuku.pingBinder()) Shizuku.requestPermission(0);
        showAlertDialog(R.string.shizuku_not_authorized_title, R.string.shizuku_not_authorized_message, (dialog, which) -> launchShizukuAndExit(), R.string.ok);
    }

    private void alertShizukuNotRunning() {
        showAlertDialog(R.string.shizuku_not_running_title, R.string.shizuku_not_running_message, (dialog, which) -> launchShizukuAndExit(), R.string.start);
    }

    private void launchShizukuAndExit() {
        Intent launchIntent = MainActivity.this.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launchIntent != null) {
            startActivity(launchIntent);
            System.exit(0);
        }
    }


    private void showAlertDialog(@StringRes int titleId, @StringRes int messageId, @Nullable DialogInterface.OnClickListener listener, @StringRes int ok) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MainActivity.this);
        builder.setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(ok, listener)
                .setNegativeButton(R.string.cancel, null);
        runOnUiThread(() -> builder.create().show());
    }


    // ============ COMPOSE UI BRIDGE ============

    public boolean isComposeServiceActive() {
        return composeServiceActive;
    }

    public String selectedProfileNameForCompose() {
        return selectedProfileName;
    }

    public void toggleServiceFromCompose() {
        if (composeServiceActive) stopPointer();
        else startPointer();
    }

    public void fixPermissionFromCompose() {
        checkOverlayPermission(this);
    }

    public void testInputFromCompose() {
        Toast.makeText(this, "Input tester is not exposed by the legacy implementation.", Toast.LENGTH_SHORT).show();
    }

    public void selectProfileFromCompose(String profileName) {
        onProfileSelected(profileName);
    }

    public void launchAppFromCompose() {
        launchApp();
    }

    public void startEditorFromCompose() {
        startEditor();
    }

    public void renameProfileFromCompose(String profileName, Runnable onComplete) {
        final Context context = MainActivity.this;
        xtr.keymapper.databinding.TextFieldBinding field =
                xtr.keymapper.databinding.TextFieldBinding.inflate(getLayoutInflater());
        field.getRoot().setHint(R.string.profile_name);
        field.editText.setText(profileName);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_alert_add_profile)
                .setView(field.getRoot())
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String renamed = field.editText.getText().toString();
                    new xtr.keymapper.keymap.KeymapProfiles(context).renameProfile(profileName, renamed);
                    if (onComplete != null) runOnUiThread(onComplete);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void changeProfileAppFromCompose(String profileName, Runnable onComplete) {
        final Context context = MainActivity.this;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        xtr.keymapper.profiles.ProfilesApps.asyncLoadAppsAndThen(context, builder,
                (p, adapter, loadingDialog) -> {
                    p.binding.appsGrid.setAdapter(adapter);
                    loadingDialog.dismiss();

                    androidx.appcompat.app.AlertDialog dialog = builder.setView(p.appsView).show();
                    p.setListener(packageName -> {
                        new xtr.keymapper.keymap.KeymapProfiles(context)
                                .setProfilePackageName(profileName, packageName);
                        p.onDestroyView();
                        dialog.dismiss();
                        if (onComplete != null) runOnUiThread(onComplete);
                    });
                });
    }

    public void deleteProfileFromCompose(String profileName) {
        new xtr.keymapper.keymap.KeymapProfiles(this).deleteProfile(profileName);
        if (profileName != null && profileName.equals(selectedProfileName)) {
            selectedProfileName = null;
        }
    }

    public void stopPointerFromCompose() {
        stopPointer();
    }

    public void openHelpFromCompose() {
        startActivity(new Intent(this, InfoActivity.class));
    }

    public void createNewProfileFromCompose(Runnable onComplete) {
        xtr.keymapper.profiles.ProfileSelector.createNewProfile(this, profileName -> {
            selectedProfileName = profileName;
            if (onComplete != null) runOnUiThread(onComplete);
        });
    }

    public void openExportImportFromCompose() {
        startActivity(new Intent(this, ImportExportActivity.class));
    }

    public void toggleOverlayFromCompose() {
        if (!Settings.canDrawOverlays(this)) checkOverlayPermission(this);
    }

    public void openSettingsFromCompose() {
        launchSettings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindTouchPointer();
    }

    public void onProfileSelected(String profileName) {
        this.selectedProfileName = profileName;
    }

    private final TouchPointer.MainActivityCallback mCallback = new TouchPointer.MainActivityCallback() {

        public void updateCmdView1(String line) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, line, Toast.LENGTH_SHORT).show());
        }

        public void stopPointer() {
            MainActivity.this.stopPointer();
        }
    };

    /** Defines callbacks for service binding, passed to bindService() */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            isServiceBound = true;
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            pointerOverlay = binder.getService();
            pointerOverlay.setActivityCallback(mCallback);
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isServiceBound = false;
        }
    };
}
