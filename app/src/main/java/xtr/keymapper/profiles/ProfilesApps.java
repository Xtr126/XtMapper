package xtr.keymapper.profiles;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.R;
import xtr.keymapper.databinding.FragmentProfilesAppsBinding;

public class ProfilesApps {
    public static final String defaultProfile = "xtr.keymapper.default";
    public String packageName;
    public FragmentProfilesAppsBinding binding;
    public final View view;

    public ProfilesApps(Context context, String profileName){
        this.packageName  = new KeymapProfiles(context).getProfile(profileName).packageName;

        view = createView(LayoutInflater.from(context));
        onViewCreated(view);
    }

    public View createView(@NonNull LayoutInflater inflater) {
        // Inflate the layout for this fragment
        binding = FragmentProfilesAppsBinding.inflate(inflater);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view) {
        Context context = view.getContext();

        AppsGridAdapter adapter = new AppsGridAdapter(context);
        binding.appsGrid.setAdapter(adapter);
    }

    public void onDestroyView() {
        binding = null;
    }

    public class AppsGridAdapter extends RecyclerView.Adapter<AppsGridAdapter.RecyclerViewHolder> {

        private final ArrayList<RecyclerData> appsDataArrayList = new ArrayList<>();
        private ColorStateList defaultTint;

        private final ColorStateList selectedTint;

        private View selectedView;

        public AppsGridAdapter(Context context) {
            selectedTint = ColorStateList.valueOf(context.getColor(R.color.purple_700));
            PackageManager pm = context.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN, null);
            i.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> allApps = pm.queryIntentActivities(i, 0);

            Drawable drawable = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher_foreground);
            appsDataArrayList.add(
                    new RecyclerData(defaultProfile,
                            "Default",
                            drawable));
            for(ResolveInfo ri:allApps)
                appsDataArrayList.add(new RecyclerData(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm),
                        ri.activityInfo.loadIcon(pm)));

            binding.currentProfile.setText(packageName);
        }

        @NonNull
        @Override
        public RecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate Layout
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_view, parent, false);
            defaultTint = view.getRootView().getBackgroundTintList();
            return new RecyclerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerViewHolder holder, int position) {
            // Set the data to textview and imageview.
            RecyclerData recyclerData = appsDataArrayList.get(position);
            holder.appName.setText(recyclerData.title);
            holder.appIcon.setImageDrawable(recyclerData.icon);


            if (recyclerData.packageName.equals(packageName ) && selectedView == null) {
                selectedView = holder.appName.getRootView();
                selectedView.setBackgroundTintList(selectedTint);
            }
        }

        @Override
        public int getItemCount() {
            // this method returns the size of recyclerview
            return appsDataArrayList.size();
        }

        // View Holder Class to handle Recycler View.
        private class RecyclerViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

            private final TextView appName;
            private final ImageView appIcon;

            public RecyclerViewHolder(@NonNull View itemView) {
                super(itemView);
                appName = itemView.findViewById(R.id.app_name);
                appIcon = itemView.findViewById(R.id.app_icon);

                itemView.setOnClickListener(this);
            }

            @Override
            public void onClick (View view) {
                int i = getAdapterPosition();
                ProfilesApps.this.packageName = appsDataArrayList.get(i).packageName;
                binding.currentProfile.setText(packageName);

                if (selectedView != null) selectedView.setBackgroundTintList(defaultTint);
                view.setBackgroundTintList(selectedTint);
                selectedView = view;
            }
        }

        private class RecyclerData {
            public RecyclerData(String packageName, CharSequence title, Drawable icon) {
                this.packageName = packageName;
                this.icon = icon;
                this.title = title;
            }

            String packageName;
            CharSequence title;
            Drawable icon;
        }
    }
}