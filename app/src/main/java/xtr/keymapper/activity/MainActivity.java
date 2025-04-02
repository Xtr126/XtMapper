package xtr.keymapper.activity;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.topjohnwu.superuser.Shell;

import kotlin.Unit;
import rikka.shizuku.Shizuku;
import xtr.keymapper.BuildConfig;
import xtr.keymapper.R;
import xtr.keymapper.Server;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.databinding.ActivityMainBinding;
import xtr.keymapper.editor.EditorActivity;
import xtr.keymapper.editor.EditorUI;
import xtr.keymapper.keymap.KeymapConfig;
import xtr.keymapper.profiles.ProfilesViewAdapter;
import xtr.keymapper.server.RemoteServiceHelper;

public class MainActivity extends AppCompatActivity implements ProfilesViewAdapter.ProfileSelectedCallback {
    public static final String SHELL_INIT = "shell";
    public TouchPointer pointerOverlay;

    public ActivityMainBinding binding;
    private ColorStateList defaultTint;
    private String selectedProfileName = null;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // User has enabled shizuku or not
        KeymapConfig keymapConfig = new KeymapConfig(this);
        RemoteServiceHelper.useShizuku = keymapConfig.useShizuku;
        Server.setupServer(this, mCallback);

        /*
        * If user has not enabled shizuku from settings
        * Then Check for root access
        *   - if root access is granted then auto-start
        *   - if root access is not granted check if shizuku app is installed and prompt user to enable shizuku
        * Or if user has enabled shizuku then check shizuku permission
        */

        if(!RemoteServiceHelper.useShizuku) {
            Shell.getShell(shell -> {
                RemoteServiceHelper.getInstance(this, null);
                if (Shizuku.pingBinder() || getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api") != null) { // Ask user to enable shizuku if shizuku app detected
                    showAlertDialog(R.string.detected_shizuku, R.string.use_shizuku_for_activation, (dialog, which) -> {
                        RemoteServiceHelper.useShizuku = keymapConfig.useShizuku = true;
                        keymapConfig.applySharedPrefs();
                        alertShizukuNotAuthorized();
                    }, R.string.ok);
                } else if (!RemoteServiceHelper.isRootService) {
                    alertRootAccessNotFound();
                }
            });
        } else if (!Shizuku.pingBinder()) {
            alertShizukuNotRunning();
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            alertShizukuNotAuthorized();
        }

        displaySelector = new DisplaySelector(this).register(this::startPointer);
        setupButtons();

        // Check for if this activity was started with am shell command
        String data = getIntent().getStringExtra("data");
        if (data != null) {
            if (data.equals(SHELL_INIT)) {
                startPointer();
            } else {
                // Crash report
                new MaterialAlertDialogBuilder(MainActivity.this).setTitle("Server crashed")
                        .setMessage(data)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        }
    }

    private void setupButtons() {
        defaultTint = binding.controls.launchApp.getBackgroundTintList();
        binding.controls.launchApp.setOnClickListener(v -> launchApp());
        binding.controls.startPointer.setOnClickListener(v -> startPointer());
        binding.controls.startEditor.setOnClickListener(v -> startEditor());
        binding.controls.configButton.setOnClickListener
                (v -> launchSettings());
        binding.controls.aboutButton.setOnClickListener
                (v -> startActivity(new Intent(this, InfoActivity.class)));
        binding.controls.importExportButton.setOnClickListener
                (v -> startActivity(new Intent(this, ImportExportActivity.class)));

        RemoteServiceHelper.runIfActive(this, () -> runOnUiThread(() -> setButtonState(false)));
    }

    private void launchSettings() {
        EditorUI editorUI = new EditorUI(this, null, null, EditorUI.START_SETTINGS);
        editorUI.openSettings();
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
            startForegroundService(intent);
            setButtonState(false);
            requestNotificationPermission();
        }
        if (RemoteServiceHelper.useShizuku) {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                alertShizukuNotAuthorized();
        } else if (!RemoteServiceHelper.isRootService) {
            alertRootAccessAndExit();
        }

        return null;
    }

    private void setButtonState(boolean start) {
        Button button = binding.controls.startPointer;
        if (start) {
            button.setText(R.string.start);
            button.setOnClickListener(v -> startPointer());
            button.setBackgroundTintList(defaultTint);
        } else {
            button.setText(R.string.stop);
            button.setOnClickListener(v -> stopPointer());
            button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.purple_700)));
        }
    }

    public void stopPointer(){
        unbindTouchPointer();
        Intent intent = new Intent(this, TouchPointer.class);
        stopService(intent);
        setButtonState(true);
    }

    private void unbindTouchPointer() {
        if (pointerOverlay != null) {
            pointerOverlay.activityCallback = null;
            pointerOverlay = null;
        }
        if (isServiceBound) unbindService(connection);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindTouchPointer();
    }

    @Override
    public void onProfileSelected(String profileName) {
        this.selectedProfileName = profileName;
    }

    public interface Callback {
        void updateCmdView1(String line);
        void stopPointer();
    }

    private final Callback mCallback = new Callback() {

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
            pointerOverlay.activityCallback = mCallback;
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isServiceBound = false;
        }
    };
}
