package xtr.keymapper.aim;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import java.util.ArrayList;

public class MouseAimSettings extends DialogFragment {

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        ArrayList<Integer> selectedItems = new ArrayList<>();  // Where we track the selected items
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
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
                        })
                // Set the action buttons
                .setPositiveButton("ok", (dialog, id) -> {
                    // User clicked OK, so save the selectedItems results somewhere
                    // or return them to the component that opened the dialog
                })
                .setNegativeButton("cancel", (dialog, id) -> {
                });

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        return dialog;
    }
}
