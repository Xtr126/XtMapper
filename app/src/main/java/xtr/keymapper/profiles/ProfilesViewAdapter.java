package xtr.keymapper.profiles;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;

import xtr.keymapper.R;
import xtr.keymapper.databinding.ProfileRowItemBinding;
import xtr.keymapper.databinding.TextFieldBinding;
import xtr.keymapper.keymap.KeymapProfiles;

/**
 * Provide views to RecyclerView.
 */
public class ProfilesViewAdapter extends RecyclerView.Adapter<ProfilesViewAdapter.ViewHolder> implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final ArrayList<RecyclerData> recyclerDataArrayList = new ArrayList<>();
    private final OnItemRemovedListener callback;
    private final ProfileSelectedCallback profileSelectedCallback;
    private MaterialCardView lastCheckedCard;

    /**
     * Interface to MainActivity for notifying when a profile is selected by user
     */
    public interface ProfileSelectedCallback {
        void onProfileSelected(String profileName);
    }

    /**
     * Provide a reference to the type of views used
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final ProfileRowItemBinding binding;

        public ViewHolder(ProfileRowItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public interface OnItemRemovedListener {
        void resetAdapter();
    }

    /**
     * Initialize the dataset of the Adapter.
     */
    public ProfilesViewAdapter(Context context, OnItemRemovedListener l, ProfileSelectedCallback cb) {
        callback = l;
        profileSelectedCallback = cb;
        if (context == null) return;
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);
        // Reset the adapter and load data again after sharedPreferences change
        keymapProfiles.sharedPref.registerOnSharedPreferenceChangeListener(this);

        // Add items to adapter for all saved keymap profiles
        new KeymapProfiles(context).getAllProfiles().forEach((profileName, profile) -> {
            if(profileName != null)
                recyclerDataArrayList.add(new RecyclerData(profile.packageName, context, profileName));
            else keymapProfiles.deleteProfile(null);
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPref, String key) {
        sharedPref.unregisterOnSharedPreferenceChangeListener(this);
        callback.resetAdapter();
    }

    // Create new views (invoked by the layout manager)
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        // Create a new view
        ProfileRowItemBinding itemBinding = ProfileRowItemBinding.inflate(LayoutInflater.from(viewGroup.getContext()), viewGroup, false);
        return new ViewHolder(itemBinding);
    }

    /**
     * @param viewHolder The ViewHolder which should be updated to represent the contents of the item at the given position in the data set.
     * @param position   The position of the item within the adapter's data set.
     * Setting up the CardView showing information about the profile and action buttons
     */
    @Override
    public void onBindViewHolder(ViewHolder viewHolder, int position) {
        // Get element from dataset at this position and set the contents of the view
        RecyclerData recyclerData = recyclerDataArrayList.get(position);
        viewHolder.binding.profileName.setText(recyclerData.profileName);
        viewHolder.binding.profileText.setText(recyclerData.description);
        viewHolder.binding.appIcon.setIcon(recyclerData.icon);

        String profileName = recyclerData.profileName;

        Context context = viewHolder.itemView.getContext();
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);

        viewHolder.binding.deleteButton.setOnClickListener(v -> keymapProfiles.deleteProfile(profileName));

        viewHolder.binding.renameButton.setOnClickListener(view -> {
            TextFieldBinding binding = TextFieldBinding.inflate(LayoutInflater.from(context));
            binding.getRoot().setHint(R.string.profile_name);
            binding.editText.setText(profileName);

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
            builder.setTitle(R.string.dialog_alert_add_profile)
                    .setPositiveButton(R.string.ok, (dialog, which) -> keymapProfiles.renameProfile(profileName, binding.editText.getText().toString()))
                    .setNegativeButton(R.string.cancel, (dialog, which) -> {})
                    .setView(binding.getRoot())
                    .show();
        });

        // Show dialog for user to select app for a profile from a list of apps
        viewHolder.binding.appIcon.setOnClickListener(view -> {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
            ProfilesApps.asyncLoadAppsAndThen(context, builder,
                    (p, adapter, loadingDialog) -> {
                        loadingDialog.dismiss();

                        // Finished loading apps
                        p.binding.appsGrid.setAdapter(adapter);

                        AlertDialog dialog = builder.setView(p.appsView).show();

                        // Change associated app of profile
                        p.setListener(packageName -> {
                            keymapProfiles.setProfilePackageName(recyclerData.profileName, packageName);
                            p.onDestroyView();
                            dialog.dismiss();
                        });
                    });
        });

        viewHolder.binding.enableSwitch.setChecked(keymapProfiles.isProfileEnabled(recyclerData.profileName));
        viewHolder.binding.enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> keymapProfiles.setProfileEnabled(recyclerData.profileName, isChecked));

        // Make CardView selectable by user, one at a time
        viewHolder.binding.card.setOnClickListener(v -> {
            if (lastCheckedCard != null) lastCheckedCard.setChecked(false);
            viewHolder.binding.card.setChecked(true);
            lastCheckedCard = viewHolder.binding.card;
            profileSelectedCallback.onProfileSelected(profileName);
        });
    }

    // Return the size of dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return recyclerDataArrayList.size();
    }

    /**
     * Data class to store package name, icon of the app and the contents of the profile as text
     */
    private static class RecyclerData {
        public RecyclerData(String packageName, Context context, String profileName) {
            description = new KeymapProfiles(context).sharedPref.getStringSet(profileName, new HashSet<>()).toString();
            this.profileName = profileName;
            try {
                icon = context.getPackageManager().getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                icon = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher_foreground);
            }
        }

        String description;
        String profileName;
        Drawable icon;
    }
}
