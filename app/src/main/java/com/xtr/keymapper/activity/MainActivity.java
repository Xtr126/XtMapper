package com.xtr.keymapper.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xtr.keymapper.KeymapConfig;
import com.xtr.keymapper.R;
import com.xtr.keymapper.Server;
import com.xtr.keymapper.TouchPointer;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234;
    public static final int DEFAULT_PORT_2 = 6345;
    public static final String defaultProfile = "com.xtr.keymapper.default";
    public String currentProfile;
    public TouchPointer pointerOverlay;
    public Server server;

    private Button startOverlayButton;
    private Button startServerButton;
    private Button startInTerminal;
    private Button keymap;
    private Button configureButton;
    private Button infoButton;
    private TextView textView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        server = new Server(this);
        pointerOverlay = new TouchPointer(this);
        initButtons(); setupButtons();
    }

    private void setupButtons() {
        startServerButton.setOnClickListener(v -> startServer(true));
        startInTerminal.setOnClickListener(v -> startServer(false));
        startOverlayButton.setOnClickListener(v -> startService());
        keymap.setOnClickListener(v -> startEditor());
        configureButton.setOnClickListener
                (v -> startActivity(new Intent(this, InputDeviceSelector.class)));
        infoButton.setOnClickListener
                (v -> startActivity(new Intent(this, InfoActivity.class)));
    }

    private void initButtons(){
        startOverlayButton = findViewById(R.id.startPointer);
        startServerButton = findViewById(R.id.startServer);
        startInTerminal = findViewById(R.id.startServerM);
        keymap = findViewById(R.id.start_editor);
        configureButton = findViewById(R.id.config_pointer);
        infoButton = findViewById(R.id.about_button);
        initProfilesView();
    }

    private void initProfilesView(){
        LinearLayout profilesView = findViewById(R.id.profiles_view);
        ImageButton profilesButton = profilesView.findViewById(R.id.profiles);

        textView = findViewById(R.id.profileTextView);
        RecyclerView recyclerView = profilesView.findViewById(R.id.app_grid);
        RecyclerViewAdapter adapter = new RecyclerViewAdapter();

        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));

        Drawable profilesShow = AppCompatResources.getDrawable(this, R.drawable.ic_profiles_1);
        Drawable profilesHide = AppCompatResources.getDrawable(this, R.drawable.ic_profiles_2);
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
    }
    private void startService(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            pointerOverlay.open();
        }
    }

    public void setButtonActive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.purple_700)));
    }

    public void setButtonInactive(Button button){
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.grey)));
    }

    private void startEditor(){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            startActivity(new Intent(this, EditorUI.class));
        }
    }

    private void startServer(boolean autorun){
        checkOverlayPermission();
        if(Settings.canDrawOverlays(this)) {
            server.setupServer();
            if (autorun) {
                new Thread(server::startServer).start();
                startService();
            } else {
                server.updateCmdView1("run in adb shell:\n sh " + server.script_name);
            }
        }
    }

    private void checkOverlayPermission(){
        if (!Settings.canDrawOverlays(this)) {
            // send user to the device settings
            Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(myIntent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }



    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.RecyclerViewHolder> {

        private final ArrayList<RecyclerData> appsDataArrayList = new ArrayList<>();
        private ColorStateList defaultTint;

        private final ColorStateList selectedTint =
                ColorStateList.valueOf(getColor(R.color.purple_700));

        private View selectedView;

        private final SharedPreferences sharedPref =
                getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();


        public RecyclerViewAdapter() {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN, null);
            i.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> allApps = pm.queryIntentActivities(i, 0);

            Drawable drawable = AppCompatResources.getDrawable(MainActivity.this, R.drawable.ic_launcher_foreground);
            appsDataArrayList.add(
                    new RecyclerData(defaultProfile,
                            "Default",
                            drawable));
            for(ResolveInfo ri:allApps)
                appsDataArrayList.add(new RecyclerData(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm),
                        ri.activityInfo.loadIcon(pm)));

            currentProfile = KeymapConfig.getProfile(MainActivity.this);
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

                sharedPrefEditor.putString("profile", currentProfile);
                sharedPrefEditor.apply();

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
