package xtr.keymapper.profiles;

import android.content.Context;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;

import xtr.keymapper.R;

public class ProfileSelector {

    public interface OnProfileSelectedListener {
        void onProfileSelected(String profile);
    }

    public static void select(Context context, OnProfileSelectedListener listener){
        ArrayList<String> allProfiles = new ArrayList<>(new KeymapProfiles(context).getAllProfiles().keySet());
        if (allProfiles.size() == 1) {
            listener.onProfileSelected(allProfiles.get(0));
            return;
        }
        CharSequence[] items = allProfiles.toArray(new CharSequence[0]);

        context.setTheme(R.style.Theme_XtMapper);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);

        // Show dialog to select profile
        if (!allProfiles.isEmpty())
            builder.setTitle(xtr.keymapper.R.string.dialog_alert_select_profile)
                    .setItems(items, (d, which) -> {
                        String selectedProfile = allProfiles.get(which);
                        listener.onProfileSelected(selectedProfile);
                    });
        else { // Create profile if no profile found
            MaterialAutoCompleteTextView editText = new MaterialAutoCompleteTextView(context);
            builder.setTitle(xtr.keymapper.R.string.dialog_alert_add_profile)
                    .setPositiveButton(R.string.ok, (d, which) -> {
                        String selectedProfile = editText.getText().toString();
                        showsAppSelectionDialog(context, listener, selectedProfile);
                    })
                    .setNegativeButton(R.string.cancel, (d, which) -> {})
                    .setView(editText);
        }
        showDialog(builder);
    }

    public static void showsAppSelectionDialog(Context context, OnProfileSelectedListener listener, String profile) {
        ProfilesApps appsView = new ProfilesApps(context, profile);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                    KeymapProfiles keymapProfiles = new KeymapProfiles(context);
                    keymapProfiles.saveProfile(profile, new ArrayList<>(), appsView.packageName);
                    appsView.onDestroyView();
                    listener.onProfileSelected(profile);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {})
                .setView(appsView.view);
        showDialog(builder);
    }

    private static void showDialog(MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }
}