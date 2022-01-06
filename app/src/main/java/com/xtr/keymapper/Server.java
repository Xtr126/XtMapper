package com.xtr.keymapper;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.BreakIterator;

public class Server {

    String line;
    String points = "starting server, now start touch";
    Context context;
    public TextView cmdView;

    public Server(Context context){
        this.context=context;
        cmdView = ((MainActivity)context).findViewById(R.id.mouseView);
    }

    public void startServer() {
        cmdView.setText(points);
        new Thread(() -> {
            try{
                PackageManager pm = context.getPackageManager();
                String packageName = context.getPackageName();
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                String apk = ai.publicSourceDir;
               // this.cmdView.setText(points);
                Process sh = Runtime.getRuntime().exec("su");
                DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());
                outputStream.writeBytes("CLASSPATH=\""+ apk +"\" /system/bin/app_process32 /system/bin "+ packageName + ".Input"+"\n");
                outputStream.flush();
                outputStream.close();
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(sh.getInputStream()));
                while ((line = stdInput.readLine()) != null) {
                    points += "\n" + line;
                    //this.cmdView.setText(points);
                }
            } catch (IOException | PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void setupServer() {
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
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
            cmdView.setText("run /mnt/xtr.keymapper.sh");
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
}
