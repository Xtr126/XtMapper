package xtr.keymapper;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class Utils {
    public static final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * @param key input key code KEY_X
     * @return the index of X in alphabet
     */
    public static int obtainIndex(String key) {
        return alphabet.indexOf(key.substring(4));
    }

    public static BufferedReader geteventStream(String nativeLibraryDir) throws IOException {
        Process sh = Runtime.getRuntime().exec("sh");
        DataOutputStream outputStream = new DataOutputStream(sh.getOutputStream());

        outputStream.writeBytes("exec env LD_PRELOAD=" + nativeLibraryDir + "/libgetevent.so getevent -ql\n");
        outputStream.flush();

        return new BufferedReader(new InputStreamReader(sh.getInputStream()));
    }

}
