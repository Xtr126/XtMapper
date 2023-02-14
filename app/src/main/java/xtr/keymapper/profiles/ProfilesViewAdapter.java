package xtr.keymapper.profiles;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

import xtr.keymapper.R;
import xtr.keymapper.databinding.ProfileRowItemBinding;

/**
 * Provide views to RecyclerView.
 */
public class ProfilesViewAdapter extends RecyclerView.Adapter<ProfilesViewAdapter.ViewHolder> {

    private final ArrayList<RecyclerData> recyclerDataArrayList = new ArrayList<>();
    private final OnItemRemovedListener callback;
    private ProfileRowItemBinding itemBinding;

    /**
     * Provide a reference to the type of views that you are using (custom ViewHolder)
     */
    public class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView textView;
        private final ImageView appIcon;
        private final MaterialButton deleteButton, editButton;
        private final MaterialButton appIconButton;

        public ViewHolder(View v) {
            super(v);
            textView = itemBinding.textView;
            appIcon = itemBinding.appIcon;
            appIconButton = itemBinding.appIconButton;
            deleteButton = itemBinding.deleteButton;
            editButton = itemBinding.editButton;
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
        new KeymapProfiles(context).getAllProfiles().forEach((profileName, profile) -> {
            if(profileName != null) try {
                recyclerDataArrayList.add(
                        new RecyclerData(profile.packageName,
                                context.getPackageManager().getApplicationIcon(profile.packageName),
                                profileName));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            else keymapProfiles.deleteProfile(profileName);
        });
    }

    // Create new views (invoked by the layout manager)
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        // Create a new view
        itemBinding = ProfileRowItemBinding.inflate(LayoutInflater.from(viewGroup.getContext()), viewGroup, false);
        View v = itemBinding.getRoot();

        return new ViewHolder(v);
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(ViewHolder viewHolder, final int position) {
        // Get element from your dataset at this position and replace the contents of the view
        // with that element
        RecyclerData recyclerData = recyclerDataArrayList.get(position);
        viewHolder.textView.setText(recyclerData.name);
        viewHolder.appIcon.setBackground(recyclerData.icon);

        final String profileName = recyclerData.name.toString();

        Context context = viewHolder.itemView.getContext();
        KeymapProfiles keymapProfiles = new KeymapProfiles(context);

        viewHolder.deleteButton.setOnClickListener(v -> {
            keymapProfiles.deleteProfile(profileName);
            callback.resetAdapter();
        });


        viewHolder.editButton.setOnClickListener(view -> {
            EditText editText = new EditText(view.getContext());
            editText.setText(profileName);

            AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.Theme_AppCompat_Dialog_Alert));
            builder.setMessage(R.string.dialog_alert_add_profile)
                    .setPositiveButton("ok", (dialog, which) -> {
                        keymapProfiles.renameProfile(profileName, editText.getText().toString());
                        callback.resetAdapter();
                    })
                    .setNegativeButton("cancel", (dialog, which) -> {})
                    .setView(editText);
            AlertDialog dialog = builder.create();
            dialog.show();
        });

        viewHolder.appIconButton.setOnClickListener(view -> {
            ProfilesApps appsView = new ProfilesApps(view.getContext(), profileName);

            AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(context, R.style.Theme_AppCompat_Dialog_Alert));
            builder.setPositiveButton("ok", (dialog, which) -> {
                        keymapProfiles.setProfilePackageName(recyclerData.name.toString(), appsView.packageName);
                        appsView.onDestroyView();
                        callback.resetAdapter();
                    })
                    .setNegativeButton("cancel", (dialog, which) -> {})
                    .setView(appsView.view);
            AlertDialog dialog = builder.create();
            dialog.show();
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
