package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;

public class MainActivity extends AppCompatActivity {

    int x = 0;
    int y = 0;
    TextView mouseView;

    public static final int DEFAULT_PORT = 6234;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mouseView = findViewById(R.id.mouseView);
        checkOverlayPermission();
        startService();

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
        startService();
    }

    public static Number hexToDec(String hex)  {
        if (hex == null) {
            throw new NullPointerException("hexToDec: hex String is null.");
        }

        // You may want to do something different with the empty string.
        if (hex.equals("")) { return Byte.valueOf("0"); }

        // If you want to pad "FFF" to "0FFF" do it here.

        hex = hex.toUpperCase();

        // Check if high bit is set.
        boolean isNegative =
                hex.startsWith("8") || hex.startsWith("9") ||
                        hex.startsWith("A") || hex.startsWith("B") ||
                        hex.startsWith("C") || hex.startsWith("D") ||
                        hex.startsWith("E") || hex.startsWith("F");

        BigInteger temp;

        temp = new BigInteger(hex, 16);
        if (isNegative) {
            // Negative number
            BigInteger subtrahend = BigInteger.ONE.shiftLeft(hex.length() * 4);
            temp = temp.subtract(subtrahend);
        }  // Positive number


        // Cut BigInteger down to size.
        if (hex.length() <= 2) { return temp.byteValue(); }
        if (hex.length() <= 4) { return temp.shortValue(); }
        if (hex.length() <= 8) { return temp.intValue(); }
        if (hex.length() <= 16) { return temp.longValue(); }
        return temp;
    }
}
