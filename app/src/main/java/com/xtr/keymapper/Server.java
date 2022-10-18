package com.xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import com.xtr.keymapper.activity.MainActivity;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class Server {

    private final Context context;
    public final String script_name;

    public static final int MAX_LINES = 16;
    public static final long REFRESH_INTERVAL = 200;

    public final TextView cmdView;
    public final TextView cmdView2;
    public StringBuilder c1;
    private StringBuilder c2;
    private int counter1 = 0;
    private int counter2 = 0;


    public Server(Context context){
        this.context=context;
        cmdView =  ((MainActivity)context).findViewById(R.id.cmdview);
        cmdView2 = ((MainActivity)context).findViewById(R.id.cmdview2);
        c1 = new StringBuilder();
        c2 = new StringBuilder();
        textViewUpdaterTask();
        script_name = context.getExternalFilesDir(null) + "/xtMapper.sh";
    }

    private void textViewUpdaterTask() {
        Handler handler = new Handler(Looper.getMainLooper());

        handler.post(new Runnable() {
            public void run() {
                cmdView.setText(c1);
                cmdView2.setText(c2);
                handler.postDelayed(this, REFRESH_INTERVAL);
            }
        });
    }

    public String getDeviceName(){
        SharedPreferences sharedPref = context.getSharedPreferences("devices", MODE_PRIVATE);
        return sharedPref.getString("device", null);
    }

    private void writeScript(String packageName, ApplicationInfo ai, String apk) throws IOException, InterruptedException {
        FileWriter linesToWrite = new FileWriter(script_name);
        
        linesToWrite.append("#!/system/bin/sh\n");
        linesToWrite.append("pkill -f -9 ").append(packageName).append(".Input\n");
        linesToWrite.append("pkill -f -9 libgetevent.so\n");

        linesToWrite.append("LD_LIBRARY_PATH=\"").append(ai.nativeLibraryDir)  //path containing lib*.so
                .append("\" CLASSPATH=\"").append(apk) 
                .append("\" /system/bin/app_process /system/bin ")
                .append(packageName).append(".Input ")
                .append(getDeviceName()).append("\n"); // input device node as argument

        linesToWrite.flush();
        linesToWrite.close();
    }

    public static Thread killServer(){
        return new Thread(() -> {
            try {
                Socket socket = new Socket("127.0.0.1", MainActivity.DEFAULT_PORT_2);
                PrintWriter pOut = new PrintWriter(socket.getOutputStream());
                pOut.println("exit");
                pOut.flush(); pOut.close();
            } catch (IOException e) {
                Log.e("I/O error", e.toString());
            }
        });
    }

    public void setupServer () {
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            String apk = ai.publicSourceDir; // Absolute path to apk in /data/app
            writeScript(packageName, ai, apk);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            Log.e("Server", e.toString());
        }

    }

    public void startServer() {
        if(getDeviceName() != null) {
            updateCmdView1("exec sh " + script_name);
            try {
                Process sh = Utils.getRootAccess();
                DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
                outputStream.writeBytes("/system/bin/sh " + script_name);
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
        if(counter1 < MAX_LINES) {
            c1.append(s).append("\n");
            counter1++;
        } else {
            counter1 = 0;
            c1 = new StringBuilder();
        }
    }
    public void updateCmdView2(String s){
        if(counter2 < MAX_LINES) {
            c2.append(s).append("\n");
            counter2++;
        } else {
            counter2 = 0;
            c2 = new StringBuilder();
        }
    }
}
