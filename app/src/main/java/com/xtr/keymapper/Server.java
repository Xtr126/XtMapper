package com.xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {

    String line;
    Context context;
    public TextView cmdView;
    public TextView cmdView2;
    public TextView cmdView3;

    public Server(Context context){
        this.context=context;
        cmdView = ((MainActivity)context).findViewById(R.id.cmdview);
        cmdView2 = ((MainActivity)context).findViewById(R.id.cmdview2);
        cmdView3 = ((MainActivity)context).findViewById(R.id.cmdview3);

    }

    public void startServer() {
        try {
            setupServer();
            cmdView.append("starting server\n");
            Process sh = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("/data/xtr.keymapper.sh"+"\n");
            outputStream.close();
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(sh.getInputStream()));
            while ((line = stdInput.readLine()) != null) {
                cmdView2.setText("stdout: " + line);
            }
        } catch ( IOException e) {
            e.printStackTrace();
        }
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
            outputStream.writeBytes("LD_LIBRARY_PATH=\"" + ai.nativeLibraryDir + "\" CLASSPATH=\"" + apk + "\" /system/bin/app_process32 /system/bin " + packageName + ".Input" + "\n");
            outputStream.close();
            sh.waitFor();
            sh = Runtime.getRuntime().exec("su");
            outputStream = new DataOutputStream(sh.getOutputStream());
            outputStream.writeBytes("chmod 777 /data/xtr.keymapper.sh\n");
            outputStream.writeBytes("exit\n");
            outputStream.close();
            sh.waitFor();
            cmdView.append("run /data/xtr.keymapper.sh\n");
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
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
            cmdView.append("initialized: listening for events through socket\n");

        while ((inputLine = in.readLine()) != null) {
            cmdView3.setText("socket: " + inputLine);
        }
        in.close(); out.close();
        clientSocket.close(); serverSocket.close();
    } catch ( IOException e) {
        e.printStackTrace();
    }
        ((MainActivity)context).finish(); System.exit(0);
    }
}
