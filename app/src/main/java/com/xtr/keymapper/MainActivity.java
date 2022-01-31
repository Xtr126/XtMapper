package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;


public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234;
    public TextView cmdView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button startButton = findViewById(R.id.startPointer);
        Server server = new Server(this);
        startButton.setOnClickListener(v -> startService());
        Button startServerButton = findViewById(R.id.startServer);
        Button startServerButton2 = findViewById(R.id.startServerM);
        Button keymap = findViewById(R.id.start_editor);
        keymap.setOnClickListener(v -> startEditor());
        cmdView = findViewById(R.id.mouseView);


        startServerButton.setOnClickListener(v -> server.startServer());
        startServerButton2.setOnClickListener(v -> server.setupServer());
        checkOverlayPermission();
    }


    public void startService(){
        if(Settings.canDrawOverlays(this)) {
            // start the service based on the android version
            startForegroundService(new Intent(this, ForegroundService.class));

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
