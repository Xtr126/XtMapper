package xtr.keymapper.profiles;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.R;
import xtr.keymapper.databinding.AppViewBinding;
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
        private int defaultColor;

        private final int selectedColor;

        private View selectedView;

        public AppsGridAdapter(Context context) {
            selectedColor = context.getColor(com.google.android.material.R.color.material_on_surface_stroke);
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
            AppViewBinding itemBinding = AppViewBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            defaultColor = Color.TRANSPARENT;
            return new RecyclerViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerViewHolder holder, int position) {
            // Set the data to textview and imageview.
            RecyclerData recyclerData = appsDataArrayList.get(position);
            holder.binding.appName.setText(recyclerData.title);
            holder.binding.appIcon.setImageDrawable(recyclerData.icon);


            if (recyclerData.packageName.equals(packageName ) && selectedView == null) {
                selectedView = holder.binding.getRoot();
                selectedView.setBackgroundColor(selectedColor);
            }
        }

        @Override
        public int getItemCount() {
            // this method returns the size of recyclerview
            return appsDataArrayList.size();
        }

        // View Holder Class to handle Recycler View.
        private class RecyclerViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

            private final AppViewBinding binding;

            public RecyclerViewHolder(@NonNull AppViewBinding binding) {
                super(binding.getRoot());
                binding.getRoot().setOnClickListener(this);
                this.binding = binding;
            }

            @Override
            public void onClick (View view) {
                int i = getAdapterPosition();
                ProfilesApps.this.packageName = appsDataArrayList.get(i).packageName;
                ProfilesApps.this.binding.currentProfile.setText(packageName);

                if (selectedView != null) selectedView.setBackgroundColor(defaultColor);
                view.setBackgroundColor(selectedColor);
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