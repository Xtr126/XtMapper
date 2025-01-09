package xtr.keymapper;

import android.graphics.PixelFormat;
import android.view.WindowManager;

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

    public static WindowManager.LayoutParams getPointerLayoutParams(int type) {
        WindowManager.LayoutParams mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                // Make the underlying application window visible through the cursor
                PixelFormat.TRANSLUCENT);
        mParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return mParams;
    }


}
