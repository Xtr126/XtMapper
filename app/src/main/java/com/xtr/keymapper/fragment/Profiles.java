package com.xtr.keymapper.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xtr.keymapper.KeymapConfig;
import com.xtr.keymapper.R;

import java.util.ArrayList;
import java.util.List;

public class Profiles extends Fragment {
    private TextView textView;
    public String currentProfile;
    private Context context;
    public static final String defaultProfile = "com.xtr.keymapper.default";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        this.context = getContext();
        View view = inflater.inflate(R.layout.fragment_profiles, container, false);
        LinearLayout profilesView = view.findViewById(R.id.profiles_view);
        ImageButton profilesButton = profilesView.findViewById(R.id.profiles);

        textView = view.findViewById(R.id.profileTextView);
        RecyclerView recyclerView = profilesView.findViewById(R.id.app_grid);
        RecyclerViewAdapter adapter = new RecyclerViewAdapter();

        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new GridLayoutManager(context, 3));

        Drawable profilesShow = AppCompatResources.getDrawable(context, R.drawable.ic_profiles_1);
        Drawable profilesHide = AppCompatResources.getDrawable(context, R.drawable.ic_profiles_2);
        profilesButton.setOnClickListener(v -> {
            switch (recyclerView.getVisibility()) {
                case View.VISIBLE:{
                    textView.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.GONE);
                    profilesButton.setForeground(profilesShow);
                    break;
                }
                case View.GONE:
                case View.INVISIBLE: {
                    textView.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.VISIBLE);
                    profilesButton.setForeground(profilesHide);
                    break;
                }
            }
        });
        // Inflate the layout for this fragment
        return view;
    }

    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.RecyclerViewHolder> {

        private final ArrayList<RecyclerData> appsDataArrayList = new ArrayList<>();
        private ColorStateList defaultTint;

        private final ColorStateList selectedTint =
                ColorStateList.valueOf(context.getColor(R.color.purple_700));

        private View selectedView;
        private final KeymapConfig keymapConfig;

        public RecyclerViewAdapter() {
            PackageManager pm = context.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN, null);
            i.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> allApps = pm.queryIntentActivities(i, 0);

            Drawable drawable = AppCompatResources.getDrawable(context, R.drawable.ic_launcher_foreground);
            appsDataArrayList.add(
                    new RecyclerData(defaultProfile,
                            "Default",
                            drawable));
            for(ResolveInfo ri:allApps)
                appsDataArrayList.add(new RecyclerData(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm),
                        ri.activityInfo.loadIcon(pm)));

            keymapConfig = new KeymapConfig(context);
            currentProfile = keymapConfig.getProfile();
            textView.setText(currentProfile);
        }

        @NonNull
        @Override
        public RecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate Layout
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_grid, parent, false);
            defaultTint = view.getRootView().getBackgroundTintList();
            return new RecyclerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerViewHolder holder, int position) {
            // Set the data to textview and imageview.
            RecyclerData recyclerData = appsDataArrayList.get(position);
            holder.appName.setText(recyclerData.title);
            holder.appIcon.setImageDrawable(recyclerData.icon);


            if (recyclerData.packageName.equals(currentProfile) && selectedView == null) {
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
                currentProfile = appsDataArrayList.get(i).packageName;
                textView.setText(currentProfile);

                keymapConfig.setProfile(currentProfile);

                selectedView.setBackgroundTintList(defaultTint);
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