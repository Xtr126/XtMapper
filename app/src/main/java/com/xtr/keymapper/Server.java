package com.xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class Server {

    private final Context context;
    private final String script_name = "/data/local/tmp/xtMapper.sh\n";

    public static final int MAX_LINES_1 = 16;
    public static final int MAX_LINES_2 = 32;
    public static final long REFRESH_INTERVAL = 200;

    public final TextView cmdView;
    public final TextView cmdView2;
    private StringBuilder c1;
    private StringBuilder c2;
    private int counter1 = 0;
    private int counter2 = 0;


    public Server(Context context){
        this.context=context;
        cmdView =  ((MainActivity)context).findViewById(R.id.cmdview);
        cmdView2 = ((MainActivity)context).findViewById(R.id.cmdview2);
        c1 = new StringBuilder();
        c2 = new StringBuilder();
        textViewUpdaterTask((MainActivity) context);
    }

    private void textViewUpdaterTask(MainActivity context) {
        Handler outputUpdater = new Handler();

        outputUpdater.post(new Runnable() {
            public void run() {
                context.runOnUiThread(() -> cmdView.setText(c1));
                outputUpdater.postDelayed(this, REFRESH_INTERVAL);
            }
        });

        outputUpdater.post(new Runnable() {
            public void run() {
                context.runOnUiThread(() -> cmdView2.setText(c2));
                outputUpdater.postDelayed(this, REFRESH_INTERVAL);
            }
        });
    }

    public String getDeviceName(){
        SharedPreferences sharedPref = context.getSharedPreferences("devices", MODE_PRIVATE);
        return sharedPref.getString("device", null);
    }

    private void writeScript(String packageName, ApplicationInfo ai, String apk, DataOutputStream out) throws IOException, InterruptedException {
        out.writeBytes("cat > " + script_name); // Write contents to file through pipe

        out.writeBytes("#!/system/bin/sh\n");
        out.writeBytes("pkill -f " + packageName + ".Input\n");

        out.writeBytes("LD_LIBRARY_PATH=\"" + ai.nativeLibraryDir + //path containing lib*.so
                "\" CLASSPATH=\"" + apk +
                "\" /system/bin/app_process32 /system/bin " +
                packageName + ".Input " + getDeviceName() + "\n"); // input device node as argument
    }

    public static void killServer(String packageName){
        try {
        Process sh = Runtime.getRuntime().exec("su");
        DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
        outputStream.writeBytes("pkill -f " + packageName + ".Input\n");
        outputStream.writeBytes("pkill -f libgetevent.so\n");
        outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setExecPermission(DataOutputStream out) throws IOException, InterruptedException {
        out.writeBytes("touch " + script_name);
        out.writeBytes("chmod 777 " + script_name);
    }

    public void setupServer () {
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            String apk = ai.publicSourceDir; // Absolute path to apk in /data/app

            Process sh = Runtime.getRuntime().exec("su");
            DataOutputStream out = new DataOutputStream(sh.getOutputStream());

            setExecPermission(out);
            writeScript(packageName, ai, apk, out);
            out.close(); sh.waitFor();
            // Notify user
            updateCmdView1("run " + script_name);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            Log.e("Server", e.toString());
        }

    }

    public void startServer() {
        if(getDeviceName() != null) {
            updateCmdView1("starting server");
            try {
                Process sh = Runtime.getRuntime().exec("su");
                DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
                outputStream.writeBytes(script_name);
                outputStream.close();

                BufferedReader stdout = new BufferedReader(new InputStreamReader(sh.getInputStream()));
                String line;
                while ((line = stdout.readLine()) != null) {
                    updateCmdView2("stdout: " + line);
                }
                sh.waitFor();
            } catch (IOException | InterruptedException e) {
                Log.e("Server", e.toString());
            }
        } else {
            updateCmdView1("Please select input device");
        }
    }

    public void updateCmdView1(String s){
        if(counter1 < MAX_LINES_2) {
            c1.append(s).append("\n");
            counter1++;
        } else {
            counter1 = 0;
            c1 = new StringBuilder();
        }
    }
    public void updateCmdView2(String s){
        if(counter2 < MAX_LINES_1) {
            c2.append(s).append("\n");
            counter2++;
        } else {
            counter2 = 0;
            c2 = new StringBuilder();
        }
    }
}
