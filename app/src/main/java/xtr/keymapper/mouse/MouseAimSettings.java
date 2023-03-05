package xtr.keymapper.mouse;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.WindowManager;

import java.util.ArrayList;

import xtr.keymapper.KeymapConfig;

public class MouseAimSettings {

    public Dialog getDialog(Context context) {
        KeymapConfig keymapConfig = new KeymapConfig(context);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        CharSequence[] list = {"Mouse right click", "~ key"};
        // Set the dialog title
        builder.setTitle("Activate with")
                .setMultiChoiceItems(list, null,
                        (dialog, which, isChecked) -> {
                            if (which == 0) {
                                keymapConfig.rightClickMouseAim = isChecked;
                            } else if (which == 1)
                                keymapConfig.keyGraveMouseAim = isChecked;
                            
                            keymapConfig.applySharedPrefs();
                        });
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        return dialog;
    }
}
