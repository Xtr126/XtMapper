package xtr.keymapper.profiles;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.WindowManager;

import androidx.annotation.UiContext;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import xtr.keymapper.R;
import xtr.keymapper.databinding.AppViewBinding;
import xtr.keymapper.databinding.TextFieldNewProfileBinding;
import xtr.keymapper.keymap.KeymapProfiles;

public class ProfileSelector {

    public interface OnProfileSelectedListener {
        void onProfileSelected(String profile);
    }
    public interface OnAppSelectedListener {
        void onAppSelected(String packageName);
    }
    public interface OnProfileEnabledListener {
        void onEnabled(boolean enabled);
    }

    public static void select(Context context, OnProfileSelectedListener listener){
        context.setTheme(R.style.Theme_XtMapper);
        showAppSelectionDialog(context, packageName -> select(context, listener, packageName));
    }

    public static void select(Context context, OnProfileSelectedListener listener, String packageName) {
        context.setTheme(R.style.Theme_XtMapper);
        ArrayList<String> allProfiles = new ArrayList<>(new KeymapProfiles(context).getAllProfilesForApp(packageName).keySet());

        if (allProfiles.size() == 1) {
            listener.onProfileSelected(allProfiles.get(0));
            return;
        } else if (allProfiles.isEmpty()) {
            createNewProfileForApp(context, packageName, true, listener);
            return;
        }
        CharSequence[] items = allProfiles.toArray(new CharSequence[0]);

        // Show dialog to select profile
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.dialog_alert_select_profile)
                .setItems(items, (d, which) -> {
                    String selectedProfile = allProfiles.get(which);
                    listener.onProfileSelected(selectedProfile);
                });
        showDialog(builder);
    }

    public static void createNewProfile(@UiContext Context context, OnProfileSelectedListener listener) {
        showAppSelectionDialog(context, packageName -> createNewProfileForApp(context, packageName, true, listener));
    }

    public static void showEnableProfileDialog(@UiContext Context context, String packageName, OnProfileEnabledListener listener){
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        AppViewBinding binding = AppViewBinding.inflate(LayoutInflater.from(context));
        PackageManager pm = context.getPackageManager();
        try {
            binding.appName.setText(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
            binding.appIcon.setImageDrawable(pm.getApplicationIcon(packageName));
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        builder.setTitle(R.string.dialog_alert_enable_profile)
                .setPositiveButton(R.string.yes, (d, which) -> listener.onEnabled(true))
                .setNegativeButton(R.string.no, (d, which) -> listener.onEnabled(false))
                .setView(binding.getRoot());
        showDialog(builder);
    }

    public static void createNewProfileForApp(@UiContext Context context, String packageName, boolean enabled, OnProfileSelectedListener listener){
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        TextFieldNewProfileBinding binding = TextFieldNewProfileBinding.inflate(LayoutInflater.from(context));
        binding.editText.setText(packageName);

        builder.setTitle(R.string.dialog_alert_add_profile)
                .setPositiveButton(R.string.ok, (d, which) -> {
                    String selectedProfile = binding.editText.getText().toString();
                    KeymapProfiles keymapProfiles = new KeymapProfiles(context);
                    keymapProfiles.saveProfile(selectedProfile, new ArrayList<>(), packageName, enabled);
                    listener.onProfileSelected(selectedProfile);
                })
                .setView(binding.getRoot());
        showDialog(builder);
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
        if (Settings.canDrawOverlays(dialog.getContext()))
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }
}