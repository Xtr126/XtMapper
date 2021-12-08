package com.xtr.keymapper;

import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    int x = 0;
    int y = 0;
    TextView mouseView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mouseView = findViewById(R.id.mouseView);
        checkOverlayPermission();
        startService();
        startMouse();

    }

    // method for starting the service
    public void startService(){

            // check if the user has already granted
            // the Draw over other apps permission
            if(Settings.canDrawOverlays(this)) {
                // start the service based on the android version
                    startForegroundService(new Intent(this, ForegroundService.class));

            }

    }

    // method to ask user to grant the Overlay permission
    public void checkOverlayPermission(){


            if (!Settings.canDrawOverlays(this)) {
                // send user to the device settings
                Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivity(myIntent);
            }

    }

    // check for permission again when user grants it from
    // the device settings, and start the service
    @Override
    protected void onResume() {
        super.onResume();
        x = y = 0;
        ((TextView)findViewById(R.id.x86Msg)).setText(stringFromJNI());
        startService();
    }

    @Keep
    private void updateMouse() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String points = "x :" + com.xtr.keymapper.MainActivity.this.x + " " +
                        "y : " + com.xtr.keymapper.MainActivity.this.y;
                com.xtr.keymapper.MainActivity.this.mouseView.setText(points);
            }
        });
    }
    @Keep
    private void updateMouseX() {
        x+=2;
    }
    @Keep
    private void updateMouseY() {
        y+=2;
    }
    @Keep
    private void updateMouseXn() {
        x-=2;
    }
    @Keep
    private void updateMouseYn() {
        y-=2;
    }

    static {
        System.loadLibrary("mouse_read");
    }
    public native String stringFromJNI();
    public native void startMouse();


}
