package xtr.keymapper.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.KeymapConfig;
import xtr.keymapper.OnKeyEventListener;
import xtr.keymapper.R;
import xtr.keymapper.TouchPointer;
import xtr.keymapper.databinding.ActivityConfigureBinding;

public class InputDeviceSelector extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    // Spinner Drop down elements
    private final List<String> devices = new ArrayList<>();

    private ArrayAdapter<String> dataAdapter;
    private ActivityConfigureBinding binding;

    private KeymapConfig keymapConfig;
    private TouchPointer pointerOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isInMultiWindowMode()) setTheme(R.style.Theme_XtMapper);

        binding = ActivityConfigureBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Spinner click listener
        binding.spinner.setOnItemSelectedListener(this);

        // Creating adapter for spinner
        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);

        // attaching data adapter to spinner
        binding.spinner.setAdapter(dataAdapter);

        keymapConfig = new KeymapConfig(this);

        // Drop down layout style - list view
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        binding.endButton.setOnClickListener(v -> {
            try {
                pointerOverlay.sendSettingstoServer();
                pointerOverlay.mService.unregisterOnKeyEventListener(mOnKeyEventListener);
            } catch (RemoteException ignored) {
            }
            finish();
        });

        bindService(new Intent(this, TouchPointer.class), connection, BIND_AUTO_CREATE);
    }

    private void updateView(String s){
        runOnUiThread(() -> binding.textView.append(s + "\n"));
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        try {
            pointerOverlay.mService.unregisterOnKeyEventListener(mOnKeyEventListener);
        } catch (RemoteException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // On selecting a spinner item
        String item = parent.getItemAtPosition(position).toString();
        binding.textView2.setText(item);
        keymapConfig.device = item;
        keymapConfig.applySharedPrefs();
        // Showing selected spinner item
        Toast.makeText(parent.getContext(), item, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // TODO Auto-generated method stub
    }

    private final OnKeyEventListener mOnKeyEventListener = new OnKeyEventListener.Stub() {
        @Override
        public void onKeyEvent(String event) {
            getDevices(event);
        }
    };


    private void getDevices(String event)  {
        String[] input_event, data;
        String evdev;
        data = event.split(":"); // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
        evdev = data[0];
        input_event = data[1].split("\\s+");
        if(!input_event[1].equals("EV_SYN"))
            updateView(event);

        if( !devices.contains(evdev) && ! binding.textView2.getText().equals(evdev) )
            if (input_event[1].equals("EV_REL")) {
                devices.add(evdev);
                dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
                runOnUiThread(() -> binding.spinner.setAdapter(dataAdapter));
            }
    }
    /** Defines callbacks for service binding, passed to bindService() */
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            TouchPointer.TouchPointerBinder binder = (TouchPointer.TouchPointerBinder) service;
            pointerOverlay = binder.getService();
            try {
                pointerOverlay.mService.registerOnKeyEventListener(mOnKeyEventListener);
            } catch (RemoteException e) {
                InputDeviceSelector.this.finish();
            }
        }
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };
}