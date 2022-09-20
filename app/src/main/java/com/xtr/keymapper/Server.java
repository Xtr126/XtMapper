package com.xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class Server {

    private final Context context;
    private final String script_name = "/data/xtr.keymapper.sh\n";

    public final TextView cmdView;
    public final TextView cmdView2;

    public Server(Context context){
        this.context=context;
        cmdView =  ((MainActivity)context).findViewById(R.id.cmdview);
        cmdView2 = ((MainActivity)context).findViewById(R.id.cmdview2);
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

    public void setupServer() {
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
            updateCmdView("run " + script_name);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void startServer() {
        if(getDeviceName() != null) {
            try {
            setupServer();
            updateCmdView("starting server");
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
                e.printStackTrace();
            }
        } else {
            updateCmdView("Please select input device");
        }
    }


    public void updateCmdView(String s){
        ((MainActivity)context).runOnUiThread(() -> cmdView.append(s + "\n"));
    }
    public void updateCmdView2(String s){
        ((MainActivity)context).runOnUiThread(() -> cmdView2.append(s + "\n"));
    }

    
}
