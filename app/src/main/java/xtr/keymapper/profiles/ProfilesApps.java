package xtr.keymapper.profiles;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.R;
import xtr.keymapper.databinding.AppViewBinding;
import xtr.keymapper.databinding.FragmentProfilesAppsBinding;

public class ProfilesApps {
    public FragmentProfilesAppsBinding binding;
    public final View appsView;

    private ProfileSelector.OnAppSelectedListener mListener;


    private ProfilesApps(Context context){
        appsView = createView(LayoutInflater.from(context));
    }

    public interface OnAppsLoadedListener {
        void onAppsLoaded(ProfilesApps profilesApps, AppsGridAdapter adapter, AlertDialog dialog);
    }

    /**
     * Loading apps is a time consuming process on slow devices with a large number of apps.
     * We show a visual loading indicator while loading the apps in a separate thread
     * @param context UI context
     * @param builder builder for creating a dialog with visual loading indicator and showing
     * @param onAppsLoadedListener Called when finished loading apps
     */
    @UiThread
    public static void asyncLoadAppsAndThen(Context context, MaterialAlertDialogBuilder builder, OnAppsLoadedListener onAppsLoadedListener) {
        builder.setView(R.layout.loading);
        AlertDialog dialog = ProfileSelector.showDialog(builder);
        ProfilesApps mProfilesApps = new ProfilesApps(context);
        mProfilesApps.asyncLoadApps(onAppsLoadedListener, dialog);
    }

    @UiThread
    void asyncLoadApps(OnAppsLoadedListener l, AlertDialog dialog) {
        Context context = appsView.getContext();

        new Thread(() -> {
            AppsGridAdapter adapter = new AppsGridAdapter(context);
            Handler mHandler = new Handler(Looper.getMainLooper());
            mHandler.post(() -> l.onAppsLoaded(this, adapter, dialog));
        }).start();

    }

    public void setListener(ProfileSelector.OnAppSelectedListener mListener) {
        this.mListener = mListener;
    }

    View createView(@NonNull LayoutInflater inflater) {
        // Inflate the layout for this fragment
        binding = FragmentProfilesAppsBinding.inflate(inflater);
        return binding.getRoot();
    }

    public void onDestroyView() {
        binding = null;
    }

    public class AppsGridAdapter extends RecyclerView.Adapter<AppsGridAdapter.RecyclerViewHolder> {

        private final ArrayList<RecyclerData> appsDataArrayList = new ArrayList<>();

        public AppsGridAdapter(Context context) {
            PackageManager pm = context.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN, null);
            i.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> allApps = pm.queryIntentActivities(i, 0);

            for(ResolveInfo ri:allApps)
                appsDataArrayList.add(new RecyclerData(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm),
                        ri.activityInfo.loadIcon(pm)));
        }

        @NonNull
        @Override
        public RecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate Layout
            AppViewBinding itemBinding = AppViewBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new RecyclerViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerViewHolder holder, int position) {
            // Set the data to textview and imageview.
            RecyclerData recyclerData = appsDataArrayList.get(position);
            holder.binding.appName.setText(recyclerData.title);
            holder.binding.appIcon.setImageDrawable(recyclerData.icon);
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
                mListener.onAppSelected(appsDataArrayList.get(i).packageName);
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