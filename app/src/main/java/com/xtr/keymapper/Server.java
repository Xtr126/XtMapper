package com.xtr.keymapper;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import android.widget.ShareActionProvider;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {

    private final Context context;
    public static final int maxLines = 4;
    public TextView cmdView;
    public TextView cmdView2;
    public TextView cmdView3;
    int i = 0;
    int x = 0;

    public Server(Context context){
        this.context=context;
        cmdView =  ((MainActivity)context).findViewById(R.id.cmdview);
        cmdView2 = ((MainActivity)context).findViewById(R.id.cmdview2);
        cmdView3 = ((MainActivity)context).findViewById(R.id.cmdview3);
    }

    public String getDeviceName(){
        SharedPreferences sharedPref = context.getSharedPreferences("devices", MODE_PRIVATE);
        return sharedPref.getString("device", null);
    }

    public void setupServer() {
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            String apk = ai.publicSourceDir;
            Process sh = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("cat > /data/xtr.keymapper.sh" + "\n");
            outputStream.writeBytes("#!/system/bin/sh\n");
            outputStream.writeBytes("pkill -f " + packageName + ".Input\n");
            outputStream.writeBytes("LD_LIBRARY_PATH=\"" + ai.nativeLibraryDir + //path containing lib*.so
                                        "\" CLASSPATH=\"" + apk +
                                        "\" /system/bin/app_process32 /system/bin " +
                                        packageName + ".Input " + getDeviceName() + "\n"); // input device node as argument
            outputStream.close();
            sh.waitFor();
            sh = Runtime.getRuntime().exec("su");
            outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("chmod 777 /data/xtr.keymapper.sh\n");
            outputStream.writeBytes("exit\n");
            outputStream.close();
            sh.waitFor();
            updateCmdView("run /data/xtr.keymapper.sh\n");
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void startServer() {
        try {
            setupServer();
            updateCmdView("starting server\n");
            Process sh = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("/data/xtr.keymapper.sh"+"\n");
            outputStream.close();
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(sh.getInputStream()));
            String line;
            while ((line = stdInput.readLine()) != null) {
                updateCmdView2("stdout: " + line);
            }
        } catch ( IOException e) {
            e.printStackTrace();
        }
    }

    public void startSocketTest(){
        try {
            ServerSocket serverSocket = new ServerSocket(6345);
            Socket clientSocket = serverSocket.accept();
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
    
            String inputLine;
            if (in.readLine() != null)
                updateCmdView("initialized: listening for events through socket\n");
    
            while ((inputLine = in.readLine()) != null) {
    
                updateCmdView3("socket: " + inputLine);
            }
            in.close(); out.close();
            clientSocket.close(); serverSocket.close();
            ((MainActivity)context).finish(); System.exit(0);
        } catch ( IOException e) {
            e.printStackTrace();
        }
    }

    public void updateCmdView(String s){
        ((MainActivity)context).runOnUiThread(() -> cmdView.append(s));
    }
    public void updateCmdView2(String s){
        i++;
        if(i == maxLines) {
            i = 0;
            ((MainActivity)context).runOnUiThread(() -> cmdView2.setText(s));
        } else {
            ((MainActivity)context).runOnUiThread(() -> cmdView2.append("\n" + s));
        }
    }
    public void updateCmdView3(String s){
        x++;
        if(x == maxLines) {
            x = 0;
            ((MainActivity)context).runOnUiThread(() -> cmdView3.setText(s));
        } else {
            ((MainActivity)context).runOnUiThread(() -> cmdView3.append("\n" + s));
        }
    }
    
}
