package com.xtr.keymapper.activity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.xtr.keymapper.R;
import com.xtr.keymapper.Server;
import com.xtr.keymapper.TouchPointer;
import com.xtr.keymapper.fragment.SettingsFragment;

public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234;
    public static final int DEFAULT_PORT_2 = 6345;
    public TouchPointer pointerOverlay;
    public Server server;

    private Button startOverlayButton;
    private Button startServerButton;
    private Button startInTerminal;
    private Button keymap;
    private Button configureButton;
    private Button infoButton;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        server = new Server(this);
        pointerOverlay = new TouchPointer(this);
        initButtons(); setupButtons();
    }

    private void setupButtons() {
        startServerButton.setOnClickListener(v -> startServer(true));
        startInTerminal.setOnClickListener(v -> startServer(false));
        startOverlayButton.setOnClickListener(v -> startService());
        keymap.setOnClickListener(v -> startEditor());
        configureButton.setOnClickListener
                (v -> SettingsFragment.newInstance().show(getSupportFragmentManager(), "dialog"));
        infoButton.setOnClickListener
                (v -> startActivity(new Intent(this, InfoActivity.class)));
    }

    private void initButtons(){
        startOverlayButton = findViewById(R.id.startPointer);
        startServerButton = findViewById(R.id.startServer);
        startInTerminal = findViewById(R.id.startServerM);
        keymap = findViewById(R.id.start_editor);
        configureButton = findViewById(R.id.config_pointer);
        infoButton = findViewById(R.id.about_button);
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
            startActivity(new Intent(this, EditorUI.class));
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
