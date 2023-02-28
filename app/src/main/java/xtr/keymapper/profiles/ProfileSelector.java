package xtr.keymapper.profiles;

import android.content.Context;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import xtr.keymapper.R;

public class ProfileSelector {

    public interface OnProfileSelectedListener {
        void onProfileSelected(String profile);
    }

    public ProfileSelector(Context context, OnProfileSelectedListener listener){
        ArrayList<String> allProfiles = new ArrayList<>(new KeymapProfiles(context).getAllProfiles().keySet());
        if (allProfiles.size() == 1) {
            listener.onProfileSelected(allProfiles.get(0));
            return;
        }
        CharSequence[] items = allProfiles.toArray(new CharSequence[0]);

        AlertDialog  dialog;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(new ContextThemeWrapper(context.getApplicationContext(), R.style.Theme_Material3_Dark));

        // Show dialog to select profile
        if (!allProfiles.isEmpty())
            builder.setTitle(R.string.dialog_alert_select_profile)
                    .setItems(items, (d, which) -> {
                        String selectedProfile = allProfiles.get(which);
                        listener.onProfileSelected(selectedProfile);
                    });
        else { // Create profile if no profile found
            EditText editText = new EditText(context);
            builder.setTitle(R.string.dialog_alert_add_profile)
                    .setPositiveButton("Ok", (d, which) -> {
                        String selectedProfile = editText.getText().toString();
                        showsAppSelectionDialog(context, listener, selectedProfile);
                    })
                    .setNegativeButton("Cancel", (d, which) -> {})
                    .setView(editText);
        }
        dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    public static void showsAppSelectionDialog(Context context, OnProfileSelectedListener listener, String profile) {
        ProfilesApps appsView = new ProfilesApps(context, profile);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.Theme_Material3_Dark));
        builder.setPositiveButton("Ok", (dialog, which) -> {
                    KeymapProfiles keymapProfiles = new KeymapProfiles(context);
                    keymapProfiles.saveProfile(profile, new ArrayList<>(), appsView.packageName);
                    appsView.onDestroyView();
                    listener.onProfileSelected(profile);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {})
                .setView(appsView.view)
                .show();
    }
}
