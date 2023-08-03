package xtr.keymapper.activity;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xtr.keymapper.R;
import xtr.keymapper.Server;
import xtr.keymapper.touchpointer.TouchPointer;
import xtr.keymapper.databinding.ActivityMainBinding;
import xtr.keymapper.editor.EditorService;
import xtr.keymapper.fragment.SettingsFragment;
import xtr.keymapper.server.RemoteService;

public class MainActivity extends AppCompatActivity {
    public TouchPointer pointerOverlay;
    private final Server server = new Server();

    public ActivityMainBinding binding;
    private Intent intent;
    private ColorStateList defaultTint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        server.mCallback = this.mCallback;
        server.setupServer(this);

        setupButtons();
    }

    private void setupButtons() {
        defaultTint = binding.controls.startServer.getBackgroundTintList();
        binding.controls.startServer.setOnClickListener(v -> startServer(true));
        binding.controls.startInTerminal.setOnClickListener(v -> startServer(false));
        binding.controls.startPointer.setOnClickListener(v -> startPointer());
        binding.controls.startEditor.setOnClickListener(v -> startEditor());
        binding.controls.configButton.setOnClickListener
                (v -> new SettingsFragment(this).show(getSupportFragmentManager(), "dialog"));
        binding.controls.aboutButton.setOnClickListener
                (v -> startActivity(new Intent(this, InfoActivity.class)));
        if (RemoteService.getInstance() != null) {
            binding.cmdview.view1.setText(R.string.activated_start);
            binding.controls.startServer.setEnabled(false);
        }
    }

    public void startPointer(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            Context context = getApplicationContext();
            intent = new Intent(context, TouchPointer.class);
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
            startForegroundService(intent);

            setButtonActive(binding.controls.startPointer);
            binding.controls.startPointer.setText(R.string.stop);
            binding.controls.startPointer.setOnClickListener(v -> stopPointer());
            requestNotificationPermission();
        }
    }

    public void stopPointer(){
        pointerOverlay.hideCursor();
        unbindService(connection);
        stopService(intent);

        setButtonInactive(binding.controls.startPointer);
        binding.controls.startPointer.setText(R.string.start);
        binding.controls.startPointer.setOnClickListener(v -> startPointer());
    }

    public void setButtonActive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.purple_700)));
    }

    public void setButtonInactive(Button button){
        button.setBackgroundTintList(defaultTint);
    }

    private void startEditor(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this))
            startService(new Intent(this, EditorService.class));
    }

    private void startServer(boolean autorun){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            if (autorun) new Thread(server::startServer).start();
            else mCallback.updateCmdView1("run in adb shell:\n sh " + server.script.getPath());
        }
    }

    private void checkOverlayPermission(){
        if (!Settings.canDrawOverlays(this)) {
            // send user to the device settings
            Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(myIntent);
        }
    }

    private void requestNotificationPermission(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!(checkSelfPermission(POST_NOTIFICATIONS) ==
                    PERMISSION_GRANTED)) requestPermissions(new String[]{POST_NOTIFICATIONS}, 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (pointerOverlay != null)
            pointerOverlay.activityCallback = null;
        super.onDestroy();
    }

    public interface Callback {
        void updateCmdView1(String line);
        void updateCmdView2(String line);
        void stopPointer();
        void startPointer();
        void alertRootAccessNotFound();
    }

    private final Callback mCallback = new Callback() {
        public static final int MAX_LINES = 16;
        private int counter1 = 0;
        private int counter2 = 0;
        private final StringBuilder c1 = new StringBuilder();
        private final StringBuilder c2 = new StringBuilder();

        public void updateCmdView1(String line) {
            c1.append(line);

            if (counter1 < MAX_LINES) counter1++;
            else c1.delete(0, c1.indexOf("\n") + 1);

            runOnUiThread(() -> binding.cmdview.view1.setText(c1));
        }

        public void updateCmdView2(String line) {
            c2.append(line);

            if (counter2 < MAX_LINES) counter2++;
            else c2.delete(0, c2.indexOf("\n") + 1);

            runOnUiThread(() -> binding.cmdview.view2.setText(c2));
        }

        public void stopPointer() {
            MainActivity.this.stopPointer();
        }

        public void startPointer() {
            runOnUiThread(MainActivity.this::startPointer);
        }

        @Override
        public void alertRootAccessNotFound() {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MainActivity.this);
            builder.setTitle(R.string.root_not_found_title)
            .setMessage(R.string.root_not_found_message)
                    .setPositiveButton("OK", (dialog, which) -> {
                        Intent launchIntent = MainActivity.this.getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
                        startActivity(launchIntent);
                    });
            runOnUiThread(() -> builder.create().show());
        }
    };

    /** Defines callbacks for service binding, passed to bindService() */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            pointerOverlay = binder.getService();
            pointerOverlay.activityCallback = mCallback;
            if(!pointerOverlay.connected) pointerOverlay.init();
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };
}
