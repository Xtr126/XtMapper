package xtr.keymapper.profiles;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import xtr.keymapper.R;
import xtr.keymapper.databinding.ProfileRowItemBinding;

/**
 * Provide views to RecyclerView.
 */
public class ProfilesViewAdapter extends RecyclerView.Adapter<ProfilesViewAdapter.ViewHolder> implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final ArrayList<RecyclerData> recyclerDataArrayList = new ArrayList<>();
    private final OnItemRemovedListener callback;

    /**
     * Provide a reference to the type of views that you are using (custom ViewHolder)
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
    public ProfilesViewAdapter(Context context, OnItemRemovedListener l) {
        this.callback = l;
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);
        keymapProfiles.sharedPref.registerOnSharedPreferenceChangeListener(this);

        new KeymapProfiles(context).getAllProfiles().forEach((profileName, profile) -> {
            if(profileName != null) try {
                recyclerDataArrayList.add(
                        new RecyclerData(profile.packageName,
                                context.getPackageManager().getApplicationIcon(profile.packageName),
                                profileName));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
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
        // Get element from your dataset at this position and replace the contents of the view
        // with that element
        RecyclerData recyclerData = recyclerDataArrayList.get(position);
        viewHolder.binding.textView.setText(recyclerData.name);
        viewHolder.binding.appIcon.setImageDrawable(recyclerData.icon);

        final String profileName = recyclerData.name.toString();

        Context context = viewHolder.itemView.getContext();
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);

        viewHolder.binding.deleteButton.setOnClickListener(v -> keymapProfiles.deleteProfile(profileName));


        viewHolder.binding.editButton.setOnClickListener(view -> {
            EditText editText = new EditText(view.getContext());
            editText.setText(profileName);

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.Theme_XtMapper));
            builder.setTitle(R.string.dialog_alert_add_profile)
                    .setPositiveButton("Ok", (dialog, which) -> keymapProfiles.renameProfile(profileName, editText.getText().toString()))
                    .setNegativeButton("Cancel", (dialog, which) -> {})
                    .setView(editText)
                    .show();
        });

        viewHolder.binding.appIconButton.setOnClickListener(view -> {
            ProfilesApps appsView = new ProfilesApps(view.getContext(), profileName);

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.Theme_XtMapper));
            builder.setPositiveButton("Ok", (dialog, which) -> {
                        keymapProfiles.setProfilePackageName(recyclerData.name.toString(), appsView.packageName);
                        appsView.onDestroyView();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {})
                    .setView(appsView.view)
                    .show();
        });
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return recyclerDataArrayList.size();
    }

    private static class RecyclerData {
        public RecyclerData(String packageName, Drawable icon, CharSequence name) {
            this.packageName = packageName;
            this.name = name;
            this.icon = icon;
        }

        String packageName;
        CharSequence name;
        Drawable icon;
    }
}
