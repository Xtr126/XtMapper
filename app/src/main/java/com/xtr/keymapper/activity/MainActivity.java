package com.xtr.keymapper.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.xtr.keymapper.R;
import com.xtr.keymapper.Server;
import com.xtr.keymapper.TouchPointer;

public class MainActivity extends AppCompatActivity {
    public static final int DEFAULT_PORT = 6234;
    public static final int DEFAULT_PORT_2 = 6345;
    public TouchPointer pointerOverlay;
    public Server server;

    private Button startOverlayButton;
    private Button startServerButton;
    private Button startInTerminal;
    private Button keymap;
    private Button configureButton;
    private Button infoButton;

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
        RecyclerView recyclerView = profilesView.findViewById(R.id.app_grid);
        Drawable profilesShow = AppCompatResources.getDrawable(this, R.drawable.ic_profiles_1);
        Drawable profilesHide = AppCompatResources.getDrawable(this, R.drawable.ic_profiles_2);
        profilesButton.setOnClickListener(v -> {
            switch (recyclerView.getVisibility()) {
                case View.VISIBLE:{
                    recyclerView.setVisibility(View.GONE);
                    profilesButton.setForeground(profilesShow);
                    break;
                }
                case View.GONE:
                case View.INVISIBLE: {
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
        Server.killServer().start();
        pointerOverlay.hideCursor();
        super.onDestroy();
    }
}
