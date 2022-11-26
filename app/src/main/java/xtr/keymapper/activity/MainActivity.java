package xtr.keymapper.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import xtr.keymapper.R;
import xtr.keymapper.Server;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.databinding.ActivityMainBinding;
import xtr.keymapper.fragment.SettingsFragment;

public class MainActivity extends AppCompatActivity {
    public TouchPointer pointerOverlay;
    public Server server;
    public static final long REFRESH_INTERVAL = 200;
    public StringBuilder c1, c2, c3;

    public ActivityMainBinding binding;
    Intent intent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);
        server = new Server(this);
        setupButtons();
        textViewUpdaterTask();
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

    private void startPointer(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            intent = new Intent(this, TouchPointer.class);
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
        if(Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(this, EditorUI.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(intent);
        }
    }

    private void startServer(boolean autorun){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            server.setupServer();
            if (autorun) {
                new Thread(server::startServer).start();
                startPointer();
            } else {
                server.updateCmdView1("run in adb shell:\n sh " + server.script_name);
            }
        }
    }

    private void checkOverlayPermission(){
        if (!Settings.canDrawOverlays(this)) {
            // send user to the device settings
            Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(myIntent);
        }
    }

    private void textViewUpdaterTask() {
        c1 = new StringBuilder();
        c2 = new StringBuilder();
        c3 = new StringBuilder();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                binding.cmdview.view1.setText(c1);
                binding.cmdview.view2.setText(c2);
                binding.cmdview.view3.setText(c3);
                handler.postDelayed(this, REFRESH_INTERVAL);
            }
        });
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            pointerOverlay = binder.getService();
            pointerOverlay.init(MainActivity.this);
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };
}