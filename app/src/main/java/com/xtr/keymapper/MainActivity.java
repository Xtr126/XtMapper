package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;

import com.google.android.material.floatingactionbutton.FloatingActionButton;


public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234;
    public TouchPointer pointerOverlay;
    public Server server;

    private FloatingActionButton startOverlayButton;
    private FloatingActionButton startServerButton;
    private FloatingActionButton startInTerminal;
    private FloatingActionButton keymap;
    private FloatingActionButton configureButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        server = new Server(this);
        pointerOverlay = new TouchPointer(this);

        new Thread(server::startSocketTest).start(); //start app side listener socket

        initFab(); setupFab();
    }

    private void setupFab() {
        startServerButton.setOnClickListener(v -> new Thread(server::startServer).start());
        startInTerminal.setOnClickListener(v -> server.setupServer());
        startOverlayButton.setOnClickListener(v -> startService(startOverlayButton));
        keymap.setOnClickListener(v -> startEditor());
        configureButton.setOnClickListener(v -> startActivity(new Intent(this, InputDeviceSelector.class)));
    }

    private void initFab(){
        startOverlayButton = findViewById(R.id.startPointer);
        startServerButton = findViewById(R.id.startServer);
        startInTerminal = findViewById(R.id.startServerM);
        keymap = findViewById(R.id.start_editor);
        configureButton = findViewById(R.id.config_pointer);

    }

    private void startService(FloatingActionButton startButton){
        checkOverlayPermission();
        setButtonActive(startButton);

        if(Settings.canDrawOverlays(this)) {
            pointerOverlay.open();
        }

        startButton.setOnClickListener(v -> {
            setButtonInactive(startButton);
            pointerOverlay.hideCursor();
            startButton.setOnClickListener(view -> startService(startButton));
        });
    }

    public void setButtonActive(FloatingActionButton button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.teal_200)));
        button.setImageTintList(ColorStateList.valueOf(getColor(R.color.colorAccent)));
    }

    public void setButtonInactive(FloatingActionButton button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.grey)));
        button.setImageTintList(ColorStateList.valueOf(getColor(R.color.white2)));
    }

    private void startEditor(){
        if(Settings.canDrawOverlays(this)) {
            EditorUI window=new EditorUI(this);
            window.open();
        }
    }
    private void checkOverlayPermission(){
            if (!Settings.canDrawOverlays(this)) {
                // send user to the device settings
                Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivity(myIntent);
            }
    }
    protected void onDestroy() {
        this.finish(); System.exit(0);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

}
