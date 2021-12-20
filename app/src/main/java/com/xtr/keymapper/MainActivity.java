package com.xtr.keymapper;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;

public class MainActivity extends AppCompatActivity {

    TextView cmdView;
    String points;
    String line = "null";
    public static final int DEFAULT_PORT = 6234;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        cmdView = findViewById(R.id.mouseView);
        Button startButton = findViewById(R.id.startPointer);
        startButton.setOnClickListener(v -> startService());
        Button startServerButton = findViewById(R.id.startServer);
        startServerButton.setOnClickListener(v -> startServer());
        checkOverlayPermission();
    }

    public void startService(){

        if(Settings.canDrawOverlays(this)) {
            // start the service based on the android version
            startForegroundService(new Intent(this, ForegroundService.class));

        }
    }

    public void startServer() {
        new Thread(() -> {
            try{
                PackageManager pm = this.getPackageManager();
                String packageName = this.getPackageName();
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                String apk = ai.publicSourceDir;
                Process sh = Runtime.getRuntime().exec("su");
                DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(sh.getInputStream()));
                outputStream.writeBytes("CLASSPATH=\""+ apk +"\" /system/bin/app_process32 /system/bin "+ packageName + ".Input"+"\n");
                outputStream.flush();
                SystemClock.sleep(2000);
                while ((line = stdInput.readLine()) != null) {
                    runOnUiThread(() -> {
                        points += "\n" + line;
                        MainActivity.this.cmdView.setText(points);
                    });
                }
            } catch (IOException | PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void setupServer() {
        try {
            PackageManager pm = this.getPackageManager();
            String packageName = this.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            String apk = ai.publicSourceDir;
            Process sh = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("cat > /mnt/xtr.keymapper.sh" + "\n");
            outputStream.writeBytes("#!/system/bin/sh\n");
            outputStream.writeBytes("CLASSPATH=\"" + apk + "\" /system/bin/app_process32 /system/bin " + packageName + ".Input" + "\n");
            sh.destroy();
            sh = Runtime.getRuntime().exec("su");
            outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("chmod 777 /mnt/xtr.keymapper.sh\n");
            outputStream.writeBytes("exit\n");
            outputStream.flush();
            outputStream.close();
            sh.waitFor();
        } catch (IOException | PackageManager.NameNotFoundException | InterruptedException e) {
            e.printStackTrace();
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
