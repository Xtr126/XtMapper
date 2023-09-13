package xtr.keymapper.profiles;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.WindowManager;

import androidx.annotation.UiContext;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import xtr.keymapper.R;
import xtr.keymapper.databinding.TextFieldNewProfileBinding;
import xtr.keymapper.keymap.KeymapProfiles;

public class ProfileSelector {

    public interface OnProfileSelectedListener {
        void onProfileSelected(String profile);
    }
    public interface OnAppSelectedListener {
        void onAppSelected(String packageName);
    }

    public static void select(Context context, OnProfileSelectedListener listener){
        ArrayList<String> allProfiles = new ArrayList<>(new KeymapProfiles(context).getAllProfiles().keySet());
        if (allProfiles.size() == 1) {
            listener.onProfileSelected(allProfiles.get(0));
            return;
        }
        CharSequence[] items = allProfiles.toArray(new CharSequence[0]);

        context.setTheme(R.style.Theme_XtMapper);

        // Show dialog to select profile
        if (!allProfiles.isEmpty()) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
            builder.setTitle(R.string.dialog_alert_select_profile)
                    .setItems(items, (d, which) -> {
                        String selectedProfile = allProfiles.get(which);
                        listener.onProfileSelected(selectedProfile);
                    });
            showDialog(builder);
        } else { // Create profile if no profile found
            createNewProfile(context, listener);
        }
    }

    public static void createNewProfile(@UiContext Context context, OnProfileSelectedListener listener) {
        showAppSelectionDialog(context, packageName -> {

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
            TextFieldNewProfileBinding binding = TextFieldNewProfileBinding.inflate(LayoutInflater.from(context));
            binding.editText.setText(packageName);

            builder.setTitle(R.string.dialog_alert_add_profile)
                    .setPositiveButton(R.string.ok, (d, which) -> {
                        String selectedProfile = binding.editText.getText().toString();
                        KeymapProfiles keymapProfiles = new KeymapProfiles(context);
                        keymapProfiles.saveProfile(selectedProfile, new ArrayList<>(), packageName, true);
                        listener.onProfileSelected(selectedProfile);
                    })
                    .setNegativeButton(R.string.cancel, (d, which) -> {})
                    .setView(binding.getRoot());
            showDialog(builder);
        });

    }

    public static void showAppSelectionDialog(Context context, OnAppSelectedListener listener) {
        ProfilesApps appsView = new ProfilesApps(context);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                    listener.onAppSelected(appsView.packageName);
                    appsView.onDestroyView();
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