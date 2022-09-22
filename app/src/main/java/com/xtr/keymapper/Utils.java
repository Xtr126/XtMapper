package com.xtr.keymapper;

import android.content.Context;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class Utils {
    public static final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";


    public static int obtainIndex(String s) {
        return alphabet.indexOf(s.substring(4));
    }

    public static BufferedReader geteventStream(Context context) throws IOException {
        Process sh = Runtime.getRuntime().exec("su");
        DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());

        outputStream.writeBytes("pkill libgetevent.so\n");
        outputStream.writeBytes(context.getApplicationInfo().nativeLibraryDir + "/libgetevent.so -ql"+"\n");
        outputStream.writeBytes("exit\n");
        outputStream.flush();

        return new BufferedReader(new InputStreamReader(sh.getInputStream()));
    }

}
