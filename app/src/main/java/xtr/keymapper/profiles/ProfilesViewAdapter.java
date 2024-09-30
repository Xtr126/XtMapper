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
import xtr.keymapper.databinding.TextFieldNewProfileBinding;
import xtr.keymapper.keymap.KeymapProfiles;

/**
 * Provide views to RecyclerView.
 */
public class ProfilesViewAdapter extends RecyclerView.Adapter<ProfilesViewAdapter.ViewHolder> implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final ArrayList<RecyclerData> recyclerDataArrayList = new ArrayList<>();
    private final OnItemRemovedListener callback;
    private final ProfileSelectedCallback profileSelectedCallback;
    private MaterialCardView lastCheckedCard = null;

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
        this.callback = l;
        this.profileSelectedCallback = cb;
        if (context == null) return;
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);
        keymapProfiles.sharedPref.registerOnSharedPreferenceChangeListener(this);

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

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(ViewHolder viewHolder, final int position) {
        // Get element from dataset at this position and set the contents of the view
        RecyclerData recyclerData = recyclerDataArrayList.get(position);
        viewHolder.binding.profileName.setText(recyclerData.profileName);
        viewHolder.binding.profileText.setText(recyclerData.description);
        viewHolder.binding.appIcon.setIcon(recyclerData.icon);

        final String profileName = recyclerData.profileName;

        Context context = viewHolder.itemView.getContext();
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);

        viewHolder.binding.deleteButton.setOnClickListener(v -> keymapProfiles.deleteProfile(profileName));

        viewHolder.binding.renameButton.setOnClickListener(view -> {
            TextFieldNewProfileBinding binding = TextFieldNewProfileBinding.inflate(LayoutInflater.from(context));
            binding.editText.setText(profileName);

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
            builder.setTitle(R.string.dialog_alert_add_profile)
                    .setPositiveButton(R.string.ok, (dialog, which) -> keymapProfiles.renameProfile(profileName, binding.editText.getText().toString()))
                    .setNegativeButton(R.string.cancel, (dialog, which) -> {})
                    .setView(binding.getRoot())
                    .show();
        });

        // Show dialog for user to select app for a profile from a grid of apps
        viewHolder.binding.appIcon.setOnClickListener(view -> {
            ProfilesApps appsView = new ProfilesApps(view.getContext());

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
            AlertDialog dialog = builder.setView(appsView.view).show();

            appsView.setListener(packageName -> {
                keymapProfiles.setProfilePackageName(recyclerData.profileName, packageName);
                appsView.onDestroyView();
                dialog.dismiss();
            });
        });

        viewHolder.binding.enableSwitch.setChecked(keymapProfiles.isProfileEnabled(recyclerData.profileName));
        viewHolder.binding.enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> keymapProfiles.setProfileEnabled(recyclerData.profileName, isChecked));
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

    private static class RecyclerData {
        public RecyclerData(String packageName, Context context, String profileName) {
            this.description = new KeymapProfiles(context).sharedPref.getStringSet(profileName, new HashSet<>()).toString();
            this.profileName = profileName;
            try {
                this.icon = context.getPackageManager().getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                this.icon = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher_foreground);
            }
        }

        String description;
        String profileName;
        Drawable icon;
    }
}
