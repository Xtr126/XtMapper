package xtr.keymapper.aim;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.view.WindowManager;

import java.util.ArrayList;

public class MouseAimSettings {

    public Dialog getDialog(Context context) {
        ArrayList<Integer> selectedItems = new ArrayList<>();  // Where we track the selected items
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        CharSequence[] list = {"Mouse right click", "~ key"};
        // Set the dialog title
        builder.setTitle("Activate with")
                // Specify the list array, the items to be selected by default (null for none),
                // and the listener through which to receive callbacks when items are selected
                .setMultiChoiceItems(list, null,
                        (dialog, which, isChecked) -> {
                            if (isChecked) {
                                // If the user checked the item, add it to the selected items
                                selectedItems.add(which);
                            } else if (selectedItems.contains(which)) {
                                // Else, if the item is already in the array, remove it
                                selectedItems.remove(which);
                            }
                        });
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        return dialog;
    }
}
