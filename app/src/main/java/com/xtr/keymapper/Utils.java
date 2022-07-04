package com.xtr.keymapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;

public class Utils {
    public static String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";


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

    public int obtainAccent(Context context){
        TypedValue typedValue = new TypedValue();
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(context,
                android.R.style.Theme_DeviceDefault);
        contextThemeWrapper.getTheme().resolveAttribute(android.R.attr.colorAccent,
                typedValue, true);
        return typedValue.data;
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
