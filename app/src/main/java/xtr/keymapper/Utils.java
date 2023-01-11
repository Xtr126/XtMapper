package xtr.keymapper;

import android.content.Context;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public class Utils {
    public static final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static int obtainIndex(String s) {
        return alphabet.indexOf(s.substring(4));
    }

    public static BufferedReader geteventStream() throws IOException {
        Process sh = Runtime.getRuntime().exec("sh");
        DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());

        outputStream.writeBytes("exec env LD_PRELOAD=$LD_LIBRARY_PATH/libgetevent.so getevent -ql");
        outputStream.flush();

        return new BufferedReader(new InputStreamReader(sh.getInputStream()));
    }

    public static Process getRootAccess() throws IOException {
        String[] paths = {"/sbin/su", "/system/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/magisk/.core/bin/su"};
        for (String path : paths) {
            if (new File(path).canExecute())
                return Runtime.getRuntime().exec(path);
        }
        return Runtime.getRuntime().exec("echo root access not found!");
    }
}
