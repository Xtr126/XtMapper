package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;



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
        new Thread(pointerOverlay::handleMouseEvents).start();

        initFab(); setupFab();
    }

    private void setupFab() {
        startServerButton.setOnClickListener(v -> new Thread(server::startServer).start());
        startInTerminal.setOnClickListener(v -> server.setupServer());
        startOverlayButton.setOnClickListener(v -> startService(startOverlayButton));
        keymap.setOnClickListener(v -> startEditor());
        configureButton.setOnClickListener(v -> startActivity(new Intent(this, InputDeviceSelector.class)));
        infoButton.setOnClickListener(v -> startActivity(new Intent(this, InfoActivity.class)));
    }

    private void initFab(){
        startOverlayButton = findViewById(R.id.startPointer);
        startServerButton = findViewById(R.id.startServer);
        startInTerminal = findViewById(R.id.startServerM);
        keymap = findViewById(R.id.start_editor);
        configureButton = findViewById(R.id.config_pointer);
        infoButton = findViewById(R.id.about_button);
    }

    private void startService(Button startButton){
        checkOverlayPermission();
        setButtonActive(startButton);

        if(Settings.canDrawOverlays(this)) {
            pointerOverlay.open(startButton);
        }
    }

    public void setButtonActive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.purple_700)));
    }

    public void setButtonInactive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.grey)));
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
            super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

}
