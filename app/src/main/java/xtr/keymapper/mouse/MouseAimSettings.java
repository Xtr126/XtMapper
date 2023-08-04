package xtr.keymapper.mouse;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.view.WindowManager;

import xtr.keymapper.keymap.KeymapConfig;

public class MouseAimSettings {

    public Dialog getDialog(Context context) {
        KeymapConfig keymapConfig = new KeymapConfig(context);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        CharSequence[] list = {"Mouse right click", "~ key"};
        boolean[] checkedItems = {keymapConfig.rightClickMouseAim, keymapConfig.keyGraveMouseAim};
        // Set the dialog title
        builder.setTitle("Activate with")
                .setMultiChoiceItems(list, checkedItems,
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
