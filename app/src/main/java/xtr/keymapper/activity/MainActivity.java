package xtr.keymapper.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import xtr.keymapper.EditorService;
import xtr.keymapper.R;
import xtr.keymapper.Server;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.databinding.ActivityMainBinding;
import xtr.keymapper.fragment.SettingsFragment;

public class MainActivity extends AppCompatActivity {
    public TouchPointer pointerOverlay;
    private final Server server = new Server();

    public ActivityMainBinding binding;
    private Intent intent;

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
        binding.controls.startServer.setOnClickListener(v -> startServer(true));
        binding.controls.startInTerminal.setOnClickListener(v -> startServer(false));
        binding.controls.startPointer.setOnClickListener(v -> startPointer());
        binding.controls.startEditor.setOnClickListener(v -> startEditor());
        binding.controls.configButton.setOnClickListener
                (v -> new SettingsFragment(this).show(getSupportFragmentManager(), "dialog"));
        binding.controls.aboutButton.setOnClickListener
                (v -> startActivity(new Intent(this, InfoActivity.class)));
    }

    public void startPointer(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            Context context = getApplicationContext();
            intent = new Intent(context, TouchPointer.class);
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
            startForegroundService(intent);

            setButtonActive(binding.controls.startPointer);
            binding.controls.startPointer.setOnClickListener(v -> stopPointer());
        }
        }

    public void stopPointer(){
        pointerOverlay.hideCursor();
        unbindService(connection);
        stopService(intent);

        setButtonInactive(binding.controls.startPointer);
        binding.controls.startPointer.setOnClickListener(v -> startPointer());
    }

    public void setButtonActive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.purple_700)));
    }

    public void setButtonInactive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.grey)));
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
            else mCallback.updateCmdView1("run in adb shell:\n sh " + server.script_name);
        }
    }

    private void checkOverlayPermission(){
        if (!Settings.canDrawOverlays(this)) {
            // send user to the device settings
            Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(myIntent);
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
            MainActivity.this.startPointer();
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