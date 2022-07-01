package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;

import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;


public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234;

    TouchPointer pointerOverlay;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FloatingActionButton startOverlayButton = findViewById(R.id.startPointer);
        FloatingActionButton startServerButton = findViewById(R.id.startServer);
        FloatingActionButton startInTerminal = findViewById(R.id.startServerM);
        FloatingActionButton keymap = findViewById(R.id.start_editor);
        FloatingActionButton configureButton = findViewById(R.id.config_pointer);

        Server server = new Server(this);
        new Thread(server::startSocketTest).start();
        startServerButton.setOnClickListener(v -> new Thread(server::startServer).start());
        startInTerminal.setOnClickListener(v -> server.setupServer());

        startOverlayButton.setOnClickListener(v -> startService(startOverlayButton));
        keymap.setOnClickListener(v -> startEditor());
        configureButton.setOnClickListener(v -> startActivity(new Intent(this, InputDeviceSelector.class)));
        
    }
    
 
    public void startService(FloatingActionButton startButton){
        checkOverlayPermission();

        startButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.teal_200)));
        startButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.colorAccent)));

        if(Settings.canDrawOverlays(this)) {
             pointerOverlay = new TouchPointer(this);
             pointerOverlay.open();
            startButton.setOnClickListener(v -> {
                startButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.grey)));
                startButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.white2)));
                pointerOverlay.hideCursor();
                startButton.setOnClickListener(view -> startService(startButton));
            });
        }
    }
    public void startEditor(){
        if(Settings.canDrawOverlays(this)) {
            EditorUI window=new EditorUI(this);
            window.open();
        }
    }
    public void checkOverlayPermission(){
            if (!Settings.canDrawOverlays(this)) {
                // send user to the device settings
                Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivity(myIntent);
            }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
    }


}
