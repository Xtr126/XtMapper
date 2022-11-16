package com.xtr.keymapper.activity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.xtr.keymapper.R;
import com.xtr.keymapper.Server;
import com.xtr.keymapper.TouchPointer;
import com.xtr.keymapper.databinding.ActivityMainBinding;
import com.xtr.keymapper.fragment.SettingsFragment;

public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234, DEFAULT_PORT_2 = 6345;
    public TouchPointer pointerOverlay;
    public Server server;

    public ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);
        server = new Server(this);
        pointerOverlay = new TouchPointer(this);
        setupButtons();
    }

    private void setupButtons() {
        binding.startServer.setOnClickListener(v -> startServer(true));
        binding.startInTerminal.setOnClickListener(v -> startServer(false));
        binding.startPointer.setOnClickListener(v -> startService());
        binding.startEditor.setOnClickListener(v -> startEditor());
        binding.configButton.setOnClickListener
                (v -> new SettingsFragment(this).show(getSupportFragmentManager(), "dialog"));
        binding.aboutButton.setOnClickListener
                (v -> startActivity(new Intent(this, InfoActivity.class)));
    }

    private void startService(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            pointerOverlay.open();
        }
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
                startService();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}