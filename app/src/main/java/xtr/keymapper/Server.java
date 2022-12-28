package xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import xtr.keymapper.activity.MainActivity;

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
    public static final int DEFAULT_PORT = 6234, DEFAULT_PORT_2 = 6345;

    public StringBuilder c1, c2;
    private int counter1 = 0, counter2 = 0;

    public Server(Context context){
        this.context = context;
        script_name = context.getExternalFilesDir(null) + "/xtMapper.sh";
    }

    private void writeScript(ApplicationInfo ai) throws IOException, InterruptedException {
        final String className = Input.class.getName();

        FileWriter linesToWrite = new FileWriter(script_name);
        linesToWrite.append("#!/system/bin/sh\n");
        linesToWrite.append("pgrep -f ").append(className).append(" && echo Waiting for overlay... && exit 1\n");
        linesToWrite.append("exec env ");
        linesToWrite.append("LD_LIBRARY_PATH=\"").append(ai.nativeLibraryDir)  //path containing lib*.so
                .append("\" CLASSPATH=\"").append(ai.publicSourceDir) // Absolute path to apk in /data/app
                .append("\" /system/bin/app_process /system/bin ")
                .append(className).append("\n");

        linesToWrite.flush();
        linesToWrite.close();
    }

    public static void stopServer(){
        try {
            Socket socket = new Socket("127.0.0.1", DEFAULT_PORT_2);
            PrintWriter pOut = new PrintWriter(socket.getOutputStream());
            pOut.println("stop");
            pOut.close(); socket.close();
        } catch (IOException e) {
            Log.e("I/O error", e.toString());
        }
    }

    public static void changeDevice(String newDevice){
        try {
            Socket socket = new Socket("127.0.0.1", DEFAULT_PORT_2);
            PrintWriter pOut = new PrintWriter(socket.getOutputStream());
            pOut.println("change_device"); pOut.flush();
            pOut.print(newDevice);
            pOut.close(); socket.close();
        } catch (IOException e) {
            Log.e("I/O error", e.toString());
        }
    }

    public static void changeSensitivity(float sensitivity) throws IOException {
        Socket socket = new Socket("127.0.0.1", DEFAULT_PORT_2);
        PrintWriter pOut = new PrintWriter(socket.getOutputStream());
        pOut.println("mouse_sensitivity"); pOut.flush();
        pOut.print(sensitivity);
        pOut.close(); socket.close();
    }

    public void setupServer () {
        c1 = new StringBuilder();
        c2 = new StringBuilder();
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            writeScript(ai);
        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            Log.e("Server", e.toString());
        }
    }

    public void startServer() {
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
                if (line.equals("Waiting for overlay...")) {
                    ((MainActivity)context).startPointer();
                }
            }
            sh.waitFor();
        } catch (IOException | InterruptedException e) {
            Log.e("Server", e.toString());
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
        ((MainActivity)context).c1 = this.c1;
    }
    public void updateCmdView2(String s){
        if(counter2 < MAX_LINES) {
            c2.append(s).append("\n");
            counter2++;
        } else {
            counter2 = 0;
            c2 = new StringBuilder();
        }
        ((MainActivity)context).c2 = this.c2;
    }
}
